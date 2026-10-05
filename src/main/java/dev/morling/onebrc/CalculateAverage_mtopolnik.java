/*
 *  Copyright 2023 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dev.morling.onebrc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;

import static java.lang.ProcessBuilder.Redirect.PIPE;
import static java.util.Arrays.asList;

/**
 * Java 22+ version (tested on JDK 27) that no longer uses {@code sun.misc.Unsafe}, whose
 * memory-access methods are terminally deprecated (JEP 471 / JEP 498). Replacements:
 * <ul>
 * <li>memory-mapped input file: {@link MemorySegment} (Foreign Function &amp; Memory API, final in JDK 22)</li>
 * <li>per-thread stats hash table: on-heap {@code byte[]} accessed via
 * {@link MethodHandles#byteArrayViewVarHandle} (same 128-byte slot layout as before)</li>
 * </ul>
 */
public class CalculateAverage_mtopolnik {
    // Layouts for reading the memory-mapped input (unaligned, little endian byte order)
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final int STATS_TABLE_SIZE = 1 << 16;
    private static final int TABLE_INDEX_MASK = STATS_TABLE_SIZE - 1;
    private static final String MEASUREMENTS_TXT = "measurements.txt";

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && args[0].equals("--worker")) {
            calculate();
            System.out.close();
            return;
        }
        var curProcInfo = ProcessHandle.current().info();
        var cmdLine = new ArrayList<String>();
        cmdLine.add(curProcInfo.command().get());
        cmdLine.addAll(asList(curProcInfo.arguments().get()));
        cmdLine.add("--worker");
        new ProcessBuilder()
                .command(cmdLine)
                .inheritIO().redirectOutput(PIPE)
                .start()
                .getInputStream().transferTo(System.out);

    }

    static void calculate() throws Exception {
        final int chunkCount = Runtime.getRuntime().availableProcessors();
        final var results = new StationStats[chunkCount][];
        final var chunkStartOffsets = new long[chunkCount];
        try (var channel = FileChannel.open(Path.of(MEASUREMENTS_TXT), StandardOpenOption.READ)) {
            final long length = channel.size();
            // The global arena is never closed, so the mapping stays valid for all worker threads
            final MemorySegment file = channel.map(MapMode.READ_ONLY, 0, length, Arena.global());
            for (int i = 1; i < chunkStartOffsets.length; i++) {
                long start = length * i / chunkStartOffsets.length;
                while (start < length && file.get(BYTE, start) != '\n') {
                    start++;
                }
                chunkStartOffsets[i] = Math.min(start + 1, length);
            }
            var threads = new Thread[chunkCount];
            for (int i = 0; i < chunkCount; i++) {
                final long chunkStart = chunkStartOffsets[i];
                final long chunkLimit = (i + 1 < chunkCount) ? chunkStartOffsets[i + 1] : length;
                threads[i] = new Thread(new ChunkProcessor(file, chunkStart, chunkLimit, results, i));
            }
            for (var thread : threads) {
                thread.start();
            }
            for (var thread : threads) {
                thread.join();
            }
        }
        mergeSortAndPrint(results);
    }

    private static class ChunkProcessor implements Runnable {
        // The longest line is 107 bytes (100-byte name, ';', "-99.9", '\n') and the
        // word-at-a-time parsing reads up to 7 bytes beyond its end. Unsafe let us get
        // away with reading past the end of the mapped file; MemorySegment bounds-checks.
        // So the last SAFE_TAIL bytes of the file are processed from a zero-padded copy.
        private static final long SAFE_TAIL = 128;
        private static final long TAIL_PADDING = 16;

        private final MemorySegment file;
        private final long chunkStart;
        private final long chunkLimit;
        private final StationStats[][] results;
        private final int myIndex;

        private StatsAccessor stats;

        ChunkProcessor(MemorySegment file, long chunkStart, long chunkLimit, StationStats[][] results, int myIndex) {
            this.file = file;
            this.chunkStart = chunkStart;
            this.chunkLimit = chunkLimit;
            this.results = results;
            this.myIndex = myIndex;
        }

        @Override
        public void run() {
            try (Arena confinedArena = Arena.ofConfined()) {
                stats = new StatsAccessor(new byte[STATS_TABLE_SIZE * StatsAccessor.SIZEOF]);
                processChunk(confinedArena);
                exportResults();
            }
        }

        private void processChunk(Arena arena) {
            final long fileLength = file.byteSize();
            // Only a chunk that reaches the end of the file can read out of bounds
            final long fastLimit = chunkLimit == fileLength
                    ? Math.max(chunkStart, fileLength - SAFE_TAIL)
                    : chunkLimit;
            final long cursor = processLines(file, chunkStart, fastLimit);
            if (cursor < chunkLimit) {
                final long tailLength = chunkLimit - cursor;
                // Arena allocations are zero-initialized, so the padding is already zero
                final MemorySegment tail = arena.allocate(tailLength + TAIL_PADDING, Long.BYTES);
                MemorySegment.copy(file, cursor, tail, 0, tailLength);
                processLines(tail, 0, tailLength);
            }
        }

        // Processes whole lines starting at 'cursor' until the cursor reaches 'end';
        // returns the offset of the first unprocessed line.
        private long processLines(MemorySegment input, long cursor, long end) {
            long lastNameWord;
            while (cursor < end) {
                long nameStart = cursor;
                long nameWord0 = input.get(LONG, nameStart);
                long nameWord1 = 0;
                long matchBits = semicolonMatchBits(nameWord0);
                long hash;
                int nameLen;
                int temperature;
                if (matchBits != 0) {
                    nameLen = nameLen(matchBits);
                    nameWord0 = maskWord(nameWord0, matchBits);
                    cursor += nameLen;
                    long tempWord = input.get(LONG, cursor);
                    int dotPos = dotPos(tempWord);
                    temperature = parseTemperature(tempWord, dotPos);
                    cursor += (dotPos >> 3) + 3;
                    hash = hash(nameWord0);
                    if (stats.gotoName0(hash, nameWord0)) {
                        stats.observe(temperature);
                        continue;
                    }
                    lastNameWord = nameWord0;
                }
                else { // nameLen > 8
                    hash = hash(nameWord0);
                    nameWord1 = input.get(LONG, nameStart + Long.BYTES);
                    matchBits = semicolonMatchBits(nameWord1);
                    if (matchBits != 0) {
                        nameLen = Long.BYTES + nameLen(matchBits);
                        nameWord1 = maskWord(nameWord1, matchBits);
                        cursor += nameLen;
                        long tempWord = input.get(LONG, cursor);
                        int dotPos = dotPos(tempWord);
                        temperature = parseTemperature(tempWord, dotPos);
                        cursor += (dotPos >> 3) + 3;
                        if (stats.gotoName1(hash, nameWord0, nameWord1)) {
                            stats.observe(temperature);
                            continue;
                        }
                        lastNameWord = nameWord1;
                    }
                    else { // nameLen > 16
                        nameLen = 2 * Long.BYTES;
                        while (true) {
                            lastNameWord = input.get(LONG, nameStart + nameLen);
                            matchBits = semicolonMatchBits(lastNameWord);
                            if (matchBits != 0) {
                                nameLen += nameLen(matchBits);
                                lastNameWord = maskWord(lastNameWord, matchBits);
                                cursor += nameLen;
                                long tempWord = input.get(LONG, cursor);
                                int dotPos = dotPos(tempWord);
                                temperature = parseTemperature(tempWord, dotPos);
                                cursor += (dotPos >> 3) + 3;
                                break;
                            }
                            nameLen += Long.BYTES;
                        }
                    }
                }
                stats.gotoAndObserve(input, hash, nameStart, nameLen, nameWord0, nameWord1, lastNameWord, temperature);
            }
            return cursor;
        }

        private static final long BROADCAST_SEMICOLON = 0x3B3B3B3B3B3B3B3BL;
        private static final long BROADCAST_0x01 = 0x0101010101010101L;
        private static final long BROADCAST_0x80 = 0x8080808080808080L;

        private static long semicolonMatchBits(long word) {
            long diff = word ^ BROADCAST_SEMICOLON;
            return (diff - BROADCAST_0x01) & (~diff & BROADCAST_0x80);
        }

        // credit: artsiomkorzun
        private static long maskWord(long word, long matchBits) {
            long mask = matchBits ^ (matchBits - 1);
            return word & mask;
        }

        // credit: merykitty
        private static int dotPos(long word) {
            return Long.numberOfTrailingZeros(~word & 0x10101000);
        }

        // credit: merykitty
        private static int parseTemperature(long word, int dotPos) {
            final long signed = (~word << 59) >> 63;
            final long removeSignMask = ~(signed & 0xFF);
            final long digits = ((word & removeSignMask) << (28 - dotPos)) & 0x0F000F0F00L;
            final long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;
            return (int) ((absValue ^ signed) - signed);
        }

        private static int nameLen(long separator) {
            return (Long.numberOfTrailingZeros(separator) >>> 3) + 1;
        }

        private static long hash(long word) {
            return Long.rotateLeft(word * 0x51_7c_c1_b7_27_22_0a_95L, 17);
        }

        // Copies the results from native memory to Java heap and puts them into the results array.
        private void exportResults() {
            var exportedStats = new ArrayList<StationStats>(10_000);
            for (int i = 0; i < STATS_TABLE_SIZE; i++) {
                stats.gotoIndex(i);
                if (stats.nameLen() == 0) {
                    continue;
                }
                var sum = stats.sum();
                var count = stats.count();
                var min = stats.min();
                var max = stats.max();
                var name = stats.exportNameString();
                var stationStats = new StationStats();
                stationStats.name = name;
                stationStats.sum = sum;
                stationStats.count = count;
                stationStats.min = min;
                stationStats.max = max;
                exportedStats.add(stationStats);
            }
            StationStats[] exported = exportedStats.toArray(new StationStats[0]);
            Arrays.sort(exported);
            results[myIndex] = exported;
        }
    }

    static class StatsAccessor {
        private static final VarHandle LONG_VH = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
        private static final VarHandle INT_VH = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
        private static final VarHandle SHORT_VH = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

        static final int NAME_SLOT_SIZE = 104;
        static final int HASH_OFFSET = 0;
        static final int NAMELEN_OFFSET = HASH_OFFSET + Long.BYTES;
        static final int SUM_OFFSET = NAMELEN_OFFSET + Integer.BYTES;
        static final int COUNT_OFFSET = SUM_OFFSET + Integer.BYTES;
        static final int MIN_OFFSET = COUNT_OFFSET + Integer.BYTES;
        static final int MAX_OFFSET = MIN_OFFSET + Short.BYTES;
        static final int NAME_OFFSET = MAX_OFFSET + Short.BYTES;
        static final int SIZEOF = (NAME_OFFSET + NAME_SLOT_SIZE - 1) / 8 * 8 + 8;

        private final byte[] table;
        private int slotBase;

        StatsAccessor(byte[] table) {
            this.table = table;
        }

        void gotoIndex(int index) {
            slotBase = index * SIZEOF;
        }

        private boolean gotoName0(long hash, long nameWord0) {
            gotoIndex((int) (hash & TABLE_INDEX_MASK));
            return hash() == hash && nameWord0() == nameWord0;
        }

        private boolean gotoName1(long hash, long nameWord0, long nameWord1) {
            gotoIndex((int) (hash & TABLE_INDEX_MASK));
            return hash() == hash && nameWord0() == nameWord0 && nameWord1() == nameWord1;
        }

        long hash() {
            return (long) LONG_VH.get(table, slotBase + HASH_OFFSET);
        }

        int nameLen() {
            return (int) INT_VH.get(table, slotBase + NAMELEN_OFFSET);
        }

        int sum() {
            return (int) INT_VH.get(table, slotBase + SUM_OFFSET);
        }

        int count() {
            return (int) INT_VH.get(table, slotBase + COUNT_OFFSET);
        }

        short min() {
            return (short) SHORT_VH.get(table, slotBase + MIN_OFFSET);
        }

        short max() {
            return (short) SHORT_VH.get(table, slotBase + MAX_OFFSET);
        }

        int nameOffset() {
            return slotBase + NAME_OFFSET;
        }

        long nameWord0() {
            return (long) LONG_VH.get(table, nameOffset());
        }

        long nameWord1() {
            return (long) LONG_VH.get(table, nameOffset() + Long.BYTES);
        }

        String exportNameString() {
            return new String(table, nameOffset(), nameLen() - 1, StandardCharsets.UTF_8);
        }

        void setHash(long hash) {
            LONG_VH.set(table, slotBase + HASH_OFFSET, hash);
        }

        void setNameLen(int nameLen) {
            INT_VH.set(table, slotBase + NAMELEN_OFFSET, nameLen);
        }

        void setSum(int sum) {
            INT_VH.set(table, slotBase + SUM_OFFSET, sum);
        }

        void setCount(int count) {
            INT_VH.set(table, slotBase + COUNT_OFFSET, count);
        }

        void setMin(short min) {
            SHORT_VH.set(table, slotBase + MIN_OFFSET, min);
        }

        void setMax(short max) {
            SHORT_VH.set(table, slotBase + MAX_OFFSET, max);
        }

        void gotoAndObserve(
                            MemorySegment input, long hash, long nameStart, int nameLen, long nameWord0, long nameWord1,
                            long lastNameWord, int temperature) {
            int tableIndex = (int) (hash & TABLE_INDEX_MASK);
            while (true) {
                gotoIndex(tableIndex);
                if (hash() == hash && nameLen() == nameLen && nameEquals(
                        input, nameStart, nameLen, nameWord0, nameWord1, lastNameWord)) {
                    observe(temperature);
                    break;
                }
                if (nameLen() != 0) {
                    tableIndex = (tableIndex + 1) & TABLE_INDEX_MASK;
                    continue;
                }
                initialize(hash, nameLen, input, nameStart, temperature);
                break;
            }
        }

        void initialize(long hash, long nameLen, MemorySegment input, long nameStart, int temperature) {
            setHash(hash);
            setNameLen((int) nameLen);
            setSum(temperature);
            setCount(1);
            setMin((short) temperature);
            setMax((short) temperature);
            MemorySegment.copy(input, BYTE, nameStart, table, nameOffset(), (int) nameLen);
        }

        void observe(int temperature) {
            setSum(sum() + temperature);
            setCount(count() + 1);
            setMin((short) Integer.min(min(), temperature));
            setMax((short) Integer.max(max(), temperature));
        }

        private boolean nameEquals(
                                   MemorySegment input, long inputOff, long len, long inputWord1, long inputWord2,
                                   long lastInputWord) {
            final int statsOff = nameOffset();
            boolean mismatch1 = inputWord1 != (long) LONG_VH.get(table, statsOff);
            boolean mismatch2 = inputWord2 != (long) LONG_VH.get(table, statsOff + Long.BYTES);
            // Fix: the original only consulted these for names up to 16 bytes, so longer names
            // that differed within bytes 8..15 (and nowhere else) were wrongly treated as equal.
            if (mismatch1 | mismatch2) {
                return false;
            }
            if (len <= 2 * Long.BYTES) {
                return true;
            }
            int i = 2 * Long.BYTES;
            for (; i <= len - Long.BYTES; i += Long.BYTES) {
                if (input.get(LONG, inputOff + i) != (long) LONG_VH.get(table, statsOff + i)) {
                    return false;
                }
            }
            return i == len || lastInputWord == (long) LONG_VH.get(table, statsOff + i);
        }
    }

    private static void mergeSortAndPrint(StationStats[][] results) {
        var onFirst = true;
        System.out.print('{');
        var cursors = new int[results.length];
        var indexOfMin = 0;
        StationStats curr = null;
        int exhaustedCount;
        while (true) {
            exhaustedCount = 0;
            StationStats min = null;
            for (int i = 0; i < cursors.length; i++) {
                if (cursors[i] == results[i].length) {
                    exhaustedCount++;
                    continue;
                }
                StationStats candidate = results[i][cursors[i]];
                if (min == null || min.compareTo(candidate) > 0) {
                    indexOfMin = i;
                    min = candidate;
                }
            }
            if (exhaustedCount == cursors.length) {
                if (curr != null) {
                    if (!onFirst) {
                        System.out.print(", ");
                    }
                    System.out.print(curr);
                }
                break;
            }
            cursors[indexOfMin]++;
            if (curr == null) {
                curr = min;
            }
            else if (min.equals(curr)) {
                curr.sum += min.sum;
                curr.count += min.count;
                curr.min = Integer.min(curr.min, min.min);
                curr.max = Integer.max(curr.max, min.max);
            }
            else {
                if (onFirst) {
                    onFirst = false;
                }
                else {
                    System.out.print(", ");
                }
                System.out.print(curr);
                curr = min;
            }
        }
        System.out.println('}');
    }

    static class StationStats implements Comparable<StationStats> {
        String name;
        long sum;
        int count;
        int min;
        int max;

        @Override
        public String toString() {
            return String.format("%s=%.1f/%.1f/%.1f", name, min / 10.0, Math.round((double) sum / count) / 10.0, max / 10.0);
        }

        @Override
        public boolean equals(Object that) {
            return that.getClass() == StationStats.class && ((StationStats) that).name.equals(this.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public int compareTo(StationStats that) {
            return name.compareTo(that.name);
        }
    }
}

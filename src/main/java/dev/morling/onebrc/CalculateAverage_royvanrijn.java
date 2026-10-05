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

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Changelog:
 *
 * Initial submission:               62000 ms
 * Chunked reader:                   16000 ms
 * Optimized parser:                 13000 ms
 * Branchless methods:               11000 ms
 * Adding memory mapped files:       6500 ms (based on bjhara's submission)
 * Skipping string creation:         4700 ms
 * Custom hashmap...                 4200 ms
 * Added SWAR token checks:          3900 ms
 * Skipped String creation:          3500 ms (idea from kgonia)
 * Improved String skip:             3250 ms
 * Segmenting files:                 3150 ms (based on spullara's code)
 * Not using SWAR for EOL:           2850 ms
 * Inlining hash calculation:        2450 ms
 * Replacing branchless code:        2200 ms (sometimes we need to kill the things we love)
 * Added unsafe memory access:       1900 ms (keeping the long[] small and local)
 * Fixed bug, UNSAFE bytes String:   1850 ms
 * Separate hash from entries:       1550 ms
 * Various tweaks for Linux/cache    1550 ms (should/could make a difference on target machine)
 * Improved layout/predictability:   1400 ms
 * Delayed String creation again:    1350 ms
 * Remove writing to buffer:         1335 ms
 * Optimized collecting at the end:  1310 ms
 * Adding a lot of comments:         priceless
 * Changed to flyweight byte[]:      1290 ms (adds even more Unsafe, was initially slower, now faster)
 * More LOC now parallel:            1260 ms (moved more to processMemoryArea, recombining in ConcurrentHashMap)
 * Storing only the address:         1240 ms (this is now faster, tried before, was slower)
 * Unrolling scan-loop:              1200 ms (seems to help, perhaps even more on target machine)
 * Adding more readable reader:      1300 ms (scores got worse on target machine anyway)
 *
 * Using old x86 MacBook and perf:   3500 ms (different machine for testing)
 * Decided to rewrite loop for 16 b: 3050 ms
 * Small changes, limited heap:      2950 ms
 *
 * Java 27 port: sun.misc.Unsafe is gone. The flyweight byte[] entries are now accessed
 * through a byte-array-view VarHandle and the memory mapped file through a MemorySegment.
 * Because MemorySegment is bounds checked (Unsafe was not), the last few lines of the file
 * are parsed from a zero padded copy instead of reading past the end of the mapping.
 *
 * I have some instructions that could be removed, but faster with...
 *
 * Big thanks to Francesco Nigro, Thomas Wuerthinger, Quan Anh Mai and many others for ideas.
 *
 * Follow me at: @royvanrijn
 */
public class CalculateAverage_royvanrijn {

    private static final String FILE = "./measurements.txt";
    // private static final String FILE = "src/test/resources/samples/measurements-1.txt";

    // Twice the processors, smoothens things out.
    private static final int PROCESSORS = Runtime.getRuntime().availableProcessors();

    // Native byte order views, plain (non-atomic) access may be unaligned:
    private static final VarHandle LONG_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final VarHandle INT_VIEW = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());

    // Unaligned, native byte order access into the (mapped) file:
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    /**
     * Flyweight entry in a byte[], max 128 bytes.
     * <p>
     * byte: length
     * long: sum
     * int:  min
     * int:  max
     * int:  count
     * byte[]: cityname
     */
    // ------------------------------------------------------------------------
    // These are plain indexes into the byte[] (no more Unsafe.ARRAY_BYTE_BASE_OFFSET).
    private static final int ENTRY_LENGTH = 0;
    private static final int ENTRY_SUM = (ENTRY_LENGTH + Byte.BYTES);
    private static final int ENTRY_MIN = (ENTRY_SUM + Long.BYTES);
    private static final int ENTRY_MAX = (ENTRY_MIN + Integer.BYTES);
    private static final int ENTRY_COUNT = (ENTRY_MAX + Integer.BYTES);
    private static final int ENTRY_NAME = (ENTRY_COUNT + Integer.BYTES);

    private static final int ENTRY_BASESIZE_WHITESPACE = ENTRY_NAME + 7; // with enough empty bytes to fill a long
    // ------------------------------------------------------------------------
    private static final int PREMADE_MAX_SIZE = 1 << 5; // pre-initialize some entries in memory, keep them close
    private static final int PREMADE_ENTRIES = 512; // amount of pre-created entries we should use
    private static final int TABLE_SIZE = 1 << 19; // large enough for the contest.
    private static final int TABLE_MASK = (TABLE_SIZE - 1);

    // The parser reads whole longs and can look a few bytes past the end of a line. A line
    // starting more than this many bytes before the end of the file can never reach past it
    // (max line is 107 bytes, max over-read is below 8), so only the last bit needs padding.
    private static final int TAIL_MARGIN = 256;
    private static final int TAIL_PADDING = 32;

    // Idea of thomaswue, don't wait for slow unmap:
    private static void spawnWorker() throws IOException {
        ProcessHandle.Info info = ProcessHandle.current().info();
        ArrayList<String> workerCommand = new ArrayList<>();
        info.command().ifPresent(workerCommand::add);
        info.arguments().ifPresent(args -> workerCommand.addAll(Arrays.asList(args)));
        workerCommand.add("--worker");
        new ProcessBuilder()
                .command(workerCommand)
                .inheritIO()
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start()
                .getInputStream()
                .transferTo(System.out);
    }

    public static void main(String[] args) throws Exception {

        if (args.length == 0 || !("--worker".equals(args[0]))) {
            spawnWorker();
            return;
        }

        // Calculate input segments.
        final long fileSize;
        final MemorySegment file;
        try (FileChannel fileChannel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ)) {
            fileSize = fileChannel.size();
            // The mapping stays valid after the channel is closed (global arena, never unmapped):
            file = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global());
        }
        final long segmentSize = (fileSize + PROCESSORS - 1) / PROCESSORS;

        final Thread[] parallelThreads = new Thread[PROCESSORS - 1];

        // This is where the entries will land:
        final ConcurrentHashMap<String, byte[]> measurements = new ConcurrentHashMap<>(1 << 10);

        // We create separate threads for twice the amount of processors.
        // (offsets are relative to the start of the mapped file)
        long lastOffset = 0;
        for (int i = 0; i < PROCESSORS - 1; ++i) {

            final long from = lastOffset;
            final long to = Math.min(fileSize, from + segmentSize);

            final Thread thread = new Thread(() -> {
                // The actual work is done here:
                final byte[][] table = processMemoryArea(file, from, to, from == 0);

                for (byte[] entry : table) {
                    if (entry != null) {
                        measurements.merge(entryToName(entry), entry, CalculateAverage_royvanrijn::mergeEntry);
                    }
                }
            });
            thread.start(); // start a.s.a.p.
            parallelThreads[i] = thread;
            lastOffset = to;
        }

        // Use the current thread for the part of memory (with a single processor that is the whole file,
        // which starts on a line boundary, so nothing should be skipped):
        final byte[][] table = processMemoryArea(file, lastOffset, fileSize, lastOffset == 0);

        for (byte[] entry : table) {
            if (entry != null) {
                measurements.merge(entryToName(entry), entry, CalculateAverage_royvanrijn::mergeEntry);
            }
        }
        // Wait for all threads to finish:
        for (Thread thread : parallelThreads) {
            // Can we implement work-stealing? Not sure how...
            thread.join();
        }

        // If we don't reach start of file,
        System.out.print("{" +
                measurements.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + '=' + entryValuesToString(entry.getValue()))
                        .collect(Collectors.joining(", ")));
        System.out.println("}");

        System.out.close(); // close the stream to stop
    }

    // ------------------------------------------------------------------------
    // Tiny accessors replacing the Unsafe calls on the flyweight byte[] entries:
    private static long getLong(final byte[] entry, final int index) {
        return (long) LONG_VIEW.get(entry, index);
    }

    private static void putLong(final byte[] entry, final int index, final long value) {
        LONG_VIEW.set(entry, index, value);
    }

    private static int getInt(final byte[] entry, final int index) {
        return (int) INT_VIEW.get(entry, index);
    }

    private static void putInt(final byte[] entry, final int index, final int value) {
        INT_VIEW.set(entry, index, value);
    }
    // ------------------------------------------------------------------------

    private static byte[] fillEntry(final byte[] entry, final MemorySegment source, final long fromOffset, final int entryLength, final int temp, final long readBuffer1,
                                    final long readBuffer2) {
        putLong(entry, ENTRY_SUM, temp);
        putInt(entry, ENTRY_MIN, temp);
        putInt(entry, ENTRY_MAX, temp);
        putInt(entry, ENTRY_COUNT, 1);
        entry[ENTRY_LENGTH] = (byte) entryLength;
        // entryLength is a multiple of 16, so this is a whole number of longs:
        for (int i = 0; i < entryLength - 16; i += 8) {
            putLong(entry, ENTRY_NAME + i, source.get(LONG, fromOffset + i));
        }
        putLong(entry, ENTRY_NAME + entryLength - 16, readBuffer1);
        putLong(entry, ENTRY_NAME + entryLength - 8, readBuffer2);
        return entry;
    }

    private static byte[] fillEntry16(final byte[] entry, final int entryLength, final int temp, final long readBuffer1, final long readBuffer2) {
        putLong(entry, ENTRY_SUM, temp);
        putInt(entry, ENTRY_MIN, temp);
        putInt(entry, ENTRY_MAX, temp);
        putInt(entry, ENTRY_COUNT, 1);
        entry[ENTRY_LENGTH] = (byte) entryLength;
        putLong(entry, ENTRY_NAME + entryLength - 16, readBuffer1);
        putLong(entry, ENTRY_NAME + entryLength - 8, readBuffer2);
        return entry;
    }

    public static void updateEntry(final byte[] entry, final int temp) {

        int entryMin = getInt(entry, ENTRY_MIN);
        int entryMax = getInt(entry, ENTRY_MAX);
        long entrySum = getLong(entry, ENTRY_SUM) + temp;
        int entryCount = getInt(entry, ENTRY_COUNT) + 1;

        if (temp < entryMin) {
            putInt(entry, ENTRY_MIN, temp);
        }
        else if (temp > entryMax) {
            putInt(entry, ENTRY_MAX, temp);
        }
        putInt(entry, ENTRY_COUNT, entryCount);
        putLong(entry, ENTRY_SUM, entrySum);
    }

    public static byte[] mergeEntry(final byte[] entry, final byte[] merge) {

        long sum = getLong(merge, ENTRY_SUM);
        final int mergeMin = getInt(merge, ENTRY_MIN);
        final int mergeMax = getInt(merge, ENTRY_MAX);
        int count = getInt(merge, ENTRY_COUNT);

        sum += getLong(entry, ENTRY_SUM);
        count += getInt(entry, ENTRY_COUNT);

        int entryMin = getInt(entry, ENTRY_MIN);
        int entryMax = getInt(entry, ENTRY_MAX);
        entryMin = Math.min(entryMin, mergeMin);
        entryMax = Math.max(entryMax, mergeMax);
        putInt(entry, ENTRY_MIN, entryMin);
        putInt(entry, ENTRY_MAX, entryMax);

        putLong(entry, ENTRY_SUM, sum);
        putInt(entry, ENTRY_COUNT, count);
        return entry;
    }

    private static String entryToName(final byte[] entry) {
        // Get the length from the entry:
        int length = entry[ENTRY_LENGTH];

        // Create a new String straight from the entry (the zero padding is trimmed again):
        return new String(entry, ENTRY_NAME, length, StandardCharsets.UTF_8).trim();
    }

    private static String entryValuesToString(final byte[] entry) {
        return (round(getInt(entry, ENTRY_MIN))
                + "/" +
                round((1.0 * getLong(entry, ENTRY_SUM)) /
                        getInt(entry, ENTRY_COUNT))
                + "/" +
                round(getInt(entry, ENTRY_MAX)));
    }

    // Print a piece of memory:
    // For debug.
    private static String printMemory(final long value, int length) {
        String result = "";
        for (int i = 0; i < length; i++) {
            result += (char) ((value >> (i << 3)) & 0xFF);
        }
        return result;
    }

    private static double round(final double value) {
        return Math.round(value) / 10.0;
    }

    private static final class Reader {

        private final MemorySegment source;
        private long ptr;
        private long readBuffer1;
        private long readBuffer2;

        private long hash;
        private long entryStart;
        private int entryLength; // in bytes rounded to nearest 16

        private final long endAddress;

        /**
         * @param isLineStart true if startOffset is known to be the first byte of a line
         */
        Reader(final MemorySegment source, final long startOffset, final long endOffset, final boolean isLineStart) {

            this.source = source;
            this.ptr = startOffset;
            this.endAddress = endOffset;

            // Adjust start to next delimiter:
            if (!isLineStart) {
                ptr--;
                while (ptr < endAddress) {
                    if (source.get(BYTE, ptr++) == '\n') {
                        break;
                    }
                }
            }
        }

        private void processStart() {
            hash = 0;
            entryStart = ptr;
            entryLength = 0;
        }

        private static final long DELIMITER_MASK = 0x3B3B3B3B3B3B3B3BL;

        private boolean readNext() {

            long lastRead = source.get(LONG, ptr);

            entryLength += 16;

            // Find delimiter and create mask for long1
            long comparisonResult1 = (lastRead ^ DELIMITER_MASK);
            long highBitMask1 = (comparisonResult1 - 0x0101010101010101L) & (~comparisonResult1 & 0x8080808080808080L);

            boolean noContent1 = highBitMask1 == 0;
            long mask1 = noContent1 ? 0 : ~((highBitMask1 >>> 7) - 1);
            int position1 = noContent1 ? 0 : 1 + (Long.numberOfTrailingZeros(highBitMask1) >> 3);

            readBuffer1 = lastRead & ~mask1;
            hash ^= readBuffer1;

            int delimiter1 = position1 == 0 ? 0 : position1; // not nnecessary, but faster?

            if (delimiter1 != 0) {
                hash ^= hash >> 32;
                readBuffer2 = 0;
                ptr += delimiter1;
                return false;
            }

            lastRead = source.get(LONG, ptr + 8);

            // Repeat for long2
            long comparisonResult2 = (lastRead ^ DELIMITER_MASK);
            long highBitMask2 = (comparisonResult2 - 0x0101010101010101L) & (~comparisonResult2 & 0x8080808080808080L);
            boolean noContent2 = highBitMask2 == 0;
            long mask2 = noContent2 ? 0 : ~((highBitMask2 >>> 7) - 1);
            int position2 = noContent2 ? 0 : 1 + (Long.numberOfTrailingZeros(highBitMask2) >> 3);

            // Apply masks
            readBuffer2 = lastRead & ~mask2;
            hash ^= readBuffer2;

            int delimiter2 = position2 == 0 ? 0 : position2 + 8; // not necessary, but faster?

            hash ^= hash >> 32;

            if (delimiter2 != 0) {
                ptr += delimiter2;
                return false;
            }
            ptr += 16;
            return true;
        }

        private int processEndAndGetTemperature() {
            finalizeHash();
            return readTemperature();
        }

        private void finalizeHash() {
            hash ^= hash >> 17; // extra entropy
        }

        private static final long DOT_BITS = 0x10101000;
        private static final long MAGIC_MULTIPLIER = (100 * 0x1000000 + 10 * 0x10000 + 1);

        // Awesome idea of merykitty:
        private int readTemperature() {
            // This is the number part: X.X, -X.X, XX.x or -XX.X
            final long numberBytes = source.get(LONG, ptr);
            final long invNumberBytes = ~numberBytes;

            final int dotPosition = Long.numberOfTrailingZeros(invNumberBytes & DOT_BITS);

            // Calculates the sign
            final long signed = (invNumberBytes << 59) >> 63;
            final int min28 = (dotPosition ^ 0b11100);
            final long minusFilter = ~(signed & 0xFF);
            // Use the pre-calculated decimal position to adjust the values
            final long digits = ((numberBytes & minusFilter) << min28) & 0x0F000F0F00L;

            // Update the pointer here, bit awkward, but we have all the data
            ptr += (dotPosition >> 3) + 3;

            // Multiply by a magic (100 * 0x1000000 + 10 * 0x10000 + 1), to get the result
            final long absValue = ((digits * MAGIC_MULTIPLIER) >>> 32) & 0x3FF;
            // And perform abs()
            return (int) ((absValue + signed) ^ signed); // non-patented method of doing the same trick
        }

        private boolean matches(final byte[] entry) {
            int step = 0;
            for (; step < entryLength - 16;) {
                if (compare(source, entryStart + step, entry, ENTRY_NAME + step)) {
                    return false;
                }
                step += 8;
            }
            if (compare(readBuffer1, entry, ENTRY_NAME + step)) {
                return false;
            }
            step += 8;
            if (compare(readBuffer2, entry, ENTRY_NAME + step)) {
                return false;
            }
            return true;
        }

        private boolean matches16(final byte[] entry) {
            if (compare(readBuffer1, entry, ENTRY_NAME)) {
                return false;
            }
            if (compare(readBuffer2, entry, ENTRY_NAME + 8)) {
                return false;
            }
            return true;
        }
    }

    /**
     * The hash table of one worker, plus the pool of pre-allocated entries.
     */
    private static final class Table {
        private final byte[][] slots = new byte[TABLE_SIZE][];
        private final byte[][] preConstructedEntries = new byte[PREMADE_ENTRIES][ENTRY_BASESIZE_WHITESPACE + PREMADE_MAX_SIZE];
        private int entryCount = 0;
    }

    private static byte[][] processMemoryArea(final MemorySegment file, final long startOffset, final long endOffset, final boolean isFileStart) {

        final Table table = new Table();
        final long fileSize = file.byteSize();

        // Fast path: whole lines that can't make us read beyond the end of the mapping.
        final long safeLimit = Math.min(endOffset, fileSize - TAIL_MARGIN);
        final Reader reader = new Reader(file, startOffset, endOffset, isFileStart);
        processLines(reader, table, safeLimit);

        // Slow path (at most a few lines, only for the worker that owns the end of the file):
        // parse the remainder from a zero padded copy, so the long reads can't go out of bounds.
        if (reader.ptr < endOffset) {
            final long tailStart = reader.ptr;
            final MemorySegment tail = MemorySegment.ofArray(new byte[(int) (fileSize - tailStart) + TAIL_PADDING]);
            MemorySegment.copy(file, tailStart, tail, 0, fileSize - tailStart);
            final long tailEnd = endOffset - tailStart;
            processLines(new Reader(tail, 0, tailEnd, true), table, tailEnd);
        }
        return table.slots;
    }

    private static void processLines(final Reader reader, final Table table, final long limit) {

        final byte[][] slots = table.slots;

        byte[] entry;

        // Find the correct starting position
        while (reader.ptr < limit) {

            reader.processStart();

            if (!reader.readNext()) {
                // First 16 bytes:

                int temperature = reader.processEndAndGetTemperature();

                // Find or insert the entry:
                int index = (int) (reader.hash & TABLE_MASK);
                while (true) {
                    entry = slots[index];
                    if (entry == null) {
                        byte[] entryBytes = (table.entryCount < PREMADE_ENTRIES) ? table.preConstructedEntries[table.entryCount++]
                                : new byte[ENTRY_BASESIZE_WHITESPACE + 16]; // with enough room
                        slots[index] = fillEntry16(entryBytes, 16, temperature, reader.readBuffer1, reader.readBuffer2);
                        break;
                    }
                    else if (reader.matches16(entry)) {
                        updateEntry(entry, temperature);
                        break;
                    }
                    else {
                        // Move to the next index
                        index = (index + 1) & TABLE_MASK;
                    }
                }
                continue;
            }
            while (reader.readNext())
                ;

            int temperature = reader.processEndAndGetTemperature();

            // Find or insert the entry:
            int index = (int) (reader.hash & TABLE_MASK);
            while (true) {
                entry = slots[index];
                if (entry == null) {
                    int length = reader.entryLength;
                    byte[] entryBytes = (length < PREMADE_MAX_SIZE && table.entryCount < PREMADE_ENTRIES) ? table.preConstructedEntries[table.entryCount++]
                            : new byte[ENTRY_BASESIZE_WHITESPACE + length]; // with enough room
                    slots[index] = fillEntry(entryBytes, reader.source, reader.entryStart, length, temperature, reader.readBuffer1, reader.readBuffer2);
                    break;
                }
                else if (reader.matches(entry)) {
                    updateEntry(entry, temperature);
                    break;
                }
                else {
                    // Move to the next index
                    index = (index + 1) & TABLE_MASK;
                }
            }
        }
    }

    // Returns true if the 8 bytes in the file differ from the 8 bytes in the entry:
    private static boolean compare(final MemorySegment source, final long offset1, final byte[] entry, final int index2) {
        return source.get(LONG, offset1) != getLong(entry, index2);
    }

    private static boolean compare(final long value1, final byte[] entry, final int index2) {
        return value1 != getLong(entry, index2);
    }

    /*
     * `___` ___ ___ _ ___` ` ___ ` _ ` _ ` _` ___
     * / ` \| _ \ __| \| \ \ / /_\ | | | | | | __|
     * | () | _ / __|| . |\ V / _ \| |_| |_| | ._|
     * \___/|_| |___|_|\_| \_/_/ \_\___|\___/|___|
     * ---------------- BETTER SOFTWARE, FASTER --
     *
     * https://www.openvalue.eu/
     */
}

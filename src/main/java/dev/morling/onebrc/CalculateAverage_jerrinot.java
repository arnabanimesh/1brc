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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel.MapMode;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * I figured out it would be very hard to win the main competition of the One Billion Rows Challenge.
 * but I think this code has a good chance to win a special prize for the Ugliest Solution ever! :)
 *
 * Anyway, if you can make sense out of not exactly idiomatic Java code, and you enjoy pushing performance limits
 * then QuestDB - the fastest open-source time-series database - is hiring: https://questdb.io/careers/core-database-engineer/
 * <p>
 * <b>Credit</b>
 * <p>
 * I stand on shoulders of giants. I wouldn't be able to code this without analyzing and borrowing from solutions of others.
 * People who helped me the most:
 * <ul>
 * <li>Thomas Wuerthinger (thomaswue): The munmap() trick and work-stealing. In both cases, I shameless copy-pasted their code.
 *     Including SWAR for detecting new lines. Thomas also gave me helpful hints on how to detect register spilling issues.</li>
 * <li>Quan Anh Mai (merykitty): I borrowed their phenomenal branch-free parser.</li>
 * <li>Marko Topolnik (mtopolnik): I use a hashing function I saw in his code. It seems the produce good quality hashes
 *     and it's next-level in speed. Marko joined the challenge before me and our discussions made me to join too!</li>
 * <li>Van Phu DO (abeobk): I saw the idea with simple lookup tables instead of complicated bit-twiddling in their code first.</li>
 * <li>Roy van Rijn (royvanrijn): I borrowed their SWAR code and initially their hash code impl</li>
 * <li>Francesco Nigro (franz1981): For our online discussions about performance. Both before and during this challenge.
 *     Francesco gave me the idea to check register spilling.</li>
 * </ul>
 * <p>
 * <b>JDK 26+ port</b>
 * <p>
 * sun.misc.Unsafe memory access throws UnsupportedOperationException by default since JDK 26 (JEP 498 / JEP 471)
 * and the methods are going away entirely. All raw memory access now goes through {@link Raw}, which is backed by
 * the Foreign Function &amp; Memory API (JEP 454, final since JDK 22). The algorithm itself is unchanged.
 * <p>
 * {@link Raw} needs one <i>restricted</i> FFM call, so the worker JVM is launched with
 * {@code --enable-native-access=ALL-UNNAMED} (see {@link #spawnWorker()}). If you run with {@code --worker} directly,
 * pass that flag yourself (or add {@code Enable-Native-Access: ALL-UNNAMED} to the jar manifest).
 */
public class CalculateAverage_jerrinot {
    private static final String MEASUREMENTS_TXT = "measurements.txt";
    // todo: with hyper-threading enable we would be better of with availableProcessors / 2;
    // todo: validate the testing env. params.
    private static final int EXTRA_THREAD_COUNT = Runtime.getRuntime().availableProcessors() - 1;
    // private static final int THREAD_COUNT = 1;

    private static final long SEPARATOR_PATTERN = 0x3B3B3B3B3B3B3B3BL;
    private static final long NEW_LINE_PATTERN = 0x0A0A0A0A0A0A0A0AL;
    private static final int SEGMENT_SIZE = 4 * 1024 * 1024;

    // credits for the idea with lookup tables instead of bit-shifting: abeobk
    private static final long[] HASH_MASKS = new long[]{
            0x0000000000000000L, // semicolon is the first char
            0x00000000000000ffL,
            0x000000000000ffffL,
            0x0000000000ffffffL,
            0x00000000ffffffffL,
            0x000000ffffffffffL,
            0x0000ffffffffffffL,
            0x00ffffffffffffffL, // semicolon is the last char
            0xffffffffffffffffL // there is no semicolon at all
    };

    private static final long[] ADVANCE_MASKS = new long[]{
            0x0000000000000000L,
            0x0000000000000000L,
            0x0000000000000000L,
            0x0000000000000000L,
            0x0000000000000000L,
            0x0000000000000000L,
            0x0000000000000000L,
            0x0000000000000000L,
            0xffffffffffffffffL,
    };

    /**
     * Raw absolute-address memory access, a drop-in replacement for the handful of sun.misc.Unsafe methods this
     * solution used. Backed by a single zero-based segment spanning the whole address space, so plain
     * {@code long} addresses (file mapping, off-heap tables) keep working exactly as before.
     * <p>
     * All layouts are the <i>_UNALIGNED</i> variants: the hash-table entries contain 8-byte fields at 4-byte
     * offsets (e.g. {@code sum} at +12), and the parser reads 8-byte words at arbitrary byte positions. The
     * aligned layouts would throw on those.
     */
    private static final class Raw {
        private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;
        private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED;
        private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

        // MemorySegment::reinterpret is a restricted method: needs --enable-native-access=ALL-UNNAMED.
        @SuppressWarnings("restricted")
        private static final MemorySegment ALL = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

        static long getLong(long address) {
            return ALL.get(LONG, address);
        }

        static int getInt(long address) {
            return ALL.get(INT, address);
        }

        static byte getByte(long address) {
            return ALL.get(BYTE, address);
        }

        static void putLong(long address, long value) {
            ALL.set(LONG, address, value);
        }

        static void putInt(long address, int value) {
            ALL.set(INT, address, value);
        }

        static void copyMemory(long srcAddress, long dstAddress, long bytes) {
            MemorySegment.copy(ALL, srcAddress, ALL, dstAddress, bytes);
        }
    }

    public static void main(String[] args) throws Exception {
        // credits for spawning new workers: thomaswue
        if (args.length == 0 || !("--worker".equals(args[0]))) {
            spawnWorker();
            return;
        }
        calculate();
    }

    private static void spawnWorker() throws IOException {
        ProcessHandle.Info info = ProcessHandle.current().info();
        ArrayList<String> workerCommand = new ArrayList<>();
        info.command().ifPresent(workerCommand::add);
        // JVM options must precede the main class / -jar, so this goes right after the java executable.
        // Needed by Raw (MemorySegment::reinterpret) to avoid a warning today and a failure in a future JDK.
        workerCommand.add("--enable-native-access=ALL-UNNAMED");
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

    static void calculate() throws Exception {
        final File file = new File(MEASUREMENTS_TXT);
        final long length = file.length();
        try (var raf = new RandomAccessFile(file, "r")) {
            long fileStart = raf.getChannel().map(MapMode.READ_ONLY, 0, length, Arena.global()).address();
            long fileEnd = fileStart + length;
            var globalCursor = new AtomicLong(fileStart);

            Processor[] processors = new Processor[EXTRA_THREAD_COUNT];
            Thread[] threads = new Thread[EXTRA_THREAD_COUNT];

            for (int i = 0; i < EXTRA_THREAD_COUNT; i++) {
                Processor processor = new Processor(fileStart, fileEnd, globalCursor);
                Thread thread = new Thread(processor);
                processors[i] = processor;
                threads[i] = thread;
                thread.start();
            }

            Processor processor = new Processor(fileStart, fileEnd, globalCursor);
            processor.run();

            var accumulator = new TreeMap<String, StationStats>();
            processor.accumulateStatus(accumulator);

            for (int i = 0; i < EXTRA_THREAD_COUNT; i++) {
                Thread t = threads[i];
                t.join();
                processors[i].accumulateStatus(accumulator);
            }

            printResults(accumulator);
        }
    }

    private static void printResults(TreeMap<String, StationStats> accumulator) {
        var sb = new StringBuilder(10000);
        boolean first = true;
        for (Map.Entry<String, StationStats> statsEntry : accumulator.entrySet()) {
            if (first) {
                sb.append("{");
                first = false;
            }
            else {
                sb.append(", ");
            }
            var value = statsEntry.getValue();
            var name = statsEntry.getKey();
            int min = value.min;
            int max = value.max;
            int count = value.count;
            long sum2 = value.sum;
            sb.append(String.format("%s=%.1f/%.1f/%.1f", name, min / 10.0, Math.round((double) sum2 / count) / 10.0, max / 10.0));
        }
        sb.append('}');
        System.out.println(sb);
        System.out.close();
    }

    public static int ceilPow2(int i) {
        i--;
        i |= i >> 1;
        i |= i >> 2;
        i |= i >> 4;
        i |= i >> 8;
        i |= i >> 16;
        return i + 1;
    }

    private static class Processor implements Runnable {
        private static final int MAX_UNIQUE_KEYS = 10000;
        private static final int MAPS_SLOT_COUNT = ceilPow2(MAX_UNIQUE_KEYS);
        private static final int STATION_MAX_NAME_BYTES = 104;

        private static final long MAP_COUNT_OFFSET = 0;
        private static final long MAP_MIN_OFFSET = 4;
        private static final long MAP_MAX_OFFSET = 8;
        private static final long MAP_SUM_OFFSET = 12;
        private static final long MAP_LEN_OFFSET = 20;
        private static final long SLOW_MAP_NAME_OFFSET = 24;

        // private int longestChain = 0;

        private static final int SLOW_MAP_ENTRY_SIZE_BYTES = Integer.BYTES // count // 0
                + Integer.BYTES // min // +4
                + Integer.BYTES // max // +8
                + Long.BYTES // sum // +12
                + Integer.BYTES // station name len // +20
                + Long.BYTES; // station name ptr // 24

        private static final long FAST_MAP_NAME_PART1 = 24;
        private static final long FAST_MAP_NAME_PART2 = 32;

        private static final int FAST_MAP_ENTRY_SIZE_BYTES = Integer.BYTES // count // 0
                + Integer.BYTES // min // +4
                + Integer.BYTES // max // +8
                + Long.BYTES // sum // +12
                + Integer.BYTES // station name len // +20
                + Long.BYTES // station name part 1 // 24
                + Long.BYTES; // station name part 2 // 32

        private static final int SLOW_MAP_SIZE_BYTES = MAPS_SLOT_COUNT * SLOW_MAP_ENTRY_SIZE_BYTES;
        private static final int FAST_MAP_SIZE_BYTES = MAPS_SLOT_COUNT * FAST_MAP_ENTRY_SIZE_BYTES;
        private static final int SLOW_MAP_MAP_NAMES_BYTES = MAX_UNIQUE_KEYS * STATION_MAX_NAME_BYTES;
        private static final int MAP_MASK = MAPS_SLOT_COUNT - 1;
        private final AtomicLong globalCursor;

        private long slowMap;
        private long slowMapNamesPtr;
        private long cursorA;
        private long endA;
        private long cursorB;
        private long endB;
        private HashMap<String, CalculateAverage_jerrinot.StationStats> stats = new HashMap<>(1000);
        private final long fileEnd;
        private final long fileStart;

        // credit: merykitty
        private long parseAndStoreTemperature(long startCursor, long baseEntryPtr, long word) {
            long countPtr = baseEntryPtr + MAP_COUNT_OFFSET;
            int cnt = Raw.getInt(countPtr);
            Raw.putInt(countPtr, cnt + 1);

            long minPtr = baseEntryPtr + MAP_MIN_OFFSET;
            long maxPtr = baseEntryPtr + MAP_MAX_OFFSET;
            long sumPtr = baseEntryPtr + MAP_SUM_OFFSET;

            int min = Raw.getInt(minPtr);
            int max = Raw.getInt(maxPtr);
            long sum = Raw.getLong(sumPtr);

            final long negateda = ~word;
            final int dotPos = Long.numberOfTrailingZeros(negateda & 0x10101000);
            final long signed = (negateda << 59) >> 63;
            final long removeSignMask = ~(signed & 0xFF);
            final long digits = ((word & removeSignMask) << (28 - dotPos)) & 0x0F000F0F00L;
            final long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;
            final int temperature = (int) ((absValue ^ signed) - signed);
            sum += temperature;
            Raw.putLong(sumPtr, sum);

            if (temperature > max) {
                Raw.putInt(maxPtr, temperature);
            }
            if (temperature < min) {
                Raw.putInt(minPtr, temperature);
            }
            return startCursor + (dotPos / 8) + 3;
        }

        private static long getDelimiterMask(final long word) {
            // credit royvanrijn
            final long match = word ^ SEPARATOR_PATTERN;
            return (match - 0x0101010101010101L) & (~match & 0x8080808080808080L);
        }

        void accumulateStatus(TreeMap<String, CalculateAverage_jerrinot.StationStats> accumulator) {
            for (Map.Entry<String, CalculateAverage_jerrinot.StationStats> entry : stats.entrySet()) {
                String name = entry.getKey();
                CalculateAverage_jerrinot.StationStats localStats = entry.getValue();

                CalculateAverage_jerrinot.StationStats globalStats = accumulator.get(name);
                if (globalStats == null) {
                    accumulator.put(name, localStats);
                }
                else {
                    accumulator.put(name, globalStats.mergeWith(localStats));
                }
            }
        }

        Processor(long fileStart, long fileEnd, AtomicLong globalCursor) {
            this.globalCursor = globalCursor;
            this.fileEnd = fileEnd;
            this.fileStart = fileStart;
        }

        private void transferToHeap(long fastMap) {
            for (long baseAddress = slowMap; baseAddress < slowMap + SLOW_MAP_SIZE_BYTES; baseAddress += SLOW_MAP_ENTRY_SIZE_BYTES) {
                long len = Raw.getInt(baseAddress + MAP_LEN_OFFSET);
                if (len == 0) {
                    continue;
                }
                byte[] nameArr = new byte[(int) len];
                long baseNameAddr = Raw.getLong(baseAddress + SLOW_MAP_NAME_OFFSET);
                for (int i = 0; i < len; i++) {
                    nameArr[i] = Raw.getByte(baseNameAddr + i);
                }
                String name = new String(nameArr);
                int min = Raw.getInt(baseAddress + MAP_MIN_OFFSET);
                int max = Raw.getInt(baseAddress + MAP_MAX_OFFSET);
                int count = Raw.getInt(baseAddress + MAP_COUNT_OFFSET);
                long sum = Raw.getLong(baseAddress + MAP_SUM_OFFSET);

                stats.put(name, new CalculateAverage_jerrinot.StationStats(min, max, count, sum));
            }

            for (long baseAddress = fastMap; baseAddress < fastMap + FAST_MAP_SIZE_BYTES; baseAddress += FAST_MAP_ENTRY_SIZE_BYTES) {
                long len = Raw.getInt(baseAddress + MAP_LEN_OFFSET);
                if (len == 0) {
                    continue;
                }
                byte[] nameArr = new byte[(int) len];
                long baseNameAddr = baseAddress + FAST_MAP_NAME_PART1;
                for (int i = 0; i < len; i++) {
                    nameArr[i] = Raw.getByte(baseNameAddr + i);
                }
                String name = new String(nameArr);
                int min = Raw.getInt(baseAddress + MAP_MIN_OFFSET);
                int max = Raw.getInt(baseAddress + MAP_MAX_OFFSET);
                int count = Raw.getInt(baseAddress + MAP_COUNT_OFFSET);
                long sum = Raw.getLong(baseAddress + MAP_SUM_OFFSET);

                var v = stats.get(name);
                if (v == null) {
                    stats.put(name, new CalculateAverage_jerrinot.StationStats(min, max, count, sum));
                }
                else {
                    stats.put(name, new CalculateAverage_jerrinot.StationStats(Math.min(v.min, min), Math.max(v.max, max), v.count + count, v.sum + sum));
                }
            }
        }

        private void doOne(long cursor, long end, long fastMap) {
            while (cursor < end) {
                // it seems that when pulling just from a single chunk
                // then bit-twiddling is faster than lookup tables
                // hypothesis: when processing multiple things at once then LOAD latency is partially hidden
                // but when processing just one thing then it's better to keep things local as much as possible? maybe:)

                long start = cursor;
                long currentWord = Raw.getLong(cursor);
                long mask = getDelimiterMask(currentWord);
                long firstWordMask = ((mask - 1) ^ mask) >>> 8;
                final long isMaskZeroA = ((mask | -mask) >>> 63) ^ 1;
                long ext = -isMaskZeroA;
                firstWordMask |= ext;

                long maskedFirstWord = currentWord & firstWordMask;
                int hash = hash(maskedFirstWord);
                int mapIndex = hash & MAP_MASK;
                while (mask == 0) {
                    cursor += 8;
                    currentWord = Raw.getLong(cursor);
                    mask = getDelimiterMask(currentWord);
                }
                final int delimiterByte = Long.numberOfTrailingZeros(mask);
                final long semicolon = cursor + (delimiterByte >> 3);
                final long maskedWord = currentWord & ((mask - 1) ^ mask) >>> 8;

                int len = (int) (semicolon - start);
                if (len > 15) {
                    long baseEntryPtr = getOrCreateEntryBaseOffsetSlow(len, start, hash, maskedWord);
                    long temperatureWord = Raw.getLong(semicolon + 1);
                    cursor = parseAndStoreTemperature(semicolon + 1, baseEntryPtr, temperatureWord);
                }
                else {
                    long baseEntryPtr = getOrCreateEntryBaseOffsetFast(mapIndex, len, maskedWord, maskedFirstWord, fastMap);
                    long temperatureWord = Raw.getLong(semicolon + 1);
                    cursor = parseAndStoreTemperature(semicolon + 1, baseEntryPtr, temperatureWord);
                }
            }
        }

        private static int hash(long word) {
            // credit: mtopolnik
            long seed = 0x51_7c_c1_b7_27_22_0a_95L;
            int rotDist = 17;
            //
            long hash = word;
            hash *= seed;
            hash = Long.rotateLeft(hash, rotDist);
            return (int) hash;
        }

        private static long nextNewLine(long prev) {
            // again: credits to @thomaswue for this code, literally copy'n'paste
            while (true) {
                long currentWord = Raw.getLong(prev);
                long input = currentWord ^ NEW_LINE_PATTERN;
                long pos = (input - 0x0101010101010101L) & ~input & 0x8080808080808080L;
                if (pos != 0) {
                    prev += Long.numberOfTrailingZeros(pos) >>> 3;
                    break;
                }
                else {
                    prev += 8;
                }
            }
            return prev;
        }

        @Override
        public void run() {
            // The tables are off-heap memory owned by a per-thread confined arena. It is released once the results
            // have been moved to the heap (previously this was Unsafe::allocateMemory and was never freed).
            try (Arena arena = Arena.ofConfined()) {
                long fastMap = allocateMem(arena);
                for (;;) {
                    long startingPtr = globalCursor.addAndGet(SEGMENT_SIZE) - SEGMENT_SIZE;
                    if (startingPtr >= fileEnd) {
                        break;
                    }
                    setCursors(startingPtr);
                    mainLoop(fastMap);
                    doOne(cursorA, endA, fastMap);
                    doOne(cursorB, endB, fastMap);
                }
                transferToHeap(fastMap);
            }
        }

        private long allocateMem(Arena arena) {
            // Arena::allocate returns zero-initialized memory, which replaces the explicit Unsafe::setMemory calls.
            // The zeroing is relied upon: an empty slot is detected by len == 0, and the slow-map names area must
            // have zero padding after each name so that the masked last-word comparison works.
            this.slowMap = arena.allocate(SLOW_MAP_SIZE_BYTES, 64).address();
            this.slowMapNamesPtr = arena.allocate(SLOW_MAP_MAP_NAMES_BYTES, 8).address();
            return arena.allocate(FAST_MAP_SIZE_BYTES, 64).address();
        }

        private void mainLoop(long fastMap) {
            while (cursorA < endA && cursorB < endB) {
                long currentWordA = Raw.getLong(cursorA);
                long currentWordB = Raw.getLong(cursorB);

                long delimiterMaskA = getDelimiterMask(currentWordA);
                long delimiterMaskB = getDelimiterMask(currentWordB);

                long candidateWordA = Raw.getLong(cursorA + 8);
                long candidateWordB = Raw.getLong(cursorB + 8);

                long startA = cursorA;
                long startB = cursorB;

                int trailingZerosA = Long.numberOfTrailingZeros(delimiterMaskA) >> 3;
                int trailingZerosB = Long.numberOfTrailingZeros(delimiterMaskB) >> 3;

                long advanceMaskA = ADVANCE_MASKS[trailingZerosA];
                long advanceMaskB = ADVANCE_MASKS[trailingZerosB];

                long wordMaskA = HASH_MASKS[trailingZerosA];
                long wordMaskB = HASH_MASKS[trailingZerosB];

                long maskedMaskA = advanceMaskA & 8;
                long maskedMaskB = advanceMaskB & 8;

                long negAdvanceMaskA = ~advanceMaskA;
                long negAdvanceMaskB = ~advanceMaskB;

                cursorA += maskedMaskA;
                cursorB += maskedMaskB;

                long nextWordA = (advanceMaskA & candidateWordA) | (negAdvanceMaskA & currentWordA);
                long nextWordB = (advanceMaskB & candidateWordB) | (negAdvanceMaskB & currentWordB);

                delimiterMaskA = getDelimiterMask(nextWordA);
                delimiterMaskB = getDelimiterMask(nextWordB);

                boolean slowA = delimiterMaskA == 0;
                boolean slowB = delimiterMaskB == 0;
                trailingZerosA = Long.numberOfTrailingZeros(delimiterMaskA) >> 3;
                trailingZerosB = Long.numberOfTrailingZeros(delimiterMaskB) >> 3;
                boolean slowSome = (slowA || slowB);

                long maskedFirstWordA = wordMaskA & currentWordA;
                long maskedFirstWordB = wordMaskB & currentWordB;

                int hashA = hash(maskedFirstWordA);
                int hashB = hash(maskedFirstWordB);

                currentWordA = nextWordA;
                currentWordB = nextWordB;

                if (slowSome) {
                    doSlow(fastMap, delimiterMaskA, currentWordA, delimiterMaskB, currentWordB, startA, startB, hashA, hashB, slowA, maskedFirstWordA, slowB,
                            maskedFirstWordB);
                }
                else {
                    final long semicolonA = cursorA + trailingZerosA;
                    final long semicolonB = cursorB + trailingZerosB;

                    long digitStartA = semicolonA + 1;
                    long digitStartB = semicolonB + 1;

                    long lastWordMaskA = HASH_MASKS[trailingZerosA];
                    long lastWordMaskB = HASH_MASKS[trailingZerosB];

                    long temperatureWordA = Raw.getLong(digitStartA);
                    long temperatureWordB = Raw.getLong(digitStartB);

                    final long maskedLastWordA = currentWordA & lastWordMaskA;
                    final long maskedLastWordB = currentWordB & lastWordMaskB;

                    int lenA = (int) (semicolonA - startA);
                    int lenB = (int) (semicolonB - startB);

                    int mapIndexA = hashA & MAP_MASK;
                    int mapIndexB = hashB & MAP_MASK;

                    long baseEntryPtrA;
                    long baseEntryPtrB;

                    baseEntryPtrA = getOrCreateEntryBaseOffsetFast(mapIndexA, lenA, maskedLastWordA, maskedFirstWordA, fastMap);
                    baseEntryPtrB = getOrCreateEntryBaseOffsetFast(mapIndexB, lenB, maskedLastWordB, maskedFirstWordB, fastMap);

                    cursorA = parseAndStoreTemperature(digitStartA, baseEntryPtrA, temperatureWordA);
                    cursorB = parseAndStoreTemperature(digitStartB, baseEntryPtrB, temperatureWordB);
                }
            }
        }

        private void doSlow(long fastMap, long delimiterMaskA, long currentWordA, long delimiterMaskB, long currentWordB, long startA, long startB, int hashA, int hashB,
                            boolean slowA, long maskedFirstWordA, boolean slowB, long maskedFirstWordB) {
            int trailingZerosB;
            int trailingZerosA;
            while (delimiterMaskA == 0) {
                cursorA += 8;
                currentWordA = Raw.getLong(cursorA);
                delimiterMaskA = getDelimiterMask(currentWordA);
            }

            while (delimiterMaskB == 0) {
                cursorB += 8;
                currentWordB = Raw.getLong(cursorB);
                delimiterMaskB = getDelimiterMask(currentWordB);
            }
            trailingZerosA = Long.numberOfTrailingZeros(delimiterMaskA) >> 3;
            trailingZerosB = Long.numberOfTrailingZeros(delimiterMaskB) >> 3;

            final long semicolonA = cursorA + trailingZerosA;
            final long semicolonB = cursorB + trailingZerosB;

            long digitStartA = semicolonA + 1;
            long digitStartB = semicolonB + 1;

            long lastWordMaskA = HASH_MASKS[trailingZerosA];
            long lastWordMaskB = HASH_MASKS[trailingZerosB];

            long temperatureWordA = Raw.getLong(digitStartA);
            long temperatureWordB = Raw.getLong(digitStartB);

            final long maskedLastWordA = currentWordA & lastWordMaskA;
            final long maskedLastWordB = currentWordB & lastWordMaskB;

            int lenA = (int) (semicolonA - startA);
            int lenB = (int) (semicolonB - startB);

            int mapIndexA = hashA & MAP_MASK;
            int mapIndexB = hashB & MAP_MASK;

            long baseEntryPtrA;
            long baseEntryPtrB;

            if (slowA) {
                baseEntryPtrA = getOrCreateEntryBaseOffsetSlow(lenA, startA, hashA, maskedLastWordA);
            }
            else {
                baseEntryPtrA = getOrCreateEntryBaseOffsetFast(mapIndexA, lenA, maskedLastWordA, maskedFirstWordA, fastMap);
            }

            if (slowB) {
                baseEntryPtrB = getOrCreateEntryBaseOffsetSlow(lenB, startB, hashB, maskedLastWordB);
            }
            else {
                baseEntryPtrB = getOrCreateEntryBaseOffsetFast(mapIndexB, lenB, maskedLastWordB, maskedFirstWordB, fastMap);
            }
            cursorA = parseAndStoreTemperature(digitStartA, baseEntryPtrA, temperatureWordA);
            cursorB = parseAndStoreTemperature(digitStartB, baseEntryPtrB, temperatureWordB);
        }

        private void setCursors(long current) {
            // Credit for the whole work-stealing scheme: @thomaswue
            // I have totally stolen it from him. I changed the order a bit to suite my taste better,
            // but it's his code
            long segmentStart;
            if (current == fileStart) {
                segmentStart = current;
            }
            else {
                segmentStart = nextNewLine(current) + 1;
            }
            long segmentEnd = nextNewLine(Math.min(fileEnd - 1, current + SEGMENT_SIZE));

            long size = (segmentEnd - segmentStart) / 2;
            long mid = nextNewLine(segmentStart + size);

            cursorA = segmentStart;
            endA = mid;
            cursorB = mid + 1;
            endB = segmentEnd;
        }

        private static long getOrCreateEntryBaseOffsetFast(int mapIndexA, int lenA, long maskedLastWord, long maskedFirstWord, long fastMap) {
            for (;;) {
                long basePtr = mapIndexA * FAST_MAP_ENTRY_SIZE_BYTES + fastMap;
                long namePart1 = Raw.getLong(basePtr + FAST_MAP_NAME_PART1);
                long namePart2 = Raw.getLong(basePtr + FAST_MAP_NAME_PART2);
                if (namePart1 == maskedFirstWord && namePart2 == maskedLastWord) {
                    return basePtr;
                }
                long lenPtr = basePtr + MAP_LEN_OFFSET;
                int len = Raw.getInt(lenPtr);
                if (len == 0) {
                    return newEntryFast(lenA, maskedLastWord, maskedFirstWord, lenPtr, basePtr);
                }
                mapIndexA = ++mapIndexA & MAP_MASK;
            }
        }

        private static long newEntryFast(int lenA, long maskedLastWord, long maskedFirstWord, long lenPtr, long basePtr) {
            Raw.putInt(lenPtr, lenA);
            // todo: this could be a single putLong()
            Raw.putInt(basePtr + MAP_MAX_OFFSET, Integer.MIN_VALUE);
            Raw.putInt(basePtr + MAP_MIN_OFFSET, Integer.MAX_VALUE);
            Raw.putLong(basePtr + FAST_MAP_NAME_PART1, maskedFirstWord);
            Raw.putLong(basePtr + FAST_MAP_NAME_PART2, maskedLastWord);
            return basePtr;
        }

        private long getOrCreateEntryBaseOffsetSlow(int lenA, long startPtr, int hash, long maskedLastWord) {
            long fullLen = lenA & ~7L;
            long mapIndexA = hash & MAP_MASK;
            for (;;) {
                long basePtr = mapIndexA * SLOW_MAP_ENTRY_SIZE_BYTES + slowMap;
                long lenPtr = basePtr + MAP_LEN_OFFSET;
                long namePtr = basePtr + SLOW_MAP_NAME_OFFSET;
                int len = Raw.getInt(lenPtr);
                if (len == lenA) {
                    namePtr = Raw.getLong(basePtr + SLOW_MAP_NAME_OFFSET);
                    if (nameMatchSlow(startPtr, namePtr, fullLen, maskedLastWord)) {
                        return basePtr;
                    }
                }
                else if (len == 0) {
                    Raw.putLong(namePtr, slowMapNamesPtr);
                    Raw.putInt(lenPtr, lenA);
                    Raw.putInt(basePtr + MAP_MAX_OFFSET, Integer.MIN_VALUE);
                    Raw.putInt(basePtr + MAP_MIN_OFFSET, Integer.MAX_VALUE);
                    Raw.copyMemory(startPtr, slowMapNamesPtr, lenA);
                    long alignedLen = (lenA & ~7L) + 8;
                    slowMapNamesPtr += alignedLen;
                    return basePtr;
                }
                mapIndexA = ++mapIndexA & MAP_MASK;
            }
        }

        private static boolean nameMatchSlow(long start, long namePtr, long fullLen, long maskedLastWord) {
            long offset;
            for (offset = 0; offset < fullLen; offset += 8) {
                if (Raw.getLong(start + offset) != Raw.getLong(namePtr + offset)) {
                    return false;
                }
            }
            long maskedWordInMap = Raw.getLong(namePtr + fullLen);
            return (maskedWordInMap == maskedLastWord);
        }
    }

    record StationStats(int min, int max, int count, long sum) {
        StationStats mergeWith(StationStats other) {
            return new StationStats(Math.min(min, other.min), Math.max(max, other.max), count + other.count, sum + other.sum);
        }
    }
}

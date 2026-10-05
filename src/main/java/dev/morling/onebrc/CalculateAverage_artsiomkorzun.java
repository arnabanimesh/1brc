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
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JDK 27 compatible variant: sun.misc.Unsafe is gone (its memory-access methods throw by default since JDK 26),
 * all raw-address access is replaced by the standard Foreign Function &amp; Memory API (java.lang.foreign, final since JDK 22).
 *
 * Differences that follow from this:
 * - every access is bounds-checked, so the SWAR code must never read past the end of a segment. The file is therefore
 *   processed as a mapped "main" part plus a small "tail" that is copied into a zero-padded buffer.
 * - positions are offsets into a MemorySegment instead of absolute addresses, so "not found" is -1 (offset 0 is valid).
 */
public class CalculateAverage_artsiomkorzun {

    private static final Path FILE = Path.of("./measurements.txt");
    private static final long SEGMENT_SIZE = 2 * 1024 * 1024;
    private static final long TAIL_SIZE = 256; // the last ~256 bytes are processed from a padded copy
    private static final long PADDING = 32; // SWAR code reads up to 16 bytes past the end of the last line
    private static final long COMMA_PATTERN = 0x3B3B3B3B3B3B3B3BL;
    private static final long LINE_PATTERN = 0x0A0A0A0A0A0A0A0AL;
    private static final long DOT_BITS = 0x10101000;
    private static final long MAGIC_MULTIPLIER = (100 * 0x1000000 + 10 * 0x10000 + 1);
    private static final long[] WORD_MASK = { 0, 0, 0, 0, 0, 0, 0, 0, -1 };
    private static final int[] LENGTH_MASK = { 0, 0, 0, 0, 0, 0, 0, 0, -1 };

    // little-endian byte order, no alignment requirement
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    public static void main(String[] args) throws Exception {
        // for (int i = 0; i < 10; i++) {
        // long start = System.currentTimeMillis();
        // execute();
        // long end = System.currentTimeMillis();
        // System.err.println("Time: " + (end - start));
        // }

        if (isSpawn(args)) {
            spawn();
            return;
        }

        execute();
    }

    private static boolean isSpawn(String[] args) {
        for (String arg : args) {
            if ("--worker".equals(arg)) {
                return false;
            }
        }

        return true;
    }

    private static void spawn() throws Exception {
        ProcessHandle.Info info = ProcessHandle.current().info();
        ArrayList<String> commands = new ArrayList<>();
        Optional<String> command = info.command();
        Optional<String[]> arguments = info.arguments();

        if (command.isPresent()) {
            commands.add(command.get());
        }

        if (arguments.isPresent()) {
            commands.addAll(Arrays.asList(arguments.get()));
        }

        commands.add("--worker");

        new ProcessBuilder()
                .command(commands)
                .start()
                .getInputStream()
                .transferTo(System.out);
    }

    private static void execute() throws Exception {
        MemorySegment file = map(FILE);
        long fileSize = file.byteSize();
        long tailStart = tailStart(file, fileSize);
        long tailSize = fileSize - tailStart;
        MemorySegment tail = copyTail(file, tailStart, tailSize);
        int segmentCount = (int) ((tailStart + SEGMENT_SIZE - 1) / SEGMENT_SIZE);

        AtomicInteger counter = new AtomicInteger();
        AtomicReference<Aggregates> result = new AtomicReference<>();

        int parallelism = Runtime.getRuntime().availableProcessors();
        Aggregator[] aggregators = new Aggregator[parallelism];

        for (int i = 0; i < aggregators.length; i++) {
            aggregators[i] = new Aggregator(counter, result, file, tailStart, tail, tailSize, segmentCount);
            aggregators[i].start();
        }

        for (int i = 0; i < aggregators.length; i++) {
            aggregators[i].join();
        }

        Map<String, Aggregate> aggregates = result.get().build();
        System.out.println(text(aggregates));
        System.out.close();
    }

    private static MemorySegment map(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, size, Arena.global());
        }
        catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /** First line start at or after (fileSize - TAIL_SIZE); everything from here on is handled via the padded copy. */
    private static long tailStart(MemorySegment file, long fileSize) {
        long from = fileSize - TAIL_SIZE;

        if (from <= 0) {
            return 0;
        }

        for (long i = from; i < fileSize; i++) {
            if (file.get(BYTE, i) == '\n') {
                return i + 1;
            }
        }

        return fileSize;
    }

    private static MemorySegment copyTail(MemorySegment file, long tailStart, long tailSize) {
        MemorySegment tail = Arena.global().allocate(tailSize + PADDING, 8); // zero-initialized
        MemorySegment.copy(file, tailStart, tail, 0, tailSize);
        return tail;
    }

    private static long word(MemorySegment memory, long offset) {
        return memory.get(LONG, offset);
    }

    private static String text(Map<String, Aggregate> aggregates) {
        StringBuilder text = new StringBuilder(aggregates.size() * 32 + 2);
        text.append('{');

        for (Map.Entry<String, Aggregate> entry : aggregates.entrySet()) {
            if (text.length() > 1) {
                text.append(", ");
            }

            Aggregate aggregate = entry.getValue();
            text.append(entry.getKey()).append('=')
                    .append(round(aggregate.min)).append('/')
                    .append(round(1.0 * aggregate.sum / aggregate.cnt)).append('/')
                    .append(round(aggregate.max));
        }

        text.append('}');
        return text.toString();
    }

    private static double round(double v) {
        return Math.round(v) / 10.0;
    }

    private record Aggregate(int min, int max, long sum, int cnt) {
    }

    private static class Aggregates {

        private static final long ENTRIES = 64 * 1024;
        private static final long SIZE = 128 * ENTRIES;
        private static final long MASK = (ENTRIES - 1) << 7;

        // entry layout (128 bytes): len:int | hash:int | sum:long | cnt:int | min:short | max:short | key bytes (incl. ';')
        private final MemorySegment table;

        public Aggregates() {
            // zero-initialized, page aligned, shareable between threads (needed for the final merge)
            table = Arena.global().allocate(SIZE, 4096);
        }

        /** @return entry offset, or -1 if the first probed slot does not hold this key */
        public long find(long word1, long word2, long hash) {
            long entry = offset(hash);
            long w1 = table.get(LONG, entry + 24);
            long w2 = table.get(LONG, entry + 32);
            return (word1 == w1) && (word2 == w2) ? entry : -1;
        }

        public long put(MemorySegment memory, long reference, long word, long length, long hash) {
            for (long entry = offset(hash);; entry = next(entry)) {
                if (equal(memory, reference, word, entry + 24, length)) {
                    return entry;
                }

                int len = table.get(INT, entry);
                if (len == 0) {
                    alloc(memory, reference, length, hash, entry);
                    return entry;
                }
            }
        }

        public void update(long entry, long value) {
            long sum = table.get(LONG, entry + 8) + value;
            int cnt = table.get(INT, entry + 16) + 1;
            short min = table.get(SHORT, entry + 20);
            short max = table.get(SHORT, entry + 22);

            table.set(LONG, entry + 8, sum);
            table.set(INT, entry + 16, cnt);

            if (value < min) {
                table.set(SHORT, entry + 20, (short) value);
            }

            if (value > max) {
                table.set(SHORT, entry + 22, (short) value);
            }
        }

        public void merge(Aggregates rights) {
            MemorySegment other = rights.table;

            for (long rightEntry = 0; rightEntry < SIZE; rightEntry += 128) {
                int length = other.get(INT, rightEntry);

                if (length == 0) {
                    continue;
                }

                int hash = other.get(INT, rightEntry + 4);

                for (long entry = offset(hash);; entry = next(entry)) {
                    if (equal(entry + 24, other, rightEntry + 24, length)) {
                        long sum = table.get(LONG, entry + 8) + other.get(LONG, rightEntry + 8);
                        int cnt = table.get(INT, entry + 16) + other.get(INT, rightEntry + 16);
                        short min = (short) Math.min(table.get(SHORT, entry + 20), other.get(SHORT, rightEntry + 20));
                        short max = (short) Math.max(table.get(SHORT, entry + 22), other.get(SHORT, rightEntry + 22));

                        table.set(LONG, entry + 8, sum);
                        table.set(INT, entry + 16, cnt);
                        table.set(SHORT, entry + 20, min);
                        table.set(SHORT, entry + 22, max);
                        break;
                    }

                    int len = table.get(INT, entry);

                    if (len == 0) {
                        MemorySegment.copy(other, rightEntry, table, entry, length + 24L);
                        break;
                    }
                }
            }
        }

        public Map<String, Aggregate> build() {
            TreeMap<String, Aggregate> set = new TreeMap<>();

            for (long entry = 0; entry < SIZE; entry += 128) {
                int length = table.get(INT, entry);

                if (length != 0) {
                    byte[] array = new byte[length - 1];
                    MemorySegment.copy(table, BYTE, entry + 24, array, 0, array.length);
                    String key = new String(array, StandardCharsets.UTF_8);

                    long sum = table.get(LONG, entry + 8);
                    int cnt = table.get(INT, entry + 16);
                    short min = table.get(SHORT, entry + 20);
                    short max = table.get(SHORT, entry + 22);

                    Aggregate aggregate = new Aggregate(min, max, sum, cnt);
                    set.put(key, aggregate);
                }
            }

            return set;
        }

        private void alloc(MemorySegment memory, long reference, long length, long hash, long entry) {
            table.set(INT, entry, (int) length);
            table.set(INT, entry + 4, (int) hash);
            table.set(SHORT, entry + 20, Short.MAX_VALUE);
            table.set(SHORT, entry + 22, Short.MIN_VALUE);
            MemorySegment.copy(memory, reference, table, entry + 24, length);
        }

        private static long offset(long hash) {
            return hash & MASK;
        }

        private static long next(long prev) {
            return (prev + 128) & (SIZE - 1);
        }

        /** key in the input (first words compared raw, last word pre-masked) vs. key in this table */
        private boolean equal(MemorySegment memory, long leftOffset, long leftWord, long rightOffset, long length) {
            while (length > 8) {
                long left = memory.get(LONG, leftOffset);
                long right = table.get(LONG, rightOffset);

                if (left != right) {
                    return false;
                }

                leftOffset += 8;
                rightOffset += 8;
                length -= 8;
            }

            return leftWord == table.get(LONG, rightOffset);
        }

        /** key in this table vs. key in another table */
        private boolean equal(long leftOffset, MemorySegment other, long rightOffset, long length) {
            do {
                long left = table.get(LONG, leftOffset);
                long right = other.get(LONG, rightOffset);

                if (left != right) {
                    return false;
                }

                leftOffset += 8;
                rightOffset += 8;
                length -= 8;
            } while (length > 0);

            return true;
        }
    }

    private static class Aggregator extends Thread {

        private final AtomicInteger counter;
        private final AtomicReference<Aggregates> result;
        private final MemorySegment file;
        private final long fileLimit; // main part of the file: lines starting before this offset
        private final MemorySegment tail;
        private final long tailSize;
        private final int segmentCount;

        public Aggregator(AtomicInteger counter, AtomicReference<Aggregates> result,
                          MemorySegment file, long fileLimit, MemorySegment tail, long tailSize, int segmentCount) {
            super("aggregator");
            this.counter = counter;
            this.result = result;
            this.file = file;
            this.fileLimit = fileLimit;
            this.tail = tail;
            this.tailSize = tailSize;
            this.segmentCount = segmentCount;
        }

        @Override
        public void run() {
            Aggregates aggregates = new Aggregates();

            int segment;
            while ((segment = counter.getAndIncrement()) < segmentCount) {
                long position = SEGMENT_SIZE * segment;
                long end = Math.min(position + SEGMENT_SIZE + 1, fileLimit);
                long start = (segment > 0) ? next(file, position) : position;
                process(aggregates, file, start, end);
            }

            // exactly one thread draws the ticket == segmentCount and takes care of the tail
            if (segment == segmentCount) {
                process(aggregates, tail, 0, tailSize);
            }

            while (!result.compareAndSet(null, aggregates)) {
                Aggregates rights = result.getAndSet(null);

                if (rights != null) {
                    aggregates.merge(rights);
                }
            }
        }

        /** Processes all lines that start in [start, end); start must be a line start. */
        private static void process(Aggregates aggregates, MemorySegment memory, long start, long end) {
            if (start >= end) {
                return;
            }

            long chunk = (end - start) / 3;
            long left = Math.min(next(memory, start + chunk), end);
            long right = Math.min(next(memory, start + chunk + chunk), end);

            Chunk chunk1 = new Chunk(start, left);
            Chunk chunk2 = new Chunk(left, right);
            Chunk chunk3 = new Chunk(right, end);

            while (chunk1.has() && chunk2.has() && chunk3.has()) {
                long word1 = word(memory, chunk1.position);
                long word2 = word(memory, chunk2.position);
                long word3 = word(memory, chunk3.position);
                long word4 = word(memory, chunk1.position + 8);
                long word5 = word(memory, chunk2.position + 8);
                long word6 = word(memory, chunk3.position + 8);

                long separator1 = separator(word1);
                long separator2 = separator(word2);
                long separator3 = separator(word3);
                long separator4 = separator(word4);
                long separator5 = separator(word5);
                long separator6 = separator(word6);

                long entry1 = find(aggregates, memory, chunk1, word1, word4, separator1, separator4);
                long entry2 = find(aggregates, memory, chunk2, word2, word5, separator2, separator5);
                long entry3 = find(aggregates, memory, chunk3, word3, word6, separator3, separator6);

                long value1 = value(memory, chunk1);
                long value2 = value(memory, chunk2);
                long value3 = value(memory, chunk3);

                aggregates.update(entry1, value1);
                aggregates.update(entry2, value2);
                aggregates.update(entry3, value3);
            }

            drain(aggregates, memory, chunk1);
            drain(aggregates, memory, chunk2);
            drain(aggregates, memory, chunk3);
        }

        private static void drain(Aggregates aggregates, MemorySegment memory, Chunk chunk) {
            while (chunk.has()) {
                long word1 = word(memory, chunk.position);
                long word2 = word(memory, chunk.position + 8);

                long separator1 = separator(word1);
                long separator2 = separator(word2);

                long entry = find(aggregates, memory, chunk, word1, word2, separator1, separator2);
                long value = value(memory, chunk);

                aggregates.update(entry, value);
            }
        }

        private static long next(MemorySegment memory, long position) {
            while (true) {
                long word = word(memory, position);
                long match = word ^ LINE_PATTERN;
                long line = (match - 0x0101010101010101L) & (~match & 0x8080808080808080L);

                if (line == 0) {
                    position += 8;
                    continue;
                }

                return position + length(line) + 1;
            }
        }

        private static long find(Aggregates aggregates, MemorySegment memory, Chunk chunk,
                                 long word1, long word2, long separator1, long separator2) {
            boolean small = (separator1 | separator2) != 0;
            long start = chunk.position;
            long hash;
            long word;

            if (small) {
                int length1 = length(separator1);
                int length2 = length(separator2);
                word1 = mask(word1, separator1);
                word2 = mask(word2 & WORD_MASK[length1], separator2);
                hash = mix(word1 ^ word2);

                chunk.position += length1 + (length2 & LENGTH_MASK[length1]) + 1;
                long entry = aggregates.find(word1, word2, hash);

                if (entry >= 0) {
                    return entry;
                }

                word = (separator1 == 0) ? word2 : word1;
            }
            else {
                chunk.position += 16;
                hash = word1 ^ word2;

                while (true) {
                    word = word(memory, chunk.position);
                    long separator = separator(word);

                    if (separator == 0) {
                        chunk.position += 8;
                        hash ^= word;
                        continue;
                    }

                    word = mask(word, separator);
                    hash = mix(hash ^ word);
                    chunk.position += length(separator) + 1;
                    break;
                }
            }

            long length = chunk.position - start;
            return aggregates.put(memory, start, word, length, hash);
        }

        private static long value(MemorySegment memory, Chunk chunk) {
            long num = word(memory, chunk.position);
            long dot = dot(num);
            long value = value(num, dot);
            chunk.position += (dot >> 3) + 3;
            return value;
        }

        private static long separator(long word) {
            long match = word ^ COMMA_PATTERN;
            return (match - 0x0101010101010101L) & (~match & 0x8080808080808080L);
        }

        private static long mask(long word, long separator) {
            long mask = separator ^ (separator - 1);
            return word & mask;
        }

        private static int length(long separator) {
            return Long.numberOfTrailingZeros(separator) >>> 3;
        }

        private static long mix(long x) {
            long h = x * -7046029254386353131L;
            h ^= h >>> 35;
            return h;
            // h ^= h >>> 32;
            // return (int) (h ^ h >>> 16);
        }

        private static long dot(long num) {
            return Long.numberOfTrailingZeros(~num & DOT_BITS);
        }

        private static long value(long w, long dot) {
            long signed = (~w << 59) >> 63;
            long mask = ~(signed & 0xFF);
            long digits = ((w & mask) << (28 - dot)) & 0x0F000F0F00L;
            long abs = ((digits * MAGIC_MULTIPLIER) >>> 32) & 0x3FF;
            return (abs ^ signed) - signed;
        }
    }

    private static class Chunk {
        final long limit;
        long position;

        public Chunk(long position, long limit) {
            this.position = position;
            this.limit = limit;
        }

        boolean has() {
            return position < limit;
        }
    }
}

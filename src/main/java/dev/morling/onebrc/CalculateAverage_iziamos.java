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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import static java.nio.channels.FileChannel.MapMode.READ_ONLY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.READ;

/**
 * Same algorithm as the original, but with sun.misc.Unsafe replaced by the
 * Foreign Function & Memory API (java.lang.foreign, final since JDK 22).
 * <p>
 * Nothing here needs --add-opens, --sun-misc-unsafe-memory-access or
 * --enable-native-access: no restricted methods are used.
 * <p>
 * All "addresses" are now byte offsets into the mapped file segment.
 */
public class CalculateAverage_iziamos {
    private static final String FILE = "./measurements.txt";

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    /** Unaligned, always little-endian 8-byte read (a plain load on x86/ARM64). */
    private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public static void main(String[] args) throws Exception {
        final long chunkSize = 64L * 1024 * 1024;

        final MemorySegment file;
        try (FileChannel channel = FileChannel.open(Path.of(FILE), READ)) {
            // The mapping outlives the channel; it lives as long as the global arena.
            file = channel.map(READ_ONLY, 0, channel.size(), Arena.global());
        }

        final long fileSize = file.byteSize();
        if (fileSize == 0) {
            System.out.println("{}");
            return;
        }

        final int chunkCount = (int) ((fileSize + chunkSize - 1) / chunkSize);
        final List<CompletableFuture<ResultTable>> futures = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; ++i) {
            futures.add(processChunk(file, i, chunkSize));
        }

        // Merge chunks in order as they finish and free each table right after,
        // so at most a handful of 4 MB tables are alive at any time.
        final Map<String, ResultRow> output = new TreeMap<>();
        try (ResultTable aggregate = futures.get(0).get()) {
            futures.set(0, null);
            for (int i = 1; i < chunkCount; i++) {
                try (ResultTable other = futures.get(i).get()) {
                    aggregate.merge(other);
                }
                futures.set(i, null);
            }

            aggregate.forEach(
                    (name, min, max, sum, count) -> output.put(name, new ResultRow(min, (double) sum / count, max)));
        }

        System.out.println(output);
    }

    private record ResultRow(long min, double mean, long max) {
        @Override
        public String toString() {
            return "%s/%s/%s".formatted(tenths(min), roundedTenths(mean), tenths(max));
        }

        private static double tenths(final long value) {
            return value / 10.0;
        }

        private static double roundedTenths(final double value) {
            return Math.round(value) / 10.0;
        }
    }

    private static CompletableFuture<ResultTable> processChunk(final MemorySegment file,
                                                               final long chunkNumber,
                                                               final long chunkSize) {
        final var ret = new CompletableFuture<ResultTable>();

        Thread.ofVirtual().start(() -> {
            try {
                final long fileSize = file.byteSize();
                final long chunkStart = chunkNumber * chunkSize;
                final long chunkEnd = Math.min(chunkStart + chunkSize, fileSize);
                final long start = skipIncomplete(file, chunkStart);

                final ResultTable table = new ResultTable(file);
                scalarLoop(file, start, chunkEnd, table);
                ret.complete(table);
            }
            catch (final Throwable t) {
                ret.completeExceptionally(t);
            }
        });

        return ret;
    }

    /**
     * Returns the offset of the first line that starts at or after {@code start}.
     * A line belongs to the chunk in which it *starts*, so we look at the byte
     * before {@code start}: if it is '\n', {@code start} is already a line start.
     */
    private static long skipIncomplete(final MemorySegment file, final long start) {
        if (start == 0) {
            return 0;
        }
        final long size = file.byteSize();
        for (long i = start - 1; i < size; ++i) {
            if (file.get(BYTE, i) == '\n') {
                return i + 1;
            }
        }
        return size;
    }

    private static void scalarLoop(final MemorySegment file, final long start, final long limit, final ResultTable result) {
        final LoopCursor cursor = new LoopCursor(file, start, limit);
        while (cursor.hasMore()) {
            final long nameOffset = cursor.getCurrentAddress();
            final int length = cursor.getStringLength();
            final int hash = cursor.getHash();
            final int value = cursor.getCurrentValue();
            result.put(nameOffset, length, hash, value);
        }
    }

    /**
     * 8-byte little-endian read at {@code offset}. Within 8 bytes of the end of the
     * file a plain read would be out of bounds, so the missing bytes read as zero.
     */
    private static long readWord(final MemorySegment file, final long offset) {
        final long size = file.byteSize();
        if (offset <= size - Long.BYTES) {
            return file.get(LONG_LE, offset);
        }
        long word = 0;
        for (long i = 0; offset + i < size; ++i) {
            word |= (file.get(BYTE, offset + i) & 0xFFL) << (8 * i);
        }
        return word;
    }

    public static class LoopCursor {
        private final MemorySegment file;
        private final long limit;
        private long pointer;

        private int hash = 0;

        public LoopCursor(final MemorySegment file, final long pointer, final long limit) {
            this.file = file;
            this.pointer = pointer;
            this.limit = limit;
        }

        public long getCurrentAddress() {
            return pointer;
        }

        public int getStringLength() {
            int strLen = 0;
            hash = 0;

            byte b = file.get(BYTE, pointer);
            for (; b != ';'; ++strLen, b = file.get(BYTE, pointer + strLen)) {
                hash = 31 * hash + b;
            }
            pointer += strLen + 1;

            return strLen;
        }

        public int getHash() {
            return hash;
        }

        public int getCurrentValue() {
            return getCurrentValueMeryKitty();
        }

        /**
         * No point rewriting what would essentially be the same code <3.
         */
        public int getCurrentValueMeryKitty() {
            final long word = readWord(file, pointer); // always little-endian

            int decimalSepPos = Long.numberOfTrailingZeros(~word & 0x10101000);
            int shift = 28 - decimalSepPos;

            long signed = (~word << 59) >> 63;
            long designMask = ~(signed & 0xFF);

            long digits = ((word & designMask) << shift) & 0x0F000F0F00L;

            long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;
            int increment = (decimalSepPos >>> 3) + 3;

            pointer += increment;
            return (int) ((absValue ^ signed) - signed);
        }

        public boolean hasMore() {
            return pointer < limit;
        }
    }

    public interface ResultConsumer {
        void consume(final String name, final int min, final int max, final long sum, final long count);
    }

    /**
     * Open-addressing hash table living in an off-heap MemorySegment, one 64-byte
     * struct per slot. A slot is empty while its name length is 0 (names are never empty).
     * Names are not copied: each slot refers to the first occurrence in the mapped file.
     */
    static final class ResultTable implements AutoCloseable {
        private static final int MAP_SIZE = 16384 * 4;
        private static final int MASK = MAP_SIZE - 1;
        private static final long STRUCT_SIZE = 64;
        private static final long BYTE_SIZE = MAP_SIZE * STRUCT_SIZE;
        private static final long NAME_OFFSET = 0; // long: offset of the name in the file
        private static final long STRING_LEN_OFFSET = 8; // int
        private static final long HASH_OFFSET = 12; // int
        private static final long MIN_OFFSET = 16; // int
        private static final long MAX_OFFSET = 20; // int
        private static final long SUM_OFFSET = 24; // long
        private static final long COUNT_OFFSET = 32; // long

        private final MemorySegment file;
        private final Arena arena;
        private final MemorySegment table;

        ResultTable(final MemorySegment file) {
            this.file = file;
            // Shared, because a table is filled by a worker thread and then merged by main.
            // The allocation is zero-initialised and 64-byte aligned.
            this.arena = Arena.ofShared();
            this.table = arena.allocate(BYTE_SIZE, 64);
        }

        @Override
        public void close() {
            arena.close();
        }

        void put(final long nameOffset, final int length, final int hash, final int value) {
            final long s = findSlot(nameOffset, length, hash);

            final int min = table.get(INT, s + MIN_OFFSET);
            final int max = table.get(INT, s + MAX_OFFSET);
            final long sum = table.get(LONG, s + SUM_OFFSET);
            final long count = table.get(LONG, s + COUNT_OFFSET);

            table.set(INT, s + MIN_OFFSET, Math.min(value, min));
            table.set(INT, s + MAX_OFFSET, Math.max(value, max));
            table.set(LONG, s + SUM_OFFSET, sum + value);
            table.set(LONG, s + COUNT_OFFSET, count + 1);
        }

        void forEach(final ResultConsumer resultConsumer) {
            for (long s = 0; s < BYTE_SIZE; s += STRUCT_SIZE) {
                final int strLen = table.get(INT, s + STRING_LEN_OFFSET);
                if (strLen == 0) {
                    continue;
                }

                final long nameOffset = table.get(LONG, s + NAME_OFFSET);
                final byte[] bytes = file.asSlice(nameOffset, strLen).toArray(BYTE);

                resultConsumer.consume(
                        new String(bytes, UTF_8),
                        table.get(INT, s + MIN_OFFSET),
                        table.get(INT, s + MAX_OFFSET),
                        table.get(LONG, s + SUM_OFFSET),
                        table.get(LONG, s + COUNT_OFFSET));
            }
        }

        void merge(final ResultTable other) {
            for (long o = 0; o < BYTE_SIZE; o += STRUCT_SIZE) {
                final int otherLength = other.table.get(INT, o + STRING_LEN_OFFSET);
                if (otherLength == 0) {
                    continue;
                }

                final long otherName = other.table.get(LONG, o + NAME_OFFSET);
                final int otherHash = other.table.get(INT, o + HASH_OFFSET);

                final long s = findSlot(otherName, otherLength, otherHash);

                table.set(INT, s + MIN_OFFSET, Math.min(table.get(INT, s + MIN_OFFSET), other.table.get(INT, o + MIN_OFFSET)));
                table.set(INT, s + MAX_OFFSET, Math.max(table.get(INT, s + MAX_OFFSET), other.table.get(INT, o + MAX_OFFSET)));
                table.set(LONG, s + SUM_OFFSET, table.get(LONG, s + SUM_OFFSET) + other.table.get(LONG, o + SUM_OFFSET));
                table.set(LONG, s + COUNT_OFFSET, table.get(LONG, s + COUNT_OFFSET) + other.table.get(LONG, o + COUNT_OFFSET));
            }
        }

        /** Returns the byte offset of the slot's struct, claiming an empty slot if the name is new. */
        private long findSlot(final long nameOffset, final int length, final int hash) {
            for (int slot = hash & MASK;; slot = (slot + 1) & MASK) {
                final long s = (long) slot * STRUCT_SIZE;
                final int storedLength = table.get(INT, s + STRING_LEN_OFFSET);

                if (storedLength == 0) {
                    table.set(LONG, s + NAME_OFFSET, nameOffset);
                    table.set(INT, s + STRING_LEN_OFFSET, length);
                    table.set(INT, s + HASH_OFFSET, hash);
                    table.set(INT, s + MIN_OFFSET, Integer.MAX_VALUE);
                    table.set(INT, s + MAX_OFFSET, Integer.MIN_VALUE);
                    return s;
                }

                if (storedLength == length
                        && table.get(INT, s + HASH_OFFSET) == hash
                        && namesEqual(table.get(LONG, s + NAME_OFFSET), nameOffset, length)) {
                    return s;
                }
            }
        }

        private boolean namesEqual(final long a, final long b, final int length) {
            if (a == b) {
                return true;
            }

            int i = 0;
            for (; i < length - 7; i += 8) {
                if (readWord(file, a + i) != readWord(file, b + i)) {
                    return false;
                }
            }

            final int remaining = length - i;
            if (remaining == 0) {
                return true;
            }

            final long mask = (1L << (remaining * 8)) - 1;
            return 0 == ((readWord(file, a + i) ^ readWord(file, b + i)) & mask);
        }
    }
}

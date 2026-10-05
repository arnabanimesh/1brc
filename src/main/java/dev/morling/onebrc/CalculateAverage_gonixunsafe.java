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
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Port of the original sun.misc.Unsafe based solution to the standard
 * Foreign Function & Memory API (final since JDK 22). It needs no
 * --add-opens / --enable-native-access / --sun-misc-unsafe-memory-access
 * flags and produces no warnings on JDK 24+ (and keeps working once the
 * Unsafe memory-access methods are removed).
 *
 * Raw addresses became byte offsets into MemorySegments:
 *  - positions in the input file are offsets into the mmap'ed file segment
 *  - entry "addresses" are offsets into the Aggregator's `mem` segment
 *  - index slots are offsets into the Aggregator's `index` segment
 */
public class CalculateAverage_gonixunsafe {

    private static final String FILE = "./measurements.txt";
    private static final int MAX_THREADS = Runtime.getRuntime().availableProcessors();

    public static void main(String[] args) throws Exception {

        try (var file = new RandomAccessFile(FILE, "r")) {
            var chunks = Aggregator.buildChunks(file, MAX_THREADS);
            var chunksCount = chunks.size();
            var threads = new Thread[chunksCount];
            var result = new AtomicReference<Aggregator>();
            for (int i = 0; i < chunksCount; ++i) {
                var agg = new Aggregator();
                var chunk = chunks.get(i);
                var thread = new Thread(() -> {
                    agg.processChunk(chunk);
                    while (!result.compareAndSet(null, agg)) {
                        Aggregator other = result.getAndSet(null);
                        if (other != null) {
                            agg.merge(other);
                        }
                    }
                });
                thread.start();
                threads[i] = thread;
            }
            for (int i = 0; i < chunksCount; ++i) {
                threads[i].join();
            }
            var total = result.get();
            System.out.println(total == null ? "{}" : total.toString());
            System.out.close();
        }
    }

    private static class Aggregator {
        private static final int MAX_STATIONS = 10_000;
        private static final int INDEX_SIZE = 256 * 1024 * 8;
        private static final int INDEX_MASK = (INDEX_SIZE - 1) & ~7;

        private static final int HEADER_SIZE = 8;
        private static final int MAX_KEY_SIZE = 100;
        private static final int FLD_COUNT = 0; // long
        private static final int FLD_SUM = 8; // long
        private static final int FLD_MIN = 16; // int
        private static final int FLD_MAX = 20; // int
        private static final int FLD_HASH = 24; // int
        private static final int FIELDS_SIZE = 28 + 4; // +padding to align to 8 bytes
        private static final int MAX_STATION_SIZE = HEADER_SIZE + MAX_KEY_SIZE + FIELDS_SIZE;

        // Offset 0 of `mem` is reserved so that 0 can keep meaning "empty slot"
        // in the index (it used to be the null address).
        private static final long MEM_START = 8;

        // The algorithm below is written for little-endian byte order. Declaring it
        // explicitly keeps results correct everywhere, and is free on LE hardware.
        private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
        private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

        // Zero-initialised off-heap memory that is released by the GC once unreachable.
        // (ofAuto, unlike ofConfined, may be accessed from any thread, which merge() needs.)
        private static MemorySegment alloc(long size) {
            return Arena.ofAuto().allocate(size, 8);
        }

        // Poor man's hash map: hash code to offset in `mem`.
        private final MemorySegment index = alloc(INDEX_SIZE);

        // Contiguous storage of key (station name) and stats fields of all
        // unique stations.
        // The idea here is to improve locality so that stats fields would
        // possibly be already in the CPU cache after we are done comparing
        // the key.
        private final MemorySegment mem = alloc(MEM_START + (long) MAX_STATIONS * MAX_STATION_SIZE);
        private long memUsed = MEM_START;
        private int count = 0;

        static List<Chunk> buildChunks(RandomAccessFile file, int count) throws IOException {
            var fileSize = file.length();
            var chunks = new ArrayList<Chunk>();
            if (fileSize == 0) {
                return chunks;
            }
            var chunkSize = Math.min(Integer.MAX_VALUE - 512, fileSize / count);
            if (chunkSize <= 0) {
                chunkSize = fileSize;
            }
            var mmap = file.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global());
            long chunkStart = 0;
            while (chunkStart < fileSize) {
                long pos = chunkStart + chunkSize;
                if (pos < fileSize) {
                    while (pos < fileSize && mmap.get(BYTE, pos) != '\n') {
                        pos += 1;
                    }
                    pos = Math.min(pos + 1, fileSize);
                }
                else {
                    pos = fileSize;
                }
                chunks.add(new Chunk(mmap, chunkStart, pos));
                chunkStart = pos;
            }
            return chunks;
        }

        Aggregator processChunk(Chunk chunk) {
            // The parser below reads whole longs, so it may look a few bytes past
            // the end of the last line of a chunk. Within the file that is fine,
            // but MemorySegment (rightly) bounds-checks, so we can't do it past
            // the end of the file.
            final long WANT_PADDING = 8;
            final MemorySegment file = chunk.file;
            if (chunk.end + WANT_PADDING <= file.byteSize()) {
                return processChunk(file, chunk.start, chunk.end);
            }

            // Otherwise, to avoid checking if it is safe to read a whole long
            // near the end of the file, we copy the last couple of lines to a
            // padded buffer and process that part separately.
            long pos = Math.max(chunk.start - 1, chunk.end - WANT_PADDING - 1);
            while (pos >= chunk.start && file.get(BYTE, pos) != '\n') {
                pos--;
            }
            pos++;
            if (pos > chunk.start) {
                processChunk(file, chunk.start, pos);
            }
            long tailLen = chunk.end - pos;
            var tail = alloc(tailLen + WANT_PADDING);
            MemorySegment.copy(file, pos, tail, 0, tailLen);
            processChunk(tail, 0, tailLen);
            return this;
        }

        private Aggregator processChunk(MemorySegment src, long startPos, long endPos) {
            long pos = startPos;
            while (pos < endPos) {

                long start = pos;
                long keyLong = src.get(LONG, pos);
                long valueSepMark = valueSepMark(keyLong);
                if (valueSepMark != 0) {
                    int tailBits = tailBits(valueSepMark);
                    pos += valueOffset(tailBits);
                    // assert (src.get(BYTE, pos - 1) == ';') : "Expected ';' (1), pos=" + (pos - startPos);
                    long tailAndLen = tailAndLen(tailBits, keyLong, pos - start - 1);

                    long valueLong = src.get(LONG, pos);
                    int decimalSepMark = decimalSepMark(valueLong);
                    pos += nextKeyOffset(decimalSepMark);
                    // assert (src.get(BYTE, pos - 1) == '\n') : "Expected '\\n' (1), pos=" + (pos - startPos);
                    int measurement = decimalValue(decimalSepMark, valueLong);

                    add1(src, start, tailAndLen, hash(hash1(tailAndLen)), measurement);
                    continue;
                }

                pos += 8;
                long keyLong1 = keyLong;
                keyLong = src.get(LONG, pos);
                valueSepMark = valueSepMark(keyLong);
                if (valueSepMark != 0) {
                    int tailBits = tailBits(valueSepMark);
                    pos += valueOffset(tailBits);
                    // assert (src.get(BYTE, pos - 1) == ';') : "Expected ';' (2), pos=" + (pos - startPos);
                    long tailAndLen = tailAndLen(tailBits, keyLong, pos - start - 1);

                    long valueLong = src.get(LONG, pos);
                    int decimalSepMark = decimalSepMark(valueLong);
                    pos += nextKeyOffset(decimalSepMark);
                    // assert (src.get(BYTE, pos - 1) == '\n') : "Expected '\\n' (2), pos=" + (pos - startPos);
                    int measurement = decimalValue(decimalSepMark, valueLong);

                    add2(src, start, keyLong1, tailAndLen, hash(hash(hash1(keyLong1), tailAndLen)), measurement);
                    continue;
                }

                long hash = hash1(keyLong1);
                do {
                    pos += 8;
                    hash = hash(hash, keyLong);
                    keyLong = src.get(LONG, pos);
                    valueSepMark = valueSepMark(keyLong);
                } while (valueSepMark == 0);
                int tailBits = tailBits(valueSepMark);
                pos += valueOffset(tailBits);
                // assert (src.get(BYTE, pos - 1) == ';') : "Expected ';' (N), pos=" + (pos - startPos);
                long tailAndLen = tailAndLen(tailBits, keyLong, pos - start - 1);
                hash = hash(hash, tailAndLen);

                long valueLong = src.get(LONG, pos);
                int decimalSepMark = decimalSepMark(valueLong);
                pos += nextKeyOffset(decimalSepMark);
                // assert (src.get(BYTE, pos - 1) == '\n') : "Expected '\\n' (N), pos=" + (pos - startPos);
                int measurement = decimalValue(decimalSepMark, valueLong);

                addN(src, start, tailAndLen, hash(hash), measurement);
            }

            return this;
        }

        private static long hash1(long value) {
            return value;
        }

        private static long hash(long hash, long value) {
            return hash ^ value;
        }

        private static int hash(long hash) {
            hash *= 0x9E3779B97F4A7C15L; // Fibonacci hashing multiplier
            return (int) (hash >>> 39);
        }

        private static long valueSepMark(long keyLong) {
            // Seen this trick used in multiple other solutions.
            // Nice breakdown here: https://graphics.stanford.edu/~seander/bithacks.html#ZeroInWord
            long match = keyLong ^ 0x3B3B3B3B_3B3B3B3BL; // 3B == ';'
            match = (match - 0x01010101_01010101L) & (~match & 0x80808080_80808080L);
            return match;
        }

        private static int tailBits(long valueSepMark) {
            return Long.numberOfTrailingZeros(valueSepMark >>> 7);
        }

        private static int valueOffset(int tailBits) {
            return (int) (tailBits >>> 3) + 1;
        }

        private static long tailAndLen(int tailBits, long keyLong, long keyLen) {
            long tailMask = ~(-1L << tailBits);
            long tail = keyLong & tailMask;
            return (tail << 8) | (keyLen & 0xFF);
        }

        private static int decimalSepMark(long value) {
            // Seen this trick used in multiple other solutions.
            // Looks like the original author is @merykitty.

            // The 4th binary digit of the ascii of a digit is 1 while
            // that of the '.' is 0. This finds the decimal separator
            // The value can be 12, 20, 28
            return Long.numberOfTrailingZeros(~value & 0x10101000);
        }

        private static int decimalValue(int decimalSepMark, long value) {
            // Seen this trick used in multiple other solutions.
            // Looks like the original author is @merykitty.

            int shift = 28 - decimalSepMark;
            // signed is -1 if negative, 0 otherwise
            long signed = (~value << 59) >> 63;
            long designMask = ~(signed & 0xFF);
            // Align the number to a specific position and transform the ascii code
            // to actual digit value in each byte
            long digits = ((value & designMask) << shift) & 0x0F000F0F00L;

            // Now digits is in the form 0xUU00TTHH00 (UU: units digit, TT: tens digit, HH: hundreds digit)
            // 0xUU00TTHH00 * (100 * 0x1000000 + 10 * 0x10000 + 1) =
            // 0x000000UU00TTHH00 +
            // 0x00UU00TTHH000000 * 10 +
            // 0xUU00TTHH00000000 * 100
            // Now TT * 100 has 2 trailing zeroes and HH * 100 + TT * 10 + UU < 0x400
            // This results in our value lies in the bit 32 to 41 of this product
            // That was close :)
            long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;
            return (int) ((absValue ^ signed) - signed);
        }

        private static int nextKeyOffset(int decimalSepMark) {
            return (decimalSepMark >>> 3) + 3;
        }

        private void add1(MemorySegment src, long keyStart, long tailAndLen, int hash, int measurement) {
            int idx = hash & INDEX_MASK;
            for (long entry; (entry = index.get(LONG, idx)) != 0; idx = (idx + 8) & INDEX_MASK) {
                if (update1(entry, tailAndLen, measurement)) {
                    return;
                }
            }
            index.set(LONG, idx, create(src, keyStart, tailAndLen, hash, measurement, '1'));
        }

        private void add2(MemorySegment src, long keyStart, long keyLong, long tailAndLen, int hash, int measurement) {
            int idx = hash & INDEX_MASK;
            for (long entry; (entry = index.get(LONG, idx)) != 0; idx = (idx + 8) & INDEX_MASK) {
                if (update2(entry, keyLong, tailAndLen, measurement)) {
                    return;
                }
            }
            index.set(LONG, idx, create(src, keyStart, tailAndLen, hash, measurement, '2'));
        }

        private void addN(MemorySegment src, long keyStart, long tailAndLen, int hash, int measurement) {
            int idx = hash & INDEX_MASK;
            for (long entry; (entry = index.get(LONG, idx)) != 0; idx = (idx + 8) & INDEX_MASK) {
                if (updateN(entry, src, keyStart, tailAndLen, measurement)) {
                    return;
                }
            }
            index.set(LONG, idx, create(src, keyStart, tailAndLen, hash, measurement, 'N'));
        }

        private long create(MemorySegment src, long keyStart, long tailAndLen, int hash, int measurement, char _origin) {
            // assert (memUsed + MAX_STATION_SIZE <= mem.byteSize()) : "Too many stations";

            final long entry = memUsed;

            int keySize = (int) (tailAndLen & 0xF8);
            long fields = entry + HEADER_SIZE + keySize;
            memUsed += HEADER_SIZE + keySize + FIELDS_SIZE;
            count++;

            mem.set(LONG, entry, tailAndLen);
            MemorySegment.copy(src, keyStart, mem, entry + HEADER_SIZE, keySize);
            mem.set(LONG, fields + FLD_COUNT, 1L);
            mem.set(LONG, fields + FLD_SUM, (long) measurement);
            mem.set(INT, fields + FLD_MIN, measurement);
            mem.set(INT, fields + FLD_MAX, measurement);
            mem.set(INT, fields + FLD_HASH, hash);

            return entry;
        }

        private boolean update1(long entry, long tailAndLen, int measurement) {
            if (mem.get(LONG, entry) != tailAndLen) {
                return false;
            }

            updateStats(entry + HEADER_SIZE, measurement);
            return true;
        }

        private boolean update2(long entry, long keyLong, long tailAndLen, int measurement) {
            if (mem.get(LONG, entry) != tailAndLen) {
                return false;
            }
            if (mem.get(LONG, entry + 8) != keyLong) {
                return false;
            }

            updateStats(entry + HEADER_SIZE + 8, measurement);
            return true;
        }

        private boolean updateN(long entry, MemorySegment src, long keyStart, long tailAndLen, int measurement) {
            if (mem.get(LONG, entry) != tailAndLen) {
                return false;
            }
            long memPos = entry + HEADER_SIZE;
            long memEnd = memPos + ((int) (tailAndLen & 0xF8));
            long bufPos = keyStart;
            while (memPos != memEnd) {
                if (mem.get(LONG, memPos) != src.get(LONG, bufPos)) {
                    return false;
                }
                memPos += 8;
                bufPos += 8;
            }

            updateStats(memPos, measurement);
            return true;
        }

        private void updateStats(long addr, int measurement) {
            long oldCount = mem.get(LONG, addr + FLD_COUNT);
            long oldSum = mem.get(LONG, addr + FLD_SUM);
            long oldMin = mem.get(INT, addr + FLD_MIN);
            long oldMax = mem.get(INT, addr + FLD_MAX);

            mem.set(LONG, addr + FLD_COUNT, oldCount + 1);
            mem.set(LONG, addr + FLD_SUM, oldSum + measurement);
            if (measurement < oldMin) {
                mem.set(INT, addr + FLD_MIN, measurement);
            }
            if (measurement > oldMax) {
                mem.set(INT, addr + FLD_MAX, measurement);
            }
        }

        private void updateStats(long addr, long count, long sum, int min, int max) {
            long oldCount = mem.get(LONG, addr + FLD_COUNT);
            long oldSum = mem.get(LONG, addr + FLD_SUM);
            long oldMin = mem.get(INT, addr + FLD_MIN);
            long oldMax = mem.get(INT, addr + FLD_MAX);

            mem.set(LONG, addr + FLD_COUNT, oldCount + count);
            mem.set(LONG, addr + FLD_SUM, oldSum + sum);
            if (min < oldMin) {
                mem.set(INT, addr + FLD_MIN, min);
            }
            if (max > oldMax) {
                mem.set(INT, addr + FLD_MAX, max);
            }
        }

        public Aggregator merge(Aggregator other) {
            var otherMem = other.mem;
            var otherMemPos = MEM_START;
            var otherMemEnd = other.memUsed;
            merge: for (long entrySize; otherMemPos < otherMemEnd; otherMemPos += entrySize) {
                int keySize = (int) (otherMem.get(LONG, otherMemPos) & 0xF8);
                long otherKeyEnd = otherMemPos + HEADER_SIZE + keySize;
                entrySize = HEADER_SIZE + keySize + FIELDS_SIZE;
                int hash = otherMem.get(INT, otherKeyEnd + FLD_HASH);
                int idx = hash & INDEX_MASK;
                search: for (long entry; (entry = index.get(LONG, idx)) != 0; idx = (idx + 8) & INDEX_MASK) {
                    var thisPos = entry;
                    var otherPos = otherMemPos;
                    while (otherPos < otherKeyEnd) {
                        if (mem.get(LONG, thisPos) != otherMem.get(LONG, otherPos)) {
                            continue search;
                        }
                        thisPos += 8;
                        otherPos += 8;
                    }
                    updateStats(
                            thisPos,
                            otherMem.get(LONG, otherPos + FLD_COUNT),
                            otherMem.get(LONG, otherPos + FLD_SUM),
                            otherMem.get(INT, otherPos + FLD_MIN),
                            otherMem.get(INT, otherPos + FLD_MAX));
                    continue merge;
                }

                // create
                // assert (memUsed + entrySize <= mem.byteSize()) : "Too many stations (merge)";
                long entry = memUsed;
                memUsed += entrySize;
                count++;
                MemorySegment.copy(otherMem, otherMemPos, mem, entry, entrySize);
                index.set(LONG, idx, entry);
            }
            return this;
        }

        @Override
        public String toString() {
            if (count == 0) {
                return "{}";
            }
            var entries = new Entry[count];
            int i = 0;
            for (long pos = MEM_START; pos < memUsed; pos += (int) (mem.get(LONG, pos) & 0xF8) + HEADER_SIZE + FIELDS_SIZE) {
                entries[i++] = new Entry(mem, pos);
            }
            Arrays.sort(entries);
            var sb = new StringBuilder(count * 50);
            sb.append('{');
            entries[0].appendTo(sb);
            for (int j = 1; j < entries.length; ++j) {
                sb.append(", ");
                entries[j].appendTo(sb);
            }
            sb.append('}');
            return sb.toString();
        }

        static class Chunk {
            final MemorySegment file;
            final long start;
            final long end;

            Chunk(MemorySegment file, long start, long end) {
                this.file = file;
                this.start = start;
                this.end = end;
            }
        }

        static class Entry implements Comparable<Entry> {
            private final MemorySegment mem;
            private final long entry;
            private final int keySize;
            private final String key;

            Entry(MemorySegment mem, long entry) {
                this.mem = mem;
                this.entry = entry;
                this.keySize = (int) (mem.get(LONG, entry) & 0xF8);
                // The header holds the full key length in its low byte and the
                // last (up to 7) key bytes right after it; the leading whole
                // 8-byte words of the key follow the header.
                int keyLen = mem.get(BYTE, entry) & 0xFF;
                var buf = new byte[keySize + 7];
                MemorySegment.copy(mem, BYTE, entry + HEADER_SIZE, buf, 0, keySize);
                MemorySegment.copy(mem, BYTE, entry + 1, buf, keySize, 7);
                this.key = new String(buf, 0, keyLen, StandardCharsets.UTF_8);
            }

            @Override
            public int compareTo(Entry other) {
                return key.compareTo(other.key);
            }

            @Override
            public String toString() {
                long pos = entry + HEADER_SIZE + keySize;
                return round(mem.get(INT, pos + FLD_MIN))
                        + "/" + round(((double) mem.get(LONG, pos + FLD_SUM)) / mem.get(LONG, pos + FLD_COUNT))
                        + "/" + round(mem.get(INT, pos + FLD_MAX));
            }

            void appendTo(StringBuilder sb) {
                long pos = entry + HEADER_SIZE + keySize;
                sb.append(key);
                sb.append('=');
                sb.append(round(mem.get(INT, pos + FLD_MIN)));
                sb.append('/');
                sb.append(round(((double) mem.get(LONG, pos + FLD_SUM)) / mem.get(LONG, pos + FLD_COUNT)));
                sb.append('/');
                sb.append(round(mem.get(INT, pos + FLD_MAX)));
            }

            private static double round(double value) {
                return Math.round(value) / 10.0;
            }
        }
    }
}

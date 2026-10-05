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

import java.lang.foreign.ValueLayout;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import static java.nio.channels.FileChannel.MapMode.READ_ONLY;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Same algorithm as the original, but built on the Foreign Function & Memory API
 * (final since Java 22) instead of sun.misc.Unsafe, whose memory-access methods are
 * deprecated for removal (JEP 471 / JEP 498).
 *
 * All "addresses" are now byte offsets into a MemorySegment, so every access is
 * bounds-checked. No --enable-native-access or --sun-misc-unsafe-memory-access flag is needed.
 */
public class CalculateAverage_armandino {

    private static final Path FILE = Path.of("./measurements.txt");

    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private static final int NUM_CHUNKS = Math.max(8, Runtime.getRuntime().availableProcessors());
    private static final int INITIAL_MAP_CAPACITY = 8192; // must be a power of two
    private static final byte SEMICOLON = 59;
    private static final byte NL = 10;
    private static final int PRIME = 1117;

    private static final int KEY_OFFSET = 0, // 100b
            HASH_OFFSET = 100, // int
            KEY_LENGTH_OFFSET = 104, // short
            MIN_OFFSET = 106, // short
            MAX_OFFSET = 108, // short
            COUNT_OFFSET = 110, // int
            SUM_OFFSET = 114; // long

    private static final long ENTRY_SIZE = 100 // key: offset=0
            + 4 // keyHash: offset=100
            + 2 // keyLength: offset=104
            + 2 // min: offset=106
            + 2 // max: offset=108
            + 4 // count: offset=110
            + 8; // sum: offset=114

    public static void main(String[] args) throws Exception {
        final MemorySegment file;
        try (var channel = FileChannel.open(FILE, StandardOpenOption.READ)) {
            // The mapping stays valid after the channel is closed; the global arena never unmaps it.
            file = channel.map(READ_ONLY, 0, channel.size(), Arena.global());
        }

        Chunk[] chunks = split(file);
        ChunkProcessor[] processors = new ChunkProcessor[chunks.length];

        for (int i = 0; i < processors.length; i++) {
            processors[i] = new ChunkProcessor(file, chunks[i].start, chunks[i].end);
            processors[i].start();
        }

        Map<String, Stats> results = new TreeMap<>();

        for (ChunkProcessor processor : processors) {
            processor.join();
            final MemorySegment table = processor.map.table;
            final long tableSize = table.byteSize();

            for (long entry = 0; entry < tableSize; entry += ENTRY_SIZE) {
                final short keyLength = table.get(SHORT, entry + KEY_LENGTH_OFFSET);

                if (keyLength == 0)
                    continue;

                final byte[] keyBytes = table.asSlice(entry + KEY_OFFSET, keyLength).toArray(BYTE);
                final short min = table.get(SHORT, entry + MIN_OFFSET);
                final short max = table.get(SHORT, entry + MAX_OFFSET);
                final int count = table.get(INT, entry + COUNT_OFFSET);
                final long sum = table.get(LONG, entry + SUM_OFFSET);
                final Stats s = new Stats(new String(keyBytes, UTF_8), min, max, count, sum);
                results.merge(s.key, s, CalculateAverage_armandino::mergeStats);
            }
        }

        print(results.values());
    }

    private static Stats mergeStats(final Stats x, final Stats y) {
        x.min = Math.min(x.min, y.min);
        x.max = Math.max(x.max, y.max);
        x.count += y.count;
        x.sum += y.sum;
        return x;
    }

    private static class ChunkProcessor extends Thread {
        private final MemorySegment file;
        private final SegmentMap map;

        final long chunkStart;
        final long chunkEnd;

        private ChunkProcessor(MemorySegment file, long chunkStart, long chunkEnd) {
            this.file = file;
            this.map = new SegmentMap(file, INITIAL_MAP_CAPACITY);
            this.chunkStart = chunkStart;
            this.chunkEnd = chunkEnd;
        }

        @Override
        public void run() {
            final MemorySegment file = this.file;
            final long fileSize = file.byteSize();

            long i = chunkStart;
            while (i < chunkEnd) {
                final long keyOffset = i;
                int keyHash = 0;
                byte b;

                while ((b = file.get(BYTE, i++)) != SEMICOLON) {
                    keyHash = PRIME * keyHash + b;
                }

                final short keyLength = (short) (i - keyOffset - 1);
                final long numberWord = readWord(file, i, fileSize);
                final int decimalSepPos = Long.numberOfTrailingZeros(~numberWord & 0x10101000);
                final short measurement = parseNumber(decimalSepPos, numberWord);
                final int addOffset = (decimalSepPos >>> 3) + 3;
                i += addOffset;

                map.addEntry(keyHash, keyOffset, keyLength, measurement);
            }
        }

        /**
         * Reads 8 bytes at the given offset. Unsafe let the original read past the end of the
         * mapping for the last record; MemorySegment is bounds-checked, so near the end of the
         * file the missing bytes are filled with zeros instead. (Little-endian, like parseNumber.)
         */
        private static long readWord(MemorySegment file, long offset, long fileSize) {
            if (offset + Long.BYTES <= fileSize) {
                return file.get(LONG, offset);
            }
            long word = 0;
            for (long j = offset; j < fileSize; j++) {
                word |= (file.get(BYTE, j) & 0xFFL) << ((j - offset) << 3);
            }
            return word;
        }

        // credit: merykitty
        private static short parseNumber(int decimalSepPos, long numberWord) {
            int shift = 28 - decimalSepPos;
            // signed is -1 if negative, 0 otherwise
            long signed = (~numberWord << 59) >> 63;
            long designMask = ~(signed & 0xFF);
            // Align the number to a specific position and transform the ascii to digit value
            long digits = ((numberWord & designMask) << shift) & 0x0F000F0F00L;
            // Now digits is in the form 0xUU00TTHH00 (UU: units digit, TT: tens digit, HH: hundreds digit)
            // 0xUU00TTHH00 * (100 * 0x1000000 + 10 * 0x10000 + 1) =
            // 0x000000UU00TTHH00 + 0x00UU00TTHH000000 * 10 + 0xUU00TTHH00000000 * 100
            long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;
            return (short) ((absValue ^ signed) - signed);
        }
    }

    private static class Stats {
        private final String key;
        private int min;
        private int max;
        private int count;
        private long sum;

        Stats(final String key, final int min, final int max, final int count, final long sum) {
            this.min = min;
            this.max = max;
            this.count = count;
            this.sum = sum;
            this.key = key;
        }

        void print(final PrintStream out) {
            out.print(key);
            out.print('=');
            out.print(round(min / 10f));
            out.print('/');
            out.print(round((sum / 10f) / count));
            out.print('/');
            out.print(round(max) / 10f);
        }

        private static double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }
    }

    private static void print(final Collection<Stats> sorted) {
        int size = sorted.size();
        System.out.print('{');
        for (Stats stats : sorted) {
            stats.print(System.out);
            if (--size > 0) {
                System.out.print(", ");
            }
        }
        System.out.println('}');
    }

    private static Chunk[] split(final MemorySegment file) {
        final long fileSize = file.byteSize();
        if (fileSize < 10000) {
            return new Chunk[]{ new Chunk(0, fileSize) };
        }

        final long chunkSize = fileSize / NUM_CHUNKS;
        final var chunks = new Chunk[NUM_CHUNKS];
        long start = 0;
        long end = chunkSize;

        for (int i = 0; i < NUM_CHUNKS; i++) {
            if (i > 0) {
                start = chunks[i - 1].end;
                end = Math.min(start + chunkSize, fileSize);
            }
            if (i == NUM_CHUNKS - 1) {
                // fileSize / NUM_CHUNKS truncates, so make sure the tail of the file is covered
                end = fileSize;
            }
            else if (end < fileSize) {
                while (end < fileSize && file.get(BYTE, end) != NL) {
                    end++;
                }
                end = Math.min(end + 1, fileSize);
            }
            chunks[i] = new Chunk(start, end);
        }
        return chunks;
    }

    private record Chunk(long start, long end) {
    }

    /**
     * Open-addressing hash table (linear probing) stored in a MemorySegment, one fixed-size
     * entry per slot. A slot is empty while its hash field is 0.
     */
    private static class SegmentMap {

        private final MemorySegment file;
        MemorySegment table;
        int capacity; // num entries, power of two
        int size;

        SegmentMap(MemorySegment file, int numEntries) {
            this.file = file;
            this.capacity = numEntries;
            // Zero-initialised; the auto arena frees it once the segment is unreachable,
            // and (unlike a confined arena) it can be read from the main thread after join().
            this.table = Arena.ofAuto().allocate(ENTRY_SIZE * numEntries, Long.BYTES);
        }

        void addEntry(final int keyHash, final long keyOffset, final short keyLength, final short measurement) {
            final int mask = capacity - 1;
            int slot = keyHash & mask;

            while (true) {
                final long entry = slot * ENTRY_SIZE;
                final int hash = table.get(INT, entry + HASH_OFFSET);

                if (hash == 0) { // new entry
                    initEntry(entry, keyOffset, keyLength, measurement, keyHash);
                    if (++size * 2 > capacity) {
                        resize();
                    }
                    return;
                }
                if (hash == keyHash && keysEqual(entry, keyOffset, keyLength)) {
                    updateEntry(entry, measurement);
                    return;
                }
                slot = (slot + 1) & mask;
            }
        }

        private void resize() {
            final MemorySegment oldTable = table;
            final long oldSize = oldTable.byteSize();
            final int newCapacity = capacity * 2;
            final MemorySegment newTable = Arena.ofAuto().allocate(ENTRY_SIZE * newCapacity, Long.BYTES);
            final int mask = newCapacity - 1;

            for (long oldEntry = 0; oldEntry < oldSize; oldEntry += ENTRY_SIZE) {
                final int hash = oldTable.get(INT, oldEntry + HASH_OFFSET);
                if (hash == 0)
                    continue;

                int slot = hash & mask;
                while (newTable.get(INT, slot * ENTRY_SIZE + HASH_OFFSET) != 0) {
                    slot = (slot + 1) & mask;
                }
                // copy the whole entry, including the key bytes
                MemorySegment.copy(oldTable, oldEntry, newTable, slot * ENTRY_SIZE, ENTRY_SIZE);
            }

            this.table = newTable;
            this.capacity = newCapacity;
        }

        private void initEntry(final long entry, final long keyOffset, final short keyLength, final short measurement, final int keyHash) {
            MemorySegment.copy(file, keyOffset, table, entry + KEY_OFFSET, keyLength);
            table.set(INT, entry + HASH_OFFSET, keyHash);
            table.set(SHORT, entry + KEY_LENGTH_OFFSET, keyLength);
            table.set(SHORT, entry + MIN_OFFSET, Short.MAX_VALUE);
            table.set(SHORT, entry + MAX_OFFSET, Short.MIN_VALUE);

            updateEntry(entry, measurement);
        }

        private void updateEntry(final long entry, final short measurement) {
            table.set(SHORT, entry + MIN_OFFSET,
                    (short) Math.min(table.get(SHORT, entry + MIN_OFFSET), measurement));
            table.set(SHORT, entry + MAX_OFFSET,
                    (short) Math.max(table.get(SHORT, entry + MAX_OFFSET), measurement));
            table.set(INT, entry + COUNT_OFFSET,
                    table.get(INT, entry + COUNT_OFFSET) + 1);
            table.set(LONG, entry + SUM_OFFSET,
                    table.get(LONG, entry + SUM_OFFSET) + measurement);
        }

        private boolean keysEqual(final long entry, final long keyOffset, final int keyLength) {
            // credit: abeobk
            long xsum = 0;
            int n = keyLength & 0xF8;
            for (int i = 0; i < n; i += 8) {
                xsum |= (table.get(LONG, entry + KEY_OFFSET + i) ^ file.get(LONG, keyOffset + i));
            }
            return xsum == 0;
        }
    }
}

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
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Same algorithm as the original, but memory is accessed through the Foreign
 * Function &amp; Memory API (java.lang.foreign, final since JDK 22) instead of
 * sun.misc.Unsafe, whose memory-access methods are terminally deprecated
 * (JEP 471 / JEP 498) and are being phased out.
 *
 * Requires JDK 22+. No --enable-native-access or --add-exports flags needed.
 */
public class CalculateAverage_JamalMulla {

    private static final long ALL_SEMIS = 0x3B3B3B3B3B3B3B3BL;
    private static final Map<String, ResultRow> global = new TreeMap<>();
    private static final String FILE = "./measurements.txt";
    private static final Lock lock = new ReentrantLock();
    private static final long FXSEED = 0x517cc1b727220a95L;

    // The word-at-a-time tricks below assume little-endian byte order, so say so explicitly
    // rather than relying on the platform's native order.
    private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private static final long[] masks = {
            0x0,
            0x00000000000000FFL,
            0x000000000000FFFFL,
            0x0000000000FFFFFFL,
            0x00000000FFFFFFFFL,
            0x000000FFFFFFFFFFL,
            0x0000FFFFFFFFFFFFL,
            0x00FFFFFFFFFFFFFFL
    };

    private static final class ResultRow {
        private int min;
        private int max;
        private long sum;
        private int count;
        // offset of the station name within the mapped file
        private final long keyStart;
        private final byte keyLength;

        private ResultRow(int v, final long keyStart, final byte keyLength) {
            this.min = v;
            this.max = v;
            this.sum = v;
            this.count = 1;
            this.keyStart = keyStart;
            this.keyLength = keyLength;
        }

        public String toString() {
            return round(min) + "/" + round((double) (sum) / count) + "/" + round(max);
        }

        private double round(double value) {
            return Math.round(value) / 10.0;
        }

    }

    // [start, end) byte offsets into the mapped file; end is just past a '\n' (or EOF)
    private record Chunk(long start, long end) {
    }

    static List<Chunk> getChunks(int numThreads, MemorySegment file) {
        final long size = file.byteSize();
        final long roughChunkSize = Math.max(1, size / numThreads);
        final List<Chunk> chunks = new ArrayList<>(numThreads);

        long start = 0;
        while (start < size) {
            long end = Math.min(start + roughChunkSize, size);
            // extend to the end of the current line
            while (end < size && file.get(BYTE, end - 1) != 0xA /* \n */) {
                end++;
            }
            chunks.add(new Chunk(start, end));
            start = end;
        }
        return chunks;
    }

    /**
     * Reads 8 bytes at {@code offset}. The hot loop deliberately reads whole words, which can
     * overshoot the end of the file by a few bytes on the very last line. Unsafe silently allowed
     * that; MemorySegment bounds-checks, so near EOF we assemble the word byte by byte and
     * zero-fill the missing bytes.
     */
    private static long getWord(MemorySegment file, long offset) {
        if (offset <= file.byteSize() - Long.BYTES) {
            return file.get(LONG_LE, offset);
        }
        return getWordNearEnd(file, offset);
    }

    private static long getWordNearEnd(MemorySegment file, long offset) {
        long word = 0;
        final long n = Math.min(Long.BYTES, file.byteSize() - offset);
        for (long k = 0; k < n; k++) {
            word |= (file.get(BYTE, offset + k) & 0xFFL) << (k * 8);
        }
        return word;
    }

    private static void run(MemorySegment file, Chunk chunk) {

        // can't have more than 10000 unique keys but want to match max hash
        final int MAPSIZE = 65536;
        final ResultRow[] slots = new ResultRow[MAPSIZE];

        byte nameLength;
        int temp;
        long hash;

        long i = chunk.start;
        final long cl = chunk.end;
        long word;
        long hs;
        long start;
        byte c;
        int slot;
        long n;
        ResultRow slotValue;

        while (i < cl) {
            start = i;
            hash = 0;

            word = getWord(file, i);

            while (true) {
                n = word ^ ALL_SEMIS;
                hs = (n - 0x0101010101010101L) & (~n & 0x8080808080808080L);
                if (hs != 0)
                    break;
                hash = (hash ^ word) * FXSEED;
                i += 8;
                word = getWord(file, i);
            }

            i += Long.numberOfTrailingZeros(hs) >> 3;

            // hash of what's left ((hs >>> 7) - 1) masks off the bytes from word that are before the semicolon
            hash = (hash ^ word & (hs >>> 7) - 1) * FXSEED;
            nameLength = (byte) (i++ - start);

            // temperature value follows
            c = file.get(BYTE, i++);
            // we know the val has to be between -99.9 and 99.8
            // always with a single fractional digit
            // represented as a byte array of either 4 or 5 characters
            if (c != 0x2D /* minus sign */) {
                // could be either n.x or nn.x
                if (file.get(BYTE, i + 2) == 0xA) {
                    temp = (c - 48) * 10; // char 1
                }
                else {
                    temp = (c - 48) * 100; // char 1
                    temp += (file.get(BYTE, i++) - 48) * 10; // char 2
                }
                temp += (file.get(BYTE, ++i) - 48); // char 3
            }
            else {
                // could be either n.x or nn.x
                if (file.get(BYTE, i + 3) == 0xA) {
                    temp = (file.get(BYTE, i) - 48) * 10; // char 1
                    i += 2;
                }
                else {
                    temp = (file.get(BYTE, i) - 48) * 100; // char 1
                    temp += (file.get(BYTE, i + 1) - 48) * 10; // char 2
                    i += 3;
                }
                temp += (file.get(BYTE, i) - 48); // char 2
                temp = -temp;
            }
            i += 2;

            // xor folding
            slot = (int) (hash ^ hash >> 32) & 65535;

            // Linear probe for open slot
            while ((slotValue = slots[slot]) != null && (slotValue.keyLength != nameLength || !keyEquals(file, slotValue.keyStart, start, nameLength))) {
                slot = (slot + 1) % MAPSIZE;
            }

            // existing
            if (slotValue != null) {
                slotValue.sum += temp;
                slotValue.count++;
                if (temp > slotValue.max) {
                    slotValue.max = temp;
                    continue;
                }
                if (temp < slotValue.min)
                    slotValue.min = temp;

            }
            else {
                // new value
                slots[slot] = new ResultRow(temp, start, nameLength);
            }
        }

        // merge results with overall results
        ResultRow rr;
        String key;
        byte[] bytes;
        lock.lock();
        try {
            for (ResultRow resultRow : slots) {
                if (resultRow != null) {
                    bytes = new byte[resultRow.keyLength];
                    // copy the name bytes
                    MemorySegment.copy(file, BYTE, resultRow.keyStart, bytes, 0, resultRow.keyLength);
                    key = new String(bytes, StandardCharsets.UTF_8);
                    if ((rr = global.get(key)) != null) {
                        rr.min = Math.min(rr.min, resultRow.min);
                        rr.max = Math.max(rr.max, resultRow.max);
                        rr.count += resultRow.count;
                        rr.sum += resultRow.sum;
                    }
                    else {
                        global.put(key, resultRow);
                    }
                }
            }
        }
        finally {
            lock.unlock();
        }

    }

    static boolean keyEquals(MemorySegment file, final long a_offset, final long b_offset, final byte b_length) {
        // byte by byte comparisons are slow, so do as big chunks as possible
        byte i = 0;
        for (; i < (b_length & -8); i += 8) {
            if (getWord(file, a_offset + i) != getWord(file, b_offset + i)) {
                return false;
            }
        }
        if (i == b_length)
            return true;
        final long mask = masks[b_length - i];
        return (getWord(file, a_offset + i) & mask) == (getWord(file, b_offset + i) & mask);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        // The shared arena owns the mapping; it is closed (and the file unmapped)
        // only after every worker thread has finished.
        try (FileChannel channel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ);
                Arena arena = Arena.ofShared()) {

            final MemorySegment file = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);

            int numThreads = 1;
            if (file.byteSize() > 64000) {
                numThreads = Runtime.getRuntime().availableProcessors();
            }

            final List<Chunk> chunks = getChunks(numThreads, file);
            final List<Thread> threads = new ArrayList<>(chunks.size());
            for (Chunk chunk : chunks) {
                Thread thread = new Thread(() -> run(file, chunk));
                thread.setPriority(Thread.MAX_PRIORITY);
                thread.start();
                threads.add(thread);
            }
            for (Thread t : threads) {
                t.join();
            }
            System.out.println(global);
        }
    }
}

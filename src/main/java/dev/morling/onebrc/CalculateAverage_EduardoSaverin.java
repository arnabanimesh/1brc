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

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.nio.file.StandardOpenOption.READ;

/**
 * Java 22+ (tested on JDK 27). Uses only the standard Foreign Function & Memory API
 * (java.lang.foreign, final since JDK 22) instead of sun.misc.Unsafe, whose memory-access
 * methods are deprecated for removal (JEP 471) and warn at run time (JEP 498).
 * <p>
 * Nothing here is a "restricted" FFM method (no reinterpret(), no Linker), so no
 * --enable-native-access flag is needed either.
 */
public class CalculateAverage_EduardoSaverin {
    private static final Path FILE = Path.of("./measurements.txt");
    private static final int NO_OF_THREADS = Runtime.getRuntime().availableProcessors();
    private static final int FNV_32_OFFSET = 0x811c9dc5;
    private static final int FNV_32_PRIME = 0x01000193;
    private static final int MAX_NAME_LENGTH = 100;
    private static final Map<String, ResultRow> resultRowMap = new HashMap<>();
    private static final Lock lock = new ReentrantLock();

    public record Chunk(long start, long length) {
    }

    record MapEntry(String key, ResultRow row) {
    }

    private static final class ResultRow {
        private double min;
        private double max;
        private double sum;
        private int count;

        private ResultRow(double v) {
            this.min = v;
            this.max = v;
            this.sum = v;
            this.count = 1;
        }

        @Override
        public String toString() {
            return round(min) + "/" + round(sum / count) + "/" + round(max);
        }

        private double round(double value) {
            return Math.round(value) / 10.0;
        }
    }

    /**
     * Splits the file into at most NO_OF_THREADS chunks, each ending right after a '\n'
     * (0xA) so no line is ever split between two threads.
     */
    static List<Chunk> getChunks(MemorySegment file) {
        final long fileBytes = file.byteSize();
        final List<Chunk> chunks = new ArrayList<>();
        if (fileBytes == 0) {
            return chunks;
        }
        final int numThreads = fileBytes > 64000 ? NO_OF_THREADS : 1;
        final long chunkSize = (fileBytes + numThreads - 1) / numThreads; // round up

        long chunkStart = 0;
        while (chunkStart < fileBytes) {
            long chunkEnd = Math.min(chunkStart + chunkSize, fileBytes);
            // Extend until the chunk ends just after a newline (or at EOF)
            while (chunkEnd < fileBytes && file.get(JAVA_BYTE, chunkEnd - 1) != 0xA) {
                chunkEnd++;
            }
            chunks.add(new Chunk(chunkStart, chunkEnd - chunkStart));
            chunkStart = chunkEnd;
        }
        return chunks;
    }

    static class SimplerHashMap {
        static final int MAPSIZE = 65536; // must be a power of two
        static final int MASK = MAPSIZE - 1;
        final ResultRow[] slots = new ResultRow[MAPSIZE];
        final byte[][] keys = new byte[MAPSIZE][];

        public void putOrMerge(final byte[] key, final int length, final int hash, final int temp) {
            int slot = hash & MASK;

            // Linear probing on collision, wrapping around at the end of the table
            while (true) {
                final ResultRow slotValue = slots[slot];

                // New key
                if (slotValue == null) {
                    slots[slot] = new ResultRow(temp);
                    keys[slot] = Arrays.copyOf(key, length);
                    return;
                }

                // Existing key (Arrays.equals on ranges is a vectorized JDK intrinsic)
                final byte[] slotKey = keys[slot];
                if (slotKey.length == length && Arrays.equals(slotKey, 0, length, key, 0, length)) {
                    slotValue.min = Math.min(slotValue.min, temp);
                    slotValue.max = Math.max(slotValue.max, temp);
                    slotValue.sum += temp;
                    slotValue.count++;
                    return;
                }

                slot = (slot + 1) & MASK;
            }
        }

        // Get all pairs
        public List<MapEntry> getAll() {
            final List<MapEntry> result = new ArrayList<>();
            for (int i = 0; i < slots.length; i++) {
                ResultRow slotValue = slots[i];
                if (slotValue != null) {
                    result.add(new MapEntry(new String(keys[i], StandardCharsets.UTF_8), slotValue));
                }
            }
            return result;
        }
    }

    private static class Task implements Runnable {

        private final SimplerHashMap results;
        private final MemorySegment segment; // this chunk only, offsets start at 0

        public Task(MemorySegment segment) {
            this.results = new SimplerHashMap();
            this.segment = segment;
        }

        @Override
        public void run() {
            final MemorySegment seg = segment;
            final long end = seg.byteSize();
            // Max length of any city name
            final byte[] nameBytes = new byte[MAX_NAME_LENGTH];

            long i = 0;
            while (i < end) {
                int nameLength = 0;
                int hash = FNV_32_OFFSET;
                byte c;

                // 0x3B is ;
                while ((c = seg.get(JAVA_BYTE, i++)) != 0x3B) {
                    nameBytes[nameLength++] = c;
                    // FNV-1a hash : https://en.wikipedia.org/wiki/Fowler–Noll–Vo_hash_function
                    hash ^= (c & 0xFF);
                    hash *= FNV_32_PRIME;
                }

                // Temperature just after Semicolon: [-]D.D or [-]DD.D, stored as tenths
                // Below you will see -48 which is used to convert from ASCII to Integer, 48 represents 0 in ASCII
                c = seg.get(JAVA_BYTE, i++);
                // 0x2D is Minus(-)
                final boolean negative = (c == 0x2D);
                if (negative) {
                    c = seg.get(JAVA_BYTE, i++);
                }
                int temp = c - 48;
                while ((c = seg.get(JAVA_BYTE, i++)) != 0x2E) { // 0x2E is Dot(.)
                    temp = temp * 10 + (c - 48);
                }
                temp = temp * 10 + (seg.get(JAVA_BYTE, i++) - 48); // Number after dot
                i++; // Since Parsed Line, Next thing must be newline

                results.putOrMerge(nameBytes, nameLength, hash, negative ? -temp : temp);
            }

            List<MapEntry> all = results.getAll();
            lock.lock();
            try {
                for (MapEntry me : all) {
                    ResultRow rr;
                    ResultRow lr = me.row;
                    if ((rr = resultRowMap.get(me.key)) != null) {
                        rr.min = Math.min(rr.min, lr.min);
                        rr.max = Math.max(rr.max, lr.max);
                        rr.count += lr.count;
                        rr.sum += lr.sum;
                    }
                    else {
                        resultRowMap.put(me.key, lr);
                    }
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            finally {
                lock.unlock();
            }
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        // A shared arena lets every worker thread read the mapping, and closing it
        // after the join unmaps the file deterministically.
        try (FileChannel fileChannel = FileChannel.open(FILE, READ);
                Arena arena = Arena.ofShared()) {
            MemorySegment file = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size(), arena);

            List<Thread> threads = new ArrayList<>();
            for (Chunk chunk : getChunks(file)) {
                Task task = new Task(file.asSlice(chunk.start(), chunk.length()));
                threads.add(Thread.ofPlatform().start(task));
            }
            for (Thread thread : threads) {
                thread.join();
            }
        }
        System.out.println(new TreeMap<>(resultRowMap));
    }
}

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Requires Java 22+ (java.lang.foreign is final from 22 on). Uses no sun.misc.Unsafe,
 * no restricted methods and no JVM flags.
 */
public class CalculateAverage_Smoofie {

    private static final String FILE = "./measurements.txt";

    private static final int HASH_BITS = 16;
    private static final int MAX_STATIONS = 10_000;
    private static final int BINS = 1000; // 0.0 .. 99.9 in tenths, one half for >= 0 and one for negatives

    // The last bytes of the file are parsed on a bounds-safe slow path so the 8-byte word reads
    // in the hot loop can never run past the end of the mapping.
    private static final int TAIL_MARGIN = 256;

    private static final long HASH_MULTIPLIER = 0x9E3779B97F4A7C15L;

    // The SWAR tricks below assume little-endian word layout; being explicit keeps them correct on any CPU.
    private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private static final class MeasurementAggregator {
        private int min = Integer.MAX_VALUE;
        private int max = Integer.MIN_VALUE;
        private long sum = 0;
        private long count = 0;

        private void add(int value, long times) {
            if (times > 0) {
                min = Math.min(min, value);
                max = Math.max(max, value);
                sum += value * times;
                count += times;
            }
        }

        private void merge(MeasurementAggregator other) {
            min = Math.min(min, other.min);
            max = Math.max(max, other.max);
            sum += other.sum;
            count += other.count;
        }

        @Override
        public String toString() {
            return ((double) min) / 10 + "/" + round(sum / 10.0 / count) + "/" + ((double) max) / 10;
        }

        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }
    }

    /**
     * Per-thread station table. Stations are chained per hash bucket; indexes are stored +1 so that 0 means "none".
     * Names are not copied: each station remembers where its first occurrence lives in the mapped file.
     */
    private static final class StationTable {
        private final int[] bucketHead = new int[1 << HASH_BITS];
        private final int[] chainNext = new int[MAX_STATIONS];
        private final int[] nameLength = new int[MAX_STATIONS];
        private final long[] nameOffset = new long[MAX_STATIONS];
        private final int[][] histograms = new int[MAX_STATIONS][]; // [0, BINS) = value >= 0, [BINS, 2*BINS) = -value
        private int size;

        private int add(int bucket, long offset, int length) {
            if (size == MAX_STATIONS) {
                throw new IllegalStateException("More than " + MAX_STATIONS + " distinct stations");
            }
            int id = size++;
            nameOffset[id] = offset;
            nameLength[id] = length;
            histograms[id] = new int[2 * BINS];
            chainNext[id] = bucketHead[bucket];
            bucketHead[bucket] = id + 1;
            return id;
        }
    }

    private record ChunkResult(Map<String, MeasurementAggregator> stats, long resumeAt) {
    }

    private static long locateSemicolon(long word) {
        long x = word ^ 0x3B3B3B3B3B3B3B3BL;
        return (x - 0x0101010101010101L) & ~x & 0x8080808080808080L;
    }

    private static int hash(MemorySegment file, long start, int length) {
        long h = length;
        int i = 0;
        for (; i + 8 <= length; i += 8) {
            h = (h ^ file.get(LONG_LE, start + i)) * HASH_MULTIPLIER;
        }
        int remaining = length - i;
        if (remaining > 0) {
            long tail = file.get(LONG_LE, start + i) & ((1L << (remaining << 3)) - 1);
            h = (h ^ tail) * HASH_MULTIPLIER;
        }
        return (int) (h >>> (64 - HASH_BITS));
    }

    private static boolean sameName(MemorySegment file, long a, long b, int length) {
        int i = 0;
        for (; i + 8 <= length; i += 8) {
            if (file.get(LONG_LE, a + i) != file.get(LONG_LE, b + i)) {
                return false;
            }
        }
        int remaining = length - i;
        if (remaining == 0) {
            return true;
        }
        long mask = (1L << (remaining << 3)) - 1;
        return ((file.get(LONG_LE, a + i) ^ file.get(LONG_LE, b + i)) & mask) == 0;
    }

    private static ChunkResult processChunk(MemorySegment file, long start, long end, long fastLimit) {
        var table = new StationTable();
        long limit = Math.min(end, fastLimit);
        long position = start;

        while (position < limit) {
            long nameStart = position;
            long semicolon = locateSemicolon(file.get(LONG_LE, position));
            while (semicolon == 0) {
                position += 8;
                semicolon = locateSemicolon(file.get(LONG_LE, position));
            }
            position += Long.numberOfTrailingZeros(semicolon) >> 3;
            int nameLength = (int) (position - nameStart);

            int bucket = hash(file, nameStart, nameLength);
            int id = table.bucketHead[bucket] - 1;
            while (id >= 0 && !(table.nameLength[id] == nameLength && sameName(file, nameStart, table.nameOffset[id], nameLength))) {
                id = table.chainNext[id] - 1;
            }
            if (id < 0) {
                id = table.add(bucket, nameStart, nameLength);
            }

            position++; // skip semicolon
            byte c = file.get(BYTE, position++);
            boolean negative = c == '-';
            if (negative) {
                c = file.get(BYTE, position++);
            }
            int tenths = c - '0';
            while ((c = file.get(BYTE, position++)) != '\n') {
                if (c != '.') {
                    tenths = tenths * 10 + (c - '0');
                }
            }
            table.histograms[id][negative ? BINS + tenths : tenths]++;
        }

        var stats = new HashMap<String, MeasurementAggregator>();
        for (int id = 0; id < table.size; id++) {
            byte[] name = file.asSlice(table.nameOffset[id], table.nameLength[id]).toArray(BYTE);
            var aggregator = new MeasurementAggregator();
            int[] histogram = table.histograms[id];
            for (int i = 0; i < BINS; i++) {
                aggregator.add(i, histogram[i]);
                aggregator.add(-i, histogram[BINS + i]);
            }
            stats.put(new String(name, StandardCharsets.UTF_8), aggregator);
        }
        return new ChunkResult(stats, position);
    }

    /** Plain, bounds-safe parsing for the few lines at the very end of the file. */
    private static void processTail(MemorySegment file, long from, long to, Map<String, MeasurementAggregator> result) {
        if (from >= to) {
            return;
        }
        String text = new String(file.asSlice(from, to - from).toArray(BYTE), StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int semicolon = line.lastIndexOf(';');
            int tenths = Integer.parseInt(line.substring(semicolon + 1).replace(".", ""));
            result.computeIfAbsent(line.substring(0, semicolon), k -> new MeasurementAggregator()).add(tenths, 1);
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException, ExecutionException {
        try (FileChannel fileChannel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ)) {
            long fileSize = fileChannel.size();
            // Arena.global() is fine for a run-once CLI: no scope-close checks in the hot loop and nothing to close.
            MemorySegment file = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global());

            int numberOfThreads = Runtime.getRuntime().availableProcessors();
            if (fileSize < numberOfThreads * 1024L) {
                numberOfThreads = (int) Math.max(1, fileSize / 1024);
            }
            long chunkSize = fileSize / numberOfThreads;

            // Chunk boundaries, moved forward to the next line start.
            long[] bounds = new long[numberOfThreads + 1];
            bounds[numberOfThreads] = fileSize;
            for (int i = 1; i < numberOfThreads; i++) {
                long p = i * chunkSize;
                while (file.get(BYTE, p++) != '\n')
                    ;
                bounds[i] = p;
            }
            long fastLimit = fileSize - TAIL_MARGIN;

            var resultMap = new TreeMap<String, MeasurementAggregator>();
            try (var executor = Executors.newFixedThreadPool(numberOfThreads)) {
                List<Future<ChunkResult>> futures = new ArrayList<>();
                for (int i = 0; i < numberOfThreads; i++) {
                    final long start = bounds[i];
                    final long end = bounds[i + 1];
                    futures.add(executor.submit(() -> processChunk(file, start, end, fastLimit)));
                }

                long tailStart = 0;
                for (Future<ChunkResult> future : futures) {
                    ChunkResult chunk = future.get(); // rethrows worker failures instead of swallowing them
                    chunk.stats().forEach((name, stats) -> resultMap.computeIfAbsent(name, k -> new MeasurementAggregator()).merge(stats));
                    tailStart = chunk.resumeAt(); // only the last chunk can stop before its end
                }
                processTail(file, tailStart, fileSize, resultMap);
            }

            System.out.println(resultMap);
        }
    }
}

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
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Java 22+ (tested target: Java 27). Uses the standard Foreign Function & Memory API
 * (java.lang.foreign) instead of sun.misc.Unsafe, whose memory-access methods are
 * terminally deprecated (JEP 471 / JEP 498) and denied by default in newer JDKs.
 */
public class CalculateAverage_charlibot {

    private static final String FILE = "./measurements.txt";

    private static final int MAP_CAPACITY = 16384; // Need at least 10,000 so 2^14 = 16384. Might need 2^15 = 32768.

    public static void main(String[] args) throws Exception {
        memoryMap();
    }

    // Copied from Roy van Rijn's code
    // branchless max (unprecise for large numbers, but good enough)
    static int max(final int a, final int b) {
        final int diff = a - b;
        final int dsgn = diff >> 31;
        return a - (diff & dsgn);
    }

    // branchless min (unprecise for large numbers, but good enough)
    static int min(final int a, final int b) {
        final int diff = a - b;
        final int dsgn = diff >> 31;
        return b + (diff & dsgn);
    }

    static class Measurement {
        int min;
        int max;
        long sum; // long so that huge inputs can't overflow
        int count;

        Measurement(int value) {
            min = value;
            max = value;
            sum = value;
            count = 1;
        }

        @Override
        public String toString() {
            double minD = (double) min / 10;
            double maxD = (double) max / 10;
            double meanD = (double) sum / 10 / count;
            return round(minD) + "/" + round(meanD) + "/" + round(maxD);
        }

        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }
    }

    static class MeasurementMap3 {

        private final MemorySegment file;
        final Measurement[] measurements;
        final byte[][] cities;

        final int capacity = MAP_CAPACITY;

        MeasurementMap3(MemorySegment file) {
            this.file = file;
            measurements = new Measurement[capacity];
            cities = new byte[capacity][128]; // 100 bytes for the city. Round up to nearest power of 2.
        }

        /** fromOffset / toOffset are byte offsets into the mapped file segment. */
        public void insert(long fromOffset, long toOffset, int hashcode, int value) {
            int index = hashcode & (capacity - 1); // same trick as in hashmap. This is the same as (% capacity).
            tryInsert(index, fromOffset, toOffset, value);
        }

        private void tryInsert(int mapIndex, long fromOffset, long toOffset, int value) {
            byte length = (byte) (toOffset - fromOffset);
            outer: while (true) {
                byte[] cityArray = cities[mapIndex];
                Measurement jas = measurements[mapIndex];
                if (jas != null) {
                    if (cityArray[0] == length) {
                        int i = 0;
                        while (i < length) {
                            byte b = file.get(ValueLayout.JAVA_BYTE, fromOffset + i);
                            if (b != cityArray[i + 1]) {
                                mapIndex = (mapIndex + 1) & (capacity - 1);
                                continue outer;
                            }
                            i++;
                        }
                        jas.min = min(value, jas.min);
                        jas.max = max(value, jas.max);
                        jas.sum += value;
                        jas.count += 1;
                        break;
                    }
                    else {
                        mapIndex = (mapIndex + 1) & (capacity - 1);
                    }
                }
                else {
                    // just insert
                    cityArray[0] = length;
                    MemorySegment.copy(file, ValueLayout.JAVA_BYTE, fromOffset, cityArray, 1, length);
                    measurements[mapIndex] = new Measurement(value);
                    break;
                }
            }
        }

        public HashMap<String, Measurement> toMap() {
            HashMap<String, Measurement> hashMap = new HashMap<>();
            for (int mapIndex = 0; mapIndex < cities.length; mapIndex++) {
                byte[] cityArray = cities[mapIndex];
                Measurement measurement = measurements[mapIndex];
                if (measurement != null) {
                    int length = cityArray[0];
                    String city = new String(cityArray, 1, length, StandardCharsets.UTF_8);
                    hashMap.put(city, measurement);
                }
            }
            return hashMap;
        }

        public Set<Map.Entry<String, Measurement>> entrySet() {
            return toMap().entrySet();
        }
    }

    /**
     * Splits the file into numChunks byte ranges, each starting at the beginning of a line.
     * Returns numChunks + 1 offsets; chunk i is [chunks[i], chunks[i + 1]).
     */
    static long[] getChunks(MemorySegment file, int numChunks) {
        long fileSize = file.byteSize();
        long sizeOfChunk = fileSize / numChunks;
        long[] chunks = new long[numChunks + 1];
        chunks[0] = 0;
        for (int processIdx = 1; processIdx < numChunks; processIdx++) {
            // never start before the previous boundary (only matters for tiny files)
            long offset = Math.max(processIdx * sizeOfChunk, chunks[processIdx - 1]);
            while (offset < fileSize && file.get(ValueLayout.JAVA_BYTE, offset) != '\n') {
                offset++;
            }
            chunks[processIdx] = Math.min(offset + 1, fileSize);
        }
        chunks[numChunks] = fileSize;
        return chunks;
    }

    private static HashMap<String, Measurement> processChunk(MemorySegment file, long chunkStart, long chunkEnd) {
        MeasurementMap3 measurements = new MeasurementMap3(file);
        long chunkIdx = chunkStart;
        while (chunkIdx < chunkEnd) {
            long cityStart = chunkIdx;
            byte b;
            int hashcode = 0;
            while ((b = file.get(ValueLayout.JAVA_BYTE, chunkIdx)) != ';') {
                hashcode = 31 * hashcode + b;
                chunkIdx++;
            }
            long cityEnd = chunkIdx;
            chunkIdx++;
            int multiplier = 1;
            b = file.get(ValueLayout.JAVA_BYTE, chunkIdx);
            if (b == '-') {
                multiplier = -1;
                chunkIdx++;
            }
            int value = 0;
            while ((b = file.get(ValueLayout.JAVA_BYTE, chunkIdx)) != '\n') {
                if (b != '.') {
                    value = (value * 10) + (b - '0');
                }
                chunkIdx++;
            }
            value = value * multiplier;
            measurements.insert(cityStart, cityEnd, hashcode, value);
            chunkIdx++;
        }
        return measurements.toMap();
    }

    public static void memoryMap() throws Exception {
        int numProcessors = Runtime.getRuntime().availableProcessors();

        // The mapping stays valid after the channel is closed; Arena.global() keeps it alive
        // for the life of the JVM and makes it accessible from every worker thread.
        MemorySegment file;
        try (FileChannel fileChannel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ)) {
            file = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size(), Arena.global());
        }

        long[] chunks = getChunks(file, numProcessors);

        try (ExecutorService executorService = Executors.newWorkStealingPool(numProcessors)) {
            List<Future<HashMap<String, Measurement>>> results = new ArrayList<>(numProcessors);
            for (int processIdx = 0; processIdx < numProcessors; processIdx++) {
                final long chunkStart = chunks[processIdx];
                final long chunkEnd = chunks[processIdx + 1];
                results.add(executorService.submit(() -> processChunk(file, chunkStart, chunkEnd)));
            }

            final HashMap<String, Measurement> measurements = new HashMap<>();
            for (Future<HashMap<String, Measurement>> f : results) {
                HashMap<String, Measurement> m = f.get();
                m.forEach((city, measurement) -> {
                    measurements.merge(city, measurement, (oldValue, newValue) -> {
                        Measurement mmm = new Measurement(0);
                        mmm.min = Math.min(oldValue.min, newValue.min);
                        mmm.max = Math.max(oldValue.max, newValue.max);
                        mmm.sum = oldValue.sum + newValue.sum;
                        mmm.count = oldValue.count + newValue.count;
                        return mmm;
                    });
                });
            }
            System.out.print("{");
            System.out.print(
                    measurements.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Object::toString).collect(Collectors.joining(", ")));
            System.out.println("}");
        }
    }
}

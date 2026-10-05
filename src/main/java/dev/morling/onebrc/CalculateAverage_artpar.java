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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class CalculateAverage_artpar {
    public static final int N_THREADS = 8;
    private static final String FILE = "./measurements.txt";
    private static final int INT_MAP_SIZE = 8192; // from calculateIntegerByteMapTest()
    private static final int MAX_NAME_BYTES = 128; // station names are at most 100 bytes
    final static int[] byteHashMapToInt = calculateIntegerByteMap();
    final int AVERAGE_CHUNK_SIZE = 1024 * 64;
    final int AVERAGE_CHUNK_SIZE_1 = AVERAGE_CHUNK_SIZE - 1;

    public CalculateAverage_artpar() throws IOException {
        long start = Instant.now().toEpochMilli();
        Path measurementFile = Paths.get(FILE);
        long fileSize = Files.size(measurementFile);

        // System.out.println("File size - " + fileSize);
        int expectedChunkSize = Math.toIntExact(Math.min(fileSize / N_THREADS, Integer.MAX_VALUE / 2));

        ExecutorService threadPool = Executors.newFixedThreadPool(N_THREADS);

        long chunkStartPosition = 0;
        RandomAccessFile fis = new RandomAccessFile(measurementFile.toFile(), "r");
        List<Future<Map<String, MeasurementAggregator>>> futures = new ArrayList<>();
        long bytesReadCurrent = 0;

        FileChannel fileChannel = FileChannel.open(measurementFile, StandardOpenOption.READ);
        for (int i = 0; chunkStartPosition < fileSize; i++) {

            int chunkSize = expectedChunkSize;
            chunkSize = fis.skipBytes(chunkSize);

            bytesReadCurrent += chunkSize;
            while (((char) fis.read()) != '\n' && bytesReadCurrent < fileSize) {
                chunkSize++;
                bytesReadCurrent++;
            }

            // System.out.println("[" + chunkStartPosition + "] - [" + (chunkStartPosition + chunkSize) + " bytes");
            if (chunkStartPosition + chunkSize >= fileSize) {
                chunkSize = (int) Math.min(fileSize - chunkStartPosition, Integer.MAX_VALUE);
            }
            if (chunkSize < 1) {
                break;
            }
            if (chunkSize >= Integer.MAX_VALUE) {
                throw new RuntimeException();
            }

            ReaderRunnable readerRunnable = new ReaderRunnable(chunkStartPosition, chunkSize, fileChannel);
            Future<Map<String, MeasurementAggregator>> future = threadPool.submit(readerRunnable::run);
            // System.out.println("Added future [" + chunkStartPosition + "][" + chunkSize + "]");
            futures.add(future);
            chunkStartPosition = chunkStartPosition + chunkSize + 1;
        }

        fis.close();

        Map<String, MeasurementAggregator> globalMap = futures.stream().flatMap(future -> {
            try {
                return future.get().entrySet().stream();
            }
            catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }).parallel().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, MeasurementAggregator::combine));
        fileChannel.close();

        Map<String, ResultRow> results = globalMap.entrySet().stream().parallel()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().finish()));

        threadPool.shutdown();
        Map<String, ResultRow> measurements = new TreeMap<>(results);

        PrintStream printStream = new PrintStream(new BufferedOutputStream(System.out));
        // PrintStream printStream = System.out;
        printStream.print("{");

        boolean isFirst = true;
        for (Map.Entry<String, ResultRow> stringResultRowEntry : measurements.entrySet()) {
            if (!isFirst) {
                printStream.print(", ");
                printStream.flush();
            }
            printStream.flush();
            printStream.print(stringResultRowEntry.getKey());
            printStream.flush();
            printStream.print("=");
            printStream.flush();
            stringResultRowEntry.getValue().printTo(printStream);
            printStream.flush();
            isFirst = false;
        }

        System.out.print("}\n");

        // long end = Instant.now().toEpochMilli();
        // System.out.println((end - start) / 1000);

    }

    public static int[] calculateIntegerByteMapTest() {
        int[] intToIntMap = null;
        for (int j = 0; j < 10000; j++) {
            int length = 2000 + j;
            intToIntMap = new int[length];
            boolean hasHashClash = false;
            Map<Integer, Integer> byteHashToInt = new HashMap<>();
            for (int i = -999; i < 1000; i++) {
                int hashCode = hashInteger(i);

                int position = hashCode & (length - 1);
                if (byteHashToInt.containsKey(hashCode) || intToIntMap[position] != 0) {
                    hasHashClash = true;
                    break;
                }
                else {
                    byteHashToInt.put(hashCode, i);
                    intToIntMap[position] = i;
                }
            }
            if (!hasHashClash) {
                // 8192
                System.out.println("NoHash clash at [" + length + "]");
                return intToIntMap;
            }

        }
        System.out.println("Fail");
        return null;
    }

    private static int hashInteger(int i) {
        float number = i / 10f;
        String numberString = String.format("%.1f", number);
        byte[] value = numberString.getBytes();

        int hashCode = 1;
        for (int k = 0; k < value.length; k++) {
            hashCode = hashCode * 31 + value[k];
        }
        return hashCode;
    }

    public static int[] calculateIntegerByteMap() {
        int[] intToIntMap = new int[INT_MAP_SIZE];
        for (int i = -999; i < 1000; i++) {
            float number = i / 10f;
            byte[] value = String.format("%.1f", number).getBytes();

            int hashCode = 1;
            for (byte b : value) {
                hashCode = hashCode * 31 + b;
            }
            int position = hashCode & (INT_MAP_SIZE - 1);
            intToIntMap[position] = i;
        }
        return intToIntMap;
    }

    public static void main(String[] args) throws IOException {
        new CalculateAverage_artpar();
    }

    private record ResultRow(double min, double mean, double max) {
        public String toString() {
            return round(min / 10) + "/" + round(mean / 10) + "/" + round(max / 10);
        }

        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }

        public void printTo(PrintStream out) {
            out.printf("%.1f/%.1f/%.1f", min / 10, mean / 10, max / 10);
        }
    }

    private static class MeasurementAggregator {
        private int min = 999;
        private int max = -999;
        private double sum;
        private long count;

        MeasurementAggregator combine(MeasurementAggregator other) {
            min = other.min + ((min - other.min) & ((min - other.min) >> (32 * 8 - 1)));
            max = max - ((max - other.max) & ((max - other.max) >> (32 * 8 - 1)));
            sum += other.sum;
            count += other.count;
            return this;
        }

        void combine(int value) {
            sum += value;
            count++;

            min = value + ((min - value) & ((min - value) >> (32 * 8 - 1))); // min(x, y)
            max = max - ((max - value) & ((max - value) >> (32 * 8 - 1))); // max(x, y)
        }

        ResultRow finish() {
            double mean = (count > 0) ? sum / count : 0;
            return new ResultRow(min, mean, max);
        }
    }

    static class StationName {
        public final int hash;
        private final byte[] nameBytes;
        private final MeasurementAggregator measurementAggregator = new MeasurementAggregator();
        public int count = 0;

        public StationName(byte[] nameBytes, int hash) {
            this.nameBytes = nameBytes;
            this.hash = hash;
        }

    }

    private class ReaderRunnable {
        private final long startPosition;
        private final FileChannel fileChannel;
        private final int chunkSize;
        StationNameMap stationNameMap = new StationNameMap();

        private ReaderRunnable(long startPosition, int chunkSize, FileChannel fileChannel) throws IOException {
            this.chunkSize = chunkSize;
            this.startPosition = startPosition;
            this.fileChannel = fileChannel;
        }

        public Map<String, MeasurementAggregator> run() throws IOException {
            // Heap scratch buffer for the current station name (replaces Unsafe.allocateMemory)
            byte[] nameBuffer = new byte[MAX_NAME_BYTES];
            int nameLength = 0;

            // A confined arena unmaps the chunk as soon as we are done with it, instead of
            // keeping every mapping alive until the JVM exits (as Arena.global() did).
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = fileChannel.map(FileChannel.MapMode.READ_ONLY,
                        startPosition, chunkSize, arena);

                long position = 0;
                long endPosition = chunkSize;
                byte b;
                int hash = 1;
                int nameHash;

                while (position < endPosition) {

                    while ((position < endPosition) &&
                            (b = segment.get(ValueLayout.JAVA_BYTE, position++)) != ';') {
                        nameBuffer[nameLength++] = b;
                        hash = hash * 31 + b;
                    }

                    nameHash = hash;
                    hash = 1;

                    while ((position < endPosition) &&
                            (b = segment.get(ValueLayout.JAVA_BYTE, position++)) != '\n') {
                        hash = hash * 31 + b;
                    }
                    stationNameMap.getOrCreate(nameBuffer, nameLength,
                            byteHashMapToInt[hash & (INT_MAP_SIZE - 1)], nameHash);
                    nameLength = 0;
                    hash = 1;

                }
            }
            return Arrays.stream(stationNameMap.names).filter(Objects::nonNull).collect(
                    Collectors.toMap(e -> new String(e.nameBytes, StandardCharsets.UTF_8),
                            e -> e.measurementAggregator, MeasurementAggregator::combine));
        }
    }

    class StationNameMap {
        int[] indexes = new int[AVERAGE_CHUNK_SIZE];
        StationName[] names = new StationName[AVERAGE_CHUNK_SIZE];
        int currentIndex = 0;

        public void getOrCreate(byte[] stationNameBytes, int length, int doubleValue, int hash) {
            int position = hash & AVERAGE_CHUNK_SIZE_1;
            while (indexes[position] != 0 && (names[indexes[position]].hash != hash)) {
                position = ++position & AVERAGE_CHUNK_SIZE_1;
            }
            if (indexes[position] != 0) {
                StationName stationName = names[indexes[position]];
                stationName.measurementAggregator.combine(doubleValue);
            }
            else {
                StationName stationName = new StationName(Arrays.copyOf(stationNameBytes, length), hash);
                indexes[position] = ++currentIndex;
                names[indexes[position]] = stationName;
                stationName.measurementAggregator.combine(doubleValue);
            }
        }
    }

}

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
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

class ByteArrayWrapper {
    private final byte[] data;

    public ByteArrayWrapper(byte[] data) {
        this.data = data;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ByteArrayWrapper o && Arrays.equals(data, o.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }
}

public class CalculateAverage_bufistov {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    static class ResultRow {
        byte[] station;

        String stationString;
        long min, max, count, suma;

        ResultRow() {
        }

        ResultRow(byte[] station, long value) {
            this.station = new byte[station.length];
            System.arraycopy(station, 0, this.station, 0, station.length);
            this.min = value;
            this.max = value;
            this.count = 1;
            this.suma = value;
        }

        ResultRow(long value) {
            this.min = value;
            this.max = value;
            this.count = 1;
            this.suma = value;
        }

        void setStation(MemorySegment segment, long startPosition, long endPosition) {
            this.station = new byte[(int) (endPosition - startPosition)];
            MemorySegment.copy(segment, BYTE, startPosition, this.station, 0, this.station.length);
        }

        public String toString() {
            stationString = new String(station, StandardCharsets.UTF_8);
            return stationString + "=" + round(min / 10.0) + "/" + round(suma / 10.0 / count) + "/" + round(max / 10.0);
        }

        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }

        void update(long newValue) {
            this.count += 1;
            this.suma += newValue;
            if (newValue < this.min) {
                this.min = newValue;
            }
            else if (newValue > this.max) {
                this.max = newValue;
            }
        }

        ResultRow merge(ResultRow another) {
            this.count += another.count;
            this.suma += another.suma;
            this.min = Math.min(this.min, another.min);
            this.max = Math.max(this.max, another.max);
            return this;
        }
    }

    static class OpenHash {
        ResultRow[] data;
        int dataSizeMask;

        public OpenHash(int capacityPow2) {
            assert capacityPow2 <= 20;
            int dataSize = 1 << capacityPow2;
            dataSizeMask = dataSize - 1;
            data = new ResultRow[dataSize];
        }

        void merge(MemorySegment segment, final long startPosition, long endPosition, int hashValue, long value) {
            while (data[hashValue] != null && !equalsToStation(segment, startPosition, endPosition, data[hashValue].station)) {
                hashValue += 1;
                hashValue &= dataSizeMask;
            }
            if (data[hashValue] == null) {
                data[hashValue] = new ResultRow(value);
                data[hashValue].setStation(segment, startPosition, endPosition);
            }
            else {
                data[hashValue].update(value);
            }
        }

        boolean equalsToStation(MemorySegment segment, long startPosition, long endPosition, byte[] station) {
            if (endPosition - startPosition != station.length) {
                return false;
            }
            for (int i = 0; i < station.length; ++i, ++startPosition) {
                if (segment.get(BYTE, startPosition) != station[i])
                    return false;
            }
            return true;
        }

        HashMap<ByteArrayWrapper, ResultRow> toJavaHashMap() {
            HashMap<ByteArrayWrapper, ResultRow> result = new HashMap<>(20000);
            for (ResultRow row : data) {
                if (row != null) {
                    result.put(new ByteArrayWrapper(row.station), row);
                }
            }
            return result;
        }
    }

    static final long LINE_SEPARATOR = '\n';

    public static class FileRead implements Callable<HashMap<ByteArrayWrapper, ResultRow>> {

        private final MemorySegment segment;
        private long currentLocation;
        private long endLocation;

        private static final int hashCapacityPow2 = 18;

        static final int hashCapacityMask = (1 << hashCapacityPow2) - 1;

        /** Processes the byte range [startLocation, startLocation + bytesToRead) of the segment. */
        public FileRead(MemorySegment segment, long startLocation, long bytesToRead) {
            this.segment = segment;
            this.currentLocation = startLocation;
            this.endLocation = startLocation + bytesToRead;
        }

        @Override
        public HashMap<ByteArrayWrapper, ResultRow> call() {
            OpenHash openHash = new OpenHash(hashCapacityPow2);
            log("Reading the segment: " + currentLocation + ":" + endLocation);
            // Align chunk start to the beginning of a line (the previous chunk owns the partial line).
            if (currentLocation > 0) {
                while (currentLocation < endLocation && segment.get(BYTE, currentLocation - 1) != LINE_SEPARATOR) {
                    ++currentLocation;
                }
            }
            // Extend chunk end to the end of the line.
            long fileSize = segment.byteSize();
            while (endLocation < fileSize && segment.get(BYTE, endLocation - 1) != LINE_SEPARATOR) {
                ++endLocation;
            }
            processChunk(openHash);
            log("Done Reading the segment: " + currentLocation + ":" + endLocation);
            return openHash.toJavaHashMap();
        }

        void processChunk(OpenHash result) {
            long nameBegin = currentLocation;
            long nameEnd = -1;
            long numberBegin = -1;
            int currentHash = 0;
            int currentMask = 0;
            int nameHash = 0;
            byte nextByte;
            for (; currentLocation < endLocation; ++currentLocation) {
                nextByte = segment.get(BYTE, currentLocation);
                if (nextByte == ';') {
                    nameEnd = currentLocation;
                    numberBegin = currentLocation + 1;
                    nameHash = currentHash & hashCapacityMask;
                }
                else if (nextByte == LINE_SEPARATOR) {
                    long value = getValue(numberBegin, currentLocation);
                    result.merge(segment, nameBegin, nameEnd, nameHash, value);
                    nameBegin = currentLocation + 1;
                    currentHash = 0;
                    currentMask = 0;
                }
                else {
                    currentHash += (nextByte << currentMask);
                    currentMask = (currentMask + 1) & 3;
                }
            }
        }

        long getValue(long startLocation, long endLocation) {
            byte nextByte = segment.get(BYTE, startLocation);
            boolean negate = nextByte == '-';
            long result = negate ? 0 : nextByte - '0';
            for (long i = startLocation + 1; i < endLocation; ++i) {
                nextByte = segment.get(BYTE, i);
                if (nextByte != '.') {
                    result *= 10;
                    result += nextByte - '0';
                }
            }
            return negate ? -result : result;
        }
    }

    public static void main(String[] args) throws Exception {
        String fileName = "measurements.txt";
        if (args.length > 0 && args[0].length() > 0) {
            fileName = args[0];
        }
        log("InputFile: " + fileName);
        int numThreads = 2 * Runtime.getRuntime().availableProcessors();
        if (args.length > 1) {
            numThreads = Integer.parseInt(args[1]);
        }
        log("NumThreads: " + numThreads);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<HashMap<ByteArrayWrapper, ResultRow>>> results = new ArrayList<>(numThreads);

        try (FileChannel channel = FileChannel.open(Paths.get(fileName), StandardOpenOption.READ);
                Arena arena = Arena.ofShared()) {
            final long fileSize = channel.size();
            // Map the whole file once; MemorySegment supports sizes beyond 2 GB.
            MemorySegment segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena);

            long chunkSize = (fileSize + numThreads - 1) / numThreads;
            long startLocation = 0;
            while (startLocation < fileSize) {
                long actualSize = Math.min(chunkSize, fileSize - startLocation);
                results.add(executor.submit(new FileRead(segment, startLocation, actualSize)));
                startLocation += actualSize;
            }
            executor.shutdown();

            HashMap<ByteArrayWrapper, ResultRow> result = new HashMap<>(20000);
            for (var future : results) {
                for (var entry : future.get().entrySet()) {
                    result.merge(entry.getKey(), entry.getValue(), ResultRow::merge);
                }
            }
            log("Finished all threads");

            ResultRow[] finalResult = result.values().toArray(new ResultRow[0]);
            for (var row : finalResult) {
                row.toString();
            }
            Arrays.sort(finalResult, Comparator.comparing(a -> a.stationString));
            System.out.println("{" + String.join(", ", Arrays.stream(finalResult).map(ResultRow::toString).toList()) + "}");
            log("All done!");
        }
        catch (IOException e) {
            executor.shutdownNow();
            throw e;
        }
    }

    static void log(String message) {
        // System.err.println(Instant.now() + "[" + Thread.currentThread().getName() + "]: " + message);
    }
}

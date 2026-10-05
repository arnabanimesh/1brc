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

import static java.util.stream.Collectors.groupingBy;

import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collector;

public class CalculateAverage_karthikeyan97 {

    private static final String FILE = "./measurements.txt";

    private record Measurement(modifiedbytearray station, double value) {
    }

    private record customPair(String stationName, MeasurementAggregator agg) {
    }

    private static class MeasurementAggregator {
        private long min = Long.MAX_VALUE;
        private long max = Long.MIN_VALUE;
        private long sum;
        private long count;

        public String toString() {
            return new StringBuffer(14)
                    .append(round((1.0 * min)))
                    .append("/")
                    .append(round((1.0 * sum) / count))
                    .append("/")
                    .append(round((1.0 * max))).toString();
        }

        private double round(double value) {
            return Math.round(value) / 10.0;
        }
    }

    public static void main(String[] args) throws Exception {
        Collector<Map.Entry<modifiedbytearray, MeasurementAggregator>, MeasurementAggregator, MeasurementAggregator> collector = Collector.of(
                MeasurementAggregator::new,
                (a, m) -> {
                    MeasurementAggregator agg = m.getValue();
                    if (a.min >= agg.min) {
                        a.min = agg.min;
                    }
                    if (a.max <= agg.max) {
                        a.max = agg.max;
                    }
                    a.max = Math.max(a.max, m.getValue().max);
                    a.sum += m.getValue().sum;
                    a.count += m.getValue().count;
                },
                (agg1, agg2) -> {
                    if (agg1.min <= agg2.min) {
                        agg2.min = agg1.min;
                    }
                    if (agg1.max >= agg2.max) {
                        agg2.max = agg1.max;
                    }
                    agg2.sum = agg1.sum + agg2.sum;
                    agg2.count = agg1.count + agg2.count;

                    return agg2;
                },
                agg -> agg);

        final MemorySegment mapped;
        long boundary[][];
        int cores;
        long length;
        long before = -1;
        final long endOffset;
        try (RandomAccessFile raf = new RandomAccessFile(FILE, "r")) {
            FileChannel fileChannel = raf.getChannel();
            length = raf.length();
            // Mapped via the (final since Java 22) FFM API. All reads below are bounds-checked
            // MemorySegment copies, so no sun.misc.Unsafe is needed.
            mapped = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, length, Arena.global());
            endOffset = length - 1;
            cores = length > 1000 ? Runtime.getRuntime().availableProcessors() : 1;
            boundary = new long[cores][2];
            long segments = length / (cores);
            for (int i = 0; i < cores - 1; i++) {
                boundary[i][0] = before + 1;
                if (before + segments - 107 > 0) {
                    raf.seek(before + segments - 107);
                }
                else {
                    raf.seek(0);
                }
                while (raf.read() != '\n') {
                }
                boundary[i][1] = raf.getChannel().position() - 1;
                before = boundary[i][1];
            }
        }
        boundary[cores - 1][0] = before + 1;
        boundary[cores - 1][1] = length - 1;

        int l3Size = (12 * 1024 * 1024);

        System.out.println(new TreeMap<>((Arrays.stream(boundary).parallel().map(i -> {
            try {
                int seglen = (int) (i[1] - i[0] + 1);
                HashMap<modifiedbytearray, MeasurementAggregator> resultmap = new HashMap<>(4000);
                long segOffset = i[0];
                int bytesRemaining = seglen;
                long num = 0;
                boolean isNumber = false;
                byte bi;
                int sign = 1;
                modifiedbytearray stationName = null;
                int hascode = 5381;
                while (bytesRemaining > 0) {
                    int bytesptr = 0;
                    int bbstart = 0;
                    int readSize = bytesRemaining > l3Size ? l3Size : bytesRemaining;
                    int actualReadSize = (segOffset + readSize + 110 > endOffset || readSize + 110 > i[1]) ? readSize : readSize + 110;
                    byte[] readArr = new byte[actualReadSize];

                    MemorySegment.copy(mapped, ValueLayout.JAVA_BYTE, segOffset, readArr, 0, actualReadSize);
                    while (bytesptr < actualReadSize) {
                        bi = readArr[bytesptr++];
                        if (!isNumber) {
                            while (bi != 59) {
                                hascode = (hascode << 5) + hascode ^ bi;
                                bi = readArr[bytesptr++];
                            }
                            isNumber = true;
                            stationName = new modifiedbytearray(readArr, bbstart, bytesptr - 2, hascode & 0xFFFFFFFF);
                            bbstart = 0;
                            hascode = 5381;
                        }
                        else {
                            while (bi != 10) {
                                if (bi == 0x2D) {
                                    sign = -1;
                                }
                                else if (bi != 0x2E) {
                                    num = num * 10 + (bi - 0x30);
                                }
                                bi = readArr[bytesptr++];
                            }
                            hascode = 5381;
                            isNumber = false;
                            bbstart = bytesptr;
                            num *= sign;
                            MeasurementAggregator agg = resultmap.get(stationName);
                            if (agg == null) {
                                agg = new MeasurementAggregator();
                                agg.min = num;
                                agg.max = num;
                                agg.sum = (long) (num);
                                agg.count = 1;
                                resultmap.put(stationName, agg);
                            }
                            else {
                                if (agg.min >= num) {
                                    agg.min = num;
                                }
                                if (agg.max <= num) {
                                    agg.max = num;
                                }
                                agg.sum += (long) (num);
                                agg.count++;
                            }
                            num = 0;
                            sign = 1;
                            if (bytesptr >= readSize) {
                                break;
                            }
                        }
                    }
                    bytesRemaining -= bytesptr;
                    segOffset += bytesptr;
                }
                return resultmap;
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }).flatMap(e -> e.entrySet().stream()).collect(groupingBy(e -> e.getKey().getStationName(), collector)))));
    }

}

class modifiedbytearray implements Comparable<modifiedbytearray> {
    private int length;
    private int start;
    private byte[] arr;
    public int hashcode;

    modifiedbytearray(byte[] arr, int start, int end, int hashcode) {
        this.arr = arr;
        this.length = end - start + 1;
        this.start = start;
        this.hashcode = hashcode;
    }

    public String getStationName() {
        return new String(this.getArr(), start, length, StandardCharsets.UTF_8);
    }

    public byte[] getArr() {
        return this.arr;
    }

    @Override
    public String toString() {
        return getStationName();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof modifiedbytearray b)) {
            return false;
        }
        // Arrays.equals(..., from, to, ...) treats "to" as exclusive; 'end' here is the last
        // index (inclusive), so use start + length to compare every byte of the name.
        return length == b.length
                && Arrays.equals(arr, start, start + length, b.arr, b.start, b.start + b.length);
    }

    @Override
    public int compareTo(modifiedbytearray other) {
        // High-performance array comparison (available since Java 9)
        return Arrays.compare(this.getArr(), other.getArr());
    }

    public int getHashcode() {
        return hashcode;
    }

    @Override
    public int hashCode() {
        return hashcode;
    }
}

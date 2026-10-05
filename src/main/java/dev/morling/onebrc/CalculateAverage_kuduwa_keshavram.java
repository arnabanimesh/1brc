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

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Java 22+ compatible (including Java 27): uses the standard Foreign Function &amp; Memory API
 * ({@link MemorySegment}) instead of {@code sun.misc.Unsafe}, whose memory-access methods
 * throw by default from JDK 26 onwards (JEP 471 / JEP 498).
 */
public class CalculateAverage_kuduwa_keshavram {

    private static final String FILE = "./measurements.txt";

    /** Max station name length in bytes (the challenge guarantees <= 100). */
    private static final int MAX_NAME_BYTES = 128;

    /** Open-addressing table size; must be a power of two. Challenge has at most 10,000 stations. */
    private static final int TABLE_SIZE = 1 << 17;

    public static void main(String[] args) throws IOException {
        final MemorySegment file;
        try (FileChannel channel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ)) {
            // The mapping stays valid after the channel is closed; Arena.global() never unmaps it.
            file = channel.map(MapMode.READ_ONLY, 0, channel.size(), Arena.global());
        }

        TreeMap<String, Measurement> resultMap = getFileSegments(file)
                .flatMap(segment -> processSegment(file, segment).stream())
                .collect(
                        Collectors.toMap(
                                measurement -> new String(measurement.city, StandardCharsets.UTF_8),
                                Function.identity(),
                                (m1, m2) -> {
                                    m1.merge(m2);
                                    return m1;
                                },
                                TreeMap::new));

        System.out.println(resultMap);
    }

    private static Result processSegment(MemorySegment file, FileSegment segment) {
        final Result result = new Result();
        final byte[] name = new byte[MAX_NAME_BYTES]; // reused for every row; copied only on first sight
        final long end = segment.end();
        long pos = segment.start();

        while (pos < end) {
            byte b;
            int hash = 0;
            int len = 0;
            while ((b = file.get(JAVA_BYTE, pos++)) != ';') {
                hash = 31 * hash + b;
                name[len++] = b;
            }

            int temp = 0; // tenths of a degree
            boolean negative = false;
            while (pos < end && (b = file.get(JAVA_BYTE, pos++)) != '\n') {
                if (b == '-') {
                    negative = true;
                }
                else if (b != '.') {
                    temp = temp * 10 + (b - '0');
                }
            }
            result.add(hash, name, len, negative ? -temp : temp);
        }
        return result;
    }

    /** Per-segment hash table with linear probing. */
    private static final class Result {
        private final Measurement[] table = new Measurement[TABLE_SIZE];
        private int size = 0;

        void add(int hash, byte[] name, int len, int temp) {
            final int mask = TABLE_SIZE - 1;
            int index = (hash ^ (hash >>> 16)) & mask;
            while (true) {
                final Measurement existing = table[index];
                if (existing == null) {
                    if (size >= TABLE_SIZE / 2) {
                        throw new IllegalStateException("Too many distinct stations");
                    }
                    table[index] = new Measurement(hash, Arrays.copyOf(name, len), temp);
                    size++;
                    return;
                }
                if (existing.hash == hash
                        && Arrays.equals(existing.city, 0, existing.city.length, name, 0, len)) {
                    existing.add(temp);
                    return;
                }
                index = (index + 1) & mask;
            }
        }

        Stream<Measurement> stream() {
            return Arrays.stream(table).filter(Objects::nonNull);
        }
    }

    private record FileSegment(long start, long end) {
    }

    private static final class Measurement {

        private final int hash;
        private final byte[] city;

        int min;
        int max;
        long sum;
        long count;

        private Measurement(int hash, byte[] city, int temp) {
            this.hash = hash;
            this.city = city;
            this.min = this.max = temp;
            this.sum = temp;
            this.count = 1;
        }

        private void add(int temp) {
            if (temp < this.min) {
                this.min = temp;
            }
            if (temp > this.max) {
                this.max = temp;
            }
            this.sum += temp;
            this.count++;
        }

        private void merge(Measurement m2) {
            this.min = Math.min(this.min, m2.min);
            this.max = Math.max(this.max, m2.max);
            this.sum += m2.sum;
            this.count += m2.count;
        }

        @Override
        public String toString() {
            // Mean is rounded in whole tenths (ties toward +infinity) so the result doesn't depend on
            // binary floating-point representation of values like x.x5.
            final long meanTenths = Math.round((double) this.sum / this.count);
            return String.format(
                    Locale.ROOT, "%.1f/%.1f/%.1f", this.min / 10.0, meanTenths / 10.0, this.max / 10.0);
        }
    }

    private static Stream<FileSegment> getFileSegments(final MemorySegment file) {
        final int numberOfSegments = Runtime.getRuntime().availableProcessors() * 4;
        final long fileSize = file.byteSize();
        final long segmentSize = (fileSize + numberOfSegments - 1) / numberOfSegments;
        final long[] chunks = new long[numberOfSegments + 1];

        chunks[0] = 0;
        for (int i = 1; i < numberOfSegments; ++i) {
            long pos = Math.min(i * segmentSize, fileSize);
            // Align to first row start.
            while (pos < fileSize && file.get(JAVA_BYTE, pos++) != '\n') {
                // nop
            }
            chunks[i] = pos;
        }
        chunks[numberOfSegments] = fileSize;

        return IntStream.range(0, numberOfSegments)
                .mapToObj(i -> new FileSegment(chunks[i], chunks[i + 1]))
                .parallel();
    }

}

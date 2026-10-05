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
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Same algorithm as the original, but memory is accessed through the java.lang.foreign API
 * (final since Java 22) instead of sun.misc.Unsafe, whose memory-access methods are being removed.
 * Raw addresses became offsets into the mapped file's MemorySegment.
 */
public class CalculateAverage_zerninv {
    private static final String FILE = "./measurements.txt";
    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static final int CHUNK_SIZE = 1024 * 1024 * 32;

    // Accessors for the mapped file. The word-at-a-time parsing below assumes little-endian byte order,
    // so it is requested explicitly (this costs nothing on little-endian CPUs).
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfInt INT_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public static void main(String[] args) throws IOException, InterruptedException {
        try (var channel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ)) {
            var fileSize = channel.size();
            var minChunkSize = Math.min(fileSize, CHUNK_SIZE);
            var data = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global());

            var tasks = new TaskThread[CORES];
            for (int i = 0; i < tasks.length; i++) {
                tasks[i] = new TaskThread(data, (int) (fileSize / minChunkSize / CORES + 1));
            }

            var chunks = splitByChunks(data, fileSize, minChunkSize);
            for (int i = 0; i < chunks.size() - 1; i++) {
                var task = tasks[i % tasks.length];
                task.addChunk(chunks.get(i), chunks.get(i + 1));
            }

            for (var task : tasks) {
                task.start();
            }

            var results = new HashMap<String, TemperatureAggregation>();
            for (var task : tasks) {
                task.join();
                task.collectTo(results);
            }

            var bos = new BufferedOutputStream(System.out);
            bos.write(new TreeMap<>(results).toString().getBytes(StandardCharsets.UTF_8));
            bos.write('\n');
            bos.flush();
        }
    }

    private static List<Long> splitByChunks(MemorySegment data, long end, long minChunkSize) {
        // split by chunks, offsets are relative to the start of the mapped file
        List<Long> result = new ArrayList<>((int) (end / minChunkSize + 1));
        long offset = 0;
        result.add(offset);
        while (offset < end) {
            offset += Math.min(end - offset, minChunkSize);
            while (offset < end && data.get(BYTE, offset++) != '\n') {
            }
            result.add(offset);
        }
        return result;
    }

    private static final class TemperatureAggregation {
        private long sum;
        private int count;
        private short min;
        private short max;

        public TemperatureAggregation(long sum, int count, short min, short max) {
            this.sum = sum;
            this.count = count;
            this.min = min;
            this.max = max;
        }

        public void merge(long sum, int count, short min, short max) {
            this.sum += sum;
            this.count += count;
            this.min = this.min < min ? this.min : min;
            this.max = this.max > max ? this.max : max;
        }

        @Override
        public String toString() {
            return min / 10d + "/" + Math.round(sum / 1d / count) / 10d + "/" + max / 10d;
        }
    }

    private static final class MeasurementContainer {
        private static final int SIZE = 1 << 17;

        private static final int ENTRY_SIZE = 4 + 4 + 8 + 1 + 8 + 8 + 2 + 2;
        private static final int COUNT_OFFSET = 0;
        private static final int HASH_OFFSET = 4;
        private static final int LAST_BYTES_OFFSET = 8;
        private static final int SIZE_OFFSET = 16;
        private static final int ADDRESS_OFFSET = 17;
        private static final int SUM_OFFSET = 25;
        private static final int MIN_OFFSET = 33;
        private static final int MAX_OFFSET = 35;

        // Entries are packed (37 bytes), so the table is accessed with unaligned layouts
        private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED;
        private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;
        private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED;

        // the mapped input file, entries refer to station names in it by offset
        private final MemorySegment data;
        private final MemorySegment table;

        private MeasurementContainer(MemorySegment data) {
            this.data = data;
            // allocate() returns zeroed memory, and a zero count marks a free slot
            this.table = Arena.ofAuto().allocate((long) ENTRY_SIZE * SIZE, Long.BYTES);
        }

        public void put(long address, byte size, int hash, long lastBytes, short value) {
            int idx = Math.abs(hash % SIZE);
            long ptr = (long) idx * ENTRY_SIZE;
            int count;
            boolean fastEqual;

            while ((count = table.get(INT, ptr + COUNT_OFFSET)) != 0) {
                fastEqual = table.get(INT, ptr + HASH_OFFSET) == hash && table.get(LONG, ptr + LAST_BYTES_OFFSET) == lastBytes;
                if (fastEqual && table.get(BYTE, ptr + SIZE_OFFSET) == size && isEqual(table.get(LONG, ptr + ADDRESS_OFFSET), address, size - 8)) {

                    table.set(INT, ptr + COUNT_OFFSET, count + 1);
                    table.set(LONG, ptr + ADDRESS_OFFSET, address);
                    table.set(LONG, ptr + SUM_OFFSET, table.get(LONG, ptr + SUM_OFFSET) + value);
                    if (value < table.get(SHORT, ptr + MIN_OFFSET)) {
                        table.set(SHORT, ptr + MIN_OFFSET, value);
                    }
                    if (value > table.get(SHORT, ptr + MAX_OFFSET)) {
                        table.set(SHORT, ptr + MAX_OFFSET, value);
                    }
                    return;
                }
                idx = (idx + 1) % SIZE;
                ptr = (long) idx * ENTRY_SIZE;
            }

            table.set(INT, ptr + COUNT_OFFSET, 1);
            table.set(INT, ptr + HASH_OFFSET, hash);
            table.set(LONG, ptr + LAST_BYTES_OFFSET, lastBytes);
            table.set(BYTE, ptr + SIZE_OFFSET, size);
            table.set(LONG, ptr + ADDRESS_OFFSET, address);

            table.set(LONG, ptr + SUM_OFFSET, value);
            table.set(SHORT, ptr + MIN_OFFSET, value);
            table.set(SHORT, ptr + MAX_OFFSET, value);
        }

        public void collectTo(Map<String, TemperatureAggregation> results) {
            int count;
            for (int i = 0; i < SIZE; i++) {
                long ptr = (long) i * ENTRY_SIZE;
                count = table.get(INT, ptr + COUNT_OFFSET);
                if (count != 0) {
                    var station = createString(table.get(LONG, ptr + ADDRESS_OFFSET), table.get(BYTE, ptr + SIZE_OFFSET));
                    var result = results.get(station);
                    if (result == null) {
                        results.put(station, new TemperatureAggregation(
                                table.get(LONG, ptr + SUM_OFFSET),
                                count,
                                table.get(SHORT, ptr + MIN_OFFSET),
                                table.get(SHORT, ptr + MAX_OFFSET)));
                    }
                    else {
                        result.merge(table.get(LONG, ptr + SUM_OFFSET), count, table.get(SHORT, ptr + MIN_OFFSET), table.get(SHORT, ptr + MAX_OFFSET));
                    }
                }
            }
        }

        private boolean isEqual(long address, long address2, int size) {
            for (int i = 0; i < size; i += 8) {
                if (data.get(LONG_LE, address + i) != data.get(LONG_LE, address2 + i)) {
                    return false;
                }
            }
            return true;
        }

        private String createString(long address, byte size) {
            return new String(data.asSlice(address, size).toArray(BYTE), StandardCharsets.UTF_8);
        }
    }

    private static class TaskThread extends Thread {
        // #.##
        private static final int THREE_DIGITS_MASK = 0x2e0000;
        // #.#
        private static final int TWO_DIGITS_MASK = 0x2e00;
        // #.#-
        private static final int TWO_NEGATIVE_DIGITS_MASK = 0x2e002d;
        private static final int BYTE_MASK = 0xff;

        private static final int ZERO = '0';
        private static final long DELIMITER_MASK = 0x3b3b3b3b3b3b3b3bL;
        private static final long[] SIGNIFICANT_BYTES_MASK = {
                0,
                0xff,
                0xffff,
                0xffffff,
                0xffffffffL,
                0xffffffffffL,
                0xffffffffffffL,
                0xffffffffffffffL,
                0xffffffffffffffffL
        };

        private final MemorySegment data;
        private final MeasurementContainer container;
        private final List<Long> begins;
        private final List<Long> ends;

        private TaskThread(MemorySegment data, int chunks) {
            this.data = data;
            this.container = new MeasurementContainer(data);
            this.begins = new ArrayList<>(chunks);
            this.ends = new ArrayList<>(chunks);
        }

        public void addChunk(long begin, long end) {
            begins.add(begin);
            ends.add(end);
        }

        @Override
        public void run() {
            for (int i = 0; i < begins.size(); i++) {
                var begin = begins.get(i);
                var end = ends.get(i) - 1;
                while (end > begin && data.get(BYTE, end - 1) != '\n') {
                    end--;
                }
                calcForChunk(begin, end);
                calcLastLine(end);
            }
        }

        private void calcLastLine(long offset) {
            long cityOffset = offset;
            long lastBytes = 0;
            int hashCode = 0;
            byte cityNameSize = 0;

            byte b;
            while ((b = data.get(BYTE, offset++)) != ';') {
                lastBytes = (lastBytes << 8) | b;
                hashCode = hashCode * 31 + b;
                cityNameSize++;
            }

            // The last line of a chunk can be the last line of the file, so the temperature is parsed
            // byte by byte: the word-sized reads of calcForChunk could run past the end of the mapping,
            // which a MemorySegment rejects (Unsafe silently read the zero padding of the last page).
            long fileSize = data.byteSize();
            boolean negative = false;
            int temperature = 0;
            while (offset < fileSize && (b = data.get(BYTE, offset++)) != '\n') {
                if (b == '-') {
                    negative = true;
                }
                else if (b >= '0' && b <= '9') {
                    temperature = temperature * 10 + (b - ZERO);
                }
            }
            container.put(cityOffset, cityNameSize, hashCode, lastBytes, (short) (negative ? -temperature : temperature));
        }

        private void calcForChunk(long offset, long end) {
            final MemorySegment data = this.data;
            long cityOffset, lastBytes, city, masked, hashCode;
            int temperature, word, delimiterIdx;
            byte cityNameSize;

            while (offset < end) {
                cityOffset = offset;
                lastBytes = 0;
                hashCode = 0;
                delimiterIdx = 8;

                while (delimiterIdx == 8) {
                    city = data.get(LONG_LE, offset);
                    masked = city ^ DELIMITER_MASK;
                    masked = (masked - 0x0101010101010101L) & ~masked & 0x8080808080808080L;
                    delimiterIdx = Long.numberOfTrailingZeros(masked) >>> 3;
                    if (delimiterIdx == 0) {
                        break;
                    }
                    offset += delimiterIdx;
                    lastBytes = city & SIGNIFICANT_BYTES_MASK[delimiterIdx];
                    hashCode = ((hashCode >>> 5) ^ lastBytes) * 0x517cc1b727220a95L;
                }

                cityNameSize = (byte) (offset - cityOffset);

                word = data.get(INT_LE, ++offset);
                offset += 4;

                if ((word & TWO_NEGATIVE_DIGITS_MASK) == TWO_NEGATIVE_DIGITS_MASK) {
                    word >>>= 8;
                    temperature = ZERO * 11 - ((word & BYTE_MASK) * 10 + ((word >>> 16) & BYTE_MASK));
                }
                else if ((word & THREE_DIGITS_MASK) == THREE_DIGITS_MASK) {
                    temperature = (word & BYTE_MASK) * 100 + ((word >>> 8) & BYTE_MASK) * 10 + ((word >>> 24) & BYTE_MASK) - ZERO * 111;
                }
                else if ((word & TWO_DIGITS_MASK) == TWO_DIGITS_MASK) {
                    temperature = (word & BYTE_MASK) * 10 + ((word >>> 16) & BYTE_MASK) - ZERO * 11;
                    offset--;
                }
                else {
                    // #.##-
                    word = (word >>> 8) | (data.get(BYTE, offset++) << 24);
                    temperature = ZERO * 111 - ((word & BYTE_MASK) * 100 + ((word >>> 8) & BYTE_MASK) * 10 + ((word >>> 24) & BYTE_MASK));
                }
                offset++;
                container.put(cityOffset, cityNameSize, Long.hashCode(hashCode), lastBytes, (short) temperature);
            }
        }

        public void collectTo(Map<String, TemperatureAggregation> results) {
            container.collectTo(results);
        }
    }
}

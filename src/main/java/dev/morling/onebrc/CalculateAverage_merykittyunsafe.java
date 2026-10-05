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

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * JDK 27 compatible version of the original "merykittyunsafe" solution.
 *
 * The memory-access methods of sun.misc.Unsafe throw UnsupportedOperationException by default
 * since JDK 26 (JEP 471 / JEP 498), so this version no longer uses Unsafe at all:
 *
 *  - the memory-mapped input file is read through the (final since JDK 22) FFM API, using
 *    offsets into the mapped MemorySegment instead of raw addresses;
 *  - the per-thread hash table (a plain byte[]) is accessed through byte-array-view VarHandles;
 *  - MemorySegment.reinterpret (a restricted method) is no longer needed.
 *
 * Build and run (the Vector API is still an incubator module in JDK 27):
 *
 *   javac --add-modules jdk.incubator.vector -d out CalculateAverage_merykittyunsafe.java
 *   java  --add-modules jdk.incubator.vector -cp out dev.morling.onebrc.CalculateAverage_merykittyunsafe
 */
public class CalculateAverage_merykittyunsafe {
    private static final String FILE = "./measurements.txt";

    private static final ByteOrder NATIVE_ORDER = ByteOrder.nativeOrder();
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfInt INT_UNALIGNED = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfLong LONG_UNALIGNED = ValueLayout.JAVA_LONG_UNALIGNED;

    private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED.length() >= 32
            ? ByteVector.SPECIES_256
            : ByteVector.SPECIES_128;
    private static final long KEY_MAX_SIZE = 100;

    private static class Aggregator {
        private long min = Integer.MAX_VALUE;
        private long max = Integer.MIN_VALUE;
        private long sum;
        private long count;

        public String toString() {
            return round(min / 10.) + "/" + round(sum / (double) (10 * count)) + "/" + round(max / 10.);
        }

        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }
    }

    // An open-address map that is specialized for this task
    private static class PoorManMap {
        // 100-byte key + 4-byte hash + 4-byte size +
        // 2-byte min + 2-byte max + 8-byte sum + 8-byte count
        private static final int ENTRY_SIZE = 128;
        private static final int SIZE_OFFSET = 0;
        private static final int MIN_OFFSET = 4;
        private static final int MAX_OFFSET = 6;
        private static final int SUM_OFFSET = 8;
        private static final int COUNT_OFFSET = 16;
        private static final int KEY_OFFSET = 24;

        // There is an assumption that map size <= 10000;
        private static final int CAPACITY = 1 << 17;
        private static final int ENTRY_MASK = ENTRY_SIZE * CAPACITY - 1;

        // Views over the backing byte[] (replaces sun.misc.Unsafe get/put on the array).
        // All offsets used below are naturally aligned, and plain get/set also allow unaligned access.
        private static final VarHandle SHORT_VIEW = MethodHandles.byteArrayViewVarHandle(short[].class, NATIVE_ORDER);
        private static final VarHandle INT_VIEW = MethodHandles.byteArrayViewVarHandle(int[].class, NATIVE_ORDER);
        private static final VarHandle LONG_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class, NATIVE_ORDER);

        final byte[] data;

        PoorManMap() {
            this.data = new byte[CAPACITY * ENTRY_SIZE];
        }

        int sizeAt(int entryOffset) {
            return (int) INT_VIEW.get(this.data, entryOffset + SIZE_OFFSET);
        }

        void observe(int entryOffset, long value) {
            final byte[] data = this.data;
            if ((short) SHORT_VIEW.get(data, entryOffset + MIN_OFFSET) > value) {
                SHORT_VIEW.set(data, entryOffset + MIN_OFFSET, (short) value);
            }
            if ((short) SHORT_VIEW.get(data, entryOffset + MAX_OFFSET) < value) {
                SHORT_VIEW.set(data, entryOffset + MAX_OFFSET, (short) value);
            }
            LONG_VIEW.set(data, entryOffset + SUM_OFFSET,
                    value + (long) LONG_VIEW.get(data, entryOffset + SUM_OFFSET));
            LONG_VIEW.set(data, entryOffset + COUNT_OFFSET,
                    1L + (long) LONG_VIEW.get(data, entryOffset + COUNT_OFFSET));
        }

        int indexSimple(MemorySegment input, long pos, int size) {
            int x;
            int y;
            if (size >= Integer.BYTES) {
                x = input.get(INT_UNALIGNED, pos);
                y = input.get(INT_UNALIGNED, pos + size - Integer.BYTES);
            }
            else {
                x = input.get(BYTE, pos);
                y = input.get(BYTE, pos + size - Byte.BYTES);
            }
            int hash = hash(x, y);
            int entryOffset = (hash * ENTRY_SIZE) & ENTRY_MASK;
            for (;; entryOffset = (entryOffset + ENTRY_SIZE) & ENTRY_MASK) {
                int nodeSize = sizeAt(entryOffset);
                if (nodeSize == 0) {
                    insertInto(entryOffset, input, pos, size);
                    return entryOffset;
                }
                else if (keyEqualScalar(entryOffset, input, pos, size)) {
                    return entryOffset;
                }
            }
        }

        void insertInto(int entryOffset, MemorySegment input, long pos, int size) {
            INT_VIEW.set(this.data, entryOffset + SIZE_OFFSET, size);
            SHORT_VIEW.set(this.data, entryOffset + MIN_OFFSET, Short.MAX_VALUE);
            SHORT_VIEW.set(this.data, entryOffset + MAX_OFFSET, Short.MIN_VALUE);
            // Copy the key together with its trailing ';' so that the vector comparison
            // in iterate() can cover the delimiter as well.
            MemorySegment.copy(input, BYTE, pos, this.data, entryOffset + KEY_OFFSET, size + 1);
        }

        void mergeInto(Map<String, Aggregator> target) {
            for (int entryOffset = 0; entryOffset < data.length; entryOffset += ENTRY_SIZE) {
                int size = sizeAt(entryOffset);
                if (size == 0) {
                    continue;
                }

                String key = new String(this.data, entryOffset + KEY_OFFSET, size, StandardCharsets.UTF_8);
                long min = (short) SHORT_VIEW.get(this.data, entryOffset + MIN_OFFSET);
                long max = (short) SHORT_VIEW.get(this.data, entryOffset + MAX_OFFSET);
                long sum = (long) LONG_VIEW.get(this.data, entryOffset + SUM_OFFSET);
                long count = (long) LONG_VIEW.get(this.data, entryOffset + COUNT_OFFSET);

                Aggregator v = target.computeIfAbsent(key, k -> new Aggregator());
                v.min = Math.min(v.min, min);
                v.max = Math.max(v.max, max);
                v.sum += sum;
                v.count += count;
            }
        }

        static int hash(int x, int y) {
            int seed = 0x9E3779B9;
            int rotate = 5;
            return (Integer.rotateLeft(x * seed, rotate) ^ y) * seed; // FxHash
        }

        private boolean keyEqualScalar(int entryOffset, MemorySegment input, long pos, int size) {
            if (sizeAt(entryOffset) != size) {
                return false;
            }

            // Be simple
            for (int i = 0; i < size; i++) {
                if (this.data[entryOffset + KEY_OFFSET + i] != input.get(BYTE, pos + i)) {
                    return false;
                }
            }
            return true;
        }
    }

    // Parse a number that may/may not contain a minus sign followed by a decimal with
    // 1 - 2 digits to the left and 1 digits to the right of the separator to a
    // fix-precision format. It returns the offset of the next line (presumably followed
    // the final digit and a '\n')
    private static long parseDataPoint(PoorManMap aggrMap, MemorySegment input, int entryOffset, long pos) {
        long word = input.get(LONG_UNALIGNED, pos);
        if (NATIVE_ORDER == ByteOrder.BIG_ENDIAN) {
            word = Long.reverseBytes(word);
        }
        // The 4th binary digit of the ascii of a digit is 1 while
        // that of the '.' is 0. This finds the decimal separator
        // The value can be 12, 20, 28
        int decimalSepPos = Long.numberOfTrailingZeros(~word & 0x10101000);
        int shift = 28 - decimalSepPos;
        // signed is -1 if negative, 0 otherwise
        long signed = (~word << 59) >> 63;
        long designMask = ~(signed & 0xFF);
        // Align the number to a specific position and transform the ascii code
        // to actual digit value in each byte
        long digits = ((word & designMask) << shift) & 0x0F000F0F00L;

        // Now digits is in the form 0xUU00TTHH00 (UU: units digit, TT: tens digit, HH: hundreds digit)
        // 0xUU00TTHH00 * (100 * 0x1000000 + 10 * 0x10000 + 1) =
        // 0x000000UU00TTHH00 +
        // 0x00UU00TTHH000000 * 10 +
        // 0xUU00TTHH00000000 * 100
        // Now TT * 100 has 2 trailing zeroes and HH * 100 + TT * 10 + UU < 0x400
        // This results in our value lies in the bit 32 to 41 of this product
        // That was close :)
        long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;
        long value = (absValue ^ signed) - signed;
        aggrMap.observe(entryOffset, value);
        return pos + (decimalSepPos >>> 3) + 3;
    }

    // Tail processing version of the above, do not over-fetch and be simple
    private static long parseDataPointSimple(PoorManMap aggrMap, MemorySegment input, int entryOffset, long pos) {
        int value = 0;
        boolean negative = false;
        if (input.get(BYTE, pos) == '-') {
            negative = true;
            pos++;
        }
        for (;; pos++) {
            int c = input.get(BYTE, pos);
            if (c == '.') {
                c = input.get(BYTE, pos + 1);
                value = value * 10 + (c - '0');
                pos += 3;
                break;
            }

            value = value * 10 + (c - '0');
        }
        value = negative ? -value : value;
        aggrMap.observe(entryOffset, value);
        return pos;
    }

    // An iteration of the main parse loop, parse a line starting from pos.
    // This requires pos to be the start of the line and there is spare space so
    // that we have relative freedom in processing
    // It returns the offset of the next line that it needs processing
    private static long iterate(PoorManMap aggrMap, MemorySegment input, long pos) {
        ByteVector line = ByteVector.fromMemorySegment(BYTE_SPECIES, input, pos, NATIVE_ORDER);

        // Find the delimiter ';'
        long semicolons = line.compare(VectorOperators.EQ, (byte) ';').toLong();

        // If we cannot find the delimiter in the vector, that means the key is
        // longer than the vector, fall back to scalar processing
        if (semicolons == 0) {
            int keySize = BYTE_SPECIES.length();
            while (input.get(BYTE, pos + keySize) != ';') {
                keySize++;
            }
            int node = aggrMap.indexSimple(input, pos, keySize);
            return parseDataPoint(aggrMap, input, node, pos + 1 + keySize);
        }

        // We inline the searching of the value in the hash map
        int keySize = Long.numberOfTrailingZeros(semicolons);
        int x;
        int y;
        if (keySize >= Integer.BYTES) {
            x = input.get(INT_UNALIGNED, pos);
            y = input.get(INT_UNALIGNED, pos + keySize - Integer.BYTES);
        }
        else {
            x = input.get(BYTE, pos);
            y = input.get(BYTE, pos + keySize - Byte.BYTES);
        }
        int hash = PoorManMap.hash(x, y);
        int entryOffset = (hash * PoorManMap.ENTRY_SIZE) & PoorManMap.ENTRY_MASK;
        for (;; entryOffset = (entryOffset + PoorManMap.ENTRY_SIZE) & PoorManMap.ENTRY_MASK) {
            int nodeSize = aggrMap.sizeAt(entryOffset);
            if (nodeSize == 0) {
                aggrMap.insertInto(entryOffset, input, pos, keySize);
                break;
            }

            if (nodeSize != keySize) {
                continue;
            }

            var nodeKey = ByteVector.fromArray(BYTE_SPECIES, aggrMap.data, entryOffset + PoorManMap.KEY_OFFSET);
            long eqMask = line.compare(VectorOperators.EQ, nodeKey).toLong();
            long validMask = semicolons ^ (semicolons - 1);
            if ((eqMask & validMask) == validMask) {
                break;
            }
        }

        return parseDataPoint(aggrMap, input, entryOffset, pos + keySize + 1);
    }

    private static long findOffset(MemorySegment input, long offset, long limit) {
        if (offset == 0) {
            return offset;
        }

        offset--;
        while (offset < limit) {
            if (input.get(BYTE, offset++) == '\n') {
                break;
            }
        }
        return offset;
    }

    // Process all lines that start in [offset, limit)
    private static PoorManMap processFile(MemorySegment input, long offset, long limit) {
        var aggrMap = new PoorManMap();
        if (offset == limit) {
            return aggrMap;
        }
        int batches = 2;
        long batchSize = Math.ceilDiv(limit - offset, batches);
        long offset0 = offset;
        long offset1 = offset + batchSize;
        long limit0 = Math.min(offset1, limit);
        long limit1 = limit;

        // Find the start of a new line
        offset0 = findOffset(input, offset0, limit0);
        offset1 = findOffset(input, offset1, limit1);

        long begin;
        long end = limit;
        long mainLoopMinWidth = Math.max(BYTE_SPECIES.vectorByteSize(), KEY_MAX_SIZE + 1 + Long.BYTES);
        if (limit1 - offset1 < mainLoopMinWidth) {
            begin = findOffset(input, offset, limit);
            while (begin < end - mainLoopMinWidth) {
                begin = iterate(aggrMap, input, begin);
            }
        }
        else {
            long begin0 = offset0;
            long begin1 = offset1;
            long end0 = limit0;
            long end1 = limit1;
            while (true) {
                boolean finish = false;
                if (begin0 < end0) {
                    begin0 = iterate(aggrMap, input, begin0);
                }
                else {
                    finish = true;
                }
                if (begin1 < end1 - mainLoopMinWidth) {
                    begin1 = iterate(aggrMap, input, begin1);
                }
                else {
                    if (finish) {
                        break;
                    }
                }
            }
            begin = begin1;
        }

        // Now we are at the tail, just be simple
        while (begin < end) {
            int keySize = 0;
            while (input.get(BYTE, begin + keySize) != ';') {
                keySize++;
            }
            int entryOffset = aggrMap.indexSimple(input, begin, keySize);
            begin = parseDataPointSimple(aggrMap, input, entryOffset, begin + 1 + keySize);
        }

        return aggrMap;
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        int processorCnt = Runtime.getRuntime().availableProcessors();
        var res = new TreeMap<String, Aggregator>();
        try (var file = FileChannel.open(Path.of(FILE), StandardOpenOption.READ);
                var arena = Arena.ofShared()) {
            var data = file.map(MapMode.READ_ONLY, 0, file.size(), arena);
            long chunkSize = Math.ceilDiv(data.byteSize(), processorCnt);
            var threadList = new Thread[processorCnt];
            var resultList = new PoorManMap[processorCnt];
            for (int i = 0; i < processorCnt; i++) {
                int index = i;
                long offset = i * chunkSize;
                long limit = Math.min((i + 1) * chunkSize, data.byteSize());
                var thread = new Thread(() -> resultList[index] = processFile(data, offset, limit));
                threadList[index] = thread;
                thread.start();
            }
            for (var thread : threadList) {
                thread.join();
            }

            // Collect the results
            for (var aggrMap : resultList) {
                aggrMap.mergeInto(res);
            }
        }

        System.out.println(res);
    }
}

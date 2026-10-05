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
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * JDK 27 port: no sun.misc.Unsafe. All raw memory access goes through the (final since JDK 22)
 * Foreign Function & Memory API, and the only non-standard dependency left is the Vector API,
 * which is still an incubator module in JDK 27 (JEP 537).
 *
 * Compile: javac --add-modules jdk.incubator.vector -d out CalculateAverage_jincongho.java
 * Run:     java  --add-modules jdk.incubator.vector -cp out dev.morling.onebrc.CalculateAverage_jincongho [file]
 *
 * Changelog (based on Macbook Pro Intel i7 6-cores 2.6GHz):
 *
 * Initial                          40000 ms
 * Parse key as byte vs string      30000 ms
 * Parse temp as fixed vs double    15000 ms
 * HashMap optimization             10000 ms
 * Simd + reduce memory copy         8000 ms
 *
 */
public class CalculateAverage_jincongho {

    private static final String FILE = "./measurements.txt";

    // Unaligned layouts: the measurements file and hash-table slots are read at arbitrary byte offsets.
    // (Native byte order, same as Unsafe used; the temperature trick below assumes little endian.)
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED;
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;

    /**
     * Vectorization utilities with 1BRC-specific optimizations
     */
    protected static class VectorUtils {

        // key length is usually less than 32 bytes, having more is just expensive
        public static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_256;

        /** Vectorized field delimiter search **/

        public static int findDelimiter(MemorySegment data, long offset) {
            return ByteVector.fromMemorySegment(VectorUtils.BYTE_SPECIES, data, offset, ByteOrder.nativeOrder())
                    .compare(VectorOperators.EQ, (byte) ';')
                    .firstTrue();
        }

        /** Hashing (explicit vectorization was tried and was slower, so scalar fxhash it is) **/

        // fxhash
        public static int hashCode(final MemorySegment array, final long offset, final short length) {
            final int seed = 0x9E3779B9;
            final int rotate = 5;

            int x, y;
            if (length >= Integer.BYTES) {
                x = array.get(INT, offset);
                y = array.get(INT, offset + length - Integer.BYTES);
            }
            else {
                x = array.get(BYTE, offset);
                y = array.get(BYTE, offset + length - Byte.BYTES);
            }

            return (Integer.rotateLeft(x * seed, rotate) ^ y) * seed;
        }

        /** Vectorized Key Comparison **/

        private static boolean notEquals(MemorySegment a, long aOffset, MemorySegment b, long bOffset, short length, VectorSpecies<Byte> species) {
            final long aLimit = aOffset + length, bLimit = bOffset + length;

            // main loop
            long loopBound = bOffset + species.loopBound(length);
            for (; bOffset < loopBound; aOffset += species.length(), bOffset += species.length()) {
                ByteVector av = ByteVector.fromMemorySegment(species, a, aOffset, ByteOrder.nativeOrder());
                ByteVector bv = ByteVector.fromMemorySegment(species, b, bOffset, ByteOrder.nativeOrder());
                if (av.compare(VectorOperators.NE, bv).anyTrue())
                    return true;
            }

            // tail cleanup - load last N bytes with mask
            if (bOffset < bLimit) {
                ByteVector av = ByteVector.fromMemorySegment(species, a, aOffset, ByteOrder.nativeOrder(), species.indexInRange(aOffset, aLimit));
                ByteVector bv = ByteVector.fromMemorySegment(species, b, bOffset, ByteOrder.nativeOrder(), species.indexInRange(bOffset, bLimit));
                if (av.compare(VectorOperators.NE, bv).anyTrue())
                    return true;
            }

            return false;
        }

    }

    /**
     * Measurement Hash Table (for each partition)
     * Uses contiguous native segments to optimize for cache-line (hopefully)
     *
     * Each entry:
     * - KEYS: keyLength (2 bytes) + key (100 bytes)
     * - VALUES: min (2 bytes) + max (2 bytes) + count (4 bytes) + sum ( 8 bytes)
     */
    protected static class PartitionAggr {

        private static final int MAP_SIZE = 1 << 14; // 2^14 = 16384, closes to 10000
        private static final int KEY_SIZE = 128; // key length (2 bytes) + key (100 bytes)
        private static final int KEY_MASK = (MAP_SIZE - 1);
        private static final int VALUE_SIZE = 16; // min (2 bytes) + max ( 2 bytes) + count (4 bytes) + sum (8 bytes)

        private final MemorySegment keys;
        private final MemorySegment values;

        /** Memory is owned by the given arena; it must stay open until the results have been merged and printed. */
        public PartitionAggr(Arena arena) {
            // allocate() returns zeroed memory, so key length 0 == empty slot
            keys = arena.allocate((long) MAP_SIZE * KEY_SIZE, 64);
            values = arena.allocate((long) MAP_SIZE * VALUE_SIZE, 16);

            // init min and max
            for (long offset = 0; offset < (long) MAP_SIZE * VALUE_SIZE; offset += VALUE_SIZE) {
                values.set(SHORT, offset, Short.MAX_VALUE);
                values.set(SHORT, offset + 2, Short.MIN_VALUE);
            }
        }

        public void update(MemorySegment key, long keyStart, short keyLength, int keyHash, short value) {
            int index = keyHash & KEY_MASK;
            long keyOffset = (long) index * KEY_SIZE;
            while ((keys.get(SHORT, keyOffset) != keyLength) ||
                    VectorUtils.notEquals(keys, keyOffset + 2, key, keyStart, keyLength, VectorUtils.BYTE_SPECIES)) {
                if (keys.get(SHORT, keyOffset) == 0) {
                    // put key
                    keys.set(SHORT, keyOffset, keyLength);
                    MemorySegment.copy(key, keyStart, keys, keyOffset + 2, keyLength);
                    break;
                }
                else {
                    index = (index + 1) & KEY_MASK;
                    keyOffset = (long) index * KEY_SIZE;
                }
            }

            long valueOffset = (long) index * VALUE_SIZE;
            values.set(SHORT, valueOffset, (short) Math.min(values.get(SHORT, valueOffset), value));
            valueOffset += 2;
            values.set(SHORT, valueOffset, (short) Math.max(values.get(SHORT, valueOffset), value));
            valueOffset += 2;
            values.set(INT, valueOffset, values.get(INT, valueOffset) + 1);
            valueOffset += 4;
            values.set(LONG, valueOffset, values.get(LONG, valueOffset) + value);
        }

        public void mergeTo(ResultAggr result) {
            for (int i = 0; i < MAP_SIZE; i++) {
                // extract key
                final long keyOffset = (long) i * KEY_SIZE;
                final short keyLength = keys.get(SHORT, keyOffset);
                if (keyLength == 0)
                    continue;

                // extract values (if key is not null)
                final long valueOffset = (long) i * VALUE_SIZE;
                result.compute(new ResultAggr.ByteKey(keys, keyOffset + 2, keyLength), (k, v) -> {
                    if (v == null) {
                        v = new ResultAggr.Measurement();
                    }
                    v.min = (short) Math.min(values.get(SHORT, valueOffset), v.min);
                    v.max = (short) Math.max(values.get(SHORT, valueOffset + 2), v.max);
                    v.count += values.get(INT, valueOffset + 4);
                    v.sum += values.get(LONG, valueOffset + 8);

                    return v;
                });
            }
        }

    }

    /**
     * Measurement Aggregation (for all partitions)
     */
    protected static class ResultAggr extends HashMap<ResultAggr.ByteKey, ResultAggr.Measurement> {

        private static final long serialVersionUID = 1L;

        public static class ByteKey implements Comparable<ByteKey> {
            private final MemorySegment data;
            private final long offset;
            private final short length;
            private String str;

            public ByteKey(MemorySegment data, long offset, short length) {
                this.data = data;
                this.offset = offset;
                this.length = length;
            }

            @Override
            public boolean equals(Object other) {
                return (length == ((ByteKey) other).length)
                        && !VectorUtils.notEquals(data, offset, ((ByteKey) other).data, ((ByteKey) other).offset, length, VectorUtils.BYTE_SPECIES);
            }

            @Override
            public int hashCode() {
                return VectorUtils.hashCode(data, offset, length);
            }

            @Override
            public String toString() {
                if (str == null) {
                    // finally has to do a copy!
                    byte[] copy = new byte[length];
                    MemorySegment.copy(data, offset, MemorySegment.ofArray(copy), 0, length);
                    str = new String(copy, StandardCharsets.UTF_8);
                }
                return str;
            }

            @Override
            public int compareTo(ByteKey o) {
                return toString().compareTo(o.toString());
            }
        }

        protected static class Measurement {
            public short min = Short.MAX_VALUE;
            public short max = Short.MIN_VALUE;
            public int count = 0;
            public long sum = 0;

            @Override
            public String toString() {
                return ((double) min / 10) + "/" + (Math.round((1.0 * sum) / count) / 10.0) + "/" + ((double) max / 10);
            }

        }

        public ResultAggr(int initialCapacity, float loadFactor) {
            super(initialCapacity, loadFactor);
        }

        public Map<ByteKey, Measurement> toSorted() {
            return new TreeMap<>(this);
        }

    }

    protected static class Partition implements Runnable {

        private final MemorySegment data;
        private final long start;
        private final long limit;
        private final PartitionAggr result;

        public Partition(MemorySegment data, long start, long limit, PartitionAggr result) {
            this.data = data;
            this.start = start;
            this.limit = limit;
            this.result = result;
        }

        @Override
        public void run() {
            final MemorySegment data = this.data;
            final PartitionAggr aggr = this.result;
            final int vectorLength = VectorUtils.BYTE_SPECIES.length();
            long offset = this.start;

            // main loop (vectorized)
            // stays far enough from the end so the vector / long reads below never cross the end of the file
            final long loopLimit = limit - (vectorLength * Math.ceilDiv(100, vectorLength) + Long.BYTES);
            while (offset < loopLimit) {
                long offsetStart = offset;

                // find station name upto ";"
                int found;
                do {
                    found = VectorUtils.findDelimiter(data, offset);
                    offset += found;
                } while (found == vectorLength);
                short stationLength = (short) (offset - offsetStart);
                int stationHash = VectorUtils.hashCode(data, offsetStart, stationLength);

                // find measurement upto "\n" (credit: merykitty)
                long numberBits = data.get(LONG, ++offset);
                final long invNumberBits = ~numberBits;
                final int decimalSepPos = Long.numberOfTrailingZeros(invNumberBits & 0x10101000);

                int shift = 28 - decimalSepPos;
                long signed = (invNumberBits << 59) >> 63;
                long designMask = ~(signed & 0xFF);
                long digits = ((numberBits & designMask) << shift) & 0x0F000F0F00L;
                long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;

                short fixed = (short) ((absValue ^ signed) - signed);
                offset += (decimalSepPos >>> 3) + 3;

                // update measurement
                aggr.update(data, offsetStart, stationLength, stationHash, fixed);
            }

            // tail loop (simple)
            while (offset < limit) {
                long offsetStart = offset;

                // find station name upto ";"
                short stationLength = 0;
                while (data.get(BYTE, offset++) != ';')
                    stationLength++;
                int stationHash = VectorUtils.hashCode(data, offsetStart, stationLength);

                // find measurement upto "\n"
                byte tempBuffer = data.get(BYTE, offset++);
                boolean isNegative = (tempBuffer == '-');
                short fixed = (short) (isNegative ? 0 : (tempBuffer - '0'));
                while (true) {
                    tempBuffer = data.get(BYTE, offset++);
                    if (tempBuffer == '.') {
                        fixed = (short) (fixed * 10 + (data.get(BYTE, offset) - '0'));
                        offset += 2;
                        break;
                    }
                    fixed = (short) (fixed * 10 + (tempBuffer - '0'));
                }
                fixed = isNegative ? (short) -fixed : fixed;

                // update measurement
                aggr.update(data, offsetStart, stationLength, stationHash, fixed);
            }
        }

    }

    public static void main(String[] args) throws IOException, InterruptedException {

        // long startTime = System.currentTimeMillis();

        final Path file = Path.of(args.length > 0 ? args[0] : FILE);

        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ);
                Arena arena = Arena.ofShared()) {

            // scan data
            MemorySegment data = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size(), arena);
            final long size = data.byteSize();
            final int processors = Runtime.getRuntime().availableProcessors();

            // partition split: cut at roughly equal sizes, then move each cut to the start of the next line
            long[] partition = new long[processors + 1];
            long partitionSize = Math.ceilDiv(size, processors);
            partition[processors] = size;
            for (int i = 1; i < processors; i++) {
                long p = Math.min(size, Math.max(partition[i - 1], i * partitionSize));

                // note: vectorize this made performance worse :(
                while (p < size && data.get(BYTE, p++) != '\n')
                    ;
                partition[i] = p;
            }

            // partition aggregation
            var threadList = new Thread[processors];
            PartitionAggr[] partAggrs = new PartitionAggr[processors];
            for (int i = 0; i < processors; i++) {
                if (partition[i] == partition[i + 1])
                    continue; // empty partition

                partAggrs[i] = new PartitionAggr(arena);
                threadList[i] = new Thread(new Partition(data, partition[i], partition[i + 1], partAggrs[i]));
                threadList[i].start();
            }

            // result
            ResultAggr result = new ResultAggr(1 << 14, 1);
            for (int i = 0; i < processors; i++) {
                if (threadList[i] == null)
                    continue;

                threadList[i].join();
                partAggrs[i].mergeTo(result);
            }
            System.out.println(result.toSorted());
        }

        // long elapsed = System.currentTimeMillis() - startTime;
        // System.out.println("Elapsed: " + ((double) elapsed / 1000.0));

    }

    /** Unit Tests **/

    public static void testMain(String[] args) {
        testHashCode();
        testNotEquals();
    }

    private static void testHashCode() {
        // same bytes at different offsets must hash identically, for key lengths from 1 to 100
        for (int i = 1; i <= 100; i++) {
            byte[] array = new byte[i];
            for (int j = 0; j < i; j++)
                array[j] = (byte) j;

            byte[] shifted = new byte[i + 7];
            System.arraycopy(array, 0, shifted, 7, i);

            assertTrue(VectorUtils.hashCode(MemorySegment.ofArray(array), 0, (short) i) == VectorUtils.hashCode(MemorySegment.ofArray(shifted), 7, (short) i));
        }
    }

    private static void testNotEquals() {
        byte[] a = new byte[128];
        byte[] b = new byte[128];

        // all equals
        for (int i = 1; i < 100; i++) {
            a[(i + 2) - 1] = 0;
            b[i - 1] = 0;
            a[(i + 2)] = 10;
            b[i] = 10;
            assertTrue(!VectorUtils.notEquals(MemorySegment.ofArray(a), 2, MemorySegment.ofArray(b), 0, (short) 100, ByteVector.SPECIES_64));
            assertTrue(!VectorUtils.notEquals(MemorySegment.ofArray(a), 2, MemorySegment.ofArray(b), 0, (short) 100, ByteVector.SPECIES_128));
            assertTrue(!VectorUtils.notEquals(MemorySegment.ofArray(a), 2, MemorySegment.ofArray(b), 0, (short) 100, ByteVector.SPECIES_256));
            assertTrue(!VectorUtils.notEquals(MemorySegment.ofArray(a), 2, MemorySegment.ofArray(b), 0, (short) 100, ByteVector.SPECIES_512));
        }

        // one el not equals
        for (int i = 1; i < 100; i++) {
            a[(i + 2) - 1] = 0;
            b[i - 1] = 0;
            a[(i + 2)] = 20;
            b[i] = 10;
            assertTrue(VectorUtils.notEquals(MemorySegment.ofArray(a), 2, MemorySegment.ofArray(b), 0, (short) 100, ByteVector.SPECIES_64));
            assertTrue(VectorUtils.notEquals(MemorySegment.ofArray(a), 2, MemorySegment.ofArray(b), 0, (short) 100, ByteVector.SPECIES_128));
            assertTrue(VectorUtils.notEquals(MemorySegment.ofArray(a), 2, MemorySegment.ofArray(b), 0, (short) 100, ByteVector.SPECIES_256));
            assertTrue(VectorUtils.notEquals(MemorySegment.ofArray(a), 2, MemorySegment.ofArray(b), 0, (short) 100, ByteVector.SPECIES_512));
        }
    }

    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new RuntimeException("Failed test");
        }
    }

}

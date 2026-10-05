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
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Java 27 compatible version: no sun.misc.Unsafe and no restricted FFM methods
 * (so no --sun-misc-unsafe-memory-access and no --enable-native-access needed).
 * The mapped file is read through the final java.lang.foreign API, the per-thread
 * results tables are plain on-heap long[]; only the Vector API is still an
 * incubator module (--add-modules jdk.incubator.vector).
 */
public class CalculateAverage_yourwass {
    static final class Stats {
        int min = 1000;
        int max = -1000;
        long sum;
        long count;

        void add(int temp) {
            if (temp < min)
                min = temp;
            if (temp > max)
                max = temp;
            sum += temp;
            count++;
        }

        Stats merge(Stats r) {
            if (r.min < this.min)
                this.min = r.min;
            if (r.max > this.max)
                this.max = r.max;
            this.sum += r.sum;
            this.count += r.count;
            return this;
        }
    }

    private static final String FILE = "measurements.txt";
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    private static final ByteOrder BYTEORDER = ByteOrder.nativeOrder();
    private static final int MAXINDEX = (1 << 16) + 10000; // short hash + max allowed cities for collisions at the end :p

    // Lines starting before (fileSize - TAIL_PAD) can be parsed with full-width vector loads without running
    // past the end of the mapping (city <= 100 bytes + one vector <= 64 bytes). The remaining tail of the file
    // is parsed with a plain scalar loop instead.
    private static final long TAIL_PAD = 256;

    // Layouts. The temperature lookup tables below assume little endian, so make that explicit.
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfShort SHORT_LE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    // Per-thread results table: a long[] with 4 longs (32 bytes) per record
    // [0] city length (low 32 bits, 0 = empty slot) | count (high 32 bits)
    // [1] city offset in the mapped file
    // [2] sum
    // [3] min (low 32 bits) | max (high 32 bits)
    private static final int RECORD_SHIFT = 2;

    // the parsing reads two shorts after possible '-'
    // first short, the Decimal part, can be N. or NN with N:[0..9]
    // second short, the Fraction part, can be N\n or .N
    private static final short[] LOOKUP_DECIMAL = new short[('9' << 8) + '9' + 1];
    private static final byte[] LOOKUP_FRACTION = new byte[('9' << 8) + '.' + 1];
    private static final byte[] LOOKUP_DOT_POSITIVE = new byte[('9' << 8) + '.' + 1];
    private static final byte[] LOOKUP_DOT_NEGATIVE = new byte[('9' << 8) + '.' + 1];
    static {
        for (int i = 0; i < 10; i++) {
            final int ones = i * 10;
            final int ix256 = i << 8;
            // case N. i.e. single digit decimals: skip to 11824 = ('.'<<8)+'0'
            LOOKUP_DECIMAL[('.' << 8) + '0' + i] = (short) ones;
            for (int j = 1; j < 10; j++) {
                // case NN i.e double digits decimals: skip to 12336 = ('0'<<8)+'0'
                LOOKUP_DECIMAL[('0' << 8) + '0' + ix256 + j] = (short) (j * 100 + ones);
            }
            // case N\n skip to 2608 = ('\n'<<8)+'0'
            LOOKUP_FRACTION[('\n' << 8) + '0' + i] = (byte) i;
            LOOKUP_DOT_POSITIVE[('\n' << 8) + '0' + i] = 4;
            LOOKUP_DOT_NEGATIVE[('\n' << 8) + '0' + i] = 5;
            // case .N skip to 12334 = ('0'<<8)+'.'
            LOOKUP_FRACTION[('0' << 8) + '.' + ix256] = (byte) i;
            LOOKUP_DOT_POSITIVE[('0' << 8) + '.' + ix256] = 5;
            LOOKUP_DOT_NEGATIVE[('0' << 8) + '.' + ix256] = 6;
        }
    }

    private static final Lock _mutex = new ReentrantLock(true);
    private static final TreeMap<String, Stats> aggregateResults = new TreeMap<>();

    public static void main(String[] args) throws IOException, InterruptedException {
        final long fileSize;
        final MemorySegment file;
        try (FileChannel fileChannel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ)) {
            fileSize = fileChannel.size();
            // The mapping stays valid after the channel is closed.
            file = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global());
        }

        // start and wait for threads to finish
        final int nThreads = Runtime.getRuntime().availableProcessors();
        final long chunkSize = fileSize / nThreads;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread[] threadList = new Thread[nThreads];
        for (int i = 0; i < nThreads; i++) {
            final int threadIndex = i;
            final long start = i * chunkSize;
            final long end = (i == nThreads - 1) ? fileSize : (i + 1) * chunkSize;
            threadList[i] = new Thread(() -> {
                try {
                    threadMain(threadIndex, nThreads, file, fileSize, start, end);
                }
                catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            threadList[i].start();
        }
        for (int i = 0; i < nThreads; i++)
            threadList[i].join();
        if (failure.get() != null)
            throw new RuntimeException("worker thread failed", failure.get());

        // prepare string and print
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (var entry : aggregateResults.entrySet()) {
            Stats record = entry.getValue();
            float min = record.min;
            min /= 10.f;
            float max = record.max;
            max /= 10.f;
            double avg = Math.round((record.sum * 1.0) / record.count) / 10.;
            if (!first)
                sb.append(", ");
            first = false;
            sb.append(entry.getKey()).append("=").append(min).append("/").append(avg).append("/").append(max);
        }
        sb.append("}\n");
        System.out.print(sb.toString());
        System.out.close();
    }

    /** Offset just after the next '\n' at or after pos (or limit, if there is none). */
    private static long nextLine(final MemorySegment file, long pos, final long limit) {
        while (pos < limit && file.get(BYTE, pos++) != '\n')
            ;
        return pos;
    }

    private static boolean citiesDiffer(final MemorySegment file, final long a, final long b, final long len) {
        long part = 0;
        final long fullWords = (len - 1) >> 3;
        for (; part < fullWords; part++)
            if (file.get(LONG_LE, a + (part << 3)) != file.get(LONG_LE, b + (part << 3)))
                return true;
        // last (partial) word: shift away the bytes that are past the end of the name
        return ((file.get(LONG_LE, a + (part << 3)) ^ file.get(LONG_LE, b + (part << 3))) << ((8 - (len & 7)) << 3)) != 0;
    }

    private static void threadMain(final int id, final int nThreads, final MemorySegment file, final long fileSize,
                                   long startOff, long endOff) {
        // snap to newlines
        if (id != 0)
            startOff = nextLine(file, startOff, fileSize);
        if (id != nThreads - 1)
            endOff = nextLine(file, endOff, fileSize);

        // lines starting before this offset are safe for full-width vector loads
        final long vectorEnd = Math.min(endOff, fileSize - TAIL_PAD);

        final Map<String, Stats> local = new HashMap<>();
        long cityOff = startOff;

        final long[] table = new long[MAXINDEX << RECORD_SHIFT];

        final long vectorByteSize = SPECIES.length();
        final ByteVector delim = ByteVector.broadcast(SPECIES, ';');
        long ptr = 0;
        while (cityOff < vectorEnd) {
            // parse city
            final ByteVector parsed = ByteVector.fromMemorySegment(SPECIES, file, cityOff, BYTEORDER);
            long mask = parsed.compare(VectorOperators.EQ, delim).toLong();
            while (mask == 0) {
                ptr += vectorByteSize;
                mask = ByteVector.fromMemorySegment(SPECIES, file, cityOff + ptr, BYTEORDER).compare(VectorOperators.EQ, delim).toLong();
            }
            final long cityLength = ptr + Long.numberOfTrailingZeros(mask);
            final long tempOff = cityOff + cityLength + 1;
            ptr = 0;

            // compute hash table index
            int index;
            if (cityLength > 1)
                index = (file.get(BYTE, cityOff) // mix the first,
                        ^ (file.get(BYTE, cityOff + 2) << 4) // the third (even if it is the delimiter ';')
                        ^ (file.get(BYTE, tempOff - 2) << 8) // and the last two bytes of each city's name
                        ^ (file.get(BYTE, tempOff - 3) << 12))
                        & 0xFFFF;
            else
                index = (file.get(BYTE, cityOff) << 8) & 0xFF00;
            // resolve collisions with linear probing
            // use vector api here also, but only if city name fits in one vector length, for faster default case
            int rec = index << RECORD_SHIFT;
            int recCityLength = (int) table[rec];
            if (cityLength <= vectorByteSize) {
                while (recCityLength > 0) {
                    if (cityLength == recCityLength) {
                        long sameMask = ByteVector.fromMemorySegment(SPECIES, file, table[rec + 1], BYTEORDER)
                                .compare(VectorOperators.EQ, parsed).toLong();
                        if (Long.numberOfTrailingZeros(~sameMask) >= cityLength)
                            break;
                    }
                    index++;
                    rec = index << RECORD_SHIFT;
                    recCityLength = (int) table[rec];
                }
            }
            else { // slower normal case for city names with length > vectorByteSize
                while (recCityLength > 0 && (cityLength != recCityLength || citiesDiffer(file, table[rec + 1], cityOff, cityLength))) {
                    index++;
                    rec = index << RECORD_SHIFT;
                    recCityLength = (int) table[rec];
                }
            }

            // add record for new key
            if (recCityLength == 0) {
                table[rec] = cityLength;
                table[rec + 1] = cityOff;
                table[rec + 3] = (1000 & 0xFFFFFFFFL) | ((long) -1000 << 32);
            }

            // parse temp with lookup tables
            final int temp;
            if (file.get(BYTE, tempOff) == '-') {
                final short decimal = file.get(SHORT_LE, tempOff + 1);
                final short fraction = file.get(SHORT_LE, tempOff + 3);
                temp = -LOOKUP_DECIMAL[decimal] - LOOKUP_FRACTION[fraction];
                cityOff = tempOff + LOOKUP_DOT_NEGATIVE[fraction];
            }
            else {
                final short decimal = file.get(SHORT_LE, tempOff);
                final short fraction = file.get(SHORT_LE, tempOff + 2);
                temp = LOOKUP_DECIMAL[decimal] + LOOKUP_FRACTION[fraction];
                cityOff = tempOff + LOOKUP_DOT_POSITIVE[fraction];
            }

            // merge
            final long minMax = table[rec + 3];
            int min = (int) minMax;
            int max = (int) (minMax >> 32);
            if (temp < min)
                min = temp;
            if (temp > max)
                max = temp;
            table[rec + 3] = (min & 0xFFFFFFFFL) | ((long) max << 32);
            table[rec + 2] += temp;
            table[rec] += 1L << 32;
        }

        // create strings from raw data
        for (int i = 0; i < MAXINDEX; i++) {
            final int rec = i << RECORD_SHIFT;
            final int len = (int) table[rec];
            if (len == 0)
                continue;
            final Stats stats = new Stats();
            stats.min = (int) table[rec + 3];
            stats.max = (int) (table[rec + 3] >> 32);
            stats.sum = table[rec + 2];
            stats.count = table[rec] >>> 32;
            final byte[] name = file.asSlice(table[rec + 1], len).toArray(BYTE);
            local.merge(new String(name, StandardCharsets.UTF_8), stats, Stats::merge);
        }

        // whatever is left (the last few lines of the file) is parsed byte by byte, so that nothing reads past the mapping
        processTail(file, cityOff, endOff, fileSize, local);

        // aggregate results onto TreeMap
        _mutex.lock();
        try {
            for (var entry : local.entrySet())
                aggregateResults.merge(entry.getKey(), entry.getValue(), Stats::merge);
        }
        finally {
            _mutex.unlock();
        }
    }

    private static void processTail(final MemorySegment file, long pos, final long end, final long fileSize, final Map<String, Stats> out) {
        final byte[] name = new byte[256];
        while (pos < end) {
            int n = 0;
            byte b;
            while ((b = file.get(BYTE, pos++)) != ';')
                name[n++] = b;

            boolean negative = false;
            b = file.get(BYTE, pos++);
            if (b == '-') {
                negative = true;
                b = file.get(BYTE, pos++);
            }
            int temp = 0;
            while (b != '\n') {
                if (b != '.')
                    temp = temp * 10 + (b - '0');
                if (pos >= fileSize) // last line without a trailing newline
                    break;
                b = file.get(BYTE, pos++);
            }
            out.computeIfAbsent(new String(name, 0, n, StandardCharsets.UTF_8), k -> new Stats()).add(negative ? -temp : temp);
        }
    }
}

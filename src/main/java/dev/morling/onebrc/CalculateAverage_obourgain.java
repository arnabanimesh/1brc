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

import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Java 27 compatible version: no sun.misc.Unsafe (its memory-access methods are terminally deprecated
 * and throw by default on recent JDKs) and no restricted FFM methods (e.g. MemorySegment::reinterpret),
 * so it runs without any --add-opens / --enable-native-access / --sun-misc-unsafe-memory-access flag.
 * Memory is accessed through the standard FFM API (final since JDK 22) and byte[] view VarHandles (JDK 9+).
 */
public class CalculateAverage_obourgain {

    private static final String FILE = "./measurements.txt";

    static class ThreadLocalState {
        private final OpenAddressingMap resultMap = new OpenAddressingMap();
        private final byte[] cityNameBuffer = new byte[128];
    }

    private static final ThreadLocal<ThreadLocalState> THREAD_LOCAL_STATE = ThreadLocal.withInitial(ThreadLocalState::new);
    public static final int PER_THREAD_MAP_CAPACITY = 65536;
    public static final int MASK = PER_THREAD_MAP_CAPACITY - 1;

    // Reading with an explicit byte order makes the code independent of the platform endianness. On little-endian CPUs the JIT turns this into a plain load + bswap.
    private static final ValueLayout.OfInt BIG_ENDIAN_INT_UNALIGNED = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    // Views on the byte[] used as a scratch buffer for the city name. Plain (non-atomic) access supports unaligned offsets.
    private static final VarHandle BYTES_AS_INT_BIG_ENDIAN = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    // for equality checks only the byte order doesn't matter, so use the native one and skip the bswap
    private static final VarHandle BYTES_AS_INT_NATIVE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());
    private static final VarHandle BYTES_AS_LONG_NATIVE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    // The int read from the file is always big-endian (first byte of the file in the most significant byte), whatever the platform
    public static final int MASK_3_BYTES = 0xFFFFFF00;
    public static final int MASK_2_BYTES = 0xFFFF0000;

    static class MeasurementAggregator {
        // deci-Celcius values
        private int min = Integer.MAX_VALUE;
        private int max = Integer.MIN_VALUE;
        private long sum;
        private int count;

        public void appendTo(StringBuilder stringBuilder) {
            // micro optim, never saw the toString on a profile
            stringBuilder.append(round(min)).append("/").append(round(((double) sum) / count)).append("/").append(round(max));
        }

        private double round(double value) {
            return Math.round(value) / 10.0;
        }

        public void reset() {
            min = Integer.MAX_VALUE;
            max = Integer.MIN_VALUE;
            sum = 0;
            count = 0;
        }

        void add(int measurementInDeciCelsius) {
            max = Math.max(max, measurementInDeciCelsius);
            min = Math.min(min, measurementInDeciCelsius);
            sum += measurementInDeciCelsius;
            count++;
        }
    }

    record PrintableMeasurement(String key, MeasurementAggregator measurementAggregator) {
    }

    public static void main(String[] args) throws Exception {
        // no close, leak everything and let the OS cleanup!
        var randomAccessFile = new RandomAccessFile(FILE, "r");
        // FileChannel.map(..., Arena) is not a restricted method, no --enable-native-access needed
        MemorySegment segment = randomAccessFile.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, randomAccessFile.length(), Arena.global());

        // can we do better to balance across cpu cores?
        int chunkSize = 20 * 1024 * 1024;

        var mergedResults = new ConcurrentHashMap<KeyWrapper, MeasurementAggregator>(1024);

        // Fork join pool as a lock free queue, it should help putting all threads to work faster
        try (ExecutorService executor = Executors.newWorkStealingPool()) {
            // start processing chunks asap, no need for an intermediate list. Finding chunks limits takes about 5ms. Using ForkJoinPool would be fun, but unlikely to yield any measurable gain
            createChunks(segment, chunkSize, chunk -> {
                executor.execute(() -> {
                    ThreadLocalState threadLocalState = THREAD_LOCAL_STATE.get();
                    processChunk(chunk, threadLocalState);
                    merge(mergedResults, threadLocalState.resultMap);
                });
            });
            executor.shutdown();
            boolean shutdownProperly = executor.awaitTermination(1, TimeUnit.MINUTES);
            if (!shutdownProperly) {
                throw new RuntimeException("did not complete on time");
            }
        }

        // making it over complicated here for the sake of gaining a few milliseconds. In fact, it doesn't matter much and the stupid code would be enough here
        List<PrintableMeasurement> entries = mergedResults.entrySet().stream()
                // we have to convert to String to have proper UTF-8 support for the sort
                .map(entry -> new PrintableMeasurement(new String(entry.getKey().key, StandardCharsets.UTF_8), entry.getValue()))
                .sorted(Comparator.comparing(PrintableMeasurement::key))
                .toList();

        // presize the StringBuilder to avoid any copy. With the worst case dataset (10k keys each 100 bytes long etc) it would resize, but I don't care much
        var sb = new StringBuilder(64 * 1024);
        sb.append('{');
        for (int i = 0; i < entries.size(); i++) {
            printEntry(entries, i, sb);
        }
        sb.append('}');
        System.out.println(sb);
    }

    private static void printEntry(List<PrintableMeasurement> entries, int i, StringBuilder sb) {
        var entry = entries.get(i);
        sb.append(entry.key());
        sb.append('=');
        entry.measurementAggregator().appendTo(sb);
        if (i != entries.size() - 1) {
            sb.append(',').append(' ');
        }
    }

    private static void processChunk(MemorySegment segment, ThreadLocalState threadLocalState) {
        // safe as our segments are in the tens of MB range
        int size = (int) segment.byteSize();
        long position = 0;

        while (position < size - 1) {
            position = processLineInChunk(segment, position, threadLocalState);
        }
    }

    private static long processLineInChunk(MemorySegment segment, long position, ThreadLocalState threadLocalState) {
        // compute hashCode for the city name, copy the bytes to a buffer and search for the semicolon all at once, so we don' t visit the same byte twice
        // the packing is used to return two ints. The alternative is to return an int and add a mutable field to ThreadLocalState, but that's a bit slower
        long packed_cityNameLength_hashCode = getCityNameLength(segment, position, threadLocalState);
        int cityNameLength = (int) (packed_cityNameLength_hashCode >> 32);
        int hashCode = (int) packed_cityNameLength_hashCode;

        MeasurementAggregator perCityStats = threadLocalState.resultMap.getOrCreate(threadLocalState, cityNameLength, hashCode);

        // I tried packing for decodeDouble, but here it is slower than passing the MeasurementAggregator
        // + 1 for the semicolon
        position = decodeDouble(segment, position + cityNameLength + 1, perCityStats);
        return position;
    }

    static long getCityNameLength(MemorySegment segment, long position, ThreadLocalState threadLocalState) {
        int cityNameLength = 0;
        int cityNameHashCode = 0;
        byte[] cityNameBuffer = threadLocalState.cityNameBuffer;

        while (true) {
            // trick: we know that we will have a value after the semicolon which is at least 3 bytes, so we can unroll the loop
            // adding one for the semicolon, we know we can always read 4 bytes without worrying about reading out of bounds
            int i = readBigEndianInt(segment, position + cityNameLength);

            // put all four bytes at once, we'll use cityNameLength to not read past the actual end of the name
            BYTES_AS_INT_BIG_ENDIAN.set(cityNameBuffer, cityNameLength, i);

            byte b0 = (byte) (i >>> 24);
            if (b0 == ';') {
                break;
            }

            byte b1 = (byte) (i >>> 16);
            if (b1 == ';') {
                cityNameHashCode = cityNameHashCode * 31 + b0;
                cityNameLength += 1;
                break;
            }

            byte b2 = (byte) (i >>> 8);
            if (b2 == ';') {
                int masked = i & MASK_2_BYTES;
                cityNameHashCode = cityNameHashCode * 31 + masked;
                cityNameLength += 2;
                break;
            }

            byte b3 = (byte) i;
            if (b3 == ';') {
                int masked = i & MASK_3_BYTES;
                cityNameHashCode = cityNameHashCode * 31 + masked;
                cityNameLength += 3;
                break;
            }
            cityNameHashCode = cityNameHashCode * 31 + i;
            cityNameLength += 4;
        }
        return ((long) cityNameLength << 32) | (cityNameHashCode & 0xffffffffL);
    }

    private static long decodeDouble(MemorySegment segment, long position, MeasurementAggregator perCityStats) {
        // values are assumed to be:
        // * maybe with a minus sign
        // * an integer part in the range of 0 to 99 included, single digit possible
        // * always with a single decimal
        // that's between 3 and 5 bytes

        long offsetFromSign = 0;
        long offsetFromValue = 0;
        int signum = 1;
        // peak at the first byte to see if we have a minus sign
        byte maybeSign = readByte(segment, position);
        if (maybeSign == '-') {
            offsetFromSign++;
            signum = -1;
        }

        // as the value is at least 3 bytes then we have a line feed, we can safely read 4 bytes
        int i = readBigEndianInt(segment, position + offsetFromSign);
        // keep in deci-Celcius, so we avoid a division for each line, and keep it for the end
        int tempInDeciCelcius = (byte) (i >>> 24) - '0';

        byte secondDigitOrDot = (byte) (i >>> 16);
        byte decimalDigit;
        if (secondDigitOrDot == '.') {
            decimalDigit = (byte) (i >>> 8);
            // +1 for the line feed
            offsetFromValue += 3 + 1;
        }
        else {
            tempInDeciCelcius = 10 * tempInDeciCelcius + (secondDigitOrDot - '0');
            decimalDigit = (byte) i;
            // +1 for the line feed
            offsetFromValue += 4 + 1;
        }
        tempInDeciCelcius = 10 * tempInDeciCelcius + (decimalDigit - '0');
        tempInDeciCelcius *= signum;
        perCityStats.add(tempInDeciCelcius);
        return position + offsetFromSign + offsetFromValue;
    }

    private static int readBigEndianInt(MemorySegment segment, long position) {
        // Bounds-checked read through the standard FFM API. It replaces the former Unsafe.getInt + Integer.reverseBytes.
        return segment.get(BIG_ENDIAN_INT_UNALIGNED, position);
    }

    private static byte readByte(MemorySegment segment, long position) {
        return segment.get(ValueLayout.JAVA_BYTE, position);
    }

    static final class KeyWrapper implements Comparable<KeyWrapper> {
        private final byte[] key;
        private final int keyHashCode;

        KeyWrapper(byte[] key, int keyHashCode) {
            this.key = key;
            this.keyHashCode = keyHashCode;
        }

        @Override
        public int hashCode() {
            return keyHashCode;
        }

        @Override
        public boolean equals(Object obj) {
            // I tried making the key field mutable and interning, but that only made performance more variable. Sometime faster, sometime slower
            var that = (KeyWrapper) obj;
            return Arrays.equals(this.key, that.key);
        }

        @Override
        public int compareTo(KeyWrapper o) {
            return Arrays.compare(this.key, o.key);
        }

        @Override
        public String toString() {
            return new String(key, StandardCharsets.UTF_8);
        }
    }

    private static void merge(ConcurrentHashMap<KeyWrapper, MeasurementAggregator> mergedResults, OpenAddressingMap chunkResult) {
        chunkResult.forEach((k1, v1) -> {
            var keyWrapper = new KeyWrapper(k1, Arrays.hashCode(k1));
            // compute is atomic, so we don't need to synchronize
            mergedResults.compute(keyWrapper, (k2, v2) -> {
                if (v2 == null) {
                    v2 = new MeasurementAggregator();
                }
                v2.min = Math.min(v2.min, v1.min);
                v2.max = Math.max(v2.max, v1.max);
                v2.sum += v1.sum;
                v2.count += v1.count;
                v1.reset();
                return v2;
            });
        });
    }

    static void createChunks(MemorySegment segment, int chunkSize, Consumer<MemorySegment> onChunkCreated) {
        long endOfPreviousChunk = 0;
        while (endOfPreviousChunk < segment.byteSize()) {
            long chunkStart = endOfPreviousChunk;

            long tmpChunkEnd = Math.min(segment.byteSize() - 1, endOfPreviousChunk + chunkSize);
            long chunkEnd;
            if (segment.get(ValueLayout.JAVA_BYTE, tmpChunkEnd) == '\n') {
                // we got lucky and our chunk ends on a line break
                chunkEnd = tmpChunkEnd + 1;
            }
            else {
                // round the chunk to the next line break, included
                chunkEnd = findNextLineBreak(segment, tmpChunkEnd) + 1;
            }
            MemorySegment slice = segment.asSlice(chunkStart, chunkEnd - chunkStart);
            onChunkCreated.accept(slice);
            endOfPreviousChunk = chunkEnd;
        }
    }

    static long findNextLineBreak(MemorySegment segment, long start) {

        long limit = segment.byteSize();
        for (long i = start; i < limit; i++) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, i);
            if (b == '\n') {
                return i;
            }
        }
        return segment.byteSize();
    }

    static class OpenAddressingMap {
        private final byte[][] keys;
        private final MeasurementAggregator[] values;
        private int size = 0;

        public OpenAddressingMap() {
            // must be power of 2
            this.keys = new byte[PER_THREAD_MAP_CAPACITY][];
            this.values = new MeasurementAggregator[PER_THREAD_MAP_CAPACITY];
        }

        public void forEach(final BiConsumer<byte[], MeasurementAggregator> consumer) {
            int remaining = size;
            // start at 0: slot 0 is a valid slot (the original started at 1 and could silently drop a city hashed there)
            for (int i = 0, length = values.length; remaining > 0 && i < length; i++) {
                MeasurementAggregator value = values[i];
                if (null != value) {
                    consumer.accept(keys[i], value);
                    remaining--;
                }
            }
        }

        public MeasurementAggregator getOrCreate(ThreadLocalState threadLocalState, int cityNameLength, int cityNameHashCode) {
            // as I mask I lose some bits. Reinject those bit to avoid too many collisions. Maybe expert in hashing can help?
            cityNameHashCode = (cityNameHashCode >> 16) ^ cityNameHashCode;

            byte[] cityNameBuffer = threadLocalState.cityNameBuffer;
            int keyIndex = cityNameHashCode & MASK;

            MeasurementAggregator value;
            while (null != (value = values[keyIndex])) {
                byte[] existingKey = keys[keyIndex];
                if (existingKey.length == cityNameLength && arrayEquals(existingKey, cityNameBuffer, cityNameLength)) {
                    return value;
                }
                // go to next slot
                keyIndex = (keyIndex + 1) & MASK;
            }
            return create(cityNameLength, cityNameBuffer, keyIndex);
        }

        private MeasurementAggregator create(int cityNameLength, byte[] cityNameBuffer, int keyIndex) {
            byte[] key = Arrays.copyOf(cityNameBuffer, cityNameLength);
            keys[keyIndex] = key;
            MeasurementAggregator value = new MeasurementAggregator();
            values[keyIndex] = value;
            size++;
            return value;
        }
    }

    /**
     * Compares the first {@code length} bytes of both arrays. Never reads past {@code length}
     * (the former Unsafe version could read up to 7 bytes past the end of the key).
     */
    static boolean arrayEquals(byte[] existingKey, byte[] cityNameBuffer, int length) {
        int i = 0;
        for (; i + 8 <= length; i += 8) {
            if ((long) BYTES_AS_LONG_NATIVE.get(existingKey, i) != (long) BYTES_AS_LONG_NATIVE.get(cityNameBuffer, i)) {
                return false;
            }
        }
        if (i + 4 <= length) {
            if ((int) BYTES_AS_INT_NATIVE.get(existingKey, i) != (int) BYTES_AS_INT_NATIVE.get(cityNameBuffer, i)) {
                return false;
            }
            i += 4;
        }
        for (; i < length; i++) {
            if (existingKey[i] != cityNameBuffer[i]) {
                return false;
            }
        }
        return true;
    }
}

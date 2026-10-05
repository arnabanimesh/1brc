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
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Java 27 compatible version.
 * <p>
 * Differences from the original:
 * <ul>
 * <li>No {@code sun.misc.Unsafe}. Its memory-access methods are deprecated for removal and throw
 * {@code UnsupportedOperationException} by default since JDK 26. All raw accesses now go through the
 * standard FFM API ({@link MemorySegment}), final since JDK 22, using absolute addresses.</li>
 * <li>The hash map lives in native memory instead of a {@code byte[]}, so it no longer depends on
 * array base offsets or object-header layout (compact object headers are the default in JDK 27) and it
 * is not a humongous heap allocation under G1 (the default GC in all environments in JDK 27).</li>
 * <li>All multi-byte accesses use explicit little-endian layouts, so there are no
 * {@code BIG_ENDIAN} special cases anymore.</li>
 * </ul>
 * <p>
 * Build:
 * {@code javac --add-modules jdk.incubator.vector -d out CalculateAverage_serkan_ozal.java}
 * <br>
 * Run:
 * {@code java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -cp out dev.morling.onebrc.CalculateAverage_serkan_ozal}
 * <p>
 * ({@code jdk.incubator.vector} is still an incubator module in JDK 27, JEP 537, and
 * {@code MemorySegment::reinterpret} is a restricted method, hence the two flags.)
 *
 * @author serkan-ozal
 */
public class CalculateAverage_serkan_ozal {

    private static final String FILE = System.getProperty("file.path", "./measurements.txt");

    private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED.length() >= 16
            // Since majority (99%) of the city names <= 16 bytes, according to my experiments,
            // 128 bit (16 byte) vectors perform better than 256 bit (32 byte) or 512 bit (64 byte) vectors
            // even though supported by platform.
            ? ByteVector.SPECIES_128
            : ByteVector.SPECIES_64;
    private static final int BYTE_SPECIES_SIZE = BYTE_SPECIES.vectorByteSize();
    private static final ByteOrder NATIVE_BYTE_ORDER = ByteOrder.nativeOrder();

    private static final byte NEW_LINE_SEPARATOR = '\n';
    private static final byte KEY_VALUE_SEPARATOR = ';';
    private static final int MAX_LINE_LENGTH = 128;

    // Get configurations
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    private static final boolean VERBOSE = false; // getBooleanConfig("VERBOSE", false);
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors(); // getIntegerConfig("THREAD_COUNT", Runtime.getRuntime().availableProcessors());
    private static final boolean USE_VTHREADS = false; // getBooleanConfig("USE_VTHREADS", false);
    private static final int VTHREAD_COUNT = 1024; // getIntegerConfig("VTHREAD_COUNT", 1024);
    private static final int REGION_COUNT = 256; // getIntegerConfig("REGION_COUNT", -1);
    private static final boolean USE_SHARED_ARENA = true; // getBooleanConfig("USE_SHARED_ARENA", true);
    private static final boolean USE_SHARED_REGION = true; // getBooleanConfig("USE_SHARED_REGION", true);
    private static final int MAP_CAPACITY = 1 << 17; // getIntegerConfig("MAP_CAPACITY", 1 << 17);
    private static final boolean CLOSE_STDOUT_ON_RESULT = true; // getBooleanConfig("CLOSE_STDOUT_ON_RESULT", true);
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // Raw memory access (replacement of sun.misc.Unsafe)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // A zero-based segment spanning the whole address space, so plain addresses (as obtained from
    // MemorySegment::address) can be used as offsets. This is the supported equivalent of Unsafe's
    // absolute-address accessors. Note that "reinterpret" is a restricted method (--enable-native-access).
    private static final MemorySegment MEM = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

    // Unaligned + explicit little-endian: same semantics on every platform and no alignment assumptions.
    private static final ValueLayout.OfShort SHORT_LE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static byte getByte(long address) {
        return MEM.get(ValueLayout.JAVA_BYTE, address);
    }

    private static short getShort(long address) {
        return MEM.get(SHORT_LE, address);
    }

    private static int getInt(long address) {
        return MEM.get(INT_LE, address);
    }

    private static long getLong(long address) {
        return MEM.get(LONG_LE, address);
    }

    private static void putShort(long address, short value) {
        MEM.set(SHORT_LE, address, value);
    }

    private static void putInt(long address, int value) {
        MEM.set(INT_LE, address, value);
    }

    private static void putLong(long address, long value) {
        MEM.set(LONG_LE, address, value);
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        if (VERBOSE) {
            System.out.println("Processing started at " + start);
            System.out.println("Vector byte size: " + BYTE_SPECIES.vectorByteSize());
            System.out.println("Use shared memory arena: " + USE_SHARED_ARENA);
            if (USE_VTHREADS) {
                System.out.println("Virtual thread count: " + VTHREAD_COUNT);
            }
            else {
                System.out.println("Thread count: " + THREAD_COUNT);
            }
            System.out.println("Map capacity: " + MAP_CAPACITY);
        }

        int concurrency = USE_VTHREADS ? VTHREAD_COUNT : THREAD_COUNT;
        int regionCount = REGION_COUNT > 0 ? REGION_COUNT : concurrency;
        ByteBuffer lineBuffer = ByteBuffer.allocateDirect(MAX_LINE_LENGTH);
        Result result = new Result();

        RandomAccessFile file = new RandomAccessFile(FILE, "r");
        FileChannel fc = file.getChannel();
        // A single shared arena (the original created one and immediately overwrote it when USE_SHARED_REGION was set)
        Arena arena = (USE_SHARED_ARENA || USE_SHARED_REGION) ? Arena.ofShared() : null;
        try {
            long fileSize = fc.size();
            long regionSize = fileSize / regionCount;
            long startPos = 0;
            ExecutorService executor = USE_VTHREADS
                    ? Executors.newVirtualThreadPerTaskExecutor()
                    : Executors.newFixedThreadPool(concurrency, new RegionProcessorThreadFactory());
            MemorySegment region = null;
            if (USE_SHARED_REGION) {
                region = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena);
            }

            List<Task> tasks = new ArrayList<>(regionCount);
            // Split whole file into regions and create tasks for each region
            List<Future<Void>> futures = new ArrayList<>(regionCount);
            for (int i = 0; i < regionCount; i++) {
                long endPos = Math.min(fileSize, startPos + regionSize);
                // Lines might split into different regions.
                // If so, move back to the line starting at the end of previous region
                long closestLineEndPos = (i < regionCount - 1)
                        ? findClosestLineEnd(fc, endPos, lineBuffer)
                        : fileSize;
                Task task = new Task(fc, region, startPos, closestLineEndPos);
                tasks.add(task);
                startPos = closestLineEndPos;
            }

            Queue<Task> sharedTasks = new ConcurrentLinkedQueue<>(tasks);

            // Start region processors to process tasks for each region
            for (int i = 0; i < concurrency; i++) {
                Request request = new Request(arena, sharedTasks, result);
                RegionProcessor regionProcessor = createRegionProcessor(request);
                Future<Void> future = executor.submit(regionProcessor);
                futures.add(future);
            }

            // Wait processors to complete
            for (Future<Void> future : futures) {
                future.get();
            }

            long finish = System.currentTimeMillis();
            if (VERBOSE) {
                System.out.println("Processing completed at " + finish);
                System.out.println("Processing completed in " + (finish - start) + " milliseconds");
            }

            // Print result to stdout
            result.print();

            if (CLOSE_STDOUT_ON_RESULT) {
                // After printing result, close stdout.
                // So parent process can complete without waiting this process completed.
                // Saves a few hundred milliseconds caused by unmap.
                System.out.close();
            }
        }
        finally {
            // Close memory arena if it is managed globally here (shared arena)
            if (arena != null) {
                arena.close();
            }
            fc.close();
            if (VERBOSE) {
                long finish = System.currentTimeMillis();
                System.out.println("All completed at " + finish);
                System.out.println("All Completed in " + ((finish - start)) + " milliseconds");
            }
        }
    }

    private static boolean getBooleanConfig(String envVarName, boolean defaultValue) {
        String envVarValue = System.getenv(envVarName);
        if (envVarValue == null) {
            return defaultValue;
        }
        else {
            return Boolean.parseBoolean(envVarValue);
        }
    }

    private static int getIntegerConfig(String envVarName, int defaultValue) {
        String envVarValue = System.getenv(envVarName);
        if (envVarValue == null) {
            return defaultValue;
        }
        else {
            return Integer.parseInt(envVarValue);
        }
    }

    private static long findClosestLineEnd(FileChannel fc, long endPos, ByteBuffer lineBuffer) throws IOException {
        long lineCheckStartPos = Math.max(0, endPos - MAX_LINE_LENGTH);
        lineBuffer.rewind();
        fc.read(lineBuffer, lineCheckStartPos);
        int i = MAX_LINE_LENGTH;
        while (lineBuffer.get(i - 1) != NEW_LINE_SEPARATOR) {
            i--;
        }
        return lineCheckStartPos + i;
    }

    private static RegionProcessor createRegionProcessor(Request request) {
        return new RegionProcessor(request);
    }

    private static class RegionProcessorThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        }

    }

    /**
     * Region processor
     */
    private static class RegionProcessor implements Callable<Void> {

        private final Arena arena;
        private final Queue<Task> sharedTasks;
        private final Result result;
        private OpenMap map;

        private RegionProcessor(Request request) {
            this.arena = request.arena;
            this.sharedTasks = request.sharedTasks;
            this.result = request.result;
        }

        @Override
        public Void call() throws Exception {
            if (VERBOSE) {
                System.out.println("[Processor-" + Thread.currentThread().getName() + "] Processing started at " + System.currentTimeMillis());
            }
            try {
                processRegion();
                return null;
            }
            finally {
                if (VERBOSE) {
                    System.out.println("[Processor-" + Thread.currentThread().getName() + "] Processing finished at " + System.currentTimeMillis());
                }
            }
        }

        private void processRegion() throws Exception {
            // Create map in its own thread (its native memory is owned by, and released from, this thread)
            this.map = new OpenMap();

            boolean arenaGiven = arena != null;
            // If no shared global memory arena is used, create and use its own local memory arena
            Arena a = arenaGiven ? arena : Arena.ofConfined();
            try {
                for (Task task = sharedTasks.poll(); task != null; task = sharedTasks.poll()) {
                    boolean regionGiven = task.region != null;
                    MemorySegment r = regionGiven
                            ? task.region
                            : task.fileChannel.map(FileChannel.MapMode.READ_ONLY, task.start, task.size, a);
                    long regionStart = regionGiven ? (r.address() + task.start) : r.address();
                    long regionEnd = regionStart + task.size;

                    doProcessRegion(regionStart, regionEnd);
                }

                if (VERBOSE) {
                    System.out.println("[Processor-" + Thread.currentThread().getName() + "] Region processed at " + System.currentTimeMillis());
                }

                // Some threads/processors might finish slightly before others.
                // So, instead of releasing their cores idle, merge their own results here.

                // If there is no another processor merging its results now, merge now.
                // Otherwise (there is already another thread/processor got the lock of merging),
                // Close current processor's own local memory arena (if no shared global memory arena is used) now
                // and merge its own results after then.

                boolean merged = result.tryMergeInto(map);
                if (VERBOSE && merged) {
                    System.out.println("[Processor-" + Thread.currentThread().getName() + "] Result merged at " + System.currentTimeMillis());
                }
                if (!merged) {
                    if (!arenaGiven) {
                        a.close();
                        a = null;
                        if (VERBOSE) {
                            System.out.println("[Processor-" + Thread.currentThread().getName() + "] Arena closed at " + System.currentTimeMillis());
                        }
                    }
                    result.mergeInto(map);
                    if (VERBOSE) {
                        System.out.println("[Processor-" + Thread.currentThread().getName() + "] Result merged at " + System.currentTimeMillis());
                    }
                }
            }
            finally {
                // Release the map's native memory (results have already been merged at this point)
                map.close();
                // If local memory arena is managed here and not closed yet, close it here
                if (!arenaGiven && a != null) {
                    a.close();
                    if (VERBOSE) {
                        System.out.println("[Processor-" + Thread.currentThread().getName() + "] Arena closed at " + System.currentTimeMillis());
                    }
                }
            }
        }

        private long findClosestLineEnd(long endPos, long minPos) {
            int i = 0;
            int maxI = Math.min(MAX_LINE_LENGTH, (int) (endPos - minPos));
            while (i <= maxI && getByte(endPos - i) != NEW_LINE_SEPARATOR) {
                i++;
            }
            return endPos - i + 1;
        }

        // Credits: merykitty
        private long extractValue(long regionPtr, long word, OpenMap map, int entryOffset) {
            // Parse and extract value

            // 1. level instruction set (no dependency between each other so can be run in parallel)
            long signed = (~word << 59) >> 63;
            int decimalSepPos = Long.numberOfTrailingZeros(~word & 0x10101000);

            // 2. level instruction set (no dependency between each other so can be run in parallel)
            long nextPtr = regionPtr + (decimalSepPos >>> 3) + 3;
            int shift = 28 - decimalSepPos;
            long designMask = ~(signed & 0xFF);

            long digits = ((word & designMask) << shift) & 0x0F000F0F00L;
            long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;
            int value = (int) ((absValue ^ signed) - signed);

            // Put extracted value into map
            map.putValue(entryOffset, value);

            // Return new position
            return nextPtr;
        }

        private void doProcessRegion(long regionStart, long regionEnd) {
            final long size = regionEnd - regionStart;
            final long segmentSize = size / 2;

            final long regionStart1 = regionStart;
            final long regionEnd1 = Math.max(regionStart1, findClosestLineEnd(regionStart1 + segmentSize, regionStart));

            final long regionStart2 = regionEnd1;
            final long regionEnd2 = regionEnd;

            long regionPtr1, regionPtr2;

            // Read and process region - main
            // Inspired by: @jerrinot
            // - two lines at a time (according to my experiment, this is optimum value in terms of register spilling)
            // - most of the implementation is inlined
            // - so get the benefit of ILP (Instruction Level Parallelism) better
            for (regionPtr1 = regionStart1, regionPtr2 = regionStart2; regionPtr1 < regionEnd1 && regionPtr2 < regionEnd2;) {
                // Search key/value separators and find keys' start and end positions
                ////////////////////////////////////////////////////////////////////////////////////////////////////////
                long keyStartPtr1 = regionPtr1;
                long keyStartPtr2 = regionPtr2;

                ByteVector keyVector1 = ByteVector.fromMemorySegment(BYTE_SPECIES, MEM, regionPtr1, NATIVE_BYTE_ORDER);
                ByteVector keyVector2 = ByteVector.fromMemorySegment(BYTE_SPECIES, MEM, regionPtr2, NATIVE_BYTE_ORDER);

                int keyLength1 = keyVector1.compare(VectorOperators.EQ, KEY_VALUE_SEPARATOR).firstTrue();
                int keyLength2 = keyVector2.compare(VectorOperators.EQ, KEY_VALUE_SEPARATOR).firstTrue();

                if (keyLength1 != BYTE_SPECIES_SIZE && keyLength2 != BYTE_SPECIES_SIZE) {
                    regionPtr1 += (keyLength1 + 1);
                    regionPtr2 += (keyLength2 + 1);
                }
                else {
                    if (keyLength1 != BYTE_SPECIES_SIZE) {
                        regionPtr1 += (keyLength1 + 1);
                    }
                    else {
                        regionPtr1 += BYTE_SPECIES_SIZE;
                        for (; getByte(regionPtr1) != KEY_VALUE_SEPARATOR; regionPtr1++)
                            ;
                        keyLength1 = (int) (regionPtr1 - keyStartPtr1);
                        regionPtr1++;
                    }
                    if (keyLength2 != BYTE_SPECIES_SIZE) {
                        regionPtr2 += (keyLength2 + 1);
                    }
                    else {
                        regionPtr2 += BYTE_SPECIES_SIZE;
                        for (; getByte(regionPtr2) != KEY_VALUE_SEPARATOR; regionPtr2++)
                            ;
                        keyLength2 = (int) (regionPtr2 - keyStartPtr2);
                        regionPtr2++;
                    }
                }

                // Read first words as they will be used while extracting values later
                // (little-endian by construction, see LONG_LE)
                long word1 = getLong(regionPtr1);
                long word2 = getLong(regionPtr2);
                ////////////////////////////////////////////////////////////////////////////////////////////////////////

                // Calculate key hashes and find entry indexes
                ////////////////////////////////////////////////////////////////////////////////////////////////////////
                int x1, y1, x2, y2;
                if (keyLength1 > 3 && keyLength2 > 3) {
                    x1 = getInt(keyStartPtr1);
                    y1 = getInt(regionPtr1 - 5);
                    x2 = getInt(keyStartPtr2);
                    y2 = getInt(regionPtr2 - 5);
                }
                else {
                    if (keyLength1 > 3) {
                        x1 = getInt(keyStartPtr1);
                        y1 = getInt(regionPtr1 - 5);
                    }
                    else {
                        x1 = getByte(keyStartPtr1);
                        y1 = getByte(regionPtr1 - 2);
                    }
                    if (keyLength2 > 3) {
                        x2 = getInt(keyStartPtr2);
                        y2 = getInt(regionPtr2 - 5);
                    }
                    else {
                        x2 = getByte(keyStartPtr2);
                        y2 = getByte(regionPtr2 - 2);
                    }
                }

                int keyHash1 = (Integer.rotateLeft(x1 * OpenMap.HASH_SEED, OpenMap.HASH_ROTATE) ^ y1) * OpenMap.HASH_SEED;
                int keyHash2 = (Integer.rotateLeft(x2 * OpenMap.HASH_SEED, OpenMap.HASH_ROTATE) ^ y2) * OpenMap.HASH_SEED;

                int entryIdx1 = (keyHash1 & OpenMap.ENTRY_HASH_MASK) << OpenMap.ENTRY_SIZE_SHIFT;
                int entryIdx2 = (keyHash2 & OpenMap.ENTRY_HASH_MASK) << OpenMap.ENTRY_SIZE_SHIFT;
                ////////////////////////////////////////////////////////////////////////////////////////////////////////

                // Put keys and calculate entry offsets to put values
                ////////////////////////////////////////////////////////////////////////////////////////////////////////
                int entryOffset1 = map.putKey(keyVector1, keyStartPtr1, keyLength1, entryIdx1);
                int entryOffset2 = map.putKey(keyVector2, keyStartPtr2, keyLength2, entryIdx2);
                ////////////////////////////////////////////////////////////////////////////////////////////////////////

                // Extract values by parsing and put them into map
                ////////////////////////////////////////////////////////////////////////////////////////////////////////
                regionPtr1 = extractValue(regionPtr1, word1, map, entryOffset1);
                regionPtr2 = extractValue(regionPtr2, word2, map, entryOffset2);
                ////////////////////////////////////////////////////////////////////////////////////////////////////////
            }

            // Read and process region - tail
            doProcessTail(regionPtr1, regionEnd1, regionPtr2, regionEnd2);
        }

        private void doProcessTail(long regionPtr1, long regionEnd1, long regionPtr2, long regionEnd2) {
            while (regionPtr1 < regionEnd1) {
                long keyStartPtr1 = regionPtr1;
                ByteVector keyVector1 = ByteVector.fromMemorySegment(BYTE_SPECIES, MEM, regionPtr1, NATIVE_BYTE_ORDER);
                int keyLength1 = keyVector1.compare(VectorOperators.EQ, KEY_VALUE_SEPARATOR).firstTrue();
                if (keyLength1 != BYTE_SPECIES_SIZE) {
                    regionPtr1 += (keyLength1 + 1);
                }
                else {
                    regionPtr1 += BYTE_SPECIES_SIZE;
                    for (; getByte(regionPtr1) != KEY_VALUE_SEPARATOR; regionPtr1++)
                        ;
                    keyLength1 = (int) (regionPtr1 - keyStartPtr1);
                    regionPtr1++;
                }
                int entryIdx1 = map.calculateEntryIndex(keyStartPtr1, keyLength1);
                int entryOffset1 = map.putKey(keyVector1, keyStartPtr1, keyLength1, entryIdx1);
                long word1 = getLong(regionPtr1);
                regionPtr1 = extractValue(regionPtr1, word1, map, entryOffset1);
            }
            while (regionPtr2 < regionEnd2) {
                long keyStartPtr2 = regionPtr2;
                ByteVector keyVector2 = ByteVector.fromMemorySegment(BYTE_SPECIES, MEM, regionPtr2, NATIVE_BYTE_ORDER);
                int keyLength2 = keyVector2.compare(VectorOperators.EQ, KEY_VALUE_SEPARATOR).firstTrue();
                if (keyLength2 != BYTE_SPECIES_SIZE) {
                    regionPtr2 += (keyLength2 + 1);
                }
                else {
                    regionPtr2 += BYTE_SPECIES_SIZE;
                    for (; getByte(regionPtr2) != KEY_VALUE_SEPARATOR; regionPtr2++)
                        ;
                    keyLength2 = (int) (regionPtr2 - keyStartPtr2);
                    regionPtr2++;
                }
                int entryIdx2 = map.calculateEntryIndex(keyStartPtr2, keyLength2);
                int entryOffset2 = map.putKey(keyVector2, keyStartPtr2, keyLength2, entryIdx2);
                long word2 = getLong(regionPtr2);
                regionPtr2 = extractValue(regionPtr2, word2, map, entryOffset2);
            }
        }

    }

    /**
     * Region processor task
     */
    private static final class Task {

        private final FileChannel fileChannel;
        private final MemorySegment region;
        private final long start;
        private final long end;
        private final long size;

        private Task(FileChannel fileChannel, MemorySegment region, long start, long end) {
            this.fileChannel = fileChannel;
            this.region = region;
            this.start = start;
            this.end = end;
            this.size = end - start;
        }

    }

    /**
     * Region processor request
     */
    private static final class Request {

        private final Arena arena;
        private final Queue<Task> sharedTasks;
        private final Result result;

        private Request(Arena arena, Queue<Task> sharedTasks, Result result) {
            this.arena = arena;
            this.sharedTasks = sharedTasks;
            this.result = result;
        }

    }

    /**
     * Result of each key (city)
     */
    private static final class KeyResult {

        private int count;
        private int minValue;
        private int maxValue;
        private long sum;

        private KeyResult(int count, int minValue, int maxValue, long sum) {
            this.count = count;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.sum = sum;
        }

        private void merge(KeyResult result) {
            count += result.count;
            minValue = Math.min(minValue, result.minValue);
            maxValue = Math.max(maxValue, result.maxValue);
            sum += result.sum;
        }

        @Override
        public String toString() {
            // "count * 10.0" (not "count * 10") to avoid int overflow for keys with > ~214M measurements
            return (minValue / 10.0) + "/" + round(sum / (count * 10.0)) + "/" + (maxValue / 10.0);
        }

        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }

    }

    /**
     * Global result
     */
    private static final class Result {

        private final Lock lock = new ReentrantLock();
        private final Map<String, KeyResult> resultMap;

        private Result() {
            this.resultMap = new TreeMap<>();
        }

        private boolean tryMergeInto(OpenMap map) {
            // Use lock (not "synchronized" block) to be virtual threads friendly
            if (!lock.tryLock()) {
                return false;
            }
            try {
                map.merge(this.resultMap);
                return true;
            }
            finally {
                lock.unlock();
            }
        }

        private void mergeInto(OpenMap map) {
            // Use lock (not "synchronized" block) to be virtual threads friendly
            lock.lock();
            try {
                map.merge(this.resultMap);
            }
            finally {
                lock.unlock();
            }
        }

        private void print() {
            StringBuilder sb = new StringBuilder(1 << 14);
            boolean firstEntryAppended = false;
            sb.append("{");
            for (Map.Entry<String, KeyResult> e : resultMap.entrySet()) {
                if (firstEntryAppended) {
                    sb.append(", ");
                }
                String key = e.getKey();
                KeyResult value = e.getValue();
                sb.append(key).append("=").append(value);
                firstEntryAppended = true;
            }
            sb.append('}');
            System.out.println(sb);
        }

    }

    /**
     * Custom map implementation to store results.
     * <p>
     * The table lives in native memory and is addressed as {@code base + entryOffset}, where
     * {@code entryOffset} is a plain offset in {@code [0, MAP_SIZE)}.
     */
    private static final class OpenMap implements AutoCloseable {

        // Layout
        // ================================
        // 0 : 4 bytes - count
        // 4 : 2 bytes - min value
        // 6 : 2 bytes - max value
        // 8 : 8 bytes - value sum
        // 16 : 4 bytes - key size
        // 20 : 4 bytes - padding
        // 24 : 100 bytes - key
        // 124 : 4 bytes - padding
        // ================================
        // 128 bytes - total

        private static final int ENTRY_SIZE = 128;
        private static final int ENTRY_SIZE_SHIFT = 7;

        private static final int COUNT_OFFSET = 0;
        private static final int MIN_VALUE_OFFSET = 4;
        private static final int MAX_VALUE_OFFSET = 6;
        private static final int VALUE_SUM_OFFSET = 8;
        private static final int KEY_SIZE_OFFSET = 16;
        private static final int KEY_OFFSET = 24;

        private static final int ENTRY_HASH_MASK = MAP_CAPACITY - 1;
        private static final int MAP_SIZE = ENTRY_SIZE * MAP_CAPACITY;
        private static final int ENTRY_MASK = MAP_SIZE - 1;

        private static final int HASH_SEED = 0x9E3779B9;
        private static final int HASH_ROTATE = 5;

        private final Arena arena;
        private final long base;
        private final int[] entryOffsets;
        private int entryOffsetIdx;

        private OpenMap() {
            // Confined: allocated, used and closed by the same (processor) thread.
            this.arena = Arena.ofConfined();
            // Entries are 128 bytes (two cache lines), so align the table to the entry size.
            MemorySegment table = arena.allocate(MAP_SIZE, ENTRY_SIZE);
            // The "empty slot" check relies on key size == 0, so don't depend on allocator zeroing.
            table.fill((byte) 0);
            this.base = table.address();
            // Max number of unique keys are 10K, so 1 << 14 (16384) is long enough to hold offsets for all of them
            this.entryOffsets = new int[1 << 14];
            this.entryOffsetIdx = 0;
        }

        @Override
        public void close() {
            arena.close();
        }

        // Credits: merykitty
        private int calculateEntryIndex(long address, int keyLength) {
            int x, y;
            if (keyLength >= Integer.BYTES) {
                x = getInt(address);
                y = getInt(address + keyLength - Integer.BYTES);
            }
            else {
                x = getByte(address);
                y = getByte(address + keyLength - Byte.BYTES);
            }
            // Calculate key hash
            int keyHash = (Integer.rotateLeft(x * HASH_SEED, HASH_ROTATE) ^ y) * HASH_SEED;
            // Get the position of the entry in the linear map based on calculated hash
            return (keyHash & ENTRY_HASH_MASK) << ENTRY_SIZE_SHIFT;
        }

        private int putKey(ByteVector keyVector, long keyStartAddress, int keyLength, int entryIdx) {
            // Start searching from the calculated position
            // and continue until find an available slot in case of hash collision
            // TODO Prevent infinite loop if all the slots are in use for other keys
            for (int entryOffset = entryIdx;; entryOffset = (entryOffset + ENTRY_SIZE) & ENTRY_MASK) {
                long entryAddress = base + entryOffset;
                int keySize = getInt(entryAddress + KEY_SIZE_OFFSET);
                // Check whether current index is empty (no another key is inserted yet)
                if (keySize == 0) {
                    // Initialize entry slot for new key
                    putShort(entryAddress + MIN_VALUE_OFFSET, Short.MAX_VALUE);
                    putShort(entryAddress + MAX_VALUE_OFFSET, Short.MIN_VALUE);
                    putInt(entryAddress + KEY_SIZE_OFFSET, keyLength);
                    MemorySegment.copy(MEM, keyStartAddress, MEM, entryAddress + KEY_OFFSET, keyLength);
                    entryOffsets[entryOffsetIdx++] = entryOffset;
                    return entryOffset;
                }
                // Check for hash collision (hashes are same, but keys are different).
                // If there is no collision (both hashes and keys are equals), return current slot's offset.
                // Otherwise, continue iterating until find an available slot.
                if (keySize == keyLength && keysEqual(keyVector, keyStartAddress, keyLength, entryAddress + KEY_OFFSET)) {
                    return entryOffset;
                }
            }
        }

        private boolean keysEqual(ByteVector keyVector, long keyStartAddress, int keyLength, long entryKeyAddress) {
            // Use vectorized search for the comparison of keys.
            // Since majority of the city names >= 8 bytes and <= 16 bytes,
            // this way is more efficient (according to my experiments) than any other comparisons (byte by byte or 2 longs).
            // Lanes past the end of the key can never match: the file side holds ';', digits, etc. (never 0)
            // while the map side is zero padded.
            ByteVector entryKeyVector = ByteVector.fromMemorySegment(BYTE_SPECIES, MEM, entryKeyAddress, NATIVE_BYTE_ORDER);
            int eqCount = keyVector.compare(VectorOperators.EQ, entryKeyVector).trueCount();
            if (keyLength <= BYTE_SPECIES_SIZE) {
                return eqCount == keyLength;
            }
            // Longer keys: the vector must cover the first BYTE_SPECIES_SIZE bytes completely.
            // (The original did not check this, so two different keys with the same length and the same
            // bytes after the first vector could have been merged.)
            if (eqCount != BYTE_SPECIES_SIZE) {
                return false;
            }

            // Compare remaining parts of the keys

            int alignedKeyLength = keyLength & 0xFFFFFFF8;
            int i;
            for (i = BYTE_SPECIES_SIZE; i < alignedKeyLength; i += Long.BYTES) {
                if (getLong(keyStartAddress + i) != getLong(entryKeyAddress + i)) {
                    return false;
                }
            }

            long wordA = getLong(keyStartAddress + i);
            long wordB = getLong(entryKeyAddress + i);
            // Keep only the (keyLength & 7) low bytes. Two shifts, because a single shift by 64 would be a no-op.
            int halfShift = (Long.BYTES - (keyLength & 0x00000007)) << 2;
            long mask = (0xFFFFFFFFFFFFFFFFL >>> halfShift) >>> halfShift;
            wordA = wordA & mask;
            // No need to mask "wordB" (word from key in the map), because it is already padded with 0s
            return wordA == wordB;
        }

        private void putValue(int entryOffset, int value) {
            long entryAddress = base + entryOffset;
            long countAddress = entryAddress + COUNT_OFFSET;
            long minValueAddress = entryAddress + MIN_VALUE_OFFSET;
            long maxValueAddress = entryAddress + MAX_VALUE_OFFSET;
            long sumAddress = entryAddress + VALUE_SUM_OFFSET;

            putInt(countAddress, getInt(countAddress) + 1);
            if (value < getShort(minValueAddress)) {
                putShort(minValueAddress, (short) value);
            }
            if (value > getShort(maxValueAddress)) {
                putShort(maxValueAddress, (short) value);
            }
            putLong(sumAddress, getLong(sumAddress) + value);
        }

        private void merge(Map<String, KeyResult> resultMap) {
            // Merge this local map into global result map
            Arrays.sort(entryOffsets, 0, entryOffsetIdx);
            for (int i = 0; i < entryOffsetIdx; i++) {
                long entryAddress = base + entryOffsets[i];
                int keyLength = getInt(entryAddress + KEY_SIZE_OFFSET);
                if (keyLength == 0) {
                    // No entry is available for this index, so continue iterating
                    continue;
                }
                byte[] keyBytes = new byte[keyLength];
                MemorySegment.copy(MEM, ValueLayout.JAVA_BYTE, entryAddress + KEY_OFFSET, keyBytes, 0, keyLength);
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                int count = getInt(entryAddress + COUNT_OFFSET);
                short minValue = getShort(entryAddress + MIN_VALUE_OFFSET);
                short maxValue = getShort(entryAddress + MAX_VALUE_OFFSET);
                long sum = getLong(entryAddress + VALUE_SUM_OFFSET);
                KeyResult result = new KeyResult(count, minValue, maxValue, sum);
                KeyResult existingResult = resultMap.get(key);
                if (existingResult == null) {
                    resultMap.put(key, result);
                }
                else {
                    existingResult.merge(result);
                }
            }
        }

    }

}

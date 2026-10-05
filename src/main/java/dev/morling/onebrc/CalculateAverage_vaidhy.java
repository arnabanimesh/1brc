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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Java 22+ port (final FFM API, JEP 454). sun.misc.Unsafe is no longer used.
 *
 * All "addresses" from the original are now byte offsets into the single
 * memory-mapped file segment, so no restricted FFM methods (e.g. reinterpret)
 * are needed and --enable-native-access is not required.
 */
public class CalculateAverage_vaidhy<I, T> {

    // Unaligned, explicitly little-endian 8-byte reads. The SWAR tricks below
    // (numberOfTrailingZeros -> byte index) assume little-endian byte order.
    private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED
            .withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final class HashEntry {
        private long startOffset;
        private long keyLength;
        private long suffix;
        private int next;
        IntSummaryStatistics value;
    }

    private static class PrimitiveHashMap {
        private final MemorySegment seg;
        private final HashEntry[] entries;
        private final long[] hashes;

        private final int twoPow;
        private int next = -1;

        PrimitiveHashMap(MemorySegment seg, int twoPow) {
            this.seg = seg;
            this.twoPow = twoPow;
            this.entries = new HashEntry[1 << twoPow];
            this.hashes = new long[1 << twoPow];
            for (int i = 0; i < entries.length; i++) {
                this.entries[i] = new HashEntry();
            }
        }

        public IntSummaryStatistics find(long startOffset, long endOffset, long hash, long suffix) {
            int len = entries.length;
            int h = Long.hashCode(hash);
            int initialIndex = (h ^ (h >> twoPow)) & (len - 1);
            int i = initialIndex;
            long lookupLength = endOffset - startOffset;

            long hashEntry = hashes[i];

            if (hashEntry == hash) {
                HashEntry entry = entries[i];
                if (lookupLength <= 7) {
                    // This works because
                    // hash = suffix , when simpleHash is just xor.
                    // Since length is not 8, suffix will have a 0 at the end.
                    // Since utf-8 strings can't have 0 in middle of a string this means
                    // we can stop here.
                    return entry.value;
                }
                boolean found = (entry.suffix == suffix &&
                        compareEntryKeys(startOffset, endOffset, entry.startOffset));
                if (found) {
                    return entry.value;
                }
            }

            if (hashEntry == 0) {
                HashEntry entry = entries[i];
                entry.startOffset = startOffset;
                entry.keyLength = lookupLength;
                hashes[i] = hash;
                entry.suffix = suffix;
                entry.next = next;
                this.next = i;
                entry.value = new IntSummaryStatistics();
                return entry.value;
            }

            i++;
            if (i == len) {
                i = 0;
            }

            if (i == initialIndex) {
                return null;
            }

            do {
                hashEntry = hashes[i];
                if (hashEntry == hash) {
                    HashEntry entry = entries[i];
                    if (lookupLength <= 7) {
                        return entry.value;
                    }
                    boolean found = (entry.suffix == suffix &&
                            compareEntryKeys(startOffset, endOffset, entry.startOffset));
                    if (found) {
                        return entry.value;
                    }
                }
                if (hashEntry == 0) {
                    HashEntry entry = entries[i];
                    entry.startOffset = startOffset;
                    entry.keyLength = lookupLength;
                    hashes[i] = hash;
                    entry.suffix = suffix;
                    entry.next = next;
                    this.next = i;
                    entry.value = new IntSummaryStatistics();
                    return entry.value;
                }

                i++;
                if (i == len) {
                    i = 0;
                }
            } while (i != initialIndex);
            return null;
        }

        private boolean compareEntryKeys(long startOffset, long endOffset, long entryStartOffset) {
            long entryIndex = entryStartOffset;
            long lookupIndex = startOffset;
            long endOffsetStop = endOffset - 7;

            for (; lookupIndex < endOffsetStop; lookupIndex += 8) {
                if (seg.get(LONG_LE, entryIndex) != seg.get(LONG_LE, lookupIndex)) {
                    return false;
                }
                entryIndex += 8;
            }

            return true;
        }

        String keyString(HashEntry entry) {
            byte[] keyBytes = seg
                    .asSlice(entry.startOffset, entry.keyLength)
                    .toArray(JAVA_BYTE);
            return new String(keyBytes, StandardCharsets.UTF_8);
        }

        public Iterable<HashEntry> entrySet() {
            return () -> new Iterator<>() {
                int scan = next;

                @Override
                public boolean hasNext() {
                    return scan != -1;
                }

                @Override
                public HashEntry next() {
                    HashEntry entry = entries[scan];
                    scan = entry.next;
                    return entry;
                }
            };
        }
    }

    private static final String FILE = "./measurements.txt";

    private static long simpleHash(long hash, long nextData) {
        return hash ^ nextData;
        // return (hash ^ Long.rotateLeft((nextData * C1), R1)) * C2;
    }

    /**
     * Reads 8 bytes at {@code offset} (little-endian). Unlike raw Unsafe reads, a
     * MemorySegment is bounds-checked, so a read that would cross the end of the
     * file falls back to a byte-wise read with zero padding (which is what the
     * zero-filled tail of an mmap'ed page used to give us).
     */
    private static long getLong(MemorySegment seg, long offset) {
        if (offset <= seg.byteSize() - 8) {
            return seg.get(LONG_LE, offset);
        }
        return getLongTail(seg, offset);
    }

    private static long getLongTail(MemorySegment seg, long offset) {
        long size = seg.byteSize();
        long value = 0;
        for (int i = 0; i < 8 && offset + i < size; i++) {
            value |= (seg.get(JAVA_BYTE, offset + i) & 0xFFL) << (i << 3);
        }
        return value;
    }

    private static int parseDouble(MemorySegment seg, long startOffset, long endOffset) {
        int normalized;
        int length = (int) (endOffset - startOffset);
        if (length == 5) {
            normalized = (seg.get(JAVA_BYTE, startOffset + 1) ^ 0x30);
            normalized = (normalized << 3) + (normalized << 1) + (seg.get(JAVA_BYTE, startOffset + 2) ^ 0x30);
            normalized = (normalized << 3) + (normalized << 1) + (seg.get(JAVA_BYTE, startOffset + 4) ^ 0x30);
            normalized = -normalized;
            return normalized;
        }
        if (length == 3) {
            normalized = (seg.get(JAVA_BYTE, startOffset) ^ 0x30);
            normalized = (normalized << 3) + (normalized << 1) + (seg.get(JAVA_BYTE, startOffset + 2) ^ 0x30);
            return normalized;
        }

        if (seg.get(JAVA_BYTE, startOffset) == '-') {
            normalized = (seg.get(JAVA_BYTE, startOffset + 1) ^ 0x30);
            normalized = (normalized << 3) + (normalized << 1) + (seg.get(JAVA_BYTE, startOffset + 3) ^ 0x30);
            normalized = -normalized;
            return normalized;
        }
        else {
            normalized = (seg.get(JAVA_BYTE, startOffset) ^ 0x30);
            normalized = (normalized << 3) + (normalized << 1) + (seg.get(JAVA_BYTE, startOffset + 1) ^ 0x30);
            normalized = (normalized << 3) + (normalized << 1) + (seg.get(JAVA_BYTE, startOffset + 3) ^ 0x30);
            return normalized;
        }
    }

    interface MapReduce<I> {

        void process(long keyStartOffset, long keyEndOffset, long hash, long suffix, int temperature);

        I result();
    }

    private final FileService fileService;
    private final Supplier<MapReduce<I>> chunkProcessCreator;
    private final Function<List<I>, T> reducer;

    interface FileService {
        long length();

        MemorySegment segment();
    }

    CalculateAverage_vaidhy(FileService fileService,
                            Supplier<MapReduce<I>> mapReduce,
                            Function<List<I>, T> reducer) {
        this.fileService = fileService;
        this.chunkProcessCreator = mapReduce;
        this.reducer = reducer;
    }

    static class LineStream {
        private final MemorySegment seg;
        private final long fileEnd;
        private final long chunkEnd;

        private long position;
        private long hash;

        private long suffix;

        private final ByteBuffer buf = ByteBuffer
                .allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN);

        public LineStream(FileService fileService, long offset, long chunkSize) {
            this.seg = fileService.segment();
            this.fileEnd = fileService.length();
            this.chunkEnd = offset + chunkSize;
            this.position = offset;
            this.hash = 0;
        }

        public boolean hasNext() {
            return position <= chunkEnd;
        }

        public long findSemi() {
            long h = 0;
            buf.rewind();

            for (long i = position; i < fileEnd; i++) {
                byte ch = seg.get(JAVA_BYTE, i);
                if (ch == ';') {
                    int discard = buf.remaining();
                    buf.rewind();
                    long nextData = (buf.getLong() << discard) >>> discard;
                    this.suffix = nextData;
                    this.hash = simpleHash(h, nextData);
                    position = i + 1;
                    return i;
                }
                if (buf.hasRemaining()) {
                    buf.put(ch);
                }
                else {
                    buf.flip();
                    long nextData = buf.getLong();
                    h = simpleHash(h, nextData);
                    buf.rewind();
                }
            }
            this.hash = h;
            this.suffix = buf.getLong();
            position = fileEnd;
            return fileEnd;
        }

        public long skipLine() {
            for (long i = position; i < fileEnd; i++) {
                byte ch = seg.get(JAVA_BYTE, i);
                if (ch == 0x0a) {
                    position = i + 1;
                    return i;
                }
            }
            position = fileEnd;
            return fileEnd;
        }

        public long findTemperature() {
            position += 3;
            for (long i = position; i < fileEnd; i++) {
                byte ch = seg.get(JAVA_BYTE, i);
                if (ch == 0x0a) {
                    position = i + 1;
                    return i;
                }
            }
            position = fileEnd;
            return fileEnd;
        }
    }

    private static final long START_BYTE_INDICATOR = 0x0101_0101_0101_0101L;
    private static final long END_BYTE_INDICATOR = START_BYTE_INDICATOR << 7;

    private static final long NEW_LINE_DETECTION = START_BYTE_INDICATOR * '\n';

    private static final long SEMI_DETECTION = START_BYTE_INDICATOR * ';';

    private static final long ALL_ONES = 0xffff_ffff_ffff_ffffL;

    private static long findByteOctet(long data, long pattern) {
        long match = data ^ pattern;
        return (match - START_BYTE_INDICATOR) & ((~match) & END_BYTE_INDICATOR);
    }

    private void bigWorker(long offset, long chunkSize, MapReduce<I> lineConsumer) {
        final MemorySegment seg = fileService.segment();
        long chunkStart = offset;
        long chunkEnd = chunkStart + chunkSize;
        long fileEnd = fileService.length();
        long stopPoint = Math.min(chunkEnd + 1, fileEnd);

        boolean skip = offset != 0;
        for (long position = chunkStart; position < stopPoint;) {
            if (skip) {
                long data = getLong(seg, position);
                long newLineMask = findByteOctet(data, NEW_LINE_DETECTION);
                if (newLineMask != 0) {
                    int newLinePosition = Long.numberOfTrailingZeros(newLineMask) >>> 3;
                    skip = false;
                    position = position + newLinePosition + 1;
                }
                else {
                    position = position + 8;
                }
                continue;
            }

            long stationStart = position;
            long stationEnd = -1;
            long hash = 0;
            long suffix = 0;
            do {
                long data = getLong(seg, position);
                long semiMask = findByteOctet(data, SEMI_DETECTION);
                if (semiMask != 0) {
                    int semiPosition = Long.numberOfTrailingZeros(semiMask) >>> 3;
                    stationEnd = position + semiPosition;
                    position = stationEnd + 1;

                    if (semiPosition != 0) {
                        suffix = data & (ALL_ONES >>> (64 - (semiPosition << 3)));
                    }
                    else {
                        suffix = getLong(seg, position - 8);
                    }
                    hash = simpleHash(hash, suffix);
                    break;
                }
                else {
                    hash = simpleHash(hash, data);
                    position = position + 8;
                }
            } while (true);

            int temperature = 0;
            {
                byte ch = seg.get(JAVA_BYTE, position++);
                boolean negative = false;
                if (ch == '-') {
                    negative = true;
                    ch = seg.get(JAVA_BYTE, position++);
                }
                do {
                    if (ch != '.') {
                        temperature *= 10;
                        temperature += (ch ^ '0');
                    }
                    ch = seg.get(JAVA_BYTE, position++);
                } while (ch != '\n');
                if (negative) {
                    temperature = -temperature;
                }
            }

            lineConsumer.process(stationStart, stationEnd, hash, suffix, temperature);
        }
    }

    private void smallWorker(long offset, long chunkSize, MapReduce<I> lineConsumer) {
        final MemorySegment seg = fileService.segment();
        LineStream lineStream = new LineStream(fileService, offset, chunkSize);

        if (offset != 0) {
            if (lineStream.hasNext()) {
                // Skip the first line.
                lineStream.skipLine();
            }
            else {
                // No lines then do nothing.
                return;
            }
        }
        while (lineStream.hasNext()) {
            long keyStartOffset = lineStream.position;
            long keyEndOffset = lineStream.findSemi();
            long keyHash = lineStream.hash;
            long suffix = lineStream.suffix;
            long valueStartOffset = lineStream.position;
            long valueEndOffset = lineStream.findTemperature();
            int temperature = parseDouble(seg, valueStartOffset, valueEndOffset);
            lineConsumer.process(keyStartOffset, keyEndOffset, keyHash, suffix, temperature);
        }
    }

    // file size = 7
    // (0,0) (0,0) small chunk= (0,7)
    // a;0.1\n

    public T master(int shards, ExecutorService executor) {
        List<Future<I>> summaries = new ArrayList<>();
        long len = fileService.length();

        if (len > 128) {
            long bigChunk = Math.floorDiv(len, shards);
            long bigChunkReAlign = bigChunk & 0xffff_ffff_ffff_fff8L;

            long smallChunkStart = bigChunkReAlign * shards;
            long smallChunkSize = len - smallChunkStart;

            for (long offset = 0; offset < smallChunkStart; offset += bigChunkReAlign) {
                MapReduce<I> mr = chunkProcessCreator.get();
                final long transferOffset = offset;
                Future<I> task = executor.submit(() -> {
                    bigWorker(transferOffset, bigChunkReAlign, mr);
                    return mr.result();
                });
                summaries.add(task);
            }

            MapReduce<I> mrLast = chunkProcessCreator.get();
            Future<I> lastTask = executor.submit(() -> {
                smallWorker(smallChunkStart, smallChunkSize - 1, mrLast);
                return mrLast.result();
            });
            summaries.add(lastTask);
        }
        else {

            MapReduce<I> mrLast = chunkProcessCreator.get();
            Future<I> lastTask = executor.submit(() -> {
                smallWorker(0, len - 1, mrLast);
                return mrLast.result();
            });
            summaries.add(lastTask);
        }

        List<I> summariesDone = summaries.stream()
                .map(task -> {
                    try {
                        return task.get();
                    }
                    catch (InterruptedException | ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
        return reducer.apply(summariesDone);
    }

    static class DiskFileService implements FileService {
        private final long fileSize;
        private final MemorySegment segment;

        DiskFileService(String fileName) throws IOException {
            try (FileChannel fileChannel = FileChannel.open(Path.of(fileName),
                    StandardOpenOption.READ)) {
                this.fileSize = fileChannel.size();
                // The mapping is tied to the global arena, so it stays valid after the channel closes.
                this.segment = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0,
                        fileSize, Arena.global());
            }
        }

        @Override
        public long length() {
            return fileSize;
        }

        @Override
        public MemorySegment segment() {
            return segment;
        }
    }

    private static class ChunkProcessorImpl implements MapReduce<PrimitiveHashMap> {

        // 1 << 14 > 10,000 so it works
        private final PrimitiveHashMap statistics;

        ChunkProcessorImpl(MemorySegment seg) {
            this.statistics = new PrimitiveHashMap(seg, 15);
        }

        @Override
        public void process(long keyStartOffset, long keyEndOffset, long hash, long suffix, int temperature) {
            IntSummaryStatistics stats = statistics.find(keyStartOffset, keyEndOffset, hash, suffix);
            stats.accept(temperature);
        }

        @Override
        public PrimitiveHashMap result() {
            return statistics;
        }
    }

    public static void main(String[] args) throws IOException {
        DiskFileService diskFileService = new DiskFileService(FILE);
        MemorySegment segment = diskFileService.segment();

        CalculateAverage_vaidhy<PrimitiveHashMap, Map<String, IntSummaryStatistics>> calculateAverageVaidhy = new CalculateAverage_vaidhy<>(
                diskFileService,
                () -> new ChunkProcessorImpl(segment),
                CalculateAverage_vaidhy::combineOutputs);

        int proc = Runtime.getRuntime().availableProcessors();

        ExecutorService executor = Executors.newFixedThreadPool(proc);
        Map<String, IntSummaryStatistics> output = calculateAverageVaidhy.master(2 * proc, executor);
        executor.shutdown();

        Map<String, String> outputStr = toPrintMap(output);
        System.out.println(outputStr);
    }

    private static Map<String, String> toPrintMap(Map<String, IntSummaryStatistics> output) {

        Map<String, String> outputStr = new TreeMap<>();
        for (Map.Entry<String, IntSummaryStatistics> entry : output.entrySet()) {
            IntSummaryStatistics stat = entry.getValue();
            outputStr.put(entry.getKey(),
                    "%s/%s/%s".formatted(
                            stat.getMin() / 10.0,
                            Math.round(stat.getAverage()) / 10.0,
                            stat.getMax() / 10.0));
        }
        return outputStr;
    }

    private static Map<String, IntSummaryStatistics> combineOutputs(
                                                                    List<PrimitiveHashMap> list) {

        Map<String, IntSummaryStatistics> output = HashMap.newHashMap(10000);
        for (PrimitiveHashMap map : list) {
            for (HashEntry entry : map.entrySet()) {
                if (entry.value != null) {
                    String keyStr = map.keyString(entry);

                    output.compute(keyStr, (ignore, val) -> {
                        if (val == null) {
                            return entry.value;
                        }
                        else {
                            val.combine(entry.value);
                            return val;
                        }
                    });
                }
            }
        }

        return output;
    }
}

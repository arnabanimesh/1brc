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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * Java 22+ port: sun.misc.Unsafe is gone. Memory access now goes through the
 * standard APIs that replace it:
 * <ul>
 * <li>off-heap (the mmapped file): {@link MemorySegment} + {@link ValueLayout} (JEP 454)</li>
 * <li>on-heap (byte[] name buffers): {@link VarHandle} byte-array view (JEP 193)</li>
 * </ul>
 * All multi-byte reads are explicitly little-endian, because the SWAR tricks below
 * (semicolon search, temperature parsing) depend on that byte order.
 */
public final class CalculateAverage_JaimePolidura {
    private static final String FILE = "./measurements.txt";
    private static final long SEMICOLON_PATTERN = 0X3B3B3B3B3B3B3B3BL;

    // Unaligned, little-endian 8-byte read from a MemorySegment
    private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    // Little-endian 8-byte read/write on a byte[]
    private static final VarHandle BYTES_AS_LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    // Station names are at most 100 bytes. The name buffer is written one 8-byte word at a time,
    // so it must have room for the whole last word: ceil(100 / 8) * 8 = 104. 128 leaves headroom.
    private static final int NAME_BUFFER_SIZE = 128;

    // A line is at most ~110 bytes and the parsers read whole 8-byte words, so they can read up to
    // ~110 bytes past the start of a line. Lines starting closer than this to the end of the file
    // are parsed from a zero-padded copy instead, so we never read outside the mapped segment.
    private static final int TAIL_SAFETY_MARGIN = 128;

    public static void main(String[] args) throws Exception {
        Worker[] workers = createWorkers();

        startWorkers(workers);
        joinWorkers(workers);

        Map<String, Result> results = mergeWorkersResults(workers);
        printResults(results);
    }

    private static void joinWorkers(Worker[] workers) throws InterruptedException {
        for (int i = 0; i < workers.length; i++) {
            workers[i].join();
        }
    }

    private static void startWorkers(Worker[] workers) {
        for (int i = 0; i < workers.length; i++) {
            workers[i].start();
        }
    }

    private static Worker[] createWorkers() throws Exception {
        long fileSize;
        MemorySegment mmappedFile;

        // The mapping's lifetime is bound to the Arena, not to the channel, so the channel can be closed right away
        try (RandomAccessFile channel = new RandomAccessFile(FILE, "r")) {
            fileSize = channel.length();
            mmappedFile = channel.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global());
        }

        int nWorkers = fileSize > 1024 * 1024 ? Runtime.getRuntime().availableProcessors() : 1;
        Worker[] workers = new Worker[nWorkers];
        long quantityPerWorker = Math.floorDiv(fileSize, nWorkers);
        long quantityLastWorker = quantityPerWorker + (fileSize % nWorkers);

        for (int i = 0; i < nWorkers; i++) {
            boolean isLastWorker = i == nWorkers - 1;

            // Offsets into the mapped segment (no raw addresses any more)
            long startOffset = quantityPerWorker * i;
            long endOffset = startOffset + (isLastWorker ? quantityLastWorker : quantityPerWorker);
            workers[i] = new Worker(mmappedFile, fileSize, startOffset, endOffset);
            workers[i].setPriority(Thread.MAX_PRIORITY);
        }

        return workers;
    }

    private static Map<String, Result> mergeWorkersResults(Worker[] workers) {
        Map<String, Result> mergedResults = new TreeMap<>();

        for (int i = 0; i < workers.length; i++) {
            Worker worker = workers[i];

            for (Result entry : worker.results.entries) {
                if (entry != null) {
                    String name = new String(entry.name, 0, entry.nameLength, StandardCharsets.UTF_8);
                    Result alreadyExistingResult = mergedResults.get(name);
                    if (alreadyExistingResult != null) {
                        alreadyExistingResult.min = Math.min(alreadyExistingResult.min, entry.min);
                        alreadyExistingResult.max = Math.max(alreadyExistingResult.max, entry.max);
                        alreadyExistingResult.count = alreadyExistingResult.count + entry.count;
                        alreadyExistingResult.sum = alreadyExistingResult.sum + entry.sum;
                    }
                    else {
                        mergedResults.put(name, entry);
                    }
                }
            }
        }

        return mergedResults;
    }

    private static void printResults(Map<String, Result> results) {
        StringBuilder stringBuilder = new StringBuilder(results.size() * 32);
        stringBuilder.append('{');

        for (Map.Entry<String, Result> entry : results.entrySet()) {
            if (stringBuilder.length() > 1) {
                stringBuilder.append(", ");
            }

            Result result = entry.getValue();
            stringBuilder.append(entry.getKey())
                    .append('=')
                    .append(round(((double) result.min) / 10.0))
                    .append('/')
                    .append(round((double) result.sum / (result.count * 10)))
                    .append('/')
                    .append(round(((double) result.max) / 10.0d));

        }

        stringBuilder.append('}');

        System.out.println(stringBuilder);
    }

    static class Worker extends Thread {
        private final byte[] lastParsedNameBytes = new byte[NAME_BUFFER_SIZE];
        private int lastParsedNameLength;
        private long lastParsedNameHash;
        private int lastParsedTemperature;

        private final SimpleMap results;
        private final MemorySegment mmappedFile;
        private final long fileSize;
        // Offsets are relative to whichever segment is currently being parsed
        // (the mapped file, or the padded tail copy at the very end).
        private long currentOffset; // Will point to beginning of string
        private long endOffset; // Will point to \n

        public Worker(MemorySegment mmappedFile, long fileSize, long startOffset, long endOffset) {
            super("Worker[" + startOffset + ", " + endOffset + "]");

            this.fileSize = fileSize;
            this.mmappedFile = mmappedFile;
            this.currentOffset = startOffset;
            this.endOffset = endOffset;

            this.results = new SimpleMap(roundUpToPowerOfTwo(1 << 16)); // 2^16
        }

        @Override
        public void run() {
            adjustStartOffset();
            adjustEndOffset();

            if (this.currentOffset >= endOffset) {
                return;
            }

            // Fast path: every line that starts far enough from EOF can be parsed straight from the mapping
            parseLines(mmappedFile, Math.min(endOffset, fileSize - TAIL_SAFETY_MARGIN));

            // Slow path: the last few lines of the file (only ever reached by the worker that owns EOF)
            if (this.currentOffset < endOffset) {
                parseTail();
            }
        }

        private void parseLines(MemorySegment segment, long limit) {
            while (currentOffset < limit) {
                parseName(segment);
                parseTemperature(segment);

                this.currentOffset++; // We don't want it to point to \n

                results.put(this.lastParsedNameHash, this.lastParsedNameBytes, this.lastParsedNameLength, this.lastParsedTemperature);
            }
        }

        // Copies the remaining bytes of the file into a zero-padded native segment, so that the
        // 8-byte word reads of the parsers cannot go past the end of the segment.
        private void parseTail() {
            long tailStart = this.currentOffset;
            long tailLength = fileSize - tailStart;

            try (Arena arena = Arena.ofConfined()) {
                // Arena.allocate returns zeroed memory, so the padding after the copied bytes is 0x00
                MemorySegment tail = arena.allocate(tailLength + TAIL_SAFETY_MARGIN);
                MemorySegment.copy(mmappedFile, tailStart, tail, 0, tailLength);

                this.currentOffset = 0;
                this.endOffset -= tailStart;

                parseLines(tail, endOffset);
            }
        }

        // Idea from Quan Anh Mai's implementation
        private void parseTemperature(MemorySegment segment) {
            long numberWord = segment.get(LONG_LE, currentOffset);

            // The 4th binary digit of the ascii (Starting from left) of a digit is 1 while '.' is 0
            int decimalSepPos = Long.numberOfTrailingZeros(~numberWord & 0x10101000);
            // 28 = 4 + 8 * 3 (4 bytes is the number of tail zeros in the byte of decimalPos)
            // xxxn.nn- shift: 28 - 28 = 0
            // xxxxxn.n shift: 28 - 12 = 16
            // xxxxn.nn shift: 28 - 20 = 8
            int shift = 28 - decimalSepPos;

            // Negative in ASCII: 00101101 2D. In ascii every digit starts with hex digit 3
            // So in order to know if a number is positive, we simpy need the first bit of the 2º half
            // If signed is 0 the number is positive. If it is negative signed will be -1.
            long signed = (~numberWord << 59) >> 63;

            // If signed is 0 (positive), designMask will be 0xFFFFFFFFFFFFFFFF (-256)
            // If signed is -1, all 1s (negative), designMask will be 0xFFFFFFFFFFFFFF00 (-1)
            long designMask = ~(signed & 0xFF);

            // Align the number to a fixed position
            // (x represents any non-related character, _ represents 0x00, n represents the actual digit and - negative)
            // xxxn.nn- -> xxxn.nn-
            // xxxxxn.n -> xxxn.n__
            // xxxxn.nn -> xxxn.nn_
            long numberAligned = (numberWord & designMask) << shift;

            // We convert ascii representation to number value
            long numberConvertedFromAscii = numberAligned & 0x0F000F0F00L;

            // Now digits is in the form 0xUU00TTHH00 (UU: units digit, TT: tens digit, HH: hundreds digit)
            // 0xUU00TTHH00 * (100 * 0x1000000 + 10 * 0x10000 + 1) =
            // 0x000000UU00TTHH00 +
            // 0x00UU00TTHH000000 * 10 +
            // 0xUU00TTHH00000000 * 100
            // Now TT * 100 has 2 trailing zeroes and HH * 100 + TT * 10 + UU < 0x400
            // This results in our value lies in the bit 32 to 41 of this product
            // That was close :)
            long absValue = ((numberConvertedFromAscii * 0x640a0001) >>> 32) & 0x3FF;

            long signedValue = (absValue ^ signed) - signed;

            this.currentOffset += (((decimalSepPos - 4) / 8) + 2);

            this.lastParsedTemperature = (int) signedValue;
        }

        // I first saw this idea in Artsiom Korzun's implementation
        private void parseName(MemorySegment segment) {
            long totalWordHash = 0;
            int totalWordLength = 0;

            for (;;) {
                long actualWord = segment.get(LONG_LE, currentOffset + totalWordLength);
                long hasSemicolon = hasByte(actualWord, SEMICOLON_PATTERN);

                if (hasSemicolon != 0) {
                    int actualLength = Long.numberOfTrailingZeros(hasSemicolon) >> 3;

                    actualWord = mask(actualWord, actualLength);

                    BYTES_AS_LONG.set(this.lastParsedNameBytes, totalWordLength, actualWord);

                    totalWordHash ^= actualWord;
                    totalWordLength += actualLength;

                    this.lastParsedNameLength = totalWordLength;
                    this.lastParsedNameHash = totalWordHash;
                    this.currentOffset += totalWordLength + 1; // +1 Because we don't want to point to ';'

                    break;
                }
                else {
                    BYTES_AS_LONG.set(this.lastParsedNameBytes, totalWordLength, actualWord);

                    totalWordLength += 8;
                    totalWordHash ^= actualWord;
                }
            }
        }

        // Removes "garbage" of a word: keeps the first `length` bytes (0..7) and zeroes the rest.
        // Works for length == 0 as well (shift of 0 -> mask of 0), so no special case is needed.
        // NOTE: this must zero-fill. The previous (word << s) >> s sign-extended, which put 0xFF
        // padding after names whose last byte was >= 0x80 (UTF-8 multibyte characters).
        private static long mask(long word, int length) {
            return word & ~(-1L << (length << 3));
        }

        private static long hasByte(long word, long pattern) {
            long patternMatch = word ^ pattern;
            return (patternMatch - 0x0101010101010101L) & (~patternMatch & 0x8080808080808080L);
        }

        private void adjustStartOffset() {
            if (currentOffset == 0) {
                return;
            }

            // Bounds are checked first: MemorySegment (unlike Unsafe) throws on out-of-range reads
            while (currentOffset < endOffset && mmappedFile.get(BYTE, currentOffset) != '\n') {
                currentOffset++;
            }

            currentOffset++; // We want it to point to the first character instead of \n
        }

        private void adjustEndOffset() {
            if (endOffset >= fileSize) {
                return;
            }

            while (endOffset < fileSize && mmappedFile.get(BYTE, endOffset) != '\n') {
                endOffset++;
            }
        }
    }

    static class SimpleMap {
        private final Result[] entries;
        private final long size;

        public SimpleMap(int size) {
            this.entries = new Result[size];
            this.size = size;
        }

        public void put(long hashToPut, byte[] nameToPut, int nameLength, int valueToPut) {
            int index = toIndex(hashToPut);

            for (;;) {
                Result actualEntry = entries[index];

                if (actualEntry == null) {
                    // Copy rounded up to a multiple of 8 so that Result.isSameName can always read whole words.
                    // nameToPut is zero-padded after nameLength (see Worker.parseName), so the padding is 0x00.
                    byte[] nameToPutCopy = Arrays.copyOf(nameToPut, (nameLength + 7) & ~7);

                    entries[index] = new Result(hashToPut, nameToPutCopy, nameLength, valueToPut,
                            valueToPut, valueToPut, 1);
                    return;
                }
                if (actualEntry.isSameName(nameToPut, nameLength)) {
                    actualEntry.min = Math.min(actualEntry.min, valueToPut);
                    actualEntry.max = Math.max(actualEntry.max, valueToPut);
                    actualEntry.count++;
                    actualEntry.sum = actualEntry.sum + valueToPut;
                    return;
                }

                index = toIndex(index + 31);
            }
        }

        private int toIndex(long hash) {
            return (int) (((hash >> 32) ^ ((int) hash)) & (this.size - 1));
        }
    }

    static class Result {
        public byte[] name; // length is nameLength rounded up to a multiple of 8, zero-padded
        public int nameLength;
        public int max;
        public int min;
        public int sum;
        public int count;
        public long hash;

        public Result(long hash, byte[] name, int nameLength, int max, int min, int sum, int occ) {
            this.nameLength = nameLength;
            this.count = occ;
            this.hash = hash;
            this.name = name;
            this.max = max;
            this.min = min;
            this.sum = sum;
        }

        public boolean isSameName(byte[] otherNameBytes, int otherNameLength) {
            return this.nameLength == otherNameLength && isSameNameBytes(otherNameBytes);
        }

        // Both arrays are zero-padded up to the next multiple of 8, so whole words can be compared directly
        private boolean isSameNameBytes(byte[] otherNameBytes) {
            for (int i = 0; i < this.nameLength; i += 8) {
                long thisNameBytesAsLong = (long) BYTES_AS_LONG.get(this.name, i);
                long otherNameBytesAsLong = (long) BYTES_AS_LONG.get(otherNameBytes, i);

                if (thisNameBytesAsLong != otherNameBytesAsLong) {
                    return false;
                }
            }

            return true;
        }
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static int roundUpToPowerOfTwo(int number) {
        if (number <= 0) {
            return 1;
        }

        number--;
        number |= number >> 1;
        number |= number >> 2;
        number |= number >> 4;
        number |= number >> 8;
        number |= number >> 16;

        return number + 1;
    }
}

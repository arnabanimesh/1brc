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
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The solution starts a child worker process for the actual work such that clean up of the memory mapping can occur
 * while the main process already returns with the result. The worker then memory maps the input file, creates a worker
 * thread per available core, and then processes segments of size {@link #SEGMENT_SIZE} at a time. The segments are
 * split into 3 parts and cursors for each of those parts are processing the segment simultaneously in the same thread.
 * Results are accumulated into {@link Result} objects and a tree map is used to sequentially accumulate the results in
 * the end.
 * Runs in 0.31 on an Intel i9-13900K while the reference implementation takes 120.37s.
 * Credit:
 *  Quan Anh Mai for branchless number parsing code
 *  Alfonso² Peterssen for suggesting memory mapping with unsafe and the subprocess idea
 *  Artsiom Korzun for showing the benefits of work stealing at 2MB segments instead of equal split between workers
 *  Jaromir Hamala for showing that avoiding the branch misprediction between <8 and 8-16 cases is a big win even if
 *  more work is performed
 *  Van Phu DO for demonstrating the lookup tables based on masks instead of bit shifting
 *
 * Port note: sun.misc.Unsafe has been replaced with the Foreign Function & Memory API (java.lang.foreign, final since
 * Java 22). All former absolute addresses are now offsets into the mapped {@link MemorySegment}. Because segment
 * accesses are bounds checked, the SWAR fast path only runs over the "body" of the file (everything up to the last
 * newline that is at least {@link #TAIL_SAFETY} bytes before the end); the remaining few lines are handled by a plain
 * scalar loop. No restricted FFM methods are used, so --enable-native-access is not required.
 */
public class CalculateAverage_thomaswue {
    private static final String FILE = "./measurements.txt";
    private static final int MIN_TEMP = -999;
    private static final int MAX_TEMP = 999;
    private static final int MAX_CITIES = 10000;
    private static final int SEGMENT_SIZE = 1 << 21;
    private static final int HASH_TABLE_SIZE = 1 << 17;

    // The fast path may read up to ~16 bytes past the end of a line. A line is at most ~107 bytes, so this margin keeps
    // every read of the fast path inside the file.
    private static final int TAIL_SAFETY = 128;

    // The parsing code assumes little endian word layout, so make that explicit.
    private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /**
     * Holder class so that the file is only mapped in the worker process (the parent never touches this class).
     */
    private static final class Input {
        static final MemorySegment SEGMENT = map();
        static final long SIZE = SEGMENT.byteSize();

        private static MemorySegment map() {
            try (FileChannel channel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ)) {
                return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), Arena.global());
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        // Start worker subprocess if this process is not the worker.
        if (args.length == 0 || !("--worker".equals(args[0]))) {
            spawnWorker();
            return;
        }

        int numberOfWorkers = Runtime.getRuntime().availableProcessors();
        final long fileSize = Input.SIZE;
        final long bodyEnd = findBodyEnd(fileSize);
        final AtomicLong cursor = new AtomicLong(0);

        // Parallel processing of segments.
        Thread[] threads = new Thread[numberOfWorkers];
        @SuppressWarnings("unchecked")
        List<Result>[] allResults = new List[numberOfWorkers];
        for (int i = 0; i < threads.length; ++i) {
            final int index = i;
            threads[i] = new Thread(() -> {
                List<Result> results = new ArrayList<>(MAX_CITIES);
                parseLoop(cursor, bodyEnd, results);
                allResults[index] = results;
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        // The last few lines of the file are processed with a simple, bounds-safe scalar loop.
        Map<String, Result> tailResults = processTail(bodyEnd, fileSize);

        // Final output.
        System.out.println(accumulateResults(allResults, tailResults));
        System.out.close();
    }

    private static void spawnWorker() throws IOException {
        ProcessHandle.Info info = ProcessHandle.current().info();
        ArrayList<String> workerCommand = new ArrayList<>();
        info.command().ifPresent(workerCommand::add);
        info.arguments().ifPresent(args -> workerCommand.addAll(Arrays.asList(args)));
        workerCommand.add("--worker");
        new ProcessBuilder().command(workerCommand).inheritIO().redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start().getInputStream().transferTo(System.out);
    }

    private static TreeMap<String, Result> accumulateResults(List<Result>[] allResults, Map<String, Result> tailResults) {
        TreeMap<String, Result> result = new TreeMap<>();
        for (List<Result> resultArr : allResults) {
            for (Result r : resultArr) {
                Result current = result.putIfAbsent(r.calcName(), r);
                if (current != null) {
                    current.accumulate(r);
                }
            }
        }
        for (Map.Entry<String, Result> e : tailResults.entrySet()) {
            Result current = result.putIfAbsent(e.getKey(), e.getValue());
            if (current != null) {
                current.accumulate(e.getValue());
            }
        }
        return result;
    }

    /**
     * Returns the offset just after the last newline that lies at least TAIL_SAFETY bytes before the end of the file,
     * or 0 if there is no such newline (small files are then handled entirely by the scalar tail loop).
     */
    private static long findBodyEnd(long fileSize) {
        for (long i = fileSize - TAIL_SAFETY; i >= 0; i--) {
            if (byteAt(i) == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * Plain byte-by-byte parser for the lines in [start, end). Slow, but only ever sees a handful of lines.
     */
    private static Map<String, Result> processTail(long start, long end) {
        Map<String, Result> tail = new HashMap<>();
        long pos = start;
        while (pos < end) {
            long nameStart = pos;
            while (byteAt(pos) != ';') {
                pos++;
            }
            String name = new String(Input.SEGMENT.asSlice(nameStart, pos - nameStart).toArray(ValueLayout.JAVA_BYTE),
                    StandardCharsets.UTF_8);
            pos++; // skip ';'
            boolean negative = false;
            if (byteAt(pos) == '-') {
                negative = true;
                pos++;
            }
            int value = 0;
            while (pos < end && byteAt(pos) != '\n') {
                byte b = byteAt(pos++);
                if (b != '.') {
                    value = value * 10 + (b - '0');
                }
            }
            pos++; // skip '\n'
            record(tail.computeIfAbsent(name, k -> new Result()), negative ? -value : value);
        }
        return tail;
    }

    private static void parseLoop(AtomicLong counter, long bodyEnd, List<Result> collectedResults) {
        Result[] results = new Result[HASH_TABLE_SIZE];
        while (true) {
            long current = counter.addAndGet(SEGMENT_SIZE) - SEGMENT_SIZE;
            if (current >= bodyEnd) {
                return;
            }

            long segmentEnd = nextNewLine(Math.min(bodyEnd - 1, current + SEGMENT_SIZE));
            long segmentStart;
            if (current == 0) {
                segmentStart = current;
            }
            else {
                segmentStart = nextNewLine(current) + 1;
            }
            if (segmentStart > segmentEnd) {
                continue;
            }

            long dist = (segmentEnd - segmentStart) / 3;
            long midPoint1 = nextNewLine(segmentStart + dist);
            long midPoint2 = nextNewLine(segmentStart + dist + dist);

            Scanner scanner1 = new Scanner(segmentStart, midPoint1);
            Scanner scanner2 = new Scanner(midPoint1 + 1, midPoint2);
            Scanner scanner3 = new Scanner(midPoint2 + 1, segmentEnd);
            while (true) {
                if (!scanner1.hasNext()) {
                    break;
                }
                if (!scanner2.hasNext()) {
                    break;
                }
                if (!scanner3.hasNext()) {
                    break;
                }
                long word1 = scanner1.getLong();
                long word2 = scanner2.getLong();
                long word3 = scanner3.getLong();
                long delimiterMask1 = findDelimiter(word1);
                long delimiterMask2 = findDelimiter(word2);
                long delimiterMask3 = findDelimiter(word3);
                long word1b = scanner1.getLongAt(scanner1.pos() + 8);
                long word2b = scanner2.getLongAt(scanner2.pos() + 8);
                long word3b = scanner3.getLongAt(scanner3.pos() + 8);
                long delimiterMask1b = findDelimiter(word1b);
                long delimiterMask2b = findDelimiter(word2b);
                long delimiterMask3b = findDelimiter(word3b);
                Result existingResult1 = findResult(word1, delimiterMask1, word1b, delimiterMask1b, scanner1, results, collectedResults);
                Result existingResult2 = findResult(word2, delimiterMask2, word2b, delimiterMask2b, scanner2, results, collectedResults);
                Result existingResult3 = findResult(word3, delimiterMask3, word3b, delimiterMask3b, scanner3, results, collectedResults);
                long number1 = scanNumber(scanner1);
                long number2 = scanNumber(scanner2);
                long number3 = scanNumber(scanner3);
                record(existingResult1, number1);
                record(existingResult2, number2);
                record(existingResult3, number3);
            }

            while (scanner1.hasNext()) {
                long word = scanner1.getLong();
                long pos = findDelimiter(word);
                long wordB = scanner1.getLongAt(scanner1.pos() + 8);
                long posB = findDelimiter(wordB);
                record(findResult(word, pos, wordB, posB, scanner1, results, collectedResults), scanNumber(scanner1));
            }
            while (scanner2.hasNext()) {
                long word = scanner2.getLong();
                long pos = findDelimiter(word);
                long wordB = scanner2.getLongAt(scanner2.pos() + 8);
                long posB = findDelimiter(wordB);
                record(findResult(word, pos, wordB, posB, scanner2, results, collectedResults), scanNumber(scanner2));
            }
            while (scanner3.hasNext()) {
                long word = scanner3.getLong();
                long pos = findDelimiter(word);
                long wordB = scanner3.getLongAt(scanner3.pos() + 8);
                long posB = findDelimiter(wordB);
                record(findResult(word, pos, wordB, posB, scanner3, results, collectedResults), scanNumber(scanner3));
            }
        }
    }

    private static final long[] MASK1 = new long[]{ 0xFFL, 0xFFFFL, 0xFFFFFFL, 0xFFFFFFFFL, 0xFFFFFFFFFFL, 0xFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL,
            0xFFFFFFFFFFFFFFFFL };
    private static final long[] MASK2 = new long[]{ 0x00L, 0x00L, 0x00L, 0x00L, 0x00L, 0x00L, 0x00L, 0x00L, 0xFFFFFFFFFFFFFFFFL };

    private static Result findResult(long initialWord, long initialDelimiterMask, long wordB, long delimiterMaskB, Scanner scanner, Result[] results,
                                     List<Result> collectedResults) {
        Result existingResult;
        long word = initialWord;
        long delimiterMask = initialDelimiterMask;
        long hash;
        long nameOffset = scanner.pos();
        long word2 = wordB;
        long delimiterMask2 = delimiterMaskB;
        if ((delimiterMask | delimiterMask2) != 0) {
            int letterCount1 = Long.numberOfTrailingZeros(delimiterMask) >>> 3; // value between 1 and 8
            int letterCount2 = Long.numberOfTrailingZeros(delimiterMask2) >>> 3; // value between 0 and 8
            long mask = MASK2[letterCount1];
            word = word & MASK1[letterCount1];
            word2 = mask & word2 & MASK1[letterCount2];
            hash = word ^ word2;
            existingResult = results[hashToIndex(hash, results)];
            scanner.add(letterCount1 + (letterCount2 & mask));
            if (existingResult != null && existingResult.firstNameWord == word && existingResult.secondNameWord == word2) {
                return existingResult;
            }
        }
        else {
            // Slow-path for when the ';' could not be found in the first 16 bytes.
            hash = word ^ word2;
            scanner.add(16);
            while (true) {
                word = scanner.getLong();
                delimiterMask = findDelimiter(word);
                if (delimiterMask != 0) {
                    int trailingZeros = Long.numberOfTrailingZeros(delimiterMask);
                    word = (word << (63 - trailingZeros));
                    scanner.add(trailingZeros >>> 3);
                    hash ^= word;
                    break;
                }
                else {
                    scanner.add(8);
                    hash ^= word;
                }
            }
        }

        // Save length of name for later.
        int nameLength = (int) (scanner.pos() - nameOffset);

        // Final calculation for index into hash table.
        int tableIndex = hashToIndex(hash, results);
        outer: while (true) {
            existingResult = results[tableIndex];
            if (existingResult == null) {
                existingResult = newEntry(results, nameOffset, tableIndex, nameLength, scanner, collectedResults);
            }
            // Check for collision.
            int i = 0;
            for (; i < nameLength + 1 - 8; i += 8) {
                if (scanner.getLongAt(existingResult.nameOffset + i) != scanner.getLongAt(nameOffset + i)) {
                    // Collision error, try next.
                    tableIndex = (tableIndex + 31) & (results.length - 1);
                    continue outer;
                }
            }

            int remainingShift = (64 - ((nameLength + 1 - i) << 3));
            if (((scanner.getLongAt(existingResult.nameOffset + i) ^ (scanner.getLongAt(nameOffset + i))) << remainingShift) == 0) {
                break;
            }
            else {
                // Collision error, try next.
                tableIndex = (tableIndex + 31) & (results.length - 1);
            }
        }
        return existingResult;
    }

    private static long nextNewLine(long prev) {
        while (true) {
            long currentWord = longAt(prev);
            long input = currentWord ^ 0x0A0A0A0A0A0A0A0AL;
            long pos = (input - 0x0101010101010101L) & ~input & 0x8080808080808080L;
            if (pos != 0) {
                prev += Long.numberOfTrailingZeros(pos) >>> 3;
                break;
            }
            else {
                prev += 8;
            }
        }
        return prev;
    }

    private static long scanNumber(Scanner scanPtr) {
        long numberWord = scanPtr.getLongAt(scanPtr.pos() + 1);
        int decimalSepPos = Long.numberOfTrailingZeros(~numberWord & 0x10101000L);
        long number = convertIntoNumber(decimalSepPos, numberWord);
        scanPtr.add((decimalSepPos >>> 3) + 4);
        return number;
    }

    private static void record(Result existingResult, long number) {
        if (number < existingResult.min) {
            existingResult.min = (short) number;
        }
        if (number > existingResult.max) {
            existingResult.max = (short) number;
        }
        existingResult.sum += number;
        existingResult.count++;
    }

    private static int hashToIndex(long hash, Result[] results) {
        long hashAsInt = hash ^ (hash >>> 33) ^ (hash >>> 15);
        return (int) (hashAsInt & (results.length - 1));
    }

    // Special method to convert a number in the ascii number into an int without branches created by Quan Anh Mai.
    private static long convertIntoNumber(int decimalSepPos, long numberWord) {
        int shift = 28 - decimalSepPos;
        // signed is -1 if negative, 0 otherwise
        long signed = (~numberWord << 59) >> 63;
        long designMask = ~(signed & 0xFF);
        // Align the number to a specific position and transform the ascii to digit value
        long digits = ((numberWord & designMask) << shift) & 0x0F000F0F00L;
        // Now digits is in the form 0xUU00TTHH00 (UU: units digit, TT: tens digit, HH: hundreds digit)
        // 0xUU00TTHH00 * (100 * 0x1000000 + 10 * 0x10000 + 1) =
        // 0x000000UU00TTHH00 + 0x00UU00TTHH000000 * 10 + 0xUU00TTHH00000000 * 100
        long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;
        return (absValue ^ signed) - signed;
    }

    private static long findDelimiter(long word) {
        long input = word ^ 0x3B3B3B3B3B3B3B3BL;
        return (input - 0x0101010101010101L) & ~input & 0x8080808080808080L;
    }

    private static Result newEntry(Result[] results, long nameOffset, int hash, int nameLength, Scanner scanner, List<Result> collectedResults) {
        Result r = new Result();
        results[hash] = r;
        int totalLength = nameLength + 1;
        r.firstNameWord = scanner.getLongAt(nameOffset);
        r.secondNameWord = scanner.getLongAt(nameOffset + 8);
        if (totalLength <= 8) {
            r.firstNameWord = r.firstNameWord & MASK1[totalLength - 1];
            r.secondNameWord = 0;
        }
        else if (totalLength < 16) {
            r.secondNameWord = r.secondNameWord & MASK1[totalLength - 9];
        }
        r.nameOffset = nameOffset;
        collectedResults.add(r);
        return r;
    }

    private static long longAt(long offset) {
        return Input.SEGMENT.get(LONG_LE, offset);
    }

    private static byte byteAt(long offset) {
        return Input.SEGMENT.get(ValueLayout.JAVA_BYTE, offset);
    }

    private static final class Result {
        long firstNameWord, secondNameWord;
        short min, max;
        int count;
        long sum;
        long nameOffset;

        private Result() {
            this.min = MAX_TEMP;
            this.max = MIN_TEMP;
        }

        public String toString() {
            return round(((double) min) / 10.0) + "/" + round((((double) sum) / 10.0) / count) + "/" + round(((double) max) / 10.0);
        }

        private static double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }

        private void accumulate(Result other) {
            if (other.min < min) {
                min = other.min;
            }
            if (other.max > max) {
                max = other.max;
            }
            sum += other.sum;
            count += other.count;
        }

        public String calcName() {
            int nameLength = 0;
            while (byteAt(nameOffset + nameLength) != ';') {
                nameLength++;
            }
            byte[] array = Input.SEGMENT.asSlice(nameOffset, nameLength).toArray(ValueLayout.JAVA_BYTE);
            return new String(array, StandardCharsets.UTF_8);
        }
    }

    private static final class Scanner {
        private long pos;
        private final long end;

        public Scanner(long start, long end) {
            this.pos = start;
            this.end = end;
        }

        boolean hasNext() {
            return pos < end;
        }

        long pos() {
            return pos;
        }

        void add(long delta) {
            pos += delta;
        }

        long getLong() {
            return longAt(pos);
        }

        long getLongAt(long pos) {
            return longAt(pos);
        }
    }
}

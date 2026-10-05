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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;

public class CalculateAverage_yavuztas {

    private static final Path FILE = Path.of("./measurements.txt");

    // Port note: the original used sun.misc.Unsafe for raw memory access. Those memory-access methods are
    // deprecated for removal (JEP 471/498), so all loads now go through the Foreign Function & Memory API
    // (java.lang.foreign, final since Java 22). Nothing here is a restricted method, so no
    // --enable-native-access or --sun-misc-unsafe-memory-access flags are needed.
    //
    // The algorithm assumes little-endian word layout (trailing-zero tricks); we state it explicitly so the
    // result is also correct on big-endian hosts. On little-endian hosts this is a no-op.
    private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    // Word loads may read a few bytes past the end of a line (at most 7). MemorySegment is bounds-checked
    // (Unsafe was not), so the last TAIL_MARGIN bytes of the file are processed from a zero-padded copy.
    private static final int TAIL_MARGIN = 16;

    private static long getWord(MemorySegment segment, long offset) {
        return segment.get(LONG_LE, offset);
    }

    /**
     * Extract bytes from a long
     */
    private static long partial(long word, int length) {
        final long mask = (~0L) << (length << 3);
        return word & (~mask);
    }

    // Only one object, both for measurements and keys, less object creation in hotpots is always faster
    private static final class Record {

        private final MemorySegment segment; // segment holding the key bytes
        private final long start; // offset of the key inside the segment
        private final int length;
        private final long word1;
        private final long word2;
        private final long wordLast;
        private final int hash;
        private Record next; // linked list to resolve hash collisions

        private int min; // calculations over int is faster than double, we convert to double in the end only once
        private int max;
        private long sum;
        private int count;

        public Record(MemorySegment segment, long start, int length, long word1, long word2, long wordLast, int hash, int temp) {
            this.segment = segment;
            this.start = start;
            this.length = length;
            this.word1 = word1;
            this.word2 = word2;
            this.wordLast = wordLast;
            this.hash = hash;
            this.min = temp;
            this.max = temp;
            this.sum = temp;
            this.count = 1;
        }

        @Override
        public boolean equals(Object o) {
            final Record record = (Record) o;
            return equals(record.segment, record.start, record.word1, record.word2, record.wordLast, record.length);
        }

        // word1/word2 (bytes 0-15) and the masked last word are compared by the caller;
        // this compares every full 8-byte word in between, starting at byte 16.
        private static boolean middleEquals(MemorySegment segment1, long start1, MemorySegment segment2, long start2, int length) {
            final int lastWordOffset = length & ~7;
            for (int offset = 16; offset < lastWordOffset; offset += 8) {
                if (getWord(segment1, start1 + offset) != getWord(segment2, start2 + offset)) {
                    return false;
                }
            }
            return true;
        }

        private boolean equals(MemorySegment segment, long start, long word1, long word2, long last, int length) {
            if (this.word1 != word1)
                return false;
            if (this.word2 != word2)
                return false;
            if (this.wordLast != last)
                return false;

            // equals check is done by comparing longs instead of byte by byte check, this is faster
            return middleEquals(this.segment, this.start, segment, start, length);
        }

        @Override
        public String toString() {
            final byte[] bytes = this.segment.asSlice(this.start, this.length).toArray(ValueLayout.JAVA_BYTE);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private void collect(int temp) {
            if (temp < this.min)
                this.min = temp;
            if (temp > this.max)
                this.max = temp;
            this.sum += temp;
            this.count++;
        }

        private void merge(Record that) {
            if (that.min < this.min)
                this.min = that.min;
            if (that.max > this.max)
                this.max = that.max;
            this.sum += that.sum;
            this.count += that.count;
        }

        private String measurements() {
            // here is only executed once for each unique key, so StringBuilder creation doesn't harm
            final StringBuilder sb = new StringBuilder(14);
            sb.append(round(this.min)).append("/");
            sb.append(round(1.0 * this.sum / this.count)).append("/");
            sb.append(round(this.max));
            return sb.toString();
        }
    }

    // Inspired by @spullara - customized hashmap on purpose
    // The main difference is we hold only one array instead of two, fewer objects is faster
    private static final class RecordMap {

        // Bigger bucket size less collisions, but you have to find a sweet spot otherwise it is becoming slower.
        // Also works good enough for 10K stations
        private static final int SIZE = 1 << 14; // 16kb - enough for 10K
        private static final int BITMASK = SIZE - 1;
        private final Record[] keys = new Record[SIZE];

        // int collision;

        private boolean hasNoRecord(int index) {
            return this.keys[index] == null;
        }

        private Record getRecord(int index) {
            return this.keys[index];
        }

        private static int hashBucket(int hash) {
            hash = hash ^ (hash >>> 16); // naive bit spreading but surprisingly decreases collision :)
            return hash & BITMASK; // fast modulo, to find bucket
        }

        private void putAndCollect(MemorySegment segment, int hash, int temp, long start, int length, long word1, long word2, long wordLast) {
            final int bucket = hashBucket(hash);
            if (hasNoRecord(bucket)) {
                this.keys[bucket] = new Record(segment, start, length, word1, word2, wordLast, hash, temp);
                return;
            }

            Record existing = getRecord(bucket);
            if (existing.equals(segment, start, word1, word2, wordLast, length)) {
                existing.collect(temp);
                return;
            }

            // collision++;
            // find possible slot by scanning the slot linked list
            while (existing.next != null) {
                if (existing.next.equals(segment, start, word1, word2, wordLast, length)) {
                    existing.next.collect(temp);
                    return;
                }
                existing = existing.next; // go on to next
                // collision++;
            }
            existing.next = new Record(segment, start, length, word1, word2, wordLast, hash, temp);
        }

        private void putOrMerge(Record key) {
            final int bucket = hashBucket(key.hash);
            if (hasNoRecord(bucket)) {
                key.next = null;
                this.keys[bucket] = key;
                return;
            }

            Record existing = getRecord(bucket);
            if (existing.equals(key)) {
                existing.merge(key);
                return;
            }

            // collision++;
            // find possible slot by scanning the slot linked list
            while (existing.next != null) {
                if (existing.next.equals(key)) {
                    existing.next.merge(key);
                    return;
                }
                existing = existing.next; // go on to next
                // collision++;
            }
            key.next = null;
            existing.next = key;
        }

        private void forEach(Consumer<Record> consumer) {
            int pos = 0;
            Record key;
            while (pos < SIZE) {
                if ((key = this.keys[pos++]) == null) {
                    continue;
                }
                Record next = key.next;
                consumer.accept(key);
                while (next != null) { // also traverse the records in the collision list
                    final Record tmp = next.next;
                    consumer.accept(next);
                    next = tmp;
                }
            }
        }

        private void merge(RecordMap other) {
            other.forEach(this::putOrMerge);
        }

    }

    // One actor for one thread, no synchronization
    private static final class RegionActor extends Thread {

        private final MemorySegment segment;
        private final long startPos; // start of the region, offset inside segment
        private final long endPos; // end of the region (exclusive), always right after a line break

        private final RecordMap map = new RecordMap();

        public RegionActor(MemorySegment segment, long startPos, long endPos) {
            this.segment = segment;
            this.startPos = startPos;
            this.endPos = endPos;
        }

        // hasvalue & haszero
        // adapted from https://graphics.stanford.edu/~seander/bithacks.html#ZeroInWord
        private static long hasSemicolon(long word) {
            // semicolon pattern
            final long hasVal = word ^ 0x3B3B3B3B3B3B3B3BL; // hasvalue
            return ((hasVal - 0x0101010101010101L) & ~hasVal & 0x8080808080808080L); // haszero
        }

        private static int semicolonPos(long hasVal) {
            return Long.numberOfTrailingZeros(hasVal) >>> 3;
        }

        private static int decimalPos(long numberWord) {
            return Long.numberOfTrailingZeros(~numberWord & 0x10101000);
        }

        private static final int MAX_INNER_LOOP_SIZE = 11;

        @Override
        public void run() {
            final MemorySegment segment = this.segment; // local copy helps the JIT hoist checks
            long pointer = this.startPos;
            final long end = this.endPos;
            while (pointer < end) { // line start
                long hash = 0; // reset hash
                long s; // semicolon check word
                final int pos; // semicolon position
                long word1 = getWord(segment, pointer);
                if ((s = hasSemicolon(word1)) != 0) {
                    pos = semicolonPos(s);
                    // read temparature
                    final long numberWord = getWord(segment, pointer + pos + 1);
                    final int decimalPos = decimalPos(numberWord);
                    final int temp = convertIntoNumber(decimalPos, numberWord);

                    word1 = partial(word1, pos); // last word
                    this.map.putAndCollect(segment, completeHash(hash, word1), temp, pointer, pos, word1, 0, 0);

                    pointer += pos + (decimalPos >>> 3) + 4;
                }
                else {
                    long word2 = getWord(segment, pointer + 8);
                    if ((s = hasSemicolon(word2)) != 0) {
                        pos = semicolonPos(s);
                        // read temparature
                        final int length = pos + 8;
                        final long numberWord = getWord(segment, pointer + length + 1);
                        final int decimalPos = decimalPos(numberWord);
                        final int temp = convertIntoNumber(decimalPos, numberWord);

                        word2 = partial(word2, pos); // last word
                        this.map.putAndCollect(segment, completeHash(hash, word1, word2), temp, pointer, length, word1, word2, 0);

                        pointer += length + (decimalPos >>> 3) + 4; // seek to the line end
                    }
                    else {
                        long word = 0;
                        int length = 16;
                        hash = appendHash(hash, word1, word2);
                        // Let the compiler know the loop size ahead
                        // Then it's automatically unrolled
                        // Max key length is 13 longs, 2 we've read before, 11 left
                        for (int i = 0; i < MAX_INNER_LOOP_SIZE; i++) {
                            if ((s = hasSemicolon((word = getWord(segment, pointer + length)))) != 0) {
                                break;
                            }
                            hash = appendHash(hash, word);
                            length += 8;
                        }

                        pos = semicolonPos(s);
                        length += pos;
                        // read temparature
                        final long numberWord = getWord(segment, pointer + length + 1);
                        final int decimalPos = decimalPos(numberWord);
                        final int temp = convertIntoNumber(decimalPos, numberWord);

                        word = partial(word, pos); // last word
                        this.map.putAndCollect(segment, completeHash(hash, word), temp, pointer, length, word1, word2, word);

                        pointer += length + (decimalPos >>> 3) + 4; // seek to the line end
                    }
                }
            }
        }

        // Hashes are calculated by a Mersenne Prime (1 << 7) -1
        // This is faster than multiplication in some machines
        private static long appendHash(long hash, long word) {
            return (hash << 7) - hash + word;
        }

        private static long appendHash(long hash, long word1, long word2) {
            hash = (hash << 7) - hash + word1;
            return (hash << 7) - hash + word2;
        }

        private static int completeHash(long hash, long partial) {
            hash = (hash << 7) - hash + partial;
            return (int) (hash ^ (hash >>> 25));
        }

        private static int completeHash(long hash, long word1, long word2) {
            hash = (hash << 7) - hash + word1;
            hash = (hash << 7) - hash + word2;
            return (int) hash ^ (int) (hash >>> 25);
        }

        // Credits to @merrykitty. Magical solution to parse temparature values branchless!
        // Taken as without modification, comments belong to @merrykitty
        private static int convertIntoNumber(int decimalSepPos, long numberWord) {
            final int shift = 28 - decimalSepPos;
            // signed is -1 if negative, 0 otherwise
            final long signed = (~numberWord << 59) >> 63;
            final long designMask = ~(signed & 0xFF);
            // Align the number to a specific position and transform the ascii code
            // to actual digit value in each byte
            final long digits = ((numberWord & designMask) << shift) & 0x0F000F0F00L;
            // Now digits is in the form 0xUU00TTHH00 (UU: units digit, TT: tens digit, HH: hundreds digit)
            // 0xUU00TTHH00 * (100 * 0x1000000 + 10 * 0x10000 + 1) =
            // 0x000000UU00TTHH00 +
            // 0x00UU00TTHH000000 * 10 +
            // 0xUU00TTHH00000000 * 100
            // Now TT * 100 has 2 trailing zeroes and HH * 100 + TT * 10 + UU < 0x400
            // This results in our value lies in the bit 32 to 41 of this product
            // That was close :)
            final long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;
            final long value = (absValue ^ signed) - signed;
            return (int) value;
        }

        /**
         * blocks until the map is fully collected
         */
        private RecordMap get() throws InterruptedException {
            join();
            return this.map;
        }
    }

    private static double round(double value) {
        return Math.round(value) / 10.0;
    }

    /**
     * Scans backwards from nominalEnd to the closest position that is right after a line break
     */
    private static long findClosestLineEnd(MemorySegment file, long from, long nominalEnd) {
        long end = nominalEnd;
        while (end > from && file.get(ValueLayout.JAVA_BYTE, end - 1) != '\n') {
            end--;
        }
        return end;
    }

    private static boolean isWorkerProcess(String[] args) {
        return Arrays.asList(args).contains("--worker");
    }

    private static void runAsWorker() throws Exception {
        final ProcessHandle.Info info = ProcessHandle.current().info();
        final List<String> commands = new ArrayList<>();
        info.command().ifPresent(commands::add);
        info.arguments().ifPresent(args -> commands.addAll(Arrays.asList(args)));
        commands.add("--worker");

        new ProcessBuilder()
                .command(commands)
                .redirectError(ProcessBuilder.Redirect.INHERIT) // don't let an undrained stderr pipe block the worker
                .start()
                .getInputStream()
                .transferTo(System.out);
    }

    public static void main(String[] args) throws Exception {

        // Dased on @thomaswue's idea, to cut unmapping delay.
        // Strangely, unmapping delay doesn't occur on macOS/M1 however in Linux/AMD it's substantial - ~200ms
        if (!isWorkerProcess(args)) {
            runAsWorker();
            return;
        }

        final long fileSize = Files.size(FILE);
        final MemorySegment file;
        try (FileChannel channel = FileChannel.open(FILE, StandardOpenOption.READ)) {
            // the mapping lives in the global arena, so it stays valid after the channel is closed
            file = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global());
        }

        // Split the file in two parts: a bulk part that is processed straight from the mapping, and a
        // short tail (a few lines) that is copied into a zero-padded buffer, because word loads on the
        // last lines would otherwise run past the end of the segment.
        long tailStart = Math.max(0, fileSize - TAIL_MARGIN);
        while (tailStart > 0 && file.get(ValueLayout.JAVA_BYTE, tailStart - 1) != '\n') {
            tailStart--; // move back to a line start
        }
        final long mainSize = tailStart;

        int concurrency = 2 * Runtime.getRuntime().availableProcessors();
        if (mainSize <= 1 << 20) { // small input (1mb), no need concurrency
            concurrency = 1;
        }
        final long regionSize = mainSize / concurrency;

        final List<RegionActor> actors = new ArrayList<>(concurrency + 1);
        long startPos = 0;
        for (int i = 0; i < concurrency && startPos < mainSize; i++) {
            // the last region always runs up to the end of the bulk part, so no lines are left behind
            final long endPos = (i == concurrency - 1)
                    ? mainSize
                    : findClosestLineEnd(file, startPos, startPos + regionSize);

            final RegionActor region = new RegionActor(file, startPos, endPos);
            actors.add(region);
            region.start(); // start processing

            startPos = endPos;
        }

        if (tailStart < fileSize) {
            final long tailLength = fileSize - tailStart;
            final MemorySegment tail = Arena.global().allocate(tailLength + TAIL_MARGIN); // zero-filled padding
            MemorySegment.copy(file, tailStart, tail, 0, tailLength);
            final RegionActor tailRegion = new RegionActor(tail, 0, tailLength);
            actors.add(tailRegion);
            tailRegion.start();
        }

        final RecordMap output = new RecordMap(); // output to merge all records
        for (RegionActor actor : actors) {
            final RecordMap partial = actor.get(); // blocks until get the result
            output.merge(partial);
            // System.out.println("collisions: " + partial.collision);
        }

        // sort and print the result
        final TreeMap<String, String> sorted = new TreeMap<>();
        output.forEach(key -> {
            sorted.put(key.toString(), key.measurements());
        });
        System.out.println(sorted);
        System.out.close(); // closing the stream will trigger the main process to pick up the output early
    }

}

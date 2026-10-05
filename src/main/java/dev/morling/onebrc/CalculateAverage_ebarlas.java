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
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.TreeMap;

/**
 * Port of the original sun.misc.Unsafe implementation to the Foreign Function & Memory API
 * (java.lang.foreign, final since JDK 22).
 * <p>
 * The memory-access methods of sun.misc.Unsafe throw UnsupportedOperationException by default
 * from JDK 26 onwards, so the original does not run on JDK 27. This version uses only
 * non-restricted FFM methods, so no --enable-native-access flag is needed.
 * <p>
 * Differences from the original:
 * - raw addresses (long) are replaced by MemorySegments plus byte offsets
 * - all multi-byte reads/writes use an explicit little-endian layout, since the parsing
 * logic assumes the first byte of an int is its low-order byte
 * - accesses are bounds-checked
 */
public class CalculateAverage_ebarlas {

    private static final Arena ARENA = Arena.global();

    private static final int MAX_KEY_SIZE = 104; // 4 additional bytes to allow for single-int overflow due to padding
    private static final int MAX_VAL_SIZE = 5; // -dd.d
    private static final int MAX_LINE_SIZE = MAX_KEY_SIZE + MAX_VAL_SIZE + 2; // key, semicolon, val, newline
    private static final int HASH_TBL_SIZE = 131_071; // range of allowed hash values, inclusive

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfInt INT_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public static void main(String[] args) throws IOException, InterruptedException {
        var path = Paths.get("measurements.txt");
        var channel = FileChannel.open(path, StandardOpenOption.READ);
        var numPartitions = Runtime.getRuntime().availableProcessors();
        var partitionSize = channel.size() / numPartitions;
        var partitions = new Partition[numPartitions];
        var threads = new Thread[numPartitions];
        for (int i = 0; i < numPartitions; i++) {
            var pIdx = i;
            var pStart = pIdx * partitionSize;
            var pEnd = pIdx == numPartitions - 1
                    ? channel.size() // last partition might be slightly larger
                    : pStart + partitionSize;
            var pSize = pEnd - pStart;
            Runnable r = () -> {
                try {
                    var ms = channel.map(FileChannel.MapMode.READ_ONLY, pStart, pSize, ARENA);
                    partitions[pIdx] = processSegment(ms, pIdx == 0, pIdx == numPartitions - 1);
                }
                catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
            threads[i] = new Thread(r);
            threads[i].start();
        }
        for (var thread : threads) {
            thread.join();
        }
        var partitionList = List.of(partitions);
        foldFootersAndHeaders(partitionList);
        printResults(foldStats(partitionList));
    }

    private static void printResults(Stats[] stats) { // adheres to Gunnar's reference code
        var result = new TreeMap<String, String>();
        for (var st : stats) {
            if (st != null) {
                var key = new String(convert(st.key, st.keyLen, st.lastBytes), StandardCharsets.UTF_8);
                result.put(key, format(st));
            }
        }
        System.out.println(result);
    }

    private static byte[] convert(MemorySegment key, int keyLen, int keyLastBytes) {
        var len = keyLastBytes == 4
                ? keyLen * 4 // fully packed
                : (keyLen - 1) * 4 + keyLastBytes; // last int partially packed
        var bytes = new byte[len];
        var idx = 0;
        for (long i = 0; i < keyLen; i++) {
            var offset = i << 2;
            var n = key.get(INT_LE, offset);
            var bound = i == keyLen - 1 ? keyLastBytes : 4;
            for (int j = 0; j < bound; j++) {
                bytes[idx++] = (byte) (n & 0xFF);
                n >>>= 8;
            }
        }
        return bytes;
    }

    private static String format(Stats st) { // adheres to expected output format
        return round(st.min / 10.0) + "/" + round((st.sum / 10.0) / st.count) + "/" + round(st.max / 10.0);
    }

    private static double round(double value) { // Gunnar's round function
        return Math.round(value * 10.0) / 10.0;
    }

    private static Stats[] foldStats(List<Partition> partitions) { // fold stats from all partitions into first partition
        var target = partitions.getFirst().stats;
        for (int i = 1; i < partitions.size(); i++) {
            var current = partitions.get(i).stats;
            for (int j = 0; j < current.length; j++) {
                if (current[j] != null) {
                    var t = findInTable(target, current[j].hash, current[j].key, current[j].keyLen, current[j].lastBytes);
                    t.min = Math.min(t.min, current[j].min);
                    t.max = Math.max(t.max, current[j].max);
                    t.sum += current[j].sum;
                    t.count += current[j].count;
                }
            }
        }
        return target;
    }

    private static void foldFootersAndHeaders(List<Partition> partitions) { // fold footers and headers into prev partition
        for (int i = 1; i < partitions.size(); i++) {
            var pNext = partitions.get(i);
            var pPrev = partitions.get(i - 1);
            var merged = mergeFooterAndHeader(pPrev.footer, pNext.header);
            if (merged != null && merged.length != 0) {
                if (merged[merged.length - 1] == '\n') { // fold into prev partition
                    doProcessSegment(ARENA.allocateFrom(ValueLayout.JAVA_BYTE, merged), 0, pPrev.stats, true);
                }
                else { // no newline appeared in partition, carry forward
                    pNext.footer = merged;
                }
            }
        }
    }

    private static byte[] mergeFooterAndHeader(byte[] footer, byte[] header) {
        if (footer == null) {
            return header;
        }
        if (header == null) {
            return footer;
        }
        var merged = new byte[footer.length + header.length];
        System.arraycopy(footer, 0, merged, 0, footer.length);
        System.arraycopy(header, 0, merged, footer.length, header.length);
        return merged;
    }

    private static Partition processSegment(MemorySegment ms, boolean first, boolean last) {
        var stats = new Stats[HASH_TBL_SIZE + 1]; // vals range from [0, size] inclusive
        var header = first ? null : readHeader(ms);
        var keyStart = doProcessSegment(ms, header == null ? 0 : header.offset, stats, last); // last segment is complete
        var footer = keyStart < ms.byteSize() ? readFooter(ms, keyStart) : null;
        return new Partition(header == null ? null : header.data, footer, stats);
    }

    private static long doProcessSegment(MemorySegment ms, long offset, Stats[] stats, boolean complete) {
        long cursor = offset; // byte offset into segment
        MemorySegment keyBuf = ARENA.allocate(MAX_KEY_SIZE, 8); // reusable target for current key data
        long lineStart = cursor; // start of key in segment used for footer calc
        long limit = complete ? ms.byteSize() : ms.byteSize() - MAX_LINE_SIZE; // stop short of longest line, sweep up at the end
        while (cursor < limit) { // one line per iteration
            int keyHash = 0; // key hash code
            long keyOff = 0; // offset in keyBuf for next int
            int keyArrLen = 0; // number of key 4-byte ints
            int keyLastBytes; // occupancy in last byte (1, 2, 3, or 4)
            int val;
            while (true) {
                int n = ms.get(INT_LE, cursor);
                cursor += 4;
                if ((n & 0xFF) == ';') { // ;vvv
                    keyBuf.set(INT_LE, keyOff, 0); // always pad with extra int to facilitate 8-byte aligned comparisons
                    keyLastBytes = 4;
                    byte b0 = (byte) ((n >> 8) & 0xFF);
                    byte b1 = (byte) ((n >> 16) & 0xFF);
                    byte b2 = (byte) ((n >> 24) & 0xFF);
                    if (b0 == '-') {
                        if (b2 != '.') { // 6 bytes: -dd.dn
                            cursor++; // decimal point
                            byte b4 = ms.get(BYTE, cursor);
                            cursor += 2; // adv beyond digit and newline
                            val = -(((b1 - '0') * 10 + (b2 - '0')) * 10 + (b4 - '0'));
                        }
                        else { // 5 bytes: -d.dn
                            byte b3 = ms.get(BYTE, cursor);
                            cursor += 2; // digit and newline
                            val = -((b1 - '0') * 10 + (b3 - '0'));
                        }
                    }
                    else {
                        if (b1 != '.') { // 5 bytes: dd.dn
                            var b3 = ms.get(BYTE, cursor);
                            cursor += 2; // digit and newline
                            val = ((b0 - '0') * 10 + (b1 - '0')) * 10 + (b3 - '0');
                        }
                        else { // 4 bytes: d.dn
                            cursor++; // newline
                            val = (b0 - '0') * 10 + (b2 - '0');
                        }
                    }
                    break;
                }
                else if ((n & 0xFF00) == 0x3b00) { // k;vv
                    int k = n & 0xFF;
                    keyBuf.set(LONG_LE, keyOff, k); // pad with extra int for comparison alignment
                    keyLastBytes = 1;
                    keyArrLen++;
                    keyHash += k;
                    byte b0 = (byte) ((n >> 16) & 0xFF);
                    byte b1 = (byte) ((n >> 24) & 0xFF);
                    byte b2 = ms.get(BYTE, cursor++);
                    if (b0 == '-') {
                        if (b2 != '.') { // 6 bytes: -dd.dn
                            cursor++; // decimal point
                            byte b4 = ms.get(BYTE, cursor);
                            cursor += 2; // adv beyond digit and newline
                            val = -(((b1 - '0') * 10 + (b2 - '0')) * 10 + (b4 - '0'));
                        }
                        else { // 5 bytes: -d.dn
                            byte b3 = ms.get(BYTE, cursor);
                            cursor += 2; // digit and newline
                            val = -((b1 - '0') * 10 + (b3 - '0'));
                        }
                    }
                    else {
                        if (b1 != '.') { // 5 bytes: dd.dn
                            byte b3 = ms.get(BYTE, cursor);
                            cursor += 2; // newline
                            val = ((b0 - '0') * 10 + (b1 - '0')) * 10 + (b3 - '0');
                        }
                        else { // 4 bytes: d.dn
                            cursor++;
                            val = (b0 - '0') * 10 + (b2 - '0');
                        }
                    }
                    break;
                }
                else if ((n & 0xFF0000) == 0x3b0000) { // kk;v
                    int k = n & 0xFFFF;
                    keyBuf.set(LONG_LE, keyOff, k); // pad with extra int for comparison alignment
                    keyLastBytes = 2;
                    keyArrLen++;
                    keyHash += k;
                    byte b0 = (byte) ((n >> 24) & 0xFF);
                    if (b0 == '-') {
                        n = ms.get(INT_LE, cursor);
                        cursor += 4;
                        byte b1 = (byte) (n & 0xFF);
                        byte b2 = (byte) ((n >> 8) & 0xFF);
                        byte b3 = (byte) ((n >> 16) & 0xFF);
                        if (b2 != '.') { // 6 bytes: -dd.dn
                            byte b4 = (byte) ((n >> 24) & 0xFF);
                            cursor++; // newline
                            val = -(((b1 - '0') * 10 + (b2 - '0')) * 10 + (b4 - '0'));
                        }
                        else { // 5 bytes: -d.dn
                            val = -((b1 - '0') * 10 + (b3 - '0'));
                        }
                    }
                    else {
                        byte b1 = ms.get(BYTE, cursor++);
                        byte b2 = ms.get(BYTE, cursor++);
                        byte b3 = ms.get(BYTE, cursor++);
                        if (b1 != '.') { // 5 bytes: dd.dn
                            cursor++; // newline
                            val = ((b0 - '0') * 10 + (b1 - '0')) * 10 + (b3 - '0');
                        }
                        else { // 4 bytes: d.dn
                            val = (b0 - '0') * 10 + (b2 - '0');
                        }
                    }
                    break;
                }
                else if ((n & 0xFF000000) == 0x3b000000) { // kkk;
                    int k = n & 0xFFFFFF;
                    keyBuf.set(LONG_LE, keyOff, k); // pad with extra int for comparison alignment
                    keyLastBytes = 3;
                    keyArrLen++;
                    keyHash += k;
                    n = ms.get(INT_LE, cursor);
                    cursor += 4;
                    byte b0 = (byte) (n & 0xFF);
                    byte b1 = (byte) ((n >> 8) & 0xFF);
                    byte b2 = (byte) ((n >> 16) & 0xFF);
                    byte b3 = (byte) ((n >> 24) & 0xFF);
                    if (b0 == '-') {
                        if (b2 != '.') { // 6 bytes: -dd.dn
                            byte b4 = ms.get(BYTE, cursor);
                            cursor += 2; // adv beyond digit and newline
                            val = -(((b1 - '0') * 10 + (b2 - '0')) * 10 + (b4 - '0'));
                        }
                        else { // 5 bytes: -d.dn
                            cursor++; // newline
                            val = -((b1 - '0') * 10 + (b3 - '0'));
                        }
                    }
                    else {
                        if (b1 != '.') { // 5 bytes: dd.dn
                            cursor++; // newline
                            val = ((b0 - '0') * 10 + (b1 - '0')) * 10 + (b3 - '0');
                        }
                        else { // 4 bytes: d.dn
                            val = (b0 - '0') * 10 + (b2 - '0');
                        }
                    }
                    break;
                }
                else { // kkkk
                    keyBuf.set(INT_LE, keyOff, n);
                    keyArrLen++;
                    keyOff += 4;
                    keyHash += n;
                }
            }
            keyHash ^= keyHash >>> 13;
            var idx = keyHash & HASH_TBL_SIZE;
            var st = stats[idx];
            if (st == null) { // nothing in table, eagerly claim spot
                st = stats[idx] = newStats(keyBuf, keyArrLen, keyLastBytes, keyHash);
            }
            else if (!equals(st.key, st.keyLen, keyBuf, keyArrLen)) {
                st = findInTable(stats, keyHash, keyBuf, keyArrLen, keyLastBytes);
            }
            st.min = Math.min(st.min, val);
            st.max = Math.max(st.max, val);
            st.sum += val;
            st.count++;
            lineStart = cursor; // preserve line start
        }
        return lineStart;
    }

    private static boolean equals(MemorySegment key1, int len1, MemorySegment key2, int len2) {
        if (len1 != len2) {
            return false;
        }
        if (len1 <= 2) {
            return key1.get(LONG_LE, 0) == key2.get(LONG_LE, 0);
        }
        if (len1 <= 4) {
            return key1.get(LONG_LE, 0) == key2.get(LONG_LE, 0) && key1.get(LONG_LE, 8) == key2.get(LONG_LE, 8);
        }
        for (int i = 0; i < len1; i += 2) {
            var offset = (long) i << 2;
            if (key1.get(LONG_LE, offset) != key2.get(LONG_LE, offset)) {
                return false;
            }
        }
        return true;
    }

    private static Stats findInTable(Stats[] stats, int hash, MemorySegment key, int keyLen, int keyLastBytes) { // open-addressing scan
        var idx = hash & HASH_TBL_SIZE;
        var st = stats[idx];
        while (st != null && !equals(st.key, st.keyLen, key, keyLen)) {
            idx = (idx + 1) % (HASH_TBL_SIZE + 1);
            st = stats[idx];
        }
        if (st != null) {
            return st;
        }
        return stats[idx] = newStats(key, keyLen, keyLastBytes, hash);
    }

    private static Stats newStats(MemorySegment key, int keyLen, int keyLastBytes, int hash) {
        var copyBytes = (long) (keyLen + 1) << 2; // include overflow chunk
        var k = ARENA.allocate(Math.max(copyBytes, 8), 8); // at least 8 bytes: equals() compares in 8-byte steps; zero-initialized
        MemorySegment.copy(key, 0, k, 0, copyBytes);
        return new Stats(k, keyLen, keyLastBytes, hash);
    }

    private static byte[] readFooter(MemorySegment ms, long offset) { // read from line start to current pos (end-of-input)
        var footer = new byte[(int) (ms.byteSize() - offset)];
        for (int i = 0; i < footer.length; i++) {
            footer[i] = ms.get(ValueLayout.JAVA_BYTE, offset + i);
        }
        return footer;
    }

    private static ByteArrayOffset readHeader(MemorySegment ms) { // read up to and including first newline (or end-of-input)
        long offset = 0;
        while (offset < ms.byteSize() && ms.get(ValueLayout.JAVA_BYTE, offset++) != '\n')
            ;
        var header = new byte[(int) offset];
        for (int i = 0; i < offset; i++) {
            header[i] = ms.get(ValueLayout.JAVA_BYTE, i);
        }
        return new ByteArrayOffset(header, offset);
    }

    record ByteArrayOffset(byte[] data, long offset) {
    }

    private static class Partition {
        byte[] header;
        byte[] footer;
        Stats[] stats;

        Partition(byte[] header, byte[] footer, Stats[] stats) {
            this.header = header;
            this.footer = footer;
            this.stats = stats;
        }
    }

    private static class Stats { // min, max, and sum values are modeled with integral types that represent tenths of a unit
        final MemorySegment key; // native segment holding the key as a 4-byte integer array plus one zero pad int
        final int keyLen; // number of 4-byte integers in key
        final int lastBytes; // number of bytes packed into last key int (1, 2, 3 or 4)
        final int hash;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum;
        long count;

        Stats(MemorySegment key, int keyLen, int lastBytes, int hash) {
            this.key = key;
            this.keyLen = keyLen;
            this.lastBytes = lastBytes;
            this.hash = hash;
        }
    }
}

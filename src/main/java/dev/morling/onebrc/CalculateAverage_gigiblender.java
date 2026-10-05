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
import java.util.TreeMap;

/**
 * Java 22+ (tested design target: Java 27) version. No sun.misc.Unsafe:
 * - the input file is accessed through the (final) java.lang.foreign API,
 * - the per-thread hash table is a plain long[] (4 longs per entry).
 *
 * The parsing logic assumes little-endian byte order, so all 8-byte reads
 * explicitly use a little-endian layout (a no-op on x86-64 / AArch64).
 */
public class CalculateAverage_gigiblender {
    private static final int AVAIL_CORES = Runtime.getRuntime().availableProcessors();
    private static final HashTable[] tables = new HashTable[AVAIL_CORES];

    private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private static final String FILE = "./measurements.txt";

    private static final long HASH_SEED = -2346162244362633811L;

    /**
     * Reads 8 bytes at {@code offset}. Unlike the old Unsafe code, a MemorySegment
     * is bounds-checked, so near the end of the file we assemble the value from the
     * bytes that exist and pad with zeros. Zero padding is safe: none of the byte
     * searches below (';', '.', '\n') can produce a false match on a 0x00 byte.
     */
    private static long readLong(MemorySegment file, long offset) {
        if (offset <= file.byteSize() - Long.BYTES) {
            return file.get(LONG_LE, offset);
        }
        return readLongSlow(file, offset);
    }

    private static long readLongSlow(MemorySegment file, long offset) {
        long size = file.byteSize();
        long v = 0;
        for (int i = 0; i < Long.BYTES && offset + i < size; i++) {
            v |= (file.get(BYTE, offset + i) & 0xFFL) << (i * Byte.SIZE);
        }
        return v;
    }

    static class HashTable {

        // 10_000 unique keys -> 16384 slots
        private static final int ENTRY_LONGS = 4;
        private static final int NUM_ENTRIES = 16384;
        private static final int ENTRY_MASK = NUM_ENTRIES - 1;

        /*
         * Entry layout (4 longs = 32 bytes), indices relative to the entry start:
         * [0] 8 bytes hash
         * [1] low 7 bytes: offset of the string in the file, high byte: length of the string
         * [2] low 4 bytes count, then 2 bytes max, then 2 bytes min (sign preserved)
         * [3] 8 bytes sum
         */
        private static final int HASH_SLOT = 0;
        private static final int ADDR_SLOT = 1;
        private static final int CMM_SLOT = 2;
        private static final int SUM_SLOT = 3;

        private static final long ADDR_MASK = 0x00FFFFFFFFFFFFFFL;
        private static final int STRING_LENGTH_SHIFT = 56;

        private final MemorySegment file;
        private final long[] data = new long[NUM_ENTRIES * ENTRY_LONGS];

        public HashTable(MemorySegment file) {
            this.file = file;
        }

        private static long string_addr(long encoded_str_addr) {
            return (encoded_str_addr & ADDR_MASK);
        }

        private static long string_length(long encoded_str_addr) {
            return encoded_str_addr >>> STRING_LENGTH_SHIFT;
        }

        private static short mask_min(long count_max_min) {
            // Preserve the sign
            return (short) (count_max_min >> 6 * Byte.SIZE);
        }

        private static short mask_max(long count_max_min) {
            return (short) (count_max_min >>> 4 * Byte.SIZE);
        }

        private static int mask_count(long count_max_min) {
            return (int) count_max_min;
        }

        private static long encode_count_max_min(int count, short max, short min) {
            return (count & 0xFFFFFFFFL) | ((((long) max) & 0xFFFF) << 4 * Byte.SIZE) | (((long) min) << 6 * Byte.SIZE);
        }

        private static boolean string_equals(MemorySegment file, long string_addr, long entry_string_addr, int size_bytes) {
            int remaining_bytes = size_bytes & 7;
            int i = 0;
            for (; i < size_bytes - remaining_bytes; i += 8) {
                if (file.get(LONG_LE, entry_string_addr + i) != file.get(LONG_LE, string_addr + i)) {
                    return false;
                }
            }
            if (remaining_bytes != 0) {
                // Read a full word and mask the bytes we care about. readLong() makes this
                // safe even when the string is within the last 8 bytes of the file.
                long entry_bytes = readLong(file, entry_string_addr + i);
                long string_bytes = readLong(file, string_addr + i);
                long mask = (1L << (remaining_bytes * Byte.SIZE)) - 1;
                return ((entry_bytes ^ string_bytes) & mask) == 0;
            }
            return true;
        }

        public void insert(long hash, long string_addr, int string_size, long final_number) {
            assert string_addr >>> 56 == 0 : String.format("Expected final 8 bytes to be 0, got %s", Long.toBinaryString(string_addr));
            assert string_size > 0 && string_size < 256 : "String length must fit in one byte, got " + string_size;

            long encoded_string_addr_and_length = string_addr | ((long) string_size << STRING_LENGTH_SHIFT);
            assert string_addr(encoded_string_addr_and_length) == string_addr;
            assert string_length(encoded_string_addr_and_length) == string_size;

            final long[] data = this.data;
            int slot = (int) (hash & ENTRY_MASK);
            while (true) {
                int base = slot * ENTRY_LONGS;
                long entry_count_max_min = data[base + CMM_SLOT];
                if (mask_count(entry_count_max_min) == 0) {
                    // Found an empty slot. Insert the entry here
                    data[base + HASH_SLOT] = hash;
                    data[base + ADDR_SLOT] = encoded_string_addr_and_length;
                    data[base + CMM_SLOT] = encode_count_max_min(1, (short) final_number, (short) final_number);
                    data[base + SUM_SLOT] = final_number;
                    return;
                }

                // Check if strings match. If yes, update. Otherwise, look for the next available slot
                long entry_string_addr_and_length = data[base + ADDR_SLOT];
                if (string_length(entry_string_addr_and_length) == string_size
                        && string_equals(file, string_addr, string_addr(entry_string_addr_and_length), string_size)) {
                    int entry_count = mask_count(entry_count_max_min) + 1;
                    short entry_max = (short) Math.max(mask_max(entry_count_max_min), (int) final_number);
                    short entry_min = (short) Math.min(mask_min(entry_count_max_min), (int) final_number);

                    data[base + CMM_SLOT] = encode_count_max_min(entry_count, entry_max, entry_min);
                    data[base + SUM_SLOT] += final_number;
                    return;
                }
                slot = (slot + 1) & ENTRY_MASK;
            }
        }

        public void update_res(TreeMap<String, Result> result_map) {
            Result r = new Result();

            for (int i = 0; i < NUM_ENTRIES; i++) {
                int base = i * ENTRY_LONGS;
                long entry_count_max_min = data[base + CMM_SLOT];
                int entry_count = mask_count(entry_count_max_min);
                if (entry_count == 0) {
                    continue;
                }
                long entry_string_addr_and_length = data[base + ADDR_SLOT];
                long entry_string_addr = string_addr(entry_string_addr_and_length);
                int entry_string_length = (int) string_length(entry_string_addr_and_length);

                byte[] bytes = new byte[entry_string_length];
                MemorySegment.copy(file, BYTE, entry_string_addr, bytes, 0, entry_string_length);
                String s = new String(bytes, StandardCharsets.UTF_8);

                short entry_max = mask_max(entry_count_max_min);
                short entry_min = mask_min(entry_count_max_min);
                long entry_sum = data[base + SUM_SLOT];

                Result ret = result_map.putIfAbsent(s, r);
                if (ret == null) {
                    r.count = entry_count;
                    r.max = entry_max;
                    r.min = entry_min;
                    r.sum = entry_sum;
                    r = new Result();
                }
                else {
                    ret.count += entry_count;
                    ret.max = (short) Math.max(ret.max, entry_max);
                    ret.min = (short) Math.min(ret.min, entry_min);
                    ret.sum += entry_sum;
                }
            }
        }

        public void dump_insert(long map_entry, long hash, long string_addr, int string_size, long final_number) {
            System.out.println("START dump_insert");
            System.out.println("Inserting " + final_number + " with hash " + hash);
            System.out.println("Map entry: " + map_entry);
            System.out.println("String addr: " + string_addr + " with length " + string_size);
            dump(file, string_addr, string_addr + string_size);
            System.out.println("END dump_insert");
        }
    }

    static class Result {
        public int count;
        public short max;
        public short min;
        public long sum;

        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }

        @Override
        public String toString() {
            return round(min / 10.) + "/" + round(sum / (double) (10 * count)) + "/" + round(max / 10.);
        }
    }

    private static void compute_slice(final MemorySegment file, final long slice_size, final long file_size, final int thread_index) {
        HashTable my_table;
        if (!SINGLE_CORE) {
            my_table = new HashTable(file);
            tables[thread_index] = my_table;
        }
        else {
            if (tables[0] == null) {
                tables[0] = new HashTable(file);
            }
            my_table = tables[0];
        }

        // All "addresses" below are byte offsets into the mapped file segment.
        long cur_addr = (long) thread_index * slice_size;
        // Lookup the next newline. If thread_index == 0 then start right away
        if (thread_index != 0) {
            while (file.get(BYTE, cur_addr) != '\n') {
                cur_addr++;
            }
            cur_addr++;
        }

        long end_addr = (long) (thread_index + 1) * slice_size;
        if (thread_index == (AVAIL_CORES - 1)) {
            // Last thread. We need to read until the end of the file
            end_addr = file_size;
        }
        else {
            // look ahead for the next newline
            while (file.get(BYTE, end_addr) != '\n') {
                end_addr++;
            }
            end_addr++;
        }

        // We now have a well-defined interval [cur_addr, end_addr) to work on
        long hash = HASH_SEED;
        int string_size = 0;
        long string_addr = cur_addr;
        while (cur_addr < end_addr) {
            long value_mem = readLong(file, cur_addr);
            int semicolon_byte_index = get_semicolon_index(value_mem);

            string_size += semicolon_byte_index;

            if (semicolon_byte_index != 8) {
                long value_mem_up_to_semicolon = value_mem & ((1L << (semicolon_byte_index * Byte.SIZE)) - 1);

                // We have a semicolon, so the hash is complete now. We can construct the number
                // and insert it into the hash table
                long start_num_addr = cur_addr + semicolon_byte_index + 1;

                // Always read the next 8 bytes for the number. It seems that this is faster than
                // checking if the whole number is in the current 8 bytes and only reading if it is not
                long number_mem_value = readLong(file, start_num_addr);
                long number_len_bytes = get_newline_index(number_mem_value);

                long final_number = extract_number(number_mem_value, number_len_bytes);

                hash = compute_hash(hash ^ value_mem_up_to_semicolon);

                // We have the final number now. We can insert it into the hash table
                my_table.insert(hash, string_addr, string_size, final_number);
                // Now we can move on to the next line
                hash = HASH_SEED;
                string_size = 0;
                cur_addr = start_num_addr + number_len_bytes + 1;
                string_addr = cur_addr;
            }
            else {
                // No semicolon in the 8 bytes read. Continue reading
                hash = hash ^ value_mem;
                cur_addr += 8;
            }
        }
        assert cur_addr == end_addr : String.format("Expected cur_addr to be %s, got %s", end_addr, cur_addr);
    }

    private static long extract_number(long number_mem_value, long number_len_bytes) {
        // Pray for GVN/CSE and Sea of Nodes moving the mess below in the proper places because
        // I don't want to spend the time to do it properly :)
        long number_mem_dot_index = get_dot_index(number_mem_value);

        int fractional_part = get_fractional_part(number_mem_value, number_len_bytes);
        int sign = get_sign(number_mem_value);
        int skip_sign = skip_sign(number_mem_value);

        long number_mem_value_no_sign = number_mem_value >>> (skip_sign << 3);
        // Two cases: either there's a single digit before the dot, or there's two
        // Start from the dot index and go backwards
        long new_number_mem_dot_index = number_mem_dot_index - skip_sign;
        long read_byte_mask = 0xFFL << ((new_number_mem_dot_index - 1) * Byte.SIZE);
        long ones = ((number_mem_value_no_sign & read_byte_mask) >>> ((new_number_mem_dot_index - 1) * Byte.SIZE)) - 0x30;
        // Should be 0 due to the multiplication if there's only one digit before the dot
        long tens = ((number_mem_value_no_sign & 0xFFL) - 0x30) * (new_number_mem_dot_index - 1);

        long final_number = (tens * 100 + ones * 10 + fractional_part) * sign;
        return final_number;
    }

    private static int get_fractional_part(long number_mem_value, long number_len_bytes) {
        return (int) ((number_mem_value >>> ((number_len_bytes - 1) * Byte.SIZE)) & 0xFF) - 0x30;
    }

    private static int skip_sign(long number_mem_value) {
        // return 1 if char is '-', 0 if it is not
        long diff = (number_mem_value & 0xFF) - 0x2D;
        long sign = (diff | -diff) >>> 63;
        return (int) ((sign - 1) * -1);
    }

    private static int get_sign(long number_mem_value) {
        // return 1 if char is not '-', -1 if it is
        long diff = (number_mem_value & 0xFF) - 0x2D;
        long sign = (diff | -diff) >>> 63;
        return (int) (-2 * sign + 1) * -1;
    }

    private static long compute_hash(long x) { // Hash burrowed from artsiomkorzun and slightly changed
        long h = x * -7046029254386353131L;
        long h1 = h ^ (h >>> 32);
        h = h ^ (h << 32);
        return h1 ^ h;
    }

    private static void dump(MemorySegment file, long startAddr, long endAddr) {
        byte[] bytes = new byte[(int) (endAddr - startAddr)];
        MemorySegment.copy(file, BYTE, startAddr, bytes, 0, bytes.length);
        String s = new String(bytes, StandardCharsets.UTF_8);
        System.out.println(s);
        // Dump the bytes to binary form
        for (byte b : bytes) {
            System.out.print(Integer.toBinaryString(b & 0xFF));
            System.out.print(" ");
        }
        System.out.println();
        // Dump the bytes to hex form
        for (byte b : bytes) {
            System.out.print(Integer.toHexString(b & 0xFF));
            System.out.print(" ");
        }
        System.out.println();
    }

    private static int get_byte_0_index(long value) {
        long res = (value - 0x0101010101010101L) & (~value & 0x8080808080808080L);
        res = Long.numberOfTrailingZeros(res) >> 3;
        return (int) res;
    }

    private static int get_dot_index(long value) {
        long temp = value ^ 0x2E2E2E2E2E2E2E2EL;
        return get_byte_0_index(temp);
    }

    private static int get_newline_index(long value) {
        long temp = value ^ 0x0A0A0A0A0A0A0A0AL;
        return get_byte_0_index(temp);
    }

    private static int get_semicolon_index(long value) {
        long temp = value ^ 0x3B3B3B3B3B3B3B3BL;
        return get_byte_0_index(temp);
    }

    private static final boolean SINGLE_CORE = false;

    private static MemorySegment map_file() throws IOException {
        // The mapping stays valid after the channel is closed; Arena.global() keeps it alive
        // for the lifetime of the process and makes it accessible from every thread.
        try (FileChannel file_channel = FileChannel.open(Paths.get(FILE), StandardOpenOption.READ)) {
            return file_channel.map(FileChannel.MapMode.READ_ONLY, 0, file_channel.size(), Arena.global());
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        final MemorySegment file = map_file();
        final long file_size = file.byteSize();
        final long slice_size = file_size / AVAIL_CORES;

        if (!SINGLE_CORE) {
            int num_threads = AVAIL_CORES;
            Thread[] threads = new Thread[num_threads];
            for (int i = 0; i < num_threads; i++) {
                int finalI = i;
                threads[i] = new Thread(() -> compute_slice(file, slice_size, file_size, finalI));
                threads[i].start();
            }

            TreeMap<String, Result> result_map = new TreeMap<>();
            for (int i = 0; i < num_threads; i++) {
                threads[i].join();
                tables[i].update_res(result_map);
            }

            System.out.println(result_map);
        }
        else {
            for (int i = 0; i < AVAIL_CORES; i++) {
                compute_slice(file, slice_size, file_size, i);
            }

            TreeMap<String, Result> result_map = new TreeMap<>();
            tables[0].update_res(result_map);

            System.out.println(result_map);
        }
    }
}

package org.graalvm.visualvm.lib.jfluid.heap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

final class LongList {
    private long[] array;
    private int size;

    LongList() {
        this.array = new long[10];
    }

    LongList(int size) {
        this.array = new long[Math.max(size, 10)];
    }

    int size() {
        return this.size;
    }

    long get(int index) {
        if (index < 0 || index >= this.size) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + this.size);
        }

        return this.array[index];
    }

    long uncheckedGet(int index) {
        return this.array[index];
    }

    void add(long value) {
        if (this.size == this.array.length) {
            this.array = Arrays.copyOf(this.array, this.array.length + (this.array.length >> 2));
        }

        this.array[this.size++] = value;
    }

    void clear() {
        this.size = 0;
    }

    //---- Serialization support
    LongList(DataInputStream dis) throws IOException {
        int size = dis.readInt();
        this.array = new long[Math.max(10, size)];

        for (int i = 0; i < size; i++) {
            this.array[i] = dis.readLong();
        }
    }

    void writeToStream(DataOutputStream out) throws IOException {
        long[] array = this.array;
        int size = this.size;

        out.writeInt(size);

        for (int i = 0; i < size; i++) {
            out.writeLong(array[i]);
        }
    }
}

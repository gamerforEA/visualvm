package org.graalvm.visualvm.lib.jfluid.heap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

final class IntList {
    private int[] array;
    private int size;

    IntList() {
        this.array = new int[10];
    }

    IntList(int size) {
        this.array = new int[Math.max(size, 10)];
    }

    int size() {
        return this.size;
    }

    int get(int index) {
        if (index < 0 || index >= this.size) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + this.size);
        }

        return this.array[index];
    }

    int uncheckedGet(int index) {
        return this.array[index];
    }

    void add(int value) {
        if (this.size == this.array.length) {
            this.array = Arrays.copyOf(this.array, this.array.length + (this.array.length >> 2));
        }

        this.array[this.size++] = value;
    }

    void clear() {
        this.size = 0;
    }

    //---- Serialization support
    IntList(DataInputStream dis) throws IOException {
        int size = dis.readInt();
        this.array = new int[Math.max(10, size)];

        for (int i = 0; i < size; i++) {
            this.array[i] = dis.readInt();
        }
    }

    void writeToStream(DataOutputStream out) throws IOException {
        int[] array = this.array;
        int size = this.size;

        out.writeInt(size);

        for (int i = 0; i < size; i++) {
            out.writeInt(array[i]);
        }
    }
}

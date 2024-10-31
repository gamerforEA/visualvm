package org.graalvm.visualvm.lib.jfluid.heap;

import java.util.Arrays;

final class LongList {
    private long[] array = new long[10];
    private int size;

    int size() {
        return this.size;
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
}

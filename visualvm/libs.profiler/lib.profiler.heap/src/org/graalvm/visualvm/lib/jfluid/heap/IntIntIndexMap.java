package org.graalvm.visualvm.lib.jfluid.heap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

final class IntIntIndexMap {
    private static final int ABSENT_VALUE = -1;
    private int[] values;

    IntIntIndexMap(int size) {
        this.values = new int[Math.max(10, size)];
        Arrays.fill(this.values, ABSENT_VALUE);
    }

    void put(int key, int value) {
        assert key >= 0;
        int[] values = this.values;

        if (key >= values.length) {
            if (value == ABSENT_VALUE) {
                return;
            }

            int oldLength = values.length;
            int required = Math.max(key + 1, oldLength + (oldLength >> 2));
            values = Arrays.copyOf(values, required);
            Arrays.fill(values, oldLength, values.length, ABSENT_VALUE);
            this.values = values;
        }

        values[key] = value;
    }

    int get(int key) {
        int[] values = this.values;
        return 0 <= key && key < values.length ? values[key] : ABSENT_VALUE;
    }

    //---- Serialization support
    void writeToStream(DataOutputStream out) throws IOException {
        int[] values = this.values;
        int size = 0;

        for (int value : values) {
            if (value != ABSENT_VALUE) {
                size++;
            }
        }

        out.writeInt(values.length);
        out.writeInt(size);

        for (int key = 0; key < values.length; key++) {
            int value = values[key];

            if (value != ABSENT_VALUE) {
                out.writeInt(key);
                out.writeInt(value);
            }
        }
    }

    IntIntIndexMap(DataInputStream dis) throws IOException {
        this.values = new int[dis.readInt()];
        int size = dis.readInt();

        for (int i = 0; i < size; i++) {
            int key = dis.readInt();
            int value = dis.readInt();
            this.values[key] = value;
        }
    }
}

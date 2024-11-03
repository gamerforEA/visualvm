/*
 * Copyright (c) 2011, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.visualvm.lib.jfluid.heap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/**
 * Map for ints. IdentityHashMap was used as template.
 * Zero cannot be used as key. Load factor is 3/4.
 * @author Tomas Hurka
 */
class IntHashMap {
    /**
     * The initial capacity used by the no-args constructor.
     * MUST be a power of two.
     */
    private static final int DEFAULT_CAPACITY = 32;

    /**
     * The minimum capacity, used if a lower value is implicitly specified
     * by either of the constructors with arguments.  The value 4 corresponds
     * to an expected maximum size of 2, given a load factor of 2/3.
     * MUST be a power of two.
     */
    private static final int MINIMUM_CAPACITY = 4;

    /**
     * The maximum capacity, used if a higher value is implicitly specified
     * by either of the constructors with arguments.
     * MUST be a power of two <= 1<<29.
     */
    private static final int MAXIMUM_CAPACITY = 1 << 29;

    /**
     * The table, resized as necessary. Length MUST always be a power of two.
     */
    private transient int[] table;

    /**
     * The number of key-value mappings contained in this identity hash map.
     *
     * @serial
     */
    private int size;

    /**
     * The number of modifications, to support fast-fail iterators
     */
    private transient int modCount;

    /**
     * The next size value at which to resize (capacity * load factor).
     */
    private transient int threshold;

    /**
     * Constructs a new, empty identity hash map with a default expected
     * maximum size.
     */
    IntHashMap() {
        init(DEFAULT_CAPACITY);
    }

    /**
     * Constructs a new, empty map with the specified expected maximum size.
     * Putting more than the expected number of key-value mappings into
     * the map may cause the internal data structure to grow, which may be
     * somewhat time-consuming.
     *
     * @param expectedMaxSize the expected maximum size of the map
     * @throws IllegalArgumentException if <tt>expectedMaxSize</tt> is negative
     */
    IntHashMap(int expectedMaxSize) {
        if (expectedMaxSize < 0)
            throw new IllegalArgumentException("expectedMaxSize is negative: "
                                               + expectedMaxSize);
        init(capacity(expectedMaxSize));
    }

    /**
     * Returns the appropriate capacity for the specified expected maximum
     * size.  Returns the smallest power of two between MINIMUM_CAPACITY
     * and MAXIMUM_CAPACITY, inclusive, that is greater than
     * (4 * expectedMaxSize)/3, if such a number exists.  Otherwise
     * returns MAXIMUM_CAPACITY.  If (4 * expectedMaxSize)/3 is negative, it
     * is assumed that overflow has occurred, and MAXIMUM_CAPACITY is returned.
     */
    private int capacity(int expectedMaxSize) {
        // Compute min capacity for expectedMaxSize given a load factor of 3/4
        int minCapacity = (4 * expectedMaxSize)/3;

        // Compute the appropriate capacity
        int result;
        if (minCapacity > MAXIMUM_CAPACITY || minCapacity < 0) {
            result = MAXIMUM_CAPACITY;
        } else {
            result = MINIMUM_CAPACITY;
            while (result < minCapacity)
                result <<= 1;
        }
        return result;
    }

    /**
     * Initializes object to be an empty map with the specified initial
     * capacity, which is assumed to be a power of two between
     * MINIMUM_CAPACITY and MAXIMUM_CAPACITY inclusive.
     */
    private void init(int initCapacity) {
        assert (initCapacity & -initCapacity) == initCapacity; // power of 2
        assert initCapacity >= MINIMUM_CAPACITY;
        assert initCapacity <= MAXIMUM_CAPACITY;

        threshold = (initCapacity * 3)/ 4;
        table = new int[2 * initCapacity];
    }

    /**
     * Returns the number of key-value mappings in this identity hash map.
     *
     * @return the number of key-value mappings in this map
     */
    int size() {
        return size;
    }

    /**
     * Returns <tt>true</tt> if this identity hash map contains no key-value
     * mappings.
     *
     * @return <tt>true</tt> if this identity hash map contains no key-value
     *         mappings
     */
    boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns index for Object x.
     */
    private static int hash(int x, int length) {
        int h = x;
        // This function ensures that hashCodes that differ only by
        // constant multiples at each bit position have a bounded
        // number of collisions (approximately 8 at default load factor).
        h ^= (h >>> 20) ^ (h >>> 12);
        h ^= (h >>> 7) ^ (h >>> 4);
        return (h) & (length - 2);
    }

    /**
     * Circularly traverses table of size len.
     */
    private static int nextKeyIndex(int i, int len) {
        return (i + 2 < len ? i + 2 : 0);
    }

    /**
     * Returns the value to which the specified key is mapped,
     * or {@code null} if this map contains no mapping for the key.
     *
     * <p>More formally, if this map contains a mapping from a key
     * {@code k} to a value {@code v} such that {@code (key == k)},
     * then this method returns {@code v}; otherwise it returns
     * {@code null}.  (There can be at most one such mapping.)
     *
     * <p>A return value of {@code null} does not <i>necessarily</i>
     * indicate that the map contains no mapping for the key; it's also
     * possible that the map explicitly maps the key to {@code null}.
     * The {@link #containsKey containsKey} operation may be used to
     * distinguish these two cases.
     *
     * @see #put(Object, Object)
     */
    int get(int key) {
        int[] tab = table;
        int len = tab.length;
        int i = hash(key, len);
        while (true) {
            int item = tab[i];
            if (item == key)
                return tab[i + 1];
            if (item == 0)
                return -1;
            i = nextKeyIndex(i, len);
        }
    }

    /**
     * Tests whether the specified object reference is a key in this identity
     * hash map.
     *
     * @param   key   possible key
     * @return  <code>true</code> if the specified object reference is a key
     *          in this map
     * @see     #containsValue(Object)
     */
    boolean containsKey(int key) {
        int[] tab = table;
        int len = tab.length;
        int i = hash(key, len);
        while (true) {
            int item = tab[i];
            if (item == key)
                return true;
            if (item == 0)
                return false;
            i = nextKeyIndex(i, len);
        }
    }

    /**
     * Tests whether the specified object reference is a value in this identity
     * hash map.
     *
     * @param value value whose presence in this map is to be tested
     * @return <tt>true</tt> if this map maps one or more keys to the
     *         specified object reference
     * @see     #containsKey(Object)
     */
    boolean containsValue(int value) {
        int[] tab = table;
        for (int i = 1; i < tab.length; i += 2)
            if (tab[i] == value && tab[i - 1] != 0)
                return true;

        return false;
    }

    /**
     * Tests if the specified key-value mapping is in the map.
     *
     * @param   key   possible key
     * @param   value possible value
     * @return  <code>true</code> if and only if the specified key-value
     *          mapping is in the map
     */
    private boolean containsMapping(int key, int value) {
        int[] tab = table;
        int len = tab.length;
        int i = hash(key, len);
        while (true) {
            int item = tab[i];
            if (item == key)
                return tab[i + 1] == value;
            if (item == 0)
                return false;
            i = nextKeyIndex(i, len);
        }
    }

    /**
     * Associates the specified value with the specified key in this identity
     * hash map.  If the map previously contained a mapping for the key, the
     * old value is replaced.
     *
     * @param key the key with which the specified value is to be associated
     * @param value the value to be associated with the specified key
     * @return the previous value associated with <tt>key</tt>, or
     *         <tt>null</tt> if there was no mapping for <tt>key</tt>.
     *         (A <tt>null</tt> return can also indicate that the map
     *         previously associated <tt>null</tt> with <tt>key</tt>.)
     * @see     Object#equals(Object)
     * @see     #get(Object)
     * @see     #containsKey(Object)
     */
    int put(int key, int value) {
        assert key != 0;
        assert value != -1;
        int[] tab = table;
        int len = tab.length;
        int i = hash(key, len);

        int item;
        while ( (item = tab[i]) != 0) {
            if (item == key) {
                int oldValue = tab[i + 1];
                tab[i + 1] = value;
                return oldValue;
            }
            i = nextKeyIndex(i, len);
        }

        modCount++;
        tab[i] = key;
        tab[i + 1] = value;
        if (++size >= threshold)
            resize(len); // len == 2 * current capacity.
        return -1;
    }

    /**
     * Resize the table to hold given capacity.
     *
     * @param newCapacity the new capacity, must be a power of two.
     */
    private void resize(int newCapacity) {
        // assert (newCapacity & -newCapacity) == newCapacity; // power of 2
        int newLength = newCapacity * 2;

        int[] oldTable = table;
        int oldLength = oldTable.length;
        if (oldLength == 2*MAXIMUM_CAPACITY) { // can't expand any further
            if (threshold == MAXIMUM_CAPACITY-1)
                throw new IllegalStateException("Capacity exhausted.");
            threshold = MAXIMUM_CAPACITY-1;  // Gigantic map!
            return;
        }
        if (oldLength >= newLength)
            return;

        int[] newTable = new int[newLength];
        threshold = (newCapacity * 3) / 4;

        for (int j = 0; j < oldLength; j += 2) {
            int key = oldTable[j];
            if (key != 0) {
                int value = oldTable[j+1];
                int i = hash(key, newLength);
                while (newTable[i] != 0)
                    i = nextKeyIndex(i, newLength);
                newTable[i] = key;
                newTable[i + 1] = value;
            }
        }
        table = newTable;
    }

    /**
     * Copies all of the mappings from the specified map to this map.
     * These mappings will replace any mappings that this map had for
     * any of the keys currently in the specified map.
     *
     * @param m mappings to be stored in this map
     * @throws NullPointerException if the specified map is null
     */
    void putAll(Map<Integer,Integer> m) {
        int n = m.size();
        if (n == 0)
            return;
        if (n > threshold) // conservatively pre-expand
            resize(capacity(n));

        for (Map.Entry<Integer,Integer> e : m.entrySet())
            put(e.getKey(), e.getValue());
    }

    /**
     * Removes the mapping for this key from this map if present.
     *
     * @param key key whose mapping is to be removed from the map
     * @return the previous value associated with <tt>key</tt>, or
     *         <tt>null</tt> if there was no mapping for <tt>key</tt>.
     *         (A <tt>null</tt> return can also indicate that the map
     *         previously associated <tt>null</tt> with <tt>key</tt>.)
     */
    int remove(int key) {
        int[] tab = table;
        int len = tab.length;
        int i = hash(key, len);

        while (true) {
            int item = tab[i];
            if (item == key) {
                modCount++;
                size--;
                int oldValue = tab[i + 1];
                tab[i + 1] = 0;
                tab[i] = 0;
                closeDeletion(i);
                return oldValue;
            }
            if (item == 0)
                return -1;
            i = nextKeyIndex(i, len);
        }

    }

    /**
     * Removes the specified key-value mapping from the map if it is present.
     *
     * @param   key   possible key
     * @param   value possible value
     * @return  <code>true</code> if and only if the specified key-value
     *          mapping was in the map
     */
    private boolean removeMapping(int key, int value) {
        int[] tab = table;
        int len = tab.length;
        int i = hash(key, len);

        while (true) {
            int item = tab[i];
            if (item == key) {
                if (tab[i + 1] != value)
                    return false;
                modCount++;
                size--;
                tab[i] = 0;
                tab[i + 1] = 0;
                closeDeletion(i);
                return true;
            }
            if (item == 0)
                return false;
            i = nextKeyIndex(i, len);
        }
    }

    /**
     * Rehash all possibly-colliding entries following a
     * deletion. This preserves the linear-probe
     * collision properties required by get, put, etc.
     *
     * @param d the index of a newly empty deleted slot
     */
    private void closeDeletion(int d) {
        // Adapted from Knuth Section 6.4 Algorithm R
        int[] tab = table;
        int len = tab.length;

        // Look for items to swap into newly vacated slot
        // starting at index immediately following deletion,
        // and continuing until a null slot is seen, indicating
        // the end of a run of possibly-colliding keys.
        int item;
        for (int i = nextKeyIndex(d, len); (item = tab[i]) != 0;
             i = nextKeyIndex(i, len) ) {
            // The following test triggers if the item at slot i (which
            // hashes to be at slot r) should take the spot vacated by d.
            // If so, we swap it in, and then continue with d now at the
            // newly vacated i.  This process will terminate when we hit
            // the null slot at the end of this run.
            // The test is messy because we are using a circular table.
            int r = hash(item, len);
            if ((i < r && (r <= d || d <= i)) || (r <= d && d <= i)) {
                tab[d] = item;
                tab[d + 1] = tab[i + 1];
                tab[i] = 0;
                tab[i + 1] = 0;
                d = i;
            }
        }
    }

    /**
     * Removes all of the mappings from this map.
     * The map will be empty after this call returns.
     */
    void clear() {
        modCount++;
        int[] tab = table;
        Arrays.fill(tab, 0);
        size = 0;
    }

    /**
     * Compares the specified object with this map for equality.  Returns
     * <tt>true</tt> if the given object is also a map and the two maps
     * represent identical object-reference mappings.  More formally, this
     * map is equal to another map <tt>m</tt> if and only if
     * <tt>this.entrySet().equals(m.entrySet())</tt>.
     *
     * <p><b>Owing to the reference-equality-based semantics of this map it is
     * possible that the symmetry and transitivity requirements of the
     * <tt>Object.equals</tt> contract may be violated if this map is compared
     * to a normal map.  However, the <tt>Object.equals</tt> contract is
     * guaranteed to hold among <tt>IntHashMap</tt> instances.</b>
     *
     * @param  o object to be compared for equality with this map
     * @return <tt>true</tt> if the specified object is equal to this map
     * @see Object#equals(Object)
     */
    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (o instanceof IntHashMap) {
            IntHashMap m = (IntHashMap) o;
            if (m.size() != size)
                return false;

            int[] tab = m.table;
            for (int i = 0; i < tab.length; i+=2) {
                int k = tab[i];
                if (k != 0 && !containsMapping(k, tab[i + 1]))
                    return false;
            }
            return true;
        } else {
            return false;  // o is not a IntHashMap
        }
    }

    /**
     * Returns the hash code value for this map.  The hash code of a map is
     * defined to be the sum of the hash codes of each entry in the map's
     * <tt>entrySet()</tt> view.  This ensures that <tt>m1.equals(m2)</tt>
     * implies that <tt>m1.hashCode()==m2.hashCode()</tt> for any two
     * <tt>IdentityHashMap</tt> instances <tt>m1</tt> and <tt>m2</tt>, as
     * required by the general contract of {@link Object#hashCode}.
     *
     * <p><b>Owing to the reference-equality-based semantics of the
     * <tt>Map.Entry</tt> instances in the set returned by this map's
     * <tt>entrySet</tt> method, it is possible that the contractual
     * requirement of <tt>Object.hashCode</tt> mentioned in the previous
     * paragraph will be violated if one of the two objects being compared is
     * an <tt>IntHashMap</tt> instance and the other is a normal map.</b>
     *
     * @return the hash code value for this map
     * @see Object#equals(Object)
     * @see #equals(Object)
     */
    @Override
    public int hashCode() {
        int result = 0;
        int[] tab = table;
        for (int i = 0; i < tab.length; i +=2) {
            int key = tab[i];
            if (key != 0) {
                result += hash(key,tab.length) ^
                          hash(tab[i + 1],tab.length);
            }
        }
        return result;
    }

    //---- Serialization support
    void writeToStream(DataOutputStream out) throws IOException {
        out.writeInt(modCount);
        out.writeInt(size);
        out.writeInt(threshold);
        out.writeInt(table.length);
        for (int i = 0; i < table.length; i++) {
            out.writeInt(table[i]);
        }
    }

    IntHashMap(DataInputStream dis) throws IOException {
        modCount = dis.readInt();
        size = dis.readInt();
        threshold = dis.readInt();
        table = new int[dis.readInt()];
        for (int i = 0; i < table.length; i++) {
            table[i] = dis.readInt();
        }
    }
}

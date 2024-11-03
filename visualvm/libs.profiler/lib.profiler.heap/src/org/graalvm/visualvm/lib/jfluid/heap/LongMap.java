/*
 * Copyright (c) 1997, 2022, Oracle and/or its affiliates. All rights reserved.
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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * key - ID (long/int) of heap object
 * value (8/4) + 4 + 4 + 1 + 4 + (8/4)
 *  - index (int)
 *  - offset (long/int) to dump file
 *  - instance index (int) - unique number of this {@link Instance} among all instances of the same Java Class
 *  - references flags (byte) - bit 0 set - has zero or one reference,
 *                            - bit 1 set - has GC root
 *                            - bit 2 set - tree object
 *  - ID/offset (int) - ID if reference flag bit 0 is set, otherwise block index of reference list file
 *  - retained size (long/int)
 *
 * @author Tomas Hurka
 */
class LongMap extends AbstractLongMap {

    private final LongList instanceList;
    private final NumberList referenceList;

    //~ Inner Classes ------------------------------------------------------------------------------------------------------------

    class Entry extends AbstractLongMap.Entry {

        private static final byte NUMBER_LIST = 1;
        private static final byte GC_ROOT = 2;
        private static final byte TREE_OBJ = 4;
        private static final byte DEEP_OBJ = 8;

        //~ Instance fields ------------------------------------------------------------------------------------------------------

        private final long offset;

        //~ Constructors ---------------------------------------------------------------------------------------------------------

        private Entry(long off) {
            offset = off;
        }

        private Entry(long off,int listIndex,long value) {
            offset = off;
            dumpBuffer.putInt(offset + KEY_SIZE, listIndex);
            putFoffset(offset + KEY_SIZE + 4, value);
        }

        //~ Methods --------------------------------------------------------------------------------------------------------------

        int getListIndex() {
            return dumpBuffer.getInt(offset + KEY_SIZE);
        }

        void setIndex(int index) {
            dumpBuffer.putInt(offset + KEY_SIZE + 4 + FOFFSET_SIZE, index);
        }

        int getIndex() {
            return dumpBuffer.getInt(offset + KEY_SIZE + 4 + FOFFSET_SIZE);
        }

        void setTreeObj() {
            byte flags = (byte)(getFlags() | TREE_OBJ);
            setFlags(flags);
        }
        
        boolean isTreeObj() {
            return (getFlags() & TREE_OBJ) != 0;
        }

        void setDeepObj() {
            byte flags = (byte)(getFlags() | DEEP_OBJ);
            setFlags(flags);
        }

        boolean isDeepObj() {
            return (getFlags() & DEEP_OBJ) != 0;
        }

        boolean hasOnlyOneReference() {
            return (getFlags() & NUMBER_LIST) == 0;
        }
        
        void setNearestGCRootPointer(int instanceIndex) {
            byte flags = (byte)(getFlags() | GC_ROOT);
            setFlags(flags);
            if ((flags & NUMBER_LIST) != 0) {   // put GC root pointer on the first place in references list
                try {
                    referenceList.putFirst(getReferencesPointer(),instanceIndex);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }

        int getNearestGCRootPointer() {
            try {
                byte flag = getFlags();
                if ((flag & GC_ROOT) != 0) { // has GC root pointer
                    int ref = getReferencesPointer();
                    if ((flag & NUMBER_LIST) != 0) { // get GC root pointer from number list
                        return referenceList.getFirstNumber(ref);
                    }
                    return ref;
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            return 0;
        }
        
        void addReference(int instanceIndex) {
            try {
                byte flags = getFlags();
                int ref = getReferencesPointer();
                if ((flags & NUMBER_LIST) == 0) { // reference list is not used
                    if (ref == 0) {    // no reference was set
                        setReferencesPointer(instanceIndex);
                    } else if (ref != instanceIndex) {    // one reference was set, switch to reference list
                       setFlags((byte)(flags | NUMBER_LIST));
                       int list = referenceList.addFirstNumber(ref,instanceIndex);
                       setReferencesPointer(list);
                    }
                } else { // use reference list
                    int newRef = referenceList.addNumber(ref,instanceIndex);
                    if (newRef != ref) {
                        setReferencesPointer(newRef);
                    }
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        
        IntIterator getReferences() {
            byte flags = getFlags();
            int ref = getReferencesPointer();
            if ((flags & NUMBER_LIST) == 0) {
                if (ref == 0) {
                    return IntIterator.EMPTY_ITERATOR;
                } else {
                    return IntIterator.singleton(ref);
                }
            } else {
                try {
                    return referenceList.getNumbersIterator(ref);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
            return IntIterator.EMPTY_ITERATOR;
        }
        
        long getOffset() {
            return getFoffset(offset + KEY_SIZE + 4);
        }

        void setRetainedSize(long size) {
            if (FOFFSET_SIZE == 4) {
                dumpBuffer.putInt(offset + KEY_SIZE + 4 + FOFFSET_SIZE + 4 + 1 + 4, (int)size);
            } else {
                dumpBuffer.putLong(offset + KEY_SIZE + 4 + FOFFSET_SIZE + 4 + 1 + 4, size);
            }
        }

        long getRetainedSize() {
            if (FOFFSET_SIZE == 4) {
                return dumpBuffer.getInt(offset + KEY_SIZE + 4 + FOFFSET_SIZE + 4 + 1 + 4);
            }
            return dumpBuffer.getLong(offset + KEY_SIZE + 4 + FOFFSET_SIZE + 4 + 1 + 4);
        }

        private void setReferencesPointer(int instanceIndex) {
            dumpBuffer.putInt(offset + KEY_SIZE + 4 + FOFFSET_SIZE + 4 + 1, instanceIndex);
        }

        private int getReferencesPointer() {
            return dumpBuffer.getInt(offset + KEY_SIZE + 4 + FOFFSET_SIZE + 4 + 1);
        }

        private void setFlags(byte flags) {
            dumpBuffer.putByte(offset + KEY_SIZE + 4 + FOFFSET_SIZE + 4, flags);
        }

        private byte getFlags() {
            return dumpBuffer.getByte(offset + KEY_SIZE + 4 + FOFFSET_SIZE + 4);
        }
    }

    private static class RetainedSizeEntry implements Comparable<RetainedSizeEntry> {
        private final long instanceId;
        private final long retainedSize;
        
        private RetainedSizeEntry(long id,long size) {
            instanceId = id;
            retainedSize = size;
        }

        public int compareTo(RetainedSizeEntry other) {
            // bigger object are at beginning
            int diff = Long.compare(other.retainedSize, retainedSize);
            if (diff == 0) {
                // sizes are the same, compare ids
                return Long.compare(instanceId, other.instanceId);
            }
            return diff;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final RetainedSizeEntry other = (RetainedSizeEntry) obj;
            return this.instanceId == other.instanceId;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 31 * hash + (int) (this.instanceId ^ (this.instanceId >>> 32));
            return hash;
        }
    }
    
    //~ Constructors -------------------------------------------------------------------------------------------------------------

    LongMap(int size,int idSize,int foffsetSize,CacheDirectory cacheDir) throws FileNotFoundException, IOException {
        super(size,idSize,foffsetSize,4 + foffsetSize + 4 + 1 + 4 + foffsetSize, cacheDir);
        instanceList = new LongList(size + 1);
        instanceList.add(0); // Instance ID and index can't be zero
        referenceList = cacheDir.createNumberList();
    }

    //~ Methods ------------------------------------------------------------------------------------------------------------------

    long getInstanceIdByIndex(int index) {
        return instanceList.get(index);
    }

    int getInstanceIndexById(long instanceId) {
        Entry entry = get(instanceId);
        return entry == null ? 0 : entry.getListIndex();
    }

    Entry createEntry(long index) {
        return new Entry(index);
    }
    
    Entry createEntryOnPut(long key, long index, long value) {
        int listIndex = instanceList.size();
        instanceList.add(key);
        return new Entry(index,listIndex,value);
    }
    
    Entry getByIndex(int index) {
        return get(getInstanceIdByIndex(index));
    }

    Entry get(long key) {
        return (Entry)super.get(key);
    }

    Entry put(long key, long value) {
        return (Entry)super.put(key,value);
    }

    void flush() {
        referenceList.flush();
    }

    long[] getBiggestObjectsByRetainedSize(int number) {
        SortedSet<RetainedSizeEntry> bigObjects = new TreeSet<>();
        long[] bigIds = new long[number];
        long min = 0;
        for (long index=0;index<fileSize;index+=ENTRY_SIZE) {
            long id = getID(index);
            if (id != 0) {
                long retainedSize = createEntry(index).getRetainedSize();
                if (bigObjects.size()<number) {
                    bigObjects.add(new RetainedSizeEntry(id,retainedSize));
                    min = bigObjects.last().retainedSize;
                } else if (retainedSize>min) {
                    bigObjects.remove(bigObjects.last());
                    bigObjects.add(new RetainedSizeEntry(id,retainedSize));
                    min = bigObjects.last().retainedSize;
                }
            }
        }
        int i = 0;
        for (RetainedSizeEntry rse : bigObjects) {
            bigIds[i++]=rse.instanceId;
        }
        return bigIds;
    }

    //---- Serialization support    
    void writeToStream(DataOutputStream out) throws IOException {
        super.writeToStream(out);
        instanceList.writeToStream(out);
        referenceList.writeToStream(out);
    }
    
    LongMap(DataInputStream dis, CacheDirectory cacheDir) throws IOException {
        super(dis, cacheDir);
        instanceList = new LongList(dis);
        referenceList = new NumberList(dis, cacheDir);
    }
}

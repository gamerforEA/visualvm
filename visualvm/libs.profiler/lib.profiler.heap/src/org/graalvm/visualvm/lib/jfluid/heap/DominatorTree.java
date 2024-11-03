/*
 * Copyright (c) 1997, 2023, Oracle and/or its affiliates. All rights reserved.
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
import java.util.*;

/**
 *
 * @author Tomas Hurka
 */
class DominatorTree {
    //~ Static fields/initializers -----------------------------------------------------------------------------------------------

    private static final int BUFFER_SIZE = (64 * 1024) / 8;
    private static final int ADDITIONAL_IDS_THRESHOLD = 30;
    private static final int ADDITIONAL_IDS_THRESHOLD_DIRTYSET_SAME_SIZE = 5;

    //~ Instance fields ----------------------------------------------------------------------------------------------------------

    private HprofHeap heap;
    private IntBuffer multipleParents;
    private IntBuffer revertedMultipleParents;
    private IntBuffer currentMultipleParents;
    private IntIntIndexMap map;
    private int dirtySetSameSize;
    private Map<ClassDump,Boolean> canContainItself;
    private Map<Integer,Integer> nearestGCRootCache = new NearestGCRootCache<>(400000);

    //~ Constructors -------------------------------------------------------------------------------------------------------------

    DominatorTree(HprofHeap h, IntBuffer multiParents) {
        heap = h;
        multipleParents = multiParents;
        currentMultipleParents = multipleParents;
        map = new IntIntIndexMap(multiParents.getSize());
        try {
            revertedMultipleParents = multiParents.revertBuffer();
        } catch (IOException ex) {
            throw new IllegalArgumentException(ex.getLocalizedMessage(),ex);
        }
    }

    //~ Methods ------------------------------------------------------------------------------------------------------------------    
    
    synchronized void computeDominators() {
        boolean changed = true;
        boolean igonoreDirty;
        try {
            FastBitSet dirtySet = new FastBitSet();
            FastBitSet newDirtySet = new FastBitSet();

            FastBitSet leftIdoms = new FastBitSet();
            FastBitSet rightIdoms = new FastBitSet();

            IntList additionalIndexes = new IntList();

            do {
                currentMultipleParents.startReading();
                igonoreDirty = !changed;
                changed = computeOneLevel(igonoreDirty, dirtySet, newDirtySet, leftIdoms, rightIdoms, additionalIndexes);

                // Swap dirty sets
                FastBitSet oldDirtySet = dirtySet;
                dirtySet = newDirtySet;
                oldDirtySet.clear();
                newDirtySet = oldDirtySet;

                switchParents();
            } while (changed || !igonoreDirty);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        deleteBuffers();
    }
    
    private boolean computeOneLevel(boolean ignoreDirty, FastBitSet dirtySet, FastBitSet newDirtySet, FastBitSet leftIdoms, FastBitSet rightIdoms, IntList additionalIndexes) throws IOException {
        boolean changed = false;
        additionalIndexes.clear();
        int additionalIndex = 0;
        // debug 
//        long processedId = 0;
//        long changedId = 0;
//        long index = 0;
//        List<Long> changedIds = new ArrayList();
//        List<Long> changedIdx = new ArrayList();
//        List<Boolean> addedBynewDirtySet = new ArrayList();
//        List<Long> oldDomIds = new ArrayList();
//        List<Long> newDomIds = new ArrayList();

//System.out.println("New level, dirtyset size: "+dirtySet.size());
        for (;;) {
            int instanceIndex = readInt();
            if (instanceIndex == 0) {  // end of level
                if (additionalIndex >= additionalIndexes.size()) {
                    if (additionalIndex>0) {
//System.out.println("Additional instances "+additionalIndex);
                    }
                    break;
                }
                instanceIndex = additionalIndexes.uncheckedGet(additionalIndex++);
            }
            int oldIdom = map.get(instanceIndex);
//index++;
            if (oldIdom == -1 || (oldIdom > 0 && (ignoreDirty || dirtySet.contains(oldIdom) || dirtySet.contains(instanceIndex)))) {
//processedId++;
                LongMap.Entry entry = heap.idToOffsetMap.getByIndex(instanceIndex);
                IntIterator refIt = entry.getReferences();
                int newIdomIndex = refIt.next();
                boolean dirty = false;
                
                while(refIt.hasNext() && newIdomIndex != 0) {
                    int refIndexObj = refIt.next();
                    newIdomIndex = intersect(newIdomIndex, refIndexObj, leftIdoms, rightIdoms);
                }
                if (oldIdom == -1) {
//addedBynewDirtySet.add(newDirtySet.contains(instanceId) && !dirtySet.contains(instanceId));
                    map.put(instanceIndex, newIdomIndex);
                    if (newIdomIndex != 0) newDirtySet.add(newIdomIndex);
                    changed = true;
//changedId++;
//changedIds.add(instanceId);
//changedIdx.add(index);
//oldDomIds.add(null);
//newDomIds.add(newIdomIndex);
                } else if (oldIdom != newIdomIndex) {
//addedBynewDirtySet.add((newDirtySet.contains(oldIdom) || newDirtySet.contains(instanceId)) && !(dirtySet.contains(oldIdom) || dirtySet.contains(instanceId)));
                    newDirtySet.add(oldIdom);
                    if (newIdomIndex != 0) newDirtySet.add(newIdomIndex);
                    map.put(instanceIndex,newIdomIndex);
                    if (dirtySet.size() < ADDITIONAL_IDS_THRESHOLD || dirtySetSameSize >= ADDITIONAL_IDS_THRESHOLD_DIRTYSET_SAME_SIZE) {
                        updateAdditionalIds(instanceIndex, additionalIndexes);
                    }
                    changed = true;
//changedId++;
//changedIds.add(instanceId);
//changedIdx.add(index);
//oldDomIds.add(oldIdom);
//newDomIds.add(newIdomIndex);
                }
            }
        }
        if (dirtySet.size() != newDirtySet.size()) {
            dirtySetSameSize = 0;
        } else {
            dirtySetSameSize++;
        }
//System.out.println("Processed: "+processedId);
//System.out.println("Changed:   "+changedId);
//System.out.println("-------------------");
//printObjs(changedIds,oldDomIds,newDomIds, addedBynewDirtySet, changedIdx);
//System.out.println("-------------------");
        return changed;
    }
        
    private void updateAdditionalIds(final int instanceIndex, final IntList additionalIndexes) {
        Instance i = heap.getInstanceByIndex(instanceIndex);
//System.out.println("Inspecting "+printInstance(instanceId));
        if (i != null) {
            for (FieldValue v : i.getFieldValues()) {
                if (v instanceof ObjectFieldValue) {
                    Instance val = ((ObjectFieldValue)v).getInstance();
                    if (val != null) {
                        int idp = val.getInstanceIndex();
                        int idomO = map.get(idp);
                        if (idomO > 0) {
                            additionalIndexes.add(idp);
//System.out.println("  Adding "+printInstance(idO));
                        }
                    }
                }
            }
        }
    }
    
    private void deleteBuffers() {
        multipleParents.delete();
        revertedMultipleParents.delete();
    }
        
    private int readInt() throws IOException {
        return currentMultipleParents.readInt();
    }

    int getIdomIndex(int instanceIndex, LongMap.Entry entry) {
        int idomEntry = map.get(instanceIndex);
        if (idomEntry != -1) {
            return idomEntry;
        }
        if (entry == null) {
            entry = heap.idToOffsetMap.get(instanceIndex);
        }
        return entry.getNearestGCRootPointer();
    }
    
    boolean hasInstanceInChain(int tag, Instance i) {
        if (tag == HprofHeap.PRIMITIVE_ARRAY_DUMP) {
            return false;
        }

        ClassDump javaClass = (ClassDump) i.getJavaClass();
        if (tag == HprofHeap.INSTANCE_DUMP) {
            if (canContainItself == null) {
                canContainItself = new HashMap<>(heap.getAllClasses().size()/2);
            }

            boolean canContain = canContainItself.computeIfAbsent(javaClass, ClassDump::canContainItself);
            if (!canContain) {
                return false;
            }
        }
        int instanceId = i.getInstanceIndex();
        int idom = getIdomIndex(instanceId);
        for (;idom!=0;idom=getIdomIndex(idom)) {
            Instance ip = heap.getInstanceByIndex(idom);
            JavaClass cls = ip.getJavaClass();
            
            if (javaClass.equals(cls)) {
                return true;
            }
        }
        return false;
    }

    private int getNearestGCRootPointer(Integer instanceIndexObj) {
        Integer nearestGCObj = nearestGCRootCache.get(instanceIndexObj);
        if (nearestGCObj != null) {
            return nearestGCObj;
        }
        LongMap.Entry entry = heap.idToOffsetMap.getByIndex(instanceIndexObj.intValue());
        int nearestGC = entry.getNearestGCRootPointer();
        nearestGCRootCache.put(instanceIndexObj,Integer.valueOf(nearestGC));
        return nearestGC;
    }
    
    private int getIdomIndex(int instanceIndex) {
        int idom = map.get(instanceIndex);
        
        if (idom != -1) {
            return idom;
        }
        return getNearestGCRootPointer(instanceIndex);
    }
    
    private int intersect(int idomIndex, int refIndex, FastBitSet leftIdoms, FastBitSet rightIdoms) {
        if (idomIndex == refIndex) {
            return idomIndex;
        }
        if (idomIndex == 0 || refIndex == 0) {
            return 0;
        }
        leftIdoms.clear();
        rightIdoms.clear();
        int leftIdom = idomIndex;
        int rightIdom = refIndex;

        
        leftIdoms.add(leftIdom);
        rightIdoms.add(rightIdom);
        while(true) {
            if (rightIdom == 0 && leftIdom == 0) return 0;
            if (leftIdom != 0) {
                leftIdom = getIdomIndex(leftIdom);
                if (leftIdom != 0) {
                    if (rightIdoms.contains(leftIdom)) {
                        return leftIdom;
                    }
                    leftIdoms.add(leftIdom);
                }
            }
            if (rightIdom != 0) {
                rightIdom = getIdomIndex(rightIdom);
                if (rightIdom != 0) {
                    if (leftIdoms.contains(rightIdom)) {
                        return rightIdom;
                    }
                    rightIdoms.add(rightIdom);
                }
            }
        }
    }

    private void switchParents() {
        if (currentMultipleParents == revertedMultipleParents) {
            currentMultipleParents = multipleParents;
        } else {
            currentMultipleParents = revertedMultipleParents;
        }
    }

    // debugging 
    private void printObjs(List<Long> changedIds, List<Long> oldDomIds, List<Long> newDomIds, List<Boolean> addedByDirtySet, List<Long> changedIdx) {
        if (changedIds.size()>20) return;
        TreeMap<Integer,String> m = new TreeMap<>();
        
        for (int i=0; i<changedIds.size(); i++) {
            Long iid = changedIds.get(i);
            Long oldDom = oldDomIds.get(i);
            Long newDom = newDomIds.get(i);
            Long index = changedIdx.get(i);
            Boolean addedByDirt = addedByDirtySet.get(i);
            Instance ii = heap.getInstanceByID(iid.longValue());
            int number = ii.getInstanceNumber();
            String text = "Index: "+index+(addedByDirt?" New ":" Old ")+printInstance(iid);
            
            text+=" OldDom "+printInstance(oldDom);
            text+=" NewDom: "+printInstance(newDom);
            m.put(number,text);
        }
        for (Integer in : m.keySet()) {
            System.out.println(m.get(in));
        }
    }
    
    // debugging
    String printInstance(Long instanceid) {
        if (instanceid == null || instanceid.longValue() == 0) {
            return "null";
        }
        Instance ii = heap.getInstanceByID(instanceid.longValue());
        return ii.getJavaClass().getName()+"#"+ii.getInstanceNumber();
        
    }

    //---- Serialization support
    void writeToStream(DataOutputStream out) throws IOException {
        map.writeToStream(out);
    }

    DominatorTree(HprofHeap h, DataInputStream dis) throws IOException {
        heap = h;
        map = new IntIntIndexMap(dis);
    }
    
    private static final class NearestGCRootCache<K,V> extends LinkedHashMap<K,V> {
        private final int maxSize;
        
        private NearestGCRootCache(int size) {
            super(size,0.75F,true);
            maxSize = size;
        }

        protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
            return size() > maxSize;
        }

    }

    private static final class FastBitSet {
        private static final int ADDRESS_BITS_PER_WORD = 6;

        private static final int INITIAL_CAPACITY = 2048;
        private long[] words = new long[INITIAL_CAPACITY];
        private final IntList wordIndexes = new IntList(INITIAL_CAPACITY / 64);
        private int size;

        boolean contains(int index) {
            if (index < 0) {
                return false;
            }

            int wordIndex = wordIndex(index);
            long[] words = this.words;
            return wordIndex < words.length && (words[wordIndex] & 1L << index) != 0;
        }

        void add(int index) {
            if (index < 0) {
                return;
            }

            int wordIndex = wordIndex(index);
            long[] words = this.words;

            if (wordIndex >= words.length) {
                int requiredLength = Math.max(words.length * 2, wordIndex + 1);
                words = Arrays.copyOf(words, requiredLength);
                this.words = words;
            }

            long mask = 1L << index;
            long word = words[wordIndex];

            if ((word & mask) == 0L) {
                words[wordIndex] = word | mask;
                size++;

                if (word == 0L) {
                    wordIndexes.add(wordIndex);
                }
            }
        }

        void clear() {
            if (size == 0) {
                return;
            }

            long[] words = this.words;
            IntList wordIndexes = this.wordIndexes;

            for (int i = 0, size = wordIndexes.size(); i < size; i++) {
                words[wordIndexes.uncheckedGet(i)] = 0;
            }

            wordIndexes.clear();
            size = 0;
        }

        int size() {
            return size;
        }

        private static int wordIndex(int bitIndex) {
            return bitIndex >> ADDRESS_BITS_PER_WORD;
        }
    }
}

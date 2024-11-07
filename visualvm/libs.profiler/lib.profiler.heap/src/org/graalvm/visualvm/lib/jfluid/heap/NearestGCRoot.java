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
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 *
 * @author Tomas Hurka
 */
class NearestGCRoot {
    //~ Static fields/initializers -----------------------------------------------------------------------------------------------

    private static final int BUFFER_SIZE = (64 * 1024) / 8;
    private static final int MULTI_PARENTS_BUFFER_SIZE = BUFFER_SIZE * 1024;
    private static final String[] REF_CLASSES = {
        "java.lang.ref.WeakReference",    // NOI18N
        "java.lang.ref.SoftReference",    // NOI18N
        "java.lang.ref.FinalReference",   // NOI18N
        "java.lang.ref.PhantomReference"  // NOI18N
    };
    private static final String JAVA_LANG_REF_REFERENCE = "java.lang.ref.Reference";   // NOI18N
    private static final String REFERENT_FIELD_NAME = "referent"; // NOI18N
    private static final String SVM_REFFERENCE = "com.oracle.svm.core.heap.heapImpl.DiscoverableReference";    // NOI18N
    private static final String SVM_REFFERENCE_1 = "com.oracle.svm.core.heap.DiscoverableReference";    // NOI18N
    private static final String SVM_REFERENT_FIELD_NAME = "rawReferent"; // NOI18N
    private static final int DEEP_LEVEL = 10000;

    //~ Instance fields ----------------------------------------------------------------------------------------------------------

    private Field referentField;
    private HprofHeap heap;
    private LongBuffer readBuffer;
    private LongBuffer writeBuffer;
    private IntBuffer deepPathBuffer;
    private IntBuffer leaves;
    private IntBuffer multipleParents;
    private Set<JavaClass> referenceClasses;
    private boolean gcRootsComputed;
    private long allInstances;
    private long processedInstances;
    private long level;
//private long leavesCount;
//private long firstLevel;
//private long multiParentsCount;

    //~ Constructors -------------------------------------------------------------------------------------------------------------

    NearestGCRoot(HprofHeap h) {
        heap = h;
    }

    //~ Methods ------------------------------------------------------------------------------------------------------------------

    Instance getNearestGCRootPointer(Instance instance) {
        if (heap.isGCRoot(instance)) {
            return instance;
        }
        computeGCRoots();
        int nextGCPathIndex = heap.idToOffsetMap.get(instance.getInstanceId()).getNearestGCRootPointer();
        return heap.getInstanceByIndex(nextGCPathIndex);
    }

    private boolean isSpecialReference(FieldValue value, Instance instance) {
        Field f = value.getField();

        return f.equals(referentField) && referenceClasses.contains(instance.getJavaClass());
    }

    private synchronized void computeGCRoots() {
        if (gcRootsComputed) {
            return;
        }
        HeapProgress.progressStart();
        if (!initHotSpotReference()) {
            if (!initSVMReference()) {
                throw new IllegalArgumentException("reference field not found"); // NOI18N
            }
        }
        heap.computeReferences(); // make sure references are computed first
        heap.cacheDirectory.setDirty(true);
        allInstances = heap.getSummary().getTotalLiveInstances();
        Set<JavaClass> processedClasses = new HashSet<>(heap.getAllClasses().size()*4/3);
        
        try {
            createBuffers();
            fillZeroLevel();

            do {
                switchBuffers();
                computeOneLevel(processedClasses);
            } while (hasMoreLevels());
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        deleteBuffers();
        heap.idToOffsetMap.flush();
        gcRootsComputed = true;
        heap.writeToFile();
        HeapProgress.progressFinish();
    }

    private boolean initHotSpotReference() {
        referentField = computeReferentField(JAVA_LANG_REF_REFERENCE, REFERENT_FIELD_NAME);
        if (referentField != null) {
            referenceClasses = new HashSet<>();
            for (String refClass : REF_CLASSES) {
                JavaClass ref = heap.getJavaClassByName(refClass);
                if (ref != null) {
                    referenceClasses.add(ref);
                    referenceClasses.addAll(ref.getSubClasses());
                }
            }
            return referenceClasses.size() >= REF_CLASSES.length;
        }
        return false;
    }

    private boolean initSVMReference() {
        referentField = computeReferentField(SVM_REFFERENCE, SVM_REFERENT_FIELD_NAME);
        if (referentField == null) {
            referentField = computeReferentField(SVM_REFFERENCE_1, SVM_REFERENT_FIELD_NAME);
        }
        if (referentField != null) {
            JavaClass ref = referentField.getDeclaringClass();

            referenceClasses = new HashSet<>();
            referenceClasses.add(ref);
            referenceClasses.addAll(ref.getSubClasses());
            return !referenceClasses.isEmpty();
        }
        return false;
    }

    private void computeOneLevel(Set<JavaClass> processedClasses) throws IOException {
        int idSize = heap.dumpBuffer.getIDSize();
        level++;
        for (;;) {
            Instance instance;
            long instanceOffset = readLong();
            List<FieldValue> fieldValues;
            boolean hasValues = false;
            
            if (instanceOffset == 0L) { // end of level
                break;
            }
            HeapProgress.progress(processedInstances++,allInstances);
            instance = heap.getInstanceByOffset(new long[] {instanceOffset});
            if (instance instanceof ObjectArrayInstance) {
                ObjectArrayDump array = (ObjectArrayDump) instance;
                int size = array.getLength();
                long offset = array.getOffset();
                long instanceId = instance.getInstanceId();

                for (int i=0;i<size;i++) {
                    long referenceId = heap.dumpBuffer.getID(offset + (i * idSize));

                    if (writeConnection(instanceId, referenceId)) {
                        hasValues = true;
                    }
                }
                if (!hasValues) {
                    writeLeaf(instanceId,instance.getSize());
                }
                continue;
            } else if (instance instanceof PrimitiveArrayInstance) {
                writeLeaf(instance.getInstanceId(),instance.getSize());
                continue;
            } else if (instance instanceof ClassDumpInstance) {
                ClassDump javaClass = ((ClassDumpInstance) instance).classDump;

                fieldValues = javaClass.getStaticFieldValues();
            } else if (instance instanceof InstanceDump) {
                fieldValues = instance.getFieldValues();
            } else {
                if (instance == null) {
                    System.err.println("HeapWalker Warning - null instance for " + heap.dumpBuffer.getID(instanceOffset + 1)); // NOI18N
                    continue;
                }
                throw new IllegalArgumentException("Illegal type " + instance.getClass()); // NOI18N
            }
            long instanceId = instance.getInstanceId();
            for (FieldValue val : fieldValues) {
                if (val instanceof ObjectFieldValue) {
                     // skip Soft, Weak, Final and Phantom References
                    if (!isSpecialReference(val, instance)) {
                        long refInstanceId;

                        if (val instanceof HprofFieldObjectValue) {
                            refInstanceId = ((HprofFieldObjectValue) val).getInstanceID();
                        } else {
                             refInstanceId = ((HprofInstanceObjectValue) val).getInstanceId();
                        }
                        if (writeConnection(instanceId, refInstanceId)) {
                            hasValues = true;
                        }
                    }
                }
            }
            if (writeClassConnection(processedClasses, instanceId, instance.getJavaClass())) {
                hasValues = true;
            }
            if (!hasValues) {
                writeLeaf(instanceId,instance.getSize());
            }

        }
    }

    private Field computeReferentField(String className, String fieldName) {
        JavaClass reference = heap.getJavaClassByName(className);

        if (reference != null) {
            for (Field f : reference.getFields()) {
                if (f.getName().equals(fieldName)) {
                    return f;
                }
            }
        }
        return null;
    }

    private void createBuffers() {
        readBuffer = new LongBuffer(BUFFER_SIZE, heap.cacheDirectory);
        writeBuffer = new LongBuffer(BUFFER_SIZE, heap.cacheDirectory);
        leaves = new IntBuffer(BUFFER_SIZE, heap.cacheDirectory);
        multipleParents = new IntBuffer(MULTI_PARENTS_BUFFER_SIZE, heap.cacheDirectory);
        deepPathBuffer = new IntBuffer(BUFFER_SIZE, heap.cacheDirectory);
    }

    private void deleteBuffers() {
        readBuffer.delete();
        writeBuffer.delete();
    }

    private void fillZeroLevel() throws IOException {
        for (GCRoot gcr : heap.getGCRoots()) {
            HprofGCRoot root = (HprofGCRoot)gcr;
            long id = root.getInstanceId();
            LongMap.Entry entry = heap.idToOffsetMap.get(id);
            
            if (entry != null) {
                writeLong(entry.getOffset());
            }
        }
    }

    private boolean hasMoreLevels() {
        return writeBuffer.hasData();
    }

    private long readLong() throws IOException {
        return readBuffer.readLong();
    }

    private void switchBuffers() throws IOException {
        LongBuffer b = readBuffer;
        readBuffer = writeBuffer;
        writeBuffer = b;
        readBuffer.startReading();
        writeBuffer.reset();
    }

    private boolean writeClassConnection(final Set<JavaClass> processedClasses, final long instanceId, final JavaClass jcls) throws IOException {
        if (!processedClasses.contains(jcls)) {
            long jclsId = jcls.getJavaClassId();
            
            processedClasses.add(jcls);
            if (writeConnection(instanceId, jclsId, true)) {
                return true;
            }
        }
        return false;
    }

    private boolean writeConnection(long instanceId, long refInstanceId)
                          throws IOException {
        return writeConnection(instanceId, refInstanceId, false);
    }
    
    private boolean writeConnection(long instanceId, long refInstanceId, boolean addRefInstanceId)
                          throws IOException {
        if (refInstanceId != 0) {
            LongMap.Entry entry = heap.idToOffsetMap.get(refInstanceId);

            if (entry != null && entry.getNearestGCRootPointer() == 0 && heap.gcRoots.getGCRoots(refInstanceId) == null) {
                int refInstanceIndex = entry.getListIndex();
                writeLong(entry.getOffset());
                if (level > DEEP_LEVEL) {
                    deepPathBuffer.writeInt(refInstanceIndex);
                    entry.setDeepObj();
                }

                // TODO Cache instanceIndex
                int instanceIndex = heap.idToOffsetMap.getInstanceIndexById(instanceId);

                if (addRefInstanceId) {
                    if (!checkReferences(refInstanceId, instanceId)) {
                        entry.addReference(instanceIndex);
                    }
                }
                entry.setNearestGCRootPointer(instanceIndex);
                if (!entry.hasOnlyOneReference()) {
                    multipleParents.writeInt(refInstanceIndex);
//multiParentsCount++;
                }
                return true;
            }
            return !addRefInstanceId && entry != null;
        }
        return false;
    }

    private boolean checkReferences(final long refInstanceId, final long instanceId) {
        Instance instance = heap.getInstanceByID(instanceId);        
        
        for (FieldValue field : instance.getFieldValues()) {
            if (field instanceof HprofInstanceObjectValue) {
                HprofInstanceObjectValue objectValue = (HprofInstanceObjectValue) field;

                if (objectValue.getInstanceId() == refInstanceId) {
                    return true;
                }
            }
        }
        return false;
    }

    private void writeLong(long instanceOffset) throws IOException {
        writeBuffer.writeLong(instanceOffset);
    }

    private void writeLeaf(long instanceId, long size) throws IOException {
        LongMap.Entry entry = heap.idToOffsetMap.get(instanceId);
        
        entry.setTreeObj();
        entry.setRetainedSize(size);
//leavesCount++;
        if (entry.hasOnlyOneReference()) {
            int gcRootPointer = entry.getNearestGCRootPointer();
            if (gcRootPointer != 0) {
                LongMap.Entry gcRootPointerEntry = heap.idToOffsetMap.getByIndex(gcRootPointer);
                
                if (gcRootPointerEntry.getRetainedSize() == 0) {
                    gcRootPointerEntry.setRetainedSize(-1);
                    leaves.writeInt(gcRootPointer);
//firstLevel++;
                }
            }
        }
    }

    IntBuffer getLeaves() {
        computeGCRoots();
//System.out.println("Multi par.  "+multiParentsCount);
//System.out.println("Leaves      "+leavesCount);
//System.out.println("Tree obj.   "+heap.idToOffsetMap.treeObj);
//System.out.println("First level "+firstLevel);
        return leaves;
    }

    IntBuffer getMultipleParents() {
        computeGCRoots();
        return multipleParents;
    }

    IntBuffer getDeepPathBuffer() {
        computeGCRoots();
        return deepPathBuffer;
    }

    //---- Serialization support
    void writeToStream(DataOutputStream out) throws IOException {
        out.writeBoolean(gcRootsComputed);
        if (gcRootsComputed) {
            leaves.writeToStream(out);
            multipleParents.writeToStream(out);
            deepPathBuffer.writeToStream(out);
        }
    }

    NearestGCRoot(HprofHeap h, DataInputStream dis) throws IOException {
        this(h);
        gcRootsComputed = dis.readBoolean();
        if (gcRootsComputed) {
            leaves = new IntBuffer(dis, heap.cacheDirectory);
            multipleParents = new IntBuffer(dis, heap.cacheDirectory);
            deepPathBuffer = new IntBuffer(dis, heap.cacheDirectory);
        }
    }
}

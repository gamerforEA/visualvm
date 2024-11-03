/*
 * Copyright (c) 2017, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 *
 * @author Tomas Hurka
 */
class CacheDirectory {

    private static final String DIR_EXT = ".hwcache";   // NOI18N
    private static final String DUMP_AUX_FILE = "NBProfiler.nphd";   // NOI18N
    private static final String DIRTY_FILENAME = "dirty.lck";   // NOI18N

    private File cacheDirectory;

    static CacheDirectory getHeapDumpCacheDirectory(File heapDump, int seg) {
        String dumpName = heapDump.getName();
        String suffix = seg==0 ? "" : "_"+seg;
        File parent = heapDump.getParentFile();
        File dir = new File(parent, dumpName+suffix+DIR_EXT);
        return new CacheDirectory(dir);
    }

    CacheDirectory(File cacheDir) {
        cacheDirectory = cacheDir;
        if (cacheDir != null) {
            if (!cacheDir.exists()) {
                if (!cacheDir.mkdir()) {
                    cacheDirectory = null;
                }
            }
        }
        if (cacheDirectory != null) {
            assert cacheDirectory.isDirectory() && cacheDirectory.canRead() && cacheDirectory.canWrite();
        }
    }

    File createTempFile(String prefix, String suffix) throws IOException {
        File newFile;

        if (isTemporary()) {
            newFile = File.createTempFile(prefix, suffix);
            newFile.deleteOnExit();
        } else {
            newFile = File.createTempFile(prefix, suffix, cacheDirectory);
        }
        return newFile;
    }

    File getHeapDumpAuxFile() {
        assert !isTemporary();
        return new File(cacheDirectory, DUMP_AUX_FILE);
    }

    boolean isTemporary() {
        return cacheDirectory == null;
    }

    File getCacheFile(String fileName) throws FileNotFoundException {
        File file = lookupFile(fileName, false);
        if (file.canWrite()) {
            return file;
        }
        throw new FileNotFoundException(fileName);
    }

    File getHeapFile(String fileName) throws FileNotFoundException {
        return lookupFile(fileName, true);
    }
    
    void deleteAllCachedFiles() {
        assert !isTemporary();
        for (File f : cacheDirectory.listFiles()) {
            f.delete();
        }
    }

    boolean isDirty() {
        if (isTemporary()) return true;
        File dirtyFile = new File(cacheDirectory,DIRTY_FILENAME);
        return isFileR(dirtyFile);
    }

    void setDirty(boolean dirty) {
        if (!isTemporary()) {
            File dirtyFile = new File(cacheDirectory, DIRTY_FILENAME);
            try {
                if (dirty) {
                    assert !isFileR(dirtyFile);
                    dirtyFile.createNewFile();
                } else {
                    assert isFileRW(dirtyFile);
                    dirtyFile.delete();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    HprofByteBuffer createHprofByteBuffer(File dumpFile)  throws IOException{
        return HprofByteBuffer.createHprofByteBuffer(dumpFile);
    }

    AbstractLongMap.Data createDumpBuffer(long fileSize, int entrySize) throws IOException {
        File tempFile = createTempFile("NBProfiler", ".map"); // NOI18N
        try (RandomAccessFile file = new RandomAccessFile(tempFile, "rw")) { // NOI18N
            if (Boolean.getBoolean("org.graalvm.visualvm.lib.jfluid.heap.zerofile")) {    // NOI18N
                byte[] zeros = new byte[512*1024];
                while(file.length()+zeros.length<fileSize) {
                    file.write(zeros);
                }
                file.write(zeros,0,(int)(fileSize-file.length()));
            }
            file.setLength(fileSize);
            return AbstractLongMap.getDumpBuffer(tempFile, file, entrySize);
        }
    }

    NumberList createNumberList() throws IOException {
        return new NumberList(this);
    }

    private File lookupFile(String fileName, boolean checkParent) throws FileNotFoundException {
        File f = new File(fileName);
        if (isFileR(f)) {
            return f;
        }
        // try to find file next to cache directory  or in cache directory
        File dir = checkParent ? cacheDirectory.getParentFile() : cacheDirectory;
        f = new File(dir, f.getName());
        if (isFileR(f)) {
            return f;
        }
        if (!fileName.contains(File.separator)) {
            // OS mismatch, try opposite separator
            char sep = File.separatorChar == '/' ? '\\' : '/';      // NOI18N
            int lastSep = fileName.lastIndexOf(sep);
            if (lastSep != -1) {
                f = new File(dir, fileName.substring(lastSep+1));
                if (isFileR(f)) {
                    return f;
                }
            }
        }
        throw new FileNotFoundException(fileName);
    }

    private static boolean isFileR(File f) {
        return f.exists() && f.isFile() && f.canRead();
    }
    
    private static boolean isFileRW(File f) {
        return isFileR(f) && f.canWrite();
    }

    private static boolean isLinux() {
        String osName = System.getProperty("os.name");  // NOI18N

        return osName.endsWith("Linux"); // NOI18N
    }
}

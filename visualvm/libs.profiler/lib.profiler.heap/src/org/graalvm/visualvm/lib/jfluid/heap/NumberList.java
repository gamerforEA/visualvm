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
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * @author Tomas Hurka
 */
class NumberList {

    private static final int NUMBERS_IN_BLOCK = 3;
    private static final int numberSize = Integer.BYTES;
    private static final int blockSize = (NUMBERS_IN_BLOCK + 1) * numberSize;
    private static final int MAX_BLOCK_COUNT = Integer.MAX_VALUE - 1000;

    private final File dataFile;
    private final RandomAccessFile data;
    // Map <index,block>
    private final Map<Integer,byte[]> blockCache;
    private final Set<Integer> dirtyBlocks;
    private int blocks;
    private MappedByteBuffer buf;
    private long mappedSize;
    private CacheDirectory cacheDirectory;

    NumberList(CacheDirectory cacheDir) throws IOException {
        dataFile = cacheDir.createTempFile("NBProfiler", ".ref"); // NOI18N
        data = new RandomAccessFile(dataFile, "rw"); // NOI18N
        blockCache = new BlockLRUCache<>();
        dirtyBlocks = new HashSet<>(100000);
        cacheDirectory = cacheDir;
        addBlock(); // first block is unused, since it starts at offset 0
    }
    
    protected void finalize() throws Throwable {
        if (cacheDirectory.isTemporary()) {
            dataFile.delete();
        }
        super.finalize();
    }

    int addNumber(int startIndex,int number) throws IOException {
        int slot;
        byte[] block = getBlock(startIndex);
        for (slot=0;slot<NUMBERS_IN_BLOCK;slot++) {
            int el = readNumber(block,slot);
            if (el == 0) {
                writeNumber(startIndex,block,slot,number);
                return startIndex;
            }
            if (el == number) { // number is already in the list
                return startIndex; // do nothing
            }
        }
        int nextBlock = addBlock(); // create next blok
        block = getBlock(nextBlock);
        writeNumber(nextBlock,block,slot,startIndex); // put next block in front of old block
        writeNumber(nextBlock,block,0,number); // write number to first position in the new block
        return nextBlock;
    }

    int addFirstNumber(int number1,int number2) throws IOException {
        int blockIndex = addBlock();
        byte[] block = getBlock(blockIndex);
        writeNumber(blockIndex,block,0,number1);
        writeNumber(blockIndex,block,1,number2);
        return blockIndex;
    }
    
    void putFirst(int startIndex,int number) throws IOException {
        int slot;
        int index = startIndex;
        int movedNumber = 0;
        for(;;) {
            byte[] block = getBlock(index);
            for (slot=0;slot<NUMBERS_IN_BLOCK;slot++) {
                int el = readNumber(block,slot);
                if (index == startIndex && slot == 0) { // first block
                    if (number == el) { // already first element 
                        return;
                    }
                    movedNumber = el;
                    writeNumber(index,block,slot,number);
                } else if (el == 0) { // end of the block, move to next one
                    break;
                } else if (el == number) { // number is already in the list
                    writeNumber(index,block,slot,movedNumber);    // replace number and return
                    return;
                }
            }
            index = getIndexToNextBlock(block);
            if (index == 0) {
                System.out.println("Error - number not found at end");
                return;
            }
        }
    }

    int getFirstNumber(int startIndex) throws IOException {
        byte[] block = getBlock(startIndex);
        return readNumber(block,0);
    }
    
    IntIterator getNumbersIterator(int startIndex) throws IOException {
        return new NumberIterator(startIndex);
    }

    List<Integer> getNumbers(int startIndex) throws IOException {
        int slot;
        List<Integer> numbers = new ArrayList<>();

        for(;;) {
            byte[] block = getBlock(startIndex);
            for (slot=0;slot<NUMBERS_IN_BLOCK;slot++) {
                int el = readNumber(block,slot);
                if (el == 0) {     // end of the block, move to next one
                    break;
                }
                numbers.add(new Integer(el));
            }
            int nextBlock = getIndexToNextBlock(block);
            if (nextBlock == 0) {
                return numbers;
            }
            startIndex = nextBlock;
        }
    }
    
    private void mmapData() {
        if (buf == null) {
            try {
                mappedSize = Math.min(blockSize*blocks, Integer.MAX_VALUE-blockSize+1);
                buf = data.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, mappedSize);
            } catch (IOException ex) {
                // map() failed
                mappedSize = 0;
                ex.printStackTrace();
            }
        }
    }
    
    void flush() {
        try {
            flushDirtyBlocks();
            blockCache.clear();
            mmapData();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    
    private static int getIndexToNextBlock(byte[] block) {
        return readNumber(block,NUMBERS_IN_BLOCK);
    }
    
    private static int readNumber(byte[] block,int slot) {
        int offset = slot*numberSize;
        return getInt(block, offset);
    }

    private static int getInt(byte[] buf, int i) {
        int ch1 = ((int) buf[i]) & 0xFF;
        int ch2 = ((int) buf[i + 1]) & 0xFF;
        int ch3 = ((int) buf[i + 2]) & 0xFF;
        int ch4 = ((int) buf[i + 3]) & 0xFF;

        return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4));
    }

    private synchronized void writeNumber(int blockIndex,byte[] block,int slot,int element) throws IOException {
        long blockOffset = (long) blockIndex * blockSize;
        if (blockOffset < mappedSize) {
            long offset = blockOffset+slot*numberSize;
            buf.putInt((int)offset, element);
        } else {
            int offset = slot*numberSize;
            block[offset] = (byte) (element >> 24);
            block[offset + 1] = (byte) (element >> 16);
            block[offset + 2] = (byte) (element >> 8);
            block[offset + 3] = (byte) (element);
            dirtyBlocks.add(new Integer(blockIndex));
            if (dirtyBlocks.size()>10000) {
                flushDirtyBlocks();
            }
        }
    }
    
    private synchronized byte[] getBlock(int index) throws IOException {
        long offset = (long) index * blockSize;
        byte[] block;

        if (offset < mappedSize) {
            block = new byte[blockSize];
            buf.get((int)offset, block);
            return block;
        } else {
            Integer indexObj = new Integer(index);

            block = blockCache.get(indexObj);
            if (block == null) {
                block = new byte[blockSize];
                data.seek(offset);
                data.readFully(block);
                blockCache.put(indexObj,block);
            }
            return block;
        }
    }

    private int addBlock() throws IOException {
        int index=blocks;
        if (index >= MAX_BLOCK_COUNT) {
            throw new IllegalStateException("Too many blocks");
        }

        blockCache.put(new Integer(index),new byte[blockSize]);
        blocks++;
        return index;
    }

    private void flushDirtyBlocks() throws IOException {
        if (dirtyBlocks.isEmpty()) {
            return;
        }
        Integer[] dirty=dirtyBlocks.toArray(new Integer[0]);
        Arrays.sort(dirty);
        byte blocks[] = new byte[1024*blockSize];
        int dataOffset = 0;
        long lastBlockOffset = 0;
        for (Integer blockIndexObj : dirty) {
            byte[] block = blockCache.get(blockIndexObj);
            long blockOffset = (long) blockIndexObj.intValue() * blockSize;
            if (lastBlockOffset+dataOffset==blockOffset && dataOffset <= blocks.length - blockSize) {
                System.arraycopy(block,0,blocks,dataOffset,blockSize);
                dataOffset+=blockSize;
            } else {
                data.seek(lastBlockOffset);
                data.write(blocks,0,dataOffset);
                dataOffset = 0;
                System.arraycopy(block,0,blocks,dataOffset,blockSize);
                dataOffset+=blockSize;                
                lastBlockOffset = blockOffset;
            }
        }
        data.seek(lastBlockOffset);
        data.write(blocks,0,dataOffset);
        dirtyBlocks.clear();
    }

    //---- Serialization support
    void writeToStream(DataOutputStream out) throws IOException {
        out.writeUTF(dataFile.getAbsolutePath());
        out.writeInt(blocks);
        out.writeBoolean(buf != null);        
    }

    NumberList(DataInputStream dis, CacheDirectory cacheDir) throws IOException {
        boolean mmaped;
        
        cacheDirectory = cacheDir;
        dataFile = cacheDirectory.getCacheFile(dis.readUTF());
        data = new RandomAccessFile(dataFile, "rw"); // NOI18N
        blocks = dis.readInt();
        mmaped = dis.readBoolean();
        blockCache = new BlockLRUCache<>();
        dirtyBlocks = new HashSet<>(100000);
        if (mmaped) {
            mmapData();
        }
    }
    
    private class NumberIterator extends IntIterator {
        private int slot;
        private byte[] block;
        private int nextNumber;

        private NumberIterator(int startIndex) throws IOException {
            slot = 0;
            block = getBlock(startIndex);
            nextNumber();
        }

        @Override
        boolean hasNext() {
            return nextNumber != 0;
        }

        @Override
        int next() {
            if (hasNext()) {
                int num = nextNumber;
                try {
                    nextNumber();
                } catch (IOException ex) {
                    ex.printStackTrace();
                    nextNumber = 0;
                }
                return num;
            }
            throw new NoSuchElementException();
        }

        private void nextNumber() throws IOException {
            if (slot < NUMBERS_IN_BLOCK) {
                int nextNum = readNumber(block,slot++);
                if (nextNum == 0) {     // end of the block, move to next one
                    nextBlock();
                } else {
                    nextNumber = nextNum;
                }
            } else {
               nextBlock();
            }
        }

        private void nextBlock() throws IOException {
            int nextBlock = getIndexToNextBlock(block);

            if (nextBlock == 0) { // end of list
                nextNumber = 0;
                return;
            }
            block = getBlock(nextBlock);
            slot = 0;
            nextNumber();
        }
    }

    private class BlockLRUCache<K,V> extends LinkedHashMap<K,V> {
        
        private static final int MAX_CAPACITY = 10000;
        
        private BlockLRUCache() {
            super(MAX_CAPACITY,0.75f,true);
        }

        protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
            if (size()>MAX_CAPACITY) {
                K key = eldest.getKey();
                if (!dirtyBlocks.contains(key)) {
                    return true;
                }
                get(key);
            }
            return false;
        }

    }
}

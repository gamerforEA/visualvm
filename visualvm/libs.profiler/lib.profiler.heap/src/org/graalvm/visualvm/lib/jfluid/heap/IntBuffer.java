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

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;


/**
 * IntBuffer is a special kind of buffer for storing ints. It uses array of ints if there is only few ints
 * stored, otherwise ints are saved to backing temporary file.
 *
 * @author Tomas Hurka
 */
class IntBuffer {
    //~ Instance fields ----------------------------------------------------------------------------------------------------------

    private final CacheDirectory cacheDirectory;
    // TODO Merge buffers
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;
    private File backingFile;
    private FileChannel fileChannel;
    private boolean readStreamClosed;
    private int ints;

    //~ Constructors -------------------------------------------------------------------------------------------------------------

    IntBuffer(int size, CacheDirectory cacheDir) {
        this.readBuffer = allocateBuffer(size * Integer.BYTES);
        this.readBuffer.flip();
        this.writeBuffer = allocateBuffer(size * Integer.BYTES);
        this.cacheDirectory = cacheDir;
    }

    //~ Methods ------------------------------------------------------------------------------------------------------------------

    void delete() {
        this.resetBuffers();

        if (this.backingFile != null) {
            //noinspection EmptyTryBlock
            try (FileChannel _ = this.fileChannel) {
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                this.fileChannel = null;
            }

            this.backingFile.delete();
            this.backingFile = null;
        }
    }

    boolean hasData() {
        return this.ints > 0;
    }

    int readInt() throws IOException {
        ByteBuffer buf = this.readBuffer;
        if (buf.hasRemaining()) {
            return buf.getInt();
        }

        if (!this.readStreamClosed && this.fileChannel != null) {
            buf.clear();

            while (buf.hasRemaining()) {
                int n = this.fileChannel.read(buf);

                if (n == -1) {
                    this.readStreamClosed = true;
                    break;
                }
            }

            buf.flip();
            if (buf.hasRemaining()) {
                return buf.getInt();
            }
        }

        return 0;
    }

    void reset() throws IOException {
        this.resetBuffers();

        if (this.fileChannel != null) {
            this.fileChannel.truncate(0);
        }
    }

    private void resetBuffers() {
        this.readBuffer.clear().flip();
        this.writeBuffer.clear();
        this.ints = 0;
    }

    void startReading() throws IOException {
        this.readStreamClosed = true;

        if (this.fileChannel == null && this.backingFile != null && this.backingFile.isFile()) {
            try {
                this.fileChannel = FileChannel.open(this.backingFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        ByteBuffer readBuf = this.readBuffer;
        readBuf.clear();

        if (this.fileChannel != null) {
            this.flush();
            this.fileChannel.position(0);
            this.readStreamClosed = false;
        } else {
            ByteBuffer writeBuf = this.writeBuffer;
            writeBuf.flip();
            readBuf.put(writeBuf);
            writeBuf.clear();
        }

        readBuf.flip();
    }

    void writeInt(int data) throws IOException {
        ByteBuffer buf = this.writeBuffer;

        if (buf.hasRemaining()) {
            buf.putInt(data);
            this.ints++;
            return;
        }

        if (this.backingFile == null) {
            this.backingFile = this.cacheDirectory.createTempFile("NBProfiler", ".gc"); // NOI18N
        }

        if (this.fileChannel == null) {
            this.fileChannel = FileChannel.open(this.backingFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        }

        this.flush();
        buf.putInt(data);
        this.ints++;
    }

    private void flush() throws IOException {
        ByteBuffer buf = this.writeBuffer;
        buf.flip();

        while (buf.hasRemaining()) {
            this.fileChannel.write(buf);
        }

        buf.clear();
    }

    IntBuffer revertBuffer() throws IOException {
        ByteBuffer buf = this.writeBuffer;
        IntBuffer reverted = new IntBuffer(buf.capacity() / Integer.BYTES, this.cacheDirectory);

        reverseCopy(buf, buf.position(), reverted);

        if (this.fileChannel != null) {
            this.reverseCopy(this.fileChannel, reverted);
        } else {
            try (FileChannel channel = FileChannel.open(this.backingFile.toPath())) {
                this.reverseCopy(channel, reverted);
            }
        }

        reverted.startReading();
        return reverted;
    }

    private void reverseCopy(FileChannel src, IntBuffer dst) throws IOException {
        long size = src.size();
        if (size == 0) {
            return;
        }

        if (size % Integer.BYTES != 0) {
            throw new IOException(this.backingFile + " size (" + size + ") is not divisible by " + Integer.BYTES);
        }

        ByteBuffer buf = allocateBuffer((int) Math.min(size, this.readBuffer.capacity()));

        for (long to = size, from = to - buf.capacity(); from >= 0; ) {
            buf.clear().limit((int) (to - from));

            for (long position = from; buf.hasRemaining(); ) {
                int n = src.read(buf, position);
                if (n == -1) {
                    throw new EOFException("Unexpected end of file " + this.backingFile);
                }

                position += n;
            }

            buf.flip();
            reverseCopy(buf, buf.limit(), dst);

            to = from;

            if (from >= buf.capacity()) {
                from -= buf.capacity();
            } else if (from > 0) {
                from = 0;
            } else {
                break;
            }
        }
    }

    private static void reverseCopy(ByteBuffer src, int srcLimit, IntBuffer dst) throws IOException {
        for (int bufOffset = srcLimit - Integer.BYTES; bufOffset >= 0; bufOffset -= Integer.BYTES) {
            dst.writeInt(src.getInt(bufOffset));
        }
    }

    int getSize() {
        return this.ints;
    }

    // serialization support
    void writeToStream(DataOutputStream out) throws IOException {
        out.writeInt(this.ints);

        ByteBuffer writeBuf = this.writeBuffer;
        out.writeInt(writeBuf.capacity());

        int writeContentSize = writeBuf.position();
        out.writeInt(writeContentSize);

        if (writeContentSize > 0) {
            byte[] content = new byte[writeContentSize];
            writeBuf.position(0);
            writeBuf.get(content);
            writeBuf.position(writeContentSize);
            out.write(content);
        }

        out.writeUTF(this.backingFile != null ? this.backingFile.getAbsolutePath() : "");
    }

    IntBuffer(DataInputStream dis, CacheDirectory cacheDir) throws IOException {
        this.ints = dis.readInt();

        int bufferCapacity = dis.readInt();
        this.readBuffer = allocateBuffer(bufferCapacity);
        this.readBuffer.flip();
        this.writeBuffer = allocateBuffer(bufferCapacity);

        int writeContentSize = dis.readInt();

        if (writeContentSize > 0) {
            byte[] content = new byte[writeContentSize];
            dis.readFully(content);
            this.writeBuffer.put(content);
        }

        String backingFilePath = dis.readUTF();
        this.backingFile = backingFilePath.isEmpty() ? null : cacheDir.getCacheFile(dis.readUTF());
        this.cacheDirectory = cacheDir;
    }

    private static ByteBuffer allocateBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
    }
}

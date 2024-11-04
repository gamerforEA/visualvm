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
import java.nio.channels.FileChannel;


/**
 * IntBuffer is a special kind of buffer for storing ints. It uses array of ints if there is only few ints
 * stored, otherwise ints are saved to backing temporary file.
 * @author Tomas Hurka
 */
class IntBuffer {
    //~ Instance fields ----------------------------------------------------------------------------------------------------------

    private DataInputStream readStream;
    private boolean readStreamClosed;
    private DataOutputStream writeStream;
    private File backingFile;
    private int[] buffer;
    private boolean useBackingFile;
    private int bufferSize;
    private int readOffset;
    private int ints;
    private CacheDirectory cacheDirectory;

    //~ Constructors -------------------------------------------------------------------------------------------------------------

    IntBuffer(int size, CacheDirectory cacheDir) {
        buffer = new int[size];
        cacheDirectory = cacheDir;
    }

    //~ Methods ------------------------------------------------------------------------------------------------------------------

    void delete() {
        if (backingFile != null) {
            assert writeStream == null;
            assert readStreamClosed || readStream == null;
            backingFile.delete();
            useBackingFile = false;
            backingFile = null;
            ints = 0;
        }
    }

    boolean hasData() {
        return ints > 0;
    }

    int readInt() throws IOException {
        if (!useBackingFile) {
            if (readOffset < bufferSize) {
                return buffer[readOffset++];
            } else {
                return 0;
            }
        }
        if (readStreamClosed) {
            return 0;
        }
        try {
            return readStream.readInt();
        } catch (EOFException ex) {
            readStreamClosed = true;
            readStream.close();
            return 0;
        }
    }

    void reset() throws IOException {
        bufferSize = 0;
        if (writeStream != null) {
            writeStream.close();
        }
        if (readStream != null) {
            readStream.close();
        }
        writeStream = null;
        readStream = null;
        readStreamClosed = false;
        ints = 0;
        useBackingFile = false;
        readOffset = 0;
    }

    void startReading() throws IOException {
        if (writeStream != null) {
            writeStream.close();
        }

        writeStream = null;
        rewind();
    }

    void rewind() {
        readOffset = 0;

        if (useBackingFile) {
            try {
                if (readStream != null) {
                    readStream.close();
                }
                readStream = new DataInputStream(new BufferedInputStream(new FileInputStream(backingFile), buffer.length * Integer.BYTES));
                readStreamClosed = false;
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    void writeInt(int data) throws IOException {
        ints++;
        if (bufferSize < buffer.length) {
            buffer[bufferSize++] = data;
            return;
        }

        if (backingFile == null) {
            backingFile = cacheDirectory.createTempFile("NBProfiler", ".gc"); // NOI18N
        }

        if (writeStream == null) {
            writeStream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(backingFile), buffer.length * Integer.BYTES));

            for (int i = 0; i < buffer.length; i++) {
                writeStream.writeInt(buffer[i]);
            }

            useBackingFile = true;
        }

        writeStream.writeInt(data);
    }

    IntBuffer revertBuffer() throws IOException {
        IntBuffer reverted = new IntBuffer(buffer.length, cacheDirectory);

        if (bufferSize < buffer.length) {
            for (int i=0;i<bufferSize;i++) {
                reverted.writeInt(buffer[bufferSize - 1 - i]);
            }
        } else {
            if (writeStream != null) writeStream.flush();

            transfer:
            try (FileChannel channel = FileChannel.open(backingFile.toPath())) {
                long size = channel.size();
                if (size == 0) {
                    break transfer;
                }

                if (size % Integer.BYTES != 0) {
                    throw new IOException(backingFile + " size (" + size + ") is not divisible by " + Integer.BYTES);
                }

                ByteBuffer buf = ByteBuffer.allocateDirect((int) Math.min(size, (long) buffer.length * Integer.BYTES));

                for (long to = size, from = to - buf.capacity(); from >= 0;) {
                    channel.position(from);
                    buf.clear().limit((int) (to - from));

                    while (buf.hasRemaining()) {
                        int n = channel.read(buf);
                        if (n == -1) {
                            throw new EOFException("Unexpected end of file " + backingFile);
                        }
                    }

                    for (int bufOffset = buf.limit() - Integer.BYTES; bufOffset >= 0; bufOffset -= Integer.BYTES) {
                        reverted.writeInt(buf.getInt(bufOffset));
                    }

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
        }
        reverted.startReading();
        return reverted;
    }

    int getSize() {
        return ints;
    }

    // serialization support
    void writeToStream(DataOutputStream out) throws IOException {
        out.writeInt(bufferSize);
        out.writeInt(readOffset);
        out.writeInt(ints);
        out.writeInt(buffer.length);
        out.writeBoolean(useBackingFile);
        if (useBackingFile) {
            if (writeStream != null) writeStream.flush();
            out.writeUTF(backingFile.getAbsolutePath());
        } else {
            for (int i=0; i<bufferSize; i++) {
                out.writeInt(buffer[i]);
            }
        }
    }

    IntBuffer(DataInputStream dis, CacheDirectory cacheDir) throws IOException {
        bufferSize = dis.readInt();
        readOffset = dis.readInt();
        ints = dis.readInt();
        buffer = new int[dis.readInt()];
        useBackingFile = dis.readBoolean();
        if (useBackingFile) {
            backingFile = cacheDir.getCacheFile(dis.readUTF());
        } else {
            for (int i=0; i<bufferSize; i++) {
                buffer[i] = dis.readInt();
            }
        }
        cacheDirectory = cacheDir;
    } 
}

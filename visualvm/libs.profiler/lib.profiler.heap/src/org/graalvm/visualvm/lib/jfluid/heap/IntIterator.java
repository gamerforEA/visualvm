/*
 * Copyright (c) 2017, 2021, Oracle and/or its affiliates. All rights reserved.
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

import java.util.NoSuchElementException;

/**
 *
 * @author Tomas Hurka
 */
abstract class IntIterator {

    static final IntIterator EMPTY_ITERATOR = new Empty();

    static IntIterator singleton(int i) {
        return new Singleton(i);
    }

    abstract boolean hasNext();

    abstract int next();

    private static class Empty extends IntIterator {

        @Override
        boolean hasNext() {
            return false;
        }

        @Override
        int next() {
            throw new NoSuchElementException();
        }
    }

    private static class Singleton extends IntIterator {

        private final int item;
        private boolean skipped;

        private Singleton(int i) {
            item = i;
        }

        @Override
        boolean hasNext() {
            return !skipped;
        }

        @Override
        int next() {
            if (hasNext()) {
                skipped = true;
                return item;
            }
            throw new NoSuchElementException();
        }
    }
}

/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.netlink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Utility class for reversing bytes of big-endian ordered values on
 *  little-endian systems. On big-endian systems, no reversing is done. At
 *  startup, a single instance is selected by checking the return value of
 *  ByteOrder.nativeOrder() for the current system. */
public interface BytesUtil {

    /** Reverses the bytes of the given short if the system order is LE.
     *  otherwise does nothing and return the same value. */
    short reverseBE(short value);

    /** Reverses the bytes of the given short if the system order is LE.
     *  otherwise does nothing and return the same value. */
    int reverseBE(int value);

    /** Reverses the bytes of the given short if the system order is LE.
     *  otherwise does nothing and return the same value. */
    long reverseBE(long value);

    /** Writes an array of int into a ByteBuffer, also reversing the ints bytes
     *  if the system order is LE. */
    void writeBEIntsInto(ByteBuffer receiver, int[] source);

    /** Fills an array with ints read from a ByteBuffer, also reversing the ints
     *  bytes if the system order is LE. */
    void readBEIntsFrom(ByteBuffer source, int[] receiver);

    /** Allocate a ByteBuffer backed by a java array, with system order. */
    ByteBuffer allocate(int nBytes);

    /** Allocate a ByteBuffer backed by raw memory, with system order. */
    ByteBuffer allocateDirect(int nBytes);

    /** Allocate a ByteBuffer backed by another ByteBuffer, with system order. */
    ByteBuffer sliceOf(ByteBuffer source);

    /** Wrap an existing array in a ByteBuffer, with system order. */
    ByteBuffer wrap(byte[] arr);

    BytesUtil instance = Implementations.systemReverser();

    static final class Implementations {
        private Implementations() { }

        public static BytesUtil systemReverser() {
            if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
                return leReverser;
            } else {
                return beReverser;
            }
        }

        public final static BytesUtil leReverser = new BytesUtil() {

            public short reverseBE(short value) {
                return Short.reverseBytes(value);
            }

            public int reverseBE(int value) {
                return Integer.reverseBytes(value);
            }

            public long reverseBE(long value) {
                return Long.reverseBytes(value);
            }

            public void writeBEIntsInto(ByteBuffer receiver, int[] source) {
                for (int i = 0; i < source.length; i++) {
                    receiver.putInt(Integer.reverseBytes(source[i]));
                }
            }

            public void readBEIntsFrom(ByteBuffer source, int[] receiver) {
                for (int i = 0; i < receiver.length; i++) {
                    receiver[i] = Integer.reverseBytes(source.getInt());
                }
            }

            public ByteBuffer allocate(int nBytes) {
                return ByteBuffer.allocate(nBytes)
                                 .order(ByteOrder.LITTLE_ENDIAN);
            }

            public ByteBuffer allocateDirect(int nBytes) {
                return ByteBuffer.allocateDirect(nBytes)
                                 .order(ByteOrder.LITTLE_ENDIAN);
            }

            public ByteBuffer sliceOf(ByteBuffer source) {
                return source.slice().order(ByteOrder.LITTLE_ENDIAN);
            }

            public ByteBuffer wrap(byte[] arr) {
                return ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN);
            }
        };

        public final static BytesUtil beReverser = new BytesUtil() {

            public short reverseBE(short value) {
                return value;
            }

            public int reverseBE(int value) {
                return value;
            }

            public long reverseBE(long value) {
                return value;
            }

            public void writeBEIntsInto(ByteBuffer receiver, int[] source) {
                for (int i = 0; i < source.length; i++) {
                    receiver.putInt(source[i]);
                }
            }

            public void readBEIntsFrom(ByteBuffer source, int[] receiver) {
                for (int i = 0; i < receiver.length; i++) {
                    receiver[i] = source.getInt();
                }
            }

            public ByteBuffer allocate(int nBytes) {
                return ByteBuffer.allocate(nBytes);
            }

            public ByteBuffer allocateDirect(int nBytes) {
                return ByteBuffer.allocateDirect(nBytes);
            }

            public ByteBuffer sliceOf(ByteBuffer source) {
                return source.slice();
            }

            public ByteBuffer wrap(byte[] arr) {
                return ByteBuffer.wrap(arr);
            }
        };
    }
}

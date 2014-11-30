/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.lang.io;

import net.openhft.lang.io.serialization.BytesMarshallerFactory;
import net.openhft.lang.io.serialization.ObjectSerializer;
import org.jetbrains.annotations.NotNull;
import sun.misc.Unsafe;

import java.io.EOFException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author peter.lawrey
 */
public class NativeBytes extends AbstractBytes {
    /**
     * *** Access the Unsafe class *****
     */
    @NotNull
    @SuppressWarnings("ALL")
    public static final Unsafe UNSAFE;
    protected static final long NO_PAGE;
    static final int BYTES_OFFSET;
    static final int CHARS_OFFSET;

    static {
        try {
            @SuppressWarnings("ALL")
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);
            BYTES_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
            CHARS_OFFSET = UNSAFE.arrayBaseOffset(char[].class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        NO_PAGE = UNSAFE.allocateMemory(UNSAFE.pageSize());
    }

    protected long startAddr;
    protected long positionAddr;
    protected long limitAddr;
    protected long capacityAddr;

    public NativeBytes(long startAddr, long capacityAddr) {
        super();
        assert checkSingleThread();
        this.positionAddr =
                this.startAddr = startAddr;
        this.limitAddr =
                this.capacityAddr = capacityAddr;
    }

    /**
     * @deprecated Use {@link #NativeBytes(ObjectSerializer, long, long, AtomicInteger)} instead
     */
    @Deprecated
    public NativeBytes(BytesMarshallerFactory bytesMarshallerFactory,
                       long startAddr, long capacityAddr, AtomicInteger refCount) {
        super(bytesMarshallerFactory, refCount);
        assert checkSingleThread();
        this.positionAddr =
                this.startAddr = startAddr;
        this.limitAddr =
                this.capacityAddr = capacityAddr;
    }

    public NativeBytes(ObjectSerializer objectSerializer,
                       long startAddr, long capacityAddr, AtomicInteger refCount) {
        super(objectSerializer, refCount);
        assert checkSingleThread();
        this.positionAddr =
                this.startAddr = startAddr;
        this.limitAddr =
                this.capacityAddr = capacityAddr;
    }

    public NativeBytes(NativeBytes bytes) {
        super(bytes.objectSerializer(), new AtomicInteger(1));
        this.startAddr = bytes.startAddr;
        assert checkSingleThread();
        this.positionAddr = bytes.positionAddr;
        this.limitAddr = bytes.limitAddr;
        this.capacityAddr = bytes.capacityAddr;
    }

    public static long longHash(byte[] bytes, int off, int len) {
        long hash = 0;
        int pos = 0;
        for (; pos < len - 7; pos += 8)
            hash = hash * 10191 + UNSAFE.getLong(bytes, (long) BYTES_OFFSET + off + pos);
        for (; pos < len; pos++)
            hash = hash * 57 + bytes[off + pos];
        return hash;
    }

    @Override
    public NativeBytes slice() {
        return new NativeBytes(objectSerializer(), positionAddr, limitAddr, refCount);
    }

    @Override
    public NativeBytes slice(long offset, long length) {
        long sliceStart = positionAddr + offset;
        assert sliceStart >= startAddr && sliceStart < capacityAddr;
        long sliceEnd = sliceStart + length;
        assert sliceEnd > sliceStart && sliceEnd <= capacityAddr;
        return new NativeBytes(objectSerializer(), sliceStart, sliceEnd, refCount);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        long subStart = positionAddr + start;
        if (subStart < positionAddr || subStart > limitAddr)
            throw new IndexOutOfBoundsException();
        long subEnd = positionAddr + end;
        if (subEnd < subStart || subEnd > limitAddr)
            throw new IndexOutOfBoundsException();
        if (start == end)
            return "";
        return new NativeBytes(objectSerializer(), subStart, subEnd, refCount);
    }

    @Override
    public NativeBytes bytes() {
        return new NativeBytes(objectSerializer(), startAddr, capacityAddr, refCount);
    }

    @Override
    public NativeBytes bytes(long offset, long length) {
        long sliceStart = startAddr + offset;
        assert sliceStart >= startAddr && sliceStart < capacityAddr;
        long sliceEnd = sliceStart + length;
        assert sliceEnd > sliceStart && sliceEnd <= capacityAddr;
        return new NativeBytes(objectSerializer(), sliceStart, sliceEnd, refCount);
    }

    @Override
    public long address() {
        return startAddr;
    }

    @Override
    public Bytes zeroOut() {
        clear();
        UNSAFE.setMemory(startAddr, capacity(), (byte) 0);
        return this;
    }

    @Override
    public Bytes zeroOut(long start, long end) {
        if (start < 0 || end > limit())
            throw new IllegalArgumentException("start: " + start + ", end: " + end);
        if (start >= end)
            return this;
        UNSAFE.setMemory(startAddr + start, end - start, (byte) 0);
        return this;
    }

    @Override
    public Bytes zeroOut(long start, long end, boolean ifNotZero) {
        return ifNotZero ? zeroOutDirty(start, end) : zeroOut(start, end);
    }

    private Bytes zeroOutDirty(long start, long end) {
        if (start < 0 || end > limit())
            throw new IllegalArgumentException("start: " + start + ", end: " + end);
        if (start >= end)
            return this;
        // get unaligned leading bytes
        while(start < end && (start & 7) != 0) {
            byte b = UNSAFE.getByte(startAddr + start);
            if (b != 0)
                UNSAFE.putByte(startAddr + start, (byte) 0);
            start++;
        }
        // check 64-bit aligned access
        while(start < end-7) {
            long l = UNSAFE.getLong(startAddr + start);
            if (l != 0)
                UNSAFE.putLong(startAddr + start, 0L);
            start++;
        }
        // check unaligned tail
        while(start < end) {
            byte b = UNSAFE.getByte(startAddr + start);
            if (b != 0)
                UNSAFE.putByte(startAddr + start, (byte) 0);
            start++;
        }
        return this;
    }

    @Override
    public int read(@NotNull byte[] bytes, int off, int len) {
        if (len < 0 || off < 0 || off + len > bytes.length)
            throw new IllegalArgumentException();
        long left = remaining();
        if (left <= 0) return -1;
        int len2 = (int) Math.min(len, left);
        UNSAFE.copyMemory(null, positionAddr, bytes, BYTES_OFFSET + off, len2);
        addPosition(len2);
        return len2;
    }

    @Override
    public byte readByte() {
        byte aByte = UNSAFE.getByte(positionAddr);
        addPosition(1);
        return aByte;
    }

    @Override
    public byte readByte(long offset) {
        return UNSAFE.getByte(startAddr + offset);
    }

    @Override
    public void readFully(@NotNull byte[] b, int off, int len) {
        checkArrayOffs(b.length, off, len);
        long left = remaining();
        if (left < len)
            throw new IllegalStateException(new EOFException());
        UNSAFE.copyMemory(null, positionAddr, b, BYTES_OFFSET + off, len);
        addPosition(len);
    }

    @Override
    public void readFully(@NotNull char[] data, int off, int len) {
        checkArrayOffs(data.length, off, len);
        long bytesOff = off * 2L;
        long bytesLen = len * 2L;
        long left = remaining();
        if (left < bytesLen)
            throw new IllegalStateException(new EOFException());
        UNSAFE.copyMemory(null, positionAddr, data, BYTES_OFFSET + bytesOff, bytesLen);
        addPosition(bytesLen);
    }

    @Override
    public short readShort() {
        short s = UNSAFE.getShort(positionAddr);
        addPosition(2);
        return s;
    }

    @Override
    public short readShort(long offset) {
        return UNSAFE.getShort(startAddr + offset);
    }

    @Override
    public char readChar() {
        char ch = UNSAFE.getChar(positionAddr);
        addPosition(2);
        return ch;
    }

    @Override
    public char readChar(long offset) {
        return UNSAFE.getChar(startAddr + offset);
    }

    @Override
    public int readInt() {
        int i = UNSAFE.getInt(positionAddr);
        addPosition(4);
        return i;
    }

    @Override
    public int readInt(long offset) {
        return UNSAFE.getInt(startAddr + offset);
    }

    @Override
    public int readVolatileInt() {
        int i = UNSAFE.getIntVolatile(null, positionAddr);
        addPosition(4);
        return i;
    }

    @Override
    public int readVolatileInt(long offset) {
        return UNSAFE.getIntVolatile(null, startAddr + offset);
    }

    @Override
    public long readLong() {
        long l = UNSAFE.getLong(positionAddr);
        addPosition(8);
        return l;
    }

    @Override
    public long readLong(long offset) {
        return UNSAFE.getLong(startAddr + offset);
    }

    @Override
    public long readVolatileLong() {
        long l = UNSAFE.getLongVolatile(null, positionAddr);
        addPosition(8);
        return l;
    }

    @Override
    public long readVolatileLong(long offset) {
        return UNSAFE.getLongVolatile(null, startAddr + offset);
    }

    @Override
    public float readFloat() {
        float f = UNSAFE.getFloat(positionAddr);
        addPosition(4);
        return f;
    }

    @Override
    public float readFloat(long offset) {
        return UNSAFE.getFloat(startAddr + offset);
    }

    @Override
    public double readDouble() {
        double d = UNSAFE.getDouble(positionAddr);
        addPosition(8);
        return d;
    }

    @Override
    public double readDouble(long offset) {
        return UNSAFE.getDouble(startAddr + offset);
    }

    @Override
    public void write(int b) {
        UNSAFE.putByte(positionAddr, (byte) b);
        addPosition(1);
    }

    @Override
    public void writeByte(long offset, int b) {
        UNSAFE.putByte(startAddr + offset, (byte) b);
    }

    @Override
    public void write(long offset, @NotNull byte[] bytes) {
        if (offset < 0 || offset + bytes.length > capacity())
            throw new IllegalArgumentException();
        UNSAFE.copyMemory(bytes, BYTES_OFFSET, null, startAddr + offset, bytes.length);
        addPosition(bytes.length);
    }

    @Override
    public void write(byte[] bytes, int off, int len) {
        if (off < 0 || off + len > bytes.length || len > remaining())
            throw new IllegalArgumentException();
        UNSAFE.copyMemory(bytes, BYTES_OFFSET + off, null, positionAddr, len);
        addPosition(len);
    }

    @Override
    public void writeShort(int v) {
        UNSAFE.putShort(positionAddr, (short) v);
        addPosition(2);
    }

    @Override
    public void writeShort(long offset, int v) {
        UNSAFE.putShort(startAddr + offset, (short) v);
    }

    @Override
    public void writeChar(int v) {
        UNSAFE.putChar(positionAddr, (char) v);
        addPosition(2);
    }

    Thread singleThread = null;

    boolean checkSingleThread() {
        Thread t = Thread.currentThread();
        if (singleThread == null)
            singleThread = t;
        if (singleThread != t)
            throw new IllegalStateException("Altered by thread " + singleThread + " and " + t);
        return true;
    }

    void addPosition(long delta) {
        positionAddr(positionAddr() + delta);
    }

    @Override
    public void writeChar(long offset, int v) {
        UNSAFE.putChar(startAddr + offset, (char) v);
    }

    @Override
    public void writeInt(int v) {
        UNSAFE.putInt(positionAddr, v);
        addPosition(4);
    }

    @Override
    public void writeInt(long offset, int v) {
        UNSAFE.putInt(startAddr + offset, v);
    }

    @Override
    public void writeOrderedInt(int v) {
        UNSAFE.putOrderedInt(null, positionAddr, v);
        addPosition(4);
    }

    @Override
    public void writeOrderedInt(long offset, int v) {
        UNSAFE.putOrderedInt(null, startAddr + offset, v);
    }

    @Override
    public boolean compareAndSwapInt(long offset, int expected, int x) {
        return UNSAFE.compareAndSwapInt(null, startAddr + offset, expected, x);
    }

    @Override
    public void writeLong(long v) {
        UNSAFE.putLong(positionAddr, v);
        addPosition(8);
    }

    @Override
    public void writeLong(long offset, long v) {
        UNSAFE.putLong(startAddr + offset, v);
    }

    @Override
    public void writeOrderedLong(long v) {
        UNSAFE.putOrderedLong(null, positionAddr, v);
        addPosition(8);
    }

    @Override
    public void writeOrderedLong(long offset, long v) {
        UNSAFE.putOrderedLong(null, startAddr + offset, v);
    }

    @Override
    public boolean compareAndSwapLong(long offset, long expected, long x) {
        return UNSAFE.compareAndSwapLong(null, startAddr + offset, expected, x);
    }

    @Override
    public void writeFloat(float v) {
        UNSAFE.putFloat(positionAddr, v);
        addPosition(4);
    }

    @Override
    public void writeFloat(long offset, float v) {
        UNSAFE.putFloat(startAddr + offset, v);
    }

    @Override
    public void writeDouble(double v) {
        UNSAFE.putDouble(positionAddr, v);
        addPosition(8);
    }

    @Override
    public void writeDouble(long offset, double v) {
        UNSAFE.putDouble(startAddr + offset, v);
    }

    @Override
    public void readObject(Object object, int start, int end) {
        int len = end - start;
        if (positionAddr + len >= limitAddr)
            throw new IndexOutOfBoundsException("Length out of bounds len: "+len);
        assert checkSingleThread();
        for (; len >= 8; len -= 8) {
            UNSAFE.putLong(object, (long) start, UNSAFE.getLong(positionAddr));
            positionAddr += 8;
            start += 8;
        }
        for (; len > 0; len--) {
            UNSAFE.putByte(object, (long) start, UNSAFE.getByte(positionAddr));
            positionAddr++;
            start++;
        }
    }

    @Override
    public void writeObject(Object object, int start, int end) {
        int len = end - start;
        assert checkSingleThread();
        for (; len >= 8; len -= 8) {
            UNSAFE.putLong(positionAddr, UNSAFE.getLong(object, (long) start));
            positionAddr += 8;
            start += 8;
        }
        for (; len > 0; len--) {
            UNSAFE.putByte(positionAddr, UNSAFE.getByte(object, (long) start));
            positionAddr++;
            start++;
        }
    }

    public boolean startsWith(RandomDataInput input) {
        long inputRemaining = input.remaining();
        if ((limitAddr - positionAddr) < inputRemaining) return false;
        long pos = position(), inputPos = input.position();

        int i = 0;
        for (; i < inputRemaining - 7; i += 8) {
            if (UNSAFE.getLong(startAddr + pos + i) != input.readLong(inputPos + i))
                return false;
        }
        for (; i < inputRemaining - 1; i += 2) {
            if (UNSAFE.getShort(startAddr + pos + i) != input.readShort(inputPos + i))
                return false;
        }
        if (i < inputRemaining) {
            if (UNSAFE.getByte(startAddr + pos + i) != input.readByte(inputPos + i))
                return false;
        }
        return true;
    }

    @Override
    public long position() {
        return (positionAddr - startAddr);
    }

    @Override
    public NativeBytes position(long position) {
        if (position < 0 || position > limit())
            throw new IllegalArgumentException("position: " + position + " limit: " + limit());

        assert checkSingleThread();
        this.positionAddr = startAddr + position;
        return this;
    }

    /*
     * Same as position(long) except it doesn't check for thread safety.
     */
    public NativeBytes lazyPosition(long position) {
        if (position < 0 || position > limit())
            throw new IllegalArgumentException("position: " + position + " limit: " + limit());
        this.positionAddr = startAddr + position;
        return this;
    }

    @Override
    public long capacity() {
        return (capacityAddr - startAddr);
    }

    @Override
    public long remaining() {
        return (limitAddr - positionAddr);
    }

    @Override
    public long limit() {
        return (limitAddr - startAddr);
    }

    @Override
    public NativeBytes limit(long limit) {
        if (limit < 0 || limit > capacity())
            throw new IllegalArgumentException("limit: " + limit + " capacity: " + capacity());
        assert checkSingleThread();
        limitAddr = startAddr + limit;
        return this;
    }

    @NotNull
    @Override
    public ByteOrder byteOrder() {
        return ByteOrder.nativeOrder();
    }

    @Override
    public void checkEndOfBuffer() throws IndexOutOfBoundsException {
        if (position() > capacity())
            throw new IndexOutOfBoundsException("position is beyond the end of the buffer " + position() + " > " + capacity());
    }

    public long startAddr() {
        return startAddr;
    }

    long capacityAddr() {
        return capacityAddr;
    }

    @Override
    protected void cleanup() {
        // TODO nothing to do.
    }

    @Override
    public Bytes load() {
        int pageSize = UNSAFE.pageSize();
        for (long addr = startAddr; addr < capacityAddr; addr += pageSize)
            UNSAFE.getByte(addr);
        return this;
    }

    public void alignPositionAddr(int powerOf2) {
        assert checkSingleThread();
        positionAddr = (positionAddr + powerOf2 - 1) & ~(powerOf2 - 1);
    }

    public void positionAddr(long positionAddr) {
        assert positionChecks(positionAddr);
        this.positionAddr = positionAddr;
    }

    private boolean positionChecks(long positionAddr) {
        if (positionAddr < startAddr || positionAddr > limitAddr)
            throw new IndexOutOfBoundsException("position out of bounds.");
        assert checkSingleThread();
        return true;
    }

    public long positionAddr() {
        return positionAddr;
    }

    @Override
    public ByteBuffer sliceAsByteBuffer(ByteBuffer toReuse) {
        return sliceAsByteBuffer(toReuse, null);
    }

    protected ByteBuffer sliceAsByteBuffer(ByteBuffer toReuse, Object att) {
        return ByteBufferReuse.INSTANCE.reuse(positionAddr, (int) remaining(), att, toReuse);
    }
}

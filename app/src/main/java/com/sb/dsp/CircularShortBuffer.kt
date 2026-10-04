package com.sb.dsp

import java.util.concurrent.atomic.AtomicInteger

class CircularShortBuffer(capacityPowerOfTwo: Int = 16384) {
    val capacity: Int = if (Integer.bitCount(capacityPowerOfTwo) == 1) capacityPowerOfTwo else 16384
    private val mask = capacity - 1
    private val buffer = ShortArray(capacity)
    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)

    fun availableToRead(): Int = (writeIndex.get() - readIndex.get()) and mask
    fun availableToWrite(): Int = capacity - 1 - availableToRead()

    fun write(src: ShortArray, offset: Int, count: Int): Int {
        val free = availableToWrite()
        val toWrite = minOf(count, free)
        if (toWrite <= 0) return 0
        val currentWrite = writeIndex.get()
        val writePos = currentWrite and mask

        if (writePos + toWrite <= capacity) {
            System.arraycopy(src, offset, buffer, writePos, toWrite)
        } else {
            val chunk1 = capacity - writePos
            System.arraycopy(src, offset, buffer, writePos, chunk1)
            System.arraycopy(src, offset + chunk1, buffer, 0, toWrite - chunk1)
        }
        writeIndex.lazySet((currentWrite + toWrite) and (capacity * 2 - 1))
        return toWrite
    }

    fun read(dst: ShortArray, offset: Int, count: Int): Int {
        val available = availableToRead()
        val toRead = minOf(count, available)
        if (toRead <= 0) return 0
        val currentRead = readIndex.get()
        val readPos = currentRead and mask

        if (readPos + toRead <= capacity) {
            System.arraycopy(buffer, readPos, dst, offset, toRead)
        } else {
            val chunk1 = capacity - readPos
            System.arraycopy(buffer, readPos, dst, offset, chunk1)
            System.arraycopy(buffer, 0, dst, offset + chunk1, toRead - chunk1)
        }
        readIndex.lazySet((currentRead + toRead) and (capacity * 2 - 1))
        return toRead
    }
}
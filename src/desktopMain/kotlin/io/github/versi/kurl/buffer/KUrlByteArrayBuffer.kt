package io.github.versi.kurl.buffer

import kotlinx.cinterop.*

private const val BUFFER_INITIAL_SIZE_IN_BYTES = 8192000

class KUrlByteArrayBuffer(initialSize: Int = BUFFER_INITIAL_SIZE_IN_BYTES) : KUrlResponseDataBuffer {

    private var bufferData: ByteArray = ByteArray(initialSize)
    private var lastIndex = 0

    override fun insertChunk(data: CPointer<ByteVar>, dataSize: Int) {
        if (lastIndex + dataSize > bufferData.size) {
            increaseBuffer(bufferData.size * 2)
        }
        for (i in 1..dataSize) {
            bufferData[lastIndex] = data[i - 1]
            lastIndex += 1
        }
    }

    override fun read(): ByteArray = bufferData

    private fun increaseBuffer(newSize: Int) {
        println("Increasing buffer")
        val newBuffer = ByteArray(newSize)
        for (i in bufferData.indices) {
            newBuffer[i] = bufferData[i]
        }
        bufferData = newBuffer
    }
}
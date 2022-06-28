package io.github.versi.kurl.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.readBytes

internal class KUrlByteArrayBuffer : KUrlResponseDataBuffer {

    private var bufferData: ByteArray = ByteArray(0)

    override fun insertChunk(data: CPointer<ByteVar>, dataSize: Int) {
        bufferData += data.readBytes(dataSize)
    }

    override fun read(): ByteArray = bufferData
}
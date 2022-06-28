package io.github.versi.kurl.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.readBytes

internal class KUrlStringBuffer : KUrlResponseDataBuffer {

    private val stringBuffer = StringBuilder()

    override fun insertChunk(data: CPointer<ByteVar>, dataSize: Int) {
        stringBuffer.append(data.toKString(dataSize).trim())
    }

    override fun read(): String = stringBuffer.toString()

    private fun CPointer<ByteVar>.toKString(length: Int): String {
        val bytes = this.readBytes(length)
        return bytes.decodeToString()
    }
}
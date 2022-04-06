package io.github.versi.kurl.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer

interface KUrlResponseDataBuffer {

    fun insertChunk(data: CPointer<ByteVar>, dataSize: Int)

    fun read(): Any
}
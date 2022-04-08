package io.github.versi.kurl

import kotlinx.cinterop.MemScope
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import platform.posix.*

class KUrlMutex(memScope: MemScope = MemScope(), mutexesCount: Long) {

    private val mutexes = memScope.allocArray<pthread_mutex_t>(mutexesCount).also {
        for (i in 0 until mutexesCount) {
            pthread_mutex_init(it[i].ptr, null)
        }
    }

    fun lock(data: Int) {
        pthread_mutex_lock(mutexes[data - 1].ptr)
    }

    fun unlock(data: Int) {
        pthread_mutex_unlock(mutexes[data - 1].ptr)
    }
}
package io.github.versi.kurl

import kotlinx.cinterop.MemScope
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

class KUrlMutex(memScope: MemScope = MemScope(), mutexesCount: Long) {

    private val mutexes = memScope.allocArray<pthread_mutex_t>(mutexesCount)

    fun lock(data: Int) {
        pthread_mutex_lock(mutexes[data].ptr)
    }

    fun unlock(data: Int) {
        pthread_mutex_unlock(mutexes[data].ptr)
    }
}
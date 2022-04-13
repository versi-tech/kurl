package io.github.versi.kurl

import io.github.versi.kurl.buffer.KUrlResponseDataBuffer
import io.github.versi.kurl.buffer.KUrlStringBuffer
import io.github.versi.kurl.curl.*
import kotlinx.cinterop.*
import platform.posix.size_t

private const val MAX_CACHED_CONNECTS_COUNT = 5L

@SharedImmutable
private val mutex = KUrlMutex(mutexesCount = MAX_CACHED_CONNECTS_COUNT)

class KUrl(
    private val url: String,
    val dataBuffer: KUrlResponseDataBuffer,
    userAgent: String? = null,
    timeoutInSeconds: Long = 15,
    withConnectionSharing: Boolean = false
) {

    private val curlShare: COpaquePointer? = if (withConnectionSharing) {
        KUrl.curlShare
    } else null

    companion object {
        private val curlShare: COpaquePointer? = curl_share_init()
    }

    val headerBuffer = KUrlStringBuffer()

    private val stableRef = StableRef.create(this)
    private val curl = curl_easy_init()
    private val memScope = MemScope()

    init {
        /**
         * Based on:
         * https://everything.curl.dev/libcurl/sharing
         * https://everything.curl.dev/libcurl/caches#connection-cache
         * https://curl.se/libcurl/c/shared-connection-cache.html
         * https://curl.se/libcurl/c/threaded-shared-conn.html
         */
        // TODO: consider implementing additional SSL caching support: https://curl.se/libcurl/c/threaded-ssl.html
        curlShare?.let {
            val shareLock = staticCFunction(::shareLock)
            val shareUnlock = staticCFunction(::shareUnlock)
            curl_easy_setopt(curl, CURLOPT_MAXCONNECTS, MAX_CACHED_CONNECTS_COUNT)
            curl_share_setopt(it, CURLSHoption.CURLSHOPT_SHARE, CURL_LOCK_DATA_CONNECT)
            curl_share_setopt(it, CURLSHoption.CURLSHOPT_LOCKFUNC, shareLock)
            curl_share_setopt(it, CURLSHoption.CURLSHOPT_UNLOCKFUNC, shareUnlock)
        }
        curl_easy_setopt(curl, CURLOPT_URL, url)
        val header = staticCFunction(::headerCallback)
        curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, header)
        curl_easy_setopt(curl, CURLOPT_HEADERDATA, stableRef.asCPointer())
        val writeData = staticCFunction(::writeCallback)
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, writeData)
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, stableRef.asCPointer())
        curl_easy_setopt(curl, CURLOPT_FAILONERROR, 1L)
        userAgent?.let {
            curl_easy_setopt(curl, CURLOPT_USERAGENT, it)
        }
        // by default connect timeout will be 0.2 of timeout
        val connectTimeout = timeoutInSeconds / 5
        val transferTimeout = timeoutInSeconds - connectTimeout
        curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, connectTimeout)
        curl_easy_setopt(curl, CURLOPT_TIMEOUT, transferTimeout)
    }

    fun fetch(withoutBody: Boolean = false, headers: List<String> = emptyList()): Any {
        val noBodyRequestCode = if (withoutBody) 1L else 0L
        curl_easy_setopt(curl, CURLOPT_NOBODY, noBodyRequestCode)

        var headersList = memScope.allocPointerTo<curl_slist>().value
        if (headers.isNotEmpty()) {
            headers.forEach { header ->
                headersList = curl_slist_append(headersList, header)
            }
            curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headersList)
        }

        curlShare?.let {
            curl_easy_setopt(curl, CURLOPT_SHARE, it)
        }
        val res = curl_easy_perform(curl)
        if (headers.isNotEmpty()) {
            curl_slist_free_all(headersList)
        }
        if (res != CURLE_OK) {
            val httpStatusCode = memScope.alloc<LongVar>()
            curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, httpStatusCode.ptr)
            val errorMessage = curl_easy_strerror(res)?.toKStringFromUtf8()
            val httpCode = httpStatusCode.value
            throw CUrlException("Failed to download content with CUrl from: $url, response code: $httpCode, CUrl code: $res, message: $errorMessage")
        }

        return dataBuffer.read()
    }

    fun close() {
        curl_easy_cleanup(curl)
        stableRef.dispose()
    }
}

fun headerCallback(
    buffer: CPointer<ByteVar>?,
    size: size_t,
    numberOfItems: size_t,
    userdata: COpaquePointer?
): size_t {
    userdata?.let {
        val curl = userdata.asStableRef<KUrl>().get()
        return readCallbackData(buffer, size, numberOfItems, curl.headerBuffer)
    }
    return 0u
}

fun writeCallback(
    buffer: CPointer<ByteVar>?,
    size: size_t,
    numberOfItems: size_t,
    userdata: COpaquePointer?
): size_t {
    userdata?.let {
        val curl = userdata.asStableRef<KUrl>().get()
        return readCallbackData(buffer, size, numberOfItems, curl.dataBuffer)
    }
    return 0u
}

fun readCallbackData(
    buffer: CPointer<ByteVar>?,
    size: size_t,
    numberOfItems: size_t,
    dataBuffer: KUrlResponseDataBuffer
): size_t {
    if (buffer == null) return 0u
    val dataSize = (size * numberOfItems).toInt()
    dataBuffer.insertChunk(buffer, dataSize)
    return size * numberOfItems
}

fun shareLock(
    handle: COpaquePointer?,
    data: curl_lock_data,
    access: curl_lock_access,
    userdata: COpaquePointer?
) {
    mutex.lock(data.toInt())
}

fun shareUnlock(
    buffer: COpaquePointer?,
    data: curl_lock_data,
    access: curl_lock_access,
    userdata: COpaquePointer?
) {
    mutex.unlock(data.toInt())
}

class CUrlException(override val message: String) : Exception(message)
package io.github.versi.kurl

import io.github.versi.kurl.buffer.KUrlByteArrayBuffer
import io.github.versi.kurl.buffer.KUrlResponseDataBuffer
import io.github.versi.kurl.buffer.KUrlStringBuffer
import io.github.versi.kurl.curl.*
import kotlinx.cinterop.*
import platform.posix.size_t

private const val MAX_CACHED_CONNECTS_COUNT = 5L

@SharedImmutable
private val mutex = KUrlMutex(mutexesCount = CURL_LOCK_DATA_LAST.toInt())

data class KUrlOptions(
    val userAgent: String? = null,
    val connectTimeoutSec: Long = 3,
    val transferTimeoutSec: Long = 12,
    val withConnectionSharing: Boolean = false,
    val proxy: String? = null
)

class KUrl(
    private val url: String,
    val dataBuffer: KUrlResponseDataBuffer,
    options: KUrlOptions = KUrlOptions()
) {

    private val curlShare: COpaquePointer? = if (options.withConnectionSharing) {
        KUrl.curlShare
    } else null

    companion object {
        init {
            curl_global_init(CURL_GLOBAL_ALL.toLong())
        }

        /**
         * Based on:
         * https://everything.curl.dev/libcurl/sharing
         * https://everything.curl.dev/libcurl/caches#connection-cache
         * https://curl.se/libcurl/c/shared-connection-cache.html
         * https://curl.se/libcurl/c/threaded-shared-conn.html
         * https://gist.github.com/bagder/7eccf74f8b6d70b5abefeb7f288dba9b
         */
        // TODO: consider implementing additional SSL caching support: https://curl.se/libcurl/c/threaded-ssl.html
        private val curlShare: COpaquePointer? = curl_share_init().also {
            it?.let {
                curl_share_setopt(it, CURLSHoption.CURLSHOPT_SHARE, CURL_LOCK_DATA_CONNECT)
                curl_share_setopt(it, CURLSHoption.CURLSHOPT_LOCKFUNC, staticCFunction(::shareLock))
                curl_share_setopt(it, CURLSHoption.CURLSHOPT_UNLOCKFUNC, staticCFunction(::shareUnlock))
            }
        }

        fun forBytes(url: String, options: KUrlOptions = KUrlOptions()) = KUrl(url, KUrlByteArrayBuffer(), options)

        fun forString(url: String, options: KUrlOptions = KUrlOptions()) = KUrl(url, KUrlStringBuffer(), options)
    }

    val headerBuffer: KUrlResponseDataBuffer = KUrlStringBuffer()

    private val stableRef = StableRef.create(this)
    private val curl = curl_easy_init()
    private val memScope = MemScope()

    init {
        curl_easy_setopt(curl, CURLOPT_MAXCONNECTS, MAX_CACHED_CONNECTS_COUNT)
        curlShare?.let {
            curl_easy_setopt(curl, CURLOPT_SHARE, it)
        }
        curl_easy_setopt(curl, CURLOPT_URL, url)
        val header = staticCFunction(::headerCallback)
        curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, header)
        curl_easy_setopt(curl, CURLOPT_HEADERDATA, stableRef.asCPointer())
        val writeData = staticCFunction(::writeCallback)
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, writeData)
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, stableRef.asCPointer())
        curl_easy_setopt(curl, CURLOPT_FAILONERROR, 1L)
        options.userAgent?.let {
            curl_easy_setopt(curl, CURLOPT_USERAGENT, it)
        }
        options.proxy?.let {
            curl_easy_setopt(curl, CURLOPT_PROXY, it)
        }
        curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, options.connectTimeoutSec)
        curl_easy_setopt(curl, CURLOPT_TIMEOUT, options.transferTimeoutSec)
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

        val res = curl_easy_perform(curl)
        if (headers.isNotEmpty()) {
            curl_slist_free_all(headersList)
        }
        if (res != CURLE_OK) {
            val httpStatusCode = memScope.alloc<LongVar>()
            curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, httpStatusCode.ptr)
            val errorMessage = curl_easy_strerror(res)?.toKStringFromUtf8()
            val httpCode = httpStatusCode.value
            throwException(url, httpCode, res, errorMessage)
        }

        return dataBuffer.read()
    }

    fun headers() = headerBuffer.read() as String

    fun close() {
        curl_easy_cleanup(curl)
        stableRef.dispose()
    }

    private fun throwException(url: String, httpCode: Long, curlCode: UInt, errorMessage: String?) {
        if (curlCode == CURLE_OPERATION_TIMEDOUT) {
            throw CUrlTimeoutException(url, httpCode, errorMessage)
        } else if (httpCode == 404L) {
            throw CUrlNotFoundException(url, httpCode, errorMessage)
        } else {
            throw CUrlException(url, httpCode, curlCode, errorMessage)
        }
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

open class CUrlException(val url: String, val httpCode: Long, val curlCode: UInt, val errorMessage: String?) :
    Exception() {

    override val message: String =
        "Failed to download content with CUrl from: $url, response code: $httpCode, CUrl code: $curlCode, message: $errorMessage"
}

class CUrlNotFoundException(url: String, httpCode: Long, errorMessage: String?) :
    CUrlException(url, httpCode, CURLE_HTTP_RETURNED_ERROR, errorMessage)

class CUrlTimeoutException(url: String, httpCode: Long, errorMessage: String?) :
    CUrlException(url, httpCode, CURLE_OPERATION_TIMEDOUT, errorMessage)
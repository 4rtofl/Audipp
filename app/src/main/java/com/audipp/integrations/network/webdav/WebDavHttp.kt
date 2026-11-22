package com.audipp.integrations.network.webdav

import com.audipp.integrations.network.NetworkCallPolicy
import com.audipp.integrations.network.NetworkDeadline
import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkResult
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Proxy
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.cert.CertificateException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.toByteString

internal class WebDavHttp(
    private val policy: NetworkCallPolicy,
    private val isLanReadAllowed: () -> Boolean,
    builder: OkHttpClient.Builder,
) {
    private val client = builder
        .connectTimeout(policy.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(policy.operationTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(policy.operationTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(policy.operationTimeoutMs, TimeUnit.MILLISECONDS)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val streamingClient = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    private var retryUntilNanos: Long? = null

    suspend fun <T> exchange(
        request: Request,
        credential: ByteArray?,
        streaming: Boolean = false,
        receive: (Response, checkAccess: () -> Unit) -> NetworkResult<T>,
    ): NetworkResult<T> {
        val job = currentCoroutineContext()
        val deadline = job[NetworkDeadline] ?: NetworkDeadline(policy.operationTimeoutMs)
        fun checkAccess() {
            job.ensureActive()
            if (!isLanReadAllowed()) throw SecurityException()
        }
        checkAccess()
        val authenticatedRequest = request.newBuilder()
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
        if (credential != null) {
            if (!request.url.isHttps) return NetworkResult.Failure(NetworkFailure.PERMISSION_REQUIRED)
            val authorization = basicAuthorization(credential)
                ?: return NetworkResult.Failure(NetworkFailure.INVALID_INPUT)
            authenticatedRequest.header("Authorization", authorization)
        }
        val prepared = authenticatedRequest.build()

        repeat(2) {
            while (true) {
                checkAccess()
                val waitMs = retryDelayMs()
                if (waitMs <= 0) break
                if (TimeUnit.MILLISECONDS.toNanos(waitMs) >= deadline.remainingNanos()) {
                    return NetworkResult.Failure(NetworkFailure.RATE_LIMITED)
                }
                delay(waitMs)
            }
            var retryThrottled = false
            val result = try {
                val call = (if (streaming) streamingClient else client).newCall(prepared)
                coroutineScope {
                    // A blocked socket read cannot observe coroutine cancellation by itself.
                    val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            call.cancel()
                        }
                    }
                    try {
                        call.execute().use { response ->
                            checkAccess()
                            when (response.code) {
                                401 -> NetworkResult.Failure(NetworkFailure.AUTH_REQUIRED)
                                403 -> NetworkResult.Failure(NetworkFailure.PERMISSION_REQUIRED)
                                429 -> {
                                    retryThrottled = deferRequests(response.header("Retry-After"))
                                    NetworkResult.Failure(NetworkFailure.RATE_LIMITED)
                                }
                                in 300..399 -> NetworkResult.Failure(NetworkFailure.INVALID_RESPONSE)
                                405, 501 -> NetworkResult.Failure(NetworkFailure.UNSUPPORTED)
                                in 500..599 -> NetworkResult.Failure(NetworkFailure.IO)
                                else -> {
                                    val encoding = response.singleHeader("Content-Encoding")
                                    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) invalidResponse()
                                    receive(response, ::checkAccess)
                                }
                            }
                        }
                    } finally {
                        cancellation.cancel()
                    }
                }
            } catch (failure: WebDavResponseException) {
                NetworkResult.Failure(failure.reason)
            } catch (_: UnknownServiceException) {
                NetworkResult.Failure(NetworkFailure.UNSUPPORTED)
            } catch (_: SSLPeerUnverifiedException) {
                NetworkResult.Failure(NetworkFailure.UNSUPPORTED)
            } catch (_: UnknownHostException) {
                NetworkResult.Failure(NetworkFailure.OFFLINE)
            } catch (_: ConnectException) {
                NetworkResult.Failure(NetworkFailure.OFFLINE)
            } catch (_: InterruptedIOException) {
                NetworkResult.Failure(NetworkFailure.TIMEOUT)
            } catch (failure: IOException) {
                checkAccess()
                if (failure is SSLHandshakeException &&
                    generateSequence(failure.cause) { it.cause }.any { it is CertificateException }
                ) {
                    NetworkResult.Failure(NetworkFailure.UNSUPPORTED)
                } else {
                    throw failure
                }
            }
            checkAccess()
            if (!retryThrottled) return result
        }
        return NetworkResult.Failure(NetworkFailure.RATE_LIMITED)
    }

    @Synchronized
    private fun retryDelayMs(nowNanos: Long = System.nanoTime()): Long {
        val until = retryUntilNanos ?: return 0
        val nanos = until - nowNanos
        if (nanos <= 0) {
            retryUntilNanos = null
            return 0
        }
        return (nanos - 1) / 1_000_000 + 1
    }

    @Synchronized
    private fun deferRequests(header: String?): Boolean {
        val maxDelayMs = policy.operationTimeoutMs * 3
        val seconds = header?.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        val delayMs = if (seconds != null) {
            val value = seconds.toLongOrNull() ?: return false
            if (value > maxDelayMs / 1_000) return false
            value * 1_000
        } else {
            val date = header?.let(::httpDate)
            (date?.minus(System.currentTimeMillis()) ?: 1_000).coerceAtLeast(0)
        }
        if (delayMs > maxDelayMs) return false
        val nowNanos = System.nanoTime()
        if (delayMs > retryDelayMs(nowNanos)) retryUntilNanos = nowNanos + delayMs * 1_000_000
        return true
    }
}

private fun basicAuthorization(value: ByteArray): String? {
    if (value.size !in 2..4_096 || value.indexOf(':'.code.toByte()) <= 0) return null
    val decoded = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value))
    } catch (_: CharacterCodingException) {
        return null
    }
    try {
        if (decoded.any { it.isISOControl() }) return null
    } finally {
        decoded.array().fill('\u0000')
    }
    return "Basic " + value.toByteString().base64()
}

private val gmt: TimeZone = TimeZone.getTimeZone("GMT")
private const val RFC_850_DATE = "EEEE, dd-MMM-yy HH:mm:ss 'GMT'"
private val httpDatePatterns = listOf("EEE, dd MMM yyyy HH:mm:ss 'GMT'", RFC_850_DATE, "EEE MMM d HH:mm:ss yyyy")

internal fun httpDate(value: String): Long? {
    if (value.length !in 24..35 || value.any { it !in ' '..'~' }) return null
    for (pattern in httpDatePatterns) {
        val format = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = gmt
            isLenient = false
            if (pattern == RFC_850_DATE) {
                set2DigitYearStart(Calendar.getInstance(gmt).apply { add(Calendar.YEAR, -50) }.time)
            }
        }
        val position = ParsePosition(0)
        val parsed = format.parse(value, position)
        if (parsed != null && position.index == value.length) return parsed.time
    }
    return null
}

internal fun Response.singleHeader(name: String): String? {
    val values = headers(name)
    if (values.size > 1) invalidResponse()
    return values.singleOrNull()
}

internal fun Response.boundedBody(maxBytes: Int, oversized: NetworkFailure = NetworkFailure.INVALID_RESPONSE): ByteArray {
    val source = body.source()
    if (body.contentLength() > maxBytes || source.request(maxBytes.toLong() + 1)) throw WebDavResponseException(oversized)
    return source.readByteArray()
}

internal class WebDavResponseException(val reason: NetworkFailure = NetworkFailure.INVALID_RESPONSE) : RuntimeException()
internal fun invalidResponse(): Nothing = throw WebDavResponseException()

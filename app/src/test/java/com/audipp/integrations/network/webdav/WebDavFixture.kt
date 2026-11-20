package com.audipp.integrations.network.webdav

import com.audipp.integrations.network.NetworkEndpoint
import com.audipp.integrations.network.NetworkProtocol
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketHandler
import okhttp3.OkHttpClient
import okio.Buffer
import okio.Socket

internal class WebDavFixture(tls: FixtureTls? = null) : Closeable {
    private val server = MockWebServer()
    val endpoint: NetworkEndpoint
    val requests = ConcurrentLinkedQueue<Received>()

    @Volatile
    var handler: (RecordedRequest) -> MockResponse = { reply(404) }

    init {
        if (tls != null) {
            server.useHttps(tls.server.socketFactory)
            server.protocolNegotiationEnabled = false
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(Received(
                    request.method, request.url.encodedPath, request.headers["Depth"],
                    request.headers["Range"], request.headers["If-Match"], request.headers["If-Unmodified-Since"],
                    request.headers["Authorization"], request.body?.utf8().orEmpty(), System.nanoTime(),
                ))
                return handler(request)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        endpoint = NetworkEndpoint(NetworkProtocol.WEBDAV, server.url("/music/").newBuilder().host("127.0.0.1").build().toString())
    }

    override fun close() = server.close()

    class Received(
        val method: String,
        val path: String,
        val depth: String?,
        val range: String?,
        val ifMatch: String?,
        val ifUnmodifiedSince: String?,
        val authorization: String?,
        val body: String,
        val receivedNanos: Long,
    )
}

internal class FixtureTls(storeFile: File) {
    private val password = "localhost-test-fixture".toCharArray()
    private val keys = KeyStore.getInstance("PKCS12").apply {
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        val process = ProcessBuilder(
            keytool.path, "-genkeypair", "-alias", "loopback", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-storetype", "PKCS12", "-keystore", storeFile.path, "-storepass", String(password),
            "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1",
        ).redirectErrorStream(true).start()
        try {
            check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0)
        } finally {
            process.destroyForcibly()
        }
        storeFile.inputStream().use { load(it, password) }
    }
    private val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(keys) }.trustManagers.single() as X509TrustManager

    val server: SSLContext = SSLContext.getInstance("TLS").apply {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keys, password) }.keyManagers
        init(keyManagers, null, null)
    }

    fun client(): OkHttpClient.Builder {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder().sslSocketFactory(context.socketFactory, trust)
    }
}

internal fun reply(
    status: Int,
    body: ByteArray = byteArrayOf(),
    vararg headers: Pair<String, String>,
    chunked: Boolean = false,
): MockResponse = MockResponse.Builder().code(status).apply {
    val buffer = Buffer().write(body)
    if (chunked) chunkedBody(buffer) else body(buffer)
    for ((name, value) in headers) setHeader(name, value)
}.build()

internal fun MockResponse.Builder.stallBody(started: CountDownLatch, release: CountDownLatch): MockResponse =
    socketHandler(object : SocketHandler {
        override fun handle(socket: Socket) {
            started.countDown()
            release.await(7, TimeUnit.SECONDS)
        }
    }).build()

internal fun xml(vararg entries: String): String =
    "<D:multistatus xmlns:D=\"DAV:\">" + entries.joinToString("") + "</D:multistatus>"

internal fun entry(
    href: String,
    collection: Boolean = false,
    type: String = "application/octet-stream",
    additionalPropstat: String = "",
): String = """
    <D:response>
      <D:href>$href</D:href>
      <D:propstat><D:prop>
        <D:resourcetype>${if (collection) "<D:collection/>" else ""}</D:resourcetype>
        <D:getcontenttype>$type</D:getcontenttype>
      </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
      $additionalPropstat
    </D:response>
""".trimIndent()

internal fun directory(body: String = xml(entry("/music/", collection = true), entry("/music/song.flac"))) =
    reply(207, body.toByteArray(), "Content-Type" to "application/xml")

internal fun range(
    start: Int,
    end: Int,
    contents: String = "abcdef",
    tag: String = "\"original\"",
    modified: String = ORIGINAL_MODIFIED,
) = reply(206, contents.substring(start..end).toByteArray(),
    "Content-Range" to "bytes $start-$end/${contents.length}", "ETag" to tag, "Last-Modified" to modified)

internal const val ORIGINAL_MODIFIED = "Mon, 06 Jan 2020 10:00:00 GMT"
internal const val NEW_MODIFIED = "Tue, 07 Jan 2020 10:00:00 GMT"

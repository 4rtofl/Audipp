package com.audipp.integrations.network.upnp

import com.audipp.integrations.network.NetworkFailure
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.concurrent.thread

class UpnpHttpTest {
    @Test fun readsFixedChunkedAndCloseDelimitedResponses() {
        for ((headers, wireBody) in listOf(
            "Content-Length: 4\r\n" to "<x/>",
            "Transfer-Encoding: chunked\r\n" to "1\r\n<\r\n3;ext=1\r\nx/>\r\n0\r\nTrailer: ok\r\n\r\n",
            "" to "<x/>",
        )) {
            withResponse("HTTP/1.1 200 OK\r\n${headers}\r\n$wireBody") { peer ->
                val result = Socket().use { upnpHttp(it, peer, peer.location, null) }
                assertEquals(200, result.status)
                assertEquals("<x/>", result.body.toString(Charsets.UTF_8))
            }
        }
    }

    @Test fun rejectsRedirectsAndAmbiguousOversizeOrTruncatedBodies() {
        for (reply in listOf(
            "HTTP/1.1 302 Found\r\nLocation: http://outside.example/\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: ${MAX_UPNP_XML_BYTES + 1}\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nxx",
            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n<",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n200001\r\n",
            "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n",
        )) {
            withResponse(reply) { peer ->
                val error = assertThrows(UpnpException::class.java) {
                    Socket().use { upnpHttp(it, peer, peer.location, null) }
                }
                assertEquals(NetworkFailure.INVALID_RESPONSE, error.failure)
            }
        }
    }

    @Test fun cancellationClosesABlockedBoundSocket() = runBlocking {
        ServerSocket(0, 1, loopback).use { server ->
            val received = kotlinx.coroutines.CompletableDeferred<Unit>()
            val serverTask = FutureTask {
                server.accept().use {
                    readRequest(it)
                    received.complete(Unit)
                    it.soTimeout = 2_000
                    it.getInputStream().read()
                }
            }
            thread(isDaemon = true) { serverTask.run() }
            val selected = peer(server)
            val request = async { cancellableUpnpIo({ Socket() }) { socket, _ -> upnpHttp(socket, selected, selected.location, null) } }
            kotlinx.coroutines.withTimeout(5_000) { received.await() }
            request.cancelAndJoin()
            assertEquals(-1, serverTask.get(2, SECONDS))
        }
    }

    private fun withResponse(reply: String, block: (UpnpPeer) -> Unit) {
        ServerSocket(0, 1, loopback).use { server ->
            val serverTask = FutureTask {
                server.accept().use {
                    readRequest(it)
                    it.getOutputStream().write(reply.toByteArray(Charsets.US_ASCII))
                }
            }
            thread(isDaemon = true) { serverTask.run() }
            block(peer(server))
            serverTask.get(3, SECONDS)
        }
    }

    private fun readRequest(socket: Socket) {
        socket.soTimeout = 3_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
        assertEquals("GET /device.xml HTTP/1.1", reader.readLine())
        while (reader.readLine().orEmpty().isNotEmpty()) Unit
    }

    private fun peer(server: ServerSocket) =
        UpnpPeer(URI("http://renderer.example:${server.localPort}/device.xml"), loopback)

    private companion object {
        val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}

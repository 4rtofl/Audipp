package com.audipp.integrations.network.upnp

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.system.ErrnoException
import android.system.OsConstants
import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkResult
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.util.ReflectionHelpers.callConstructor
import org.robolectric.util.ReflectionHelpers.callInstanceMethod
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidUpnpNetworkTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun mapsOnlySocketPermissionErrnosToPermissionRequired() = runBlocking {
        val cases = listOf(
            IOException(ErrnoException("bind", OsConstants.EPERM)) to NetworkFailure.PERMISSION_REQUIRED,
            IOException(ErrnoException("bind", OsConstants.EACCES)) to NetworkFailure.PERMISSION_REQUIRED,
            IOException(ErrnoException("bind", OsConstants.EIO)) to NetworkFailure.IO,
            SocketTimeoutException() to NetworkFailure.TIMEOUT,
            SecurityException() to NetworkFailure.IO,
        )
        for ((error, reason) in cases) {
            val network = wifiNetwork {
                object : MulticastSocket(null) {
                    override fun bind(address: SocketAddress?) {
                        if (address != null) throw error
                        super.bind(null)
                    }
                }
            }
            assertEquals(NetworkResult.Failure(reason), UpnpControlPoint(network).discover(1))
        }
    }

    @Test fun cancellingSearchReleasesItsWifiMulticastLock() = runBlocking {
        val wifi = checkNotNull(context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        val shadow = shadowOf(wifi)
        val receiving = CompletableDeferred<Unit>()
        val closed = CountDownLatch(1)
        val socket = object : MulticastSocket(null) {
            override fun bind(address: SocketAddress?) = Unit
            override fun setNetworkInterface(networkInterface: NetworkInterface?) = Unit
            override fun setTimeToLive(ttl: Int) = Unit
            override fun send(packet: DatagramPacket?) = Unit
            override fun receive(packet: DatagramPacket?) {
                receiving.complete(Unit)
                check(closed.await(5, SECONDS))
            }

            override fun close() {
                super.close()
                closed.countDown()
            }
        }
        val network = wifiNetwork { socket }
        val search = launch { network.search(setOf(RENDERER_TYPE)) { true } }
        try {
            withTimeout(5_000) { receiving.await() }
            assertEquals(1, shadow.activeLockCount)
        } finally {
            search.cancelAndJoin()
        }
        assertEquals(0, shadow.activeLockCount)
    }

    private fun wifiNetwork(socket: () -> MulticastSocket): AndroidUpnpNetwork {
        val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        val network = ShadowNetwork.newInstance(42)
        val capabilities = shadowOf(ShadowNetworkCapabilities.newInstance())
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        val loopback = InetAddress.getByName("127.0.0.1")
        val links = LinkProperties().apply {
            interfaceName = checkNotNull(NetworkInterface.getByInetAddress(loopback)).name
            val address = callConstructor(LinkAddress::class.java, ClassParameter.from(String::class.java, "192.168.10.5/24"))
            callInstanceMethod<Boolean>(this, "addLinkAddress", ClassParameter.from(LinkAddress::class.java, address))
        }
        shadowOf(connectivity).apply {
            setNetworkCapabilities(network, capabilities)
            setLinkProperties(network, links)
        }
        return AndroidUpnpNetwork(context, network, searchSocket = socket) { true }
    }
}

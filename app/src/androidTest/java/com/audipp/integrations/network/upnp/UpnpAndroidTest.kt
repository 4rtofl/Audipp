package com.audipp.integrations.network.upnp

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpnpAndroidTest {
    @Test fun platformXmlParserDecodesStandardEntitiesAndRejectsDtd() {
        val valid = parseUpnpXml("<root><name>One &amp; Two</name></root>".toByteArray())
        assertEquals("One & Two", valid.value("name"))
        val invalid = "<!DOCTYPE root [<!ENTITY secret SYSTEM 'file:///proc/self/status'>]><root>&secret;</root>"
        assertEquals(NetworkFailure.INVALID_RESPONSE, assertThrows(UpnpException::class.java) {
            parseUpnpXml(invalid.toByteArray())
        }.failure)
    }

    @Test fun revokedLanConsentRejectsDiscoveryAndManualAccessBeforeIo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = checkNotNull(context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
        val network = checkNotNull(connectivity.activeNetwork)
        val point = UpnpControlPoint(AndroidUpnpNetwork(context, network) { false })
        assertEquals(NetworkResult.Failure(NetworkFailure.PERMISSION_REQUIRED), point.discover(1))
        assertEquals(NetworkResult.Failure(NetworkFailure.PERMISSION_REQUIRED), point.manual("192.168.10.20"))
    }
}

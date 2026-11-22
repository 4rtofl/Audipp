package com.audipp.integrations.network.upnp

import com.audipp.integrations.network.NetworkFailure
import java.net.InetAddress
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpProtocolTest {
    @Test fun ssdpAcceptsOnlyConsistentAnswersAndPinsLocationToSender() {
        val accepted = checkNotNull(advertisement())
        assertEquals(TEST_UDN, accepted.udn)
        assertEquals(testAddress, accepted.peer.address)
        assertEquals(120L, accepted.maxAgeSeconds)
        assertEquals(1800L, checkNotNull(advertisement(maxAge = 4000)).maxAgeSeconds)
        assertNull(advertisement(maxAge = 0))
        assertNull(advertisement(target = "ssdp:all"))
        assertNull(advertisement(target = "${UPNP_DEVICE_PREFIX}MediaRenderer:0"))
        assertNotNull(advertisement(target = "${UPNP_DEVICE_PREFIX}MediaRenderer:2"))
        assertNull(advertisement(additionalHeader = "LOCATION: $TEST_URL\r\n"))
        assertNull(advertisement(additionalHeader = "X-Excess: ${"a".repeat(MAX_SSDP_PACKET)}\r\n"))
        for (location in listOf(
            "http://192.168.10.21:8000/device.xml", "http://outside.example/device.xml",
            "http://user:secret@192.168.10.20:8000/device.xml", "$TEST_URL#fragment", "$TEST_URL?token=1",
            "file:///upnp/device.xml", "http://192.168.10.20:8000/%0d%0aHost",
        )) assertNull(location, advertisement(url = location))
    }

    @Test fun ssdpAcceptsUda11ExtensionsAndIgnoresUnrelatedHeaderContents() {
        val response = advertisement(
            udn = "uuid:RINCON_000E5800000001400_MR",
            additionalHeader = listOf(
                "BOOTID.UPNP.ORG: 1",
                "CONFIGID.UPNP.ORG: 2",
                "SEARCHPORT.UPNP.ORG: 1901",
                "OPT: \"http://schemas.upnp.org/upnp/1/0/\"; ns=01",
                "01-NLS: test",
                "X-User-Agent: Fixture",
                "SERVER: Fixture/1.0 UPnP/1.1 機器/1.0",
                "Ignored header: \u0000",
            ).joinToString("\r\n", postfix = "\r\n"),
        )
        assertEquals("uuid:rincon_000e5800000001400_mr", response?.udn)
        assertNull(advertisement(additionalHeader = "ST: $RENDERER_TYPE\r\n"))
        assertNull(advertisement(udn = "uuid:invalid\u0000"))
        assertNull(advertisement(udn = "uuid:機器"))
    }

    @Test fun udnAcceptsPrintableCharactersWithinItsOriginalLengthLimit() {
        assertEquals("uuid:rincon_123.mr:2", upnpUdn("UUID:RINCON_123.MR:2"))
        assertEquals("uuid:機器", upnpUdn("uuid:機器"))
        assertNotNull(upnpUdn("uuid:" + "a".repeat(128)))
        for (id in listOf("", "a b", "a\u00a0b", "a\u0001b", "a::b", "a".repeat(129))) {
            assertNull(id, upnpUdn("uuid:$id"))
        }
    }

    @Test fun localSubnetRejectsPublicDifferentSubnetOwnAddressAndBroadcast() {
        fun subnet(address: String) = localSubnet(InetAddress.getByName(address), testAddress, 24)
        assertTrue(subnet("192.168.10.21"))
        for (address in listOf("192.168.10.0", "192.168.10.255", "192.168.10.20", "192.168.11.20", "8.8.8.8", "127.0.0.1", "239.255.255.250")) {
            assertFalse(address, subnet(address))
        }
        val ipv6 = InetAddress.getByName("fd00:1::1")
        assertTrue(localSubnet(InetAddress.getByName("fd00:1::2"), ipv6, 64))
        assertFalse(localSubnet(InetAddress.getByName("fd00:2::2"), ipv6, 64))
    }

    @Test fun deviceControlAndScpdUrlsRemainOnExactPinnedOrigin() {
        val peer = UpnpPeer(URI(TEST_URL), testAddress)
        fun parse(xml: String) = deviceDescription(parseUpnpXml(xml.toByteArray()), peer, null)
        val normal = parse(deviceXml(urlBase = "<URLBase>http://192.168.10.20:8000/base/</URLBase>"))
        assertEquals("Fixture & Renderer", normal.name)
        assertEquals("/base/transport.xml", normal.services.getValue(AV_TRANSPORT).description.path)
        assertEquals("/control", normal.services.getValue(AV_TRANSPORT).control.path)
        val withQuery = parse(deviceXml(control = "/control?instance=1&amp;mode=a%2Fb"))
        assertEquals("instance=1&mode=a%2Fb", withQuery.services.getValue(AV_TRANSPORT).control.rawQuery)
        val queryOnly = parse(deviceXml(control = "?instance=1")).services.getValue(AV_TRANSPORT).control
        assertEquals("$TEST_URL?instance=1", queryOnly.toString())
        assertFailure(NetworkFailure.INVALID_RESPONSE) { parse(deviceXml(scpd = "/scpd?instance=1")) }
        assertFailure(NetworkFailure.INVALID_RESPONSE) { parse(deviceXml(control = "/control?arg=%0d%0a")) }
        for (ref in listOf(
            "//192.168.10.21:8000/control", "http://192.168.10.20:8001/control",
            "http://user@192.168.10.20:8000/control", "//192.168.10.21:8000/control?token=1",
            "https://192.168.10.20:8000/control", "file:///etc/passwd",
        )) {
            assertFailure(NetworkFailure.INVALID_RESPONSE) { parse(deviceXml(control = ref)) }
            assertFailure(NetworkFailure.INVALID_RESPONSE) { parse(deviceXml(scpd = ref)) }
        }
        assertFailure(NetworkFailure.INVALID_RESPONSE) {
            parse(deviceXml(urlBase = "<URLBase>http://outside.example/</URLBase>"))
        }
    }

    @Test fun xmlRejectsDtdEntitiesAndEnforcesSizeDepthAndListLimits() {
        fun parse(value: String) = parseUpnpXml(value.toByteArray())
        assertEquals(1000, parse("<root>${"<i/>".repeat(1000)}</root>").children.size)
        assertNotNull(parse("<x>".repeat(32) + "</x>".repeat(32)))
        val exactSize = "<x>" + "a".repeat(MAX_UPNP_XML_BYTES - 7) + "</x>"
        assertNotNull(parse(exactSize))
        for (malformed in listOf(
            "<root>${"<i/>".repeat(1001)}</root>",
            "<x>".repeat(33) + "</x>".repeat(33),
            exactSize + " ",
            "<!DOCTYPE x><x/>",
            "<!DOCTYPE x [<!ENTITY private SYSTEM 'file:///nonexistent'>]><x>&private;</x>",
            "<!DOCTYPE x SYSTEM 'http://127.0.0.1:1/DTD'><x/>",
            "<x>&unknown;</x>",
        )) assertFailure(NetworkFailure.INVALID_RESPONSE) { parse(malformed) }
        assertFailure(NetworkFailure.INVALID_RESPONSE) {
            parse("<r>${("<s>" + "<x/>".repeat(1000) + "</s>").repeat(66)}</r>")
        }
    }

    @Test fun soapValidatesActionArgumentsNamespacesFaultsAndEscapesXml() {
        val uri = URI(TEST_URL)
        val service = UpnpServiceLocation(AV_TRANSPORT, uri, uri)
        val action = serviceActions(parseUpnpXml(scpdXml().toByteArray())).getValue("Play")
        val request = soapRequest(service, "Play", action, mapOf("Speed" to "1<&>\r", "InstanceID" to "0"))
        assertEquals("$AV_TRANSPORT#Play", request.soapAction)
        val text = request.body.toString(Charsets.UTF_8)
        assertTrue(text.contains("<InstanceID>0</InstanceID><Speed>1&lt;&amp;&gt;&#13;</Speed>"))
        assertFailure(NetworkFailure.INVALID_INPUT) {
            soapRequest(service, "Play", action, mapOf("InstanceID" to "0"))
        }
        assertFailure(NetworkFailure.INVALID_INPUT) {
            soapRequest(service, "Play", action, mapOf("InstanceID" to "0", "Speed" to "\u0000"))
        }
        soapResponse(parseUpnpXml(soapXml("Play").toByteArray()), 200, service, "Play", action)
        for (xml in listOf(
            soapXml("Stop"), soapXml("Play", service = "urn:external"), soapXml("Play", "<Unexpected>1</Unexpected>"),
        )) assertFailure(NetworkFailure.INVALID_RESPONSE) {
            soapResponse(parseUpnpXml(xml.toByteArray()), 200, service, "Play", action)
        }
        val fault = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
              <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                <errorCode>401</errorCode><errorDescription>Invalid Action</errorDescription>
              </UPnPError></detail>
            </s:Fault></s:Body></s:Envelope>
        """.trimIndent()
        assertFailure(NetworkFailure.UNSUPPORTED) {
            soapResponse(parseUpnpXml(fault.toByteArray()), 500, service, "Play", action)
        }
    }

    @Test fun embeddedBrowseXmlHasItsOwnStructuralLimits() {
        val service = UpnpServiceLocation(CONTENT_DIRECTORY, URI(TEST_URL), URI(TEST_URL))
        fun embedded(xml: String): UpnpXml {
            val escaped = xml.replace("<", "&lt;").replace(">", "&gt;")
            val envelope = soapXml("Browse", "<Result>$escaped</Result>", CONTENT_DIRECTORY)
            return soapResponse(
                parseUpnpXml(envelope.toByteArray()), 200, service, "Browse",
                UpnpAction(emptySet(), setOf("Result")),
            ).embeddedXml("Result")
        }
        assertEquals(1000, embedded("<DIDL-Lite>${"<item/>".repeat(1000)}</DIDL-Lite>").children.size)
        assertFailure(NetworkFailure.INVALID_RESPONSE) {
            embedded("<DIDL-Lite>${"<item/>".repeat(1001)}</DIDL-Lite>")
        }
        assertFailure(NetworkFailure.INVALID_RESPONSE) { embedded("<!DOCTYPE x><x/>") }
    }

    private fun assertFailure(reason: NetworkFailure, block: () -> Unit) {
        assertEquals(reason, assertThrows(UpnpException::class.java, block).failure)
    }
}

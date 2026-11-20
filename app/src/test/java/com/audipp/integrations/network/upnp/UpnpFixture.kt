package com.audipp.integrations.network.upnp

import com.audipp.integrations.network.NetworkFailure
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

internal const val RENDERER_TYPE = "${UPNP_DEVICE_PREFIX}MediaRenderer:1"
internal const val TEST_UDN = "uuid:renderer-1"
internal const val TEST_URL = "http://192.168.10.20:8000/upnp/device.xml"
internal val testAddress = checkNotNull(numericAddress("192.168.10.20"))

internal fun advertisement(
    url: String = TEST_URL,
    sender: InetAddress = testAddress,
    udn: String = TEST_UDN,
    target: String = RENDERER_TYPE,
    maxAge: Long = 120,
    additionalHeader: String = "",
): UpnpAdvertisement? = parseSsdp(
    ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=$maxAge\r\n" +
        "LOCATION: $url\r\nST: $target\r\nUSN: $udn::$target\r\n$additionalHeader\r\n")
        .toByteArray(Charsets.US_ASCII),
    sender,
)

internal fun deviceXml(
    type: String = RENDERER_TYPE,
    udn: String = TEST_UDN,
    service: String = AV_TRANSPORT,
    scpd: String = "transport.xml",
    control: String = "/control",
    urlBase: String = "",
): String = """
    <root xmlns="$DEVICE_XML">
      $urlBase
      <device>
        <deviceType>$type</deviceType><friendlyName>Fixture &amp; Renderer</friendlyName><UDN>$udn</UDN>
        <serviceList><service>
          <serviceType>$service</serviceType><SCPDURL>$scpd</SCPDURL><controlURL>$control</controlURL>
          <eventSubURL>http://outside.example/events</eventSubURL>
        </service></serviceList>
      </device>
    </root>
""".trimIndent()

internal fun scpdXml(): String = """
    <scpd xmlns="$SERVICE_XML"><actionList>
      <action><name>GetTransportInfo</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction></argument>
        <argument><name>CurrentTransportState</name><direction>out</direction></argument>
      </argumentList></action>
      <action><name>Play</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction></argument>
        <argument><name>Speed</name><direction>in</direction></argument>
      </argumentList></action>
    </actionList></scpd>
""".trimIndent()

internal fun soapXml(
    name: String,
    values: String = "",
    service: String = AV_TRANSPORT,
): String = """
    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
      <s:Body><u:${name}Response xmlns:u="$service">$values</u:${name}Response></s:Body>
    </s:Envelope>
""".trimIndent()

internal fun response(xml: String, status: Int = 200) = UpnpResponse(status, xml.toByteArray(Charsets.UTF_8))

internal class FixtureNetwork : UpnpNetwork {
    @Volatile var failure: NetworkFailure? = null
    @Volatile var localMembers = setOf(testAddress)
    @Volatile var dns = listOf(testAddress)
    @Volatile var adverts = listOfNotNull(advertisement())
    val events = CopyOnWriteArrayList<String>()
    var exchangeResponse: suspend (URI, UpnpPost?) -> UpnpResponse = { uri, post ->
        when (uri.path) {
            "/upnp/device.xml" -> response(deviceXml())
            "/upnp/transport.xml" -> response(scpdXml())
            "/control" -> when (post?.soapAction) {
                "$AV_TRANSPORT#GetTransportInfo" -> response(
                    soapXml("GetTransportInfo", "<CurrentTransportState>PLAYING</CurrentTransportState>"),
                )
                "$AV_TRANSPORT#Play" -> response(soapXml("Play"))
                else -> error("Unexpected SOAP action")
            }
            else -> error("Unexpected request")
        }
    }

    override fun rejection(): NetworkFailure? = failure
    override fun isLocal(address: InetAddress): Boolean = address in localMembers

    override suspend fun resolve(host: String): List<InetAddress> {
        events.add("DNS $host")
        return dns
    }

    override suspend fun search(requests: Set<String>, accept: (UpnpAdvertisement) -> Boolean) {
        events.add("SEARCH")
        for (advertisement in adverts) {
            if (!accept(advertisement)) break
        }
    }

    override suspend fun exchange(peer: UpnpPeer, uri: URI, post: UpnpPost?): UpnpResponse {
        check(peer.address == testAddress)
        events.add(if (post == null) "GET ${uri.rawPath}" else "POST ${post.soapAction}")
        return exchangeResponse(uri, post)
    }
}

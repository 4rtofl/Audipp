package com.audipp.integrations.network.upnp

import com.audipp.integrations.network.NetworkFailure

private const val SOAP_XML = "http://schemas.xmlsoap.org/soap/envelope/"
private const val UPNP_CONTROL_XML = "urn:schemas-upnp-org:control-1-0"

internal class UpnpSoapResult(private val values: Map<String, String>) {
    operator fun get(name: String): String? = values[name]

    fun embeddedXml(name: String): UpnpXml =
        parseUpnpXml(values[name]?.toByteArray(Charsets.UTF_8) ?: invalidUpnpResponse())

    override fun toString() = "UpnpSoapResult(redacted)"
}

internal fun soapRequest(
    service: UpnpServiceLocation,
    name: String,
    action: UpnpAction,
    arguments: Map<String, String>,
): UpnpPost {
    if (arguments.keys != action.input) throw UpnpException(NetworkFailure.INVALID_INPUT)
    val body = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"$SOAP_XML\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body><u:$name xmlns:u=\"${service.type}\">")
        for (key in action.input) {
            append("<$key>")
            val value = arguments.getValue(key)
            if (value.length > MAX_UPNP_XML_BYTES) throw UpnpException(NetworkFailure.INVALID_INPUT)
            appendXmlValue(value)
            append("</$key>")
            if (length > MAX_UPNP_XML_BYTES) throw UpnpException(NetworkFailure.INVALID_INPUT)
        }
        append("</u:$name></s:Body></s:Envelope>")
    }.toByteArray(Charsets.UTF_8)
    if (body.size > MAX_UPNP_XML_BYTES) throw UpnpException(NetworkFailure.INVALID_INPUT)
    return UpnpPost("${service.type}#$name", body)
}

private fun StringBuilder.appendXmlValue(value: String) {
    if (value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) != value) {
        throw UpnpException(NetworkFailure.INVALID_INPUT)
    }
    for (char in value) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '\r' -> append("&#13;")
            '\t', '\n' -> append(char)
            in '\u0000'..'\u001f', '\ufffe', '\uffff' -> throw UpnpException(NetworkFailure.INVALID_INPUT)
            else -> append(char)
        }
    }
}

internal fun soapResponse(
    root: UpnpXml,
    status: Int,
    service: UpnpServiceLocation,
    name: String,
    action: UpnpAction,
): UpnpSoapResult {
    if (root.name != "Envelope" || root.namespace != SOAP_XML) invalidUpnpResponse()
    val payload = root.element("Body")?.children?.singleOrNull() ?: invalidUpnpResponse()
    if (payload.name == "Fault" && payload.namespace == SOAP_XML) {
        val code = payload.element("detail", "")?.element("UPnPError", UPNP_CONTROL_XML)?.value("errorCode")
        throw UpnpException(when (code) {
            "401" -> NetworkFailure.UNSUPPORTED
            "606" -> NetworkFailure.AUTH_REQUIRED
            else -> NetworkFailure.INVALID_RESPONSE
        })
    }
    if (status != 200 || payload.name != "${name}Response" || payload.namespace != service.type) {
        invalidUpnpResponse()
    }
    val values = mutableMapOf<String, String>()
    for (field in payload.children) {
        if ((field.namespace.isNotEmpty() && field.namespace != service.type) ||
            field.name !in action.output || field.children.isNotEmpty() ||
            values.put(field.name, field.text.toString()) != null
        ) invalidUpnpResponse()
    }
    if (values.keys != action.output) invalidUpnpResponse()
    return UpnpSoapResult(values)
}

package com.example.hicastreceiver

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * A small UPnP AV MediaRenderer starter implementation.
 *
 * Scope: SSDP discovery + device/SCPD descriptions + a subset of AVTransport,
 * RenderingControl and ConnectionManager SOAP actions. This is a compatibility
 * baseline, not a DLNA certification implementation.
 */
class DlnaReceiverServer(
    private val context: Context,
    private val friendlyName: String,
    private val udn: String,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onUriSet(uri: String)
        fun onPlay(uri: String)
        fun onPause()
        fun onStop()
        fun onSeek(positionMs: Long)
    }

    companion object {
        private const val TAG = "HiCastDLNA"
        private const val HTTP_PORT = 18080
        private const val SSDP_PORT = 1900
        private const val SSDP_GROUP = "239.255.255.250"
        private const val ROOT_TYPE = "upnp:rootdevice"
        private const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val AVT_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RCS_TYPE = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val CM_TYPE = "urn:schemas-upnp-org:service:ConnectionManager:1"
    }

    private val running = AtomicBoolean(false)
    private val clients: ExecutorService = Executors.newCachedThreadPool()
    @Volatile private var httpSocket: ServerSocket? = null
    @Volatile private var ssdpSocket: MulticastSocket? = null
    @Volatile private var localIp: String = "0.0.0.0"
    @Volatile private var currentUri: String = ""
    @Volatile private var currentMetaData: String = ""
    @Volatile private var transportState: String = "STOPPED"
    @Volatile private var positionMs: Long = 0L
    @Volatile private var durationMs: Long = 0L
    @Volatile private var volume: Int = 100
    @Volatile private var muted: Boolean = false

    private val uuid: String = udn.removePrefix("uuid:")

    fun localAddress(): String = localIp

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            localIp = findLocalIpv4() ?: "0.0.0.0"
            httpSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", HTTP_PORT))
            }
            localIp = findLocalIpv4() ?: localIp
            Log.i(TAG, "HTTP description/control at http://$localIp:$HTTP_PORT/device.xml")
            Thread(::acceptHttpLoop, "HiCast-HTTP-Accept").start()
            Thread(::ssdpLoop, "HiCast-SSDP").start()
            Thread(::announcementLoop, "HiCast-SSDP-Notify").start()
        } catch (e: Exception) {
            running.set(false)
            try { httpSocket?.close() } catch (_: Exception) {}
            httpSocket = null
            Log.e(TAG, "Failed to start DLNA server", e)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { httpSocket?.close() } catch (_: Exception) {}
        try { ssdpSocket?.close() } catch (_: Exception) {}
        httpSocket = null
        ssdpSocket = null
        clients.shutdownNow()
    }

    fun updatePlaybackState(state: String, position: Long, duration: Long) {
        transportState = state
        positionMs = position.coerceAtLeast(0L)
        durationMs = duration.coerceAtLeast(0L)
    }

    private fun acceptHttpLoop() {
        while (running.get()) {
            try {
                val server = httpSocket ?: break
                val socket = server.accept()
                clients.execute { handleHttpClient(socket) }
            } catch (e: SocketException) {
                if (running.get()) Log.w(TAG, "HTTP accept socket error", e)
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "HTTP accept error", e)
            }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 6000
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readAsciiLine(input) ?: return
                val requestParts = requestLine.trim().split(Regex("\\s+"))
                if (requestParts.size < 2) return
                val method = requestParts[0].uppercase()
                val rawPath = requestParts[1]
                val path = rawPath.substringBefore('?')
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                }
                val length = (headers["content-length"] ?: "0").toIntOrNull()?.coerceIn(0, 2_000_000) ?: 0
                val body = if (method == "POST" && length > 0) {
                    String(readExactly(input, length), StandardCharsets.UTF_8)
                } else ""

                when {
                    method == "GET" && path == "/device.xml" -> sendHttp(client, 200, "text/xml; charset=utf-8", deviceDescription())
                    method == "GET" && path == "/upnp/avtransport.xml" -> sendHttp(client, 200, "text/xml; charset=utf-8", avTransportScpd())
                    method == "GET" && path == "/upnp/renderingcontrol.xml" -> sendHttp(client, 200, "text/xml; charset=utf-8", renderingControlScpd())
                    method == "GET" && path == "/upnp/connectionmanager.xml" -> sendHttp(client, 200, "text/xml; charset=utf-8", connectionManagerScpd())
                    method == "POST" && path == "/upnp/control/avtransport" -> handleSoap(client, headers, body, AVT_TYPE)
                    method == "POST" && path == "/upnp/control/renderingcontrol" -> handleSoap(client, headers, body, RCS_TYPE)
                    method == "POST" && path == "/upnp/control/connectionmanager" -> handleSoap(client, headers, body, CM_TYPE)
                    method == "SUBSCRIBE" || method == "UNSUBSCRIBE" -> {
                        // Basic GENA handshake only. State-change NOTIFY delivery is not implemented yet.
                        val extra = if (method == "SUBSCRIBE") {
                            "SID: uuid:${UUID.randomUUID()}\r\nTIMEOUT: Second-1800\r\n"
                        } else ""
                        sendHttp(client, 200, "text/plain; charset=utf-8", "", extraHeaders = extra)
                    }
                    method == "OPTIONS" -> sendHttp(client, 200, "text/plain; charset=utf-8", "", extraHeaders = "Allow: GET, POST, SUBSCRIBE, UNSUBSCRIBE, OPTIONS\r\n")
                    else -> sendHttp(client, 404, "text/plain; charset=utf-8", "Not found")
                }
            } catch (e: Exception) {
                Log.w(TAG, "HTTP client request failed", e)
                try { sendHttp(client, 500, "text/plain; charset=utf-8", "Internal error") } catch (_: Exception) {}
            }
        }
    }

    private fun handleSoap(socket: Socket, headers: Map<String, String>, body: String, serviceType: String) {
        val actionHeader = headers["soapaction"].orEmpty().trim().trim('"')
        val action = actionHeader.substringAfterLast('#', "").ifBlank {
            Regex("(?s)<(?:[A-Za-z0-9_]+:)?Body[^>]*>\\s*<(?:[A-Za-z0-9_]+:)?([A-Za-z0-9_]+)")
                .find(body)?.groupValues?.getOrNull(1).orEmpty()
        }
        Log.i(TAG, "SOAP $serviceType#$action")
        val result = when (serviceType) {
            AVT_TYPE -> handleAvTransport(action, body)
            RCS_TYPE -> handleRenderingControl(action, body)
            CM_TYPE -> handleConnectionManager(action, body)
            else -> null
        }
        if (result == null) {
            val fault = """<?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>
                <UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>401</errorCode><errorDescription>Invalid Action</errorDescription></UPnPError>
                </detail></s:Fault></s:Body></s:Envelope>""".trimIndent()
            sendHttp(socket, 500, "text/xml; charset=utf-8", fault)
            return
        }
        val response = """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body><u:${action}Response xmlns:u="$serviceType">$result</u:${action}Response></s:Body></s:Envelope>""".trimIndent()
        sendHttp(socket, 200, "text/xml; charset=utf-8", response)
    }

    private fun handleAvTransport(action: String, body: String): String? {
        return when (action) {
            "SetAVTransportURI" -> {
                currentUri = readXmlTag(body, "CurrentURI").let(::xmlUnescape)
                currentMetaData = readXmlTag(body, "CurrentURIMetaData").let(::xmlUnescape)
                positionMs = 0L
                durationMs = 0L
                transportState = "STOPPED"
                if (currentUri.isNotBlank()) callbacks.onUriSet(currentUri)
                ""
            }
            "SetNextAVTransportURI" -> ""
            "Play" -> {
                if (currentUri.isNotBlank()) {
                    transportState = "TRANSITIONING"
                    callbacks.onPlay(currentUri)
                }
                ""
            }
            "Pause" -> { callbacks.onPause(); transportState = "PAUSED_PLAYBACK"; "" }
            "Stop" -> { callbacks.onStop(); transportState = "STOPPED"; positionMs = 0L; "" }
            "Seek" -> {
                val target = readXmlTag(body, "Target")
                callbacks.onSeek(parseTime(target))
                ""
            }
            "Next", "Previous" -> ""
            "GetTransportInfo" -> """
                <CurrentTransportState>${xmlEscape(transportState)}</CurrentTransportState>
                <CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>
            """.trimIndent()
            "GetPositionInfo" -> """
                <Track>1</Track><TrackDuration>${formatTime(durationMs)}</TrackDuration>
                <TrackMetaData>${xmlEscape(currentMetaData)}</TrackMetaData><TrackURI>${xmlEscape(currentUri)}</TrackURI>
                <RelTime>${formatTime(positionMs)}</RelTime><AbsTime>${formatTime(positionMs)}</AbsTime>
                <RelCount>0</RelCount><AbsCount>0</AbsCount>
            """.trimIndent()
            "GetMediaInfo" -> """
                <NrTracks>${if (currentUri.isBlank()) 0 else 1}</NrTracks><MediaDuration>${formatTime(durationMs)}</MediaDuration>
                <CurrentURI>${xmlEscape(currentUri)}</CurrentURI><CurrentURIMetaData>${xmlEscape(currentMetaData)}</CurrentURIMetaData>
                <NextURI></NextURI><NextURIMetaData></NextURIMetaData><PlayMedium>NETWORK</PlayMedium>
                <RecordMedium>NOT_IMPLEMENTED</RecordMedium><WriteStatus>NOT_WRITABLE</WriteStatus>
            """.trimIndent()
            "GetDeviceCapabilities" -> "<PlayMedia>NETWORK</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia><RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>"
            "GetTransportSettings" -> "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>"
            "GetCurrentTransportActions" -> "<Actions>Play,Pause,Stop,Seek</Actions>"
            else -> null
        }
    }

    private fun handleRenderingControl(action: String, body: String): String? {
        return when (action) {
            "GetVolume" -> "<CurrentVolume>$volume</CurrentVolume>"
            "SetVolume" -> { volume = readXmlTag(body, "DesiredVolume").toIntOrNull()?.coerceIn(0, 100) ?: volume; "" }
            "GetMute" -> "<CurrentMute>${if (muted) 1 else 0}</CurrentMute>"
            "SetMute" -> { muted = readXmlTag(body, "DesiredMute").trim() == "1"; "" }
            "ListPresets" -> "<CurrentPresetNameList>FactoryDefaults</CurrentPresetNameList>"
            "SelectPreset" -> ""
            else -> null
        }
    }

    private fun handleConnectionManager(action: String, body: String): String? {
        return when (action) {
            "GetProtocolInfo" -> """
                <Source></Source>
                <Sink>http-get:*:video/mp4:* ,http-get:*:video/mpeg:* ,http-get:*:video/x-matroska:* ,http-get:*:video/3gpp:* ,http-get:*:audio/mpeg:* ,http-get:*:audio/mp4:* ,http-get:*:image/jpeg:* ,http-get:*:image/png:*</Sink>
            """.trimIndent().replace(Regex("\\s+"), " ").replace(":* ,", ":*,")
            "GetCurrentConnectionIDs" -> "<ConnectionIDs>0</ConnectionIDs>"
            "GetCurrentConnectionInfo" -> "<RcsID>0</RcsID><AVTransportID>0</AVTransportID><ProtocolInfo>http-get:*:video/mp4:*</ProtocolInfo><PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID><Direction>Input</Direction><Status>OK</Status>"
            "PrepareForConnection" -> "<ConnectionID>0</ConnectionID><AVTransportID>0</AVTransportID><RcsID>0</RcsID>"
            "ConnectionComplete" -> ""
            else -> null
        }
    }

    private fun ssdpLoop() {
        var socket: MulticastSocket? = null
        try {
            val group = InetAddress.getByName(SSDP_GROUP)
            socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(SSDP_PORT))
                soTimeout = 1200
            }
            val ni = preferredNetworkInterface()
            if (ni != null) {
                try {
                    socket.networkInterface = ni
                    socket.joinGroup(InetSocketAddress(group, SSDP_PORT), ni)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to join SSDP multicast group on ${ni.name}", e)
                }
            }
            ssdpSocket = socket
            val buffer = ByteArray(8192)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, packet.offset, packet.length, StandardCharsets.US_ASCII)
                    if (message.contains("M-SEARCH", ignoreCase = true)) {
                        val st = Regex("(?im)^ST\\s*:\\s*(.+?)\\s*$").find(message)?.groupValues?.get(1)?.trim().orEmpty()
                        val targets = if (st.equals("ssdp:all", true)) allTargets() else listOf(st).filter { isSupportedTarget(it) }
                        for (target in targets) {
                            val payload = searchResponse(target).toByteArray(StandardCharsets.US_ASCII)
                            val response = DatagramPacket(payload, payload.size, packet.address, packet.port)
                            socket.send(response)
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Periodic timeout lets the thread observe stop().
                } catch (e: SocketException) {
                    if (running.get()) Log.w(TAG, "SSDP socket error", e)
                    break
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "SSDP packet error", e)
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "SSDP listener failed", e)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
            ssdpSocket = null
        }
    }

    private fun announcementLoop() {
        while (running.get()) {
            try { sendAliveAnnouncements() } catch (e: Exception) { Log.w(TAG, "SSDP notify failed", e) }
            try { Thread.sleep(25_000L) } catch (_: InterruptedException) { break }
        }
        try { sendByebyeAnnouncements() } catch (_: Exception) {}
    }

    private fun sendAliveAnnouncements() = sendNotifications("ssdp:alive")
    private fun sendByebyeAnnouncements() = sendNotifications("ssdp:byebye")

    private fun sendNotifications(nts: String) {
        val group = InetAddress.getByName(SSDP_GROUP)
        val ni = preferredNetworkInterface()
        MulticastSocket().use { socket ->
            socket.timeToLive = 2
            if (ni != null) try { socket.networkInterface = ni } catch (_: Exception) {}
            for (target in allTargets()) {
                val nt = target
                val usn = usnFor(target)
                val locationLine = if (nts == "ssdp:alive") "LOCATION: ${deviceUrl()}\r\n" else ""
                val cacheLine = if (nts == "ssdp:alive") "CACHE-CONTROL: max-age=1800\r\n" else ""
                val message = "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $SSDP_GROUP:$SSDP_PORT\r\n" + cacheLine + locationLine +
                    "NT: $nt\r\nNTS: $nts\r\n" +
                    "SERVER: Android/13 UPnP/1.1 HiCast/0.1\r\nUSN: $usn\r\n\r\n"
                val bytes = message.toByteArray(StandardCharsets.US_ASCII)
                socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
            }
        }
    }

    private fun searchResponse(target: String): String {
        return "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "DATE: ${java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC))}\r\n" +
            "EXT:\r\n" +
            "LOCATION: ${deviceUrl()}\r\n" +
            "SERVER: Android/13 UPnP/1.1 HiCast/0.1\r\n" +
            "ST: $target\r\n" +
            "USN: ${usnFor(target)}\r\n\r\n"
    }

    private fun allTargets(): List<String> = listOf("uuid:$uuid", ROOT_TYPE, DEVICE_TYPE, AVT_TYPE, RCS_TYPE, CM_TYPE)
    private fun isSupportedTarget(target: String): Boolean = target == ROOT_TYPE || target == DEVICE_TYPE || target == AVT_TYPE || target == RCS_TYPE || target == CM_TYPE || target == "uuid:$uuid"

    private fun usnFor(target: String): String = when (target) {
        ROOT_TYPE -> "uuid:$uuid::$ROOT_TYPE"
        DEVICE_TYPE -> "uuid:$uuid::$DEVICE_TYPE"
        AVT_TYPE -> "uuid:$uuid::$AVT_TYPE"
        RCS_TYPE -> "uuid:$uuid::$RCS_TYPE"
        CM_TYPE -> "uuid:$uuid::$CM_TYPE"
        else -> "uuid:$uuid"
    }

    private fun deviceUrl(): String = "http://$localIp:$HTTP_PORT/device.xml"

    private fun deviceDescription(): String = """<?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <URLBase>http://$localIp:$HTTP_PORT/</URLBase>
          <device>
            <deviceType>$DEVICE_TYPE</deviceType>
            <friendlyName>${xmlEscape(friendlyName)}</friendlyName>
            <manufacturer>HiSilicon</manufacturer>
            <manufacturerURL>https://www.hisilicon.com/</manufacturerURL>
            <modelDescription>Android TV DLNA Media Renderer starter</modelDescription>
            <modelName>HiCast 4K Receiver</modelName>
            <modelNumber>0.1.0</modelNumber>
            <serialNumber>$uuid</serialNumber>
            <UDN>uuid:$uuid</UDN>
            <serviceList>
              <service><serviceType>$AVT_TYPE</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/upnp/avtransport.xml</SCPDURL><controlURL>/upnp/control/avtransport</controlURL><eventSubURL>/upnp/event/avtransport</eventSubURL></service>
              <service><serviceType>$RCS_TYPE</serviceType><serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><SCPDURL>/upnp/renderingcontrol.xml</SCPDURL><controlURL>/upnp/control/renderingcontrol</controlURL><eventSubURL>/upnp/event/renderingcontrol</eventSubURL></service>
              <service><serviceType>$CM_TYPE</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><SCPDURL>/upnp/connectionmanager.xml</SCPDURL><controlURL>/upnp/control/connectionmanager</controlURL><eventSubURL>/upnp/event/connectionmanager</eventSubURL></service>
            </serviceList>
            <presentationURL>http://$localIp:$HTTP_PORT/</presentationURL>
          </device>
        </root>""".trimIndent()

    private fun avTransportScpd(): String = scpd(
        listOf(
            action("SetAVTransportURI", "InstanceID:in,CurrentURI:in,CurrentURIMetaData:in"),
            action("SetNextAVTransportURI", "InstanceID:in,NextURI:in,NextURIMetaData:in"),
            action("Play", "InstanceID:in,Speed:in"), action("Pause", "InstanceID:in"), action("Stop", "InstanceID:in"),
            action("Seek", "InstanceID:in,Unit:in,Target:in"), action("Next", "InstanceID:in"), action("Previous", "InstanceID:in"),
            action("GetTransportInfo", "InstanceID:in,CurrentTransportState:out,CurrentTransportStatus:out,CurrentSpeed:out"),
            action("GetPositionInfo", "InstanceID:in,Track:out,TrackDuration:out,TrackMetaData:out,TrackURI:out,RelTime:out,AbsTime:out,RelCount:out,AbsCount:out"),
            action("GetMediaInfo", "InstanceID:in,NrTracks:out,MediaDuration:out,CurrentURI:out,CurrentURIMetaData:out,NextURI:out,NextURIMetaData:out,PlayMedium:out,RecordMedium:out,WriteStatus:out"),
            action("GetDeviceCapabilities", "InstanceID:in,PlayMedia:out,RecMedia:out,RecQualityModes:out"),
            action("GetTransportSettings", "InstanceID:in,PlayMode:out,RecQualityMode:out"),
            action("GetCurrentTransportActions", "InstanceID:in,Actions:out")
        ),
        listOf("TransportState", "TransportStatus", "CurrentSpeed", "AVTransportURI", "AVTransportURIMetaData", "NextAVTransportURI", "NextAVTransportURIMetaData", "CurrentTrack", "NumberOfTracks", "CurrentTrackDuration", "CurrentTrackMetaData", "CurrentTrackURI", "RelativeTimePosition", "AbsoluteTimePosition", "CurrentTransportActions", "CurrentPlayMode", "PlaybackStorageMedium", "RecordStorageMedium", "LastChange", "A_ARG_TYPE_InstanceID", "A_ARG_TYPE_String", "A_ARG_TYPE_SeekMode", "A_ARG_TYPE_SeekTarget")
    )

    private fun renderingControlScpd(): String = scpd(
        listOf(action("ListPresets", "InstanceID:in,CurrentPresetNameList:out"), action("SelectPreset", "InstanceID:in,PresetName:in"), action("GetVolume", "InstanceID:in,Channel:in,CurrentVolume:out"), action("SetVolume", "InstanceID:in,Channel:in,DesiredVolume:in"), action("GetMute", "InstanceID:in,Channel:in,CurrentMute:out"), action("SetMute", "InstanceID:in,Channel:in,DesiredMute:in")),
        listOf("LastChange", "PresetNameList", "Volume", "Mute", "A_ARG_TYPE_InstanceID", "A_ARG_TYPE_Channel", "A_ARG_TYPE_PresetName")
    )

    private fun connectionManagerScpd(): String = scpd(
        listOf(action("GetProtocolInfo", "Source:out,Sink:out"), action("GetCurrentConnectionIDs", "ConnectionIDs:out"), action("GetCurrentConnectionInfo", "ConnectionID:in,RcsID:out,AVTransportID:out,ProtocolInfo:out,PeerConnectionManager:out,PeerConnectionID:out,Direction:out,Status:out"), action("PrepareForConnection", "RemoteProtocolInfo:in,PeerConnectionManager:in,PeerConnectionID:in,Direction:in,ConnectionID:out,AVTransportID:out,RcsID:out"), action("ConnectionComplete", "ConnectionID:in")),
        listOf("SourceProtocolInfo", "SinkProtocolInfo", "CurrentConnectionIDs", "A_ARG_TYPE_ConnectionID", "A_ARG_TYPE_ConnectionStatus", "A_ARG_TYPE_ProtocolInfo", "A_ARG_TYPE_ConnectionManager", "A_ARG_TYPE_Direction", "A_ARG_TYPE_PeerConnectionID", "A_ARG_TYPE_InstanceID", "A_ARG_TYPE_String")
    )

    private fun scpd(actions: List<String>, stateVariables: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>")
        actions.forEach { append(it) }
        append("</actionList><serviceStateTable>")
        stateVariables.forEach { variable ->
            val evented = if (variable == "LastChange" || variable.endsWith("ProtocolInfo") || variable == "CurrentConnectionIDs") "yes" else "no"
            val dataType = when {
                variable.contains("Volume", true) -> "ui2"
                variable.contains("Mute", true) -> "boolean"
                variable.contains("Time", true) || variable.contains("Duration", true) -> "string"
                else -> "string"
            }
            append("<stateVariable sendEvents=\"$evented\"><name>${xmlEscape(variable)}</name><dataType>$dataType</dataType></stateVariable>")
        }
        append("</serviceStateTable></scpd>")
    }

    private fun action(name: String, args: String): String = buildString {
        append("<action><name>$name</name><argumentList>")
        if (args.isNotBlank()) {
            args.split(',').forEach { token ->
                val parts = token.split(':')
                val argName = parts[0]
                val direction = parts.getOrElse(1) { "in" }
                append("<argument><name>$argName</name><direction>$direction</direction><relatedStateVariable>${relatedStateVariable(argName)}</relatedStateVariable></argument>")
            }
        }
        append("</argumentList></action>")
    }

    private fun relatedStateVariable(argument: String): String = when (argument) {
        "InstanceID" -> "A_ARG_TYPE_InstanceID"
        "CurrentURI" -> "AVTransportURI"
        "NextURI" -> "NextAVTransportURI"
        "TrackURI" -> "CurrentTrackURI"
        "CurrentURIMetaData", "TrackMetaData" -> "AVTransportURIMetaData"
        "NextURIMetaData" -> "NextAVTransportURIMetaData"
        "CurrentTransportState" -> "TransportState"
        "CurrentTransportStatus" -> "TransportStatus"
        "CurrentSpeed" -> "CurrentSpeed"
        "TrackDuration", "MediaDuration" -> "CurrentTrackDuration"
        "NrTracks" -> "NumberOfTracks"
        "Track" -> "CurrentTrack"
        "PlayMedium" -> "PlaybackStorageMedium"
        "RecordMedium" -> "RecordStorageMedium"
        "WriteStatus" -> "A_ARG_TYPE_String"
        "RelTime" -> "RelativeTimePosition"
        "AbsTime" -> "AbsoluteTimePosition"
        "Actions" -> "CurrentTransportActions"
        "Speed" -> "CurrentSpeed"
        "Unit" -> "A_ARG_TYPE_SeekMode"
        "Target" -> "A_ARG_TYPE_SeekTarget"
        "NrTracks", "Track" -> "CurrentTrack"
        "DesiredVolume", "CurrentVolume" -> "Volume"
        "DesiredMute", "CurrentMute" -> "Mute"
        "Source" -> "SourceProtocolInfo"
        "Sink" -> "SinkProtocolInfo"
        "RemoteProtocolInfo", "ProtocolInfo" -> "A_ARG_TYPE_ProtocolInfo"
        "ConnectionIDs" -> "CurrentConnectionIDs"
        "ConnectionID" -> "A_ARG_TYPE_ConnectionID"
        "PeerConnectionID" -> "A_ARG_TYPE_PeerConnectionID"
        "PeerConnectionManager" -> "A_ARG_TYPE_ConnectionManager"
        "Direction" -> "A_ARG_TYPE_Direction"
        "Status" -> "A_ARG_TYPE_ConnectionStatus"
        "PresetName" -> "A_ARG_TYPE_PresetName"
        "Channel" -> "A_ARG_TYPE_Channel"
        else -> "A_ARG_TYPE_String"
    }

    private fun findLocalIpv4(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val usable = interfaces.filter { ni ->
                try { ni.isUp && !ni.isLoopback } catch (_: Exception) { false }
            }.sortedBy { ni ->
                when {
                    ni.name.startsWith("wlan", true) || ni.name.startsWith("wifi", true) -> 0
                    ni.name.startsWith("eth", true) || ni.name.startsWith("en", true) -> 1
                    else -> 2
                }
            }
            for (ni in usable) {
                for (address in Collections.list(ni.inetAddresses)) {
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Unable to determine LAN IPv4 address", e)
            null
        }
    }

    private fun preferredNetworkInterface(): NetworkInterface? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { ni -> try { ni.isUp && !ni.isLoopback && ni.supportsMulticast() } catch (_: Exception) { false } }
                .sortedBy { ni ->
                    when {
                        ni.name.startsWith("wlan", true) || ni.name.startsWith("wifi", true) -> 0
                        ni.name.startsWith("eth", true) || ni.name.startsWith("en", true) -> 1
                        else -> 2
                    }
                }
            interfaces.firstOrNull()
        } catch (_: Exception) { null }
    }

    private fun sendHttp(
        socket: Socket,
        status: Int,
        contentType: String,
        body: String,
        extraHeaders: String = "",
    ) {
        val statusText = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache\r\n" + extraHeaders + "\r\n"
        val output = socket.getOutputStream()
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val value = input.read()
            if (value < 0) return if (sawAny) output.toString("US-ASCII") else null
            sawAny = true
            if (value == '\n'.code) break
            if (value != '\r'.code) output.write(value)
            if (output.size() > 16 * 1024) throw IllegalArgumentException("HTTP header line too long")
        }
        return output.toString("US-ASCII")
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(buffer, offset, length - offset)
            if (count < 0) throw IllegalStateException("Unexpected EOF reading HTTP body")
            offset += count
        }
        return buffer
    }

    private fun readXmlTag(xml: String, tag: String): String {
        val regex = Regex("(?s)<(?:[A-Za-z0-9_]+:)?${Pattern.quote(tag)}(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?${Pattern.quote(tag)}>")
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun xmlUnescape(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun parseTime(value: String): Long {
        return try {
            val parts = value.trim().split(':')
            if (parts.size < 3) return value.toLongOrNull()?.times(1000L) ?: 0L
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val seconds = parts[2].toDouble()
            ((hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()).coerceAtLeast(0L)
        } catch (_: Exception) { 0L }
    }

    private fun formatTime(millis: Long): String {
        if (millis <= 0L) return "00:00:00"
        val totalSeconds = millis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}

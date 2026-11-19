package com.hasan.airspeaker

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

class ControlChannel(
    private val audioPort: Int = 5004,
    private val controlPort: Int = 5005,
    @Volatile var onConfigSyncReceived: ((JSONObject) -> Unit)? = null
) {
    private val TAG = "ControlChannel"
    @Volatile
    private var isRunning = false
    private var beaconThread: Thread? = null
    private var statsThread: Thread? = null
    private var listenerThread: Thread? = null

    @Volatile
    private var listenerSocket: DatagramSocket? = null
    @Volatile
    private var beaconSocket: DatagramSocket? = null
    @Volatile
    private var statsSocket: DatagramSocket? = null

    @Volatile
    private var activePcIp: String? = lastKnownPcIp
    @Volatile
    private var activePcName: String = lastKnownPcName
    @Volatile
    private var lastPingPcTs: Long = 0L
    @Volatile
    private var lastPingRxElapsedMs: Long = 0L
    @Volatile
    var lastPcSeenElapsedMs: Long = 0L

    fun getActivePcIp(): String? = activePcIp
    fun getActivePcName(): String = activePcName

    fun setActivePcIp(ip: String?) {
        if (!ip.isNullOrBlank() && !isOwnLocalIp(ip)) {
            activePcIp = ip.trim()
            lastKnownPcIp = activePcIp
            sendPreStreamBeacon(activePcIp)
        }
    }

    fun isOwnLocalIp(ip: String?): Boolean = Companion.isOwnLocalIp(ip)

    @Synchronized
    fun start() {
        if (isRunning || listenerThread != null || beaconThread != null || statsThread != null) return
        isRunning = true

        startListener()
        startBeaconBroadcaster()
        startStatsSender()
    }

    fun stop() {
        val listener: Thread?
        val beacon: Thread?
        val stats: Thread?

        synchronized(this) {
            if (!isRunning && listenerThread == null && beaconThread == null && statsThread == null) {
                return
            }

            isRunning = false

            try { listenerSocket?.close() } catch (_: Exception) {}
            try { beaconSocket?.close() } catch (_: Exception) {}
            try { statsSocket?.close() } catch (_: Exception) {}

            listenerSocket = null
            beaconSocket = null
            statsSocket = null

            listener = listenerThread
            beacon = beaconThread
            stats = statsThread
        }

        listener?.interrupt()
        beacon?.interrupt()
        stats?.interrupt()

        try {
            listener?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            beacon?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            stats?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        synchronized(this) {
            if (listenerThread === listener) {
                listenerThread = null
            }
            if (beaconThread === beacon) {
                beaconThread = null
            }
            if (statsThread === stats) {
                statsThread = null
            }
        }
    }

    fun sendConfigSync(json: JSONObject, targetIp: String? = null) {
        val dest = targetIp?.takeIf { !isOwnLocalIp(it) } ?: activePcIp
        val senderIp = AudioNative.nativeGetSenderIp()?.takeIf { !isOwnLocalIp(it) }
        thread(name = "ConfigSyncSender", isDaemon = true) {
            try {
                val data = json.toString().toByteArray(Charsets.UTF_8)
                val sock = DatagramSocket().apply { broadcast = true }
                val targetList = mutableSetOf<String>()
                if (!dest.isNullOrBlank()) targetList.add(dest)
                if (!senderIp.isNullOrBlank()) targetList.add(senderIp)
                targetList.add("255.255.255.255")

                val localIps = getLocalIpv4Addresses()
                for (ip in localIps) {
                    val parts = ip.split(".")
                    if (parts.size == 4) {
                        targetList.add("${parts[0]}.${parts[1]}.${parts[2]}.255")
                    }
                }

                for (tIp in targetList) {
                    try {
                        sock.send(DatagramPacket(data, data.size, InetAddress.getByName(tIp), controlPort))
                    } catch (_: Exception) {}
                }
                sock.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error sending CONFIG_SYNC", e)
            }
        }
    }

    private fun startListener() {
        listenerThread = thread(name = "ControlListener", isDaemon = true) {
            try {
                var sock: DatagramSocket? = null
                for (attempt in 1..10) {
                    try {
                        sock = DatagramSocket(null).apply {
                            reuseAddress = true
                            bind(InetSocketAddress(controlPort))
                        }
                        break
                    } catch (e: java.net.BindException) {
                        if (attempt == 10) throw e
                        Thread.sleep(50)
                    }
                }
                if (sock == null) return@thread
                listenerSocket = sock
                Log.i(TAG, "Control listener bound to UDP $controlPort")

                val buffer = ByteArray(4096)
                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    val senderIp = packet.address.hostAddress ?: continue

                    // CRITICAL FIX: Ignore packets sent by this phone's own IP addresses!
                    if (isOwnLocalIp(senderIp)) {
                        continue
                    }

                    try {
                        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        val json = JSONObject(text)
                        val msgType = json.optString("type")

                        if (msgType == "BEACON") {
                            val devName = json.optString("device", "Party Host")
                            val aPort = json.optInt("audio_port", 5004)
                            val isExt = json.optBoolean("is_extended_host", false)
                            val isHost = json.optBoolean("is_party_host", false)
                            val hasPartyJoin = json.has("party_join")
                            val isPartyJoin = json.optBoolean("party_join", false)
                            val labelName = if (isExt) "$devName (Extended Host)" else if (isHost) "$devName (Party Host)" else devName
                            if (!isOwnLocalIp(senderIp)) {
                                if (isHost || isExt) {
                                    discoveredPartyHosts[senderIp] = DiscoveredPartyHost(senderIp, labelName, aPort, android.os.SystemClock.elapsedRealtime(), isExt)
                                    onPartyHostDiscovered?.invoke()
                                } else if (json.has("is_party_host") || json.has("is_extended_host")) {
                                    if (discoveredPartyHosts.remove(senderIp) != null) {
                                        onPartyHostDiscovered?.invoke()
                                    }
                                }

                                // Rule 4: Handle downstream peers
                                if (isHostingOrExtendingParty || AudioNative.nativeIsPartyRelay()) {
                                    if (hasPartyJoin && isPartyJoin && !isHost) {
                                        partyPeerLastSeen[senderIp] = android.os.SystemClock.elapsedRealtime()
                                        if (partyPeerIps.add(senderIp)) {
                                            AudioNative.nativeSetPartyPeers(partyPeerIps.toTypedArray())
                                            onPartyHostDiscovered?.invoke()
                                            Log.i(TAG, "Party Mode: registered downstream peer $senderIp (total=${partyPeerIps.size})")
                                        }
                                    } else if (hasPartyJoin && !isPartyJoin) {
                                        partyPeerLastSeen.remove(senderIp)
                                        if (partyPeerIps.remove(senderIp)) {
                                            AudioNative.nativeSetPartyPeers(partyPeerIps.toTypedArray())
                                            onPartyHostDiscovered?.invoke()
                                            Log.i(TAG, "Party Mode: removed downstream peer $senderIp (total=${partyPeerIps.size})")
                                        }
                                    }
                                }
                            }
                            continue
                        }
                        if (msgType == "STATS") {
                            continue
                        }

                        lastPcSeenElapsedMs = android.os.SystemClock.elapsedRealtime()

                        if (msgType == "PING") {
                            val pcTs = json.optLong("pc_ts", 0L)
                            if (pcTs > 0) {
                                lastPingPcTs = pcTs
                                lastPingRxElapsedMs = android.os.SystemClock.elapsedRealtime()
                            }
                            continue
                        }

                        if (msgType == "PC_ANNOUNCE") {
                            activePcIp = senderIp
                            lastKnownPcIp = senderIp
                            AudioStreamService.internalAudioStreamerInstance?.updateTargetIp(senderIp)
                            val pcName = json.optString("pc_name", "")
                            if (pcName.isNotBlank()) {
                                activePcName = pcName
                                lastKnownPcName = pcName
                            }
                            val isPcPartyHost = json.optBoolean("is_party_host", false) || json.optBoolean("is_extended_host", false)
                            if (isPcPartyHost) {
                                discoveredPartyHosts[senderIp] = DiscoveredPartyHost(senderIp, if (pcName.isNotBlank()) pcName else "PC Studio", 5004, android.os.SystemClock.elapsedRealtime())
                                onPartyHostDiscovered?.invoke()
                            } else if (json.has("is_party_host") || json.has("party_mode")) {
                                if (discoveredPartyHosts.remove(senderIp) != null) {
                                    onPartyHostDiscovered?.invoke()
                                }
                            }
                            Log.i(TAG, "Discovered active PC IP via PC_ANNOUNCE: $senderIp ($activePcName)")
                        } else if (msgType == "CONFIG_SYNC") {
                            val allowOverwrite = (activePcIp == null || activePcIp == senderIp || !AudioStreamService.isStreaming)
                            if (allowOverwrite) {
                                activePcIp = senderIp
                                lastKnownPcIp = senderIp
                                AudioStreamService.internalAudioStreamerInstance?.updateTargetIp(senderIp)
                                val pcName = json.optString("pc_name", "")
                                if (pcName.isNotBlank()) {
                                    activePcName = pcName
                                    lastKnownPcName = pcName
                                }
                            }
                            onConfigSyncReceived?.invoke(json)
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind UDP $controlPort", e)
            } finally {
                try { listenerSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun startBeaconBroadcaster() {
        beaconThread = thread(name = "BeaconBroadcaster", isDaemon = true) {
            try {
                val sock = DatagramSocket().apply { broadcast = true }
                beaconSocket = sock

                while (isRunning) {
                    val ips = getLocalIpv4Addresses()
                    val json = JSONObject().apply {
                        put("type", "BEACON")
                        put("app", "AirSpeaker")
                        put("version", 1)
                        put("ips", JSONArray(ips))
                        put("audio_port", audioPort)
                        put("device", getPhoneModelName())
                        put("party_join", false)
                        put("is_party_host", isHostingOrExtendingParty && !isExtendingParty)
                        put("is_extended_host", isExtendingParty)
                        put("serving", AudioStreamService.isServing)
                        put("playing", AudioStreamService.isStreaming)
                    }
                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    try {
                        val broadcastAddr = InetAddress.getByName("255.255.255.255")
                        sock.send(DatagramPacket(data, data.size, broadcastAddr, controlPort))
                    } catch (_: Exception) {}

                    val unicastTargets = mutableSetOf<String>()
                    activePcIp?.takeIf { !isOwnLocalIp(it) }?.let { unicastTargets.add(it) }
                    lastKnownPcIp?.takeIf { !isOwnLocalIp(it) }?.let { unicastTargets.add(it) }
                    AudioNative.nativeGetSenderIp()?.takeIf { !isOwnLocalIp(it) }?.let { unicastTargets.add(it) }

                    for (target in unicastTargets) {
                        try {
                            sock.send(DatagramPacket(data, data.size, InetAddress.getByName(target), controlPort))
                        } catch (_: Exception) {}
                    }

                    try {
                        for (ip in ips) {
                            val parts = ip.split(".")
                            if (parts.size == 4) {
                                val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                                val subnetBroadcast = "$prefix.255"
                                try {
                                    sock.send(DatagramPacket(data, data.size, InetAddress.getByName(subnetBroadcast), controlPort))
                                } catch (_: Exception) {}
                                if (parts[3] != "1") {
                                    try {
                                        sock.send(DatagramPacket(data, data.size, InetAddress.getByName("$prefix.1"), controlPort))
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    Thread.sleep(1000)
                }
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "Beacon broadcaster error: ${e.message}")
            } finally {
                try { beaconSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun startStatsSender() {
        statsThread = thread(name = "StatsSender", isDaemon = true) {
            try {
                val sock = DatagramSocket().apply { broadcast = true }
                statsSocket = sock
                while (isRunning) {
                    Thread.sleep(300)
                    val targetIp = activePcIp?.takeIf { !isOwnLocalIp(it) }
                        ?: AudioNative.nativeGetSenderIp()?.takeIf { !isOwnLocalIp(it) }

                    val pingTs = lastPingPcTs
                    val pingRx = lastPingRxElapsedMs
                    val holdMs = if (pingTs > 0L && pingRx > 0L) {
                        (android.os.SystemClock.elapsedRealtime() - pingRx).coerceAtLeast(0L)
                    } else {
                        0L
                    }

                    val stats = AudioNative.getStats()
                    val json = JSONObject().apply {
                        put("type", "STATS")
                        put("buffer_ms", stats.bufferMs.toDouble())
                        put("target_ms", stats.targetMs.toDouble())
                        put("underruns", stats.underruns)
                        put("lost_packets", stats.lostPackets)
                        put("late_packets", stats.latePackets)
                        put("auto_mode", stats.autoMode)
                        put("rx_pps", stats.rxPps)
                        put("active_codec", stats.codec)
                        put("device", getPhoneModelName())
                        put("serving", AudioStreamService.isServing)
                        put("playing", AudioStreamService.isStreaming)
                        put("serve_pps", AudioStreamService.internalAudioStreamerInstance?.txPps?.get() ?: 0)
                        if (pingTs > 0L) {
                            put("echo_pc_ts", pingTs)
                            put("hold_ms", holdMs)
                        }
                    }

                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    if (!targetIp.isNullOrBlank()) {
                        try {
                            val dest = InetAddress.getByName(targetIp)
                            sock.send(DatagramPacket(data, data.size, dest, controlPort))
                        } catch (_: Exception) {}
                    } else if (AudioStreamService.isServing || AudioStreamService.isStreaming) {
                        // Also send to subnet broadcast if PC IP is not yet known
                        for (ip in getLocalIpv4Addresses()) {
                            val parts = ip.split(".")
                            if (parts.size == 4) {
                                try {
                                    val bcast = InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255")
                                    sock.send(DatagramPacket(data, data.size, bcast, controlPort))
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "Stats sender error: ${e.message}")
            } finally {
                try { statsSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    data class DiscoveredPartyHost(val ip: String, val name: String, val port: Int, val lastSeenMs: Long, val isExtended: Boolean = false)

    companion object {
        private const val TAG = "ControlChannel"
        @Volatile var isHostingOrExtendingParty: Boolean = false
        @Volatile var isExtendingParty: Boolean = false
        @Volatile var lastKnownPcIp: String? = null
        @Volatile var lastKnownPcName: String = "PC"
        val partyPeerIps = java.util.concurrent.CopyOnWriteArraySet<String>()
        val partyPeerLastSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val discoveredPartyHosts = java.util.concurrent.ConcurrentHashMap<String, DiscoveredPartyHost>()
        var onPartyHostDiscovered: (() -> Unit)? = null

        fun prunePartyHostsAndPeers() {
            val now = android.os.SystemClock.elapsedRealtime()
            var changed = false

            // Prune hosts older than 6.0 seconds
            val hostIt = discoveredPartyHosts.entries.iterator()
            while (hostIt.hasNext()) {
                val entry = hostIt.next()
                if (now - entry.value.lastSeenMs > 6000L) {
                    hostIt.remove()
                    changed = true
                }
            }

            // Prune joiners older than 10.0 seconds
            val peerIt = partyPeerLastSeen.entries.iterator()
            while (peerIt.hasNext()) {
                val entry = peerIt.next()
                if (now - entry.value > 10000L) {
                    peerIt.remove()
                    partyPeerIps.remove(entry.key)
                    changed = true
                }
            }

            if (changed) {
                AudioNative.nativeSetPartyPeers(partyPeerIps.toTypedArray())
                onPartyHostDiscovered?.invoke()
            }
        }

        fun getPreferredHostIp(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                var hotspotIp: String? = null
                var wlanIp: String? = null
                var fallbackIp: String? = null
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val name = iface.name.lowercase()
                    if (isCellularOrDummyInterface(name)) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("wlan1") || name.startsWith("SoftAp", ignoreCase = true) || ip.startsWith("192.168.43.")) {
                                hotspotIp = ip
                            } else if (name.startsWith("wlan") || name.startsWith("rndis") || name.startsWith("usb")) {
                                if (wlanIp == null) wlanIp = ip
                            } else if (fallbackIp == null) {
                                fallbackIp = ip
                            }
                        }
                    }
                }
                return hotspotIp ?: wlanIp ?: fallbackIp ?: "127.0.0.1"
            } catch (_: Exception) {
                return getLocalIpv4Addresses().firstOrNull() ?: "127.0.0.1"
            }
        }

        fun sendPartyJoinAnnouncement(hostIp: String) {
            kotlin.concurrent.thread(name = "PartyJoinAnnouncer", isDaemon = true) {
                try {
                    val sock = DatagramSocket()
                    val json = JSONObject().apply {
                        put("type", "BEACON")
                        put("app", "AirSpeaker")
                        put("version", 1)
                        put("device", getPhoneModelName())
                        put("party_join", true)
                        put("audio_port", 5004)
                    }
                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    val addr = InetAddress.getByName(hostIp)
                    val p = DatagramPacket(data, data.size, addr, 5005)
                    sock.send(p)
                    sock.close()
                    Log.i(TAG, "Sent party join announcement to $hostIp:5005")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send party join announcement: ${e.message}")
                }
            }
        }

        fun sendPartyLeaveAnnouncement(hostIp: String) {
            if (hostIp.isBlank()) return
            kotlin.concurrent.thread(name = "PartyLeaveAnnouncer", isDaemon = true) {
                try {
                    val sock = DatagramSocket()
                    val json = JSONObject().apply {
                        put("type", "BEACON")
                        put("app", "AirSpeaker")
                        put("version", 1)
                        put("device", getPhoneModelName())
                        put("party_join", false)
                        put("is_party_host", false)
                        put("is_extended_host", false)
                        put("audio_port", 5004)
                    }
                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    val addr = InetAddress.getByName(hostIp)
                    val p = DatagramPacket(data, data.size, addr, 5005)
                    sock.send(p)
                    sock.close()
                } catch (_: Exception) {}
            }
        }

        fun sendPartyHostStopAnnouncement() {
            kotlin.concurrent.thread(name = "PartyHostStopAnnouncer", isDaemon = true) {
                try {
                    val sock = DatagramSocket().apply { broadcast = true }
                    val json = JSONObject().apply {
                        put("type", "BEACON")
                        put("app", "AirSpeaker")
                        put("version", 1)
                        put("device", getPhoneModelName())
                        put("is_party_host", false)
                        put("is_extended_host", false)
                        put("party_mode", false)
                        put("audio_port", 5004)
                    }
                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), 5005))
                    sock.close()
                } catch (_: Exception) {}
            }
        }

        fun isOwnLocalIp(ip: String?): Boolean {
            if (ip.isNullOrBlank()) return false
            val trimmed = ip.trim()
            if (trimmed == "127.0.0.1" || trimmed == "0.0.0.0" || trimmed == "255.255.255.255") return true
            return getLocalIpv4Addresses().contains(trimmed)
        }

        fun sendPreStreamBeacon(targetPcIp: String? = null) {
            kotlin.concurrent.thread(name = "PreStreamBeaconSender", isDaemon = true) {
                try {
                    val sock = DatagramSocket().apply { broadcast = true }
                    val ips = getLocalIpv4Addresses()
                    val json = JSONObject().apply {
                        put("type", "BEACON")
                        put("app", "AirSpeaker")
                        put("version", 1)
                        put("ips", org.json.JSONArray(ips))
                        put("audio_port", 5004)
                        put("device", getPhoneModelName())
                        put("party_join", false)
                        put("is_party_host", isHostingOrExtendingParty && !isExtendingParty)
                        put("is_extended_host", isExtendingParty)
                        put("serving", AudioStreamService.isServing)
                        put("playing", AudioStreamService.isStreaming)
                    }
                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    try {
                        val broadcastAddr = java.net.InetAddress.getByName("255.255.255.255")
                        sock.send(DatagramPacket(data, data.size, broadcastAddr, 5005))
                    } catch (_: Exception) {}

                    val unicastTargets = mutableSetOf<String>()
                    targetPcIp?.takeIf { !isOwnLocalIp(it) }?.let { unicastTargets.add(it) }
                    lastKnownPcIp?.takeIf { !isOwnLocalIp(it) }?.let { unicastTargets.add(it) }
                    AudioNative.nativeGetSenderIp()?.takeIf { !isOwnLocalIp(it) }?.let { unicastTargets.add(it) }

                    for (target in unicastTargets) {
                        try {
                            sock.send(DatagramPacket(data, data.size, java.net.InetAddress.getByName(target), 5005))
                        } catch (_: Exception) {}
                    }

                    for (ip in ips) {
                        val parts = ip.split(".")
                        if (parts.size == 4 && !parts[0].startsWith("127")) {
                            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                            try {
                                sock.send(DatagramPacket(data, data.size, java.net.InetAddress.getByName("$prefix.255"), 5005))
                            } catch (_: Exception) {}
                            if (parts[3] != "1") {
                                try {
                                    sock.send(DatagramPacket(data, data.size, java.net.InetAddress.getByName("$prefix.1"), 5005))
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    sock.close()
                } catch (_: Exception) {}
            }
        }

        fun isCellularOrDummyInterface(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith("rmnet") ||
                   lower.startsWith("ccmni") ||
                   lower.startsWith("dummy") ||
                   lower.startsWith("lo") ||
                   lower.startsWith("p2p")
        }

        private var cachedLocalIps: List<String> = emptyList()
        private var lastIpCacheTimeMs: Long = 0L

        fun getLocalIpv4Addresses(): List<String> {
            val now = android.os.SystemClock.elapsedRealtime()
            if (cachedLocalIps.isNotEmpty() && (now - lastIpCacheTimeMs) < 3000L) {
                return cachedLocalIps
            }
            val prioritized = mutableListOf<String>()
            val fallback = mutableListOf<String>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val name = iface.name.lowercase()
                    if (isCellularOrDummyInterface(name)) continue

                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            if (name.startsWith("ap") || name.startsWith("wlan") ||
                                name.startsWith("swlan") || name.startsWith("rndis") ||
                                name.startsWith("usb") || name.startsWith("ncm") ||
                                name.startsWith("bt-pan") || name.startsWith("bnep")) {
                                prioritized.add(ip)
                            } else {
                                fallback.add(ip)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ControlChannel", "Error enumerating IP addresses", e)
            }
            val res = prioritized + fallback
            cachedLocalIps = res
            lastIpCacheTimeMs = now
            return res
        }

        fun getPhoneModelName(): String {
            val manufacturer = android.os.Build.MANUFACTURER?.trim().orEmpty()
            val model = android.os.Build.MODEL?.trim().orEmpty()
            return if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "${manufacturer.replaceFirstChar { it.uppercase() }} $model".trim()
            }
        }
    }
}

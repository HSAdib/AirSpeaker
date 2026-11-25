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

data class DiscoveredServer(
    val ip: String,
    val hostname: String,
    val port: Int,
    val serving: Boolean,
    val platform: String,
    val lastSeenMs: Long
)

class ControlChannel(
    @Volatile var audioPort: Int = configuredAudioPort,
    private val controlPort: Int = 5005,
    @Volatile var onConfigSyncReceived: ((JSONObject) -> Unit)? = null,
    @Volatile var onPartyKicked: ((String) -> Unit)? = null
) {
    private val TAG = "ControlChannel"
    @Volatile
    var isPartyJoinerActive: Boolean = false
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
    var lastPcSeenElapsedMs: Long = 0L

    fun getActivePcIp(): String? = activePcIp

    fun clearActivePcIp() {
        activePcIp = null
        activePcName = "PC"
    }

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

    fun sendConfigSync(json: JSONObject, targetIp: String? = null, extraTargets: List<String> = emptyList()) {
        val isPartyHostBroadcast = extraTargets.isNotEmpty() || (isHostingOrExtendingParty && !isExtendingParty)
        val sel = selectedServerIp
        if (!isPartyHostBroadcast && !isPartyJoinerActive && !Companion.isPartyJoinerActive && !AudioStreamService.isServing && !sel.isNullOrBlank()) {
            json.put("target_server_ip", sel)
        } else {
            json.remove("target_server_ip")
        }
        val lockedSrv = selectedServerIp?.takeIf { !isOwnLocalIp(it) }
        val explicitDest = targetIp?.takeIf { !isOwnLocalIp(it) }
        thread(name = "ConfigSyncSender", isDaemon = true) {
            try {
                val data = json.toString().toByteArray(Charsets.UTF_8)
                val sock = DatagramSocket().apply { broadcast = true }
                val targetList = mutableSetOf<String>()
                if (extraTargets.isNotEmpty()) {
                    for (et in extraTargets) {
                        val clean = et.trim()
                        if (clean.isNotEmpty() && !isOwnLocalIp(clean)) {
                            targetList.add(clean)
                        }
                    }
                    if (!explicitDest.isNullOrBlank() && !isPartyHostBroadcast) {
                        targetList.add(explicitDest)
                    }
                } else {
                    val finalDest = explicitDest ?: lockedSrv
                    if (!finalDest.isNullOrBlank()) {
                        targetList.add(finalDest)
                    }
                }
                if (targetList.isEmpty()) {
                    sock.close()
                    return@thread
                }

                // 2-shot reliable UDP send (immediate + 80ms retry with identical sync_id)
                for (attempt in 0..1) {
                    if (attempt == 1) Thread.sleep(80)
                    for (tIp in targetList) {
                        try {
                            sock.send(DatagramPacket(data, data.size, InetAddress.getByName(tIp), controlPort))
                        } catch (_: Exception) {}
                    }
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
                            val labelName = if (isExt) "$devName (Extended Host)" else if (isHost) "$devName (Party Host)" else devName
                            if (!isOwnLocalIp(senderIp)) {
                                if (json.optBoolean("party_discover", false) && isHostingOrExtendingParty) {
                                    sendPreStreamBeacon(senderIp)
                                }
                                val isPartyHostBeacon = isHost || isExt || json.optBoolean("party_mode", false)
                                if (isPartyHostBeacon) {
                                    // Party Hosts must NEVER appear in PLAY tab Discovered Servers
                                    if (discoveredServers.remove(senderIp) != null) {
                                        reconcileServers()
                                    }
                                    if (isPartyJoinScreenActive || isPartyJoinerActive || Companion.isPartyJoinerActive) {
                                        discoveredPartyHosts[senderIp] = DiscoveredPartyHost(senderIp, labelName, aPort, android.os.SystemClock.elapsedRealtime(), isExt)
                                        onPartyHostDiscovered?.invoke()
                                    }
                                } else {
                                    if (json.has("is_party_host") || json.has("is_extended_host") || json.optBoolean("party_leave", false)) {
                                        if (discoveredPartyHosts.remove(senderIp) != null) {
                                            onPartyHostDiscovered?.invoke()
                                        }
                                    }
                                    val isServingBeacon = json.optBoolean("serving", false)
                                    if (isServingBeacon) {
                                        val sDev = json.optString("device", "Phone Server")
                                        val sPort = json.optInt("audio_port", 5004)
                                        val sPlat = json.optString("platform", "android")
                                        registerDiscoveredServer(senderIp, sDev, sPort, true, sPlat)
                                    } else if (discoveredServers[senderIp]?.platform != "pc") {
                                        if (discoveredServers.remove(senderIp) != null) {
                                            reconcileServers()
                                        }
                                    }
                                }

                                // If this phone is in 1-to-1 SERVE mode and a Player explicitly selected us, pair to it
                                val targetSrvIp = json.optString("target_server_ip", "").trim()
                                if (AudioStreamService.isServing && !isHostingOrExtendingParty &&
                                    targetSrvIp.isNotEmpty() && isOwnLocalIp(targetSrvIp)
                                ) {
                                    AudioStreamService.internalAudioStreamerInstance?.updateTargetIp(senderIp)
                                    AudioStreamService.internalAudioStreamerInstance?.updateTargetPort(aPort)
                                }

                                val discSrvIp = json.optString("disconnected_server_ip", "").trim()
                                if (AudioStreamService.isServing && !isHostingOrExtendingParty &&
                                    discSrvIp.isNotEmpty() && isOwnLocalIp(discSrvIp)
                                ) {
                                    AudioStreamService.internalAudioStreamerInstance?.updateTargetIp("0.0.0.0")
                                }

                                // Rule 4: Handle downstream peers
                                val wantsJoin = json.optBoolean("party_join", false)
                                val explicitLeave = json.optBoolean("party_leave", false)
                                val targetHostIp = json.optString("party_host_ip", "").trim()
                                val myLocalIps = getLocalIpv4Addresses()
                                val addressedToThisHost = targetHostIp.isEmpty() || myLocalIps.contains(targetHostIp) || targetHostIp == "255.255.255.255"

                                if (isHostingOrExtendingParty || AudioNative.nativeIsPartyRelay()) {
                                    if (wantsJoin && !isHost && addressedToThisHost) {
                                        partyPeerLastSeen[senderIp] = android.os.SystemClock.elapsedRealtime()
                                        if (partyPeerIps.add(senderIp)) {
                                            AudioNative.nativeSetPartyPeers(partyPeerIps.toTypedArray())
                                            onPartyHostDiscovered?.invoke()
                                            onPartyPeerJoined?.invoke(senderIp)
                                            Log.i(TAG, "Party Mode: registered downstream peer $senderIp (total=${partyPeerIps.size})")
                                        }
                                    } else if (explicitLeave && addressedToThisHost) {
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

                        if (msgType == "PC_ANNOUNCE") {
                            val pcName = json.optString("pc_name", "")
                            val hostname = if (pcName.isNotBlank()) pcName else json.optString("device", "PC Server")
                            val aPort = json.optInt("audio_port", json.optInt("port", 5004))
                            val isServing = json.optBoolean("serving", false)
                            val platform = json.optString("platform", "pc")
                            val isPcExt = json.optBoolean("is_extended_host", false)
                            val isPcPartyHost = json.optBoolean("is_party_host", false) || isPcExt || json.optBoolean("party_mode", false)

                            if (isPcPartyHost) {
                                if (discoveredServers.remove(senderIp) != null) {
                                    reconcileServers()
                                }
                                if (isPartyJoinScreenActive || isPartyJoinerActive || Companion.isPartyJoinerActive) {
                                    val hostLabel = if (isPcExt) "$hostname (Extended Host)" else hostname
                                    discoveredPartyHosts[senderIp] = DiscoveredPartyHost(senderIp, hostLabel, aPort, android.os.SystemClock.elapsedRealtime(), isPcExt)
                                    onPartyHostDiscovered?.invoke()
                                }
                            } else {
                                registerDiscoveredServer(senderIp, hostname, aPort, isServing, platform)
                                if (json.has("is_party_host") || json.has("party_mode")) {
                                    if (discoveredPartyHosts.remove(senderIp) != null) {
                                        onPartyHostDiscovered?.invoke()
                                    }
                                }
                            }

                            val pcTargetSrvIp = json.optString("target_server_ip", "").trim()
                            if (AudioStreamService.isServing && !isHostingOrExtendingParty && !isPcPartyHost &&
                                json.optBoolean("playing", false) &&
                                pcTargetSrvIp.isNotEmpty() && isOwnLocalIp(pcTargetSrvIp)
                            ) {
                                AudioStreamService.internalAudioStreamerInstance?.updateTargetIp(senderIp)
                                AudioStreamService.internalAudioStreamerInstance?.updateTargetPort(aPort)
                            }

                            val discSrvIp = json.optString("disconnected_server_ip", "").trim()
                            if (AudioStreamService.isServing && !isHostingOrExtendingParty &&
                                discSrvIp.isNotEmpty() && isOwnLocalIp(discSrvIp)
                            ) {
                                AudioStreamService.internalAudioStreamerInstance?.updateTargetIp("0.0.0.0")
                            }

                            val lockedIp = selectedServerIp
                            if (!isPartyJoinerActive && !Companion.isPartyJoinerActive && !isPcPartyHost && !lockedIp.isNullOrBlank() && senderIp == lockedIp) {
                                activePcIp = senderIp
                                lastKnownPcIp = senderIp
                                if (pcName.isNotBlank()) {
                                    activePcName = pcName
                                    lastKnownPcName = pcName
                                }
                            }
                            Log.i(TAG, "Discovered active PC IP via PC_ANNOUNCE: $senderIp ($activePcName)")
                        } else if (msgType == "CONFIG_SYNC") {
                            val targetSrvIp = json.optString("target_server_ip", "").trim()
                            if (targetSrvIp.isNotEmpty() && !isOwnLocalIp(targetSrvIp)) {
                                continue
                            }
                            val targetedUs = targetSrvIp.isNotEmpty() && isOwnLocalIp(targetSrvIp)
                            if (!isPartyJoinerActive && !Companion.isPartyJoinerActive) {
                                val lockedIp = selectedServerIp
                                val matchesPlay = !lockedIp.isNullOrBlank() && senderIp == lockedIp
                                val matchesServe = AudioStreamService.isServing && targetedUs
                                if (!matchesPlay && !matchesServe) {
                                    continue
                                }
                            }
                            val lockedIp = selectedServerIp
                            if (!lockedIp.isNullOrBlank() && senderIp == lockedIp) {
                                activePcIp = senderIp
                                lastKnownPcIp = senderIp
                                val pcName = json.optString("pc_name", "")
                                if (pcName.isNotBlank()) {
                                    activePcName = pcName
                                    lastKnownPcName = pcName
                                }
                            }
                            json.put("_sender_ip", senderIp)
                            onConfigSyncReceived?.invoke(json)
                        } else if (msgType == "PARTY_KICK") {
                            val targetIp = json.optString("target_ip", "").trim()
                            if (targetIp.isEmpty() || isOwnLocalIp(targetIp)) {
                                val reason = json.optString("reason", "Removed by party host")
                                onPartyKicked?.invoke(reason)
                                Companion.onPartyKicked?.invoke(reason)
                            }
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
                    val sel = selectedServerIp
                    val json = JSONObject().apply {
                        put("type", "BEACON")
                        put("app", "AirSpeaker")
                        put("version", 1)
                        put("ips", JSONArray(ips))
                        put("audio_port", configuredAudioPort)
                        put("device", getPhoneModelName())
                        put("party_join", isPartyJoinerActive || Companion.isPartyJoinerActive)
                        if (isPartyJoinerActive || Companion.isPartyJoinerActive) {
                            joinedPartyHostIp?.let { put("party_host_ip", it) }
                        }
                        put("is_party_host", isHostingOrExtendingParty && !isExtendingParty)
                        put("is_extended_host", isExtendingParty)
                        put("serving", AudioStreamService.isServing && !isHostingOrExtendingParty)
                        put("playing", AudioStreamService.isStreaming)
                        if (!(isPartyJoinerActive || Companion.isPartyJoinerActive) && !isHostingOrExtendingParty && !sel.isNullOrBlank()) {
                            put("target_server_ip", sel)
                        }
                    }
                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    try {
                        val broadcastAddr = InetAddress.getByName("255.255.255.255")
                        sock.send(DatagramPacket(data, data.size, broadcastAddr, controlPort))
                    } catch (_: Exception) {}

                    val unicastTargets = mutableSetOf<String>()
                    sel?.let { unicastTargets.add(it) }
                    activePcIp?.takeIf { !isOwnLocalIp(it) }?.let { unicastTargets.add(it) }
                    lastKnownPcIp?.takeIf { !isOwnLocalIp(it) }?.let { unicastTargets.add(it) }
                    AudioNative.nativeGetSenderIp()?.takeIf { !isOwnLocalIp(it) }?.let { unicastTargets.add(it) }
                    if (isPartyJoinerActive || Companion.isPartyJoinerActive) {
                        joinedPartyHostIp?.takeIf { !isOwnLocalIp(it) }?.let { unicastTargets.add(it) }
                    }
                    if (isHostingOrExtendingParty) {
                        partyPeerIps.forEach { if (!isOwnLocalIp(it)) unicastTargets.add(it) }
                    }

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
                    val stats = AudioNative.getStats()
                    val targetIp = when {
                        isPartyJoinerActive || Companion.isPartyJoinerActive -> joinedPartyHostIp?.takeIf { !isOwnLocalIp(it) }
                        !selectedServerIp.isNullOrBlank() -> selectedServerIp?.takeIf { !isOwnLocalIp(it) }
                        else -> null
                    }

                    val totalPkts = stats.rxPackets + stats.lostPackets
                    val lossPct = if (totalPkts > 0L) (stats.lostPackets.toDouble() * 100.0) / totalPkts.toDouble() else 0.0
                    val sel = selectedServerIp
                    val json = JSONObject().apply {
                        put("type", "STATS")
                        put("buffer_ms", stats.bufferMs.toDouble())
                        put("target_ms", stats.targetMs.toDouble())
                        put("underruns", stats.underruns)
                        put("lost_packets", stats.lostPackets)
                        put("packet_loss_pct", lossPct)
                        put("late_packets", stats.latePackets)
                        put("auto_mode", stats.autoMode)
                        put("rx_pps", stats.rxPps)
                        put("active_codec", stats.codec)
                        put("codec", if (stats.codec == 1) "Opus" else "PCM16")
                        put("device", getPhoneModelName())
                        put("serving", AudioStreamService.isServing)
                        put("playing", AudioStreamService.isStreaming)
                        put("serve_pps", AudioStreamService.internalAudioStreamerInstance?.txPps?.get() ?: 0)
                        if (!isPartyJoinerActive && !Companion.isPartyJoinerActive && !sel.isNullOrBlank()) {
                            put("target_server_ip", sel)
                        }
                    }

                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    if (!targetIp.isNullOrBlank()) {
                        try {
                            val dest = InetAddress.getByName(targetIp)
                            sock.send(DatagramPacket(data, data.size, dest, controlPort))
                        } catch (_: Exception) {}
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
        @Volatile var configuredAudioPort: Int = 5004
        @Volatile var isPartyJoinerActive: Boolean = false
        @Volatile var isPartyJoinScreenActive: Boolean = false
        @Volatile var isHostingOrExtendingParty: Boolean = false
        @Volatile var isExtendingParty: Boolean = false

        fun sendPartyDiscoverProbe() {
            kotlin.concurrent.thread(name = "PartyDiscoverProbe", isDaemon = true) {
                try {
                    DatagramSocket().use { sock ->
                        sock.broadcast = true
                        val ips = getLocalIpv4Addresses()
                        val json = JSONObject().apply {
                            put("type", "BEACON")
                            put("app", "AirSpeaker")
                            put("version", 1)
                            put("ips", JSONArray(ips))
                            put("audio_port", configuredAudioPort)
                            put("device", getPhoneModelName())
                            put("party_discover", true)
                            put("party_join", false)
                            put("is_party_host", false)
                            put("is_extended_host", false)
                            put("serving", false)
                            put("playing", false)
                        }
                        val data = json.toString().toByteArray(Charsets.UTF_8)
                        val targets = mutableSetOf("255.255.255.255")
                        for (ip in ips) {
                            val parts = ip.split(".")
                            if (parts.size == 4 && !parts[0].startsWith("127")) {
                                targets.add("${parts[0]}.${parts[1]}.${parts[2]}.255")
                                if (parts[3] != "1") targets.add("${parts[0]}.${parts[1]}.${parts[2]}.1")
                            }
                        }
                        for (t in targets) {
                            try {
                                sock.send(DatagramPacket(data, data.size, InetAddress.getByName(t), 5005))
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        @Volatile var lastKnownPcIp: String? = null
        @Volatile var lastKnownPcName: String = "PC"
        val partyPeerIps = java.util.concurrent.CopyOnWriteArraySet<String>()
        val manualPartyPeerIps = java.util.concurrent.CopyOnWriteArraySet<String>()
        @Volatile var joinedPartyHostIp: String? = null
        val partyPeerLastSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val discoveredPartyHosts = java.util.concurrent.ConcurrentHashMap<String, DiscoveredPartyHost>()
        var onPartyHostDiscovered: (() -> Unit)? = null
        @Volatile var onPartyPeerJoined: ((String) -> Unit)? = null
        @Volatile var onPartyKicked: ((String) -> Unit)? = null

        val discoveredServers = java.util.concurrent.ConcurrentHashMap<String, DiscoveredServer>()
        @Volatile var selectedServerIp: String? = null
        @Volatile var isManualServerSelection: Boolean = false
        @Volatile var onServersChanged: ((List<DiscoveredServer>) -> Unit)? = null

        fun selectServer(ip: String?, manual: Boolean = true) {
            val cleanIp = ip?.trim().takeIf { !it.isNullOrBlank() && !isOwnLocalIp(it) }
            selectedServerIp = cleanIp
            isManualServerSelection = manual && cleanIp != null
            AudioNative.safeSetAllowedSenderIp(cleanIp ?: "", requireMatch = true)
            if (cleanIp != null) {
                lastKnownPcIp = cleanIp
            }
            reconcileServers()
        }

        fun sendDisconnectNotice(serverIp: String?) {
            val cleanIp = serverIp?.trim().takeIf { !it.isNullOrBlank() && !isOwnLocalIp(it) } ?: return
            kotlin.concurrent.thread(name = "DisconnectNoticeSender", isDaemon = true) {
                try {
                    DatagramSocket().use { sock ->
                        sock.broadcast = true
                        val ips = getLocalIpv4Addresses()
                        val json = JSONObject().apply {
                            put("type", "BEACON")
                            put("app", "AirSpeaker")
                            put("version", 1)
                            put("ips", JSONArray(ips))
                            put("audio_port", configuredAudioPort)
                            put("device", getPhoneModelName())
                            put("party_join", false)
                            put("is_party_host", false)
                            put("is_extended_host", false)
                            put("serving", AudioStreamService.isServing)
                            put("playing", false)
                            put("disconnected_server_ip", cleanIp)
                        }
                        val data = json.toString().toByteArray(Charsets.UTF_8)
                        val dest = InetAddress.getByName(cleanIp)
                        for (attempt in 0..1) {
                            if (attempt == 1) Thread.sleep(80)
                            try {
                                sock.send(DatagramPacket(data, data.size, dest, 5005))
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        fun registerDiscoveredServer(ip: String, hostname: String, port: Int, serving: Boolean, platform: String) {
            val cleanIp = ip.trim()
            if (cleanIp.isEmpty() || isOwnLocalIp(cleanIp)) return
            val now = android.os.SystemClock.elapsedRealtime()
            val existing = discoveredServers[cleanIp]
            val s = DiscoveredServer(
                ip = cleanIp,
                hostname = if (hostname.isNotBlank()) hostname else (existing?.hostname ?: "AirSpeaker Server"),
                port = if (port > 0) port else (existing?.port ?: 5004),
                serving = serving,
                platform = if (platform.isNotBlank()) platform else (existing?.platform ?: "pc"),
                lastSeenMs = now
            )
            discoveredServers[cleanIp] = s
            reconcileServers()
        }

        fun reconcileServers() {
            val now = android.os.SystemClock.elapsedRealtime()
            val iterator = discoveredServers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.lastSeenMs > 8000L) {
                    iterator.remove()
                }
            }
            val active = discoveredServers.values
                .filter { now - it.lastSeenMs < 8000L }
                .sortedBy { it.hostname.lowercase() }
            onServersChanged?.invoke(active)
        }

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

            // Prune joiners older than 10.0 seconds (never prune manually added peers)
            val peerIt = partyPeerLastSeen.entries.iterator()
            while (peerIt.hasNext()) {
                val entry = peerIt.next()
                if (manualPartyPeerIps.contains(entry.key)) continue
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
                            val isHotspotIface = name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("wlan1") || name.startsWith("wlan2") || name.contains("softap") || name.contains("tether") || ((name.startsWith("wlan") || name.startsWith("ap")) && ip.endsWith(".1"))
                            if (isHotspotIface || ip.startsWith("192.168.43.")) {
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

        fun sendPartyJoinAnnouncement(hostIp: String, listenPort: Int = configuredAudioPort) {
            joinedPartyHostIp = hostIp.trim()
            isPartyJoinerActive = true
            kotlin.concurrent.thread(name = "PartyJoinAnnouncer", isDaemon = true) {
                try {
                    val sock = DatagramSocket()
                    val json = JSONObject().apply {
                        put("type", "BEACON")
                        put("app", "AirSpeaker")
                        put("version", 1)
                        put("device", getPhoneModelName())
                        put("party_join", true)
                        put("party_host_ip", hostIp.trim())
                        put("is_party_host", false)
                        put("is_extended_host", isExtendingParty)
                        put("audio_port", listenPort)
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

        fun sendPartyLeaveAnnouncement(hostIp: String?) {
            isPartyJoinerActive = false
            joinedPartyHostIp = null
            val cleanIp = hostIp?.trim().orEmpty()
            if (cleanIp.isEmpty()) return
            kotlin.concurrent.thread(name = "PartyLeaveAnnouncer", isDaemon = true) {
                try {
                    val sock = DatagramSocket()
                    val json = JSONObject().apply {
                        put("type", "BEACON")
                        put("app", "AirSpeaker")
                        put("version", 1)
                        put("device", getPhoneModelName())
                        put("party_join", false)
                        put("party_leave", true)
                        put("party_host_ip", cleanIp)
                        put("is_party_host", false)
                        put("is_extended_host", false)
                        put("audio_port", configuredAudioPort)
                    }
                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    val addr = InetAddress.getByName(cleanIp)
                    val p = DatagramPacket(data, data.size, addr, 5005)
                    sock.send(p)
                    sock.close()
                } catch (_: Exception) {}
            }
        }

        fun sendPartyKick(peerIp: String, reason: String = "Removed by party host") {
            val cleanIp = peerIp.trim()
            if (cleanIp.isEmpty()) return
            kotlin.concurrent.thread(name = "PartyKickSender", isDaemon = true) {
                try {
                    val sock = DatagramSocket()
                    val json = JSONObject().apply {
                        put("type", "PARTY_KICK")
                        put("app", "AirSpeaker")
                        put("version", 1)
                        put("target_ip", cleanIp)
                        put("reason", reason)
                    }
                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    val addr = InetAddress.getByName(cleanIp)
                    sock.send(DatagramPacket(data, data.size, addr, 5005))
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
                        put("party_leave", true)
                        put("audio_port", configuredAudioPort)
                    }
                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    val targets = mutableSetOf("255.255.255.255")
                    targets.addAll(partyPeerIps)
                    for (ip in getLocalIpv4Addresses()) {
                        val parts = ip.split(".")
                        if (parts.size == 4 && !parts[0].startsWith("127")) {
                            targets.add("${parts[0]}.${parts[1]}.${parts[2]}.255")
                        }
                    }
                    for (t in targets) {
                        try {
                            sock.send(DatagramPacket(data, data.size, InetAddress.getByName(t), 5005))
                        } catch (_: Exception) {}
                    }
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
                    val sel = selectedServerIp
                    val json = JSONObject().apply {
                        put("type", "BEACON")
                        put("app", "AirSpeaker")
                        put("version", 1)
                        put("ips", org.json.JSONArray(ips))
                        put("audio_port", configuredAudioPort)
                        put("device", getPhoneModelName())
                        put("party_join", isPartyJoinerActive)
                        if (isPartyJoinerActive) {
                            joinedPartyHostIp?.let { put("party_host_ip", it) }
                        }
                        put("is_party_host", isHostingOrExtendingParty && !isExtendingParty)
                        put("is_extended_host", isExtendingParty)
                        put("serving", AudioStreamService.isServing && !isHostingOrExtendingParty)
                        put("playing", AudioStreamService.isStreaming)
                        if (!isPartyJoinerActive && !isHostingOrExtendingParty && !sel.isNullOrBlank()) {
                            put("target_server_ip", sel)
                        }
                    }
                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    try {
                        val broadcastAddr = java.net.InetAddress.getByName("255.255.255.255")
                        sock.send(DatagramPacket(data, data.size, broadcastAddr, 5005))
                    } catch (_: Exception) {}

                    val unicastTargets = mutableSetOf<String>()
                    sel?.let { unicastTargets.add(it) }
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
            if (cachedLocalIps.isNotEmpty() && (now - lastIpCacheTimeMs) < 1500L) {
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

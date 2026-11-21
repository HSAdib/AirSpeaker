package com.hasan.airspeaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class AudioStreamService : Service() {

    companion object {
        const val TAG = "AudioStreamService"
        const val ACTION_START = "com.hasan.airspeaker.START"
        const val ACTION_STOP = "com.hasan.airspeaker.STOP"
        const val ACTION_START_SERVE = "com.hasan.airspeaker.START_SERVE"
        const val ACTION_STOP_SERVE = "com.hasan.airspeaker.STOP_SERVE"
        const val ACTION_STATE_CHANGED = "com.hasan.airspeaker.STATE_CHANGED"
        const val ACTION_CONFIG_SYNC_RECEIVED = "com.hasan.airspeaker.CONFIG_SYNC"

        const val EXTRA_STREAMING = "streaming"
        const val EXTRA_SERVING = "serving"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_TARGET_IP = "target_ip"
        const val EXTRA_TARGET_PORT = "target_port"
        const val EXTRA_AUDIO_ENGINE = "extra_audio_engine"
        const val EXTRA_AUDIO_FX = "extra_audio_fx"
        const val EXTRA_AUDIO_PORT = "extra_audio_port"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        const val CHANNEL_ID = "AirSpeakerServiceChannel"
        const val NOTIFICATION_ID = 1001

        var isStreaming = false
            private set

        var isServing = false
            private set

        var internalAudioStreamerInstance: InternalAudioStreamer? = null
            private set

        var activeControlChannel: ControlChannel? = null
            private set

        var instance: AudioStreamService? = null
            private set

        @Volatile var isBluetoothRouteActive: Boolean = false
            private set

        @Volatile var activeBackendLabel: String = "OBOE"
            private set

        @Volatile var isUserMuted: Boolean = false
            private set

        fun setUserVolumeGain(gain: Float) {
            val clamped = gain.coerceIn(0f, 2f)
            instance?.savedVolumeGain = clamped
            AudioNative.safeSetVolumeGain(clamped)
        }

        fun setUserMuted(muted: Boolean) {
            isUserMuted = muted
            val svc = instance
            if (svc != null) {
                svc.userMuted = muted
                if (!muted) {
                    svc.pausedByHeadset = false
                    svc.pausedByFocus = false
                }
                svc.applyEffectiveMuteAndGain()
            } else {
                AudioNative.safeSetMuted(muted)
            }
        }

        private var lastServiceConfigRev = 0
        private val seenServiceSyncIds = ArrayDeque<Int>(32)
        private val lastServiceRevBySession = HashMap<Int, Int>()
        private var latestMergedConfig: JSONObject? = null

        @Synchronized
        fun getLatestConfigRev(): Int = lastServiceConfigRev

        @Synchronized
        fun getLatestSyncedConfig(): JSONObject? = latestMergedConfig?.let { JSONObject(it.toString()) }

        @Synchronized
        private fun cacheMergedConfig(json: JSONObject) {
            val merged = latestMergedConfig ?: JSONObject()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                merged.put(k, json.get(k))
            }
            latestMergedConfig = merged
        }

        @Synchronized
        fun markLocalConfigSent(rev: Int, syncId: Int, json: JSONObject? = null) {
            if (rev > lastServiceConfigRev) {
                lastServiceConfigRev = rev
            }
            if (seenServiceSyncIds.size >= 32) {
                seenServiceSyncIds.removeFirst()
            }
            seenServiceSyncIds.addLast(syncId)
            if (json != null) {
                cacheMergedConfig(json)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var internalStreamer: InternalAudioStreamer? = null
    private var exclusiveAudioMode = "MIX_WITH_OTHERS"
    private var stopOnHeadsetDisconnect = false
    private var audioOutputEngine = "OBOE_LOW_LATENCY"
    private var enableAudioEffects = false
    private var userMuted = false
    private var pausedByHeadset = false
    private var pausedByFocus = false
    private var savedVolumeGain = 1.0f
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var lastAttachedSessionId = 0
    private var currentMediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private val serviceHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun applyEffectiveMuteAndGain() {
        val effectiveMute = userMuted || pausedByHeadset || pausedByFocus
        AudioNative.nativeSetMuted(effectiveMute)
    }

    private var lastReportedBtRoute: Boolean? = null

    private val audioTrackLock = Any()
    private var audioTrackInstance: AudioTrack? = null
    private var audioTrackThread: Thread? = null
    @Volatile private var audioTrackRunning = false

    private fun shouldUseAudioTrack(btConnected: Boolean = isBluetoothRouteActive): Boolean {
        val userAudioTrack = (audioOutputEngine == "AUDIOTRACK_STANDARD") || enableAudioEffects
        return userAudioTrack || btConnected
    }

    private fun startAudioTrackOutput() {
        synchronized(audioTrackLock) {
            if (audioTrackRunning) return
            audioTrackRunning = true

            audioTrackThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val sampleRate = 48000
                val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val framesPerBlock = 480 // 10 ms stereo
                val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                    .coerceAtLeast(framesPerBlock * 4 * 2)
                // 120 ms AudioFlinger buffer so Bluetooth A2DP (SBC/AAC/LDAC) & BLE Audio never crackle
                val targetBufSize = (sampleRate * 4 * 120) / 1000
                val bufSize = maxOf(minBufSize * 2, targetBufSize)

                var track: AudioTrack? = null
                try {
                    val builder = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .setEncoding(audioFormat)
                                .build()
                        )
                        .setBufferSizeInBytes(bufSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                    }
                    track = builder.build()
                    if (track.state != AudioTrack.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioTrack failed to initialize")
                        return@Thread
                    }

                    synchronized(audioTrackLock) {
                        audioTrackInstance = track
                    }
                    track.play()
                    activeBackendLabel = if (isBluetoothRouteActive) "BT • AudioTrack" else "AudioTrack"
                    Log.i(TAG, "AudioTrack started: bufSize=$bufSize minBuf=$minBufSize sessionId=${track.audioSessionId} label=$activeBackendLabel")
                    serviceHandler.post { updateAudioEffects() }

                    val shortBuf = ShortArray(framesPerBlock * 2)
                    while (audioTrackRunning && isStreaming && !Thread.currentThread().isInterrupted) {
                        val framesRendered = AudioNative.nativeReadExternalPcm(shortBuf, framesPerBlock)
                        if (framesRendered > 0) {
                            val shortsToWrite = framesRendered * 2
                            var written = 0
                            while (written < shortsToWrite && audioTrackRunning && isStreaming) {
                                val w = track.write(shortBuf, written, shortsToWrite - written, AudioTrack.WRITE_BLOCKING)
                                if (w < 0) {
                                    Log.w(TAG, "AudioTrack write error=$w")
                                    Thread.sleep(10)
                                    break
                                }
                                if (w == 0) break
                                written += w
                            }
                        } else {
                            Thread.sleep(2)
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    if (audioTrackRunning) {
                        Log.e(TAG, "AudioTrack playback error: ${e.message}", e)
                    }
                } finally {
                    synchronized(audioTrackLock) {
                        if (audioTrackInstance === track) {
                            audioTrackInstance = null
                        }
                        audioTrackRunning = false
                    }
                    try { track?.pause() } catch (_: Exception) {}
                    try { track?.flush() } catch (_: Exception) {}
                    try { track?.stop() } catch (_: Exception) {}
                    try { track?.release() } catch (_: Exception) {}
                    Log.i(TAG, "AudioTrack output stopped cleanly")
                }
            }, "AirSpeaker-AudioTrackOut").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun stopAudioTrackOutput() {
        val threadToJoin: Thread?
        val trackToUnblock: AudioTrack?
        synchronized(audioTrackLock) {
            audioTrackRunning = false
            threadToJoin = audioTrackThread
            audioTrackThread = null
            trackToUnblock = audioTrackInstance
        }
        // Unblock any blocking track.write() safely BEFORE joining; worker finally{} releases the track
        try { trackToUnblock?.pause() } catch (_: Exception) {}
        try { trackToUnblock?.flush() } catch (_: Exception) {}
        threadToJoin?.interrupt()
        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            try {
                threadToJoin.join(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun updateOutputEngineLive(engineName: String, fxEnabled: Boolean) {
        audioOutputEngine = engineName
        enableAudioEffects = fxEnabled
        val lowLatency = (audioOutputEngine == "OBOE_LOW_LATENCY" && !enableAudioEffects)
        AudioNative.nativeSetLowLatencyMode(lowLatency)
        AudioNative.nativeSetEnableAudioEffects(enableAudioEffects)
        syncBluetoothRouteToNative(force = true)
    }

    private fun isBluetoothDeviceType(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == AudioDeviceInfo.TYPE_BLE_BROADCAST) {
            return true
        }
        return false
    }

    private fun isBluetoothOutputConnected(): Boolean {
        val am = audioManager ?: return false
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isBluetoothDeviceType(it.type) }
        } catch (_: Exception) {
            false
        }
    }

    private fun syncBluetoothRouteToNative(force: Boolean = false) {
        val btConnected = isBluetoothOutputConnected()
        val wantAudioTrack = shouldUseAudioTrack(btConnected)
        isBluetoothRouteActive = btConnected
        activeBackendLabel = when {
            btConnected -> "BT • AudioTrack"
            wantAudioTrack -> "AudioTrack"
            else -> "Oboe"
        }

        val routeChanged = (lastReportedBtRoute != btConnected)
        val backendMismatch = (isStreaming && audioTrackRunning != wantAudioTrack)

        if (force || routeChanged || backendMismatch) {
            lastReportedBtRoute = btConnected
            Log.i(TAG, "Audio route sync: bluetooth=$btConnected wantAudioTrack=$wantAudioTrack streaming=$isStreaming (force=$force, routeChanged=$routeChanged, backendMismatch=$backendMismatch)")
            if (isStreaming) {
                if (wantAudioTrack) {
                    AudioNative.nativeSetUseExternalOutput(true)
                    if (routeChanged || backendMismatch || force) {
                        AudioNative.nativeOnAudioRouteChanged(btConnected)
                    }
                    startAudioTrackOutput()
                } else {
                    stopAudioTrackOutput()
                    AudioNative.nativeOnAudioRouteChanged(false)
                    AudioNative.nativeSetUseExternalOutput(false)
                }
                updateAudioEffects()
            } else {
                AudioNative.nativeSetUseExternalOutput(wantAudioTrack)
                AudioNative.nativeOnAudioRouteChanged(btConnected)
            }
            notifyStateChanged()
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val hasBt = addedDevices.any { it.isSink && isBluetoothDeviceType(it.type) }
            if (hasBt) {
                pausedByHeadset = false
                applyEffectiveMuteAndGain()
            }
            // Pass force=false so the 700ms retry only acts if the route wasn't already switched at 150ms
            serviceHandler.postDelayed({ syncBluetoothRouteToNative(force = false) }, 150L)
            serviceHandler.postDelayed({ syncBluetoothRouteToNative(force = false) }, 700L)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val removedBt = removedDevices.any { it.isSink && isBluetoothDeviceType(it.type) }
            if (removedBt && stopOnHeadsetDisconnect && isStreaming) {
                pausedByHeadset = true
                applyEffectiveMuteAndGain()
                notifyStateChanged()
            }
            serviceHandler.postDelayed({ syncBluetoothRouteToNative(force = false) }, 150L)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    if (stopOnHeadsetDisconnect && isStreaming) {
                        Log.i(TAG, "Headset disconnected — pausing stream")
                        pausedByHeadset = true
                        applyEffectiveMuteAndGain()
                        notifyStateChanged()
                    }
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    if (state == 0 && stopOnHeadsetDisconnect && isStreaming) {
                        pausedByHeadset = true
                        applyEffectiveMuteAndGain()
                        notifyStateChanged()
                    }
                }
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" -> {
                    Log.i(TAG, "Bluetooth A2DP connection state changed")
                    serviceHandler.postDelayed({ syncBluetoothRouteToNative(force = false) }, 200L)
                    serviceHandler.postDelayed({ syncBluetoothRouteToNative(force = false) }, 800L)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "AudioStreamService.onCreate")
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        }
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, serviceHandler)
        syncBluetoothRouteToNative(force = true)
    }

    override fun onDestroy() {
        Log.d(TAG, "AudioStreamService.onDestroy")
        serviceHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {}
        try {
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (_: Exception) {}

        stopAudioPlayer()
        stopServingInternalAudio()
        releaseLocks()

        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {}
        loudnessEnhancer = null
        lastAttachedSessionId = 0
        instance = null

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "AudioStreamService.onStartCommand action=$action")
        if (action == null) {
            if (!isStreaming && !isServing) {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val targetIp = intent.getStringExtra(EXTRA_TARGET_IP)

        when (action) {
            ACTION_STOP -> {
                stopAudioPlayer()
                if (!isServing) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                intent.getStringExtra(EXTRA_AUDIO_ENGINE)?.let { audioOutputEngine = it }
                if (intent.hasExtra(EXTRA_AUDIO_FX)) {
                    enableAudioEffects = intent.getBooleanExtra(EXTRA_AUDIO_FX, false)
                }
                val port = intent.getIntExtra(EXTRA_AUDIO_PORT, 5004).coerceIn(1, 65535)
                if (!isStreaming) startAudioPlayer(port)
            }
            ACTION_START_SERVE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val targetPort = intent.getIntExtra(EXTRA_TARGET_PORT, 5004).coerceIn(1024, 65535)
                startServingInternalAudio(resultCode, resultData, targetIp, targetPort)
            }
            ACTION_STOP_SERVE -> {
                stopServingInternalAudio()
                if (!isStreaming) stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    private fun notifyStateChanged() {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_STREAMING, isStreaming)
                .putExtra(EXTRA_SERVING, isServing)
        )
    }

    private fun updateForegroundState() {
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var serviceType = 0
                if (isStreaming) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
                if (isServing) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                if (serviceType == 0) {
                    serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
                startForeground(NOTIFICATION_ID, notif, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
            Log.d(TAG, "startForeground success")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error: ${e.message}")
        }
    }

    private fun startAudioPlayer(port: Int = 5004) {
        Log.d(TAG, "startAudioPlayer entry on port $port")
        userMuted = isUserMuted
        pausedByHeadset = false
        pausedByFocus = false
        applyEffectiveMuteAndGain()

        // Determine Bluetooth/AudioTrack route BEFORE setting isStreaming=true or calling nativeStart()
        val btConnected = isBluetoothOutputConnected()
        isBluetoothRouteActive = btConnected
        lastReportedBtRoute = btConnected
        val needTrack = shouldUseAudioTrack(btConnected)
        activeBackendLabel = when {
            btConnected -> "BT • AudioTrack"
            needTrack -> "AudioTrack"
            else -> "Oboe"
        }

        isStreaming = true
        updateForegroundState()
        requestAudioFocus()
        acquireLocks()

        val lowLatency = (audioOutputEngine == "OBOE_LOW_LATENCY" && !enableAudioEffects)
        AudioNative.nativeSetLowLatencyMode(lowLatency)
        AudioNative.nativeSetEnableAudioEffects(enableAudioEffects)
        AudioNative.nativeSetUseExternalOutput(needTrack)
        AudioNative.nativeOnAudioRouteChanged(btConnected)

        val started = AudioNative.nativeStart(port)
        Log.d(TAG, "nativeStart result: $started on port $port (needTrack=$needTrack, bt=$btConnected)")
        if (!started) {
            Log.e(TAG, "Failed to start native audio engine on port $port")
            isStreaming = false
            releaseAudioFocus()
            if (!isServing) {
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                updateForegroundState()
            }
            notifyStateChanged()
            return
        }

        if (needTrack) {
            startAudioTrackOutput()
        }

        updateAudioEffects()
        serviceHandler.postDelayed({ updateAudioEffects() }, 280L)

        ensureControlChannel()
        notifyStateChanged()
    }

    private fun stopAudioPlayer() {
        Log.d(TAG, "stopAudioPlayer entry")
        isStreaming = false
        stopAudioTrackOutput()
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {}
        loudnessEnhancer = null
        lastAttachedSessionId = 0

        AudioNative.nativeStop()
        releaseAudioFocus()
        if (!isServing) {
            releaseLocks()
            activeControlChannel?.stop()
            activeControlChannel = null
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            updateForegroundState()
        }
        notifyStateChanged()
    }

    private fun startServingInternalAudio(resultCode: Int, resultData: Intent?, targetIp: String?, targetPort: Int = 5004) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "Internal system audio capture requires Android 10+")
            return
        }
        if (resultData == null || resultCode != android.app.Activity.RESULT_OK) {
            Log.e(TAG, "Invalid MediaProjection result code/data")
            return
        }

        if (isServing || internalStreamer != null) {
            stopServingInternalAudio()
        }

        ensureControlChannel()
        if (!targetIp.isNullOrBlank()) {
            activeControlChannel?.setActivePcIp(targetIp)
        }
        val ip = targetIp?.takeIf { it.isNotBlank() }
            ?: activeControlChannel?.getActivePcIp()
            ?: AudioNative.nativeGetSenderIp()
            ?: "255.255.255.255"

        isServing = true
        // Promote to foreground with MEDIA_PROJECTION type BEFORE obtaining MediaProjection (Android 14+ requirement)
        updateForegroundState()
        acquireLocks()

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection? = mpManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "Failed to obtain MediaProjection from MediaProjectionManager")
            isServing = false
            if (!isStreaming) {
                releaseLocks()
                activeControlChannel?.stop()
                activeControlChannel = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                updateForegroundState()
            }
            notifyStateChanged()
            return
        }

        val mpCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system")
                serviceHandler.post {
                    stopServingInternalAudio()
                }
            }
        }
        try {
            projection.registerCallback(mpCallback, serviceHandler)
            currentMediaProjection = projection
            mediaProjectionCallback = mpCallback
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register MediaProjection callback: ${e.message}")
        }

        if (internalStreamer == null) {
            internalStreamer = InternalAudioStreamer()
            internalAudioStreamerInstance = internalStreamer
        }

        val ok = internalStreamer!!.start(projection, ip, targetPort, this)
        if (!ok) {
            Log.e(TAG, "InternalAudioStreamer failed to start")
            serviceHandler.post {
                android.widget.Toast.makeText(
                    this,
                    "Failed to capture internal audio. Grant audio permission and choose 'Entire screen'.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            isServing = false
            internalStreamer = null
            internalAudioStreamerInstance = null
            try {
                if (currentMediaProjection != null && mediaProjectionCallback != null) {
                    currentMediaProjection?.unregisterCallback(mediaProjectionCallback!!)
                }
            } catch (_: Exception) {}
            currentMediaProjection = null
            mediaProjectionCallback = null
            if (!isStreaming) {
                releaseLocks()
                activeControlChannel?.stop()
                activeControlChannel = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                updateForegroundState()
            }
        }
        notifyStateChanged()
    }

    private fun stopServingInternalAudio() {
        isServing = false
        internalStreamer?.stop()
        internalStreamer = null
        internalAudioStreamerInstance = null

        try {
            if (currentMediaProjection != null && mediaProjectionCallback != null) {
                currentMediaProjection?.unregisterCallback(mediaProjectionCallback!!)
            }
        } catch (_: Exception) {}
        currentMediaProjection = null
        mediaProjectionCallback = null

        if (!isStreaming) {
            releaseLocks()
            activeControlChannel?.stop()
            activeControlChannel = null
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            updateForegroundState()
        }
        notifyStateChanged()
    }

    private fun ensureControlChannel() {
        if (activeControlChannel == null) {
            activeControlChannel = ControlChannel(
                audioPort = 5004,
                controlPort = 5005,
                onConfigSyncReceived = { configJson -> handleConfigSync(configJson) },
                onPartyKicked = { reason -> handlePartyKicked(reason) }
            )
            activeControlChannel?.start()
        }
    }

    private fun handlePartyKicked(reason: String) {
        serviceHandler.post {
            ControlChannel.sendPartyLeaveAnnouncement(activeControlChannel?.getActivePcIp().orEmpty())
            ControlChannel.isPartyJoinerActive = false
            AudioNative.nativeSetPartyRelay(false)
            ControlChannel.isHostingOrExtendingParty = false
            ControlChannel.isExtendingParty = false
            stopAudioPlayer()
            if (!isServing) {
                stopSelf()
            }
            if (ControlChannel.onPartyKicked == null) {
                android.widget.Toast.makeText(this, "Party Host: $reason", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleConfigSync(json: JSONObject) {
        try {
            val senderIp = json.optString("_sender_ip", "")
            // Party Host Authority: ignore CONFIG_SYNC sent by party joiners
            if (ControlChannel.isHostingOrExtendingParty && !ControlChannel.isExtendingParty) {
                if (ControlChannel.partyPeerIps.contains(senderIp) || json.optBoolean("party_join", false) || !json.optBoolean("is_party_host", false)) {
                    return
                }
            }
            // Party Joiner Authority: only accept CONFIG_SYNC from the joined host
            val isHostSync = json.optBoolean("party_host_sync", false) || json.optBoolean("is_party_host", false)
            if (ControlChannel.isPartyJoinerActive) {
                val joinedHost = ControlChannel.joinedPartyHostIp?.trim().orEmpty()
                if (joinedHost.isNotEmpty() && senderIp.isNotEmpty() && senderIp != joinedHost) {
                    return
                }
            }

            val incomingRev = json.optInt("rev", 0)
            val incomingSyncId = if (json.has("sync_id")) json.getInt("sync_id") else null
            val incomingSessionId = if (json.has("session_id")) json.getInt("session_id") else null

            synchronized(AudioStreamService::class.java) {
                if (incomingSyncId != null && seenServiceSyncIds.contains(incomingSyncId)) return
                if (incomingSessionId != null) {
                    val lastSessRev = lastServiceRevBySession[incomingSessionId] ?: 0
                    if (incomingRev <= lastSessRev) return
                    lastServiceRevBySession[incomingSessionId] = incomingRev
                } else if (incomingSyncId != null) {
                    if (incomingRev < lastServiceConfigRev) return
                } else if (incomingRev <= lastServiceConfigRev) {
                    return
                }
                if (incomingSyncId != null) {
                    if (seenServiceSyncIds.size >= 32) seenServiceSyncIds.removeFirst()
                    seenServiceSyncIds.addLast(incomingSyncId)
                }
                lastServiceConfigRev = maxOf(lastServiceConfigRev, incomingRev)
                cacheMergedConfig(json)
            }

            sendBroadcast(
                Intent(ACTION_CONFIG_SYNC_RECEIVED)
                    .setPackage(packageName)
                    .putExtra(EXTRA_CONFIG_JSON, json.toString())
            )

            if (json.has("custom_buffer_ms")) {
                AudioNative.safeSetTargetBufferMs(json.getDouble("custom_buffer_ms").toFloat())
            }
            if (json.has("auto_buffer")) {
                AudioNative.safeSetAutoMode(json.getBoolean("auto_buffer"))
            }
            // Party Joiners keep their own local volume & mute independent from the Host
            if (!(ControlChannel.isPartyJoinerActive && isHostSync)) {
                if (json.has("volume_gain")) {
                    savedVolumeGain = json.getDouble("volume_gain").toFloat().coerceIn(0f, 2f)
                    AudioNative.safeSetVolumeGain(savedVolumeGain)
                }
                if (json.has("muted")) {
                    userMuted = json.getBoolean("muted")
                    isUserMuted = userMuted
                    if (!userMuted) {
                        pausedByHeadset = false
                        pausedByFocus = false
                    }
                }
                applyEffectiveMuteAndGain()
            }
            if (json.has("exclusive_audio_mode")) {
                val oldMode = exclusiveAudioMode
                exclusiveAudioMode = json.getString("exclusive_audio_mode")
                if (oldMode != exclusiveAudioMode && isStreaming) {
                    requestAudioFocus()
                }
            }
            if (json.has("stop_on_headset_disconnect")) {
                stopOnHeadsetDisconnect = json.getBoolean("stop_on_headset_disconnect")
            }
            val oldEngine = audioOutputEngine
            val oldFx = enableAudioEffects
            if (json.has("audio_output_engine")) {
                audioOutputEngine = json.getString("audio_output_engine")
            }
            if (json.has("enable_audio_effects")) {
                enableAudioEffects = json.getBoolean("enable_audio_effects")
            }
            if (oldEngine != audioOutputEngine || oldFx != enableAudioEffects) {
                val lowLatency = (audioOutputEngine == "OBOE_LOW_LATENCY" && !enableAudioEffects)
                AudioNative.nativeSetLowLatencyMode(lowLatency)
                AudioNative.nativeSetEnableAudioEffects(enableAudioEffects)
                if (isStreaming) {
                    updateOutputEngineLive(audioOutputEngine, enableAudioEffects)
                }
                updateAudioEffects()
                serviceHandler.postDelayed({ updateAudioEffects() }, 280L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying CONFIG_SYNC in service", e)
        }
    }

    private fun updateAudioEffects() {
        if (enableAudioEffects && isStreaming) {
            val trackSessionId = synchronized(audioTrackLock) { audioTrackInstance?.audioSessionId ?: 0 }
            val sessionId = if (audioTrackRunning && trackSessionId > 0) trackSessionId else AudioNative.nativeGetAudioSessionId()
            if (sessionId > 0 && (loudnessEnhancer == null || sessionId != lastAttachedSessionId)) {
                try { loudnessEnhancer?.release() } catch (_: Exception) {}
                loudnessEnhancer = null
                try {
                    loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(sessionId).apply {
                        setTargetGain(600)
                        enabled = true
                    }
                    lastAttachedSessionId = sessionId
                    Log.i(TAG, "Attached LoudnessEnhancer to session $sessionId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to attach LoudnessEnhancer: ${e.message}")
                }
            }
        } else {
            try {
                loudnessEnhancer?.release()
            } catch (_: Exception) {}
            loudnessEnhancer = null
            lastAttachedSessionId = 0
        }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirSpeaker::WakeLock").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
        }

        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "AirSpeaker::WifiLock")
            } else {
                @Suppress("DEPRECATION")
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AirSpeaker::WifiLock")
            }.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    private fun requestAudioFocus() {
        if (exclusiveAudioMode != "EXCLUSIVE") {
            releaseAudioFocus()
            pausedByFocus = false
            applyEffectiveMuteAndGain()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener { focusChange ->
                    try {
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                if (exclusiveAudioMode == "EXCLUSIVE") {
                                    pausedByFocus = true
                                    applyEffectiveMuteAndGain()
                                }
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                if (exclusiveAudioMode == "EXCLUSIVE") {
                                    AudioNative.nativeSetVolumeGain(savedVolumeGain * 0.25f)
                                }
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                pausedByFocus = false
                                AudioNative.nativeSetVolumeGain(savedVolumeGain)
                                applyEffectiveMuteAndGain()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onAudioFocusChangeListener: ${e.message}")
                    }
                }
                .build()

            audioManager?.requestAudioFocus(audioFocusRequest!!)
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager?.abandonAudioFocusRequest(audioFocusRequest!!)
            audioFocusRequest = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirSpeaker Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ultra-low-latency audio streaming & settings sync"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val modeText = when {
            isStreaming && isServing -> "ACTIVE: DUAL (PLAY + SERVE)"
            isStreaming -> "PLAYER: Receiving Audio"
            isServing -> "AUDIO SENDER: Streaming System Audio"
            else -> "IDLE"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AirSpeaker")
            .setContentText(modeText)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

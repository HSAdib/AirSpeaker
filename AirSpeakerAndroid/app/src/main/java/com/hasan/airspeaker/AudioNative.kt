package com.hasan.airspeaker

import android.util.Log

data class AudioStats(
    val bufferMs: Float = 0f,
    val targetMs: Float = 30f,
    val underruns: Int = 0,
    val lostPackets: Int = 0,
    val latePackets: Int = 0,
    val rxPps: Int = 0,
    val codec: Int = 0,
    val autoMode: Boolean = true,
    val rxPackets: Long = 0L
)

data class NativeAudioStats(
    val bufferMs: Float = 0f,
    val targetMs: Float = 30f,
    val underruns: Long = 0L,
    val lostPackets: Long = 0L,
    val latePackets: Long = 0L,
    val duplicatePackets: Long = 0L,
    val concealedPackets: Long = 0L,
    val intentionalDriftDrops: Long = 0L,
    val packetQueueOverflowDrops: Long = 0L,
    val pcmRingOverflowDrops: Long = 0L,
    val pcmRingUnderrunFrames: Long = 0L,
    val pcmRingAvailableFrames: Long = 0L,
    val callbackAverageMicros: Double = 0.0,
    val callbackMaxMicros: Long = 0L,
    val callbackDeadlineMisses: Long = 0L,
    val rxPps: Double = 0.0
)

object AudioNative {
    private const val TAG = "AudioNative"

    @Volatile var isNativeLoaded: Boolean = false
    @Volatile var nativeLoadError: String? = null

    init {
        try {
            try {
                System.loadLibrary("c++_shared")
            } catch (t: Throwable) {
                Log.w(TAG, "c++_shared explicit load skipped or not required: ${t.message}")
            }
            System.loadLibrary("airspeaker_native")
            isNativeLoaded = true
            Log.i(TAG, "airspeaker_native loaded successfully")
        } catch (t: Throwable) {
            isNativeLoaded = false
            nativeLoadError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "Failed to load airspeaker_native: $nativeLoadError", t)
        }
    }

    external fun nativeStart(port: Int): Boolean
    external fun nativeStop()
    external fun nativeSetTargetBufferMs(targetMs: Float)
    external fun nativeGetTargetBufferMs(): Float
    external fun nativeSetAutoMode(enabled: Boolean)
    external fun nativeIsAutoMode(): Boolean
    external fun nativeSetMuted(muted: Boolean)
    external fun nativeIsMuted(): Boolean
    external fun nativeSetVolumeGain(gain: Float)
    external fun nativeGetVolumeGain(): Float
    external fun nativeSetLowLatencyMode(lowLatency: Boolean)
    external fun nativeSetEnableAudioEffects(enabled: Boolean)
    external fun nativeOnAudioRouteChanged(isBluetooth: Boolean)
    external fun nativeSetPartyRelay(enabled: Boolean)
    external fun nativeIsPartyRelay(): Boolean
    external fun nativeSetPartyPeers(peers: Array<String>)
    external fun nativeSetEqBands(enabled: Boolean, gainsDb: FloatArray)
    external fun nativeGetAudioSessionId(): Int
    external fun nativeSetUseExternalOutput(useExternal: Boolean)
    external fun nativeReadExternalPcm(outBuffer: ShortArray, numFrames: Int): Int
    external fun nativeGetOutputBackend(): Int
    external fun nativeGetStats(): FloatArray?
    external fun nativeGetAdvancedStats(): DoubleArray?
    external fun nativeGetSenderIp(): String?

    // Safe wrappers guarded against UnsatisfiedLinkError or failed native loading
    fun safeSetEqBands(enabled: Boolean, gainsDb: FloatArray) {
        if (!isNativeLoaded) return
        try {
            nativeSetEqBands(enabled, gainsDb)
        } catch (t: Throwable) {
            Log.w(TAG, "safeSetEqBands failed: ${t.message}")
        }
    }

    fun safeGetTargetBufferMs(defaultMs: Float = 30f): Float {
        if (!isNativeLoaded) return defaultMs
        return try {
            nativeGetTargetBufferMs()
        } catch (t: Throwable) {
            Log.w(TAG, "safeGetTargetBufferMs failed: ${t.message}")
            defaultMs
        }
    }

    fun safeSetTargetBufferMs(targetMs: Float) {
        if (!isNativeLoaded) return
        try {
            nativeSetTargetBufferMs(targetMs)
        } catch (t: Throwable) {
            Log.w(TAG, "safeSetTargetBufferMs failed: ${t.message}")
        }
    }

    fun safeGetVolumeGain(defaultGain: Float = 1.0f): Float {
        if (!isNativeLoaded) return defaultGain
        return try {
            nativeGetVolumeGain()
        } catch (t: Throwable) {
            Log.w(TAG, "safeGetVolumeGain failed: ${t.message}")
            defaultGain
        }
    }

    fun safeSetVolumeGain(gain: Float) {
        if (!isNativeLoaded) return
        try {
            nativeSetVolumeGain(gain)
        } catch (t: Throwable) {
            Log.w(TAG, "safeSetVolumeGain failed: ${t.message}")
        }
    }

    fun safeIsMuted(): Boolean {
        if (!isNativeLoaded) return false
        return try {
            nativeIsMuted()
        } catch (t: Throwable) {
            Log.w(TAG, "safeIsMuted failed: ${t.message}")
            false
        }
    }

    fun safeSetMuted(muted: Boolean) {
        if (!isNativeLoaded) return
        try {
            nativeSetMuted(muted)
        } catch (t: Throwable) {
            Log.w(TAG, "safeSetMuted failed: ${t.message}")
        }
    }

    fun safeSetAutoMode(enabled: Boolean) {
        if (!isNativeLoaded) return
        try {
            nativeSetAutoMode(enabled)
        } catch (t: Throwable) {
            Log.w(TAG, "safeSetAutoMode failed: ${t.message}")
        }
    }

    fun safeSetPartyRelay(enabled: Boolean) {
        if (!isNativeLoaded) return
        try {
            nativeSetPartyRelay(enabled)
        } catch (t: Throwable) {
            Log.w(TAG, "safeSetPartyRelay failed: ${t.message}")
        }
    }

    fun safeIsPartyRelay(): Boolean {
        if (!isNativeLoaded) return false
        return try {
            nativeIsPartyRelay()
        } catch (t: Throwable) {
            Log.w(TAG, "safeIsPartyRelay failed: ${t.message}")
            false
        }
    }

    fun safeSetPartyPeers(peers: Array<String>) {
        if (!isNativeLoaded) return
        try {
            nativeSetPartyPeers(peers)
        } catch (t: Throwable) {
            Log.w(TAG, "safeSetPartyPeers failed: ${t.message}")
        }
    }

    fun safeSetLowLatencyMode(lowLatency: Boolean) {
        if (!isNativeLoaded) return
        try {
            nativeSetLowLatencyMode(lowLatency)
        } catch (t: Throwable) {
            Log.w(TAG, "safeSetLowLatencyMode failed: ${t.message}")
        }
    }

    fun safeGetSenderIp(): String? {
        if (!isNativeLoaded) return null
        return try {
            nativeGetSenderIp()
        } catch (t: Throwable) {
            Log.w(TAG, "safeGetSenderIp failed: ${t.message}")
            null
        }
    }

    fun getStats(): AudioStats {
        if (!isNativeLoaded) return AudioStats()
        val arr = try {
            nativeGetStats()
        } catch (t: Throwable) {
            Log.w(TAG, "nativeGetStats failed: ${t.message}")
            null
        } ?: return AudioStats()

        if (arr.size < 8) return AudioStats()
        val rxPackets = if (arr.size >= 9) arr[8].toLong() else 0L
        return AudioStats(
            bufferMs = arr[0],
            targetMs = arr[1],
            underruns = arr[2].toInt(),
            lostPackets = arr[3].toInt(),
            latePackets = arr[4].toInt(),
            rxPps = arr[5].toInt(),
            codec = arr[6].toInt(),
            autoMode = arr[7] > 0.5f,
            rxPackets = rxPackets
        )
    }

    fun getAdvancedStats(): NativeAudioStats {
        if (!isNativeLoaded) return NativeAudioStats()
        val arr = try {
            nativeGetAdvancedStats()
        } catch (t: Throwable) {
            Log.w(TAG, "nativeGetAdvancedStats failed: ${t.message}")
            null
        } ?: return NativeAudioStats()

        if (arr.size < 16) return NativeAudioStats()
        return NativeAudioStats(
            bufferMs = arr[0].toFloat(),
            targetMs = arr[1].toFloat(),
            underruns = arr[2].toLong(),
            lostPackets = arr[3].toLong(),
            latePackets = arr[4].toLong(),
            duplicatePackets = arr[5].toLong(),
            concealedPackets = arr[6].toLong(),
            intentionalDriftDrops = arr[7].toLong(),
            packetQueueOverflowDrops = arr[8].toLong(),
            pcmRingOverflowDrops = arr[9].toLong(),
            pcmRingUnderrunFrames = arr[10].toLong(),
            pcmRingAvailableFrames = arr[11].toLong(),
            callbackAverageMicros = arr[12],
            callbackMaxMicros = arr[13].toLong(),
            callbackDeadlineMisses = arr[14].toLong(),
            rxPps = arr[15]
        )
    }
}

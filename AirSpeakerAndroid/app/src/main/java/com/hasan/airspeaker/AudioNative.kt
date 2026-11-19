package com.hasan.airspeaker

data class AudioStats(
    val bufferMs: Float = 0f,
    val targetMs: Float = 30f,
    val underruns: Int = 0,
    val lostPackets: Int = 0,
    val latePackets: Int = 0,
    val rxPps: Int = 0,
    val codec: Int = 0,
    val autoMode: Boolean = true
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
    init {
        System.loadLibrary("airspeaker_native")
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
    external fun nativeSetPartyRelay(enabled: Boolean)
    external fun nativeIsPartyRelay(): Boolean
    external fun nativeSetPartyPeers(peers: Array<String>)
    external fun nativeSetEqBands(enabled: Boolean, gainsDb: FloatArray)
    external fun nativeGetAudioSessionId(): Int
    external fun nativeGetStats(): FloatArray?
    external fun nativeGetAdvancedStats(): DoubleArray?
    external fun nativeGetSenderIp(): String?

    fun getStats(): AudioStats {
        val arr = nativeGetStats() ?: return AudioStats()
        if (arr.size < 8) return AudioStats()
        return AudioStats(
            bufferMs = arr[0],
            targetMs = arr[1],
            underruns = arr[2].toInt(),
            lostPackets = arr[3].toInt(),
            latePackets = arr[4].toInt(),
            rxPps = arr[5].toInt(),
            codec = arr[6].toInt(),
            autoMode = arr[7] > 0.5f
        )
    }

    fun getAdvancedStats(): NativeAudioStats {
        val arr = nativeGetAdvancedStats() ?: return NativeAudioStats()
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

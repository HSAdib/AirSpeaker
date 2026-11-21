package com.hasan.airspeaker

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.tanh
import kotlin.math.round

/**
 * InternalAudioStreamer captures system internal audio playback (media, games, YouTube)
 * on Android 10+ (API 29+) using AudioPlaybackCaptureConfiguration & MediaProjection,
 * and streams 48 kHz stereo PCM16 packets (5 ms, 972 bytes) over UDP port 5004.
 */
class InternalAudioStreamer {

    companion object {
        private const val TAG = "InternalAudioStreamer"
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val FRAME_MS = 5
        const val SAMPLES_PER_FRAME = (SAMPLE_RATE * FRAME_MS) / 1000 // 240 samples per channel
        const val TOTAL_SAMPLES = SAMPLES_PER_FRAME * CHANNELS        // 480 samples = 960 bytes
        const val PAYLOAD_BYTES = TOTAL_SAMPLES * 2                  // 960 bytes
        const val HEADER_BYTES = 12
        const val PACKET_SIZE = HEADER_BYTES + PAYLOAD_BYTES         // 972 bytes
        const val CODEC_PCM16 = 0

        data class EqCoeffs(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double, val active: Boolean)
        data class EqSnapshot(val enabled: Boolean, val coeffs: Array<EqCoeffs>)

        @Volatile var eqGainsDb = FloatArray(5)
        @Volatile var eqSnapshot = EqSnapshot(false, Array(5) { EqCoeffs(1.0, 0.0, 0.0, 0.0, 0.0, false) })

        fun setOutboundEq(enabled: Boolean, gains: FloatArray) {
            val copy = FloatArray(5)
            for (i in 0 until minOf(5, gains.size)) {
                val raw = gains[i]
                copy[i] = if (raw.isFinite()) raw.coerceIn(-12.0f, 12.0f) else 0.0f
            }
            eqGainsDb = copy

            val freqs = doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0)
            val sr = 48000.0
            val q = 1.15
            val newCoeffs = Array(5) { i ->
                val gDb = copy[i].toDouble()
                if (abs(gDb) <= 0.05) {
                    EqCoeffs(1.0, 0.0, 0.0, 0.0, 0.0, false)
                } else {
                    val A = 10.0.pow(gDb / 40.0)
                    val w0 = 2.0 * Math.PI * freqs[i] / sr
                    val alpha = sin(w0) / (2.0 * q)
                    val cosW0 = cos(w0)
                    val a0 = 1.0 + alpha / A
                    EqCoeffs(
                        b0 = (1.0 + alpha * A) / a0,
                        b1 = (-2.0 * cosW0) / a0,
                        b2 = (1.0 - alpha * A) / a0,
                        a1 = (-2.0 * cosW0) / a0,
                        a2 = (1.0 - alpha / A) / a0,
                        active = true
                    )
                }
            }
            val nowActive = enabled && newCoeffs.any { it.active }
            eqSnapshot = EqSnapshot(nowActive, newCoeffs)
        }
    }

    private val z1 = Array(5) { DoubleArray(2) }
    private val z2 = Array(5) { DoubleArray(2) }

    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var socket: DatagramSocket? = null
    private var mediaProjection: MediaProjection? = null

    val txPps = AtomicInteger(0)
    val peakLevel = AtomicReference(0.0f)
    val packetsSent = AtomicLong(0L)

    private val targetIpRef = AtomicReference("255.255.255.255")
    private val addrCache = ConcurrentHashMap<String, InetAddress>()

    private fun resolveAddress(ip: String): InetAddress? {
        val clean = ip.trim()
        if (clean.isEmpty() || clean == "0.0.0.0") return null
        addrCache[clean]?.let { return it }
        return try {
            val addr = InetAddress.getByName(clean)
            addrCache[clean] = addr
            addr
        } catch (_: Exception) {
            null
        }
    }
    private val targetPort = 5004

    @Volatile
    private var workerTerminatedLatch: CountDownLatch? = null

    fun updateTargetIp(newIp: String) {
        if (newIp.isNotBlank()) {
            targetIpRef.set(newIp)
        }
    }

    fun start(projection: MediaProjection, targetIp: String = "255.255.255.255", context: android.content.Context? = null): Boolean {
        if (isRunning.get()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "AudioPlaybackCapture requires Android 10 (API 29) or higher")
            return false
        }

        val frameMs = FRAME_MS
        val framesPerPacket = (SAMPLE_RATE * frameMs) / 1000
        val totalSamples = framesPerPacket * CHANNELS
        val payloadBytes = totalSamples * 2
        val packetSize = HEADER_BYTES + payloadBytes

        this.mediaProjection = projection
        updateTargetIp(targetIp)

        try {
            // 1. Configure AudioPlaybackCapture
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = max(minBufSize, payloadBytes * 10) // ~50ms buffer cushion

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize with playback capture config")
                record.release()
                return false
            }

            // 2. Prepare UDP Socket
            val sock = DatagramSocket()
            sock.broadcast = true
            sock.sendBufferSize = 65536
            this.socket = sock
            this.audioRecord = record

            isRunning.set(true)
            val latch = CountDownLatch(1)
            workerTerminatedLatch = latch

            workerThread = Thread({
                runCaptureLoop(record, sock, latch, totalSamples, packetSize)
            }, "InternalAudioCaptureWorker").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.i(TAG, "Internal system audio capture started (${frameMs}ms frames) -> streaming to $targetIp:$targetPort")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start internal audio streamer: ${e.message}", e)
            cleanup()
            return false
        }
    }

    private fun runCaptureLoop(
        record: AudioRecord,
        sock: DatagramSocket,
        latch: CountDownLatch,
        totalSamples: Int = TOTAL_SAMPLES,
        packetSize: Int = PACKET_SIZE
    ) {
        val shortBuf = ShortArray(totalSamples)
        val packetBuf = ByteBuffer.allocate(packetSize).order(ByteOrder.LITTLE_ENDIAN)
        var sequence: Long = 1
        var ppsCounter = 0
        var lastPpsTimeMs = SystemClock.elapsedRealtime()
        var prevEqEnabled = false
        val prevBandActive = BooleanArray(5)
        for (b in 0 until 5) {
            z1[b][0] = 0.0; z1[b][1] = 0.0
            z2[b][0] = 0.0; z2[b][1] = 0.0
        }

        try {
            record.startRecording()
            Log.d(TAG, "AudioRecord recording started")

            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                var samplesRead = 0
                while (samplesRead < totalSamples && isRunning.get()) {
                    val read = record.read(shortBuf, samplesRead, totalSamples - samplesRead, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        samplesRead += read
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord read error: $read")
                        if (!isRunning.get()) break
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                        break
                    }
                }

                if (samplesRead < totalSamples) {
                    continue
                }

                // Compute peak level for UI meter
                var maxSample = 0
                for (s in shortBuf) {
                    val a = abs(s.toInt())
                    if (a > maxSample) maxSample = a
                }
                peakLevel.set(maxSample.toFloat() / 32768.0f)

                val snap = eqSnapshot
                if (snap.enabled != prevEqEnabled) {
                    for (b in 0 until 5) {
                        z1[b][0] = 0.0; z1[b][1] = 0.0
                        z2[b][0] = 0.0; z2[b][1] = 0.0
                        prevBandActive[b] = snap.coeffs[b].active
                    }
                    prevEqEnabled = snap.enabled
                } else if (snap.enabled) {
                    for (b in 0 until 5) {
                        val act = snap.coeffs[b].active
                        if (act != prevBandActive[b]) {
                            z1[b][0] = 0.0; z1[b][1] = 0.0
                            z2[b][0] = 0.0; z2[b][1] = 0.0
                            prevBandActive[b] = act
                        }
                    }
                }

                if (snap.enabled) {
                    val coeffs = snap.coeffs
                    val knee = 0.85
                    val invOneMinusKnee = 1.0 / (1.0 - knee)
                    val numFrames = totalSamples / 2
                    for (n in 0 until numFrames) {
                        for (ch in 0 until 2) {
                            val dry = shortBuf[n * 2 + ch].toDouble() / 32768.0
                            var x = dry
                            var stateOk = true
                            for (b in 0 until 5) {
                                val c = coeffs[b]
                                if (!c.active) continue
                                val y = c.b0 * x + z1[b][ch]
                                val nz1 = c.b1 * x - c.a1 * y + z2[b][ch]
                                val nz2 = c.b2 * x - c.a2 * y
                                if (!y.isFinite() || !nz1.isFinite() || !nz2.isFinite()) {
                                    z1[b][ch] = 0.0
                                    z2[b][ch] = 0.0
                                    stateOk = false
                                    break
                                }
                                z1[b][ch] = nz1
                                z2[b][ch] = nz2
                                x = y
                            }
                            if (!stateOk) {
                                x = dry
                            }
                            if (x > knee) {
                                x = knee + (1.0 - knee) * tanh((x - knee) * invOneMinusKnee)
                            } else if (x < -knee) {
                                x = -knee - (1.0 - knee) * tanh((-x - knee) * invOneMinusKnee)
                            }
                            val clamped = round(x.coerceIn(-1.0, 1.0) * 32767.0).toInt()
                            shortBuf[n * 2 + ch] = clamped.coerceIn(-32768, 32767).toShort()
                        }
                    }
                    // Flush denormals once per frame
                    for (b in 0 until 5) {
                        for (ch in 0 until 2) {
                            if (abs(z1[b][ch]) < 1e-18) z1[b][ch] = 0.0
                            if (abs(z2[b][ch]) < 1e-18) z2[b][ch] = 0.0
                        }
                    }
                }

                // Build 12-byte Little-Endian Header:
                // uint32 seq, uint32 timestamp_ms, uint8 codec (0=PCM16), uint8 channels (2), uint16 sr_div100 (480)
                packetBuf.clear()
                val nowMs = SystemClock.elapsedRealtime()
                packetBuf.putInt((sequence and 0xFFFFFFFFL).toInt())
                packetBuf.putInt((nowMs and 0xFFFFFFFFL).toInt())
                packetBuf.put((CODEC_PCM16 and 0xFF).toByte())
                packetBuf.put((CHANNELS and 0xFF).toByte())
                packetBuf.putShort(((SAMPLE_RATE / 100) and 0xFFFF).toShort())

                // Append PCM16 payload
                for (i in 0 until totalSamples) {
                    packetBuf.putShort(shortBuf[i])
                }

                val packetBytes = packetBuf.array()
                val targetIpStr = targetIpRef.get()
                if (targetIpStr.isNotBlank() && targetIpStr != "0.0.0.0") {
                    val targetAddr = resolveAddress(targetIpStr)
                    if (targetAddr != null) {
                        try {
                            val dgram = DatagramPacket(packetBytes, packetSize, targetAddr, targetPort)
                            sock.send(dgram)
                        } catch (e: Exception) {
                            // Ignore transient network errors
                        }
                    }
                }

                if (targetIpStr == "255.255.255.255") {
                    for (localIp in ControlChannel.getLocalIpv4Addresses()) {
                        val parts = localIp.split(".")
                        if (parts.size == 4 && !parts[0].startsWith("127")) {
                            val bcastIp = "${parts[0]}.${parts[1]}.${parts[2]}.255"
                            val bcastAddr = resolveAddress(bcastIp)
                            if (bcastAddr != null) {
                                try {
                                    sock.send(DatagramPacket(packetBytes, packetSize, bcastAddr, targetPort))
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

                if (ControlChannel.isHostingOrExtendingParty && ControlChannel.partyPeerIps.isNotEmpty()) {
                    for (peerIp in ControlChannel.partyPeerIps) {
                        if (peerIp != targetIpStr) {
                            val peerAddr = resolveAddress(peerIp)
                            if (peerAddr != null) {
                                try {
                                    val peerDgram = DatagramPacket(packetBytes, packetSize, peerAddr, targetPort)
                                    sock.send(peerDgram)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

                sequence++
                packetsSent.incrementAndGet()
                ppsCounter++

                if (nowMs - lastPpsTimeMs >= 1000L) {
                    val elapsed = max(1L, nowMs - lastPpsTimeMs)
                    txPps.set((ppsCounter * 1000L / elapsed).toInt())
                    ppsCounter = 0
                    lastPpsTimeMs = nowMs
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Exception in audio capture loop: ${e.message}", e)
            }
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {}
            latch.countDown()
            Log.d(TAG, "Audio capture loop terminated cleanly")
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) {
            return
        }

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}

        workerThread?.interrupt()
        val latch = workerTerminatedLatch
        if (latch != null) {
            try {
                latch.await(1000, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        cleanup()
        Log.i(TAG, "InternalAudioStreamer stopped")
    }

    private fun cleanup() {
        isRunning.set(false)
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        workerThread = null
        txPps.set(0)
        peakLevel.set(0.0f)
    }

    fun isStreaming(): Boolean = isRunning.get()
}

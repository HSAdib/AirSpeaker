package com.hasan.phonespeaker

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
    }

    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var socket: DatagramSocket? = null
    private var mediaProjection: MediaProjection? = null

    val txPps = AtomicInteger(0)
    val peakLevel = AtomicReference(0.0f)
    val packetsSent = AtomicLong(0L)

    private val targetIpRef = AtomicReference("255.255.255.255")
    private val targetPort = 5004

    @Volatile
    private var workerTerminatedLatch: CountDownLatch? = null

    fun updateTargetIp(newIp: String) {
        if (newIp.isNotBlank()) {
            targetIpRef.set(newIp)
        }
    }

    fun start(projection: MediaProjection, targetIp: String = "255.255.255.255"): Boolean {
        if (isRunning.get()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "AudioPlaybackCapture requires Android 10 (API 29) or higher")
            return false
        }

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
            val bufferSize = max(minBufSize, PAYLOAD_BYTES * 10) // ~50ms buffer cushion

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
                runCaptureLoop(record, sock, latch)
            }, "InternalAudioCaptureWorker").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.i(TAG, "Internal system audio capture started -> streaming to $targetIp:$targetPort")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start internal audio streamer: ${e.message}", e)
            cleanup()
            return false
        }
    }

    private fun runCaptureLoop(record: AudioRecord, sock: DatagramSocket, latch: CountDownLatch) {
        val shortBuf = ShortArray(TOTAL_SAMPLES)
        val packetBuf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        var sequence: Long = 1
        var ppsCounter = 0
        var lastPpsTimeMs = SystemClock.elapsedRealtime()

        try {
            record.startRecording()
            Log.d(TAG, "AudioRecord recording started")

            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                var samplesRead = 0
                while (samplesRead < TOTAL_SAMPLES && isRunning.get()) {
                    val read = record.read(shortBuf, samplesRead, TOTAL_SAMPLES - samplesRead, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        samplesRead += read
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord read error: $read")
                        break
                    }
                }

                if (samplesRead < TOTAL_SAMPLES) {
                    continue
                }

                // Compute peak level for UI meter
                var maxSample = 0
                for (s in shortBuf) {
                    val a = abs(s.toInt())
                    if (a > maxSample) maxSample = a
                }
                peakLevel.set(maxSample.toFloat() / 32768.0f)

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
                for (i in 0 until TOTAL_SAMPLES) {
                    packetBuf.putShort(shortBuf[i])
                }

                val packetBytes = packetBuf.array()
                val targetIpStr = targetIpRef.get()
                try {
                    val targetAddr = InetAddress.getByName(targetIpStr)
                    val dgram = DatagramPacket(packetBytes, PACKET_SIZE, targetAddr, targetPort)
                    sock.send(dgram)
                } catch (e: Exception) {
                    // Ignore transient network errors
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

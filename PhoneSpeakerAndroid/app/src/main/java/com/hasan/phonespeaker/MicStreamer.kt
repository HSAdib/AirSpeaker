package com.hasan.phonespeaker

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MicStreamer {
    private val TAG = "MicStreamer"

    private val sampleRate = 48000
    private val outChannels = 2
    private val frameMs = 5
    private val samplesPerCh = (sampleRate * frameMs) / 1000 // 240 samples
    private val payloadSize = samplesPerCh * outChannels * 2 // 960 bytes stereo

    private val isRunning = AtomicBoolean(false)
    private val isFailedStuck = AtomicBoolean(false)
    private var workerTerminated: java.util.concurrent.CountDownLatch? = null
    private var workerThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null

    val txPps = AtomicInteger(0)
    private val ppsCounter = AtomicInteger(0)
    private var actualInputChannels = 1

    @Volatile
    var dynamicTargetIp: String = "255.255.255.255"

    fun updateTargetIp(ip: String?) {
        if (!ip.isNullOrBlank()) {
            dynamicTargetIp = ip.trim()
        }
    }

    @SuppressLint("MissingPermission")
    fun start(targetIp: String, targetPort: Int = 5006, enableFx: Boolean = true): Boolean {
        if (isFailedStuck.get()) {
            Log.e(TAG, "Cannot start MicStreamer: previous worker thread remained stuck")
            return false
        }
        if (isRunning.get()) {
            updateTargetIp(targetIp)
            return true
        }

        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        var record: AudioRecord? = null

        // Prefer VOICE_RECOGNITION / MIC in MONO first to avoid dual-mic phase cancellation on Xiaomi/Qualcomm
        val audioSources = if (enableFx) {
            listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )
        } else {
            listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )
        }

        for (source in audioSources) {
            // Always try MONO (1) first for clean primary-mic voice capture, then STEREO (2) fallback
            for (chCount in listOf(1, 2)) {
                val chConfig = if (chCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, chConfig, audioFormat)
                if (minBuf <= 0) continue
                val frameBytes = samplesPerCh * chCount * 2
                val bufferSize = maxOf(minBuf * 3, frameBytes * 16)
                try {
                    val candidate = AudioRecord(
                        source,
                        sampleRate,
                        chConfig,
                        audioFormat,
                        bufferSize
                    )
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        record = candidate
                        actualInputChannels = chCount
                        Log.i(TAG, "Initialized AudioRecord: source=$source, channels=$chCount, buf=$bufferSize")
                        break
                    } else {
                        candidate.release()
                    }
                } catch (_: Exception) {}
            }
            if (record != null) break
        }

        if (record == null) {
            Log.e(TAG, "AudioRecord failed to initialize")
            return false
        }

        try {
            audioRecord = record
            val sessionId = audioRecord!!.audioSessionId
            if (enableFx) {
                try {
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                    }
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                    }
                    if (AutomaticGainControl.isAvailable()) {
                        agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not attach audio effects: ${e.message}")
                }
            }

            audioRecord!!.startRecording()
            if (audioRecord!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord failed to enter RECORDSTATE_RECORDING")
                stop()
                return false
            }

            isRunning.set(true)
            dynamicTargetIp = targetIp.ifBlank { "255.255.255.255" }

            val latch = java.util.concurrent.CountDownLatch(1)
            workerTerminated = latch
            workerThread = thread(name = "MicStreamerThread", isDaemon = true) {
                try {
                    runLoop(targetPort)
                } finally {
                    latch.countDown()
                }
            }
            Log.i(TAG, "MicStreamer started streaming to $dynamicTargetIp:$targetPort (inChannels=$actualInputChannels)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MicStreamer", e)
            stop()
            return false
        }
    }

    private fun readFullShorts(rec: AudioRecord, buf: ShortArray, count: Int): Boolean {
        var offset = 0
        while (offset < count && isRunning.get()) {
            val r = rec.read(buf, offset, count - offset, AudioRecord.READ_BLOCKING)
            if (r <= 0) return false
            offset += r
        }
        return offset == count
    }

    private fun runLoop(targetPort: Int) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {}

        val socket = DatagramSocket().apply {
            broadcast = false
            sendBufferSize = 262144
        }
        val packetData = ByteArray(12 + payloadSize)
        val packetBuf = ByteBuffer.wrap(packetData).order(ByteOrder.LITTLE_ENDIAN)
        val inShorts = ShortArray(samplesPerCh * actualInputChannels)

        var seq = 0
        var lastPpsTime = SystemClock.elapsedRealtime()
        var lastBeaconTime = 0L
        var cachedIp = ""
        var cachedInet: InetAddress? = null

        while (isRunning.get()) {
            val rec = audioRecord ?: break

            val discovered = AudioStreamService.activeControlChannel?.getActivePcIp()
                ?: ControlChannel.lastKnownPcIp
                ?: AudioNative.nativeGetSenderIp()
            if (!discovered.isNullOrBlank() && discovered != "255.255.255.255" && dynamicTargetIp != discovered) {
                dynamicTargetIp = discovered
                Log.i(TAG, "MicStreamer locked onto discovered PC IP: $discovered")
            }

            val currentIp = dynamicTargetIp
            if (currentIp == "255.255.255.255" || currentIp.isBlank()) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBeaconTime >= 1000L) {
                    lastBeaconTime = now
                    ControlChannel.sendPreStreamBeacon()
                }
                SystemClock.sleep(10)
                continue
            }

            if (readFullShorts(rec, inShorts, inShorts.size)) {
                val nowMs = (SystemClock.elapsedRealtime() and 0xFFFFFFFFL).toInt()
                packetBuf.clear()
                packetBuf.putInt(seq++)
                packetBuf.putInt(nowMs)
                packetBuf.put(0.toByte())            // CODEC_PCM16
                packetBuf.put(outChannels.toByte())  // 2 stereo channels
                packetBuf.putShort(480.toShort())    // 48000 / 100

                val applyBoost = (agc == null)
                if (actualInputChannels == 1) {
                    for (i in 0 until samplesPerCh) {
                        val raw = inShorts[i].toInt()
                        val outSample = if (applyBoost) {
                            ((raw * 5) shr 2).coerceIn(-32768, 32767).toShort()
                        } else {
                            raw.coerceIn(-32768, 32767).toShort()
                        }
                        packetBuf.putShort(outSample)
                        packetBuf.putShort(outSample)
                    }
                } else {
                    for (i in 0 until samplesPerCh) {
                        val sL = inShorts[i * 2].toInt()
                        val sR = inShorts[i * 2 + 1].toInt()
                        packetBuf.putShort(sL.coerceIn(-32768, 32767).toShort())
                        packetBuf.putShort(sR.coerceIn(-32768, 32767).toShort())
                    }
                }

                try {
                    if (currentIp != cachedIp || cachedInet == null) {
                        cachedInet = InetAddress.getByName(currentIp)
                        cachedIp = currentIp
                    }
                    socket.send(DatagramPacket(packetData, packetData.size, cachedInet, targetPort))
                    ppsCounter.incrementAndGet()
                } catch (_: Exception) {}

                val now = SystemClock.elapsedRealtime()
                if (now - lastPpsTime >= 1000) {
                    txPps.set(ppsCounter.getAndSet(0))
                    lastPpsTime = now
                }
            } else {
                SystemClock.sleep(2)
            }
        }

        try { socket.close() } catch (_: Exception) {}
    }

    fun stop() {
        val wasRunning = isRunning.getAndSet(false)
        val worker = workerThread
        val rec = audioRecord
        val ns = noiseSuppressor
        val aec = echoCanceler
        val autoGain = agc
        val latch = workerTerminated

        // 1. Unblock AudioRecord.read() by stopping the recorder
        try {
            rec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.stop error during shutdown: ${e.message}")
        }

        // 2. Interrupt worker thread
        worker?.interrupt()

        // 3. Wait on workerTerminated latch with 1000ms bounded timeout
        var exited = false
        if (worker != null && worker != Thread.currentThread() && latch != null) {
            try {
                exited = latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!exited && worker.isAlive) {
                    Log.w(TAG, "MicStreamer worker thread alive after 1000ms (state=${worker.state}), retrying interrupt")
                    worker.interrupt()
                    exited = latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Interrupted while waiting for MicStreamer worker")
            }
        } else {
            exited = true
        }

        if (exited) {
            workerThread = null
            audioRecord = null
            noiseSuppressor = null
            echoCanceler = null
            agc = null
            workerTerminated = null

            try { ns?.release() } catch (_: Exception) {}
            try { aec?.release() } catch (_: Exception) {}
            try { autoGain?.release() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            Log.i(TAG, "MicStreamer stopped cleanly (wasRunning=$wasRunning)")
        } else {
            // Worker is still alive after second wait:
            // Do NOT release AudioRecord or effects that the worker may still access!
            isFailedStuck.set(true)
            Log.e(TAG, "CRITICAL: MicStreamer worker failed to exit after 2000ms. Resources retained to prevent crash.")
        }

        txPps.set(0)
    }
}

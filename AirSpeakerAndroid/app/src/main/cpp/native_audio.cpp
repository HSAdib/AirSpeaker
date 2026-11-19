#include "native_audio.h"
#include <sys/socket.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <android/log.h>
#include <cmath>
#include <algorithm>

#define TAG "NativeAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

NativeAudioEngine::NativeAudioEngine() {}

NativeAudioEngine::~NativeAudioEngine() {
    stop();
}

bool NativeAudioEngine::start(int audio_port) {
    std::lock_guard<std::mutex> lock(mStartStopMutex);
    if (mIsRunning.load(std::memory_order_acquire)) return true;

    mAudioPort = audio_port;

    // Reset instrumentation metrics
    mTotalCallbackCount.store(0, std::memory_order_relaxed);
    mTotalCallbackMicros.store(0, std::memory_order_relaxed);
    mMaxCallbackMicros.store(0, std::memory_order_relaxed);
    mDeadlineMisses.store(0, std::memory_order_relaxed);

    // 1. Create UDP socket
    int sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (sockfd < 0) {
        LOGE("Failed to create UDP socket");
        cleanupAfterFailedStart();
        return false;
    }

    int rcvbuf = 1024 * 1024;
    setsockopt(sockfd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

    int reuse = 1;
    setsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    struct timeval tv{};
    tv.tv_sec = 0;
    tv.tv_usec = 200000; // 200ms timeout for clean shutdown
    setsockopt(sockfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct sockaddr_in servaddr{};
    servaddr.sin_family = AF_INET;
    servaddr.sin_addr.s_addr = INADDR_ANY;
    servaddr.sin_port = htons(mAudioPort);

    if (bind(sockfd, (const struct sockaddr*)&servaddr, sizeof(servaddr)) < 0) {
        LOGE("Failed to bind UDP socket to port %d", mAudioPort);
        close(sockfd);
        cleanupAfterFailedStart();
        return false;
    }
    mSocketFd.store(sockfd, std::memory_order_release);

    // 2. Open Oboe audio output stream
    if (!openOboeStream()) {
        LOGE("Failed to open Oboe stream");
        cleanupAfterFailedStart();
        return false;
    }

    // 3. Start JitterBuffer decode worker thread
    mJitterBuffer.start();

    // 4. Mark running TRUE BEFORE spawning receiver thread so socketReceiverLoop stays alive!
    mIsRunning.store(true, std::memory_order_release);

    // 5. Start UDP receiver thread
    try {
        mReceiverThread = std::thread(&NativeAudioEngine::socketReceiverLoop, this);
    } catch (const std::exception& e) {
        LOGE("Failed to start receiver thread: %s", e.what());
        cleanupAfterFailedStart();
        return false;
    }

    // 6. Request Oboe stream playback start
    if (!startOboeStream()) {
        LOGE("Failed to start Oboe stream playback");
        cleanupAfterFailedStart();
        return false;
    }

    LOGI("NativeAudioEngine started successfully on port %d", mAudioPort);
    return true;
}

void NativeAudioEngine::cleanupAfterFailedStart() noexcept {
    mIsRunning.store(false, std::memory_order_release);
    stopAndCloseOboeStream();

    int fd = mSocketFd.exchange(-1, std::memory_order_acq_rel);
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
        close(fd);
    }

    if (mReceiverThread.joinable()) {
        mReceiverThread.join();
    }

    mJitterBuffer.stop();
}

void NativeAudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mStartStopMutex);
    if (!mIsRunning.exchange(false, std::memory_order_acq_rel)) return;

    // 1. Unblock UDP recvfrom by shutting down and closing socket
    int fd = mSocketFd.exchange(-1, std::memory_order_acq_rel);
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
        close(fd);
    }

    // 2. Stop Oboe callback delivery and close stream
    stopAndCloseOboeStream();

    // 3. Join receiver thread
    if (mReceiverThread.joinable()) {
        mReceiverThread.join();
    }

    // 4. Join reopen thread if running
    {
        std::lock_guard<std::mutex> rLock(mReopenMutex);
        if (mReopenThread.joinable()) {
            mReopenThread.join();
        }
    }

    // 5. Stop and join JitterBuffer decode worker
    mJitterBuffer.stop();

    LOGI("NativeAudioEngine stopped cleanly");
}

bool NativeAudioEngine::openOboeStream() {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    auto tryOpen = [&](oboe::SharingMode sharing, oboe::PerformanceMode perf) -> bool {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setFormat(oboe::AudioFormat::I16)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(48000)
            ->setFormatConversionAllowed(true)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setPerformanceMode(perf)
            ->setSharingMode(sharing)
            ->setDataCallback(this)
            ->setErrorCallback(this);

        oboe::Result result = builder.openStream(mAudioStream);
        if (result != oboe::Result::OK) {
            LOGW("Oboe openStream (sharing=%d, perf=%d) failed: %s",
                 static_cast<int>(sharing), static_cast<int>(perf), oboe::convertToText(result));
            mAudioStream.reset();
            return false;
        }
        return true;
    };

    if (mLowLatencyMode.load(std::memory_order_relaxed)) {
        if (tryOpen(oboe::SharingMode::Exclusive, oboe::PerformanceMode::LowLatency)) {
            return true;
        }
        if (tryOpen(oboe::SharingMode::Shared, oboe::PerformanceMode::LowLatency)) {
            return true;
        }
    }
    return tryOpen(oboe::SharingMode::Shared, oboe::PerformanceMode::None);
}

bool NativeAudioEngine::startOboeStream() {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (!mAudioStream) return false;

    oboe::Result result = mAudioStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start Oboe stream: %s", oboe::convertToText(result));
        return false;
    }
    return true;
}

void NativeAudioEngine::stopAndCloseOboeStream() {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (mAudioStream) {
        mAudioStream->stop();
        mAudioStream->close();
        mAudioStream.reset();
    }
}

void NativeAudioEngine::scheduleStreamReopen() {
    bool expected = false;
    if (!mReopenPending.compare_exchange_strong(expected, true)) {
        return;
    }

    std::lock_guard<std::mutex> lock(mReopenMutex);
    if (mReopenThread.joinable()) {
        mReopenThread.join();
    }

    mReopenThread = std::thread([this]() {
        LOGI("Oboe stream reopen worker started");
        stopAndCloseOboeStream();

        for (int retry = 0; retry < 5 && mIsRunning.load(); ++retry) {
            std::this_thread::sleep_for(std::chrono::milliseconds(250 * (1 << retry)));
            if (!mIsRunning.load()) break;

            if (openOboeStream() && startOboeStream()) {
                LOGI("Oboe stream successfully reopened on attempt %d", retry + 1);
                mReopenPending.store(false);
                return;
            }
        }

        mReopenPending.store(false);
        LOGW("Failed to reopen Oboe stream after retries");
    });
}

void NativeAudioEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result result) {
    LOGW("onErrorAfterClose fired: %s. Scheduling reopen.", oboe::convertToText(result));
    if (mIsRunning.load(std::memory_order_relaxed)) {
        scheduleStreamReopen();
    }
}

oboe::DataCallbackResult NativeAudioEngine::onAudioReady(
    oboe::AudioStream* /*stream*/,
    void* audioData,
    int32_t numFrames) {

    auto start_time = std::chrono::steady_clock::now();
    int16_t* output = static_cast<int16_t*>(audioData);

    // Guaranteed real-time safe: ZERO mutexes, ZERO allocations, ZERO opus decodes.
    mJitterBuffer.readAudio(output, numFrames);

    // Real-time safe mute, equalizer, and volume scaling
    if (mMuted.load(std::memory_order_relaxed)) {
        std::memset(output, 0, numFrames * 2 * sizeof(int16_t));
    } else {
        if (mEqResetStatePending.exchange(false, std::memory_order_acq_rel)) {
            std::memset(mEqZ1, 0, sizeof(mEqZ1));
            std::memset(mEqZ2, 0, sizeof(mEqZ2));
        }

        if (mEqEnabled.load(std::memory_order_acquire)) {
            int slot = mActiveEqSlot.load(std::memory_order_acquire);
            const double knee = 0.85;
            for (int n = 0; n < numFrames; ++n) {
                for (int ch = 0; ch < 2; ++ch) {
                    double x = output[n * 2 + ch] / 32768.0;
                    for (int b = 0; b < 5; ++b) {
                        const auto& c = mEqCoeffs[slot][b];
                        if (!c.active) continue;
                        double y = c.b0 * x + mEqZ1[b][ch];
                        mEqZ1[b][ch] = c.b1 * x - c.a1 * y + mEqZ2[b][ch];
                        mEqZ2[b][ch] = c.b2 * x - c.a2 * y;
                        x = y;
                    }
                    if (x > knee) {
                        x = knee + (1.0 - knee) * std::tanh((x - knee) / (1.0 - knee));
                    } else if (x < -knee) {
                        x = -knee - (1.0 - knee) * std::tanh((-x - knee) / (1.0 - knee));
                    }
                    int32_t clamped = static_cast<int32_t>(std::round(std::clamp(x, -1.0, 1.0) * 32767.0));
                    output[n * 2 + ch] = static_cast<int16_t>(std::clamp(clamped, -32768, 32767));
                }
            }
        }

        float gain = mVolumeGain.load(std::memory_order_relaxed);
        if (std::abs(gain - 1.0f) > 0.001f) {
            int total_samples = numFrames * 2;
            if (gain > 1.0f) {
                const double knee = 0.85;
                for (int i = 0; i < total_samples; ++i) {
                    double s = (output[i] / 32768.0) * gain;
                    if (s > knee) {
                        s = knee + (1.0 - knee) * std::tanh((s - knee) / (1.0 - knee));
                    } else if (s < -knee) {
                        s = -knee - (1.0 - knee) * std::tanh((-s - knee) / (1.0 - knee));
                    }
                    int32_t clamped = static_cast<int32_t>(std::round(std::clamp(s, -1.0, 1.0) * 32767.0));
                    output[i] = static_cast<int16_t>(std::clamp(clamped, -32768, 32767));
                }
            } else {
                for (int i = 0; i < total_samples; ++i) {
                    int32_t val = static_cast<int32_t>(output[i] * gain);
                    output[i] = static_cast<int16_t>(std::clamp(val, -32768, 32767));
                }
            }
        }
    }

    // Section 10: Callback performance instrumentation
    auto end_time = std::chrono::steady_clock::now();
    int64_t elapsed_micros = std::chrono::duration_cast<std::chrono::microseconds>(end_time - start_time).count();

    mTotalCallbackCount.fetch_add(1, std::memory_order_relaxed);
    mTotalCallbackMicros.fetch_add(elapsed_micros, std::memory_order_relaxed);

    int64_t current_max = mMaxCallbackMicros.load(std::memory_order_relaxed);
    while (elapsed_micros > current_max &&
           !mMaxCallbackMicros.compare_exchange_weak(current_max, elapsed_micros, std::memory_order_relaxed)) {}

    // Diagnostic deadline calculation: audio duration = (numFrames * 1,000,000) / sampleRate
    int64_t audio_duration_micros = (static_cast<int64_t>(numFrames) * 1000000LL) / 48000LL;
    int64_t deadline_threshold_micros = audio_duration_micros / 4; // 25% of audio duration
    if (elapsed_micros > deadline_threshold_micros) {
        mDeadlineMisses.fetch_add(1, std::memory_order_relaxed);
    }

    return oboe::DataCallbackResult::Continue;
}

void NativeAudioEngine::socketReceiverLoop() {
    uint8_t buffer[2048];
    struct sockaddr_in client_addr{};
    socklen_t client_len = sizeof(client_addr);

    while (mIsRunning.load(std::memory_order_relaxed)) {
        int fd = mSocketFd.load(std::memory_order_relaxed);
        if (fd < 0) break;

        ssize_t bytes_read = recvfrom(fd, buffer, sizeof(buffer), 0,
                                      (struct sockaddr*)&client_addr, &client_len);
        if (bytes_read <= 0) {
            continue;
        }

        // Cache sender IP address for auto-discovery
        char ip_str[INET_ADDRSTRLEN];
        if (inet_ntop(AF_INET, &(client_addr.sin_addr), ip_str, INET_ADDRSTRLEN)) {
            std::lock_guard<std::mutex> lock(mSenderIpMutex);
            mLastSenderIp = ip_str;
        }

        // Optional Party Mode mesh relay with 4-hop TTL loop prevention
        if (mPartyRelayEnabled.load(std::memory_order_relaxed) && bytes_read >= 12) {
            uint8_t rawCodec = buffer[8];
            uint8_t hopCount = (rawCodec >> 4) & 0x07;
            if (hopCount < 4) {
                uint8_t relayBuf[2048];
                std::memcpy(relayBuf, buffer, static_cast<size_t>(bytes_read));
                relayBuf[8] = (rawCodec & 0x8F) | static_cast<uint8_t>(((hopCount + 1) & 0x07) << 4);
                std::lock_guard<std::mutex> pLock(mPartyPeersMutex);
                for (const auto& peerAddr : mPartyPeerAddrs) {
                    if (peerAddr.sin_addr.s_addr != client_addr.sin_addr.s_addr) {
                        sendto(fd, relayBuf, static_cast<size_t>(bytes_read), 0,
                               (const struct sockaddr*)&peerAddr, sizeof(peerAddr));
                    }
                }
            }
        }

        // Deserialize and enqueue packet into bounded jitter buffer
        AudioPacket pkt;
        if (pkt.deserialize(buffer, bytes_read)) {
            mJitterBuffer.pushPacket(pkt);
        }
    }
}

void NativeAudioEngine::setTargetBufferMs(float target_ms) {
    mJitterBuffer.setTargetBufferMs(target_ms);
}

float NativeAudioEngine::getTargetBufferMs() {
    return mJitterBuffer.getTargetBufferMs();
}

void NativeAudioEngine::setAutoMode(bool enabled) {
    mJitterBuffer.setAutoMode(enabled);
}

bool NativeAudioEngine::isAutoMode() {
    return mJitterBuffer.isAutoMode();
}

void NativeAudioEngine::setMuted(bool muted) {
    mMuted.store(muted, std::memory_order_relaxed);
}

bool NativeAudioEngine::isMuted() const {
    return mMuted.load(std::memory_order_relaxed);
}

void NativeAudioEngine::setVolumeGain(float gain) {
    mVolumeGain.store(std::clamp(gain, 0.0f, 2.0f), std::memory_order_relaxed);
}

float NativeAudioEngine::getVolumeGain() const {
    return mVolumeGain.load(std::memory_order_relaxed);
}

void NativeAudioEngine::setLowLatencyMode(bool lowLatency) {
    mLowLatencyMode.store(lowLatency, std::memory_order_relaxed);
}

bool NativeAudioEngine::isLowLatencyMode() const {
    return mLowLatencyMode.load(std::memory_order_relaxed);
}

void NativeAudioEngine::setPartyRelayEnabled(bool enabled) {
    mPartyRelayEnabled.store(enabled, std::memory_order_relaxed);
}

bool NativeAudioEngine::isPartyRelayEnabled() const {
    return mPartyRelayEnabled.load(std::memory_order_relaxed);
}

void NativeAudioEngine::setPartyPeers(const std::vector<std::string>& peers) {
    std::vector<struct sockaddr_in> addrs;
    for (const auto& p : peers) {
        struct sockaddr_in sa{};
        sa.sin_family = AF_INET;
        sa.sin_port = htons(5004);
        if (inet_pton(AF_INET, p.c_str(), &(sa.sin_addr)) > 0) {
            addrs.push_back(sa);
        }
    }
    std::lock_guard<std::mutex> lock(mPartyPeersMutex);
    mPartyPeerAddrs = std::move(addrs);
}

void NativeAudioEngine::setEqualizerBands(bool enabled, float b0, float b1, float b2, float b3, float b4) {
    float bands[5] = {
        std::clamp(b0, -12.0f, 12.0f),
        std::clamp(b1, -12.0f, 12.0f),
        std::clamp(b2, -12.0f, 12.0f),
        std::clamp(b3, -12.0f, 12.0f),
        std::clamp(b4, -12.0f, 12.0f)
    };
    for (int i = 0; i < 5; ++i) {
        mEqGainsDb[i].store(bands[i], std::memory_order_relaxed);
    }

    constexpr double PI = 3.14159265358979323846;
    const double freqs[5] = {60.0, 230.0, 910.0, 3600.0, 14000.0};
    const double sr = 48000.0;
    const double q = 1.15;
    bool any_active = false;

    int nextSlot = 1 - mActiveEqSlot.load(std::memory_order_relaxed);
    for (int i = 0; i < 5; ++i) {
        double gainDb = bands[i];
        if (std::abs(gainDb) <= 0.05) {
            mEqCoeffs[nextSlot][i] = {1.0, 0.0, 0.0, 0.0, 0.0, false};
            continue;
        }
        any_active = true;
        double A = std::pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * PI * freqs[i] / sr;
        double alpha = std::sin(w0) / (2.0 * q);
        double cos_w0 = std::cos(w0);

        double b_0 = 1.0 + alpha * A;
        double b_1 = -2.0 * cos_w0;
        double b_2 = 1.0 - alpha * A;
        double a_0 = 1.0 + alpha / A;
        double a_1 = -2.0 * cos_w0;
        double a_2 = 1.0 - alpha / A;

        mEqCoeffs[nextSlot][i] = {
            b_0 / a_0,
            b_1 / a_0,
            b_2 / a_0,
            a_1 / a_0,
            a_2 / a_0,
            true
        };
    }

    bool active = enabled && any_active;
    if (!active) {
        mEqResetStatePending.store(true, std::memory_order_release);
    }
    mActiveEqSlot.store(nextSlot, std::memory_order_release);
    mEqEnabled.store(active, std::memory_order_release);
}

int32_t NativeAudioEngine::getAudioSessionId() const {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (mAudioStream) {
        return mAudioStream->getSessionId();
    }
    return 0;
}

JitterStats NativeAudioEngine::getStats() {
    return mJitterBuffer.getStats();
}

AdvancedEngineStats NativeAudioEngine::getAdvancedStats() {
    AdvancedEngineStats adv;
    JitterStatsSnapshot jStats = mJitterBuffer.getStatsSnapshot();

    adv.bufferMs = jStats.bufferMs;
    adv.targetMs = jStats.targetMs;
    adv.underruns = jStats.underruns;
    adv.lostPackets = jStats.lostPackets;
    adv.latePackets = jStats.latePackets;
    adv.duplicatePackets = jStats.duplicatePackets;
    adv.concealedPackets = jStats.concealedPackets;
    adv.intentionalDriftDrops = jStats.intentionalDriftDrops;
    adv.packetQueueOverflowDrops = jStats.packetQueueOverflowDrops;
    adv.pcmRingOverflowDrops = jStats.pcmRingOverflowDrops;
    adv.pcmRingUnderrunFrames = jStats.pcmRingUnderrunFrames;
    adv.pcmRingAvailableFrames = jStats.pcmRingAvailableFrames;
    adv.rxPps = static_cast<double>(jStats.rxPacketsPerSecond);
    adv.activeCodec = jStats.activeCodec;
    adv.autoMode = jStats.autoMode;

    uint64_t count = mTotalCallbackCount.load(std::memory_order_relaxed);
    uint64_t totalMicros = mTotalCallbackMicros.load(std::memory_order_relaxed);
    adv.callbackAverageMicros = (count > 0) ? (static_cast<double>(totalMicros) / static_cast<double>(count)) : 0.0;
    adv.callbackMaxMicros = mMaxCallbackMicros.load(std::memory_order_relaxed);
    adv.callbackDeadlineMisses = mDeadlineMisses.load(std::memory_order_relaxed);

    return adv;
}

std::string NativeAudioEngine::getSenderIp() {
    std::lock_guard<std::mutex> lock(mSenderIpMutex);
    return mLastSenderIp;
}

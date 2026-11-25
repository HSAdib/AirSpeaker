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

bool NativeAudioEngine::start(int audio_port, bool useExternalOutput) {
    std::lock_guard<std::mutex> lock(mStartStopMutex);
    mShuttingDown.store(false, std::memory_order_release);
    mReopenPending.store(false, std::memory_order_release);
    mReopenAgain.store(false, std::memory_order_release);
    if (mIsRunning.load(std::memory_order_acquire)) return true;

    mAudioPort = audio_port;
    mUseExternalOutput.store(useExternalOutput, std::memory_order_release);

    mTotalCallbackCount.store(0, std::memory_order_relaxed);
    mTotalCallbackMicros.store(0, std::memory_order_relaxed);
    mMaxCallbackMicros.store(0, std::memory_order_relaxed);
    mDeadlineMisses.store(0, std::memory_order_relaxed);
    std::memset(mEqZ1, 0, sizeof(mEqZ1));
    std::memset(mEqZ2, 0, sizeof(mEqZ2));
    mAudioEqSeq = 0;
    mAudioEqEnabled = false;

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
    tv.tv_usec = 200000;
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

    if (!useExternalOutput) {
        if (!openOboeStream()) {
            LOGE("Failed to open Oboe stream");
            cleanupAfterFailedStart();
            return false;
        }
    } else {
        mStreamSampleRate.store(48000, std::memory_order_relaxed);
        mJitterBuffer.onRouteChanged(mBluetoothRouteActive.load(std::memory_order_acquire), 480);
        LOGI("Starting NativeAudioEngine in external AudioTrack output mode (bt=%d)",
             static_cast<int>(mBluetoothRouteActive.load(std::memory_order_acquire)));
    }

    mJitterBuffer.start();
    mIsRunning.store(true, std::memory_order_release);

    try {
        mReceiverThread = std::thread(&NativeAudioEngine::socketReceiverLoop, this);
    } catch (const std::exception& e) {
        LOGE("Failed to start receiver thread: %s", e.what());
        cleanupAfterFailedStart();
        return false;
    }

    if (!useExternalOutput) {
        if (!startOboeStream()) {
            LOGE("Failed to start Oboe stream playback");
            cleanupAfterFailedStart();
            return false;
        }
    }

    LOGI("NativeAudioEngine started on port %d (externalAudioTrack=%d)", mAudioPort, static_cast<int>(useExternalOutput));
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
    mShuttingDown.store(true, std::memory_order_release);
    mIsRunning.store(false, std::memory_order_release);

    // Move reopen thread out under mReopenMutex, then join outside locks to prevent deadlock.
    std::thread reopenToJoin;
    {
        std::lock_guard<std::mutex> reopenLock(mReopenMutex);
        if (mReopenThread.joinable() &&
            mReopenThread.get_id() != std::this_thread::get_id()) {
            reopenToJoin = std::move(mReopenThread);
        }
    }
    if (reopenToJoin.joinable()) {
        reopenToJoin.join();
    }

    std::lock_guard<std::mutex> lock(mStartStopMutex);

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

    // 4. Stop and join JitterBuffer decode worker
    mJitterBuffer.stop();

    LOGI("NativeAudioEngine stopped cleanly");
}

bool NativeAudioEngine::openOboeStream() {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    const bool effectsEnabled = mEnableAudioEffects.load(std::memory_order_relaxed);
    const bool btActive = mBluetoothRouteActive.load(std::memory_order_acquire);

    auto tryOpen = [&](oboe::SharingMode sharing, oboe::PerformanceMode perf, oboe::AudioApi api = oboe::AudioApi::Unspecified) -> bool {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setFormat(oboe::AudioFormat::I16)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(48000)
            ->setFormatConversionAllowed(true)
            ->setSampleRateConversionQuality(
                btActive ? oboe::SampleRateConversionQuality::High : oboe::SampleRateConversionQuality::Medium
            )
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setPerformanceMode(perf)
            ->setSharingMode(sharing)
            ->setDataCallback(this)
            ->setErrorCallback(this);

        if (api != oboe::AudioApi::Unspecified) {
            builder.setAudioApi(api);
        }

        if (sharing == oboe::SharingMode::Shared || effectsEnabled) {
            builder.setSessionId(oboe::SessionId::Allocate);
        }

        oboe::Result result = builder.openStream(mAudioStream);
        if (result != oboe::Result::OK) {
            LOGW("Oboe openStream (api=%d, sharing=%d, perf=%d, bt=%d) failed: %s",
                 static_cast<int>(api), static_cast<int>(sharing), static_cast<int>(perf), static_cast<int>(btActive),
                 oboe::convertToText(result));
            mAudioStream.reset();
            return false;
        }

        int32_t burst = std::max<int32_t>(192, mAudioStream->getFramesPerBurst());
        int32_t cap = mAudioStream->getBufferCapacityInFrames();
        int32_t targetStreamFrames = btActive ? std::max(burst * 4, 3840) : (burst * 2);
        if (cap > 0) {
            mAudioStream->setBufferSizeInFrames(std::min(cap, targetStreamFrames));
        }

        int32_t actualSr = mAudioStream->getSampleRate();
        mStreamSampleRate.store(actualSr > 0 ? actualSr : 48000, std::memory_order_relaxed);
        mJitterBuffer.onRouteChanged(btActive, burst);

        LOGI("Oboe opened: bt=%d api=%d sharing=%d perf=%d sr=%d burst=%d bufFrames=%d cap=%d",
             static_cast<int>(btActive),
             static_cast<int>(mAudioStream->getAudioApi()),
             static_cast<int>(mAudioStream->getSharingMode()),
             static_cast<int>(mAudioStream->getPerformanceMode()),
             actualSr,
             burst,
             mAudioStream->getBufferSizeInFrames(),
             cap);
        return true;
    };

    if (btActive) {
        // Bluetooth A2DP/BLE: force OpenSLES first for reliable AudioFlinger A2DP HAL mixing,
        // fallback to Unspecified if OpenSLES fails.
        if (tryOpen(oboe::SharingMode::Shared, oboe::PerformanceMode::None, oboe::AudioApi::OpenSLES)) {
            return true;
        }
        return tryOpen(oboe::SharingMode::Shared, oboe::PerformanceMode::None, oboe::AudioApi::Unspecified);
    }

    if (mLowLatencyMode.load(std::memory_order_relaxed) && !effectsEnabled) {
        if (tryOpen(oboe::SharingMode::Exclusive, oboe::PerformanceMode::LowLatency)) {
            return true;
        }
        if (tryOpen(oboe::SharingMode::Shared, oboe::PerformanceMode::LowLatency)) {
            return true;
        }
    } else if (mLowLatencyMode.load(std::memory_order_relaxed) && effectsEnabled) {
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
    std::thread oldReopenThread;
    {
        std::lock_guard<std::mutex> lock(mReopenMutex);
        if (mUseExternalOutput.load(std::memory_order_acquire)) {
            return;
        }
        if (!mIsRunning.load(std::memory_order_acquire) ||
            mShuttingDown.load(std::memory_order_acquire)) {
            return;
        }
        bool expected = false;
        if (!mReopenPending.compare_exchange_strong(expected, true)) {
            mReopenAgain.store(true, std::memory_order_release);
            return;
        }
        if (mReopenThread.joinable() &&
            mReopenThread.get_id() != std::this_thread::get_id()) {
            oldReopenThread = std::move(mReopenThread);
        }
    }

    if (oldReopenThread.joinable()) {
        oldReopenThread.join();
    }

    std::lock_guard<std::mutex> lock(mReopenMutex);
    if (mUseExternalOutput.load(std::memory_order_acquire) ||
        !mIsRunning.load(std::memory_order_acquire) ||
        mShuttingDown.load(std::memory_order_acquire)) {
        mReopenPending.store(false, std::memory_order_release);
        return;
    }

    mReopenThread = std::thread([this]() {
        LOGI("Oboe stream reopen worker started");
        do {
            if (mUseExternalOutput.load(std::memory_order_acquire)) break;
            mReopenAgain.store(false, std::memory_order_release);
            stopAndCloseOboeStream();

            std::memset(mEqZ1, 0, sizeof(mEqZ1));
            std::memset(mEqZ2, 0, sizeof(mEqZ2));
            for (int retry = 0; retry < 5 && mIsRunning.load(std::memory_order_acquire) && !mShuttingDown.load(std::memory_order_acquire) && !mUseExternalOutput.load(std::memory_order_acquire); ++retry) {
                std::this_thread::sleep_for(std::chrono::milliseconds(120 * (1 << retry)));
                if (!mIsRunning.load(std::memory_order_acquire) || mShuttingDown.load(std::memory_order_acquire) || mUseExternalOutput.load(std::memory_order_acquire)) break;

                if (openOboeStream() && startOboeStream()) {
                    LOGI("Oboe stream successfully reopened on attempt %d", retry + 1);
                    break;
                }
            }
        } while (mReopenAgain.load(std::memory_order_acquire) && mIsRunning.load(std::memory_order_acquire) && !mShuttingDown.load(std::memory_order_acquire) && !mUseExternalOutput.load(std::memory_order_acquire));

        mReopenPending.store(false, std::memory_order_release);
    });
}

void NativeAudioEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result result) {
    if (mUseExternalOutput.load(std::memory_order_acquire)) {
        return;
    }
    LOGW("onErrorAfterClose fired: %s. Scheduling reopen.", oboe::convertToText(result));
    if (mIsRunning.load(std::memory_order_relaxed)) {
        scheduleStreamReopen();
    }
}

void NativeAudioEngine::processOutputBlock(int16_t* output, int32_t numFrames) {
    auto start_time = std::chrono::steady_clock::now();

    mJitterBuffer.observeCallbackFrames(numFrames);
    mJitterBuffer.readAudio(output, numFrames);

    uint64_t seq1 = mEqSeq.load(std::memory_order_acquire);
    if ((seq1 & 1ULL) == 0 && seq1 != mAudioEqSeq) {
        EqBandCoeffs tempCoeffs[5];
        bool tempEnabled = mEqStagingEnabled;
        for (int b = 0; b < 5; ++b) {
            tempCoeffs[b] = mEqStagingCoeffs[b];
        }
        std::atomic_thread_fence(std::memory_order_acquire);
        uint64_t seq2 = mEqSeq.load(std::memory_order_acquire);
        if (seq1 == seq2) {
            if (tempEnabled != mAudioEqEnabled) {
                std::memset(mEqZ1, 0, sizeof(mEqZ1));
                std::memset(mEqZ2, 0, sizeof(mEqZ2));
            } else {
                for (int b = 0; b < 5; ++b) {
                    if (tempCoeffs[b].active != mAudioEqCoeffs[b].active) {
                        mEqZ1[b][0] = 0.0; mEqZ1[b][1] = 0.0;
                        mEqZ2[b][0] = 0.0; mEqZ2[b][1] = 0.0;
                    }
                }
            }
            for (int b = 0; b < 5; ++b) {
                mAudioEqCoeffs[b] = tempCoeffs[b];
            }
            mAudioEqEnabled = tempEnabled;
            mAudioEqSeq = seq1;
        }
    }

    if (mMuted.load(std::memory_order_relaxed)) {
        std::memset(output, 0, static_cast<size_t>(numFrames) * 2 * sizeof(int16_t));
        std::memset(mEqZ1, 0, sizeof(mEqZ1));
        std::memset(mEqZ2, 0, sizeof(mEqZ2));
    } else {
        const float gain = mVolumeGain.load(std::memory_order_relaxed);
        const bool applyGain = std::abs(gain - 1.0f) > 0.001f;

        if (mAudioEqEnabled) {
            const double knee = 0.85;
            const double invOneMinusKnee = 1.0 / (1.0 - knee);
            const double dGain = static_cast<double>(gain);

            for (int n = 0; n < numFrames; ++n) {
                for (int ch = 0; ch < 2; ++ch) {
                    const double dry = output[n * 2 + ch] / 32768.0;
                    double x = dry;
                    bool stateOk = true;
                    for (int b = 0; b < 5; ++b) {
                        const auto& c = mAudioEqCoeffs[b];
                        if (!c.active) continue;
                        double y = c.b0 * x + mEqZ1[b][ch];
                        double nz1 = c.b1 * x - c.a1 * y + mEqZ2[b][ch];
                        double nz2 = c.b2 * x - c.a2 * y;
                        if (!std::isfinite(y) || !std::isfinite(nz1) || !std::isfinite(nz2)) {
                            mEqZ1[b][ch] = 0.0;
                            mEqZ2[b][ch] = 0.0;
                            stateOk = false;
                            break;
                        }
                        mEqZ1[b][ch] = nz1;
                        mEqZ2[b][ch] = nz2;
                        x = y;
                    }
                    if (!stateOk) {
                        x = dry;
                    }
                    if (applyGain) {
                        x *= dGain;
                    }
                    if (x > knee) {
                        x = knee + (1.0 - knee) * std::tanh((x - knee) * invOneMinusKnee);
                    } else if (x < -knee) {
                        x = -knee - (1.0 - knee) * std::tanh((-x - knee) * invOneMinusKnee);
                    }
                    int32_t clamped = static_cast<int32_t>(std::round(std::clamp(x, -1.0, 1.0) * 32767.0));
                    output[n * 2 + ch] = static_cast<int16_t>(std::clamp(clamped, -32768, 32767));
                }
            }

            for (int b = 0; b < 5; ++b) {
                for (int ch = 0; ch < 2; ++ch) {
                    if (std::abs(mEqZ1[b][ch]) < 1e-18) mEqZ1[b][ch] = 0.0;
                    if (std::abs(mEqZ2[b][ch]) < 1e-18) mEqZ2[b][ch] = 0.0;
                }
            }
        } else if (applyGain) {
            int total_samples = numFrames * 2;
            if (gain > 1.0f) {
                const double knee = 0.85;
                const double invOneMinusKnee = 1.0 / (1.0 - knee);
                for (int i = 0; i < total_samples; ++i) {
                    double s = (output[i] / 32768.0) * gain;
                    if (s > knee) {
                        s = knee + (1.0 - knee) * std::tanh((s - knee) * invOneMinusKnee);
                    } else if (s < -knee) {
                        s = -knee - (1.0 - knee) * std::tanh((-s - knee) * invOneMinusKnee);
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

    auto end_time = std::chrono::steady_clock::now();
    int64_t elapsed_micros = std::chrono::duration_cast<std::chrono::microseconds>(end_time - start_time).count();
    mTotalCallbackCount.fetch_add(1, std::memory_order_relaxed);
    mTotalCallbackMicros.fetch_add(elapsed_micros, std::memory_order_relaxed);

    int64_t current_max = mMaxCallbackMicros.load(std::memory_order_relaxed);
    while (elapsed_micros > current_max &&
           !mMaxCallbackMicros.compare_exchange_weak(current_max, elapsed_micros, std::memory_order_relaxed)) {}

    int64_t sr = std::max<int64_t>(8000, mStreamSampleRate.load(std::memory_order_relaxed));
    int64_t audio_duration_micros = (static_cast<int64_t>(numFrames) * 1000000LL) / sr;
    int64_t deadline_threshold_micros = audio_duration_micros / 4;
    if (elapsed_micros > deadline_threshold_micros) {
        mDeadlineMisses.fetch_add(1, std::memory_order_relaxed);
    }
}

oboe::DataCallbackResult NativeAudioEngine::onAudioReady(
    oboe::AudioStream* /*stream*/,
    void* audioData,
    int32_t numFrames) {
    processOutputBlock(static_cast<int16_t*>(audioData), numFrames);
    return oboe::DataCallbackResult::Continue;
}

int32_t NativeAudioEngine::renderExternalAudio(int16_t* output, int32_t numFrames) {
    if (!output || numFrames <= 0) return 0;
    if (!mIsRunning.load(std::memory_order_acquire) || !mUseExternalOutput.load(std::memory_order_acquire)) {
        std::memset(output, 0, static_cast<size_t>(numFrames) * 2 * sizeof(int16_t));
        return 0;
    }
    processOutputBlock(output, numFrames);
    return numFrames;
}

void NativeAudioEngine::setUseExternalOutput(bool useExternal) {
    bool prev = mUseExternalOutput.exchange(useExternal, std::memory_order_acq_rel);
    if (prev == useExternal) return;

    if (!mIsRunning.load(std::memory_order_acquire)) return;

    if (useExternal) {
        mReopenAgain.store(false, std::memory_order_release);
        std::thread reopenToJoin;
        {
            std::lock_guard<std::mutex> reopenLock(mReopenMutex);
            if (mReopenThread.joinable() && mReopenThread.get_id() != std::this_thread::get_id()) {
                reopenToJoin = std::move(mReopenThread);
            }
        }
        if (reopenToJoin.joinable()) {
            reopenToJoin.join();
        }
        mReopenPending.store(false, std::memory_order_release);
        stopAndCloseOboeStream();
        std::memset(mEqZ1, 0, sizeof(mEqZ1));
        std::memset(mEqZ2, 0, sizeof(mEqZ2));
        mStreamSampleRate.store(48000, std::memory_order_relaxed);
        mJitterBuffer.onRouteChanged(mBluetoothRouteActive.load(std::memory_order_acquire), 480);
        LOGI("Switched live output backend -> External Android AudioTrack");
    } else {
        std::memset(mEqZ1, 0, sizeof(mEqZ1));
        std::memset(mEqZ2, 0, sizeof(mEqZ2));
        LOGI("Switched live output backend -> Native Oboe");
        scheduleStreamReopen();
    }
}

bool NativeAudioEngine::isUsingExternalOutput() const {
    return mUseExternalOutput.load(std::memory_order_acquire);
}

int32_t NativeAudioEngine::getOutputBackendType() const {
    if (!mIsRunning.load(std::memory_order_acquire)) return 0;
    if (mUseExternalOutput.load(std::memory_order_acquire)) return 3;
    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (!mAudioStream) return 0;
    return (mAudioStream->getAudioApi() == oboe::AudioApi::OpenSLES) ? 2 : 1;
}

void NativeAudioEngine::socketReceiverLoop() {
    uint8_t buffer[4096];
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

        uint32_t allowedIpv4 = mAllowedSenderIpv4.load(std::memory_order_acquire);
        if (allowedIpv4 != 0) {
            if (client_addr.sin_addr.s_addr != allowedIpv4) {
                continue;
            }
        } else if (mRequireAllowedSender.load(std::memory_order_acquire)) {
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
                uint8_t relayBuf[4096];
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
    float safe = std::isfinite(target_ms) ? std::clamp(target_ms, 10.0f, 200.0f) : 30.0f;
    mJitterBuffer.setTargetBufferMs(safe);
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
    float safe = std::isfinite(gain) ? std::clamp(gain, 0.0f, 2.0f) : 1.0f;
    mVolumeGain.store(safe, std::memory_order_relaxed);
}

float NativeAudioEngine::getVolumeGain() const {
    return mVolumeGain.load(std::memory_order_relaxed);
}

void NativeAudioEngine::setLowLatencyMode(bool lowLatency) {
    bool prev = mLowLatencyMode.exchange(lowLatency, std::memory_order_relaxed);
    if (prev != lowLatency && mIsRunning.load(std::memory_order_relaxed)) {
        scheduleStreamReopen();
    }
}

bool NativeAudioEngine::isLowLatencyMode() const {
    return mLowLatencyMode.load(std::memory_order_relaxed);
}

void NativeAudioEngine::setEnableAudioEffects(bool enabled) {
    bool prev = mEnableAudioEffects.exchange(enabled, std::memory_order_relaxed);
    if (prev != enabled && mIsRunning.load(std::memory_order_relaxed)) {
        scheduleStreamReopen();
    }
}

bool NativeAudioEngine::isEnableAudioEffects() const {
    return mEnableAudioEffects.load(std::memory_order_relaxed);
}

void NativeAudioEngine::setBluetoothRouteActive(bool isBluetooth, bool forceReopen) {
    bool prev = mBluetoothRouteActive.exchange(isBluetooth, std::memory_order_acq_rel);
    mJitterBuffer.setBluetoothMode(isBluetooth);
    if (mUseExternalOutput.load(std::memory_order_acquire)) {
        if (prev != isBluetooth || forceReopen) {
            mJitterBuffer.onRouteChanged(isBluetooth, 480);
        }
        return;
    }
    if ((prev != isBluetooth || forceReopen) && mIsRunning.load(std::memory_order_acquire)) {
        LOGI("Audio route changed (bluetooth=%d, force=%d) — reopening Oboe stream",
             static_cast<int>(isBluetooth), static_cast<int>(forceReopen));
        scheduleStreamReopen();
    }
}

bool NativeAudioEngine::isBluetoothRouteActive() const {
    return mBluetoothRouteActive.load(std::memory_order_acquire);
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
    auto sanitize = [](float v) -> float {
        return std::isfinite(v) ? std::clamp(v, -12.0f, 12.0f) : 0.0f;
    };
    float bands[5] = {
        sanitize(b0),
        sanitize(b1),
        sanitize(b2),
        sanitize(b3),
        sanitize(b4)
    };
    for (int i = 0; i < 5; ++i) {
        mEqGainsDb[i].store(bands[i], std::memory_order_relaxed);
    }

    constexpr double PI = 3.14159265358979323846;
    const double freqs[5] = {60.0, 230.0, 910.0, 3600.0, 14000.0};
    const double sr = 48000.0;
    const double q = 1.15;
    bool any_active = false;
    EqBandCoeffs computed[5];

    for (int i = 0; i < 5; ++i) {
        double gainDb = bands[i];
        if (std::abs(gainDb) <= 0.05) {
            computed[i] = {1.0, 0.0, 0.0, 0.0, 0.0, false};
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

        computed[i] = {
            b_0 / a_0,
            b_1 / a_0,
            b_2 / a_0,
            a_1 / a_0,
            a_2 / a_0,
            true
        };
    }

    std::lock_guard<std::mutex> lock(mEqWriteMutex);
    uint64_t seq = mEqSeq.load(std::memory_order_relaxed);
    mEqSeq.store(seq + 1, std::memory_order_release); // Odd: write in progress
    std::atomic_thread_fence(std::memory_order_release);

    for (int i = 0; i < 5; ++i) {
        mEqStagingCoeffs[i] = computed[i];
    }
    mEqStagingEnabled = enabled && any_active;

    mEqSeq.store(seq + 2, std::memory_order_release); // Even: consistent snapshot ready
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

void NativeAudioEngine::setAllowedSenderIp(const std::string& ip, bool requireMatch) {
    std::lock_guard<std::mutex> lock(mAllowedSenderMutex);
    mRequireAllowedSender.store(requireMatch, std::memory_order_release);
    if (ip.empty() || ip == "0.0.0.0" || ip == "255.255.255.255") {
        mAllowedSenderIp.clear();
        mAllowedSenderIpv4.store(0, std::memory_order_release);
        return;
    }
    struct in_addr addr{};
    if (inet_pton(AF_INET, ip.c_str(), &addr) == 1) {
        mAllowedSenderIp = ip;
        mAllowedSenderIpv4.store(addr.s_addr, std::memory_order_release);
        LOGI("Source-IP audio filter locked to %s (requireMatch=%d)", ip.c_str(), static_cast<int>(requireMatch));
    } else {
        mAllowedSenderIp.clear();
        mAllowedSenderIpv4.store(0, std::memory_order_release);
    }
}

std::string NativeAudioEngine::getAllowedSenderIp() const {
    std::lock_guard<std::mutex> lock(mAllowedSenderMutex);
    return mAllowedSenderIp;
}

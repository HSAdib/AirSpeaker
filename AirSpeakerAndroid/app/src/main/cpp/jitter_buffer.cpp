#include "jitter_buffer.h"
#include <algorithm>
#include <cmath>

static inline int32_t seqDiff(uint32_t a, uint32_t b) {
    return static_cast<int32_t>(a - b);
}

static inline int64_t getNowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

JitterBuffer::JitterBuffer() {
    auto now = std::chrono::steady_clock::now();
    int64_t nowMs = getNowMs();
    mLastPacketRecvTimeMs.store(nowMs, std::memory_order_relaxed);
    mLastPpsCalcTimeMs.store(nowMs, std::memory_order_relaxed);
    mLastUnderrunAdaptTime = now;
    mLastDecayAdaptTime = now;

    mOpusDecoder.init(48000, 2);
    mLastValidFrame.resize(480 * 2, 0);
}

JitterBuffer::~JitterBuffer() {
    stop();
}

void JitterBuffer::start() {
    if (mIsRunning.exchange(true, std::memory_order_acq_rel)) return;
    reset();
    mIsRunning.store(true, std::memory_order_release);
    mDecodeThread = std::thread(&JitterBuffer::decodeWorkerLoop, this);
}

void JitterBuffer::stop() {
    if (!mIsRunning.exchange(false, std::memory_order_acq_rel)) return;
    {
        std::lock_guard<std::mutex> lock(mConditionMutex);
        mCondition.notify_all();
    }
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }
    reset();
}

void JitterBuffer::reset() {
    {
        std::lock_guard<std::mutex> lock(mPacketMutex);
        mPacketMap.clear();
        mFirstPacket = true;
        mHighestSeq64 = 0;
        mQueuedPacketCount.store(0, std::memory_order_relaxed);
    }
    mTotalRxPackets.store(0, std::memory_order_relaxed);
    mRxPacketsInterval.store(0, std::memory_order_relaxed);
    mCurrentRxPps.store(0, std::memory_order_relaxed);
    mLateCount.store(0, std::memory_order_relaxed);
    mDuplicatePackets.store(0, std::memory_order_relaxed);
    mPacketQueueOverflowDrops.store(0, std::memory_order_relaxed);
    mUnderrunCount.store(0, std::memory_order_relaxed);
    mLostCount.store(0, std::memory_order_relaxed);
    mConcealedPackets.store(0, std::memory_order_relaxed);
    mIntentionalDriftDrops.store(0, std::memory_order_relaxed);
    mStreamResetPending.store(false, std::memory_order_release);
    mObservedCallbackFrames.store(240, std::memory_order_relaxed);
    mPcmRingBuffer.reset();

    // Reset worker state
    mHasStarted = false;
    mIsPrebuffering = true;
    mExpectedSeq = 0;
    mConsecutiveLosses = 0;
    mLastFrameSamplesPerCh = 240;
    mPacketDurationMs.store(5.0f, std::memory_order_relaxed);
    std::fill(mLastValidFrame.begin(), mLastValidFrame.end(), 0);

    mWorkerExpectedSeq.store(0, std::memory_order_relaxed);
}

void JitterBuffer::pushPacket(const uint8_t* wireData, size_t size) {
    AudioPacket pkt;
    if (!pkt.deserialize(wireData, size)) return;
    pushPacket(pkt);
}

void JitterBuffer::pushPacket(const AudioPacket& packet) {
    if (packet.payload.empty() || packet.payload.size() > 4096) {
        return;
    }
    mTotalRxPackets.fetch_add(1, std::memory_order_relaxed);

    int64_t nowMs = getNowMs();
    int64_t prevRecvMs = mLastPacketRecvTimeMs.exchange(nowMs, std::memory_order_relaxed);
    int64_t gapSinceLastPacketMs = (prevRecvMs > 0) ? (nowMs - prevRecvMs) : 0;

    // Calculate packets-per-second (PPS) atomically
    mRxPacketsInterval.fetch_add(1, std::memory_order_relaxed);
    int64_t lastPpsMs = mLastPpsCalcTimeMs.load(std::memory_order_relaxed);
    int64_t elapsedMs = nowMs - lastPpsMs;
    if (elapsedMs >= 1000) {
        if (mLastPpsCalcTimeMs.compare_exchange_strong(lastPpsMs, nowMs, std::memory_order_relaxed)) {
            uint64_t count = mRxPacketsInterval.exchange(0, std::memory_order_relaxed);
            mCurrentRxPps.store((count * 1000) / std::max<int64_t>(1, elapsedMs), std::memory_order_relaxed);
        }
    }

    // Packet insertion and bounded queue management
    {
        std::lock_guard<std::mutex> lock(mPacketMutex);

        uint32_t expected = mWorkerExpectedSeq.load(std::memory_order_relaxed);

        // Detect sender restart (seq reset to 1..15) or stream pause > 600ms
        if (gapSinceLastPacketMs > 600 || (expected > 30 && expected < 0xFFFE0000U && packet.header.seq < 15)) {
            mPacketMap.clear();
            mFirstPacket = true;
            mHighestSeq64 = 0;
            mWorkerExpectedSeq.store(packet.header.seq, std::memory_order_relaxed);
            mStreamResetPending.store(true, std::memory_order_release);
            expected = packet.header.seq;
        }

        if (expected > 0) {
            int32_t diff = seqDiff(packet.header.seq, expected);
            if (diff < 0 && diff > -200) {
                mLateCount.fetch_add(1, std::memory_order_relaxed);
                return;
            }
        }

        uint32_t seq32 = packet.header.seq;
        uint64_t seq64;
        if (mFirstPacket) {
            mHighestSeq64 = seq32;
            seq64 = seq32;
            mFirstPacket = false;
        } else {
            int32_t diff = seqDiff(seq32, static_cast<uint32_t>(mHighestSeq64));
            seq64 = static_cast<uint64_t>(static_cast<int64_t>(mHighestSeq64) + diff);
            if (diff > 0) {
                mHighestSeq64 = seq64;
            }
        }

        if (mPacketMap.find(seq64) != mPacketMap.end()) {
            mDuplicatePackets.fetch_add(1, std::memory_order_relaxed);
            return;
        }

        mPacketMap[seq64] = packet;

        // Section 4: Hard capacity bound (256 packets = ~1.28s at 5ms/frame)
        while (mPacketMap.size() > MAX_PACKET_QUEUE_SIZE) {
            mPacketMap.erase(mPacketMap.begin());
            mPacketQueueOverflowDrops.fetch_add(1, std::memory_order_relaxed);
        }

        mQueuedPacketCount.store(mPacketMap.size(), std::memory_order_relaxed);
    }

    // Notify decode worker that data is available
    mCondition.notify_one();
}

int JitterBuffer::readAudio(int16_t* out_samples, int num_frames) {
    // REAL-TIME SAFE: Delegates directly to lock-free SPSC PCM Ring Buffer.
    // ZERO mutexes, ZERO allocations, ZERO opus decodes.
    return mPcmRingBuffer.readFrames(out_samples, num_frames);
}

void JitterBuffer::decodeWorkerLoop() {
    int16_t decoded_buf[960 * 2]; // Scratch buffer for up to 20ms at 48kHz stereo

    while (mIsRunning.load(std::memory_order_acquire)) {
        bool didWork = processPackets(decoded_buf);
        if (!mIsRunning.load(std::memory_order_relaxed)) break;

        if (!didWork) {
            std::unique_lock<std::mutex> lock(mConditionMutex);
            if (mQueuedPacketCount.load(std::memory_order_relaxed) == 0) {
                mCondition.wait_for(lock, std::chrono::milliseconds(5), [this] {
                    return !mIsRunning.load(std::memory_order_relaxed) ||
                           mQueuedPacketCount.load(std::memory_order_relaxed) > 0;
                });
            } else {
                // Ring buffer is already full or still prebuffering — sleep 2ms instead of 100% CPU spin
                mCondition.wait_for(lock, std::chrono::milliseconds(2), [this] {
                    return !mIsRunning.load(std::memory_order_relaxed) ||
                           mStreamResetPending.load(std::memory_order_acquire);
                });
            }
        }
    }
}

bool JitterBuffer::processPackets(int16_t* decoded_buf) {
    int64_t nowMs = getNowMs();
    int64_t sinceLastRxMs = nowMs - mLastPacketRecvTimeMs.load(std::memory_order_relaxed);

    if (sinceLastRxMs > 1500) {
        mCurrentRxPps.store(0, std::memory_order_relaxed);
    }

    bool resetReq = mStreamResetPending.exchange(false, std::memory_order_acq_rel);
    if (!mHasStarted || resetReq || sinceLastRxMs > 800) {
        const bool wasStarted = mHasStarted;
        mHasStarted = false;
        mIsPrebuffering = true;
        if (resetReq) {
            mPcmRingBuffer.reset();
        }
        std::lock_guard<std::mutex> lock(mPacketMutex);
        if (mPacketMap.empty()) return false;

        if (resetReq && wasStarted && mPacketMap.size() > 6) {
            auto keepFrom = mPacketMap.end();
            std::advance(keepFrom, -4);
            mPacketMap.erase(mPacketMap.begin(), keepFrom);
            mQueuedPacketCount.store(mPacketMap.size(), std::memory_order_relaxed);
        }

        // Initialize from first available packet
        auto first = mPacketMap.begin();
        mExpectedSeq = first->first;
        mWorkerExpectedSeq.store(static_cast<uint32_t>(mExpectedSeq), std::memory_order_relaxed);
        mCurrentCodec = first->second.header.codec;
        mActiveCodec.store(mCurrentCodec, std::memory_order_relaxed);
        int sr = 48000;
        if (first->second.header.sample_rate_div100 > 0) {
            sr = first->second.header.sample_rate_div100 * 100;
            mSampleRate.store(sr, std::memory_order_relaxed);
        }
        // Inspect actual packet duration before prebuffering so 10ms/20ms streams don't over-buffer
        int detectedFrames = 0;
        if (first->second.header.codec == CODEC_PCM16) {
            int pktCh = (first->second.header.channels == 1) ? 1 : 2;
            detectedFrames = std::clamp(
                static_cast<int>(first->second.payload.size() / (pktCh * sizeof(int16_t))),
                0,
                960
            );
        } else if (!first->second.payload.empty()) {
#if defined(HAS_OPUS) || defined(__ANDROID__)
            int nb = opus_packet_get_nb_samples(
                first->second.payload.data(),
                static_cast<opus_int32>(first->second.payload.size()),
                sr
            );
            if (nb > 0 && nb <= 960) {
                detectedFrames = nb;
            }
#endif
        }
        if (detectedFrames > 0) {
            mLastFrameSamplesPerCh = detectedFrames;
            mPacketDurationMs.store((detectedFrames * 1000.0f) / static_cast<float>(sr), std::memory_order_relaxed);
        }
        mHasStarted = true;
    }

    // Apply cross-thread configuration changes
    bool autoMode = mAutoMode.load(std::memory_order_relaxed);
    float configured = std::clamp(mConfiguredTargetBufferMs.load(std::memory_order_relaxed), 10.0f, 200.0f);
    if (!autoMode || configured != mLastAppliedConfiguredTargetMs) {
        mLocalTargetBufferMs = configured;
        mLastAppliedConfiguredTargetMs = configured;
    }
    adaptTargetBuffer();

    const bool btMode = mBluetoothMode.load(std::memory_order_acquire);
    const size_t sampleRate = (mSampleRate.load(std::memory_order_relaxed) > 0)
        ? static_cast<size_t>(mSampleRate.load(std::memory_order_relaxed))
        : 48000;
    const size_t obsFrames = mObservedCallbackFrames.load(std::memory_order_relaxed);

    // CRITICAL BLUETOOTH FIX:
    // Bluetooth A2DP callbacks request 960..2880+ frames per burst.
    // Keep ring buffer high enough (>= 2x observed callback burst + headroom) so onAudioReady NEVER starves.
    const size_t baseLowWaterFrames = btMode ? ((sampleRate * 50) / 1000) : ((sampleRate * 15) / 1000);
    const size_t burstSafeFrames = (obsFrames * 2) + (btMode ? 480 : 240);
    const size_t lowWaterFrames = std::clamp<size_t>(
        std::max(baseLowWaterFrames, burstSafeFrames),
        480,
        PcmRingBuffer::CAPACITY_FRAMES / 2
    );
    const float lowWaterMs = (static_cast<float>(lowWaterFrames) * 1000.0f) / static_cast<float>(sampleRate);
    const float minEffectiveTarget = btMode
        ? std::max(100.0f, lowWaterMs + 40.0f)
        : std::max(10.0f, lowWaterMs + 5.0f);
    const float effectiveTargetMs = std::clamp(std::max(mLocalTargetBufferMs, minEffectiveTarget), 10.0f, 200.0f);

    mEffectiveTargetBufferMs.store(effectiveTargetMs, std::memory_order_relaxed);
    mReportedTargetBufferMs.store(effectiveTargetMs, std::memory_order_relaxed);

    float ringOccupiedMs = mPcmRingBuffer.getOccupiedMs(static_cast<int>(sampleRate));
    size_t packetQueueSize = mQueuedPacketCount.load(std::memory_order_relaxed);
    float pktDurMs = std::max(1.0f, mPacketDurationMs.load(std::memory_order_relaxed));
    float totalQueuedMs = (packetQueueSize * pktDurMs) + ringOccupiedMs;

    // Startup prebuffering: wait until queued audio reaches effective target buffer
    if (mIsPrebuffering) {
        float startThreshold = effectiveTargetMs;
        int minPackets = std::max(2, static_cast<int>(std::ceil(startThreshold / pktDurMs)));
        if (totalQueuedMs < startThreshold || static_cast<int>(packetQueueSize) < minPackets) {
            return false;
        }
        mIsPrebuffering = false;
        mConsecutiveLosses = 0;
        std::lock_guard<std::mutex> lock(mPacketMutex);
        if (!mPacketMap.empty()) {
            mExpectedSeq = mPacketMap.begin()->first;
            mWorkerExpectedSeq.store(static_cast<uint32_t>(mExpectedSeq), std::memory_order_relaxed);
        }
    }

    const float driftHeadroomMs = btMode ? 35.0f : 15.0f;
    bool decodedAny = false;

    // Decode packets and push PCM to the SPSC ring buffer while space permits (up to 960 frames = 20ms)
    while (mPcmRingBuffer.getOccupiedFrames() < lowWaterFrames &&
           mPcmRingBuffer.getOccupiedFrames() + 960 <= PcmRingBuffer::CAPACITY_FRAMES) {
        // Drift trimming: if total queued duration exceeds effectiveTargetMs + driftHeadroomMs, discard oldest packet
        {
            std::lock_guard<std::mutex> lock(mPacketMutex);
            float currentQueuedMs = (mPacketMap.size() * mPacketDurationMs.load(std::memory_order_relaxed))
                + mPcmRingBuffer.getOccupiedMs(static_cast<int>(sampleRate));
            if (currentQueuedMs > (effectiveTargetMs + driftHeadroomMs) && mPacketMap.size() > 4) {
                auto oldest = mPacketMap.begin();
                mExpectedSeq = oldest->first + 1;
                mWorkerExpectedSeq.store(static_cast<uint32_t>(mExpectedSeq), std::memory_order_relaxed);
                mPacketMap.erase(oldest);
                mQueuedPacketCount.store(mPacketMap.size(), std::memory_order_relaxed);
                mIntentionalDriftDrops.fetch_add(1, std::memory_order_relaxed);
            }
        }

        bool has_packet = false;
        AudioPacket pkt;
        bool has_next_fec_candidate = false;
        AudioPacket next_pkt;

        {
            std::lock_guard<std::mutex> lock(mPacketMutex);
            if (mPacketMap.empty()) {
                if (mConsecutiveLosses >= 8 || mPcmRingBuffer.getOccupiedFrames() == 0) {
                    mIsPrebuffering = true;
                }
                break;
            }

            // If sequence is far behind, catch up to prevent stale playout
            uint64_t earliestSeq = mPacketMap.begin()->first;
            int64_t gap = static_cast<int64_t>(earliestSeq) - static_cast<int64_t>(mExpectedSeq);
            if (gap > 4 || gap < -20 || (mConsecutiveLosses >= 2 && gap != 0)) {
                mExpectedSeq = earliestSeq;
                mWorkerExpectedSeq.store(static_cast<uint32_t>(mExpectedSeq), std::memory_order_relaxed);
            }

            auto it = mPacketMap.find(mExpectedSeq);
            if (it != mPacketMap.end()) {
                pkt = it->second;
                mPacketMap.erase(it);
                mQueuedPacketCount.store(mPacketMap.size(), std::memory_order_relaxed);
                has_packet = true;
            } else {
                // Gap detected: check if next packet N+1 is available for Opus in-band FEC
                if (mCurrentCodec != CODEC_PCM16) {
                    auto nextIt = mPacketMap.find(mExpectedSeq + 1);
                    if (nextIt != mPacketMap.end() && !nextIt->second.payload.empty()) {
                        next_pkt = nextIt->second;
                        has_next_fec_candidate = true;
                    }
                }
            }
        }

        if (has_packet) {
            mCurrentCodec = pkt.header.codec;
            mActiveCodec.store(mCurrentCodec, std::memory_order_relaxed);
            if (pkt.header.sample_rate_div100 > 0) {
                mSampleRate.store(pkt.header.sample_rate_div100 * 100, std::memory_order_relaxed);
            }

            // Normal decode
            int total_frames = 0;
            const int pktChannels = (pkt.header.channels == 1) ? 1 : 2;

            if (pkt.header.codec == CODEC_PCM16) {
                int frame_count = static_cast<int>(pkt.payload.size() / (pktChannels * sizeof(int16_t)));
                frame_count = std::clamp(frame_count, 0, 960);
                if (frame_count <= 0) {
                    total_frames = 0;
                } else {
                    mLastFrameSamplesPerCh = frame_count;
                    mPacketDurationMs.store((frame_count * 1000.0f) / mSampleRate.load(std::memory_order_relaxed), std::memory_order_relaxed);
                    total_frames = frame_count;

                    if (pktChannels == 2) {
                        int copyBytes = std::min(static_cast<size_t>(frame_count * 2 * sizeof(int16_t)), pkt.payload.size());
                        std::memcpy(decoded_buf, pkt.payload.data(), copyBytes);
                    } else {
                        const int16_t* monoPtr = reinterpret_cast<const int16_t*>(pkt.payload.data());
                        int availMono = std::min(frame_count, static_cast<int>(pkt.payload.size() / sizeof(int16_t)));
                        for (int i = 0; i < availMono; i++) {
                            decoded_buf[i * 2] = monoPtr[i];
                            decoded_buf[i * 2 + 1] = monoPtr[i];
                        }
                    }
                }
            } else {
                int max_frames = 960;
                int decoded = mOpusDecoder.decode(pkt.payload.data(), pkt.payload.size(), decoded_buf, max_frames, false);
                if (decoded > 0) {
                    mLastFrameSamplesPerCh = decoded;
                    mPacketDurationMs.store((decoded * 1000.0f) / mSampleRate.load(std::memory_order_relaxed), std::memory_order_relaxed);
                    total_frames = decoded;
                } else {
                    total_frames = mLastFrameSamplesPerCh;
                    std::memset(decoded_buf, 0, total_frames * 2 * sizeof(int16_t));
                }
            }

            // Stereo-coherent crossfade on recovery from loss
            if (mConsecutiveLosses > 0 && total_frames > 0 && !mLastValidFrame.empty()) {
                int fade_frames = std::min({32, total_frames, static_cast<int>(mLastValidFrame.size() / 2)});
                if (fade_frames > 0) {
                    size_t tailOffset = mLastValidFrame.size() - static_cast<size_t>(fade_frames * 2);
                    for (size_t f = 0; f < static_cast<size_t>(fade_frames); ++f) {
                        const float alpha = static_cast<float>(f + 1) / static_cast<float>(fade_frames);
                        for (int ch = 0; ch < 2; ++ch) {
                            size_t idx = f * 2 + ch;
                            decoded_buf[idx] = static_cast<int16_t>(
                                mLastValidFrame[tailOffset + idx] * (1.0f - alpha) + decoded_buf[idx] * alpha
                            );
                        }
                    }
                }
            }

            if (total_frames > 0) {
                mLastValidFrame.assign(decoded_buf, decoded_buf + total_frames * 2);
                mPcmRingBuffer.writeFrames(decoded_buf, total_frames);
                decodedAny = true;
            }

            mConsecutiveLosses = 0;
            mExpectedSeq++;
            mWorkerExpectedSeq.store(static_cast<uint32_t>(mExpectedSeq), std::memory_order_relaxed);
        } else {
            // Gap detected: frame mExpectedSeq is missing
            mLostCount.fetch_add(1, std::memory_order_relaxed);

            auto underrunNow = std::chrono::steady_clock::now();
            if (mAutoMode.load(std::memory_order_relaxed)) {
                auto sinceLastBumpMs = std::chrono::duration_cast<std::chrono::milliseconds>(underrunNow - mLastUnderrunAdaptTime).count();
                if (sinceLastBumpMs >= 300) {
                    mLocalTargetBufferMs = std::min(200.0f, mLocalTargetBufferMs + 5.0f);
                    mLastUnderrunAdaptTime = underrunNow;
                }
            }

            int total_frames = 0;

            // Opus In-Band FEC Sequencing:
            // When packet N is missing and N+1 is present, decode N+1 with decode_fec=true for frame N.
            // Packet N+1 remains in mPacketMap to be decoded normally with decode_fec=false on the next iteration!
            if (has_next_fec_candidate) {
                int fec_decoded = mOpusDecoder.decode(next_pkt.payload.data(), next_pkt.payload.size(),
                                                      decoded_buf, mLastFrameSamplesPerCh, /*decode_fec=*/true);
                if (fec_decoded > 0) {
                    total_frames = fec_decoded;
                    mConcealedPackets.fetch_add(1, std::memory_order_relaxed);
                }
            }

            if (total_frames <= 0) {
                // Fallback PLC concealment (no N+1 available or PCM mode)
                if (mCurrentCodec != CODEC_PCM16) {
                    int plc_decoded = mOpusDecoder.decode(nullptr, 0, decoded_buf, mLastFrameSamplesPerCh, false);
                    if (plc_decoded > 0) {
                        total_frames = plc_decoded;
                    } else {
                        total_frames = mLastFrameSamplesPerCh;
                        std::memset(decoded_buf, 0, total_frames * 2 * sizeof(int16_t));
                    }
                } else {
                    total_frames = mLastFrameSamplesPerCh;
                    concealPcmGap(total_frames, decoded_buf);
                }
                mConcealedPackets.fetch_add(1, std::memory_order_relaxed);
            }

            if (total_frames > 0) {
                mLastValidFrame.assign(decoded_buf, decoded_buf + total_frames * 2);
                mPcmRingBuffer.writeFrames(decoded_buf, total_frames);
                decodedAny = true;
            }

            mConsecutiveLosses++;
            mExpectedSeq++;
            mWorkerExpectedSeq.store(static_cast<uint32_t>(mExpectedSeq), std::memory_order_relaxed);

            if (mConsecutiveLosses >= 8) {
                mIsPrebuffering = true;
                break;
            }
        }
    }
    return decodedAny;
}

void JitterBuffer::concealPcmGap(int frames, int16_t* out_buf) {
    int total_samples = frames * mChannels;
    if (mConsecutiveLosses >= 6 || mLastValidFrame.empty()) {
        std::memset(out_buf, 0, total_samples * sizeof(int16_t));
        return;
    }

    float factor = std::pow(0.8f, static_cast<float>(mConsecutiveLosses + 1));
    int copyCount = std::min(static_cast<int>(mLastValidFrame.size()), total_samples);

    for (int i = 0; i < copyCount; i++) {
        out_buf[i] = static_cast<int16_t>(mLastValidFrame[i] * factor);
    }
    if (copyCount < total_samples) {
        std::memset(out_buf + copyCount, 0, (total_samples - copyCount) * sizeof(int16_t));
    }
}

void JitterBuffer::adaptTargetBuffer() {
    if (!mAutoMode.load(std::memory_order_relaxed)) return;
    auto now = std::chrono::steady_clock::now();
    auto noUnderrunSec = std::chrono::duration_cast<std::chrono::seconds>(now - mLastUnderrunAdaptTime).count();
    auto decayIntervalSec = std::chrono::duration_cast<std::chrono::seconds>(now - mLastDecayAdaptTime).count();

    if (noUnderrunSec >= 10 && decayIntervalSec >= 10) {
        float floorTarget = std::max(10.0f, mConfiguredTargetBufferMs.load(std::memory_order_relaxed));
        mLocalTargetBufferMs = std::max(floorTarget, mLocalTargetBufferMs - 2.5f);
        mLastDecayAdaptTime = now;
    }
}

void JitterBuffer::setTargetBufferMs(float target_ms) {
    float safe = std::isfinite(target_ms) ? std::clamp(target_ms, 10.0f, 200.0f) : 30.0f;
    mConfiguredTargetBufferMs.store(safe, std::memory_order_relaxed);
}

float JitterBuffer::getTargetBufferMs() const {
    return mEffectiveTargetBufferMs.load(std::memory_order_relaxed);
}

void JitterBuffer::setAutoMode(bool enabled) {
    mAutoMode.store(enabled, std::memory_order_relaxed);
}

bool JitterBuffer::isAutoMode() const {
    return mAutoMode.load(std::memory_order_relaxed);
}

void JitterBuffer::setBluetoothMode(bool enabled) {
    mBluetoothMode.store(enabled, std::memory_order_release);
}

bool JitterBuffer::isBluetoothMode() const {
    return mBluetoothMode.load(std::memory_order_acquire);
}

void JitterBuffer::onRouteChanged(bool isBluetooth, int32_t burstFrames) {
    mBluetoothMode.store(isBluetooth, std::memory_order_release);
    size_t initBurst = (burstFrames > 0) ? static_cast<size_t>(burstFrames) : 240;
    mObservedCallbackFrames.store(std::clamp<size_t>(initBurst, 240, 8192), std::memory_order_relaxed);
    mStreamResetPending.store(true, std::memory_order_release);
    mCondition.notify_one();
}

void JitterBuffer::observeCallbackFrames(int32_t numFrames) {
    if (numFrames <= 0) return;
    size_t clamped = std::clamp<size_t>(static_cast<size_t>(numFrames), 240, 8192);
    size_t cur = mObservedCallbackFrames.load(std::memory_order_relaxed);
    while (clamped > cur &&
           !mObservedCallbackFrames.compare_exchange_weak(cur, clamped, std::memory_order_relaxed)) {}
}

JitterStatsSnapshot JitterBuffer::getStatsSnapshot() const {
    JitterStatsSnapshot stats;
    size_t packetCount = mQueuedPacketCount.load(std::memory_order_relaxed);
    float ringMs = mPcmRingBuffer.getOccupiedMs(mSampleRate.load(std::memory_order_relaxed));

    stats.bufferMs = (packetCount * mPacketDurationMs.load(std::memory_order_relaxed)) + ringMs;
    stats.targetMs = mReportedTargetBufferMs.load(std::memory_order_relaxed);
    stats.underruns = mUnderrunCount.load(std::memory_order_relaxed) + (mPcmRingBuffer.underrunFrames() / 240);
    stats.lostPackets = mLostCount.load(std::memory_order_relaxed);
    stats.latePackets = mLateCount.load(std::memory_order_relaxed);
    stats.duplicatePackets = mDuplicatePackets.load(std::memory_order_relaxed);
    stats.concealedPackets = mConcealedPackets.load(std::memory_order_relaxed);
    stats.intentionalDriftDrops = mIntentionalDriftDrops.load(std::memory_order_relaxed);
    stats.packetQueueOverflowDrops = mPacketQueueOverflowDrops.load(std::memory_order_relaxed);
    stats.pcmRingOverflowDrops = mPcmRingBuffer.overflowDroppedFrames();
    stats.pcmRingUnderrunFrames = mPcmRingBuffer.underrunFrames();
    stats.pcmRingAvailableFrames = mPcmRingBuffer.availableFrames();
    stats.rxPacketsPerSecond = mCurrentRxPps.load(std::memory_order_relaxed);
    stats.activeCodec = mActiveCodec.load(std::memory_order_relaxed);
    stats.autoMode = mAutoMode.load(std::memory_order_relaxed);
    stats.rxPackets = mTotalRxPackets.load(std::memory_order_relaxed);

    return stats;
}

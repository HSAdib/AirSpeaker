#include "jitter_buffer.h"
#include <algorithm>
#include <cmath>

static inline int32_t seqDiff(uint32_t a, uint32_t b) {
    return static_cast<int32_t>(a - b);
}

JitterBuffer::JitterBuffer() {
    auto now = std::chrono::steady_clock::now();
    mLastPacketRecvTime = now;
    mLastPpsTime = now;
    mLastUnderrunAdaptTime = now;
    mLastDecayAdaptTime = now;
    mOpusDecoder.init(48000, 2);
    mLastValidFrame.resize(480 * 2, 0);
}

JitterBuffer::~JitterBuffer() {
    stop();
}

void JitterBuffer::start() {
    if (mIsRunning.exchange(true)) return;
    reset();
    mIsRunning.store(true);
    mDecodeThread = std::thread(&JitterBuffer::decodeWorkerLoop, this);
}

void JitterBuffer::stop() {
    if (!mIsRunning.exchange(false)) return;
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
    }
    mPcmRingBuffer.reset();
    mHasStarted = false;
    mIsPrebuffering = true;
    mExpectedSeq = 0;
    mConsecutiveLosses = 0;
    mLastFrameSamplesPerCh = 240;
    mPacketDurationMs = 5.0f;
    std::fill(mLastValidFrame.begin(), mLastValidFrame.end(), 0);
}

void JitterBuffer::pushPacket(const AudioPacket& packet) {
    auto now = std::chrono::steady_clock::now();
    mLastPacketRecvTime = now;

    mRxPacketsInterval.fetch_add(1, std::memory_order_relaxed);
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - mLastPpsTime).count();
    if (elapsed >= 1000) {
        mCurrentRxPps.store((mRxPacketsInterval.exchange(0) * 1000) / elapsed, std::memory_order_relaxed);
        mLastPpsTime = now;
    }

    mCurrentCodec = packet.header.codec;
    mChannels = 2; // Output stream is always stereo
    if (packet.header.sample_rate_div100 > 0) {
        mSampleRate = packet.header.sample_rate_div100 * 100;
    }

    {
        std::lock_guard<std::mutex> lock(mPacketMutex);

        if (!mHasStarted) {
            mPacketMap.clear();
            mPcmRingBuffer.reset();
            mExpectedSeq = packet.header.seq;
            mConsecutiveLosses = 0;
            mIsPrebuffering = true;
            mHasStarted = true;
        } else {
            int32_t diff = seqDiff(packet.header.seq, mExpectedSeq);
            if (diff < -100 || diff > 1000) {
                // Large sequence discontinuity (sender restarted or jumped)
                mPacketMap.clear();
                mPcmRingBuffer.reset();
                mExpectedSeq = packet.header.seq;
                mConsecutiveLosses = 0;
                mIsPrebuffering = false;
            } else if (diff < 0) {
                mLateCount.fetch_add(1, std::memory_order_relaxed);
                return;
            }
        }

        if (mPacketMap.find(packet.header.seq) != mPacketMap.end()) {
            mDuplicatePackets.fetch_add(1, std::memory_order_relaxed);
            return;
        }

        mPacketMap[packet.header.seq] = packet;

        // Bounded capacity on packet map (100 packets = ~500ms)
        while (mPacketMap.size() > 100) {
            mPacketMap.erase(mPacketMap.begin());
            if (!mPacketMap.empty() && seqDiff(mPacketMap.begin()->first, mExpectedSeq) > 0) {
                mExpectedSeq = mPacketMap.begin()->first;
            }
        }
    }

    // Notify decode worker that new data has arrived
    mCondition.notify_one();
}

int JitterBuffer::readAudio(int16_t* out_samples, int num_frames) {
    // REAL-TIME SAFE: No mutex, no allocations, no decoding, no vector resizing!
    return mPcmRingBuffer.readFrames(out_samples, num_frames);
}

void JitterBuffer::decodeWorkerLoop() {
    int16_t decoded_buf[960 * 2]; // Scratch buffer for up to 20ms at 48kHz stereo

    while (mIsRunning.load(std::memory_order_relaxed)) {
        {
            std::unique_lock<std::mutex> lock(mConditionMutex);
            mCondition.wait_for(lock, std::chrono::milliseconds(3), [this] {
                if (!mIsRunning.load(std::memory_order_relaxed)) return true;
                std::lock_guard<std::mutex> pLock(mPacketMutex);
                return !mPacketMap.empty();
            });
        }

        if (!mIsRunning.load(std::memory_order_relaxed)) break;

        processPackets(decoded_buf);
    }
}

void JitterBuffer::processPackets(int16_t* decoded_buf) {
    auto now = std::chrono::steady_clock::now();
    auto sinceLastRxMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - mLastPacketRecvTime).count();
    if (sinceLastRxMs > 1500) {
        mCurrentRxPps.store(0, std::memory_order_relaxed);
    }
    if (!mHasStarted || sinceLastRxMs > 800) {
        mHasStarted = false;
        mIsPrebuffering = true;
        return;
    }

    adaptTargetBuffer();

    float ringOccupiedMs = mPcmRingBuffer.getOccupiedMs(mSampleRate);
    size_t packetQueueSize = 0;
    {
        std::lock_guard<std::mutex> lock(mPacketMutex);
        packetQueueSize = mPacketMap.size();
    }
    float totalQueuedMs = (packetQueueSize * mPacketDurationMs) + ringOccupiedMs;

    // Startup prebuffering: wait until queued audio reaches configured target buffer
    if (mIsPrebuffering) {
        float startThreshold = std::clamp(mTargetBufferMs, 10.0f, 200.0f);
        int minPackets = std::max(2, static_cast<int>(startThreshold / std::max(1.0f, mPacketDurationMs)));
        if (totalQueuedMs < startThreshold && static_cast<int>(packetQueueSize) < minPackets) {
            return;
        }
        mIsPrebuffering = false;
        mConsecutiveLosses = 0;
        std::lock_guard<std::mutex> lock(mPacketMutex);
        if (!mPacketMap.empty()) {
            mExpectedSeq = mPacketMap.begin()->first;
        }
    }

    // Decode packets and push PCM to the SPSC ring buffer while space permits
    while (mPcmRingBuffer.getOccupiedFrames() + 480 <= PcmRingBuffer::CAPACITY_FRAMES) {
        // Drift trimming: if total queued duration exceeds target + 15ms, discard oldest packet
        {
            std::lock_guard<std::mutex> lock(mPacketMutex);
            float currentQueuedMs = (mPacketMap.size() * mPacketDurationMs) + mPcmRingBuffer.getOccupiedMs(mSampleRate);
            if (currentQueuedMs > (mTargetBufferMs + 15.0f) && mPacketMap.size() > 4) {
                auto oldest = mPacketMap.begin();
                mExpectedSeq = oldest->first + 1;
                mPacketMap.erase(oldest);
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
                if (mConsecutiveLosses >= 8) {
                    mIsPrebuffering = true;
                }
                break;
            }

            // If sequence is far behind, catch up
            uint32_t earliestSeq = mPacketMap.begin()->first;
            int32_t gap = seqDiff(earliestSeq, mExpectedSeq);
            if (gap > 4 || (mConsecutiveLosses >= 2 && gap > 0)) {
                mExpectedSeq = earliestSeq;
            }

            auto it = mPacketMap.find(mExpectedSeq);
            if (it != mPacketMap.end()) {
                pkt = it->second;
                mPacketMap.erase(it);
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
            // Normal decode
            int total_frames = 0;
            const int pktChannels = (pkt.header.channels == 1) ? 1 : 2;

            if (pkt.header.codec == CODEC_PCM16) {
                int frame_count = pkt.payload.size() / (pktChannels * sizeof(int16_t));
                if (frame_count <= 0) frame_count = 240;
                mLastFrameSamplesPerCh = frame_count;
                mPacketDurationMs = (frame_count * 1000.0f) / mSampleRate;
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
            } else {
                int max_frames = 960;
                int decoded = mOpusDecoder.decode(pkt.payload.data(), pkt.payload.size(), decoded_buf, max_frames, false);
                if (decoded > 0) {
                    mLastFrameSamplesPerCh = decoded;
                    mPacketDurationMs = (decoded * 1000.0f) / mSampleRate;
                    total_frames = decoded;
                } else {
                    total_frames = mLastFrameSamplesPerCh;
                    std::memset(decoded_buf, 0, total_frames * 2 * sizeof(int16_t));
                }
            }

            // Stereo-coherent crossfade if recovering from packet loss (Checkpoint 5)
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
            }

            mConsecutiveLosses = 0;
            mExpectedSeq++;
        } else {
            // Gap detected: frame mExpectedSeq is missing
            mLostCount.fetch_add(1, std::memory_order_relaxed);

            auto underrunNow = std::chrono::steady_clock::now();
            if (mAutoMode.load()) {
                auto sinceLastBumpMs = std::chrono::duration_cast<std::chrono::milliseconds>(underrunNow - mLastUnderrunAdaptTime).count();
                if (sinceLastBumpMs >= 300) {
                    mTargetBufferMs = std::min(200.0f, mTargetBufferMs + 5.0f);
                    mLastUnderrunAdaptTime = underrunNow;
                }
            }

            int total_frames = 0;

            // Checkpoint 4: Opus FEC Sequencing
            // If next packet N+1 is available in mPacketMap, decode it with decode_fec=true to reconstruct frame N.
            // Packet N+1 remains in mPacketMap to be decoded normally with decode_fec=false on the next step!
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
            }

            mConsecutiveLosses++;
            mExpectedSeq++;

            if (mConsecutiveLosses >= 8) {
                mIsPrebuffering = true;
                break;
            }
        }
    }
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
    if (!mAutoMode.load()) return;
    auto now = std::chrono::steady_clock::now();
    auto noUnderrunSec = std::chrono::duration_cast<std::chrono::seconds>(now - mLastUnderrunAdaptTime).count();
    auto decayIntervalSec = std::chrono::duration_cast<std::chrono::seconds>(now - mLastDecayAdaptTime).count();

    if (noUnderrunSec >= 10 && decayIntervalSec >= 10) {
        mTargetBufferMs = std::max(15.0f, mTargetBufferMs - 2.5f);
        mLastDecayAdaptTime = now;
    }
}

void JitterBuffer::setTargetBufferMs(float target_ms) {
    mTargetBufferMs = std::clamp(target_ms, 10.0f, 200.0f);
}

float JitterBuffer::getTargetBufferMs() const {
    return mTargetBufferMs;
}

void JitterBuffer::setAutoMode(bool enabled) {
    mAutoMode.store(enabled);
}

bool JitterBuffer::isAutoMode() const {
    return mAutoMode.load();
}

JitterStats JitterBuffer::getStats() {
    JitterStats stats;
    size_t packetCount = 0;
    {
        std::lock_guard<std::mutex> lock(mPacketMutex);
        packetCount = mPacketMap.size();
    }
    float ringMs = mPcmRingBuffer.getOccupiedMs(mSampleRate);
    stats.buffer_ms = (packetCount * mPacketDurationMs) + ringMs;
    stats.target_ms = mTargetBufferMs;
    stats.underruns = mUnderrunCount.load(std::memory_order_relaxed) + mPcmRingBuffer.getUnderrunFrames() / 240;
    stats.lost_packets = mLostCount.load(std::memory_order_relaxed);
    stats.late_packets = mLateCount.load(std::memory_order_relaxed);
    stats.rx_pps = mCurrentRxPps.load(std::memory_order_relaxed);
    stats.active_codec = mCurrentCodec;
    stats.auto_mode = mAutoMode.load();
    stats.ring_occupied_frames = static_cast<uint32_t>(mPcmRingBuffer.getOccupiedFrames());
    stats.ring_overflow_drops = mPcmRingBuffer.getOverflowDrops();
    stats.concealed_packets = mConcealedPackets.load(std::memory_order_relaxed);
    stats.intentional_drift_drops = mIntentionalDriftDrops.load(std::memory_order_relaxed);
    return stats;
}

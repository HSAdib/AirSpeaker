#pragma once

#include "protocol.h"
#include "opus_wrapper.h"
#include "pcm_ring_buffer.h"
#include <mutex>
#include <condition_variable>
#include <map>
#include <vector>
#include <atomic>
#include <cstring>
#include <chrono>
#include <thread>

struct JitterStats {
    float buffer_ms{0.0f};
    float target_ms{30.0f};
    uint32_t underruns{0};
    uint32_t lost_packets{0};
    uint32_t late_packets{0};
    uint32_t rx_pps{0};
    uint8_t active_codec{0};
    bool auto_mode{true};
    uint32_t ring_occupied_frames{0};
    uint32_t ring_overflow_drops{0};
    uint32_t concealed_packets{0};
    uint32_t intentional_drift_drops{0};
    uint32_t max_callback_micros{0};
    uint32_t avg_callback_micros{0};
    uint32_t deadline_misses{0};
};

class JitterBuffer {
public:
    JitterBuffer();
    ~JitterBuffer();

    void start();
    void stop();
    void reset();

    void pushPacket(const AudioPacket& packet);

    // Guaranteed real-time safe for Oboe onAudioReady():
    // ZERO mutexes, ZERO allocations, ZERO opus_decode, ZERO vector erases.
    int readAudio(int16_t* out_samples, int num_frames);

    void setTargetBufferMs(float target_ms);
    float getTargetBufferMs() const;
    void setAutoMode(bool enabled);
    bool isAutoMode() const;

    JitterStats getStats();

    PcmRingBuffer& getRingBuffer() { return mPcmRingBuffer; }

    // Instrumentation counters
    uint32_t getConcealedPackets() const { return mConcealedPackets.load(std::memory_order_relaxed); }
    uint32_t getIntentionalDriftDrops() const { return mIntentionalDriftDrops.load(std::memory_order_relaxed); }
    uint32_t getDuplicatePackets() const { return mDuplicatePackets.load(std::memory_order_relaxed); }

private:
    void decodeWorkerLoop();
    void processPackets(int16_t* decoded_buf);
    void adaptTargetBuffer();
    void concealPcmGap(int frames, int16_t* out_buf);

    PcmRingBuffer mPcmRingBuffer;

    mutable std::mutex mPacketMutex;
    std::map<uint32_t, AudioPacket> mPacketMap;

    std::atomic<bool> mIsRunning{false};
    std::thread mDecodeThread;
    std::mutex mConditionMutex;
    std::condition_variable mCondition;

    bool mHasStarted{false};
    bool mIsPrebuffering{true};
    uint32_t mExpectedSeq{0};
    float mTargetBufferMs{30.0f};
    std::atomic<bool> mAutoMode{true};

    OpusDecoderWrapper mOpusDecoder;
    uint8_t mCurrentCodec{0};
    int mChannels{2};
    int mSampleRate{48000};

    int mLastFrameSamplesPerCh{240};
    float mPacketDurationMs{5.0f};
    std::vector<int16_t> mLastValidFrame;
    int mConsecutiveLosses{0};

    std::atomic<uint32_t> mUnderrunCount{0};
    std::atomic<uint32_t> mLostCount{0};
    std::atomic<uint32_t> mLateCount{0};
    std::atomic<uint32_t> mRxPacketsInterval{0};
    std::atomic<uint32_t> mCurrentRxPps{0};
    std::atomic<uint32_t> mConcealedPackets{0};
    std::atomic<uint32_t> mIntentionalDriftDrops{0};
    std::atomic<uint32_t> mDuplicatePackets{0};

    std::chrono::steady_clock::time_point mLastPacketRecvTime;
    std::chrono::steady_clock::time_point mLastPpsTime;
    std::chrono::steady_clock::time_point mLastUnderrunAdaptTime;
    std::chrono::steady_clock::time_point mLastDecayAdaptTime;
};

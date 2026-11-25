#pragma once

#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO  4
#define ANDROID_LOG_WARN  5
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, fmt, ...) ((void)0)
#endif

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

/**
 * Snapshot structure for JitterBuffer telemetry metrics.
 * Safely populated via atomics without acquiring real-time locks.
 */
struct JitterStatsSnapshot {
    float bufferMs{0.0f};
    float targetMs{30.0f};
    uint64_t underruns{0};
    uint64_t lostPackets{0};
    uint64_t latePackets{0};
    uint64_t duplicatePackets{0};
    uint64_t concealedPackets{0};
    uint64_t intentionalDriftDrops{0};
    uint64_t packetQueueOverflowDrops{0};
    uint64_t pcmRingOverflowDrops{0};
    uint64_t pcmRingUnderrunFrames{0};
    uint64_t pcmRingAvailableFrames{0};
    uint64_t rxPacketsPerSecond{0};
    uint8_t activeCodec{0};
    bool autoMode{true};
    uint64_t rxPackets{0};
};

// Legacy alias for compatibility with existing JNI / UI references
using JitterStats = JitterStatsSnapshot;

class JitterBuffer {
public:
    static constexpr size_t MAX_PACKET_QUEUE_SIZE = 256; // Hard upper bound: ~1.28s at 5ms/packet

    JitterBuffer();
    ~JitterBuffer();

    void start();
    void stop();
    void reset();

    // Receiver thread only: called when a new UDP packet is received
    void pushPacket(const AudioPacket& packet);
    void pushPacket(const uint8_t* wireData, size_t size);

#ifdef AIRSPEAKER_UNIT_TEST
    // Runs one decode/trim cycle synchronously without starting a worker thread
    void processPacketsForTest() {
        int16_t decoded_buf[960 * 2];
        (void)processPackets(decoded_buf);
    }
    size_t getPacketMapSizeForTest() {
        std::lock_guard<std::mutex> lock(mPacketMutex);
        return mPacketMap.size();
    }
    size_t getRingOccupiedFramesForTest() const {
        return mPcmRingBuffer.getOccupiedFrames();
    }
#endif

    // Audio callback thread only (e.g. Oboe onAudioReady):
    // Real-time safe: ZERO mutexes, ZERO allocations, ZERO opus decodes, ZERO vector erases.
    int readAudio(int16_t* out_samples, int num_frames);

    // Cross-thread configuration
    void setTargetBufferMs(float target_ms);
    float getTargetBufferMs() const;
    void setAutoMode(bool enabled);
    bool isAutoMode() const;

    // Bluetooth / dynamic audio route adaptation
    void setBluetoothMode(bool enabled);
    bool isBluetoothMode() const;
    void onRouteChanged(bool isBluetooth, int32_t burstFrames = 240);
    // Real-time safe (called from Oboe onAudioReady): records largest callback burst seen
    void observeCallbackFrames(int32_t numFrames);

    // Telemetry snapshots (lock-free)
    JitterStatsSnapshot getStatsSnapshot() const;
    JitterStats getStats() const { return getStatsSnapshot(); }

    PcmRingBuffer& getRingBuffer() { return mPcmRingBuffer; }
    const PcmRingBuffer& getRingBuffer() const { return mPcmRingBuffer; }

    // Atomic telemetry accessors
    uint64_t getConcealedPackets() const { return mConcealedPackets.load(std::memory_order_relaxed); }
    uint64_t getIntentionalDriftDrops() const { return mIntentionalDriftDrops.load(std::memory_order_relaxed); }
    uint64_t getDuplicatePackets() const { return mDuplicatePackets.load(std::memory_order_relaxed); }
    uint64_t getPacketQueueOverflowDrops() const { return mPacketQueueOverflowDrops.load(std::memory_order_relaxed); }

private:
    void decodeWorkerLoop();
    bool processPackets(int16_t* decoded_buf);
    void adaptTargetBuffer();
    void concealPcmGap(int frames, int16_t* out_buf);

    // ---------------------------------------------------------
    // Lock-Free SPSC PCM Ring Buffer (Shared: Worker Producer -> Audio Consumer)
    // ---------------------------------------------------------
    PcmRingBuffer mPcmRingBuffer;

    // ---------------------------------------------------------
    // Thread Synchronization
    // ---------------------------------------------------------
    alignas(64) std::atomic<bool> mIsRunning{false};
    alignas(64) std::atomic<bool> mStreamResetPending{false};
    std::thread mDecodeThread;
    std::mutex mConditionMutex;
    std::condition_variable mCondition;

    // ---------------------------------------------------------
    // Receiver Thread & Packet Map State (Guarded by mPacketMutex)
    // ---------------------------------------------------------
    mutable std::mutex mPacketMutex;
    std::map<uint64_t, AudioPacket> mPacketMap; // Bounded to MAX_PACKET_QUEUE_SIZE
    bool mFirstPacket{true};
    uint64_t mHighestSeq64{0};
    std::atomic<size_t> mQueuedPacketCount{0};

    // ---------------------------------------------------------
    // Receiver Thread Atomic Telemetry
    // ---------------------------------------------------------
    alignas(64) std::atomic<int64_t> mLastPacketRecvTimeMs{0};
    alignas(64) std::atomic<uint64_t> mTotalRxPackets{0};
    alignas(64) std::atomic<uint64_t> mRxPacketsInterval{0};
    alignas(64) std::atomic<uint64_t> mCurrentRxPps{0};
    alignas(64) std::atomic<int64_t> mLastPpsCalcTimeMs{0};
    alignas(64) std::atomic<uint64_t> mLateCount{0};
    alignas(64) std::atomic<uint64_t> mDuplicatePackets{0};
    alignas(64) std::atomic<uint64_t> mPacketQueueOverflowDrops{0};

    // ---------------------------------------------------------
    // Cross-Thread Atomic Configuration
    // ---------------------------------------------------------
    alignas(64) std::atomic<float> mConfiguredTargetBufferMs{30.0f};
    alignas(64) std::atomic<bool> mAutoMode{true};
    float mLastAppliedConfiguredTargetMs{-1.0f};
    std::atomic<float> mReportedTargetBufferMs{30.0f};
    alignas(64) std::atomic<bool> mBluetoothMode{false};
    alignas(64) std::atomic<size_t> mObservedCallbackFrames{240};

    // ---------------------------------------------------------
    // Worker Thread Published Atomics (Read by Telemetry / Receiver)
    // ---------------------------------------------------------
    alignas(64) std::atomic<uint32_t> mWorkerExpectedSeq{0};
    alignas(64) std::atomic<float> mEffectiveTargetBufferMs{30.0f};
    alignas(64) std::atomic<uint8_t> mActiveCodec{0};
    alignas(64) std::atomic<uint64_t> mUnderrunCount{0};
    alignas(64) std::atomic<uint64_t> mLostCount{0};
    alignas(64) std::atomic<uint64_t> mConcealedPackets{0};
    alignas(64) std::atomic<uint64_t> mIntentionalDriftDrops{0};
    alignas(64) std::atomic<float> mPacketDurationMs{5.0f};
    alignas(64) std::atomic<int32_t> mSampleRate{48000};

    // ---------------------------------------------------------
    // Decode Worker Private State (Strictly owned by Decode Worker Thread)
    // ---------------------------------------------------------
    bool mHasStarted{false};
    bool mIsPrebuffering{true};
    bool mHasPlayedAudio{false};
    uint64_t mExpectedSeq{0};
    float mLocalTargetBufferMs{30.0f};
    uint8_t mCurrentCodec{0};
    int mChannels{2};
    int mLastFrameSamplesPerCh{240};
    int mConsecutiveLosses{0};
    std::vector<int16_t> mLastValidFrame;
    OpusDecoderWrapper mOpusDecoder;

    std::chrono::steady_clock::time_point mLastUnderrunAdaptTime;
    std::chrono::steady_clock::time_point mLastDecayAdaptTime;
};

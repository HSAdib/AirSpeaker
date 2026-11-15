#pragma once

#include "jitter_buffer.h"
#include <oboe/Oboe.h>
#include <thread>
#include <atomic>
#include <memory>
#include <vector>
#include <string>
#include <mutex>
#include <netinet/in.h>

struct AdvancedEngineStats {
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
    double callbackAverageMicros{0.0};
    int64_t callbackMaxMicros{0};
    uint64_t callbackDeadlineMisses{0};
    double rxPps{0.0};
    uint8_t activeCodec{0};
    bool autoMode{true};
};

class NativeAudioEngine : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    NativeAudioEngine();
    ~NativeAudioEngine();

    // Section 5: Transactional start with reverse rollback on failure
    bool start(int audio_port = 5004);

    // Section 6: Idempotent and race-safe stop
    void stop();

    void setTargetBufferMs(float target_ms);
    float getTargetBufferMs();
    void setAutoMode(bool enabled);
    bool isAutoMode();
    void setMuted(bool muted);
    bool isMuted() const;
    void setVolumeGain(float gain);
    float getVolumeGain() const;
    void setLowLatencyMode(bool lowLatency);
    bool isLowLatencyMode() const;
    void setPartyRelayEnabled(bool enabled);
    bool isPartyRelayEnabled() const;
    void setPartyPeers(const std::vector<std::string>& peers);
    int32_t getAudioSessionId() const;

    JitterStats getStats();
    AdvancedEngineStats getAdvancedStats();
    std::string getSenderIp();

    // Section 10: Real-time safe Oboe AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;

    // Oboe AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result result) override;

private:
    void cleanupAfterFailedStart() noexcept;
    bool openOboeStream();
    bool startOboeStream();
    void stopAndCloseOboeStream();
    void scheduleStreamReopen();
    void socketReceiverLoop();

    int mAudioPort{5004};
    std::mutex mStartStopMutex;
    std::atomic<bool> mIsRunning{false};
    std::atomic<bool> mMuted{false};
    std::atomic<float> mVolumeGain{1.0f};
    std::atomic<bool> mLowLatencyMode{true};
    std::atomic<bool> mPartyRelayEnabled{false};
    std::mutex mPartyPeersMutex;
    std::vector<struct sockaddr_in> mPartyPeerAddrs;
    std::atomic<int> mSocketFd{-1};
    std::mutex mSenderIpMutex;
    std::string mLastSenderIp;

    std::mutex mStreamMutex;
    std::mutex mReopenMutex;
    std::atomic<bool> mReopenPending{false};
    std::thread mReceiverThread;
    std::thread mReopenThread;
    std::shared_ptr<oboe::AudioStream> mAudioStream;

    // Section 10: Lock-free callback instrumentation metrics
    alignas(64) std::atomic<uint64_t> mTotalCallbackCount{0};
    alignas(64) std::atomic<uint64_t> mTotalCallbackMicros{0};
    alignas(64) std::atomic<int64_t> mMaxCallbackMicros{0};
    alignas(64) std::atomic<uint64_t> mDeadlineMisses{0};

    JitterBuffer mJitterBuffer;
};

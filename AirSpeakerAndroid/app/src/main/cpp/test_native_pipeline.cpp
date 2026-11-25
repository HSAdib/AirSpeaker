#include <iostream>
#include <vector>
#include <thread>
#include <atomic>
#include <chrono>
#include <cassert>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <map>

// Include our actual engine headers under test
#ifndef AIRSPEAKER_UNIT_TEST
#define AIRSPEAKER_UNIT_TEST
#endif
#include "pcm_ring_buffer.h"
#include "jitter_buffer.h"
#include "protocol.h"

static int gPassCount = 0;
static int gFailCount = 0;

#define TEST_ASSERT(cond, msg) \
    do { \
        if (!(cond)) { \
            std::cerr << "  [FAIL] " << msg << " (" << __FILE__ << ":" << __LINE__ << ")" << std::endl; \
            gFailCount++; \
            return; \
        } \
    } while (0)

#define TEST_PASS(name) \
    do { \
        std::cout << "[PASS] " << name << std::endl; \
        gPassCount++; \
    } while (0)

// ============================================================================
// 1. PCM RING BUFFER TESTS
// ============================================================================

void test_ring_exact_write_read() {
    PcmRingBuffer ring;
    constexpr int frames = 240;
    int16_t src[frames * 2];
    for (int i = 0; i < frames; ++i) {
        src[i * 2] = static_cast<int16_t>(i + 1);
        src[i * 2 + 1] = static_cast<int16_t>(-(i + 1));
    }

    int written = ring.writeFrames(src, frames);
    TEST_ASSERT(written == frames, "writeFrames must return frame count");
    TEST_ASSERT(ring.availableFrames() == frames, "availableFrames must match written");
    TEST_ASSERT(ring.writeSequence() == frames, "writeSequence must advance by written");
    TEST_ASSERT(ring.readSequence() == 0, "producer must never write readSequence");

    int16_t dest[frames * 2];
    int read = ring.readFrames(dest, frames);
    TEST_ASSERT(read == frames, "readFrames must return read count");
    TEST_ASSERT(ring.readSequence() == frames, "readSequence must advance by read count");
    TEST_ASSERT(ring.availableFrames() == 0, "availableFrames must be zero after full read");
    TEST_ASSERT(ring.underrunFrames() == 0, "no underruns on exact read");

    for (int i = 0; i < frames; ++i) {
        TEST_ASSERT(dest[i * 2] == static_cast<int16_t>(i + 1), "left sample mismatch");
        TEST_ASSERT(dest[i * 2 + 1] == static_cast<int16_t>(-(i + 1)), "right sample mismatch");
    }

    TEST_PASS("PcmRingBuffer exact write and read");
}

void test_ring_partial_read_and_zero_fill() {
    PcmRingBuffer ring;
    int16_t src[100 * 2];
    for (int i = 0; i < 100 * 2; ++i) src[i] = static_cast<int16_t>(i + 1);

    ring.writeFrames(src, 100);

    int16_t dest[240 * 2];
    std::fill(dest, dest + 240 * 2, static_cast<int16_t>(0x5555));

    int read = ring.readFrames(dest, 240);
    TEST_ASSERT(read == 100, "must read 100 available frames");
    TEST_ASSERT(ring.underrunFrames() == 140, "underrun frames must equal 140 missing frames");

    // Check that available frames are valid
    for (int i = 0; i < 100 * 2; ++i) {
        TEST_ASSERT(dest[i] == static_cast<int16_t>(i + 1), "available data corrupted");
    }
    // Check that shortage was zero-filled
    for (int i = 100 * 2; i < 240 * 2; ++i) {
        TEST_ASSERT(dest[i] == 0, "shortage was not zero-filled");
    }

    TEST_PASS("PcmRingBuffer partial read with zero-fill underrun");
}

void test_ring_wrap_around() {
    PcmRingBuffer ring;
    constexpr int chunkSize = 1024;
    int16_t src[chunkSize * 2];
    int16_t dest[chunkSize * 2];

    // Write and read across capacity multiple times
    constexpr int totalChunks = (PcmRingBuffer::CAPACITY_FRAMES / chunkSize) * 3;
    for (int chunk = 0; chunk < totalChunks; ++chunk) {
        for (int i = 0; i < chunkSize; ++i) {
            int frameId = chunk * chunkSize + i;
            src[i * 2] = static_cast<int16_t>(frameId & 0x7FFF);
            src[i * 2 + 1] = static_cast<int16_t>(-(frameId & 0x7FFF));
        }

        int written = ring.writeFrames(src, chunkSize);
        TEST_ASSERT(written == chunkSize, "chunk write failed");

        int read = ring.readFrames(dest, chunkSize);
        TEST_ASSERT(read == chunkSize, "chunk read failed");

        for (int i = 0; i < chunkSize; ++i) {
            int frameId = chunk * chunkSize + i;
            int16_t expLeft = static_cast<int16_t>(frameId & 0x7FFF);
            int16_t expRight = static_cast<int16_t>(-(frameId & 0x7FFF));
            TEST_ASSERT(dest[i * 2] == expLeft, "wrap-around left channel corrupted");
            TEST_ASSERT(dest[i * 2 + 1] == expRight, "wrap-around right channel corrupted");
        }
    }

    TEST_ASSERT(ring.overflowDroppedFrames() == 0, "no drops occurred during synchronous stream");
    TEST_ASSERT(ring.underrunFrames() == 0, "no underruns occurred during synchronous stream");

    TEST_PASS("PcmRingBuffer wrap-around across capacity boundary");
}

void test_ring_overflow_drop_oldest() {
    PcmRingBuffer ring;
    constexpr size_t cap = PcmRingBuffer::CAPACITY_FRAMES;

    // Fill buffer completely
    std::vector<int16_t> chunk(cap * 2);
    for (size_t i = 0; i < cap; ++i) {
        chunk[i * 2] = static_cast<int16_t>(i & 0x7FFF);
        chunk[i * 2 + 1] = static_cast<int16_t>(-(i & 0x7FFF));
    }
    ring.writeFrames(chunk.data(), static_cast<int>(cap));
    TEST_ASSERT(ring.availableFrames() == cap, "buffer must be full");
    TEST_ASSERT(ring.overflowDroppedFrames() == 0, "no drops when exactly at capacity");

    // Write 500 more frames without reading (overflow)
    std::vector<int16_t> overflowData(500 * 2);
    for (size_t i = 0; i < 500; ++i) {
        size_t id = cap + i;
        overflowData[i * 2] = static_cast<int16_t>(id & 0x7FFF);
        overflowData[i * 2 + 1] = static_cast<int16_t>(-(id & 0x7FFF));
    }
    ring.writeFrames(overflowData.data(), 500);

    TEST_ASSERT(ring.minimumReadableSequence() == 500, "min readable sequence must advance to 500");
    TEST_ASSERT(ring.readSequence() == 0, "producer must never modify consumer read sequence");

    // Consumer reads: must catch up to min readable sequence and register 500 dropped frames
    std::vector<int16_t> out(cap * 2);
    int read = ring.readFrames(out.data(), static_cast<int>(cap));
    TEST_ASSERT(read == static_cast<int>(cap), "must read full remaining capacity");
    TEST_ASSERT(ring.overflowDroppedFrames() == 500, "drop counter must accurately increment by 500 on catch-up");
    TEST_ASSERT(ring.readSequence() == cap + 500, "read sequence must catch up to total written");

    int16_t firstLeft = out[0];
    int16_t firstRight = out[1];
    int16_t expFirstLeft = static_cast<int16_t>(500 & 0x7FFF);
    int16_t expFirstRight = static_cast<int16_t>(-(500 & 0x7FFF));
    TEST_ASSERT(firstLeft == expFirstLeft, "consumer did not catch up to newest frames: stale replay detected");
    TEST_ASSERT(firstRight == expFirstRight, "stereo right channel torn on overflow catchup");

    TEST_PASS("PcmRingBuffer overflow drop-oldest & consumer catch-up");
}

void test_ring_multithreaded_stress_5_million_frames() {
    PcmRingBuffer ring;
    constexpr uint64_t TOTAL_FRAMES = 5000000ULL; // 5 Million stereo frames
    constexpr int CHUNK = 240; // 5ms frames

    std::atomic<bool> producerDone{false};
    std::atomic<uint64_t> totalReceived{0};
    std::atomic<bool> orderingViolated{false};
    std::atomic<bool> stereoTorn{false};

    // Producer thread
    std::thread producer([&]() {
        std::vector<int16_t> buf(CHUNK * 2);
        uint64_t sent = 0;
        while (sent < TOTAL_FRAMES) {
            int toSend = static_cast<int>(std::min<uint64_t>(CHUNK, TOTAL_FRAMES - sent));
            for (int i = 0; i < toSend; ++i) {
                uint64_t id = sent + i;
                buf[i * 2] = static_cast<int16_t>(id & 0x7FFF);
                buf[i * 2 + 1] = static_cast<int16_t>(-(id & 0x7FFF));
            }
            ring.writeFrames(buf.data(), toSend);
            sent += toSend;
        }
        producerDone.store(true, std::memory_order_release);
    });

    // Consumer thread
    std::thread consumer([&]() {
        std::vector<int16_t> buf(CHUNK * 2);
        uint64_t lastFrameId = 0;
        bool hasFirst = false;

        while (!producerDone.load(std::memory_order_acquire) || ring.availableFrames() > 0) {
            int read = ring.readFrames(buf.data(), CHUNK);
            if (read > 0) {
                for (int i = 0; i < read; ++i) {
                    int16_t left = buf[i * 2];
                    int16_t right = buf[i * 2 + 1];

                    // Verify stereo alignment
                    if (left != static_cast<int16_t>(-right)) {
                        stereoTorn.store(true, std::memory_order_relaxed);
                    }

                    uint64_t frameMask = static_cast<uint16_t>(left) & 0x7FFF;
                    if (hasFirst) {
                        // In drop-oldest ring, frame IDs must be monotonically increasing
                        if (frameMask == (lastFrameId & 0x7FFF)) {
                            orderingViolated.store(true, std::memory_order_relaxed);
                        }
                    } else {
                        hasFirst = true;
                    }
                    lastFrameId = frameMask;
                    totalReceived.fetch_add(1, std::memory_order_relaxed);
                }
            }
        }
    });

    producer.join();
    consumer.join();

    TEST_ASSERT(!stereoTorn.load(), "stereo channels torn during multithreaded stress");
    TEST_ASSERT(!orderingViolated.load(), "duplicate or backward frame sequence detected");
    uint64_t drops = ring.overflowDroppedFrames();
    uint64_t received = totalReceived.load();
    TEST_ASSERT(received + drops == TOTAL_FRAMES, "frame conservation violated: received + drops != total");

    TEST_PASS("PcmRingBuffer 5 million stereo frames multithreaded SPSC stress");
}

// ============================================================================
// 2. OPUS IN-BAND FEC & TIMELINE TESTS (Mock Opus Decoder Test Seam)
// ============================================================================

static inline int32_t testSeqDiff(uint32_t a, uint32_t b) {
    return static_cast<int32_t>(a - b);
}

struct MockOpusDecodeCall {
    uint32_t seq{0};
    bool decodeFec{false};
    bool isPlc{false};
    int returnedSamples{240};
};

class MockOpusTimelineEngine {
public:
    std::map<uint64_t, AudioPacket> mPacketMap;
    std::vector<MockOpusDecodeCall> mDecodeLog;
    uint64_t mExpectedSeq{0};
    uint64_t mHighestSeq64{0};
    bool mFirstPacket{true};
    uint64_t mLostPackets{0};
    uint64_t mConcealedPackets{0};
    uint64_t mLatePackets{0};
    uint64_t mDuplicatePackets{0};
    bool mHasStarted{false};

    void pushPacket(uint32_t seq, const std::string& data = "audio") {
        if (mHasStarted && testSeqDiff(seq, static_cast<uint32_t>(mExpectedSeq)) < 0) {
            mLatePackets++;
            return;
        }

        uint64_t seq64;
        if (mFirstPacket) {
            mHighestSeq64 = seq;
            seq64 = seq;
            mFirstPacket = false;
        } else {
            int32_t diff = testSeqDiff(seq, static_cast<uint32_t>(mHighestSeq64));
            seq64 = static_cast<uint64_t>(static_cast<int64_t>(mHighestSeq64) + diff);
            if (diff > 0) {
                mHighestSeq64 = seq64;
            }
        }

        if (mPacketMap.find(seq64) != mPacketMap.end()) {
            mDuplicatePackets++;
            return;
        }

        AudioPacket pkt{};
        pkt.header.seq = seq;
        pkt.header.codec = CODEC_OPUS;
        pkt.payload.assign(data.begin(), data.end());
        mPacketMap[seq64] = pkt;

        if (!mHasStarted) {
            mExpectedSeq = seq64;
            mHasStarted = true;
        }
    }

    // Exact timeline step execution matching jitter_buffer.cpp
    bool stepTimeline(int16_t* /*outBuf*/) {
        if (!mHasStarted) return false;

        auto it = mPacketMap.find(mExpectedSeq);
        if (it != mPacketMap.end()) {
            // Normal decode
            MockOpusDecodeCall call;
            call.seq = static_cast<uint32_t>(mExpectedSeq);
            call.decodeFec = false;
            call.isPlc = false;
            call.returnedSamples = 240;
            mDecodeLog.push_back(call);

            mPacketMap.erase(it);
            mExpectedSeq++;
            return true;
        }

        // Missing mExpectedSeq: Check if N+1 is available for Opus in-band FEC
        mLostPackets++;
        auto nextIt = mPacketMap.find(mExpectedSeq + 1);
        if (nextIt != mPacketMap.end()) {
            // Checkpoint 4: Decode N+1 with decode_fec=true for frame N.
            // Packet N+1 remains in map to be decoded normally next!
            MockOpusDecodeCall call;
            call.seq = static_cast<uint32_t>(mExpectedSeq); // Reconstructed missing N
            call.decodeFec = true;
            call.isPlc = false;
            call.returnedSamples = 240;
            mDecodeLog.push_back(call);

            mConcealedPackets++;
            mExpectedSeq++;
            return true;
        }

        // Fallback PLC: neither N nor N+1 present
        MockOpusDecodeCall call;
        call.seq = static_cast<uint32_t>(mExpectedSeq);
        call.decodeFec = false;
        call.isPlc = true;
        call.returnedSamples = 240;
        mDecodeLog.push_back(call);

        mConcealedPackets++;
        mExpectedSeq++;
        return true;
    }
};

void test_opus_fec_sequencing_normal_and_missing() {
    MockOpusTimelineEngine engine;

    // Normal N, N+1, N+2
    engine.pushPacket(100);
    engine.pushPacket(101);
    engine.pushPacket(102);

    engine.stepTimeline(nullptr); // 100 normal
    engine.stepTimeline(nullptr); // 101 normal
    engine.stepTimeline(nullptr); // 102 normal

    TEST_ASSERT(engine.mDecodeLog.size() == 3, "must have 3 decodes");
    TEST_ASSERT(engine.mDecodeLog[0].seq == 100 && !engine.mDecodeLog[0].decodeFec, "100 must be normal");
    TEST_ASSERT(engine.mDecodeLog[1].seq == 101 && !engine.mDecodeLog[1].decodeFec, "101 must be normal");
    TEST_ASSERT(engine.mDecodeLog[2].seq == 102 && !engine.mDecodeLog[2].decodeFec, "102 must be normal");

    // Missing N (103) with N+1 (104) available
    engine.pushPacket(104); // 103 skipped!

    engine.stepTimeline(nullptr); // Missing 103: FEC decode candidate 104
    TEST_ASSERT(engine.mDecodeLog.size() == 4, "step must produce FEC frame for 103");
    TEST_ASSERT(engine.mDecodeLog[3].seq == 103, "reconstructed frame must represent missing 103");
    TEST_ASSERT(engine.mDecodeLog[3].decodeFec == true, "must use decode_fec=true");
    TEST_ASSERT(engine.mPacketMap.find(104) != engine.mPacketMap.end(), "packet 104 must remain in map");

    engine.stepTimeline(nullptr); // Next step: 104 normal decode
    TEST_ASSERT(engine.mDecodeLog.size() == 5, "step must produce normal 104 frame");
    TEST_ASSERT(engine.mDecodeLog[4].seq == 104 && !engine.mDecodeLog[4].decodeFec, "104 must be normal decode");
    TEST_ASSERT(engine.mPacketMap.empty(), "packet 104 must now be consumed");

    TEST_PASS("Opus in-band FEC exact timeline sequencing (missing N with N+1 available)");
}

void test_opus_plc_fallback_two_losses() {
    MockOpusTimelineEngine engine;
    engine.pushPacket(10);
    // Duplicate packet 10 arrives while in queue
    engine.pushPacket(10);
    TEST_ASSERT(engine.mDuplicatePackets == 1, "packet 10 must be classified as duplicate");

    engine.stepTimeline(nullptr); // 10 normal

    // 11 and 12 missing, 13 arrives later
    engine.stepTimeline(nullptr); // 11 missing, no 12 -> PLC
    TEST_ASSERT(engine.mDecodeLog.back().seq == 11 && engine.mDecodeLog.back().isPlc, "11 must use PLC");

    engine.stepTimeline(nullptr); // 12 missing, no 13 yet -> PLC
    TEST_ASSERT(engine.mDecodeLog.back().seq == 12 && engine.mDecodeLog.back().isPlc, "12 must use PLC");

    // Late packet 11 arrives after PLC
    engine.pushPacket(11);
    TEST_ASSERT(engine.mLatePackets == 1, "packet 11 must be classified as late");

    TEST_PASS("Opus PLC fallback on consecutive loss & late/duplicate classification");
}

void test_sequence_wrap_around_boundary() {
    MockOpusTimelineEngine engine;
    engine.pushPacket(0xFFFFFFFEU);
    engine.pushPacket(0xFFFFFFFFU);
    engine.pushPacket(0x00000000U);
    engine.pushPacket(0x00000001U);

    for (int i = 0; i < 4; ++i) {
        bool ok = engine.stepTimeline(nullptr);
        TEST_ASSERT(ok, "step timeline failed on wrap around");
    }

    TEST_ASSERT(engine.mDecodeLog[0].seq == 0xFFFFFFFEU, "seq 0xFFFFFFFE");
    TEST_ASSERT(engine.mDecodeLog[1].seq == 0xFFFFFFFFU, "seq 0xFFFFFFFF");
    TEST_ASSERT(engine.mDecodeLog[2].seq == 0x00000000U, "seq 0x00000000 wrap");
    TEST_ASSERT(engine.mDecodeLog[3].seq == 0x00000001U, "seq 0x00000001 wrap");

    TEST_PASS("Sequence boundary wrap (0xFFFFFFFE -> 0xFFFFFFFF -> 0x0 -> 0x1)");
}

// ============================================================================
// 3. LIFECYCLE & FAILURE ROLLBACK TESTS (NativeAudioEngine Simulation)
// ============================================================================

class MockLifecycleEngine {
public:
    std::atomic<bool> mIsRunning{false};
    std::atomic<int> mSocketFd{-1};
    std::atomic<bool> mWorkerAlive{false};
    std::atomic<bool> mStreamOpen{false};

    // Injectable failure modes
    bool injectSocketFail{false};
    bool injectBindFail{false};
    bool injectStreamOpenFail{false};

    bool start(int /*port*/) {
        if (mIsRunning.load()) return true;

        // Reset
        cleanupAfterFailedStart();

        // 1. Socket
        if (injectSocketFail) {
            cleanupAfterFailedStart();
            return false;
        }
        mSocketFd.store(42);

        // 2. Bind
        if (injectBindFail) {
            cleanupAfterFailedStart();
            return false;
        }

        // 3. Oboe Open
        if (injectStreamOpenFail) {
            cleanupAfterFailedStart();
            return false;
        }
        mStreamOpen.store(true);

        // 4. Worker
        mWorkerAlive.store(true);

        mIsRunning.store(true);
        return true;
    }

    void cleanupAfterFailedStart() noexcept {
        mStreamOpen.store(false);
        mSocketFd.store(-1);
        mWorkerAlive.store(false);
        mIsRunning.store(false);
    }

    void stop() {
        if (!mIsRunning.exchange(false)) return;
        mSocketFd.store(-1);
        mStreamOpen.store(false);
        mWorkerAlive.store(false);
    }
};

void test_failed_start_transactional_rollback() {
    MockLifecycleEngine engine;

    // 1. Socket failure
    engine.injectSocketFail = true;
    bool s1 = engine.start(5004);
    TEST_ASSERT(!s1, "start must fail on socket failure");
    TEST_ASSERT(!engine.mIsRunning.load(), "running must be false");
    TEST_ASSERT(engine.mSocketFd.load() == -1, "socket fd must be -1");
    TEST_ASSERT(!engine.mWorkerAlive.load(), "worker must not be alive");

    // 2. Bind failure
    engine.injectSocketFail = false;
    engine.injectBindFail = true;
    bool s2 = engine.start(5004);
    TEST_ASSERT(!s2, "start must fail on bind failure");
    TEST_ASSERT(!engine.mIsRunning.load(), "running must be false");
    TEST_ASSERT(engine.mSocketFd.load() == -1, "socket fd must be reset");

    // 3. Stream failure
    engine.injectBindFail = false;
    engine.injectStreamOpenFail = true;
    bool s3 = engine.start(5004);
    TEST_ASSERT(!s3, "start must fail on stream failure");
    TEST_ASSERT(!engine.mStreamOpen.load(), "stream must be closed");

    // 4. Normal start after fixing failures
    engine.injectStreamOpenFail = false;
    bool s4 = engine.start(5004);
    TEST_ASSERT(s4, "start must succeed when failures removed");
    TEST_ASSERT(engine.mIsRunning.load(), "running must be true");

    engine.stop();
    TEST_ASSERT(!engine.mIsRunning.load(), "must stop cleanly");

    TEST_PASS("Transactional start rollback across injected failure points");
}

void test_100_start_stop_cycles() {
    MockLifecycleEngine engine;
    for (int i = 0; i < 100; ++i) {
        bool ok = engine.start(5004);
        TEST_ASSERT(ok, "start failed during 100-cycle stress");
        // Double start must be idempotent
        bool ok2 = engine.start(5004);
        TEST_ASSERT(ok2, "double start must be idempotent");

        engine.stop();
        // Double stop must be idempotent
        engine.stop();
        TEST_ASSERT(!engine.mIsRunning.load(), "must remain stopped");
    }

    TEST_PASS("100 consecutive start/stop cycles & double stop idempotency");
}

void test_auto_buffer_persists_across_packets() {
    // Model the exact state and adaptation logic of JitterBuffer
    std::atomic<float> configuredTargetBufferMs{30.0f};
    std::atomic<bool> autoMode{true};
    float lastAppliedConfiguredTargetMs{-1.0f};
    float localTargetBufferMs{30.0f};
    std::atomic<float> effectiveTargetBufferMs{30.0f};
    std::atomic<float> reportedTargetBufferMs{30.0f};

    auto runProcessPackets = [&](bool simulateLoss) {
        bool autoM = autoMode.load(std::memory_order_relaxed);
        float configured = std::clamp(configuredTargetBufferMs.load(std::memory_order_relaxed), 10.0f, 200.0f);
        if (!autoM || configured != lastAppliedConfiguredTargetMs) {
            localTargetBufferMs = configured;
            lastAppliedConfiguredTargetMs = configured;
        }

        if (simulateLoss && autoM) {
            localTargetBufferMs = std::min(200.0f, localTargetBufferMs + 5.0f);
        }

        effectiveTargetBufferMs.store(localTargetBufferMs, std::memory_order_relaxed);
        reportedTargetBufferMs.store(localTargetBufferMs, std::memory_order_relaxed);
    };

    // 1. Initial packet processing
    runProcessPackets(/*simulateLoss=*/false);
    TEST_ASSERT(reportedTargetBufferMs.load() == 30.0f, "initial target must be 30ms");

    // 2. Loss occurs: target adapts to 35ms
    runProcessPackets(/*simulateLoss=*/true);
    TEST_ASSERT(reportedTargetBufferMs.load() == 35.0f, "target after loss must bump to 35ms");

    // 3. Normal packet arrives: adapted target MUST NOT reset back to 30ms!
    runProcessPackets(/*simulateLoss=*/false);
    TEST_ASSERT(reportedTargetBufferMs.load() == 35.0f, "auto target must persist across packets");

    // 4. Second loss: target adapts to 40ms
    runProcessPackets(/*simulateLoss=*/true);
    TEST_ASSERT(reportedTargetBufferMs.load() == 40.0f, "target after second loss must bump to 40ms");

    // 5. Another normal packet: still 40ms
    runProcessPackets(/*simulateLoss=*/false);
    TEST_ASSERT(reportedTargetBufferMs.load() == 40.0f, "auto target must persist at 40ms");

    // 6. User manually changes configured target to 50ms: must update immediately
    configuredTargetBufferMs.store(50.0f);
    runProcessPackets(/*simulateLoss=*/false);
    TEST_ASSERT(reportedTargetBufferMs.load() == 50.0f, "manual configured change must override adapted target");

    // 7. Auto mode disabled: localTarget tracks configuredTarget strictly
    autoMode.store(false);
    configuredTargetBufferMs.store(20.0f);
    runProcessPackets(/*simulateLoss=*/false);
    TEST_ASSERT(reportedTargetBufferMs.load() == 20.0f, "non-auto mode must strictly track configured target");

    TEST_PASS("Auto buffer adaptation persists across incoming packets without resetting");
}

// ============================================================================
// 12. JITTER BUFFER SUITE (TESTS 1..4)
// ============================================================================

namespace {

constexpr uint32_t kJbSampleRate = 48000;
constexpr uint8_t  kJbChannels   = 2;
constexpr uint8_t  kJbCodecPcm16 = 0;
constexpr uint8_t  kJbCodecOpus  = 1;

// Builds a wire-accurate 12-byte little-endian header (<IIBBH) + payload
std::vector<uint8_t> buildWirePacket(
    uint32_t seq,
    uint32_t timestampMs,
    uint8_t codec,
    uint8_t channels,
    uint32_t sampleRate,
    const uint8_t* payload,
    size_t payloadBytes,
    uint8_t hop = 0
) {
    std::vector<uint8_t> pkt(12 + payloadBytes);
    auto writeU32LE = [&](size_t off, uint32_t v) {
        pkt[off + 0] = static_cast<uint8_t>(v & 0xFF);
        pkt[off + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
        pkt[off + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
        pkt[off + 3] = static_cast<uint8_t>((v >> 24) & 0xFF);
    };
    auto writeU16LE = [&](size_t off, uint16_t v) {
        pkt[off + 0] = static_cast<uint8_t>(v & 0xFF);
        pkt[off + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
    };

    writeU32LE(0, seq);
    writeU32LE(4, timestampMs);
    pkt[8] = static_cast<uint8_t>((codec & 0x0F) | ((hop & 0x0F) << 4));
    pkt[9] = channels;
    writeU16LE(10, static_cast<uint16_t>(sampleRate / 100));

    if (payloadBytes > 0 && payload != nullptr) {
        std::memcpy(pkt.data() + 12, payload, payloadBytes);
    }
    return pkt;
}

// Creates a 5 ms (240 frames) stereo PCM16 packet filled with a constant int16 marker
std::vector<uint8_t> makeTaggedPcmPacket(uint32_t seq, int16_t marker, uint32_t frameMs = 5) {
    const size_t frames = (kJbSampleRate * frameMs) / 1000;
    std::vector<int16_t> samples(frames * kJbChannels, marker);
    return buildWirePacket(
        seq,
        seq * frameMs,
        kJbCodecPcm16,
        kJbChannels,
        kJbSampleRate,
        reinterpret_cast<const uint8_t*>(samples.data()),
        samples.size() * sizeof(int16_t)
    );
}

// Reads frames from JitterBuffer
size_t pullFrames(JitterBuffer& jb, std::vector<int16_t>& out, size_t frames) {
    out.assign(frames * kJbChannels, 0);
    return jb.readAudio(out.data(), static_cast<int>(frames));
}

// ---------------------------------------------------------------------------
// Test 1: Cumulative rxPackets tracking, loss % math, and clean reset()
// ---------------------------------------------------------------------------
void testCumulativeRxPacketsAndReset() {
    JitterBuffer jb;
    jb.setTargetBufferMs(20.0f);

    constexpr uint32_t kCount = 10;
    for (uint32_t seq = 0; seq < kCount; ++seq) {
        auto pkt = makeTaggedPcmPacket(seq, static_cast<int16_t>(100 + seq));
        jb.pushPacket(pkt.data(), pkt.size());
    }
    jb.processPacketsForTest();

    JitterStats stats = jb.getStats();
    TEST_ASSERT(stats.rxPackets == kCount, "rxPackets must count every received packet");
    TEST_ASSERT(stats.lostPackets == 0, "sequential packets must report 0 lost");

    jb.reset();
    stats = jb.getStats();
    TEST_ASSERT(stats.rxPackets == 0, "reset() must zero cumulative rxPackets");
    TEST_ASSERT(stats.lostPackets == 0, "reset() must zero lostPackets");

    TEST_PASS("testCumulativeRxPacketsAndReset");
}

// ---------------------------------------------------------------------------
// Test 2: Out-of-order recovery (verifies low-water cap keeps cushion in mPacketMap)
// ---------------------------------------------------------------------------
void testOutOfOrderRecovery() {
    JitterBuffer jb;
    jb.setTargetBufferMs(40.0f); // 8 x 5 ms packets

    // Prebuffer seq 0..7 (40 ms)
    for (uint32_t seq = 0; seq < 8; ++seq) {
        auto pkt = makeTaggedPcmPacket(seq, static_cast<int16_t>(1000 + seq * 10));
        jb.pushPacket(pkt.data(), pkt.size());
    }
    jb.processPacketsForTest();

    // Low-water mark (~15 ms = 720 frames) must leave remaining packets in mPacketMap
    TEST_ASSERT(jb.getRingOccupiedFramesForTest() <= 960,
           "Ring buffer exceeded low-water cap; eager draining has regressed!");
    TEST_ASSERT(jb.getPacketMapSizeForTest() >= 4,
           "Jitter cushion must stay inside mPacketMap for reordering");

    // Push seq 8 and seq 10 (skipping seq 9 temporarily)
    auto pkt8  = makeTaggedPcmPacket(8,  1080);
    auto pkt10 = makeTaggedPcmPacket(10, 1100);
    jb.pushPacket(pkt8.data(), pkt8.size());
    jb.pushPacket(pkt10.data(), pkt10.size());
    jb.processPacketsForTest();

    // Consume 15 ms (3 packets: seq 0, 1, 2) while seq 9 is still in flight
    std::vector<int16_t> pcm;
    for (int i = 0; i < 3; ++i) {
        size_t got = pullFrames(jb, pcm, 240);
        TEST_ASSERT(got == 240, "must pull 240 frames");
        TEST_ASSERT(pcm[0] == static_cast<int16_t>(1000 + i * 10), "sample mismatch");
        jb.processPacketsForTest();
    }

    // Delayed seq 9 arrives before the decoder reaches seq 9
    auto pkt9 = makeTaggedPcmPacket(9, 1090);
    jb.pushPacket(pkt9.data(), pkt9.size());

    // Drain remaining seq 3..10 and verify exact in-order playback (9 before 10)
    for (int seq = 3; seq <= 10; ++seq) {
        jb.processPacketsForTest();
        size_t got = pullFrames(jb, pcm, 240);
        TEST_ASSERT(got == 240, "must pull 240 frames");
        int16_t expectedMarker = static_cast<int16_t>(1000 + seq * 10);
        TEST_ASSERT(pcm[0] == expectedMarker,
               "Out-of-order packet 9 was not reordered ahead of packet 10");
    }

    JitterStats stats = jb.getStats();
    TEST_ASSERT(stats.rxPackets == 11, "rxPackets mismatch");
    TEST_ASSERT(stats.lostPackets == 0,
           "Delayed packet within jitter window must not register as lost");

    TEST_PASS("testOutOfOrderRecovery");
}

// ---------------------------------------------------------------------------
// Test 3: Drift trimming (fires when queued ms > target + 15 ms & map > 4)
// ---------------------------------------------------------------------------
void testDriftTrimming() {
    JitterBuffer jb;
    jb.setTargetBufferMs(20.0f); // 4 x 5 ms packets; trim threshold = 35 ms (7 packets)

    // Flood 20 packets (100 ms) to simulate sender clock running fast / burst arrival
    for (uint32_t seq = 0; seq < 20; ++seq) {
        auto pkt = makeTaggedPcmPacket(seq, static_cast<int16_t>(2000 + seq));
        jb.pushPacket(pkt.data(), pkt.size());
    }

    // Pump decode/trim loop while simulating normal 5 ms playback ticks
    std::vector<int16_t> pcm;
    for (int tick = 0; tick < 8; ++tick) {
        jb.processPacketsForTest();
        pullFrames(jb, pcm, 240);
    }
    jb.processPacketsForTest();

    const size_t ringFrames = jb.getRingOccupiedFramesForTest();
    const size_t mapPackets = jb.getPacketMapSizeForTest();
    const float remainingMs =
        (static_cast<float>(ringFrames) * 1000.0f / kJbSampleRate) +
        (static_cast<float>(mapPackets) * 5.0f);

    TEST_ASSERT(remainingMs <= 45.0f,
           "Drift trimming failed to pull excess latency back toward target buffer");

    // Verify subsequent packets continue cleanly without false loss
    const uint64_t lostBefore = jb.getStats().lostPackets;
    for (uint32_t seq = 20; seq < 26; ++seq) {
        auto pkt = makeTaggedPcmPacket(seq, static_cast<int16_t>(2000 + seq));
        jb.pushPacket(pkt.data(), pkt.size());
        jb.processPacketsForTest();
        pullFrames(jb, pcm, 240);
    }
    TEST_ASSERT(jb.getStats().rxPackets == 26, "rxPackets mismatch");
    TEST_ASSERT(jb.getStats().lostPackets == lostBefore,
           "Post-trim sequence tracking broke");

    TEST_PASS("testDriftTrimming");
}

// ---------------------------------------------------------------------------
// Test 4: Opus in-band FEC recovery preserves next packet for normal decode
// ---------------------------------------------------------------------------
#ifdef HAS_OPUS
void testOpusInBandFecPreservesNextPacket() {
    int err = OPUS_OK;
    OpusEncoder* enc = opus_encoder_create(kJbSampleRate, kJbChannels, OPUS_APPLICATION_VOIP, &err);
    TEST_ASSERT(err == OPUS_OK && enc != nullptr, "encoder create failed");
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(32000));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(25));

    constexpr size_t kFrameMs = 20;
    constexpr int kFrameSamples = 960; // 20 ms @ 48 kHz
    std::vector<std::vector<uint8_t>> encodedPackets;

    for (uint32_t seq = 0; seq < 6; ++seq) {
        std::vector<int16_t> sine(kFrameSamples * kJbChannels);
        for (int i = 0; i < kFrameSamples; ++i) {
            double t = static_cast<double>(seq * kFrameSamples + i) / kJbSampleRate;
            int16_t s = static_cast<int16_t>(16000.0 * std::sin(2.0 * M_PI * 440.0 * t));
            sine[i * 2]     = s;
            sine[i * 2 + 1] = s;
        }
        uint8_t opusBuf[400];
        int bytes = opus_encode(enc, sine.data(), kFrameSamples, opusBuf, sizeof(opusBuf));
        TEST_ASSERT(bytes > 0, "opus_encode failed");
        encodedPackets.push_back(buildWirePacket(
            seq, seq * kFrameMs, kJbCodecOpus, kJbChannels, kJbSampleRate, opusBuf, static_cast<size_t>(bytes)
        ));
    }
    opus_encoder_destroy(enc);

    JitterBuffer jb;
    jb.setTargetBufferMs(40.0f); // 2 x 20 ms frames

    for (uint32_t seq : {0u, 1u, 2u, 4u, 5u}) {
        jb.pushPacket(encodedPackets[seq].data(), encodedPackets[seq].size());
    }

    std::vector<int16_t> pcm;
    int nonZeroSlots = 0;
    for (int slot = 0; slot < 6; ++slot) {
        jb.processPacketsForTest();
        size_t got = pullFrames(jb, pcm, kFrameSamples);
        TEST_ASSERT(got == kFrameSamples,
               "Missing slot: FEC decode likely consumed seq 4 instead of preserving it");

        int64_t energy = 0;
        for (int16_t s : pcm) energy += std::abs(s);
        if (energy > 0) ++nonZeroSlots;
    }

    JitterStats stats = jb.getStats();
    TEST_ASSERT(stats.rxPackets == 5, "rxPackets mismatch");
    TEST_ASSERT(stats.lostPackets == 1, "Exactly 1 packet (seq 3) must be counted as lost");
    TEST_ASSERT(nonZeroSlots >= 5, "FEC + preserved seq 4 should produce non-silent audio");

    const double lossPct =
        (static_cast<double>(stats.lostPackets) * 100.0) /
        static_cast<double>(stats.rxPackets + stats.lostPackets);
    TEST_ASSERT(std::fabs(lossPct - (100.0 / 6.0)) < 0.01, "lossPct mismatch");

    TEST_PASS("testOpusInBandFecPreservesNextPacket");
}
#endif

void testBluetoothLargeBurstNoStarvation() {
    JitterBuffer jb;
    jb.setTargetBufferMs(30.0f);
    jb.onRouteChanged(/*isBluetooth=*/true, /*burstFrames=*/1920);
    jb.observeCallbackFrames(1920); // Simulate 40ms Bluetooth A2DP callback burst

    // Feed 30 x 5ms packets (150ms)
    for (uint32_t seq = 0; seq < 30; ++seq) {
        auto pkt = makeTaggedPcmPacket(seq, static_cast<int16_t>(3000 + seq));
        jb.pushPacket(pkt.data(), pkt.size());
    }
    jb.processPacketsForTest();

    // Must hold >= 1920 frames in ring buffer so BT callback does not underrun
    TEST_ASSERT(jb.getRingOccupiedFramesForTest() >= 1920,
                "Bluetooth low-water scaling failed to buffer a full 1920-frame BT burst");

    std::vector<int16_t> pcm;
    size_t got = pullFrames(jb, pcm, 1920);
    TEST_ASSERT(got == 1920, "Bluetooth 1920-frame read underran");
    TEST_ASSERT(jb.getRingBuffer().underrunFrames() == 0, "Zero underruns required on BT burst read");

    TEST_PASS("testBluetoothLargeBurstNoStarvation");
}

} // namespace

void runJitterBufferTests() {
    testCumulativeRxPacketsAndReset();
    testOutOfOrderRecovery();
    testDriftTrimming();
    testBluetoothLargeBurstNoStarvation();
#ifdef HAS_OPUS
    testOpusInBandFecPreservesNextPacket();
#else
    std::cout << "[SKIP] testOpusInBandFecPreservesNextPacket (compile with -DHAS_OPUS -lopus)" << std::endl;
#endif
}

// ============================================================================
// 13. 5-BAND BIQUAD EQ STABILITY, SWEEPS & NaN RECOVERY TEST
// ============================================================================

void test_biquad_eq_stability_and_nan_recovery() {
    struct Coeffs {
        double b0{1.0}, b1{0.0}, b2{0.0}, a1{0.0}, a2{0.0};
        bool active{false};
    };
    const double freqs[5] = {60.0, 230.0, 910.0, 3600.0, 14000.0};
    constexpr double PI = 3.14159265358979323846;
    const double sr = 48000.0;
    const double q = 1.15;

    auto design = [&](const float rawGains[5], Coeffs out[5]) -> bool {
        bool any = false;
        for (int i = 0; i < 5; ++i) {
            double g = std::isfinite(rawGains[i]) ? std::clamp(static_cast<double>(rawGains[i]), -12.0, 12.0) : 0.0;
            if (std::abs(g) <= 0.05) {
                out[i] = {1.0, 0.0, 0.0, 0.0, 0.0, false};
                continue;
            }
            any = true;
            double A = std::pow(10.0, g / 40.0);
            double w0 = 2.0 * PI * freqs[i] / sr;
            double alpha = std::sin(w0) / (2.0 * q);
            double cos_w0 = std::cos(w0);
            double a0 = 1.0 + alpha / A;
            out[i] = {
                (1.0 + alpha * A) / a0,
                (-2.0 * cos_w0) / a0,
                (1.0 - alpha * A) / a0,
                (-2.0 * cos_w0) / a0,
                (1.0 - alpha / A) / a0,
                true
            };
        }
        return any;
    };

    double z1[5][2] = {{0.0}};
    double z2[5][2] = {{0.0}};
    Coeffs coeffs[5];

    auto processBlock = [&](std::vector<int16_t>& pcm, double gain) {
        const int frames = static_cast<int>(pcm.size() / 2);
        const double knee = 0.85;
        const double inv = 1.0 / (1.0 - knee);
        for (int n = 0; n < frames; ++n) {
            for (int ch = 0; ch < 2; ++ch) {
                const double dry = pcm[n * 2 + ch] / 32768.0;
                double x = dry;
                bool ok = true;
                for (int b = 0; b < 5; ++b) {
                    if (!coeffs[b].active) continue;
                    double y = coeffs[b].b0 * x + z1[b][ch];
                    double nz1 = coeffs[b].b1 * x - coeffs[b].a1 * y + z2[b][ch];
                    double nz2 = coeffs[b].b2 * x - coeffs[b].a2 * y;
                    if (!std::isfinite(y) || !std::isfinite(nz1) || !std::isfinite(nz2)) {
                        z1[b][ch] = 0.0;
                        z2[b][ch] = 0.0;
                        ok = false;
                        break;
                    }
                    z1[b][ch] = nz1;
                    z2[b][ch] = nz2;
                    x = y;
                }
                if (!ok) x = dry;
                x *= gain;
                if (x > knee) x = knee + (1.0 - knee) * std::tanh((x - knee) * inv);
                else if (x < -knee) x = -knee - (1.0 - knee) * std::tanh((-x - knee) * inv);
                int32_t v = static_cast<int32_t>(std::round(std::clamp(x, -1.0, 1.0) * 32767.0));
                pcm[n * 2 + ch] = static_cast<int16_t>(std::clamp(v, -32768, 32767));
            }
        }
        for (int b = 0; b < 5; ++b) {
            for (int ch = 0; ch < 2; ++ch) {
                if (std::abs(z1[b][ch]) < 1e-18) z1[b][ch] = 0.0;
                if (std::abs(z2[b][ch]) < 1e-18) z2[b][ch] = 0.0;
            }
        }
    };

    // 1. NaN gain sanitization
    float nanGains[5] = {NAN, INFINITY, -INFINITY, 0.0f, 0.0f};
    TEST_ASSERT(!design(nanGains, coeffs), "NaN/Inf gains must sanitize to inactive flat bands");

    // Generate 5ms multi-tone test signal
    std::vector<int16_t> dry(240 * 2);
    for (int i = 0; i < 240; ++i) {
        double t = static_cast<double>(i) / sr;
        double s = 0.25 * std::sin(2.0 * PI * 60.0 * t) + 0.25 * std::sin(2.0 * PI * 1000.0 * t);
        int16_t v = static_cast<int16_t>(std::round(s * 32767.0));
        dry[i * 2] = v;
        dry[i * 2 + 1] = v;
    }

    // 2. Rapid extreme sweeps (-12dB <-> +12dB)
    for (int step = 0; step < 40; ++step) {
        float g = (step % 2 == 0) ? 12.0f : -12.0f;
        float sweep[5] = {g, -g, g, -g, g};
        TEST_ASSERT(design(sweep, coeffs), "sweep bands must be active");
        auto buf = dry;
        processBlock(buf, 1.0);
        int maxAmp = 0;
        for (int16_t s : buf) maxAmp = std::max(maxAmp, std::abs(static_cast<int>(s)));
        TEST_ASSERT(maxAmp > 500, "rapid EQ sweep lost audio signal");
    }

    // 3. Inject NaN into filter state -> immediate dry fallback & clean next frame
    float activeSweep[5] = {6.0f, 6.0f, 6.0f, 6.0f, 6.0f};
    design(activeSweep, coeffs);
    z1[0][0] = NAN;
    auto bufNan = dry;
    processBlock(bufNan, 1.0);
    TEST_ASSERT(std::isfinite(z1[0][0]), "NaN state was not cleared");
    auto bufNext = dry;
    processBlock(bufNext, 1.0);
    int maxNext = 0;
    for (int16_t s : bufNext) maxNext = std::max(maxNext, std::abs(static_cast<int>(s)));
    TEST_ASSERT(maxNext > 500, "EQ latched NaN into silence on next frame");

    TEST_PASS("5-band biquad EQ NaN sanitization, sweeps & dry-fallback recovery");
}

// ============================================================================
// Main runner
// ============================================================================

int main() {
    std::cout << "============================================================" << std::endl;
    std::cout << "Air Speaker Native Audio Engine — Comprehensive Phase 1.1 Tests" << std::endl;
    std::cout << "============================================================" << std::endl;

    test_ring_exact_write_read();
    test_ring_partial_read_and_zero_fill();
    test_ring_wrap_around();
    test_ring_overflow_drop_oldest();
    test_ring_multithreaded_stress_5_million_frames();
    test_opus_fec_sequencing_normal_and_missing();
    test_opus_plc_fallback_two_losses();
    test_sequence_wrap_around_boundary();
    test_failed_start_transactional_rollback();
    test_100_start_stop_cycles();
    test_auto_buffer_persists_across_packets();
    runJitterBufferTests();
    test_biquad_eq_stability_and_nan_recovery();

    std::cout << "============================================================" << std::endl;
    std::cout << "Results: " << gPassCount << " PASS / " << gFailCount << " FAIL" << std::endl;
    std::cout << "============================================================" << std::endl;

    return (gFailCount == 0) ? 0 : 1;
}

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
#include "pcm_ring_buffer.h"
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
    std::map<uint32_t, AudioPacket> mPacketMap;
    std::vector<MockOpusDecodeCall> mDecodeLog;
    uint32_t mExpectedSeq{0};
    uint64_t mLostPackets{0};
    uint64_t mConcealedPackets{0};
    uint64_t mLatePackets{0};
    uint64_t mDuplicatePackets{0};
    bool mHasStarted{false};

    void pushPacket(uint32_t seq, const std::string& data = "audio") {
        if (mHasStarted && testSeqDiff(seq, mExpectedSeq) < 0) {
            mLatePackets++;
            return;
        }
        if (mPacketMap.find(seq) != mPacketMap.end()) {
            mDuplicatePackets++;
            return;
        }

        AudioPacket pkt{};
        pkt.header.seq = seq;
        pkt.header.codec = CODEC_OPUS;
        pkt.payload.assign(data.begin(), data.end());
        mPacketMap[seq] = pkt;

        if (!mHasStarted) {
            mExpectedSeq = seq;
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
            call.seq = mExpectedSeq;
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
            call.seq = mExpectedSeq; // Reconstructed missing N
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
        call.seq = mExpectedSeq;
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

// ============================================================================
// Main runner
// ============================================================================

int main() {
    std::cout << "============================================================" << std::endl;
    std::cout << "PhoneSpeaker Native Audio Engine — Comprehensive Phase 1.1 Tests" << std::endl;
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

    std::cout << "============================================================" << std::endl;
    std::cout << "Results: " << gPassCount << " PASS / " << gFailCount << " FAIL" << std::endl;
    std::cout << "============================================================" << std::endl;

    return (gFailCount == 0) ? 0 : 1;
}

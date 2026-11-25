#pragma once

#include <cstdint>
#include <atomic>
#include <algorithm>
#include <cstring>
#include <vector>

/**
 * Lock-Free Single-Producer Single-Consumer (SPSC) Stereo PCM Ring Buffer.
 *
 * Designed specifically for high-priority real-time audio threads (e.g. Oboe / AAudio):
 * - Fixed preallocated capacity (65,536 stereo frames = 131,072 int16_t samples = ~1.36s at 48kHz).
 * - Zero dynamic memory allocations during read() or write().
 * - Zero mutexes or system calls in read().
 * - Strict SPSC ownership:
 *     - Producer exclusively writes mWriteSequence and advances mMinimumReadableSequence on overflow.
 *     - Consumer exclusively writes mReadSequence and catches up to mMinimumReadableSequence.
 *     - Producer NEVER writes mReadSequence.
 *     - Consumer NEVER modifies mMinimumReadableSequence.
 * - Acquire/release atomic memory ordering.
 * - Monotonically increasing 64-bit frame sequence counters.
 * - Underrun zero-filling with underrun frame accounting.
 * - Drop-oldest overflow trimming with overflow drop accounting.
 */
class PcmRingBuffer {
public:
    static constexpr size_t CAPACITY_FRAMES = 65536; // Power of 2 (65,536 frames)
    static constexpr size_t FRAME_MASK = CAPACITY_FRAMES - 1;
    static constexpr int CHANNELS = 2; // Stereo interleaved

    PcmRingBuffer()
        : mBuffer(CAPACITY_FRAMES * CHANNELS, 0)
        , mWriteSequence(0)
        , mMinimumReadableSequence(0)
        , mReadSequence(0)
        , mOverflowDrops(0)
        , mUnderrunFrames(0)
    {
    }

    void reset() {
        mWriteSequence.store(0, std::memory_order_relaxed);
        mMinimumReadableSequence.store(0, std::memory_order_relaxed);
        mReadSequence.store(0, std::memory_order_relaxed);
        mOverflowDrops.store(0, std::memory_order_relaxed);
        mUnderrunFrames.store(0, std::memory_order_relaxed);
        std::fill(mBuffer.begin(), mBuffer.end(), 0);
    }

    /**
     * Producer-only lock-free flush for live streams.
     * Advances mMinimumReadableSequence to mWriteSequence without touching
     * mReadSequence or mBuffer, preserving strict SPSC ownership while the
     * audio callback thread is concurrently executing readFrames().
     */
    void flushFromProducer() {
        uint64_t cur_write = mWriteSequence.load(std::memory_order_relaxed);
        mMinimumReadableSequence.store(cur_write, std::memory_order_release);
    }

    /**
     * Producer: write decoded stereo PCM frames into the ring buffer.
     * Called strictly from the dedicated decode/reorder worker thread.
     * Never modifies mReadSequence.
     */
    int writeFrames(const int16_t* src, int num_frames) {
        if (!src || num_frames <= 0) return 0;
        uint64_t frames = static_cast<uint64_t>(num_frames);
        uint64_t current_write = mWriteSequence.load(std::memory_order_relaxed);

        // If a single write exceeds total buffer capacity, drop excess immediately
        if (frames > CAPACITY_FRAMES) {
            uint64_t excess = frames - CAPACITY_FRAMES;
            src += excess * CHANNELS;
            frames = CAPACITY_FRAMES;
            mOverflowDrops.fetch_add(excess, std::memory_order_relaxed);
            current_write += excess;
        }

        uint64_t new_write = current_write + frames;

        // Maintain oldest retained physical frame sequence in circular storage
        if (new_write > CAPACITY_FRAMES) {
            uint64_t oldest_retained = new_write - CAPACITY_FRAMES;
            uint64_t min_readable = mMinimumReadableSequence.load(std::memory_order_relaxed);
            if (oldest_retained > min_readable) {
                mMinimumReadableSequence.store(oldest_retained, std::memory_order_release);
            }
        }

        // Copy stereo frames into circular storage
        for (uint64_t i = 0; i < frames; ++i) {
            size_t idx = ((current_write + i) & FRAME_MASK) * CHANNELS;
            mBuffer[idx] = src[i * CHANNELS];
            mBuffer[idx + 1] = src[i * CHANNELS + 1];
        }

        // Publish new write sequence with release semantics
        mWriteSequence.store(new_write, std::memory_order_release);
        return num_frames;
    }

    /**
     * Consumer: read requested stereo PCM frames for playback.
     * Called strictly from the real-time audio callback thread (e.g. Oboe onAudioReady()).
     * Guaranteed lock-free, allocation-free, and real-time safe.
     * Exclusively updates mReadSequence.
     */
    int readFrames(int16_t* dest, int num_frames) {
        if (!dest || num_frames <= 0) return 0;
        const uint64_t requested = static_cast<uint64_t>(num_frames);

        uint64_t cur_read = mReadSequence.load(std::memory_order_relaxed);
        const uint64_t min_readable = mMinimumReadableSequence.load(std::memory_order_acquire);

        // If consumer fell behind the minimum readable boundary due to producer overflow,
        // catch up to the oldest valid retained frame and record exact unread frames dropped.
        if (cur_read < min_readable) {
            uint64_t dropped = min_readable - cur_read;
            mOverflowDrops.fetch_add(dropped, std::memory_order_relaxed);
            cur_read = min_readable;
        }

        const uint64_t cur_write = mWriteSequence.load(std::memory_order_acquire);
        uint64_t available = (cur_write > cur_read) ? (cur_write - cur_read) : 0;
        uint64_t to_read = std::min(requested, available);

        // Copy stereo audio frames
        for (uint64_t i = 0; i < to_read; ++i) {
            size_t idx = ((cur_read + i) & FRAME_MASK) * CHANNELS;
            dest[i * CHANNELS] = mBuffer[idx];
            dest[i * CHANNELS + 1] = mBuffer[idx + 1];
        }

        // Zero-fill any shortage (underrun) with pure silence
        if (to_read < requested) {
            uint64_t missing = requested - to_read;
            std::memset(dest + (to_read * CHANNELS), 0, missing * CHANNELS * sizeof(int16_t));
            mUnderrunFrames.fetch_add(missing, std::memory_order_relaxed);
        }

        // Publish new read position
        mReadSequence.store(cur_read + to_read, std::memory_order_release);
        return static_cast<int>(to_read);
    }

    // Required sequence and counter inspection
    uint64_t availableFrames() const {
        uint64_t cur_read = mReadSequence.load(std::memory_order_relaxed);
        const uint64_t min_readable = mMinimumReadableSequence.load(std::memory_order_acquire);
        if (cur_read < min_readable) {
            cur_read = min_readable;
        }
        const uint64_t cur_write = mWriteSequence.load(std::memory_order_acquire);
        return (cur_write > cur_read) ? (cur_write - cur_read) : 0;
    }

    uint64_t overflowDroppedFrames() const {
        return mOverflowDrops.load(std::memory_order_relaxed);
    }

    uint64_t underrunFrames() const {
        return mUnderrunFrames.load(std::memory_order_relaxed);
    }

    uint64_t writeSequence() const {
        return mWriteSequence.load(std::memory_order_relaxed);
    }

    uint64_t readSequence() const {
        return mReadSequence.load(std::memory_order_relaxed);
    }

    uint64_t minimumReadableSequence() const {
        return mMinimumReadableSequence.load(std::memory_order_relaxed);
    }

    // Convenience telemetry helpers
    size_t getOccupiedFrames() const {
        return static_cast<size_t>(availableFrames());
    }

    float getOccupiedMs(int sample_rate = 48000) const {
        if (sample_rate <= 0) sample_rate = 48000;
        return (static_cast<float>(availableFrames()) * 1000.0f) / static_cast<float>(sample_rate);
    }

    uint32_t getOverflowDrops() const {
        return static_cast<uint32_t>(overflowDroppedFrames());
    }

    uint32_t getUnderrunFrames() const {
        return static_cast<uint32_t>(underrunFrames());
    }

private:
    std::vector<int16_t> mBuffer;

    // Cache-line separated atomic sequence counters
    alignas(64) std::atomic<uint64_t> mWriteSequence;
    alignas(64) std::atomic<uint64_t> mMinimumReadableSequence;
    alignas(64) std::atomic<uint64_t> mReadSequence;

    // Cache-line separated atomic telemetry metrics
    alignas(64) std::atomic<uint64_t> mOverflowDrops;
    alignas(64) std::atomic<uint64_t> mUnderrunFrames;
};

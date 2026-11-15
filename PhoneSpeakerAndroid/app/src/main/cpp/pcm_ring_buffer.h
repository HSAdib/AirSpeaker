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
 * - Acquire/release atomic memory ordering.
 * - Underrun zero-filling with callback underrun frame accounting.
 * - Drop-oldest overflow trimming with pcm_ring_overflow_drops accounting.
 */
class PcmRingBuffer {
public:
    static constexpr size_t CAPACITY_FRAMES = 65536; // Power of 2 (65,536 frames)
    static constexpr size_t FRAME_MASK = CAPACITY_FRAMES - 1;
    static constexpr int CHANNELS = 2; // Stereo interleaved

    PcmRingBuffer()
        : mBuffer(CAPACITY_FRAMES * CHANNELS, 0)
        , mWritePos(0)
        , mReadPos(0)
        , mOverflowDrops(0)
        , mUnderrunFrames(0)
    {
    }

    void reset() {
        mWritePos.store(0, std::memory_order_relaxed);
        mReadPos.store(0, std::memory_order_relaxed);
        std::fill(mBuffer.begin(), mBuffer.end(), 0);
    }

    /**
     * Producer: write decoded stereo PCM frames into the ring buffer.
     * Called strictly from the dedicated decode/reorder worker thread.
     */
    int writeFrames(const int16_t* src, int num_frames) {
        if (!src || num_frames <= 0) return 0;

        size_t write_pos = mWritePos.load(std::memory_order_relaxed);
        size_t read_pos = mReadPos.load(std::memory_order_acquire);

        size_t occupied = write_pos - read_pos;
        size_t available_space = CAPACITY_FRAMES - occupied;

        // If ring buffer is full or near full, drop the oldest 5ms/10ms frame chunk (e.g. 240 frames)
        // to prevent latency growth while never blocking.
        if (static_cast<size_t>(num_frames) > available_space) {
            size_t needed = static_cast<size_t>(num_frames) - available_space;
            // Round up to multiples of 240 frames (5ms)
            size_t drop_frames = ((needed + 239) / 240) * 240;
            if (drop_frames > occupied) {
                drop_frames = occupied;
            }
            mReadPos.store(read_pos + drop_frames, std::memory_order_release);
            mOverflowDrops.fetch_add(static_cast<uint32_t>(drop_frames), std::memory_order_relaxed);
            read_pos += drop_frames;
        }

        // Copy frames into circular buffer
        for (int i = 0; i < num_frames; ++i) {
            size_t idx = ((write_pos + static_cast<size_t>(i)) & FRAME_MASK) * CHANNELS;
            mBuffer[idx] = src[i * CHANNELS];
            mBuffer[idx + 1] = src[i * CHANNELS + 1];
        }

        mWritePos.store(write_pos + static_cast<size_t>(num_frames), std::memory_order_release);
        return num_frames;
    }

    /**
     * Consumer: read requested stereo PCM frames for playback.
     * Called strictly from the real-time Oboe onAudioReady() audio callback.
     * Guaranteed lock-free, allocation-free, and real-time safe.
     */
    int readFrames(int16_t* dest, int num_frames) {
        if (!dest || num_frames <= 0) return 0;

        size_t read_pos = mReadPos.load(std::memory_order_relaxed);
        size_t write_pos = mWritePos.load(std::memory_order_acquire);

        size_t occupied = (write_pos >= read_pos) ? (write_pos - read_pos) : 0;
        size_t frames_to_read = std::min(static_cast<size_t>(num_frames), occupied);

        // Copy available frames
        for (size_t i = 0; i < frames_to_read; ++i) {
            size_t idx = ((read_pos + i) & FRAME_MASK) * CHANNELS;
            dest[i * CHANNELS] = mBuffer[idx];
            dest[i * CHANNELS + 1] = mBuffer[idx + 1];
        }

        // Zero-fill any shortage (underrun) with pure silence
        if (frames_to_read < static_cast<size_t>(num_frames)) {
            size_t missing_frames = static_cast<size_t>(num_frames) - frames_to_read;
            std::memset(dest + (frames_to_read * CHANNELS), 0, missing_frames * CHANNELS * sizeof(int16_t));
            mUnderrunFrames.fetch_add(static_cast<uint32_t>(missing_frames), std::memory_order_relaxed);
        }

        mReadPos.store(read_pos + frames_to_read, std::memory_order_release);
        return static_cast<int>(frames_to_read);
    }

    size_t getOccupiedFrames() const {
        size_t write_pos = mWritePos.load(std::memory_order_relaxed);
        size_t read_pos = mReadPos.load(std::memory_order_relaxed);
        return (write_pos >= read_pos) ? (write_pos - read_pos) : 0;
    }

    float getOccupiedMs(int sample_rate = 48000) const {
        if (sample_rate <= 0) sample_rate = 48000;
        return (static_cast<float>(getOccupiedFrames()) * 1000.0f) / static_cast<float>(sample_rate);
    }

    uint32_t getOverflowDrops() const {
        return mOverflowDrops.load(std::memory_order_relaxed);
    }

    uint32_t getUnderrunFrames() const {
        return mUnderrunFrames.load(std::memory_order_relaxed);
    }

private:
    std::vector<int16_t> mBuffer;
    alignas(64) std::atomic<size_t> mWritePos;
    alignas(64) std::atomic<size_t> mReadPos;
    std::atomic<uint32_t> mOverflowDrops;
    std::atomic<uint32_t> mUnderrunFrames;
};

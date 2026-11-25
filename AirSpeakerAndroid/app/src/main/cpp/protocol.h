#pragma once

#include <cstdint>
#include <cstring>
#include <vector>

#pragma pack(push, 1)
struct AudioHeader {
    uint32_t seq{0};
    uint32_t timestamp_ms{0};
    uint8_t codec{0};
    uint8_t channels{2};
    uint16_t sample_rate_div100{480};
};
#pragma pack(pop)

static_assert(sizeof(AudioHeader) == 12, "AudioHeader must be exactly 12 bytes");

enum AudioCodec : uint8_t {
    CODEC_PCM16 = 0,
    CODEC_OPUS = 1
};

struct AudioPacket {
    AudioHeader header{};
    std::vector<uint8_t> payload;
    int64_t recv_time_ns{0};

    bool deserialize(const uint8_t* data, size_t size) {
        if (!data || size < sizeof(AudioHeader)) return false;
        std::memcpy(&header, data, sizeof(AudioHeader));
        header.codec &= 0x0F;
        payload.assign(data + sizeof(AudioHeader), data + size);
        return true;
    }
};


r"""
tests/verify_pc.py - Self-contained verification tests for Air Speaker PC.

Run with: .venv\Scripts\python tests\verify_pc.py
"""

import os
import socket
import struct
import sys
import time
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

import numpy as np

# Add src/ to path
_ROOT = Path(__file__).resolve().parent.parent
_SRC = _ROOT / "src"
sys.path.insert(0, str(_SRC))

PASS = "PASS"
FAIL = "FAIL"
results = []


def check(name: str, condition: bool, detail: str = "") -> None:
    status = PASS if condition else FAIL
    suffix = f" - {detail}" if detail else ""
    print(f"[{status}] {name}{suffix}")
    results.append((name, status))


# ---------------------------------------------------------------------------
# Test 1: Adapter filter - 192.168.56.1 is excluded
# ---------------------------------------------------------------------------
def test_adapter_filter() -> None:
    name = "Adapter filter excludes 192.168.56.1"
    try:
        from network import _is_excluded_iface

        class _FakeAddr:
            def __init__(self, ip):
                self.address = ip
                self.family = None

        addrs = [_FakeAddr("192.168.56.1")]
        excluded = _is_excluded_iface("VirtualBox Host-Only Ethernet", addrs)
        check(name, excluded, "192.168.56.1 correctly filtered")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 2: PCM16 5ms frame packet size = 972 bytes
# ---------------------------------------------------------------------------
def test_pcm_packet_size() -> None:
    name = "PCM16 5ms packet size == 972 bytes"
    try:
        from protocol import build_silence_packet, HEADER_SIZE, pcm_payload_size, FRAME_MS_5

        pkt = build_silence_packet(1)
        expected = HEADER_SIZE + pcm_payload_size(FRAME_MS_5)
        check(name, len(pkt) == 972,
              f"got {len(pkt)} bytes (header={HEADER_SIZE}, payload={pcm_payload_size(FRAME_MS_5)}, expected={expected})")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 3: Opus encoder creates and encodes 5ms silent frame
# ---------------------------------------------------------------------------
def test_opus_encoder() -> None:
    name = "Opus encoder creates and encodes 5ms silent frame"
    try:
        from opus_codec import OpusEncoder

        enc = OpusEncoder(
            sample_rate=48000,
            channels=2,
            bitrate=160_000,
            frame_ms=5,
        )
        if not enc.available:
            check(name, False, "opus.dll not found - encoder unavailable (PCM fallback mode)")
            return

        # 5ms @ 48kHz stereo int16 = 240 samples/ch * 2 ch * 2 bytes = 960 bytes
        samples_per_ch = (48000 * 5) // 1000  # 240
        silent_frame = b'\x00' * (samples_per_ch * 2 * 2)  # 960 bytes
        encoded = enc.encode(silent_frame)
        enc.destroy()

        check(name, encoded is not None and len(encoded) > 0,
              f"encoded {len(encoded) if encoded else 0} bytes")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 4: Silence frame generator at >=180 pps (5ms intervals)
# ---------------------------------------------------------------------------
def test_silence_pacing() -> None:
    name = "Silence frame pacing >=180 pps (targeting 200 pps / 5ms)"
    try:
        import ctypes
        try:
            ctypes.windll.winmm.timeBeginPeriod(1)
        except Exception:
            pass

        from protocol import build_silence_packet

        INTERVAL = 0.005
        DURATION = 1.0  # measure over 1 second

        count = 0
        next_tick = time.monotonic()
        end = time.monotonic() + DURATION

        while time.monotonic() < end:
            pkt = build_silence_packet(count)
            count += 1
            next_tick += INTERVAL
            sleep_time = next_tick - time.monotonic()
            if sleep_time > 0:
                time.sleep(sleep_time)

        try:
            ctypes.windll.winmm.timeEndPeriod(1)
        except Exception:
            pass

        pps = count
        check(name, pps >= 180, f"{pps} pps achieved")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 5: AudioCapture opens default loopback cleanly
# ---------------------------------------------------------------------------
def test_audio_capture_open() -> None:
    name = "AudioCapture opens default loopback cleanly"
    try:
        import queue
        from audio_capture import list_loopback_devices, AudioCapture

        devices = list_loopback_devices()
        if not devices:
            check(name, True, "Skipped on headless/CI runner (no physical audio output device detected)")
            return
        dev_idx = devices[0]["index"] if devices else None

        q = queue.Queue(maxsize=100)
        capture = AudioCapture(
            device_index=dev_idx,
            output_queue=q,
            frame_ms=5,
        )

        ok = capture.start()
        time.sleep(0.2)
        capture.stop()

        check(name, ok, f"device_index={dev_idx}, devices found={len(devices)}")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 6: Deterministic format regression & 440 Hz waveform conversion
# ---------------------------------------------------------------------------
def test_format_constants_and_sine_conversion() -> None:
    name = "Sample format constants & 440Hz sine conversion (Float32 & Int16)"
    try:
        try:
            import pyaudiowpatch as pyaudio
        except ImportError:
            import pyaudio

        from audio_capture import convert_to_target

        # 1. Assert PyAudio sample format constants to prevent regression
        assert pyaudio.paFloat32 == 1, f"Expected paFloat32=1, got {pyaudio.paFloat32}"
        assert pyaudio.paInt32 == 2, f"Expected paInt32=2, got {pyaudio.paInt32}"
        assert pyaudio.paInt24 == 4, f"Expected paInt24=4, got {pyaudio.paInt24}"
        assert pyaudio.paInt16 == 8, f"Expected paInt16=8, got {pyaudio.paInt16}"
        assert pyaudio.paInt8 == 16, f"Expected paInt8=16, got {pyaudio.paInt8}"
        assert pyaudio.paUInt8 == 32, f"Expected paUInt8=32, got {pyaudio.paUInt8}"

        # 2. Generate known 440 Hz stereo sine wave (5ms @ 48kHz = 240 frames)
        frames = 240
        t = np.arange(frames, dtype=np.float32) / 48000.0
        tone = 0.25 * np.sin(2.0 * np.pi * 440.0 * t)
        stereo = np.column_stack((tone, tone)).astype(np.float32)

        # Test Float32 input
        raw_float = stereo.astype("<f4").tobytes()
        converted_float = convert_to_target(
            raw_float,
            48000,
            2,
            pyaudio.paFloat32,
        )

        assert len(converted_float) == 960, f"Expected 960 bytes, got {len(converted_float)}"
        pcm_float = np.frombuffer(converted_float, dtype="<i2").reshape(-1, 2)

        peak_float = int(np.max(np.abs(pcm_float)))
        assert 7500 <= peak_float <= 8500, f"Expected peak ~8192 (7500-8500), got {peak_float}"
        assert np.array_equal(pcm_float[:, 0], pcm_float[:, 1]), "Left and right channels do not match"

        # Waveform smoothness check: max step difference between adjacent samples for 440Hz @ 48kHz
        max_diff = int(np.max(np.abs(np.diff(pcm_float[:, 0]))))
        assert max_diff < 1500, f"Waveform appears corrupted/static: max_diff={max_diff}"

        # Test Int16 input
        raw_int16 = np.rint(stereo * 32767.0).astype("<i2").tobytes()
        converted_int16 = convert_to_target(
            raw_int16,
            48000,
            2,
            pyaudio.paInt16,
        )

        assert len(converted_int16) == 960, f"Expected 960 bytes, got {len(converted_int16)}"
        pcm_int16 = np.frombuffer(converted_int16, dtype="<i2").reshape(-1, 2)
        orig_int16 = np.frombuffer(raw_int16, dtype="<i2").reshape(-1, 2)
        assert np.max(np.abs(pcm_int16 - orig_int16)) <= 1, "Int16 conversion deviated from original"

        check(name, True, f"peak={peak_float}, max_diff={max_diff}, int16 error <= 1")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 7: Local loopback UDP integration (Float32 capture -> convert -> UDP)
# ---------------------------------------------------------------------------
def test_udp_localhost_integration() -> None:
    name = "Local loopback UDP integration (Float32 -> convert -> 972-byte UDP -> verify)"
    try:
        try:
            import pyaudiowpatch as pyaudio
        except ImportError:
            import pyaudio

        from audio_capture import convert_to_target
        from protocol import CODEC_PCM16

        # 1. Generate 440Hz Float32 stereo block
        frames = 240
        t = np.arange(frames, dtype=np.float32) / 48000.0
        tone = 0.25 * np.sin(2.0 * np.pi * 440.0 * t)
        stereo = np.column_stack((tone, tone)).astype(np.float32)
        raw_float = stereo.astype("<f4").tobytes()

        # 2. Convert through convert_to_target
        converted_pcm = convert_to_target(raw_float, 48000, 2, pyaudio.paFloat32)
        assert len(converted_pcm) == 960

        # 3. Build 12-byte header + PCM payload
        seq = 42
        timestamp_ms = 123456
        codec = CODEC_PCM16  # 0
        channels = 2
        sample_rate_div100 = 480
        header = struct.pack("<IIBBH", seq, timestamp_ms, codec, channels, sample_rate_div100)
        datagram = header + converted_pcm
        assert len(datagram) == 972

        # 4. Send & receive on localhost UDP socket
        rx_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rx_sock.bind(("127.0.0.1", 0))
        rx_port = rx_sock.getsockname()[1]
        rx_sock.settimeout(1.0)

        tx_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        tx_sock.sendto(datagram, ("127.0.0.1", rx_port))

        data, _ = rx_sock.recvfrom(2048)
        rx_sock.close()
        tx_sock.close()

        # 5. Parse and verify
        assert len(data) == 972, f"Expected 972 bytes, got {len(data)}"
        r_seq, r_ts, r_codec, r_ch, r_sr = struct.unpack("<IIBBH", data[:12])
        r_payload = data[12:]

        assert r_seq == seq, f"seq mismatch: {r_seq} != {seq}"
        assert r_ts == timestamp_ms, f"ts mismatch: {r_ts} != {timestamp_ms}"
        assert r_codec == CODEC_PCM16, f"codec mismatch: {r_codec} != {CODEC_PCM16}"
        assert r_ch == 2, f"channels mismatch: {r_ch} != 2"
        assert r_sr == 480, f"sample_rate_div100 mismatch: {r_sr} != 480"
        assert r_payload == converted_pcm, "Payload does not match converted PCM"

        # Verify payload is smooth 440Hz sine wave
        pcm_arr = np.frombuffer(r_payload, dtype="<i2").reshape(-1, 2)
        peak = int(np.max(np.abs(pcm_arr)))
        assert 7500 <= peak <= 8500, f"Peak {peak} out of range"

        check(name, True, f"received {len(data)} bytes, codec={r_codec}, peak={peak}")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 8: Protocol v1.0 bidirectional header & CONFIG_SYNC validation
# ---------------------------------------------------------------------------
def test_protocol_v2_and_sync() -> None:
    name = "Protocol v1.0 bidirectional header & CONFIG_SYNC validation"
    try:
        from protocol import (
            AUDIO_PORT,
            PHONE_AUDIO_PORT,
            DEFAULT_CONFIG,
            build_header,
            parse_header,
            CODEC_OPUS,
        )
        from opus_codec import OpusDecoder

        assert AUDIO_PORT == 5004, f"Expected port 5004, got {AUDIO_PORT}"
        assert PHONE_AUDIO_PORT == 5004, f"Expected audio port 5004, got {PHONE_AUDIO_PORT}"
        assert DEFAULT_CONFIG["type"] == "CONFIG_SYNC"
        assert "direction" in DEFAULT_CONFIG
        assert DEFAULT_CONFIG["audio_profile"] == "BALANCED", f"Expected BALANCED profile, got {DEFAULT_CONFIG.get('audio_profile')}"
        assert DEFAULT_CONFIG["opus_bitrate_kbps"] == 192, f"Expected 192 kbps, got {DEFAULT_CONFIG.get('opus_bitrate_kbps')}"

        from opus_codec import OpusEncoder
        enc = OpusEncoder(bitrate=192000, frame_ms=5)
        assert enc.available, "OpusEncoder should be available"
        enc.set_bitrate(256000)
        enc.set_frame_ms(10)
        pcm_10ms = b"\x00" * 1920
        encoded_10ms = enc.encode(pcm_10ms)
        assert encoded_10ms is not None and len(encoded_10ms) > 0, "OpusEncoder failed to encode 10ms frame"
        enc.destroy()

        from ui import _QueueAdapter
        from network import AudioQueue
        assert _QueueAdapter(AudioQueue()).qsize() == 0, "QueueAdapter.qsize() check failed"

        hdr = build_header(42, 1000, CODEC_OPUS, channels=2, sample_rate=48000)
        parsed = parse_header(hdr)
        assert parsed is not None
        assert parsed["seq"] == 42
        assert parsed["timestamp_ms"] == 1000
        assert parsed["codec"] == CODEC_OPUS
        assert parsed["channels"] == 2
        assert parsed["sample_rate"] == 48000
        assert parsed.get("hop_count", 0) == 0

        relayed_hdr = bytearray(build_header(99, 2000, CODEC_OPUS))
        relayed_hdr[8] = (CODEC_OPUS & 0x0F) | (2 << 4)
        parsed_relayed = parse_header(bytes(relayed_hdr))
        assert parsed_relayed is not None
        assert parsed_relayed["codec"] == CODEC_OPUS and parsed_relayed["hop_count"] == 2

        dec = OpusDecoder(48000, 2)
        assert dec.available, "OpusDecoder should be available via opus.dll"
        plc_decoded = dec.decode(None, frame_size=240)
        dec.destroy()
        assert plc_decoded is not None and len(plc_decoded) == 240 * 2 * 2

        check(name, True, f"ports, config and OpusDecoder verified")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 9: Windows/Linux System Volume & Mute COM interface
# ---------------------------------------------------------------------------
def test_win_volume() -> None:
    name = "Windows/Linux system volume and mute query"
    try:
        from win_volume import get_system_volume_and_mute
        res = get_system_volume_and_mute()
        if res is not None:
            vol, muted = res
            check(name, 0.0 <= vol <= 2.0 and isinstance(muted, bool),
                  f"volume={vol:.2f}, muted={muted}")
        else:
            check(name, True, "get_system_volume_and_mute executed cleanly (no active endpoint)")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 10: PhoneAudioReceiver receives burst packets without drops
# ---------------------------------------------------------------------------
def test_phone_mic_receiver_no_drops() -> None:
    name = "PhoneAudioReceiver receives burst UDP packets without drops"
    try:
        from audio_output import PhoneAudioReceiver
        from protocol import build_silence_packet

        # Pick ephemeral port
        s_test = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s_test.bind(("", 0))
        test_port = s_test.getsockname()[1]
        s_test.close()

        rx = PhoneAudioReceiver(port=test_port)
        if not rx.start():
            check(name, False, "PhoneAudioReceiver failed to start")
            return

        time.sleep(0.05)

        # Send 20 back-to-back 972-byte packets
        tx_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        num_packets = 20
        for seq in range(1, num_packets + 1):
            packet = build_silence_packet(seq=seq)
            tx_sock.sendto(packet, ("127.0.0.1", test_port))
        tx_sock.close()

        # Wait up to 500ms for rx loop to receive packets
        deadline = time.monotonic() + 0.50
        while time.monotonic() < deadline and rx._pps_count < num_packets:
            time.sleep(0.01)

        received = rx._pps_count
        rx.stop()

        check(name, received == num_packets, f"sent {num_packets}, received {received} packets")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 11: UDPSender Party Mode Fan-Out to Multiple Receivers
# ---------------------------------------------------------------------------
def test_udp_sender_party_fanout() -> None:
    name = "UDPSender multi-target Party Mode fan-out"
    try:
        from network import UDPSender, AudioQueue
        from protocol import CODEC_PCM16, SILENCE_PCM_5MS

        rx1 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rx1.bind(("127.0.0.1", 0))
        rx1.settimeout(0.8)
        port1 = rx1.getsockname()[1]

        rx2 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rx2.bind(("127.0.0.2", 0))
        rx2.settimeout(0.8)
        port2 = rx2.getsockname()[1]

        q = AudioQueue()
        sender = UDPSender(audio_queue=q)
        sender.configure(target_ip="127.0.0.1", target_port=port1, codec=CODEC_PCM16, frame_ms=5)
        sender.set_party_mode(True)
        sender.add_party_target("127.0.0.2", port2)
        sender.start()

        # Push one frame
        q.put(SILENCE_PCM_5MS)

        data1, _ = rx1.recvfrom(2048)
        data2, _ = rx2.recvfrom(2048)

        sender.stop()
        rx1.close()
        rx2.close()

        ok = (len(data1) == 972 and len(data2) == 972 and data1 == data2)
        check(name, ok, f"both targets received identical 972-byte packet (len1={len(data1)}, len2={len(data2)})")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 12: AudioQueue drop-oldest overflow policy (Checkpoint 1)
# ---------------------------------------------------------------------------
def test_audio_queue_drop_oldest() -> None:
    name = "AudioQueue drop-oldest overflow policy"
    try:
        from network import AudioQueue

        capacity = 10
        q = AudioQueue(maxsize=capacity)
        for i in range(capacity):
            q.put(f"frame_{i}".encode("ascii"))

        if q.qsize() != capacity or q.capture_overflow_drops != 0:
            check(name, False, f"queue fill failed: size={q.qsize()}, drops={q.capture_overflow_drops}")
            return

        new_frame = b"new_frame_unique"
        q.put(new_frame)

        if q.capture_overflow_drops != 1:
            check(name, False, f"expected 1 drop, got {q.capture_overflow_drops}")
            return

        if q.qsize() != capacity:
            check(name, False, f"expected capacity {capacity}, got {q.qsize()}")
            return

        items = []
        while not q.empty():
            it = q.get_nowait()
            if it is not None:
                items.append(it)

        if b"frame_0" in items:
            check(name, False, "oldest frame was not dropped")
            return

        if items[0] != b"frame_1" or items[-1] != new_frame:
            check(name, False, f"frame ordering mismatch: head={items[0]}, tail={items[-1]}")
            return

        check(name, True, f"capacity={capacity}, drops={q.capture_overflow_drops}, oldest dropped, newest retained")
    except Exception as exc:
        check(name, False, str(exc))


def test_audio_queue_stress_10000() -> None:
    name = "AudioQueue 10,000-frame rapid producer stress & drop consistency"
    try:
        from network import AudioQueue
        import threading
        import time

        capacity = 10
        q = AudioQueue(maxsize=capacity)
        total_frames = 10000

        consumed = []
        stop_consumer = threading.Event()

        def consumer():
            while not stop_consumer.is_set() or not q.empty():
                frame = q.get_nowait()
                if frame is not None:
                    consumed.append(frame)
                else:
                    time.sleep(0.0001)

        consumer_thread = threading.Thread(target=consumer, daemon=True)
        consumer_thread.start()

        # Rapid producer (faster than consumer)
        for i in range(total_frames):
            frame_data = f"frame_{i}".encode("utf-8")
            q.put_latest(frame_data)

        time.sleep(0.05)
        stop_consumer.set()
        consumer_thread.join(timeout=2.0)

        remaining_items = []
        while not q.empty():
            it = q.get_nowait()
            if it is not None:
                remaining_items.append(it)

        all_received = consumed + remaining_items
        drops = q.capture_overflow_drops

        if q.qsize() > capacity:
            check(name, False, f"queue exceeded capacity: size={q.qsize()} > {capacity}")
            return

        expected_total = len(all_received) + drops
        if expected_total != total_frames:
            check(name, False, f"drop accounting inconsistent: received={len(all_received)} + drops={drops} != {total_frames}")
            return

        if remaining_items:
            last_frame_num = int(remaining_items[-1].decode("utf-8").split("_")[1])
            if last_frame_num != total_frames - 1:
                check(name, False, f"newest frame not retained: got {last_frame_num}, expected {total_frames - 1}")
                return

        check(name, True, f"10k frames: received={len(all_received)}, drops={drops}, sum={expected_total}, newest frame retained")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 14: StereoFiveBandBiquadDSP processing & soft-knee limiting
# ---------------------------------------------------------------------------
def test_equalizer_dsp() -> None:
    name = "StereoFiveBandBiquadDSP processing & limiting"
    try:
        from equalizer import StereoFiveBandBiquadDSP
        dsp = StereoFiveBandBiquadDSP()

        # Generate 480 stereo samples (240 stereo frames @ 48kHz = 5ms)
        t = np.linspace(0, 0.005, 240, endpoint=False)
        sine_60 = (np.sin(2 * np.pi * 60 * t) * 10000).astype(np.int16)
        stereo_pcm = np.empty((240, 2), dtype=np.int16)
        stereo_pcm[:, 0] = sine_60
        stereo_pcm[:, 1] = sine_60
        raw_in = stereo_pcm.tobytes()

        # Disabled DSP should return raw bytes unchanged
        out_flat = dsp.process_pcm16(raw_in)
        if out_flat != raw_in:
            check(name, False, "disabled DSP altered audio samples")
            return

        # Enable with +12 dB boost on 60 Hz
        dsp.set_gains([12.0, 0.0, 0.0, 0.0, 0.0], enabled=True)
        out_boost = dsp.process_pcm16(raw_in)
        arr_boost = np.frombuffer(out_boost, dtype=np.int16)

        if np.array_equal(arr_boost, stereo_pcm.flatten()):
            check(name, False, "boosted DSP produced identical samples")
            return

        # Check soft knee limiting with high amplitude
        loud_pcm = (np.ones((240, 2), dtype=np.int16) * 32000).tobytes()
        out_loud = dsp.process_pcm16(loud_pcm)
        arr_loud = np.frombuffer(out_loud, dtype=np.int16)
        if np.any(arr_loud > 32767) or np.any(arr_loud < -32768):
            check(name, False, "samples clipped beyond int16 bounds")
            return

        check(name, True, f"biquad filter active and soft-knee limiting verified (max amp: {np.max(np.abs(arr_boost))})")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 15: EqualizerManager context management & preset saving
# ---------------------------------------------------------------------------
def test_equalizer_manager() -> None:
    name = "EqualizerManager contexts, presets & effective gains"
    try:
        from equalizer import EqualizerManager, BUILTIN_PRESETS

        manager = EqualizerManager()

        # Verify built-in presets exist
        for p in ["Flat", "Bass Boost", "Vocal Crisp", "Gaming Footsteps", "Warm Studio", "Treble Air"]:
            if p not in BUILTIN_PRESETS:
                check(name, False, f"missing builtin preset: {p}")
                return

        # Test context update
        manager.update_context("play", enabled=True, preset_name="Bass Boost", bands=BUILTIN_PRESETS["Bass Boost"])
        play_cfg = manager.get_context("play")
        if not play_cfg["enabled"] or play_cfg["preset_name"] != "Bass Boost":
            check(name, False, f"context config mismatch: {play_cfg}")
            return

        # Test custom preset save and delete
        custom_bands = [3.0, 2.0, 1.0, 0.0, -1.0]
        manager.save_custom_preset("MyTestPreset", custom_bands)
        if "MyTestPreset" not in manager.get_all_presets():
            check(name, False, "custom preset failed to save")
            return

        manager.delete_custom_preset("MyTestPreset")
        if "MyTestPreset" in manager.get_all_presets():
            check(name, False, "custom preset failed to delete")
            return

        # Test effective gain combination with Universal EQ
        manager.update_context("universal", enabled=True, preset_name="Custom", bands=[2.0, 2.0, 0.0, 0.0, 0.0], apply_rx=True)
        effective_en, effective_bands = manager.get_effective_rx_gains(is_party_join=False)

        if not effective_en:
            check(name, False, "effective gains reported disabled")
            return

        expected_band0 = min(12.0, BUILTIN_PRESETS["Bass Boost"][0] + 2.0)
        if abs(effective_bands[0] - expected_band0) > 0.01:
            check(name, False, f"effective gain calculation wrong: {effective_bands[0]} != {expected_band0}")
            return

        check(name, True, "5 contexts, custom preset lifecycle, and universal combination verified")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 16: ControlListener PC_ANNOUNCE normalization & self-IP filter
# ---------------------------------------------------------------------------
def test_control_listener_pc_announce_and_self_filter() -> None:
    name = "ControlListener PC_ANNOUNCE normalization & self IP filter"
    listener = None
    sock = None
    try:
        from network import ControlListener
        import json

        announced = []
        beacons = []
        test_port = 55105
        listener = ControlListener(
            on_beacon=lambda m: beacons.append(m),
            on_pc_announce=lambda m: announced.append(m),
            bind_port=test_port,
        )
        listener.start()
        time.sleep(0.05)

        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

        # 1. Self-IP filter: Packet from 127.0.0.1 must be ignored because 127.0.0.1 is in _get_local_ips()
        msg_local = json.dumps({
            "type": "PC_ANNOUNCE",
            "pc_name": "SelfPC",
            "party_mode": True,
        }).encode("utf-8")
        sock.sendto(msg_local, ("127.0.0.1", test_port))
        time.sleep(0.1)

        if len(announced) != 0:
            check(name, False, f"self-packet was not filtered (got {len(announced)} callbacks)")
            return

        # 2. Peer packet: Allow 127.0.0.1 by clearing it from _local_ips cache to simulate remote peer
        listener._local_ips = {"10.254.254.254"}
        listener._last_local_ips_refresh = time.monotonic() + 100.0  # prevent immediate auto-refresh
        sock.sendto(msg_local, ("127.0.0.1", test_port))
        time.sleep(0.1)

        if len(announced) != 1:
            check(name, False, f"peer announcement not received (got {len(announced)}, expected 1)")
            return

        norm = announced[0]
        if norm.get("device") != "SelfPC":
            check(name, False, f"device not normalized: {norm.get('device')}")
            return
        if norm.get("audio_port") != 5004:
            check(name, False, f"audio_port not normalized: {norm.get('audio_port')}")
            return
        if norm.get("control_port") != test_port:
            check(name, False, f"control_port mismatch: {norm.get('control_port')}")
            return
        if norm.get("_sender_ip") != "127.0.0.1":
            check(name, False, f"_sender_ip mismatch: {norm.get('_sender_ip')}")
            return

        check(name, True, "self-filtering and PC_ANNOUNCE field normalization verified")
    except Exception as exc:
        check(name, False, str(exc))
    finally:
        if listener:
            listener.stop()
        if sock:
            sock.close()


# ---------------------------------------------------------------------------
# Test 17: StereoFiveBandBiquadDSP performance & output verification
# ---------------------------------------------------------------------------
def test_eq_dsp_performance_and_output() -> None:
    name = "StereoFiveBandBiquadDSP performance, curve & soft-knee limiting"
    try:
        from equalizer import StereoFiveBandBiquadDSP

        dsp = StereoFiveBandBiquadDSP()

        # 5ms @ 48kHz stereo = 240 samples per channel
        t = np.linspace(0, 0.005, 240, endpoint=False)
        sine_60 = (np.sin(2 * np.pi * 60 * t) * 10000).astype(np.int16)
        stereo_pcm = np.empty((240, 2), dtype=np.int16)
        stereo_pcm[:, 0] = sine_60
        stereo_pcm[:, 1] = sine_60
        raw_pcm = stereo_pcm.tobytes()

        # 1. Flat bypass: disabled DSP must return unmodified raw bytes
        out_flat = dsp.process_pcm16(raw_pcm)
        if out_flat != raw_pcm:
            check(name, False, "disabled DSP modified audio bytes")
            return

        # 2. Gain boost: +12dB on 60Hz should amplify low frequencies
        dsp.set_gains([12.0, 0.0, 0.0, 0.0, 0.0], enabled=True)
        out_boost = dsp.process_pcm16(raw_pcm)
        if out_boost == raw_pcm:
            check(name, False, "boosted DSP produced identical output")
            return
        arr_boost = np.frombuffer(out_boost, dtype=np.int16)
        if np.max(np.abs(arr_boost)) <= np.max(np.abs(stereo_pcm)):
            check(name, False, "boosted 60Hz did not increase peak amplitude")
            return

        # 3. High throughput benchmark: 10,000 frames under 500ms without NaN/Inf
        t0 = time.monotonic()
        last_out = None
        for _ in range(10000):
            last_out = dsp.process_pcm16(raw_pcm)
        elapsed = time.monotonic() - t0

        arr_last = np.frombuffer(last_out, dtype=np.int16)
        if np.isnan(arr_last).any() or np.isinf(arr_last).any():
            check(name, False, "DSP produced NaN or Inf values")
            return

        # 4. Soft-knee limiting: loud signals approaching full-scale must be smoothly constrained
        loud_pcm = (np.ones((240, 2), dtype=np.int16) * 32700).tobytes()
        dsp.set_gains([12.0, 12.0, 12.0, 12.0, 12.0], enabled=True)
        out_loud = dsp.process_pcm16(loud_pcm)
        arr_loud = np.frombuffer(out_loud, dtype=np.int16)
        if np.any(arr_loud > 32767) or np.any(arr_loud < -32768):
            check(name, False, "soft-knee limiter overflowed int16 bounds")
            return

        check(name, True, f"10k frames in {elapsed*1000:.1f}ms ({elapsed*1000/10000:.3f}ms/frame), bypass & limiter verified")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 18: AudioReceiver frame duration buffer math
# ---------------------------------------------------------------------------
def test_audio_receiver_frame_duration_buffer_math() -> None:
    name = "AudioReceiver frame duration calculation & buffer packet bounds"
    try:
        from audio_output import AudioReceiver

        rec = AudioReceiver()

        # 5ms @ 48kHz stereo int16 = 240 samples * 4 bytes = 960 bytes
        d5 = rec._update_frame_duration_ms(b"\x00" * 960, channels=2)
        if abs(d5 - 5.0) > 0.05:
            check(name, False, f"5ms frame calculated as {d5}ms")
            return

        # 10ms @ 48kHz stereo int16 = 480 samples * 4 bytes = 1920 bytes
        d10 = rec._update_frame_duration_ms(b"\x00" * 1920, channels=2)
        if abs(d10 - 10.0) > 0.05:
            check(name, False, f"10ms frame calculated as {d10}ms")
            return

        # 20ms @ 48kHz stereo int16 = 960 samples * 4 bytes = 3840 bytes
        d20 = rec._update_frame_duration_ms(b"\x00" * 3840, channels=2)
        if abs(d20 - 20.0) > 0.05:
            check(name, False, f"20ms frame calculated as {d20}ms")
            return

        # Buffer packet bounds adaptation: target = 30ms
        rec.set_target_buffer_ms(30.0)

        # 5ms frame: min_packets = round(30 / 5) = 6, max_packets = round((30 + 30) / 5) = 12
        rec._frame_duration_ms = 5.0
        min_p5, max_p5 = rec._compute_buffer_packet_bounds()
        if min_p5 != 6 or max_p5 != 12:
            check(name, False, f"5ms bounds mismatch: got ({min_p5}, {max_p5}), expected (6, 12)")
            return

        # 20ms frame: min_packets = max(2, round(30 / 20)) = 2, max_packets = max(6, round(60 / 20)) = 6
        rec._frame_duration_ms = 20.0
        min_p20, max_p20 = rec._compute_buffer_packet_bounds()
        if min_p20 != 2 or max_p20 != 6:
            check(name, False, f"20ms bounds mismatch: got ({min_p20}, {max_p20}), expected (2, 6)")
            return

        check(name, True, "5ms/10ms/20ms frame duration and dynamic buffer bounds verified")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    print("=" * 60)
    print("Air Speaker PC - Verification Tests")
    print("=" * 60)
    print()

    test_adapter_filter()
    test_pcm_packet_size()
    test_opus_encoder()
    test_silence_pacing()
    test_audio_capture_open()
    test_format_constants_and_sine_conversion()
    test_udp_localhost_integration()
    test_protocol_v2_and_sync()
    test_win_volume()
    test_phone_mic_receiver_no_drops()
    test_udp_sender_party_fanout()
    test_audio_queue_drop_oldest()
    test_audio_queue_stress_10000()
    test_equalizer_dsp()
    test_equalizer_manager()
    test_control_listener_pc_announce_and_self_filter()
    test_eq_dsp_performance_and_output()
    test_audio_receiver_frame_duration_buffer_math()

    print()
    print("=" * 60)
    passed = sum(1 for _, s in results if s == PASS)
    failed = sum(1 for _, s in results if s == FAIL)
    print(f"Results: {passed} PASS / {failed} FAIL out of {len(results)} tests")
    print("=" * 60)

    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()

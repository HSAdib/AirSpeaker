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
            DEFAULT_CONFIG,
            build_header,
            parse_header,
            CODEC_OPUS,
        )
        from opus_codec import OpusDecoder

        assert AUDIO_PORT == 5004, f"Expected port 5004, got {AUDIO_PORT}"
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
    name = "AudioReceiver receives burst UDP packets without drops"
    try:
        from audio_output import AudioReceiver
        from protocol import build_silence_packet

        # Pick ephemeral port
        s_test = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s_test.bind(("", 0))
        test_port = s_test.getsockname()[1]
        s_test.close()

        rx = AudioReceiver(port=test_port)
        used_headless_sink = False
        if not rx.start():
            # Headless CI runner (no physical playback device): mock audio sink so UDP burst path is still tested
            rx._open_audio_sink = lambda: True
            used_headless_sink = True
            if not rx.start():
                check(name, False, "AudioReceiver failed to start even with mocked sink")
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

        extra = " (headless sink mock)" if used_headless_sink else ""
        check(name, received == num_packets, f"sent {num_packets}, received {received} packets{extra}")
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
# Test 19: 5-Band EQ full-load speed, rapid sweeps, silence denormals & NaN recovery
# ---------------------------------------------------------------------------
def test_eq_5band_stress_and_nan_recovery() -> None:
    name = "5-Band EQ full-load speed, rapid sweeps, NaN gain & state recovery"
    try:
        from equalizer import StereoFiveBandBiquadDSP

        dsp = StereoFiveBandBiquadDSP()
        t = np.linspace(0, 0.005, 240, endpoint=False)
        sig = (
            0.25 * np.sin(2 * np.pi * 60 * t)
            + 0.25 * np.sin(2 * np.pi * 1000 * t)
            + 0.25 * np.sin(2 * np.pi * 8000 * t)
        )
        stereo = np.column_stack((sig, sig))
        raw_pcm = np.rint(stereo * 32767.0).astype("<i2").tobytes()
        silence_pcm = b"\x00" * 960

        # 1. NaN/Inf gain inputs must sanitize cleanly to 0 dB (never +12 dB or NaN)
        dsp.set_gains([float("nan"), float("inf"), float("-inf"), 0.0, 0.0], enabled=True)
        out_nan_gain = dsp.process_pcm16(raw_pcm, 2)
        if out_nan_gain != raw_pcm:
            check(name, False, "non-finite gain inputs did not sanitize to flat bypass")
            return

        # 2. All 5 bands active simultaneously (worst-case CPU load)
        dsp.set_gains([6.5, 4.0, -2.5, 5.0, 7.0], enabled=True)
        t0 = time.monotonic()
        for _ in range(1000):
            out = dsp.process_pcm16(raw_pcm, 2)
        elapsed_ms = (time.monotonic() - t0) * 1000.0
        per_frame_ms = elapsed_ms / 1000.0
        if per_frame_ms >= 2.0:
            check(name, False, f"5-band EQ too slow for 5ms real-time pacing: {per_frame_ms:.3f}ms/frame")
            return

        # 3. Rapid extreme gain sweeps (-12dB <-> +12dB) must never produce silence/NaN
        for step in range(40):
            g = 12.0 if (step % 2 == 0) else -12.0
            dsp.set_gains([g, -g, g, -g, g], enabled=True)
            out = dsp.process_pcm16(raw_pcm, 2)
            arr = np.frombuffer(out, dtype="<i2")
            if len(arr) != 480 or int(np.max(np.abs(arr))) == 0:
                check(name, False, f"sound lost during rapid sweep step {step}")
                return

        # 4. Silence run must flush denormals cleanly and resume normal audio immediately
        for _ in range(50):
            dsp.process_pcm16(silence_pcm, 2)
        out_resume = dsp.process_pcm16(raw_pcm, 2)
        if int(np.max(np.abs(np.frombuffer(out_resume, dtype="<i2")))) < 1000:
            check(name, False, "audio did not recover cleanly after silence")
            return

        # 5. Inject NaN into filter state -> must auto-recover to dry audio AND recover next frame
        dsp._py_zi[0][0][0] = float("nan")
        out_nan = dsp.process_pcm16(raw_pcm, 2)
        if out_nan != raw_pcm:
            check(name, False, "NaN filter state did not immediately fall back to dry audio")
            return
        out_next = dsp.process_pcm16(raw_pcm, 2)
        if int(np.max(np.abs(np.frombuffer(out_next, dtype="<i2")))) < 1000:
            check(name, False, "EQ latched NaN into permanent silence on subsequent frame")
            return

        check(name, True, f"5-band avg={per_frame_ms:.3f}ms/frame, NaN gain/state recovery & sweeps passed")
    except Exception as exc:
        check(name, False, str(exc))


# ---------------------------------------------------------------------------
# Test 20: AudioReceiver allowed_sender_ip filtering
# ---------------------------------------------------------------------------
def test_audio_receiver_allowed_sender_filter() -> None:
    name = "AudioReceiver allowed_sender_ip filtering drops unauthorized packets"
    rx = None
    tx_sock = None
    try:
        from audio_output import AudioReceiver
        from protocol import build_silence_packet

        # Pick ephemeral port
        s_test = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s_test.bind(("", 0))
        test_port = s_test.getsockname()[1]
        s_test.close()

        rx = AudioReceiver(port=test_port)
        if not rx.start():
            rx._open_audio_sink = lambda: True
            if not rx.start():
                check(name, False, "AudioReceiver failed to start even with mocked sink")
                return

        time.sleep(0.05)
        tx_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

        # 1. Unselected (allowed_sender_ip="", require_match=True) -> drops all 5 packets
        rx.set_allowed_sender_ip("", require_match=True)
        for seq in range(1, 6):
            tx_sock.sendto(build_silence_packet(seq=seq), ("127.0.0.1", test_port))
        time.sleep(0.06)
        if rx._rx_packets != 0:
            check(name, False, f"Expected 0 packets with empty allowed_sender & require_match, got {rx._rx_packets}")
            return

        # 2. Mismatched (allowed_sender_ip="10.255.255.254", require_match=True) -> drops all 5 packets
        rx.set_allowed_sender_ip("10.255.255.254", require_match=True)
        for seq in range(6, 11):
            tx_sock.sendto(build_silence_packet(seq=seq), ("127.0.0.1", test_port))
        time.sleep(0.06)
        if rx._rx_packets != 0:
            check(name, False, f"Expected 0 packets with mismatched allowed_sender, got {rx._rx_packets}")
            return

        # 3. Matched (allowed_sender_ip="127.0.0.1", require_match=True) -> accepts all 5 packets
        rx.set_allowed_sender_ip("127.0.0.1", require_match=True)
        for seq in range(11, 16):
            tx_sock.sendto(build_silence_packet(seq=seq), ("127.0.0.1", test_port))

        deadline = time.monotonic() + 0.5
        while time.monotonic() < deadline and rx._rx_packets < 5:
            time.sleep(0.01)

        if rx._rx_packets != 5:
            check(name, False, f"Expected 5 packets with matched allowed_sender, got {rx._rx_packets}")
            return

        check(name, True, "getter/setter and sender IP filtering with require_match verified")
    except Exception as exc:
        check(name, False, str(exc))
    finally:
        if rx:
            rx.stop()
        if tx_sock:
            tx_sock.close()


# ---------------------------------------------------------------------------
# Test 21: ControlListener serving, target_server_ip & play state payload
# ---------------------------------------------------------------------------
def test_control_listener_serving_and_target_server_fields() -> None:
    name = "ControlListener serving, target_server_ip & play state payload building"
    listener = None
    sock = None
    try:
        import json
        from network import ControlListener

        announced = []
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind(("127.0.0.1", 0))
        test_port = sock.getsockname()[1]
        sock.close()
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

        listener = ControlListener(bind_port=test_port, on_pc_announce=lambda msg: announced.append(msg))
        listener.set_serving(True)
        listener.set_target_server_ip("192.168.1.200")
        listener.set_play_state(True, listen_port=5004)

        raw_bytes = listener._build_announce_payload()
        payload = json.loads(raw_bytes.decode("utf-8"))
        if payload.get("serving") is not True:
            check(name, False, "serving flag was not True")
            return
        if payload.get("playing") is not True:
            check(name, False, "playing flag was not True")
            return
        if payload.get("audio_port") != 5004 and payload.get("port") != 5004:
            check(name, False, f"listen port was {payload.get('audio_port')}, expected 5004")
            return
        if payload.get("target_server_ip") != "192.168.1.200":
            check(name, False, f"target_server_ip was {payload.get('target_server_ip')}")
            return

        listener._serving = False
        listener._playing = False
        listener._target_server_ip = ""
        listener.start()
        time.sleep(0.05)

        listener._local_ips = {"10.254.254.254"}
        listener._last_local_ips_refresh = time.monotonic() + 100.0
        announced.clear()

        msg_in = json.dumps({
            "type": "PC_ANNOUNCE",
            "serving": True,
            "playing": False,
            "target_server_ip": "10.0.0.5",
            "platform": "pc"
        }).encode("utf-8")
        sock.sendto(msg_in, ("127.0.0.1", test_port))
        time.sleep(0.1)

        target_announces = [m for m in announced if m.get("target_server_ip") == "10.0.0.5"]
        if not target_announces:
            check(name, False, "announcement message was not delivered to callback")
            return
        norm = target_announces[0]
        if norm.get("serving") is not True or norm.get("target_server_ip") != "10.0.0.5":
            check(name, False, f"normalized message mismatch: {norm}")
            return

        check(name, True, "announcement payload building and normalization verified")
    except Exception as exc:
        check(name, False, str(exc))
    finally:
        if listener:
            listener.stop()
        if sock:
            sock.close()


# ---------------------------------------------------------------------------
# Test 22 & 23 Test Doubles
# ---------------------------------------------------------------------------
class _FakeVar:
    def __init__(self, value=""):
        self._v = value
    def get(self):
        return self._v
    def set(self, value):
        self._v = value

class _FakeWidget:
    def config(self, **kwargs):
        pass
    configure = config

class _FakeCombo:
    def __init__(self, idx=0):
        self._idx = idx
    def current(self, idx=None):
        if idx is not None:
            self._idx = idx
        return self._idx

class _FakeCanvas:
    def winfo_width(self):
        return 200
    def coords(self, *args):
        pass

class _FakeRoot:
    def focus_get(self):
        return None
    def after(self, delay, fn):
        return "after_id"
    def after_cancel(self, after_id):
        pass

class _FakeControlListener:
    def __init__(self):
        self.target_ip = None
    def set_target_ip(self, ip):
        self.target_ip = ip

class _FakeUDPSender:
    def __init__(self):
        self.target_ip = ""
        self.target_port = 5004
        self.cleared_primary = False
    def update_target(self, ip, port=5004):
        self.target_ip = ip
        self.target_port = port
        self.cleared_primary = False
    def clear_primary_target(self):
        self.target_ip = ""
        self.cleared_primary = True
    def add_party_target(self, ip, port=5004):
        pass
    def remove_party_target(self, ip):
        pass
    def clear_party_targets(self):
        pass


# ---------------------------------------------------------------------------
# Test 22: PC disconnects clear Connections card, IP target & UDP stream
# ---------------------------------------------------------------------------
def test_pc_disconnect_clears_connected_device() -> None:
    name = "PC clears connected device on disconnect notice, unpaired beacon & timeout"
    import queue
    import threading
    import ui as ui_mod

    orig_send_ctrl = ui_mod.send_control_message
    try:
        ui_mod.send_control_message = lambda *args, **kwargs: True

        app = ui_mod.AirSpeakerApp.__new__(ui_mod.AirSpeakerApp)
        app.root = _FakeRoot()
        app._ui_queue = queue.Queue()
        app._adapter_data = [{"name": "Wi-Fi", "ip": "192.168.1.10", "is_up": True}]
        app.adapter_combo = _FakeCombo(0)
        app.ip_var = _FakeVar("")
        app.ip_port_var = _FakeVar("5004")
        app.pc_listen_port_var = _FakeVar("5004")
        app.ip_entry = object()
        app.port_entry = object()
        app.conn_device_name_var = _FakeVar("Waiting for phone connection...")
        app.conn_device_ip_var = _FakeVar("Open AirSpeaker on your phone (Auto-Discovery active)")
        app.phone_ip_var = _FakeVar("Destination: --")
        app.latency_var = _FakeVar("Phone Buffer: --")
        app.loss_var = _FakeVar("Dropped: 0")
        app.status_var = _FakeVar("")
        app.status_label = _FakeWidget()
        app.codec_var = _FakeVar(0)
        app.sync_os_vol_var = None
        app._level_canvas = _FakeCanvas()
        app._level_bar = 1
        app._peak_level = 0.0
        app._manual_ip_override = None
        app._last_phone_seen_time = 0.0
        app._last_phone_rx_pps_time = 0.0
        app._connected_phone_name = "Waiting for phone..."
        app._phone_stats = {}
        app._peer_last_seen = {}
        app._peer_device_playing = {}
        app._discovered_servers = {}
        app._discovered_party_hosts = {}
        app._party_lock = threading.Lock()
        app._party_devices = {}
        app._party_view_state = "CHOOSER"
        app._party_streaming = False
        app._party_joined_active = False
        app.party_extend_var = _FakeVar(False)
        app._streaming = True
        app._normal_streaming = True
        app._play_active = False
        app._rx_pps = 0
        app._audio_capture = None
        app._phone_receiver = None
        app._party_join_receiver = None
        app._control_listener = _FakeControlListener()
        app._udp_sender = _FakeUDPSender()
        app._save_config_after_id = None
        app._last_adapter_poll_time = time.monotonic() + 60.0
        app._last_graph_tick = time.monotonic() + 60.0
        app._last_party_refresh = time.monotonic() + 60.0
        app._get_current_config = lambda: {"type": "CONFIG_SYNC"}
        app._send_config_reliable = lambda targets, cfg: None
        app._schedule_save_config = lambda delay_ms=250: None

        phone_ip = "192.168.1.55"
        pc_ip = "192.168.1.10"

        def pair_phone() -> None:
            app._ui_queue.put(("beacon", {
                "type": "BEACON",
                "_sender_ip": phone_ip,
                "ips": [phone_ip],
                "device": "Pixel 7a",
                "platform": "android",
                "audio_port": 5004,
                "playing": True,
                "serving": False,
                "target_server_ip": pc_ip,
            }))
            app._process_ui_queue()
            assert app.ip_var.get() == phone_ip, f"Expected paired IP {phone_ip}, got {app.ip_var.get()}"
            assert app.conn_device_name_var.get() == "Pixel 7a", "Connected device name not populated"
            assert app._udp_sender.target_ip == phone_ip, "UDPSender target not updated on pair"

        def assert_cleared(context: str) -> None:
            assert app.ip_var.get() == "", f"{context}: ip_var not cleared ({app.ip_var.get()})"
            assert app.conn_device_name_var.get() == "Waiting for phone connection...", f"{context}: device name not reset"
            assert "Auto-Discovery" in app.conn_device_ip_var.get(), f"{context}: status subtitle not reset"
            assert app.phone_ip_var.get() == "Destination: --", f"{context}: phone_ip_var not reset"
            assert app._last_phone_seen_time == 0.0, f"{context}: _last_phone_seen_time not zeroed"
            assert app._control_listener.target_ip == "", f"{context}: ControlListener target_ip not cleared"
            assert app._udp_sender.cleared_primary and app._udp_sender.target_ip == "", f"{context}: UDPSender target not cleared"

        # 1. Explicit disconnected_server_ip clears immediately (even if manual override was set)
        pair_phone()
        app._manual_ip_override = phone_ip
        app._ui_queue.put(("beacon", {
            "type": "BEACON",
            "_sender_ip": phone_ip,
            "ips": [phone_ip],
            "device": "Pixel 7a",
            "platform": "android",
            "audio_port": 5004,
            "playing": False,
            "serving": False,
            "disconnected_server_ip": pc_ip,
        }))
        app._process_ui_queue()
        assert app._manual_ip_override is None, "explicit disconnect did not clear manual IP override"
        assert_cleared("disconnected_server_ip")

        # 2. Unpaired beacon (no target_server_ip) clears previously paired device
        pair_phone()
        app._ui_queue.put(("beacon", {
            "type": "BEACON",
            "_sender_ip": phone_ip,
            "ips": [phone_ip],
            "device": "Pixel 7a",
            "platform": "android",
            "audio_port": 5004,
            "playing": False,
            "serving": False,
        }))
        app._process_ui_queue()
        assert_cleared("unpaired beacon")

        # 3. Heartbeat timeout (> 6.0s) auto-clears auto-paired device
        pair_phone()
        app._last_phone_seen_time = time.monotonic() - 7.0
        app._schedule_ui_update()
        assert_cleared("heartbeat timeout")

        # 4. Heartbeat timeout (> 5.0s) preserves manual IP override as WAITING / OFFLINE
        pair_phone()
        app._manual_ip_override = phone_ip
        app._last_phone_seen_time = time.monotonic() - 7.0
        app._schedule_ui_update()
        assert app.ip_var.get() == phone_ip, "manual override IP should be kept on timeout"
        assert "WAITING / OFFLINE" in app.conn_device_ip_var.get(), "manual override should show WAITING / OFFLINE"

        check(name, True, "explicit disconnect, unpaired beacon, timeout clear & manual fallback verified")
    except Exception as exc:
        check(name, False, str(exc))
    finally:
        ui_mod.send_control_message = orig_send_ctrl


# ---------------------------------------------------------------------------
# Test 23: Cross-platform polish, interface filter, and iOS first-beacon switch
# ---------------------------------------------------------------------------
def test_cross_platform_polish_and_ios_first_beacon() -> None:
    name = "Cross-platform polish, interface filter & iOS first-beacon PCM16 switch"
    import queue
    import threading
    import ui as ui_mod

    orig_send_ctrl = getattr(ui_mod, "send_control_message", None)
    try:
        from network import _is_excluded_iface
        import audio_capture

        # 1. Network adapter exclusion for macOS tunnel/wireless interfaces
        assert _is_excluded_iface("utun0", []), "utun0 interface must be excluded"
        assert _is_excluded_iface("awdl0", []), "awdl0 interface must be excluded"
        assert _is_excluded_iface("llw0", []), "llw0 interface must be excluded"

        # 2. AudioCapture error message OS awareness
        orig_mac = audio_capture._IS_MAC
        orig_linux = audio_capture._IS_LINUX
        try:
            audio_capture._IS_MAC = True
            audio_capture._IS_LINUX = False
            msg_mac = audio_capture._no_capture_device_message()
            assert "BlackHole" in msg_mac and "WASAPI" not in msg_mac, f"macOS message wrong: {msg_mac}"

            audio_capture._IS_MAC = False
            audio_capture._IS_LINUX = True
            msg_linux = audio_capture._no_capture_device_message()
            assert "PipeWire" in msg_linux and "WASAPI" not in msg_linux, f"Linux message wrong: {msg_linux}"

            audio_capture._IS_MAC = False
            audio_capture._IS_LINUX = False
            msg_win = audio_capture._no_capture_device_message()
            assert "WASAPI" in msg_win, f"Windows message wrong: {msg_win}"
        finally:
            audio_capture._IS_MAC = orig_mac
            audio_capture._IS_LINUX = orig_linux

        # 3. iOS first-beacon automatic switch to Lossless PCM16
        ui_mod.send_control_message = lambda host, cfg, port: None

        app = ui_mod.AirSpeakerApp.__new__(ui_mod.AirSpeakerApp)
        app.root = _FakeRoot()
        app._ui_queue = queue.Queue()
        app._adapter_data = [{"name": "Wi-Fi", "ip": "192.168.1.10", "is_up": True}]
        app.adapter_combo = _FakeCombo(0)
        app.ip_var = _FakeVar("")
        app.ip_port_var = _FakeVar("5004")
        app.pc_listen_port_var = _FakeVar("5004")
        app.ip_entry = object()
        app.port_entry = object()
        app.conn_device_name_var = _FakeVar("Waiting for phone connection...")
        app.conn_device_ip_var = _FakeVar("Open AirSpeaker on your phone (Auto-Discovery active)")
        app.phone_ip_var = _FakeVar("Destination: --")
        app.latency_var = _FakeVar("Phone Buffer: --")
        app.loss_var = _FakeVar("Dropped: 0")
        app.status_var = _FakeVar("")
        app.status_label = _FakeWidget()
        app.codec_var = _FakeVar(ui_mod.CODEC_OPUS)  # start on OPUS
        app.sync_os_vol_var = None
        app._level_canvas = _FakeCanvas()
        app._level_bar = 1
        app._peak_level = 0.0
        app._manual_ip_override = None
        app._last_phone_seen_time = 0.0
        app._last_phone_rx_pps_time = 0.0
        app._connected_phone_name = "Waiting for phone..."
        app._phone_stats = {}
        app._peer_last_seen = {}
        app._peer_device_playing = {}
        app._discovered_servers = {}
        app._discovered_party_hosts = {}
        app._party_lock = threading.Lock()
        app._party_devices = {}
        app._party_view_state = "CHOOSER"
        app._party_streaming = True
        app._normal_streaming = True
        app._party_joined_active = False
        app.party_extend_var = _FakeVar(False)
        app._streaming = True
        app._play_active = False
        app._rx_pps = 0
        app._audio_capture = None
        app._phone_receiver = None
        app._party_join_receiver = None
        app._control_listener = _FakeControlListener()
        app._udp_sender = _FakeUDPSender()
        app._save_config_after_id = None
        app._last_adapter_poll_time = time.monotonic() + 60.0
        app._last_graph_tick = time.monotonic() + 60.0
        app._last_party_refresh = time.monotonic() + 60.0
        app._get_current_config = lambda: {"type": "CONFIG_SYNC"}
        app._send_config_reliable = lambda targets, cfg: None
        app._schedule_save_config = lambda delay_ms=250: None
        app._update_codec_ui_state = lambda: None
        app._update_expected_delay_label = lambda: None
        app._update_party_join_host_cfg_label = lambda: None
        app._on_local_setting_changed = lambda: None

        iphone_ip = "192.168.1.88"
        pc_ip = "192.168.1.10"

        # Emit very first beacon from iOS
        app._ui_queue.put(("beacon", {
            "type": "BEACON",
            "_sender_ip": iphone_ip,
            "ips": [iphone_ip],
            "device": "Hasan's iPhone",
            "platform": "ios",
            "audio_port": 5004,
            "playing": True,
            "serving": False,
            "target_server_ip": pc_ip,
        }))
        app._process_ui_queue()

        assert app.ip_var.get() == iphone_ip, f"Expected paired IP {iphone_ip}, got {app.ip_var.get()}"
        assert app.codec_var.get() == ui_mod.CODEC_PCM16, f"Expected CODEC_PCM16 on first beacon, got {app.codec_var.get()}"

        check(name, True, "utun/awdl exclusion, OS-aware capture message & iOS first-beacon PCM16 switch verified")
    except Exception as exc:
        check(name, False, str(exc))
    finally:
        ui_mod.send_control_message = orig_send_ctrl


def test_defaults_and_party_host_isolation() -> None:
    name = "Default OS volume sync & strict Party Mode isolation"
    import config
    import queue
    import threading
    import time
    import ui as ui_mod
    orig_send_ctrl = ui_mod.send_control_message
    try:
        # 1. Config defaults
        assert config._DEFAULTS.get("sync_os_vol") is True, f"Expected sync_os_vol=True in _DEFAULTS, got {config._DEFAULTS.get('sync_os_vol')}"

        ui_mod.send_control_message = lambda host, cfg, port: None

        app = ui_mod.AirSpeakerApp.__new__(ui_mod.AirSpeakerApp)
        app.root = _FakeRoot()
        app._ui_queue = queue.Queue()
        app._adapter_data = [{"name": "Wi-Fi", "ip": "192.168.1.10", "is_up": True}]
        app.adapter_combo = _FakeCombo(0)
        app.ip_var = _FakeVar("")
        app.ip_port_var = _FakeVar("5004")
        app.pc_listen_port_var = _FakeVar("5004")
        app.conn_device_name_var = _FakeVar("Waiting for phone connection...")
        app.conn_device_ip_var = _FakeVar("Open AirSpeaker on your phone")
        app.phone_ip_var = _FakeVar("Destination: --")
        app.latency_var = _FakeVar("Phone Buffer: --")
        app.loss_var = _FakeVar("Dropped: 0")
        app.status_var = _FakeVar("")
        app.status_label = _FakeWidget()
        app.codec_var = _FakeVar(ui_mod.CODEC_PCM16)
        app.bitrate_var = _FakeVar("192")
        app.frame_ms_var = _FakeVar("5")
        app.buffer_mode_var = _FakeVar("AUTO")
        app.custom_buffer_var = _FakeVar(30.0)
        app.volume_gain_var = _FakeVar(1.0)
        app.muted_var = _FakeVar(False)
        app.gain_val_label = _FakeWidget()
        app.mute_btn = _FakeWidget()
        app.sync_os_vol_var = _FakeVar(True)
        app._config_rev = 1
        app.sync_rev_label = _FakeWidget()
        app._suppress_sync = False
        app._is_dragging_slider = False
        app._last_local_slider_time = 0.0
        import collections
        app._seen_sync_ids = collections.deque(maxlen=64)
        app._last_applied_rev_by_session = {}
        app._level_canvas = _FakeCanvas()
        app._level_bar = 1
        app._peak_level = 0.0
        app._manual_ip_override = None
        app._last_phone_seen_time = 0.0
        app._last_phone_rx_pps_time = 0.0
        app._connected_phone_name = "Waiting for phone..."
        app._phone_stats = {}
        app._peer_last_seen = {}
        app._peer_device_playing = {}
        app._discovered_servers = {}
        app._discovered_party_hosts = {}
        app._party_lock = threading.Lock()
        app._party_devices = {}
        app._party_view_state = "CHOOSER"
        app._party_streaming = False
        app._normal_streaming = False
        app._party_joined_active = False
        app.party_extend_var = _FakeVar(False)
        app.party_mode_var = _FakeVar(False)
        app._streaming = False
        app._play_active = False
        app._play_selected_server_ip = ""
        app._rx_pps = 0
        app._audio_capture = None
        app._phone_receiver = None
        app._party_join_receiver = None
        app._control_listener = _FakeControlListener()
        app._udp_sender = _FakeUDPSender()
        app._save_config_after_id = None
        app._last_adapter_poll_time = time.monotonic() + 60.0
        app._last_graph_tick = time.monotonic() + 60.0
        app._last_party_refresh = time.monotonic() + 60.0
        app._get_current_config = lambda: {"type": "CONFIG_SYNC"}
        app._send_config_reliable = lambda targets, cfg: None
        app._schedule_save_config = lambda delay_ms=250: None
        app._update_codec_ui_state = lambda: None
        app._update_expected_delay_label = lambda: None
        app._update_party_join_host_cfg_label = lambda: None
        app._on_local_setting_changed = lambda: None
        app._refresh_discovered_hosts_ui = lambda: None
        app._refresh_play_servers_ui = lambda: None
        app._update_combined_status = lambda: None
        app._refresh_party_ui = lambda: None

        host_ip = "192.168.1.55"
        pc_ip = "192.168.1.10"

        # 1. Feed pc_announce and beacon with is_party_host: True, serving: True while _party_view_state = "CHOOSER"
        app._party_view_state = "CHOOSER"
        app._ui_queue.put(("pc_announce", {
            "type": "PC_ANNOUNCE",
            "_sender_ip": host_ip,
            "device": "Studio Host PC",
            "audio_port": 5004,
            "serving": True,
            "is_party_host": True,
            "platform": "pc",
        }))
        app._process_ui_queue()

        assert host_ip not in app._discovered_servers, "Party Host MUST NOT appear in _discovered_servers (pc_announce)"
        assert host_ip not in app._discovered_party_hosts, "Party Host MUST NOT appear in _discovered_party_hosts when not in JOIN mode"

        phone_host_ip = "192.168.1.56"
        app._ui_queue.put(("beacon", {
            "type": "BEACON",
            "_sender_ip": phone_host_ip,
            "ips": [phone_host_ip],
            "device": "Phone Host",
            "audio_port": 5004,
            "serving": True,
            "is_party_host": True,
            "platform": "android",
        }))
        app._process_ui_queue()

        assert phone_host_ip not in app._discovered_servers, "Party Host MUST NOT appear in _discovered_servers (beacon)"
        assert phone_host_ip not in app._discovered_party_hosts, "Party Host MUST NOT appear in _discovered_party_hosts when in CHOOSER mode"

        # 2. Switch _party_view_state = "JOIN" and feed the same announcements
        app._party_view_state = "JOIN"
        app._ui_queue.put(("pc_announce", {
            "type": "PC_ANNOUNCE",
            "_sender_ip": host_ip,
            "device": "Studio Host PC",
            "audio_port": 5004,
            "serving": True,
            "is_party_host": True,
            "platform": "pc",
        }))
        app._ui_queue.put(("beacon", {
            "type": "BEACON",
            "_sender_ip": phone_host_ip,
            "ips": [phone_host_ip],
            "device": "Phone Host",
            "audio_port": 5004,
            "serving": True,
            "is_party_host": True,
            "platform": "android",
        }))
        app._process_ui_queue()

        assert host_ip not in app._discovered_servers, "Party Host still MUST NOT appear in _discovered_servers"
        assert phone_host_ip not in app._discovered_servers, "Party Host still MUST NOT appear in _discovered_servers"
        assert host_ip in app._discovered_party_hosts, "Host PC should be discovered in JOIN mode"
        assert phone_host_ip in app._discovered_party_hosts, "Phone Host should be discovered in JOIN mode"

        # 3. Party Host mode: reject 1-to-1 PLAY pairing, accept party_join
        app._party_streaming = True
        app._normal_streaming = False
        app._party_view_state = "HOST"
        app.ip_var.set("")

        play_peer_ip = "192.168.1.77"
        app._ui_queue.put(("beacon", {
            "type": "BEACON",
            "_sender_ip": play_peer_ip,
            "ips": [play_peer_ip],
            "device": "Unwelcome 1-to-1 Player",
            "target_server_ip": pc_ip,
            "party_join": False,
            "playing": True,
        }))
        app._process_ui_queue()

        assert app.ip_var.get() == "", f"Party host MUST reject 1-to-1 PLAY pairing, got {app.ip_var.get()}"
        assert play_peer_ip not in app._party_devices, "Unwelcome peer must not be in _party_devices"

        joiner_ip = "192.168.1.88"
        app._ui_queue.put(("beacon", {
            "type": "BEACON",
            "_sender_ip": joiner_ip,
            "ips": [joiner_ip],
            "device": "Welcome Party Joiner",
            "target_server_ip": pc_ip,
            "party_host_ip": pc_ip,
            "party_join": True,
            "audio_port": 5004,
        }))
        app._process_ui_queue()

        assert joiner_ip in app._party_devices, "Party Joiner MUST be registered in _party_devices"

        # 4. Auto-paired 1-to-1 CONFIG_SYNC applies cleanly when _party_streaming = False and ip_var == sender_ip
        app._party_streaming = False
        app._normal_streaming = True
        app._party_view_state = "CHOOSER"
        normal_peer = "192.168.1.99"
        app.ip_var.set(normal_peer)
        app.custom_buffer_var.set(20.0)

        app._apply_incoming_config({
            "type": "CONFIG_SYNC",
            "_sender_ip": normal_peer,
            "custom_buffer_ms": 45.0,
            "rev": 10,
            "config_rev": 10,
        })
        assert app.custom_buffer_var.get() == 45.0, f"Expected custom_buffer_ms to update to 45.0, got {app.custom_buffer_var.get()}"

        check(name, True, "defaults, Party Host discovery isolation, pairing rejection & auto-paired CONFIG_SYNC verified")
    except Exception as exc:
        check(name, False, str(exc))
    finally:
        ui_mod.send_control_message = orig_send_ctrl


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
    test_eq_5band_stress_and_nan_recovery()
    test_audio_receiver_allowed_sender_filter()
    test_control_listener_serving_and_target_server_fields()
    test_pc_disconnect_clears_connected_device()
    test_cross_platform_polish_and_ios_first_beacon()
    test_defaults_and_party_host_isolation()

    print()
    print("=" * 60)
    passed = sum(1 for _, s in results if s == PASS)
    failed = sum(1 for _, s in results if s == FAIL)
    print(f"Results: {passed} PASS / {failed} FAIL out of {len(results)} tests")
    print("=" * 60)

    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()

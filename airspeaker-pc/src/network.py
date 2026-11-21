"""
network.py — UDP sender, control listener, adapter enumeration, and frame pacing.
"""

import ipaddress
import json
import logging
import math
import queue
import re
import socket
import threading
import time
from typing import Any, Callable, Dict, List, Optional

import numpy as np
import psutil

from equalizer import StereoFiveBandBiquadDSP
from protocol import (
    AUDIO_PORT,
    CONTROL_PORT,
    CODEC_PCM16,
    CODEC_OPUS,
    SILENCE_PCM_5MS,
    SILENCE_PCM_10MS,
    SILENCE_PCM_20MS,
    build_header,
    build_pcm_packet,
    build_silence_packet,
    current_timestamp_ms,
)

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Windows timer resolution
# ---------------------------------------------------------------------------
_winmm = None
try:
    import ctypes
    _winmm = ctypes.windll.winmm
except Exception:
    pass


def set_timer_resolution(period_ms: int = 1) -> None:
    if _winmm:
        _winmm.timeBeginPeriod(period_ms)


def clear_timer_resolution(period_ms: int = 1) -> None:
    if _winmm:
        _winmm.timeEndPeriod(period_ms)


# ---------------------------------------------------------------------------
# Adapter filtering
# ---------------------------------------------------------------------------

_EXCLUDE_PATTERNS = [
    "virtualbox", "host-only", "vmware", "vethernet", "hyper-v",
    "wsl", "loopback",
]

_SHORT_EXCLUDE_TOKENS = ["tap", "tun", "vpn"]

_EXCLUDE_IP_PREFIXES = [
    "192.168.56.",
    "169.254.",
    "127.",
    "::1",
]


def _is_excluded_iface(name: str, addrs) -> bool:
    name_lower = name.lower()
    for pat in _EXCLUDE_PATTERNS:
        if pat in name_lower:
            return True
    # Match short tokens 'tap', 'tun', 'vpn' as whole words or prefix/suffix tokens
    tokens = re.findall(r"[a-zA-Z0-9]+", name_lower)
    for tok in tokens:
        for st in _SHORT_EXCLUDE_TOKENS:
            if tok == st or tok.startswith(st):
                return True
    # Check IPs on this interface
    for addr in addrs:
        ip = getattr(addr, "address", "") or ""
        for prefix in _EXCLUDE_IP_PREFIXES:
            if ip.startswith(prefix):
                return True
    return False


def list_real_adapters() -> List[Dict[str, Any]]:
    """
    Enumerate real network adapters (Wi-Fi, Ethernet, USB RNDIS, Mobile Hotspot),
    excluding virtual/VM/loopback interfaces.

    Returns list of dicts: {name, ip, netmask, broadcast, is_up}
    """
    result = []
    try:
        all_addrs = psutil.net_if_addrs()
        all_stats = psutil.net_if_stats()

        for iface_name, addrs in all_addrs.items():
            if _is_excluded_iface(iface_name, addrs):
                continue

            stats = all_stats.get(iface_name)
            is_up = stats.isup if stats else False

            # Collect IPv4 addresses
            for a in addrs:
                if a.family == socket.AF_INET:
                    ip = a.address or ""
                    if (
                        ip
                        and not ip.startswith("127.")
                        and not ip.startswith("169.254.")
                        and not ip.startswith("192.168.56.")
                    ):
                        netmask = getattr(a, "netmask", "") or "255.255.255.0"
                        bcast = None
                        try:
                            bcast = str(ipaddress.IPv4Interface(f"{ip}/{netmask}").network.broadcast_address)
                        except Exception:
                            prefix = ".".join(ip.split(".")[:3])
                            bcast = f"{prefix}.255"

                        result.append({
                            "name": iface_name,
                            "ip": ip,
                            "netmask": netmask,
                            "broadcast": bcast,
                            "is_up": is_up,
                        })
        result.sort(key=lambda a: (not a["is_up"], a["name"]))
    except Exception as exc:
        logger.error("Error enumerating adapters: %s", exc)

    return result


def get_active_broadcast_targets(port: int = AUDIO_PORT) -> List[tuple]:
    """Return broadcast (ip, port) targets for UP network adapters + global 255.255.255.255."""
    targets = [("255.255.255.255", port)]
    for a in list_real_adapters():
        if not a.get("is_up"):
            continue
        bcast = a.get("broadcast")
        if not bcast:
            ip = a.get("ip", "")
            parts = ip.split(".")
            if len(parts) == 4 and not parts[0].startswith("127"):
                bcast = f"{parts[0]}.{parts[1]}.{parts[2]}.255"
        if bcast and (bcast, port) not in targets:
            targets.append((bcast, port))
    return targets


# ---------------------------------------------------------------------------
# Bounded audio frame ring buffer (lock-free via queue)
# ---------------------------------------------------------------------------

QUEUE_MAX_FRAMES = 10  # ~50ms low-latency cushion at 5ms/frame (Gaming / Low-latency default)


class AudioQueue:
    """Thread-safe bounded queue for audio frames. Drops oldest on overflow."""

    def __init__(self, maxsize: int = QUEUE_MAX_FRAMES):
        self._q: queue.Queue = queue.Queue(maxsize=maxsize)
        self.maxsize: int = maxsize
        self.capture_overflow_drops: int = 0

    def put(self, frame: bytes) -> None:
        try:
            self._q.put_nowait(frame)
            return
        except queue.Full:
            pass

        try:
            self._q.get_nowait()
            self.capture_overflow_drops += 1
        except queue.Empty:
            pass

        try:
            self._q.put_nowait(frame)
        except queue.Full:
            self.capture_overflow_drops += 1

    def put_nowait(self, frame: bytes) -> None:
        self.put(frame)

    def put_latest(self, frame: bytes) -> None:
        self.put(frame)

    def get_nowait(self) -> Optional[bytes]:
        try:
            return self._q.get_nowait()
        except queue.Empty:
            return None

    def get(self, timeout: float = 0.005) -> Optional[bytes]:
        try:
            return self._q.get(timeout=timeout)
        except queue.Empty:
            return None

    def qsize(self) -> int:
        return self._q.qsize()

    def empty(self) -> bool:
        return self._q.empty()

    def full(self) -> bool:
        return self._q.full()

    def clear(self) -> None:
        while True:
            try:
                self._q.get_nowait()
            except queue.Empty:
                break


# ---------------------------------------------------------------------------
# UDP sender with pacing
# ---------------------------------------------------------------------------

class UDPSender:
    """
    Sends audio frames over UDP with strict 5ms pacing.

    - Pulls frames from AudioQueue
    - Synthesizes silence when no audio for ≥10ms
    - Runs on a dedicated thread
    """

    def __init__(
        self,
        audio_queue: AudioQueue,
        opus_encoder=None,  # Optional OpusEncoder instance
        on_stats: Optional[Callable[[int], None]] = None,  # pps callback
    ):
        self._audio_queue = audio_queue
        self._opus_encoder = opus_encoder
        self._on_stats = on_stats

        self._target_ip: str = ""
        self._target_port: int = AUDIO_PORT
        self._party_mode: bool = False
        self._party_targets: Dict[str, int] = {}  # ip -> port
        self._codec: int = CODEC_PCM16
        self._frame_ms: int = 5
        self._bind_ip: str = ""

        self._sock: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        self._running = False
        self._seq: int = 0
        self._pps_counter: int = 0
        self._pps_timer: float = 0.0
        self._muted: bool = False
        self._volume_gain: float = 1.0
        self._send_enabled: bool = True
        self._tx_eq_dsp = StereoFiveBandBiquadDSP(48000)
        self._lock = threading.Lock()

    def set_eq_gains(self, enabled: bool, gains_db: List[float]) -> None:
        self._tx_eq_dsp.set_gains(gains_db, enabled=enabled)

    def set_party_mode(self, enabled: bool) -> None:
        with self._lock:
            self._party_mode = bool(enabled)

    def add_party_target(self, ip: str, port: int = AUDIO_PORT) -> None:
        ip = (ip or "").strip()
        if not ip or ip in ("0.0.0.0", "255.255.255.255"):
            return
        if ":" in ip:
            parts = ip.rsplit(":", 1)
            ip = parts[0]
            if parts[1].isdigit():
                port = int(parts[1])
        with self._lock:
            self._party_targets[ip] = int(port)

    def remove_party_target(self, ip: str) -> None:
        with self._lock:
            self._party_targets.pop(ip, None)

    def clear_party_targets(self) -> None:
        with self._lock:
            self._party_targets.clear()

    def get_party_targets(self) -> List[str]:
        with self._lock:
            ips = list(self._party_targets.keys())
            if self._target_ip and self._target_ip not in ips:
                ips.insert(0, self._target_ip)
            return ips

    def configure(
        self,
        target_ip: str,
        target_port: int = AUDIO_PORT,
        codec: int = CODEC_PCM16,
        frame_ms: int = 5,
        bind_ip: str = "",
        is_party_host: bool = False,
    ) -> None:
        with self._lock:
            self._target_ip = "" if is_party_host else target_ip
            self._target_port = target_port
            self._party_targets.clear()
            self._codec = codec
            self._frame_ms = frame_ms
            self._bind_ip = bind_ip

    def start(self) -> None:
        if self._running:
            return
        self._tx_eq_dsp.reset()
        self._running = True
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 262144)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        try:
            self._sock.setsockopt(socket.IPPROTO_IP, socket.IP_TOS, 0xB8)
        except Exception:
            pass

        with self._lock:
            target_ip = self._target_ip

        # Leave unbound (0.0.0.0) so the OS kernel routes UDP 5004 packets
        # over the exact same interface and route as UDP 5005 control packets.
        logger.info("UDPSender left unbound (0.0.0.0) for OS routing to %s", target_ip)

        self._thread = threading.Thread(
            target=self._run, name="UDPSender", daemon=True
        )
        self._thread.start()
        logger.info("UDPSender started")

    def clear_primary_target(self) -> None:
        """Clear primary unicast streaming target while keeping any party targets intact."""
        with self._lock:
            self._target_ip = ""

    def update_target(self, target_ip: str, target_port: int = AUDIO_PORT) -> None:
        with self._lock:
            old_ip = self._target_ip
            self._target_ip = target_ip
            self._target_port = target_port
            if self._sock:
                try:
                    self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                except Exception:
                    pass
        logger.info("UDPSender target updated to %s:%d (was %s)", target_ip, target_port, old_ip)

    def set_codec_and_bitrate(self, codec: int, bitrate_bps: int, frame_ms: int) -> None:
        with self._lock:
            frame_changed = (self._frame_ms != frame_ms)
            self._codec = codec
            self._frame_ms = frame_ms
            if frame_changed:
                self._audio_queue.clear()
            if codec == CODEC_OPUS:
                if self._opus_encoder and self._opus_encoder.available:
                    try:
                        self._opus_encoder.set_bitrate(bitrate_bps)
                        self._opus_encoder.set_frame_ms(frame_ms)
                    except Exception as e:
                        logger.warning("Error updating Opus encoder: %s", e)
                else:
                    try:
                        from opus_codec import OpusEncoder
                        self._opus_encoder = OpusEncoder(bitrate=bitrate_bps, frame_ms=frame_ms)
                    except Exception as e:
                        logger.warning("Error creating Opus encoder: %s", e)

    @staticmethod
    def _safe_gain(gain: float) -> float:
        val = float(gain)
        return max(0.0, min(2.0, val)) if math.isfinite(val) else 1.0

    def set_routing_and_gain(self, send_enabled: bool, gain: float, muted: bool) -> None:
        safe_g = self._safe_gain(gain)
        with self._lock:
            was_silent = self._muted or self._volume_gain <= 0.001
            self._send_enabled = bool(send_enabled)
            self._volume_gain = safe_g
            self._muted = bool(muted)
        if (bool(muted) or safe_g <= 0.001) and not was_silent:
            self._tx_eq_dsp.reset()

    def set_gain_and_mute(self, gain: float, muted: bool, send_enabled: bool = True) -> None:
        safe_g = self._safe_gain(gain)
        with self._lock:
            was_silent = self._muted or self._volume_gain <= 0.001
            self._volume_gain = safe_g
            self._muted = bool(muted)
            self._send_enabled = bool(send_enabled)
        if (bool(muted) or safe_g <= 0.001) and not was_silent:
            self._tx_eq_dsp.reset()

    def set_volume_gain(self, gain: float) -> None:
        safe_g = self._safe_gain(gain)
        with self._lock:
            was_silent = self._muted or self._volume_gain <= 0.001
            self._volume_gain = safe_g
        if safe_g <= 0.001 and not was_silent:
            self._tx_eq_dsp.reset()

    def set_muted(self, muted: bool) -> None:
        with self._lock:
            was_muted = self._muted
            self._muted = bool(muted)
        if bool(muted) and not was_muted:
            self._tx_eq_dsp.reset()

    def stop(self) -> None:
        self._running = False
        if self._thread:
            self._thread.join(timeout=2.0)
            self._thread = None
        if self._sock:
            try:
                self._sock.close()
            except Exception:
                pass
            self._sock = None
        with self._lock:
            if self._opus_encoder is not None:
                try:
                    self._opus_encoder.destroy()
                except Exception:
                    pass
                self._opus_encoder = None
        logger.info("UDPSender stopped")

    def _next_seq(self) -> int:
        self._seq = (self._seq + 1) & 0xFFFFFFFF
        return self._seq

    def _build_packet(self, pcm_frame: bytes) -> bytes:
        seq = self._next_seq()
        ts = current_timestamp_ms()
        with self._lock:
            codec = self._codec
            muted = self._muted
            gain = self._volume_gain
            frame_ms = self._frame_ms

        expected_pcm_len = ((48000 * frame_ms) // 1000) * 4
        if len(pcm_frame) != expected_pcm_len:
            if len(pcm_frame) > expected_pcm_len:
                pcm_frame = pcm_frame[:expected_pcm_len]
            else:
                pcm_frame = pcm_frame + b'\x00' * (expected_pcm_len - len(pcm_frame))

        if not muted and gain > 0.001:
            pcm_frame = self._tx_eq_dsp.process_pcm16(pcm_frame, 2)

        if muted or gain <= 0.001:
            pcm_frame = b'\x00' * len(pcm_frame)
        elif abs(gain - 1.0) > 0.01:
            try:
                norm = (np.frombuffer(pcm_frame, dtype="<i2").astype(np.float32) * gain) / 32767.0
                if gain > 1.0:
                    knee = 0.85
                    above = norm > knee
                    below = norm < -knee
                    norm = np.where(above, knee + (1.0 - knee) * np.tanh((norm - knee) / (1.0 - knee)), norm)
                    norm = np.where(below, -knee - (1.0 - knee) * np.tanh((-norm - knee) / (1.0 - knee)), norm)
                else:
                    norm = np.clip(norm, -1.0, 1.0)
                pcm_frame = np.rint(norm * 32767.0).astype("<i2").tobytes()
            except Exception:
                pass

        encoder = None
        with self._lock:
            if codec == CODEC_OPUS and self._opus_encoder and self._opus_encoder.available:
                encoder = self._opus_encoder

        if encoder is not None:
            encoded = encoder.encode(pcm_frame)
            if encoded is not None:
                header = build_header(seq, ts, CODEC_OPUS)
                return header + encoded
            logger.warning("Opus encode failed, sending PCM")

        header = build_header(seq, ts, CODEC_PCM16)
        return header + pcm_frame

    def _run(self) -> None:
        """Main sender loop — steady 5ms pacing without GIL spin or burst drops."""
        set_timer_resolution(1)
        try:
            with self._lock:
                frame_ms = self._frame_ms
            interval = frame_ms / 1000.0
            silence_threshold = 0.085
            silence_frame = (
                SILENCE_PCM_20MS if frame_ms == 20
                else (SILENCE_PCM_10MS if frame_ms == 10 else SILENCE_PCM_5MS)
            )

            next_tick = time.monotonic()
            last_audio_time = time.monotonic()
            pps_window_start = time.monotonic()
            pps_count = 0

            while self._running:
                with self._lock:
                    target_ip = self._target_ip
                    target_port = self._target_port
                    party_mode = self._party_mode
                    if party_mode and self._party_targets:
                        targets_snapshot = list(self._party_targets.items())
                        if (
                            target_ip
                            and target_ip not in ("0.0.0.0", "255.255.255.255")
                            and (target_ip, target_port) not in targets_snapshot
                        ):
                            targets_snapshot.insert(0, (target_ip, target_port))
                    elif target_ip:
                        if target_ip == "255.255.255.255":
                            targets_snapshot = get_active_broadcast_targets(target_port)
                        else:
                            targets_snapshot = [(target_ip, target_port)]
                    else:
                        targets_snapshot = []
                    send_enabled = self._send_enabled
                    sock = self._sock
                    if self._frame_ms != frame_ms:
                        frame_ms = self._frame_ms
                        interval = frame_ms / 1000.0
                        silence_threshold = 0.085
                        silence_frame = (
                            SILENCE_PCM_20MS if frame_ms == 20
                            else (SILENCE_PCM_10MS if frame_ms == 10 else SILENCE_PCM_5MS)
                        )
                        next_tick = time.monotonic()

                if not targets_snapshot or sock is None:
                    time.sleep(0.010)
                    next_tick = time.monotonic()
                    continue

                # Pull next audio frame; if empty, block up to `interval` (5ms) releasing the GIL
                frame = self._audio_queue.get_nowait()
                if frame is None:
                    frame = self._audio_queue.get(timeout=interval)

                now = time.monotonic()
                if frame is not None:
                    last_audio_time = now
                    packet = self._build_packet(frame) if send_enabled else None
                else:
                    # We already waited `interval` (5ms) inside get(timeout=interval)
                    if (now - last_audio_time) >= silence_threshold and send_enabled:
                        packet = self._build_packet(silence_frame)
                    else:
                        packet = None
                    next_tick = now

                if packet is not None and send_enabled:
                    sent_any = False
                    for t_ip, t_port in targets_snapshot:
                        try:
                            sock.sendto(packet, (t_ip, t_port))
                            sent_any = True
                        except OSError as exc:
                            # Only recreate socket on bad descriptor / broken socket, not transient unreachable routes
                            if getattr(exc, "errno", None) in (9, 10038):
                                logger.warning("UDP socket invalidated (%s), recreating socket", exc)
                                try:
                                    with self._lock:
                                        if self._sock:
                                            try:
                                                self._sock.close()
                                            except Exception:
                                                pass
                                        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                                        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 262144)
                                        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                                        try:
                                            self._sock.setsockopt(socket.IPPROTO_IP, socket.IP_TOS, 0xB8)
                                        except Exception:
                                            pass
                                        sock = self._sock
                                    sock.sendto(packet, (t_ip, t_port))
                                    sent_any = True
                                except Exception as retry_exc:
                                    logger.debug("Retry send error: %s", retry_exc)
                            else:
                                logger.debug("Transient send error to %s:%d: %s", t_ip, t_port, exc)
                        except Exception as exc:
                            logger.debug("Send error to %s:%d: %s", t_ip, t_port, exc)
                    if sent_any:
                        pps_count += 1

                # PPS stat reporting (every 1 second)
                now_stat = time.monotonic()
                elapsed = now_stat - pps_window_start
                if elapsed >= 1.0:
                    if self._on_stats:
                        try:
                            self._on_stats(int(round(pps_count / elapsed)))
                        except Exception:
                            pass
                    pps_count = 0
                    pps_window_start = now_stat

                if frame is not None:
                    next_tick += interval
                    now_after = time.monotonic()
                    sleep_time = next_tick - now_after
                    if sleep_time > 0:
                        time.sleep(sleep_time)
                    elif sleep_time < -0.040:
                        # Only re-anchor if more than 40ms (8 frames) behind
                        next_tick = now_after
        finally:
            clear_timer_resolution(1)


# ---------------------------------------------------------------------------
# Control / Discovery listener (port 5005)
# ---------------------------------------------------------------------------

class ControlListener:
    """
    Listens on UDP port 5005 for BEACON, STATS, CONFIG_SYNC, and PC_ANNOUNCE messages.
    """

    def __init__(
        self,
        on_beacon: Optional[Callable[[dict], None]] = None,
        on_stats: Optional[Callable[[dict], None]] = None,
        on_config_sync: Optional[Callable[[dict], None]] = None,
        on_pc_announce: Optional[Callable[[dict], None]] = None,
        on_party_kick: Optional[Callable[[dict], None]] = None,
        bind_port: int = CONTROL_PORT,
        port: Optional[int] = None,
    ):
        self._on_beacon = on_beacon
        self._on_stats = on_stats
        self._on_config_sync = on_config_sync
        self._on_pc_announce = on_pc_announce
        self._on_party_kick = on_party_kick
        self._bind_port = port if port is not None else bind_port
        self._sock: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        self._announce_thread: Optional[threading.Thread] = None
        self._running = False
        self._party_mode_enabled: bool = False
        self._is_extended_host: bool = False
        self._target_ip: str = ""
        self._local_ips: set = set()
        self._last_local_ips_refresh: float = 0.0

    def _get_local_ips(self) -> set:
        now = time.monotonic()
        if now - self._last_local_ips_refresh > 5.0 or not self._local_ips:
            ips = {"127.0.0.1", "0.0.0.0"}
            for a in list_real_adapters():
                ip = a.get("ip")
                if ip:
                    ips.add(ip)
            try:
                hostname = socket.gethostname()
                for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
                    ips.add(info[4][0])
            except Exception:
                pass
            self._local_ips = ips
            self._last_local_ips_refresh = now
        return self._local_ips

    def set_party_mode(self, enabled: bool, is_extended: bool = False) -> None:
        self._party_mode_enabled = bool(enabled)
        self._is_extended_host = bool(is_extended)
        self.broadcast_announce()

    def set_target_ip(self, ip: str) -> None:
        self._target_ip = (ip or "").strip()

    def broadcast_announce(self) -> None:
        """Broadcast PC_ANNOUNCE immediately to all adapters and broadcast addresses."""
        try:
            msg = json.dumps({
                "type": "PC_ANNOUNCE",
                "app": "AirSpeakerPC",
                "version": 1,
                "pc_name": socket.gethostname(),
                "party_mode": bool(self._party_mode_enabled),
                "is_party_host": bool(self._party_mode_enabled and not self._is_extended_host),
                "is_extended_host": bool(self._party_mode_enabled and self._is_extended_host),
            }).encode("utf-8")
            sock = self._sock
            if sock is not None:
                if self._target_ip and not self._target_ip.startswith("127."):
                    try:
                        sock.sendto(msg, (self._target_ip, self._bind_port))
                    except Exception:
                        pass
                try:
                    sock.sendto(msg, ("255.255.255.255", self._bind_port))
                except Exception:
                    pass
                for a in list_real_adapters():
                    if not a.get("is_up"):
                        continue
                    bcast = a.get("broadcast")
                    if not bcast:
                        ip = a.get("ip", "")
                        parts = ip.split(".")
                        if len(parts) == 4 and not parts[0].startswith("127"):
                            bcast = f"{parts[0]}.{parts[1]}.{parts[2]}.255"
                    if bcast:
                        try:
                            sock.sendto(msg, (bcast, self._bind_port))
                        except Exception:
                            pass
        except Exception:
            pass

    def start(self) -> None:
        if self._running:
            return
        try:
            self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            self._sock.bind(("", self._bind_port))
            self._sock.settimeout(0.5)
        except Exception as exc:
            logger.error("Failed to bind control port %d: %s", self._bind_port, exc)
            return
        self._running = True
        self._thread = threading.Thread(
            target=self._run, name="ControlListener", daemon=True
        )
        self._thread.start()
        self._announce_thread = threading.Thread(
            target=self._announce_loop, name="ControlAnnouncer", daemon=True
        )
        self._announce_thread.start()
        logger.info("ControlListener started on port %d", self._bind_port)

    def stop(self) -> None:
        self._running = False
        if self._thread:
            self._thread.join(timeout=2.0)
            self._thread = None
        if self._announce_thread:
            self._announce_thread.join(timeout=1.0)
            self._announce_thread = None
        if self._sock:
            self._sock.close()
            self._sock = None

    def _announce_loop(self) -> None:
        while self._running:
            try:
                msg = json.dumps({
                    "type": "PC_ANNOUNCE",
                    "app": "AirSpeakerPC",
                    "version": 1,
                    "pc_name": socket.gethostname(),
                    "party_mode": bool(self._party_mode_enabled),
                    "is_party_host": bool(self._party_mode_enabled and not self._is_extended_host),
                    "is_extended_host": bool(self._party_mode_enabled and self._is_extended_host),
                }).encode("utf-8")
                adapters = list_real_adapters()
                sock = self._sock
                if sock is not None:
                    # 1. Direct unicast to manually configured Phone IP if set
                    if self._target_ip and not self._target_ip.startswith("127."):
                        try:
                            sock.sendto(msg, (self._target_ip, self._bind_port))
                        except Exception:
                            pass
                    # 2. Global broadcast
                    try:
                        sock.sendto(msg, ("255.255.255.255", self._bind_port))
                    except Exception:
                        pass
                    # 3. Subnet broadcast for each adapter
                    for a in adapters:
                        if not a.get("is_up"):
                            continue
                        bcast = a.get("broadcast")
                        if not bcast:
                            ip = a.get("ip", "")
                            parts = ip.split(".")
                            if len(parts) == 4 and not parts[0].startswith("127"):
                                prefix = f"{parts[0]}.{parts[1]}.{parts[2]}"
                                bcast = f"{prefix}.255"
                        if bcast:
                            try:
                                sock.sendto(msg, (bcast, self._bind_port))
                            except Exception:
                                pass
            except Exception as exc:
                logger.debug("Announce loop error: %s", exc)

            for _ in range(10):
                if not self._running:
                    break
                time.sleep(0.1)

    def _run(self) -> None:
        while self._running:
            sock = self._sock
            if sock is None:
                break
            try:
                data, addr = sock.recvfrom(4096)
                sender_ip = addr[0]
                if sender_ip in self._get_local_ips():
                    continue

                try:
                    msg = json.loads(data.decode("utf-8"))
                    msg["_sender_ip"] = sender_ip
                    msg_type = msg.get("type", "")
                    if msg_type in ("BEACON", "STATS", "CONFIG_SYNC"):
                        try:
                            reply = json.dumps({
                                "type": "PC_ANNOUNCE",
                                "app": "AirSpeakerPC",
                                "version": 1,
                                "pc_name": socket.gethostname(),
                                "party_mode": bool(self._party_mode_enabled),
                                "is_party_host": bool(self._party_mode_enabled and not self._is_extended_host),
                                "is_extended_host": bool(self._party_mode_enabled and self._is_extended_host),
                            }).encode("utf-8")
                            sock.sendto(reply, (sender_ip, self._bind_port))
                        except Exception:
                            pass
                    if msg_type == "BEACON" and self._on_beacon:
                        self._on_beacon(msg)
                    elif msg_type == "STATS" and self._on_stats:
                        self._on_stats(msg)
                    elif msg_type == "CONFIG_SYNC" and self._on_config_sync:
                        self._on_config_sync(msg)
                    elif msg_type == "PARTY_KICK" and self._on_party_kick:
                        self._on_party_kick(msg)
                    elif msg_type == "PC_ANNOUNCE":
                        norm_msg = dict(msg)
                        norm_msg.setdefault("device", msg.get("pc_name", "PC"))
                        norm_msg.setdefault("audio_port", 5004)
                        norm_msg.setdefault("control_port", self._bind_port)
                        norm_msg.setdefault("party_mode", msg.get("party_mode", False))
                        norm_msg.setdefault("is_party_host", msg.get("is_party_host", False))
                        if self._on_pc_announce:
                            self._on_pc_announce(norm_msg)
                        elif self._on_beacon:
                            self._on_beacon(norm_msg)
                except (json.JSONDecodeError, UnicodeDecodeError) as exc:
                    logger.debug("Control message parse error from %s: %s", addr, exc)
            except socket.timeout:
                continue
            except Exception as exc:
                if self._running and self._sock is not None:
                    logger.error("ControlListener error: %s", exc)


_ctrl_sock_lock = threading.Lock()
_ctrl_sock: Optional[socket.socket] = None

def send_control_message(target_ip: str, msg: dict, port: int = CONTROL_PORT) -> bool:
    """Send a JSON control message over UDP to target_ip:port using a persistent socket."""
    global _ctrl_sock
    if not target_ip:
        return False
    try:
        payload = dict(msg)
        payload.setdefault("app", "AirSpeakerPC")
        payload.setdefault("pc_name", socket.gethostname())
        data = json.dumps(payload).encode("utf-8")
        with _ctrl_sock_lock:
            if _ctrl_sock is None:
                _ctrl_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                _ctrl_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            try:
                _ctrl_sock.sendto(data, (target_ip, port))
            except OSError:
                try:
                    _ctrl_sock.close()
                except Exception:
                    pass
                _ctrl_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                _ctrl_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                _ctrl_sock.sendto(data, (target_ip, port))
        return True
    except Exception as exc:
        logger.debug("Error sending control message to %s:%d: %s", target_ip, port, exc)
        return False

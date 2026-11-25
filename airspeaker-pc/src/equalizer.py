"""
equalizer.py — Real-time 5-band stereo biquad Equalizer DSP, preset manager,
and Tkinter studio dialog for AirSpeaker.

Supports 5 independent EQ contexts:
  - 'play'       : Optional local receiver EQ on PLAY tab
  - 'serve'      : Outbound sender EQ on SERVE tab
  - 'party_host' : Outbound broadcast EQ on PARTY HOST tab
  - 'party_join' : Optional local receiver EQ on PARTY JOIN tab
  - 'universal'  : Master device EQ in SETTINGS tab with independent TX/RX checkmarks
"""

import copy
import math
import threading
import tkinter as tk
from tkinter import messagebox, simpledialog, ttk
from typing import Any, Callable, Dict, List, Optional, Tuple

import numpy as np

BAND_LABELS = ["60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz"]

BUILTIN_PRESETS: Dict[str, List[float]] = {
    "Flat": [0.0, 0.0, 0.0, 0.0, 0.0],
    "Bass Boost": [6.5, 4.0, 0.0, 1.0, 1.5],
    "Vocal Crisp": [-2.0, -1.0, 3.5, 5.0, 2.0],
    "Gaming Footsteps": [-3.0, 1.5, 4.5, 6.0, 3.5],
    "Warm Studio": [3.5, 2.5, -0.5, 1.5, 2.5],
    "Treble Air": [0.0, 0.0, 1.0, 4.0, 7.0],
}

DEFAULT_EQ_STATE: Dict[str, Any] = {
    "contexts": {
        "play": {"enabled": False, "preset": "Flat", "bands": [0.0, 0.0, 0.0, 0.0, 0.0]},
        "serve": {"enabled": False, "preset": "Flat", "bands": [0.0, 0.0, 0.0, 0.0, 0.0]},
        "party_host": {"enabled": False, "preset": "Flat", "bands": [0.0, 0.0, 0.0, 0.0, 0.0]},
        "party_join": {"enabled": False, "preset": "Flat", "bands": [0.0, 0.0, 0.0, 0.0, 0.0]},
        "universal": {
            "enabled": False,
            "apply_tx": False,
            "apply_rx": False,
            "preset": "Flat",
            "bands": [0.0, 0.0, 0.0, 0.0, 0.0],
        },
    },
    "custom_presets": {},
}


class StereoFiveBandBiquadDSP:
    """
    Real-time 5-band stereo peaking biquad EQ optimized for 5 ms audio frames.
    Zero-sound-loss guarantees:
      - Fused single-pass DF-II-T fallback (runs all active bands in 1 pass + NumPy I/O)
      - C-speed SciPy sosfilt fast path when available
      - NaN/Inf recovery: resets filter states and falls back to dry PCM (never latches silence)
      - Subnormal/denormal flush (< 1e-18 -> 0.0) to prevent silent-passage CPU spikes
      - Per-band state reset when a band becomes flat or channel count changes
      - Soft-knee limiter preventing clipping distortion on high boosts
    """

    BAND_FREQS = (60.0, 230.0, 910.0, 3600.0, 14000.0)
    BAND_Q = 1.15

    def __init__(self, sample_rate: int = 48000):
        self.sample_rate = float(sample_rate)
        self._lock = threading.Lock()
        self._enabled = False
        self._gains_db = [0.0] * 5
        self._coeffs: List[Tuple[float, float, float, float, float]] = [(1.0, 0.0, 0.0, 0.0, 0.0)] * 5
        self._active_indices: Tuple[int, ...] = ()

        # Audio-thread-only filter state
        self._py_zi = [[[0.0, 0.0], [0.0, 0.0]] for _ in range(5)]
        self._last_channels = 2
        self._needs_state_reset = False
        self._band_reset_mask = [False] * 5

    def set_gains(self, gains_db: List[float], enabled: Optional[bool] = None) -> None:
        raw_five = (list(gains_db) + [0.0] * 5)[:5]
        clamped: List[float] = []
        for g in raw_five:
            try:
                val = float(g)
            except (TypeError, ValueError):
                val = 0.0
            clamped.append(max(-12.0, min(12.0, val)) if math.isfinite(val) else 0.0)
        coeffs = [
            self._design_peaking_biquad(freq, g, self.BAND_Q, self.sample_rate)
            for freq, g in zip(self.BAND_FREQS, clamped)
        ]
        active = tuple(i for i, g in enumerate(clamped) if abs(g) >= 0.05)
        with self._lock:
            prev_active = set(self._active_indices)
            new_active = set(active)
            for b in range(5):
                # Clear state whenever a band turns off or turns back on from flat
                if (b in prev_active) != (b in new_active):
                    self._band_reset_mask[b] = True
            self._gains_db = clamped
            self._coeffs = coeffs
            self._active_indices = active
            if enabled is not None:
                new_en = bool(enabled)
                if self._enabled != new_en:
                    self._enabled = new_en
                    self._needs_state_reset = True
            elif active and not self._enabled:
                self._enabled = True
                self._needs_state_reset = True

    def reset(self) -> None:
        with self._lock:
            self._needs_state_reset = True

    @staticmethod
    def _design_peaking_biquad(freq: float, gain_db: float, q: float, fs: float) -> Tuple[float, float, float, float, float]:
        if abs(gain_db) < 0.05:
            return (1.0, 0.0, 0.0, 0.0, 0.0)
        a_lin = 10.0 ** (gain_db / 40.0)
        w0 = 2.0 * math.pi * freq / fs
        cos_w0 = math.cos(w0)
        alpha = math.sin(w0) / (2.0 * q)

        b0 = 1.0 + alpha * a_lin
        b1 = -2.0 * cos_w0
        b2 = 1.0 - alpha * a_lin
        a0 = 1.0 + alpha / a_lin
        a1 = -2.0 * cos_w0
        a2 = 1.0 - alpha / a_lin

        inv_a0 = 1.0 / a0
        return (b0 * inv_a0, b1 * inv_a0, b2 * inv_a0, a1 * inv_a0, a2 * inv_a0)

    def _clear_states(self) -> None:
        for b in range(5):
            self._py_zi[b][0][0] = 0.0
            self._py_zi[b][0][1] = 0.0
            self._py_zi[b][1][0] = 0.0
            self._py_zi[b][1][1] = 0.0

    def _clear_band_state(self, band_idx: int) -> None:
        self._py_zi[band_idx][0][0] = 0.0
        self._py_zi[band_idx][0][1] = 0.0
        self._py_zi[band_idx][1][0] = 0.0
        self._py_zi[band_idx][1][1] = 0.0

    def process_pcm16(self, pcm_bytes: bytes, channels: int = 2) -> bytes:
        with self._lock:
            enabled = self._enabled
            active = self._active_indices
            coeffs = self._coeffs
            should_reset = self._needs_state_reset
            self._needs_state_reset = False
            band_resets = self._band_reset_mask
            if any(band_resets):
                band_resets = list(band_resets)
                self._band_reset_mask = [False] * 5
            else:
                band_resets = None

        in_ch = 2 if channels >= 2 else 1
        if should_reset or in_ch != self._last_channels:
            self._clear_states()
            self._last_channels = in_ch
        elif band_resets is not None:
            for b, do_reset in enumerate(band_resets):
                if do_reset:
                    self._clear_band_state(b)

        if not enabled or not active or not pcm_bytes:
            return pcm_bytes

        return self._process_scalar_python(pcm_bytes, coeffs, active, in_ch)

    @staticmethod
    def _limit_and_pack(y_frames_ch: np.ndarray, dry_bytes: bytes, fallback_reset: Callable[[], None]) -> bytes:
        if not np.isfinite(y_frames_ch).all():
            fallback_reset()
            return dry_bytes

        max_val = float(np.max(np.abs(y_frames_ch)))
        if max_val <= 27852.0:  # Linear headroom: 0.85 * 32768
            return np.ascontiguousarray(np.rint(y_frames_ch), dtype="<i2").tobytes()

        knee = 0.85
        norm = y_frames_ch / 32768.0
        norm = np.where(
            norm > knee,
            knee + (1.0 - knee) * np.tanh((norm - knee) / (1.0 - knee)),
            np.where(
                norm < -knee,
                -knee - (1.0 - knee) * np.tanh((-norm - knee) / (1.0 - knee)),
                norm,
            ),
        )
        return np.ascontiguousarray(np.rint(np.clip(norm, -1.0, 1.0) * 32767.0), dtype="<i2").tobytes()

    def _filter_channel_fused(
        self,
        samples: List[float],
        coeffs: list,
        active: Tuple[int, ...],
        ch_idx: int,
    ) -> Tuple[List[float], bool]:
        """Run 1..5 active DF-II-T biquad stages in a single fused Python pass with local registers."""
        n_act = len(active)
        isfinite = math.isfinite
        out = [0.0] * len(samples)

        if n_act == 1:
            idx0 = active[0]
            b00, b10, b20, a10, a20 = coeffs[idx0]
            z0 = self._py_zi[idx0][ch_idx]
            z10, z20 = z0[0], z0[1]
            for i, x in enumerate(samples):
                y = b00 * x + z10
                z10 = b10 * x - a10 * y + z20
                z20 = b20 * x - a20 * y
                out[i] = y
            if not (isfinite(z10) and isfinite(z20)):
                return out, False
            z0[0] = 0.0 if -1e-18 < z10 < 1e-18 else z10
            z0[1] = 0.0 if -1e-18 < z20 < 1e-18 else z20
            return out, True

        if n_act == 2:
            i0, i1 = active
            b00, b10, b20, a10, a20 = coeffs[i0]
            b01, b11, b21, a11, a21 = coeffs[i1]
            z0, z1 = self._py_zi[i0][ch_idx], self._py_zi[i1][ch_idx]
            z10, z20 = z0[0], z0[1]
            z11, z21 = z1[0], z1[1]
            for i, x in enumerate(samples):
                y = b00 * x + z10
                z10 = b10 * x - a10 * y + z20
                z20 = b20 * x - a20 * y
                x = y
                y = b01 * x + z11
                z11 = b11 * x - a11 * y + z21
                z21 = b21 * x - a21 * y
                out[i] = y
            if not (isfinite(z10) and isfinite(z20) and isfinite(z11) and isfinite(z21)):
                return out, False
            z0[0] = 0.0 if -1e-18 < z10 < 1e-18 else z10
            z0[1] = 0.0 if -1e-18 < z20 < 1e-18 else z20
            z1[0] = 0.0 if -1e-18 < z11 < 1e-18 else z11
            z1[1] = 0.0 if -1e-18 < z21 < 1e-18 else z21
            return out, True

        if n_act == 3:
            i0, i1, i2 = active
            b00, b10, b20, a10, a20 = coeffs[i0]
            b01, b11, b21, a11, a21 = coeffs[i1]
            b02, b12, b22, a12, a22 = coeffs[i2]
            z0, z1, z2 = self._py_zi[i0][ch_idx], self._py_zi[i1][ch_idx], self._py_zi[i2][ch_idx]
            z10, z20 = z0[0], z0[1]
            z11, z21 = z1[0], z1[1]
            z12, z22 = z2[0], z2[1]
            for i, x in enumerate(samples):
                y = b00 * x + z10; z10 = b10 * x - a10 * y + z20; z20 = b20 * x - a20 * y; x = y
                y = b01 * x + z11; z11 = b11 * x - a11 * y + z21; z21 = b21 * x - a21 * y; x = y
                y = b02 * x + z12; z12 = b12 * x - a12 * y + z22; z22 = b22 * x - a22 * y
                out[i] = y
            states = (z10, z20, z11, z21, z12, z22)
            if not all(isfinite(s) for s in states):
                return out, False
            z0[0], z0[1] = (0.0 if -1e-18 < z10 < 1e-18 else z10), (0.0 if -1e-18 < z20 < 1e-18 else z20)
            z1[0], z1[1] = (0.0 if -1e-18 < z11 < 1e-18 else z11), (0.0 if -1e-18 < z21 < 1e-18 else z21)
            z2[0], z2[1] = (0.0 if -1e-18 < z12 < 1e-18 else z12), (0.0 if -1e-18 < z22 < 1e-18 else z22)
            return out, True

        if n_act == 4:
            i0, i1, i2, i3 = active
            b00, b10, b20, a10, a20 = coeffs[i0]
            b01, b11, b21, a11, a21 = coeffs[i1]
            b02, b12, b22, a12, a22 = coeffs[i2]
            b03, b13, b23, a13, a23 = coeffs[i3]
            z0, z1, z2, z3 = self._py_zi[i0][ch_idx], self._py_zi[i1][ch_idx], self._py_zi[i2][ch_idx], self._py_zi[i3][ch_idx]
            z10, z20 = z0[0], z0[1]
            z11, z21 = z1[0], z1[1]
            z12, z22 = z2[0], z2[1]
            z13, z23 = z3[0], z3[1]
            for i, x in enumerate(samples):
                y = b00 * x + z10; z10 = b10 * x - a10 * y + z20; z20 = b20 * x - a20 * y; x = y
                y = b01 * x + z11; z11 = b11 * x - a11 * y + z21; z21 = b21 * x - a21 * y; x = y
                y = b02 * x + z12; z12 = b12 * x - a12 * y + z22; z22 = b22 * x - a22 * y; x = y
                y = b03 * x + z13; z13 = b13 * x - a13 * y + z23; z23 = b23 * x - a23 * y
                out[i] = y
            states = (z10, z20, z11, z21, z12, z22, z13, z23)
            if not all(isfinite(s) for s in states):
                return out, False
            z0[0], z0[1] = (0.0 if -1e-18 < z10 < 1e-18 else z10), (0.0 if -1e-18 < z20 < 1e-18 else z20)
            z1[0], z1[1] = (0.0 if -1e-18 < z11 < 1e-18 else z11), (0.0 if -1e-18 < z21 < 1e-18 else z21)
            z2[0], z2[1] = (0.0 if -1e-18 < z12 < 1e-18 else z12), (0.0 if -1e-18 < z22 < 1e-18 else z22)
            z3[0], z3[1] = (0.0 if -1e-18 < z13 < 1e-18 else z13), (0.0 if -1e-18 < z23 < 1e-18 else z23)
            return out, True

        # 5 active bands
        b00, b10, b20, a10, a20 = coeffs[0]
        b01, b11, b21, a11, a21 = coeffs[1]
        b02, b12, b22, a12, a22 = coeffs[2]
        b03, b13, b23, a13, a23 = coeffs[3]
        b04, b14, b24, a14, a24 = coeffs[4]
        z0, z1, z2, z3, z4 = (
            self._py_zi[0][ch_idx], self._py_zi[1][ch_idx], self._py_zi[2][ch_idx],
            self._py_zi[3][ch_idx], self._py_zi[4][ch_idx],
        )
        z10, z20 = z0[0], z0[1]
        z11, z21 = z1[0], z1[1]
        z12, z22 = z2[0], z2[1]
        z13, z23 = z3[0], z3[1]
        z14, z24 = z4[0], z4[1]
        for i, x in enumerate(samples):
            y = b00 * x + z10; z10 = b10 * x - a10 * y + z20; z20 = b20 * x - a20 * y; x = y
            y = b01 * x + z11; z11 = b11 * x - a11 * y + z21; z21 = b21 * x - a21 * y; x = y
            y = b02 * x + z12; z12 = b12 * x - a12 * y + z22; z22 = b22 * x - a22 * y; x = y
            y = b03 * x + z13; z13 = b13 * x - a13 * y + z23; z23 = b23 * x - a23 * y; x = y
            y = b04 * x + z14; z14 = b14 * x - a14 * y + z24; z24 = b24 * x - a24 * y
            out[i] = y
        states = (z10, z20, z11, z21, z12, z22, z13, z23, z14, z24)
        if not all(isfinite(s) for s in states):
            return out, False
        z0[0], z0[1] = (0.0 if -1e-18 < z10 < 1e-18 else z10), (0.0 if -1e-18 < z20 < 1e-18 else z20)
        z1[0], z1[1] = (0.0 if -1e-18 < z11 < 1e-18 else z11), (0.0 if -1e-18 < z21 < 1e-18 else z21)
        z2[0], z2[1] = (0.0 if -1e-18 < z12 < 1e-18 else z12), (0.0 if -1e-18 < z22 < 1e-18 else z22)
        z3[0], z3[1] = (0.0 if -1e-18 < z13 < 1e-18 else z13), (0.0 if -1e-18 < z23 < 1e-18 else z23)
        z4[0], z4[1] = (0.0 if -1e-18 < z14 < 1e-18 else z14), (0.0 if -1e-18 < z24 < 1e-18 else z24)
        return out, True

    def _process_scalar_python(self, pcm_bytes: bytes, coeffs: list, active: Tuple[int, ...], in_ch: int) -> bytes:
        usable = (len(pcm_bytes) // (2 * in_ch)) * (2 * in_ch)
        if usable == 0:
            return pcm_bytes

        dry = pcm_bytes[:usable]
        pcm = np.frombuffer(dry, dtype="<i2").reshape(-1, in_ch)
        left_out, ok_l = self._filter_channel_fused(pcm[:, 0].astype(np.float64).tolist(), coeffs, active, 0)
        if not ok_l:
            self._clear_states()
            return dry

        if in_ch == 2:
            right_out, ok_r = self._filter_channel_fused(pcm[:, 1].astype(np.float64).tolist(), coeffs, active, 1)
            if not ok_r:
                self._clear_states()
                return dry
            y = np.empty((len(left_out), 2), dtype=np.float64)
            y[:, 0] = left_out
            y[:, 1] = right_out
        else:
            y = np.asarray(left_out, dtype=np.float64).reshape(-1, 1)

        return self._limit_and_pack(y, dry, self._clear_states)


class EqualizerManager:
    """Manages independent & universal EQ configurations, presets, and effective TX/RX gains."""

    def __init__(self, initial_state: Optional[Dict[str, Any]] = None, on_change: Optional[Callable[[], None]] = None):
        self._state = copy.deepcopy(DEFAULT_EQ_STATE)
        if isinstance(initial_state, dict):
            if isinstance(initial_state.get("contexts"), dict):
                for k, v in initial_state["contexts"].items():
                    if k in self._state["contexts"] and isinstance(v, dict):
                        self._state["contexts"][k].update(v)
            if isinstance(initial_state.get("custom_presets"), dict):
                self._state["custom_presets"].update(initial_state["custom_presets"])
        self._on_change = on_change

    def to_dict(self) -> Dict[str, Any]:
        return copy.deepcopy(self._state)

    def get_context(self, ctx: str) -> Dict[str, Any]:
        data = copy.deepcopy(self._state["contexts"].get(ctx, self._state["contexts"]["play"]))
        preset_val = data.get("preset") or data.get("preset_name") or "Flat"
        data["preset"] = preset_val
        data["preset_name"] = preset_val
        return data

    def update_context(self, ctx: str, **kwargs) -> None:
        if ctx not in self._state["contexts"]:
            return
        if "preset_name" in kwargs and "preset" not in kwargs:
            kwargs["preset"] = kwargs["preset_name"]
        elif "preset" in kwargs and "preset_name" not in kwargs:
            kwargs["preset_name"] = kwargs["preset"]
        self._state["contexts"][ctx].update(kwargs)
        if self._on_change:
            self._on_change()

    def get_all_presets(self) -> Dict[str, List[float]]:
        merged = dict(BUILTIN_PRESETS)
        for name, bands in self._state.get("custom_presets", {}).items():
            if isinstance(bands, list) and len(bands) == 5:
                merged[name] = [float(x) for x in bands]
        return merged

    def save_custom_preset(self, name: str, bands: List[float]) -> None:
        clean = name.strip()
        if not clean:
            return
        self._state["custom_presets"][clean] = [round(float(x), 1) for x in bands[:5]]
        if self._on_change:
            self._on_change()

    def delete_custom_preset(self, name: str) -> bool:
        if name in self._state.get("custom_presets", {}):
            self._state["custom_presets"].pop(name, None)
            if self._on_change:
                self._on_change()
            return True
        return False

    @staticmethod
    def _clean_bands(raw_bands: Any) -> List[float]:
        if not isinstance(raw_bands, (list, tuple)):
            return [0.0] * 5
        out: List[float] = []
        for x in (list(raw_bands) + [0.0] * 5)[:5]:
            try:
                v = float(x)
            except (TypeError, ValueError):
                v = 0.0
            out.append(v if math.isfinite(v) else 0.0)
        return out

    def get_effective_tx_gains(self, is_party_host: bool = False) -> Tuple[bool, List[float]]:
        mode_key = "party_host" if is_party_host else "serve"
        mode_cfg = self._state["contexts"][mode_key]
        univ_cfg = self._state["contexts"]["universal"]

        mode_on = bool(mode_cfg.get("enabled", False))
        univ_on = bool(univ_cfg.get("enabled", False) and univ_cfg.get("apply_tx", False))
        if not mode_on and not univ_on:
            return False, [0.0] * 5

        gains = [0.0] * 5
        if mode_on:
            mb = self._clean_bands(mode_cfg.get("bands"))
            gains = [gains[i] + mb[i] for i in range(5)]
        if univ_on:
            ub = self._clean_bands(univ_cfg.get("bands"))
            gains = [gains[i] + ub[i] for i in range(5)]
        gains = [max(-12.0, min(12.0, g)) if math.isfinite(g) else 0.0 for g in gains]
        return True, gains

    def get_effective_rx_gains(self, is_party_join: bool = False) -> Tuple[bool, List[float]]:
        mode_key = "party_join" if is_party_join else "play"
        mode_cfg = self._state["contexts"][mode_key]
        univ_cfg = self._state["contexts"]["universal"]

        mode_on = bool(mode_cfg.get("enabled", False))
        univ_on = bool(univ_cfg.get("enabled", False) and univ_cfg.get("apply_rx", False))
        if not mode_on and not univ_on:
            return False, [0.0] * 5

        gains = [0.0] * 5
        if mode_on:
            mb = self._clean_bands(mode_cfg.get("bands"))
            gains = [gains[i] + mb[i] for i in range(5)]
        if univ_on:
            ub = self._clean_bands(univ_cfg.get("bands"))
            gains = [gains[i] + ub[i] for i in range(5)]
        gains = [max(-12.0, min(12.0, g)) if math.isfinite(g) else 0.0 for g in gains]
        return True, gains


def open_equalizer_dialog(
    parent: tk.Misc,
    eq_manager: EqualizerManager,
    context_key: str,
    title_label: str,
    font_mono: tuple,
    font_bold: tuple,
    on_updated: Optional[Callable[[], None]] = None,
) -> None:
    dlg = tk.Toplevel(parent)
    dlg.title(f"Studio 5-Band Equalizer — {title_label}")
    dlg.configure(bg="#101318")
    dlg.resizable(False, False)

    cfg = eq_manager.get_context(context_key)
    enabled_var = tk.BooleanVar(value=bool(cfg.get("enabled", False)))
    apply_tx_var = tk.BooleanVar(value=bool(cfg.get("apply_tx", False)))
    apply_rx_var = tk.BooleanVar(value=bool(cfg.get("apply_rx", False)))
    preset_var = tk.StringVar(value=str(cfg.get("preset", "Flat")))
    band_vars = [tk.DoubleVar(value=float(b)) for b in (cfg.get("bands", [0.0] * 5) + [0.0] * 5)[:5]]
    val_labels: List[tk.Label] = []

    def _push_changes() -> None:
        bands = [round(float(v.get()), 1) for v in band_vars]
        updates: Dict[str, Any] = {
            "enabled": bool(enabled_var.get()),
            "preset": preset_var.get(),
            "bands": bands,
        }
        if context_key == "universal":
            updates["apply_tx"] = bool(apply_tx_var.get())
            updates["apply_rx"] = bool(apply_rx_var.get())
        eq_manager.update_context(context_key, **updates)
        for idx, lbl in enumerate(val_labels):
            lbl.config(text=f"{bands[idx]:+.1f} dB")
        if on_updated:
            on_updated()

    top = tk.Frame(dlg, bg="#1B2029", padx=14, pady=10)
    top.pack(fill="x")
    tk.Label(top, text=f"🎛️ {title_label}", bg="#1B2029", fg="#7BDCA8", font=font_bold).pack(side="left")

    chk_text = "Enable Equalizer (Active)" if context_key != "universal" else "Enable Universal Device Equalizer"
    tk.Checkbutton(
        top, text=chk_text, variable=enabled_var,
        bg="#101318", fg="#E3E6EE", selectcolor="#262D39",
        activebackground="#101318", activeforeground="#A8C7FA",
        font=font_bold, command=_push_changes
    ).pack(side="right")

    if context_key == "universal":
        def _on_univ_route_toggled() -> None:
            if apply_tx_var.get() or apply_rx_var.get():
                enabled_var.set(True)
            _push_changes()

        u_frame = tk.LabelFrame(dlg, text=" Universal Equalizer Target Routing ", bg="#101318", fg="#A8C7FA", font=font_bold, padx=8, pady=6)
        u_frame.pack(fill="x", padx=12, pady=(0, 6))
        tk.Checkbutton(
            u_frame, text="Apply to Hosting & Serving (SERVE + PARTY HOST Outbound TX)",
            variable=apply_tx_var, bg="#101318", fg="#E3E6EE", selectcolor="#262D39",
            font=font_mono, command=_on_univ_route_toggled
        ).pack(anchor="w")
        tk.Checkbutton(
            u_frame, text="Apply to Playing & Joining (PLAY + PARTY JOIN Inbound RX)",
            variable=apply_rx_var, bg="#101318", fg="#E3E6EE", selectcolor="#262D39",
            font=font_mono, command=_on_univ_route_toggled
        ).pack(anchor="w")

    # Preset Bar
    p_frame = tk.LabelFrame(dlg, text=" Equalizer Presets (Built-in & Custom Saved) ", bg="#101318", fg="#E3E6EE", font=font_bold, padx=8, pady=6)
    p_frame.pack(fill="x", padx=12, pady=4)

    preset_combo = ttk.Combobox(p_frame, textvariable=preset_var, state="readonly", width=22, font=font_mono)
    preset_combo.pack(side="left", padx=(0, 6))

    def _refresh_preset_list() -> None:
        preset_combo["values"] = list(eq_manager.get_all_presets().keys()) + ["Custom"]

    _refresh_preset_list()

    suppress_slider_cb = False

    def _on_preset_selected(event=None) -> None:
        nonlocal suppress_slider_cb
        name = preset_var.get()
        all_p = eq_manager.get_all_presets()
        if name in all_p:
            suppress_slider_cb = True
            try:
                for i, val in enumerate(all_p[name]):
                    band_vars[i].set(val)
                enabled_var.set(True)
                dlg.update_idletasks()
            finally:
                suppress_slider_cb = False
            _push_changes()

    preset_combo.bind("<<ComboboxSelected>>", _on_preset_selected)

    def _save_preset() -> None:
        name = simpledialog.askstring("Save Equalizer Preset", "Enter a name for your custom preset:", parent=dlg)
        if not name or not name.strip():
            return
        clean = name.strip()
        bands = [round(float(v.get()), 1) for v in band_vars]
        eq_manager.save_custom_preset(clean, bands)
        preset_var.set(clean)
        _refresh_preset_list()
        _push_changes()
        messagebox.showinfo("Preset Saved", f"Saved custom equalizer preset '{clean}'!", parent=dlg)

    def _delete_preset() -> None:
        cur = preset_var.get()
        if cur in BUILTIN_PRESETS or cur == "Custom":
            messagebox.showwarning("Delete Preset", "Built-in presets cannot be deleted.", parent=dlg)
            return
        if eq_manager.delete_custom_preset(cur):
            _reset_flat()
            _refresh_preset_list()

    def _reset_flat() -> None:
        nonlocal suppress_slider_cb
        suppress_slider_cb = True
        try:
            preset_var.set("Flat")
            for v in band_vars:
                v.set(0.0)
            dlg.update_idletasks()
        finally:
            suppress_slider_cb = False
        _push_changes()

    ttk.Button(p_frame, text="💾 Save Preset", command=_save_preset).pack(side="left", padx=2)
    ttk.Button(p_frame, text="🗑 Delete", command=_delete_preset).pack(side="left", padx=2)
    ttk.Button(p_frame, text="↺ Reset Flat", command=_reset_flat).pack(side="left", padx=2)

    # 5 Vertical Sliders
    b_frame = tk.LabelFrame(dlg, text=" 5-Band Frequency Gains (-12 dB to +12 dB) ", bg="#101318", fg="#E3E6EE", font=font_bold, padx=10, pady=8)
    b_frame.pack(fill="x", padx=12, pady=6)

    sliders_row = tk.Frame(b_frame, bg="#101318")
    sliders_row.pack(fill="x")

    def _make_slider_cb(idx: int):
        def _cb(val: str) -> None:
            if suppress_slider_cb:
                return
            preset_var.set("Custom")
            enabled_var.set(True)
            _push_changes()
        return _cb

    for i, lbl_txt in enumerate(BAND_LABELS):
        col = tk.Frame(sliders_row, bg="#101318", padx=10)
        col.pack(side="left", expand=True)
        tk.Label(col, text=lbl_txt, bg="#101318", fg="#A8C7FA", font=font_bold).pack(pady=(0, 4))
        sc = tk.Scale(
            col, from_=12.0, to=-12.0, resolution=0.5, orient="vertical",
            variable=band_vars[i], showvalue=False,
            bg="#101318", fg="#7BDCA8", troughcolor="#262D39",
            highlightthickness=0, bd=0, length=130,
            command=_make_slider_cb(i)
        )
        sc.pack()
        v_lbl = tk.Label(col, text=f"{band_vars[i].get():+.1f} dB", bg="#101318", fg="#7BDCA8", font=font_mono, width=8)
        v_lbl.pack(pady=(4, 0))
        val_labels.append(v_lbl)

    btn_close = ttk.Button(dlg, text="✔ Done / Close", command=dlg.destroy)
    btn_close.pack(pady=(4, 10))

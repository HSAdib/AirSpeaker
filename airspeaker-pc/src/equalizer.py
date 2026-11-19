"""
equalizer.py — Real-time 5-band stereo biquad Equalizer DSP, preset manager,
and Tkinter studio dialog for Air Speaker v2.1.

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
from typing import Any, Callable, Dict, List, Optional

import numpy as np

BAND_FREQS = [60, 230, 910, 3600, 14000]
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
    Stateful 5-band stereo peaking/shelving biquad filter bank for 48 kHz PCM16 frames.
    Maintains filter state across 5ms frame boundaries so there are zero clicks.
    """

    def __init__(self, sample_rate: int = 48000):
        self._sr = float(sample_rate)
        self._lock = threading.Lock()
        self._gains_db: List[float] = [0.0, 0.0, 0.0, 0.0, 0.0]
        self._active = False
        self._coeffs: List[tuple] = []
        self._z1 = np.zeros((5, 2), dtype=np.float64)
        self._z2 = np.zeros((5, 2), dtype=np.float64)
        self._recompute_coeffs()

    def set_gains(self, gains_db: List[float], enabled: bool = True) -> None:
        clamped = [max(-12.0, min(12.0, float(g))) for g in (gains_db + [0.0] * 5)[:5]]
        is_active = bool(enabled) and any(abs(g) > 0.05 for g in clamped)
        with self._lock:
            if clamped != self._gains_db or is_active != self._active:
                self._gains_db = clamped
                self._active = is_active
                self._recompute_coeffs()
                if not is_active:
                    self._z1.fill(0.0)
                    self._z2.fill(0.0)

    def _recompute_coeffs(self) -> None:
        coeffs = []
        q = 1.15
        for freq, gain_db in zip(BAND_FREQS, self._gains_db):
            if abs(gain_db) <= 0.05:
                coeffs.append((1.0, 0.0, 0.0, 0.0, 0.0))
                continue
            A = 10.0 ** (gain_db / 40.0)
            w0 = 2.0 * math.pi * float(freq) / self._sr
            alpha = math.sin(w0) / (2.0 * q)
            cos_w0 = math.cos(w0)

            b0 = 1.0 + alpha * A
            b1 = -2.0 * cos_w0
            b2 = 1.0 - alpha * A
            a0 = 1.0 + alpha / A
            a1 = -2.0 * cos_w0
            a2 = 1.0 - alpha / A

            coeffs.append((b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0))
        self._coeffs = coeffs

    def process_pcm16(self, pcm_bytes: bytes, channels: int = 2) -> bytes:
        with self._lock:
            if not self._active or not pcm_bytes:
                return pcm_bytes
            coeffs = list(self._coeffs)

        in_ch = 2 if channels >= 2 else 1
        usable = (len(pcm_bytes) // (2 * in_ch)) * (2 * in_ch)
        if usable == 0:
            return pcm_bytes

        arr = np.frombuffer(pcm_bytes[:usable], dtype="<i2").astype(np.float64) / 32768.0
        arr = arr.reshape(-1, in_ch)

        with self._lock:
            for b_idx, (b0, b1, b2, a1, a2) in enumerate(coeffs):
                if b0 == 1.0 and b1 == 0.0 and b2 == 0.0:
                    continue
                z1 = self._z1[b_idx, :in_ch].copy()
                z2 = self._z2[b_idx, :in_ch].copy()
                out = np.empty_like(arr)
                for n in range(arr.shape[0]):
                    x = arr[n]
                    y = b0 * x + z1
                    z1 = b1 * x - a1 * y + z2
                    z2 = b2 * x - a2 * y
                    out[n] = y
                self._z1[b_idx, :in_ch] = z1
                self._z2[b_idx, :in_ch] = z2
                arr = out

        # Soft-knee limiter to prevent clipping when boosting bands
        knee = 0.85
        arr = np.where(
            arr > knee,
            knee + (1.0 - knee) * np.tanh((arr - knee) / (1.0 - knee)),
            np.where(
                arr < -knee,
                -knee - (1.0 - knee) * np.tanh((-arr - knee) / (1.0 - knee)),
                arr,
            ),
        )
        return np.rint(np.clip(arr, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()


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

    def get_effective_tx_gains(self, is_party_host: bool = False) -> tuple[bool, List[float]]:
        mode_key = "party_host" if is_party_host else "serve"
        mode_cfg = self._state["contexts"][mode_key]
        univ_cfg = self._state["contexts"]["universal"]

        mode_on = bool(mode_cfg.get("enabled", False))
        univ_on = bool(univ_cfg.get("enabled", False) and univ_cfg.get("apply_tx", False))
        if not mode_on and not univ_on:
            return False, [0.0] * 5

        gains = [0.0] * 5
        if mode_on:
            mb = mode_cfg.get("bands", [0.0] * 5)
            gains = [gains[i] + float(mb[i]) for i in range(5)]
        if univ_on:
            ub = univ_cfg.get("bands", [0.0] * 5)
            gains = [gains[i] + float(ub[i]) for i in range(5)]
        gains = [max(-12.0, min(12.0, g)) for g in gains]
        return True, gains

    def get_effective_rx_gains(self, is_party_join: bool = False) -> tuple[bool, List[float]]:
        mode_key = "party_join" if is_party_join else "play"
        mode_cfg = self._state["contexts"][mode_key]
        univ_cfg = self._state["contexts"]["universal"]

        mode_on = bool(mode_cfg.get("enabled", False))
        univ_on = bool(univ_cfg.get("enabled", False) and univ_cfg.get("apply_rx", False))
        if not mode_on and not univ_on:
            return False, [0.0] * 5

        gains = [0.0] * 5
        if mode_on:
            mb = mode_cfg.get("bands", [0.0] * 5)
            gains = [gains[i] + float(mb[i]) for i in range(5)]
        if univ_on:
            ub = univ_cfg.get("bands", [0.0] * 5)
            gains = [gains[i] + float(ub[i]) for i in range(5)]
        gains = [max(-12.0, min(12.0, g)) for g in gains]
        return True, gains

    def summary_text(self, ctx: str) -> str:
        c = self.get_context(ctx)
        on = c.get("enabled", False)
        preset = c.get("preset", "Flat")
        if ctx == "universal":
            scopes = []
            if c.get("apply_tx"):
                scopes.append("Host/Serve")
            if c.get("apply_rx"):
                scopes.append("Play/Join")
            scope_str = "+".join(scopes) if scopes else "None"
            return f"Universal EQ: {'ON' if on else 'OFF'} [{preset}] ({scope_str})"
        return f"EQ: {'ON' if on else 'OFF'} ({preset})"


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
    dlg.configure(bg="#000000")
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

    top = tk.Frame(dlg, bg="#000000", padx=12, pady=8)
    top.pack(fill="x")
    tk.Label(top, text=f"🎛️ {title_label.upper()}", bg="#000000", fg="#00FF00", font=font_bold).pack(side="left")

    chk_text = "Enable Equalizer (Active)" if context_key != "universal" else "Enable Universal Device Equalizer"
    tk.Checkbutton(
        top, text=chk_text, variable=enabled_var,
        bg="#000000", fg="#FFFFFF", selectcolor="#222222",
        activebackground="#000000", activeforeground="#00FF00",
        font=font_bold, command=_push_changes
    ).pack(side="right")

    if context_key == "universal":
        u_frame = tk.LabelFrame(dlg, text=" Universal Equalizer Target Routing ", bg="#000000", fg="#00EEEE", font=font_bold, padx=8, pady=6)
        u_frame.pack(fill="x", padx=12, pady=(0, 6))
        tk.Checkbutton(
            u_frame, text="Apply to Hosting & Serving (SERVE + PARTY HOST Outbound TX)",
            variable=apply_tx_var, bg="#000000", fg="#FFFFFF", selectcolor="#222222",
            font=font_mono, command=_push_changes
        ).pack(anchor="w")
        tk.Checkbutton(
            u_frame, text="Apply to Playing & Joining (PLAY + PARTY JOIN Inbound RX)",
            variable=apply_rx_var, bg="#000000", fg="#FFFFFF", selectcolor="#222222",
            font=font_mono, command=_push_changes
        ).pack(anchor="w")

    # Preset Bar
    p_frame = tk.LabelFrame(dlg, text=" Equalizer Presets (Built-in & Custom Saved) ", bg="#000000", fg="#FFFFFF", font=font_bold, padx=8, pady=6)
    p_frame.pack(fill="x", padx=12, pady=4)

    preset_combo = ttk.Combobox(p_frame, textvariable=preset_var, state="readonly", width=22, font=font_mono)
    preset_combo.pack(side="left", padx=(0, 6))

    def _refresh_preset_list() -> None:
        preset_combo["values"] = list(eq_manager.get_all_presets().keys()) + ["Custom"]

    _refresh_preset_list()

    def _on_preset_selected(event=None) -> None:
        name = preset_var.get()
        all_p = eq_manager.get_all_presets()
        if name in all_p:
            for i, val in enumerate(all_p[name]):
                band_vars[i].set(val)
            enabled_var.set(True)
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
            preset_var.set("Flat")
            _on_preset_selected()
            _refresh_preset_list()

    def _reset_flat() -> None:
        preset_var.set("Flat")
        for v in band_vars:
            v.set(0.0)
        _push_changes()

    ttk.Button(p_frame, text="💾 Save Preset", command=_save_preset).pack(side="left", padx=2)
    ttk.Button(p_frame, text="🗑 Delete", command=_delete_preset).pack(side="left", padx=2)
    ttk.Button(p_frame, text="↺ Reset Flat", command=_reset_flat).pack(side="left", padx=2)

    # 5 Vertical Sliders
    b_frame = tk.LabelFrame(dlg, text=" 5-Band Frequency Gains (-12 dB to +12 dB) ", bg="#000000", fg="#FFFFFF", font=font_bold, padx=10, pady=8)
    b_frame.pack(fill="x", padx=12, pady=6)

    sliders_row = tk.Frame(b_frame, bg="#000000")
    sliders_row.pack(fill="x")

    def _make_slider_cb(idx: int):
        def _cb(val: str) -> None:
            preset_var.set("Custom")
            enabled_var.set(True)
            _push_changes()
        return _cb

    for i, lbl_txt in enumerate(BAND_LABELS):
        col = tk.Frame(sliders_row, bg="#000000", padx=10)
        col.pack(side="left", expand=True)
        tk.Label(col, text=lbl_txt, bg="#000000", fg="#38B6FF", font=font_bold).pack(pady=(0, 4))
        sc = tk.Scale(
            col, from_=12.0, to=-12.0, resolution=0.5, orient="vertical",
            variable=band_vars[i], showvalue=False,
            bg="#000000", fg="#00FF00", troughcolor="#222222",
            highlightthickness=0, bd=0, length=130,
            command=_make_slider_cb(i)
        )
        sc.pack()
        v_lbl = tk.Label(col, text=f"{band_vars[i].get():+.1f} dB", bg="#000000", fg="#00FF00", font=font_mono, width=8)
        v_lbl.pack(pady=(4, 0))
        val_labels.append(v_lbl)

    btn_close = ttk.Button(dlg, text="✔ Done / Close", command=dlg.destroy)
    btn_close.pack(pady=(4, 10))

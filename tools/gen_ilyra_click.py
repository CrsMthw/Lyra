#!/usr/bin/env python3
"""
Synthesize the Classic click-wheel samples: ilyra_click.wav and ilyra_select.wav.

Both are 16-bit mono 44.1 kHz.  The Classic's clicker was a piezo disc driven by a sharp pulse
through the metal case — a very short, dry, bright tick with most energy around 1.5-3.5 kHz,
instant attack, exponential decay, no low-end thump.  We approximate it with a handful of
decaying sinusoids plus filtered noise.

Round B: lowered partials from the 2.8-6.5 kHz range to 1.3-3.6 kHz and dropped peak from
-3 dBFS to -10 dBFS.  The round-A samples were too loud and high-pitched on the Fold 8.
Noise amplitude reduced (click 0.25→0.15, select 0.30→0.18) to tame brightness.

Deterministic: numpy RNG seeded at 0, no scipy.

Output: app/src/main/res/raw/ilyra_click.wav, app/src/main/res/raw/ilyra_select.wav
"""

import os
import struct
import wave

import numpy as np

SAMPLE_RATE = 44100
PEAK_DBFS = -10.0  # target peak level in dBFS (was -3 in round A)


def _make_click(
    duration_ms: float,
    freqs_hz: list[float],
    decays_ms: list[float],
    amps: list[float],
    noise_amp: float,
    noise_decay_ms: float,
    fade_out_ms: float = 1.0,
    seed: int = 0,
) -> np.ndarray:
    """Synthesize one click as a sum of decaying sinusoids + highpassed noise."""
    n_samples = int(SAMPLE_RATE * duration_ms / 1000.0)
    t = np.arange(n_samples) / SAMPLE_RATE  # time in seconds

    signal = np.zeros(n_samples, dtype=np.float64)

    # Decaying sinusoids
    for freq, decay, amp in zip(freqs_hz, decays_ms, amps):
        tau = decay / 1000.0
        signal += amp * np.sin(2.0 * np.pi * freq * t) * np.exp(-t / tau)

    # Filtered noise — first-difference filter to kill low end
    rng = np.random.default_rng(seed)
    raw_noise = rng.standard_normal(n_samples)
    # Highpass via first-difference: y[n] = x[n] - x[n-1]
    hp_noise = np.empty(n_samples, dtype=np.float64)
    hp_noise[0] = raw_noise[0]
    hp_noise[1:] = raw_noise[1:] - raw_noise[:-1]
    noise_env = noise_amp * np.exp(-t / (noise_decay_ms / 1000.0))
    signal += hp_noise * noise_env

    # Fade-out to avoid a step at the end
    fade_samples = max(1, int(SAMPLE_RATE * fade_out_ms / 1000.0))
    if fade_samples < n_samples:
        signal[-fade_samples:] *= np.linspace(1.0, 0.0, fade_samples)

    # Normalize to target peak
    peak = np.max(np.abs(signal))
    if peak > 0:
        target = 10.0 ** (PEAK_DBFS / 20.0)
        signal *= target / peak

    # Clamp and convert to 16-bit
    signal = np.clip(signal * 32767.0, -32768.0, 32767.0).astype(np.int16)
    return signal


def write_wav(path: str, samples: np.ndarray) -> None:
    """Write 16-bit mono WAV at SAMPLE_RATE."""
    with wave.open(path, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(samples.tobytes())


def main() -> None:
    script_dir = os.path.dirname(os.path.abspath(__file__))
    raw_dir = os.path.join(script_dir, "..", "app", "src", "main", "res", "raw")
    os.makedirs(raw_dir, exist_ok=True)

    # ── ilyra_click.wav — the detent tick (~15 ms) ──────────────────────
    # Three decaying sinusoids in the 1.6-3.6 kHz range (was 2.8-6.5 kHz).
    # Reduced noise amplitude (0.25→0.15) to tame metallic brightness.
    click = _make_click(
        duration_ms=15.0,
        freqs_hz=[1600.0, 2400.0, 3600.0],
        decays_ms=[4.0, 3.0, 2.0],
        amps=[1.0, 0.7, 0.35],
        noise_amp=0.15,
        noise_decay_ms=2.5,
        fade_out_ms=1.0,
        seed=0,
    )
    click_path = os.path.join(raw_dir, "ilyra_click.wav")
    write_wav(click_path, click)

    # ── ilyra_select.wav — the centre-button click (~28 ms) ────────────
    # Same family, slightly lower fundamental, longer body.
    # Reduced noise amplitude (0.30→0.18) to tame brightness.
    select = _make_click(
        duration_ms=28.0,
        freqs_hz=[1300.0, 2000.0, 3000.0],
        decays_ms=[7.0, 5.0, 3.5],
        amps=[1.0, 0.65, 0.3],
        noise_amp=0.18,
        noise_decay_ms=4.0,
        fade_out_ms=1.0,
        seed=0,
    )
    select_path = os.path.join(raw_dir, "ilyra_select.wav")
    write_wav(select_path, select)

    # ── Verify ─────────────────────────────────────────────────────────
    for name, path in [("ilyra_click.wav", click_path), ("ilyra_select.wav", select_path)]:
        with wave.open(path, "rb") as wf:
            ch = wf.getnchannels()
            sw = wf.getsampwidth()
            fr = wf.getframerate()
            nf = wf.getnframes()
            dur_ms = nf / fr * 1000.0
        size = os.path.getsize(path)
        print(f"{name}: {ch}ch {sw*8}bit {fr}Hz, {nf} frames, {dur_ms:.1f}ms, {size} bytes")
        assert ch == 1, f"expected mono, got {ch}"
        assert sw == 2, f"expected 16-bit, got {sw*8}"
        assert fr == SAMPLE_RATE, f"expected {SAMPLE_RATE}, got {fr}"
        assert size < 5120, f"file too large: {size} bytes (limit 5120)"
        print(f"  OK — valid WAV, under 5 KB")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied.
"""Compare private dry/reference/candidate WAVs without copying them into the repository."""

import argparse
import json
import math
import subprocess
from pathlib import Path

import numpy as np


def read_wav(path: Path):
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "stream=sample_rate,channels", "-of", "json", str(path)],
        check=True, capture_output=True, text=True)
    stream = json.loads(probe.stdout)["streams"][0]
    rate, channels = int(stream["sample_rate"]), int(stream["channels"])
    decoded = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-f", "f32le", "-acodec", "pcm_f32le", "-"],
        check=True, capture_output=True)
    values = np.frombuffer(decoded.stdout, dtype="<f4").astype(np.float64)
    return rate, values.reshape(-1, channels)


def db(value):
    return 20.0 * math.log10(max(float(value), 1.0e-12))


def rms(values):
    return math.sqrt(float(np.mean(values * values))) if len(values) else 0.0


def centroid(values, rate):
    if not len(values):
        return 0.0
    windowed = values * np.hanning(len(values))
    spectrum = np.abs(np.fft.rfft(windowed))
    frequencies = np.fft.rfftfreq(len(values), 1.0 / rate)
    total = float(spectrum.sum())
    return float((spectrum * frequencies).sum() / total) if total else 0.0


def band_energy_ratio(values, rate, low_hz, high_hz):
    if not len(values):
        return 0.0
    spectrum = np.abs(np.fft.rfft(values * np.hanning(len(values)))) ** 2
    frequencies = np.fft.rfftfreq(len(values), 1.0 / rate)
    selected = spectrum[(frequencies >= low_hz) & (frequencies < high_hz)].sum()
    return float(selected / max(float(spectrum.sum()), 1.0e-20))


def stereo_correlation(values):
    if values.ndim != 2 or values.shape[1] < 2 or not len(values):
        return None
    left = values[:, 0] - np.mean(values[:, 0])
    right = values[:, 1] - np.mean(values[:, 1])
    denominator = math.sqrt(float(np.dot(left, left) * np.dot(right, right)))
    return float(np.dot(left, right) / denominator) if denominator > 1.0e-20 else None


def harmonic_energy_ratio(values, rate):
    """Return a THD-like ratio only for windows dominated by one stable tone."""
    if len(values) < 256:
        return None
    spectrum = np.abs(np.fft.rfft(values * np.hanning(len(values)))) ** 2
    frequencies = np.fft.rfftfreq(len(values), 1.0 / rate)
    eligible = np.flatnonzero((frequencies >= 60.0) & (frequencies <= min(4000.0, rate / 8.0)))
    if not len(eligible):
        return None
    fundamental = eligible[np.argmax(spectrum[eligible])]
    if spectrum[fundamental] / max(float(spectrum.sum()), 1.0e-20) < 0.12:
        return None
    fundamental_energy = spectrum[max(0, fundamental - 1):fundamental + 2].sum()
    harmonic_energy = 0.0
    harmonic = 2
    while fundamental * harmonic + 1 < len(spectrum):
        center = fundamental * harmonic
        harmonic_energy += spectrum[center - 1:center + 2].sum()
        harmonic += 1
    return db(math.sqrt(float(harmonic_energy / max(fundamental_energy, 1.0e-20))))


def transient_smear(values):
    """Peak-to-RMS crest factor: falling values indicate increasingly rounded transients."""
    return db(float(np.max(np.abs(values))) / max(rms(values), 1.0e-12)) if len(values) else 0.0


def modulation_lag_samples(previous, current, maximum_lag):
    """Integer-lag drift proxy after removing level; filtering also influences this estimate."""
    count = min(len(previous), len(current))
    if count < maximum_lag * 2 + 32:
        return None
    a = previous[:count] - np.mean(previous[:count])
    b = current[:count] - np.mean(current[:count])
    if rms(a) < 1.0e-9 or rms(b) < 1.0e-9:
        return None
    correlations = [float(np.dot(a[maximum_lag:count - maximum_lag],
                                 b[maximum_lag + lag:count - maximum_lag + lag]))
                    for lag in range(-maximum_lag, maximum_lag + 1)]
    return int(np.argmax(correlations) - maximum_lag)


def analyze(dry, wet, rate, delay_ms):
    dry_mono = dry.mean(axis=1)
    wet_mono = wet.mean(axis=1)
    delay = round(rate * delay_ms / 1000.0)
    pre = min(delay, len(dry_mono), len(wet_mono))
    direct = float(np.dot(wet_mono[:pre], dry_mono[:pre])
                   / max(float(np.dot(dry_mono[:pre], dry_mono[:pre])), 1.0e-20))
    tail_start = len(dry)
    windows = []
    previous = None
    for generation in range(10):
        start, end = tail_start + generation * delay, min(len(wet), tail_start + (generation + 1) * delay)
        if end <= start:
            break
        multichannel = wet[start:end]
        segment = multichannel.mean(axis=1)
        windows.append({
            "generation": generation + 1,
            "rms_dbfs": db(rms(segment)),
            "centroid_hz": centroid(segment, rate),
            "hf_energy_ratio_db": db(math.sqrt(band_energy_ratio(segment, rate, 4000.0, rate / 2.0))),
            "lf_energy_ratio_db": db(math.sqrt(band_energy_ratio(segment, rate, 20.0, 250.0))),
            "crest_factor_db": transient_smear(segment),
            "harmonic_energy_ratio_db": harmonic_energy_ratio(segment, rate),
            "stereo_correlation": stereo_correlation(multichannel),
            "lag_from_previous_samples": modulation_lag_samples(previous, segment, max(2, round(rate * 0.002)))
                if previous is not None else None,
        })
        previous = segment
    attenuation = [windows[index]["rms_dbfs"] - windows[index - 1]["rms_dbfs"] for index in range(1, len(windows))]
    peak = float(np.max(np.abs(wet_mono)))
    full_rms = rms(wet_mono)
    tail_peak = float(np.max(np.abs(wet_mono[tail_start:]))) if len(wet_mono) > tail_start else 0.0
    thresholds = {}
    for threshold in (-60, -80, -100):
        amplitude = 10.0 ** (threshold / 20.0)
        indices = np.flatnonzero(np.abs(wet_mono) >= amplitude)
        thresholds[str(threshold)] = float(indices[-1] / rate) if len(indices) else 0.0
    return {
        "duration_seconds": len(wet_mono) / rate,
        "channels": int(wet.shape[1]),
        "direct_gain_estimate": direct,
        "peak_dbfs": db(peak),
        "rms_dbfs": db(full_rms),
        "tail_peak_dbfs": db(tail_peak),
        "tail_last_above_dbfs_seconds": thresholds,
        "mean_tail_attenuation_db_per_window": float(np.mean(attenuation[:6])) if attenuation else None,
        "repeat_windows": windows,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry", required=True, type=Path)
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--delay-ms", type=float, default=350.0)
    args = parser.parse_args()
    dry_rate, dry = read_wav(args.dry)
    output = {"delay_ms": args.delay_ms, "dry_duration_seconds": len(dry) / dry_rate}
    for label, path in (("reference", args.reference), ("candidate", args.candidate)):
        rate, audio = read_wav(path)
        if rate != dry_rate:
            raise ValueError(f"{label} sample rate {rate} differs from dry rate {dry_rate}")
        output[label] = analyze(dry, audio, rate, args.delay_ms)
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()

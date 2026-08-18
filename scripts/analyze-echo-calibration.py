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
    return rate, values.reshape(-1, channels).mean(axis=1)


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


def analyze(dry, wet, rate, delay_ms):
    delay = round(rate * delay_ms / 1000.0)
    pre = min(delay, len(dry), len(wet))
    direct = float(np.dot(wet[:pre], dry[:pre]) / max(float(np.dot(dry[:pre], dry[:pre])), 1.0e-20))
    tail_start = len(dry)
    windows = []
    for generation in range(10):
        start, end = tail_start + generation * delay, min(len(wet), tail_start + (generation + 1) * delay)
        if end <= start:
            break
        segment = wet[start:end]
        windows.append({"generation": generation + 1, "rms_dbfs": db(rms(segment)), "centroid_hz": centroid(segment, rate)})
    attenuation = [windows[index]["rms_dbfs"] - windows[index - 1]["rms_dbfs"] for index in range(1, len(windows))]
    peak = float(np.max(np.abs(wet)))
    full_rms = rms(wet)
    tail_peak = float(np.max(np.abs(wet[tail_start:]))) if len(wet) > tail_start else 0.0
    thresholds = {}
    for threshold in (-60, -80, -100):
        amplitude = 10.0 ** (threshold / 20.0)
        indices = np.flatnonzero(np.abs(wet) >= amplitude)
        thresholds[str(threshold)] = float(indices[-1] / rate) if len(indices) else 0.0
    return {
        "duration_seconds": len(wet) / rate,
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

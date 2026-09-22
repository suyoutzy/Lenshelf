#!/usr/bin/env python3
"""Detect short whole-frame luminance spikes in a Live MP4."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import tempfile
from pathlib import Path

import numpy as np


PTS_TIME = re.compile(r"pts_time:([0-9.\-]+)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mp4", type=Path)
    parser.add_argument("--ffmpeg", required=True, type=Path)
    args = parser.parse_args()
    width, height = 160, 120
    with tempfile.TemporaryDirectory() as temp:
        raw = Path(temp) / "video.gray"
        result = subprocess.run(
            [
                str(args.ffmpeg), "-hide_banner", "-loglevel", "info", "-i", str(args.mp4),
                "-map", "0:v:0", "-vf", f"scale={width}:{height},format=gray,showinfo",
                "-vsync", "0", "-f", "rawvideo", str(raw),
            ],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        if result.returncode:
            raise SystemExit(result.stderr)
        pts = np.array([float(value) for value in PTS_TIME.findall(result.stderr)], dtype=np.float64)
        frames = np.frombuffer(raw.read_bytes(), dtype=np.uint8).reshape((-1, height, width))
    count = min(len(pts), len(frames))
    pts = pts[:count]
    means = frames[:count].mean(axis=(1, 2))
    baseline = np.array([
        np.median(means[max(0, index - 3):min(count, index + 4)])
        for index in range(count)
    ])
    deltas = means - baseline
    threshold = max(8.0, float(np.median(np.abs(deltas - np.median(deltas))) * 8.0))
    spikes = [
        {"timeSec": round(float(pts[index]), 6), "meanLumaJump": round(float(deltas[index]), 3)}
        for index in range(1, count - 1)
        if deltas[index] >= threshold and deltas[index] > deltas[index - 1] and deltas[index] >= deltas[index + 1]
    ]
    output = {
        "frames": int(count),
        "threshold": round(threshold, 3),
        "maxPositiveJump": round(float(deltas.max(initial=0)), 3),
        "wholeFrameFlashCount": len(spikes),
        "spikes": spikes,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 1 if spikes else 0


if __name__ == "__main__":
    raise SystemExit(main())

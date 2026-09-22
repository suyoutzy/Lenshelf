#!/usr/bin/env python3
"""Estimate A/V sync from a visible impact and its recorded sound."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import tempfile
from pathlib import Path

import numpy as np


PTS_TIME = re.compile(r"pts_time:([0-9.\-]+)")


def local_peaks(values: np.ndarray, times: np.ndarray, count: int = 8) -> list[dict[str, float]]:
    candidates = [
        index
        for index in range(1, len(values) - 1)
        if values[index] >= values[index - 1] and values[index] >= values[index + 1]
    ]
    chosen: list[int] = []
    for index in sorted(candidates, key=lambda item: values[item], reverse=True):
        if all(abs(times[index] - times[prior]) >= 0.12 for prior in chosen):
            chosen.append(index)
        if len(chosen) == count:
            break
    return [
        {"timeSec": round(float(times[index]), 6), "score": round(float(values[index]), 3)}
        for index in chosen
    ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mp4", type=Path)
    parser.add_argument("--ffmpeg", required=True, type=Path)
    args = parser.parse_args()

    width, height = 160, 120
    with tempfile.TemporaryDirectory() as temp:
        raw_video = Path(temp) / "video.gray"
        video = subprocess.run(
            [
                str(args.ffmpeg), "-hide_banner", "-loglevel", "info", "-i", str(args.mp4),
                "-map", "0:v:0", "-vf", f"scale={width}:{height},format=gray,showinfo",
                "-vsync", "0", "-f", "rawvideo", str(raw_video),
            ],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        if video.returncode != 0:
            raise SystemExit(video.stderr)
        pts = np.array([float(value) for value in PTS_TIME.findall(video.stderr)], dtype=np.float64)
        frame_bytes = raw_video.read_bytes()
        frames = np.frombuffer(frame_bytes, dtype=np.uint8).reshape((-1, height, width))
        count = min(len(frames), len(pts))
        frames = frames[:count].astype(np.int16)
        pts = pts[:count]
        motion = np.abs(frames[1:] - frames[:-1]).mean(axis=(1, 2))
        motion_times = (pts[1:] + pts[:-1]) / 2.0

        audio = subprocess.run(
            [
                str(args.ffmpeg), "-hide_banner", "-loglevel", "error", "-i", str(args.mp4),
                "-map", "0:a:0", "-ac", "1", "-ar", "48000", "-f", "s16le", "-",
            ],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        if audio.returncode != 0:
            raise SystemExit(audio.stderr.decode(errors="replace"))
        samples = np.frombuffer(audio.stdout, dtype="<i2").astype(np.float64)
        window = 480
        usable = len(samples) // window * window
        rms = np.sqrt(np.mean(samples[:usable].reshape((-1, window)) ** 2, axis=1))
        audio_times = (np.arange(len(rms)) + 0.5) * window / 48000.0

    video_peaks = local_peaks(motion, motion_times)
    audio_peaks = local_peaks(rms, audio_times)
    strongest_audio_time = audio_peaks[0]["timeSec"] if audio_peaks else None
    motion_near_strongest_audio = []
    impact_pair = None
    if strongest_audio_time is not None:
        nearby = np.where(np.abs(motion_times - strongest_audio_time) <= 0.18)[0]
        motion_near_strongest_audio = [
            {"timeSec": round(float(motion_times[index]), 6), "score": round(float(motion[index]), 3)}
            for index in nearby
        ]
        closest_motion_index = int(np.argmin(np.abs(motion_times - strongest_audio_time)))
        impact_difference = abs(float(motion_times[closest_motion_index]) - strongest_audio_time)
        impact_pair = {
            "videoFrameCenterSec": round(float(motion_times[closest_motion_index]), 6),
            "audioPeakCenterSec": strongest_audio_time,
            "absoluteDifferenceMs": round(impact_difference * 1000.0, 3),
            "within50Ms": impact_difference <= 0.05,
        }
    pairs = sorted(
        (
            abs(video_peak["timeSec"] - audio_peak["timeSec"]),
            video_peak,
            audio_peak,
        )
        for video_peak in video_peaks[:5]
        for audio_peak in audio_peaks[:5]
        if abs(video_peak["timeSec"] - audio_peak["timeSec"]) <= 0.25
    )
    best = None
    if pairs:
        difference, video_peak, audio_peak = pairs[0]
        best = {
            "videoTimeSec": video_peak["timeSec"],
            "audioTimeSec": audio_peak["timeSec"],
            "absoluteDifferenceMs": round(difference * 1000.0, 3),
        }
    result = {
        "videoFrames": int(count),
        "videoPeaks": video_peaks,
        "audioPeaks": audio_peaks,
        "motionNearStrongestAudio": motion_near_strongest_audio,
        "impactPair": impact_pair,
        "closestPeakPair": best,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if best is not None else 1


if __name__ == "__main__":
    raise SystemExit(main())

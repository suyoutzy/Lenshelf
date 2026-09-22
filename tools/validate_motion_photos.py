#!/usr/bin/env python3
"""Validate Lenshelf Motion Photo containers and optionally decode their MP4 tails."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path


VIDEO_LENGTH = re.compile(rb'(?:Item:Length|GCamera:MicroVideoOffset)="(\d+)"')
PHOTO_PTS = re.compile(rb'GCamera:MotionPhotoPresentationTimestampUs="(\d+)"')


def validate(path: Path, extract_dir: Path | None, ffmpeg: Path | None) -> dict[str, object]:
    data = path.read_bytes()
    lengths = {int(match) for match in VIDEO_LENGTH.findall(data[: min(len(data), 512_000)])}
    pts_matches = PHOTO_PTS.findall(data[: min(len(data), 512_000)])
    errors: list[str] = []

    if data[:2] != b"\xff\xd8":
        errors.append("invalid JPEG SOI")
    if len(lengths) != 1:
        errors.append(f"expected one consistent video length, found {sorted(lengths)}")
        video_length = 0
    else:
        video_length = lengths.pop()
    photo_pts_us = int(pts_matches[0]) if pts_matches else None
    if photo_pts_us is None:
        errors.append("missing photo presentation timestamp")

    mp4 = data[-video_length:] if 0 < video_length < len(data) else b""
    if len(mp4) < 12 or mp4[4:8] != b"ftyp":
        errors.append("MP4 tail does not start with ftyp")

    extracted: Path | None = None
    decoded = None
    if mp4 and extract_dir is not None:
        extract_dir.mkdir(parents=True, exist_ok=True)
        extracted = extract_dir / f"{path.stem}.mp4"
        extracted.write_bytes(mp4)
    if extracted is not None and ffmpeg is not None:
        process = subprocess.run(
            [str(ffmpeg), "-v", "error", "-i", str(extracted), "-f", "null", "-"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        decoded = process.returncode == 0
        if not decoded:
            errors.append(f"FFmpeg decode failed: {process.stderr.strip()}")

    return {
        "file": path.name,
        "fileBytes": len(data),
        "videoBytes": video_length,
        "photoPresentationTimestampUs": photo_pts_us,
        "mp4Ftyp": len(mp4) >= 8 and mp4[4:8] == b"ftyp",
        "ffmpegDecoded": decoded,
        "valid": not errors,
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--extract-dir", type=Path)
    parser.add_argument("--ffmpeg", type=Path)
    args = parser.parse_args()

    paths: list[Path] = []
    for item in args.inputs:
        paths.extend(sorted(item.glob("*.jpg")) if item.is_dir() else [item])
    results = [validate(path, args.extract_dir, args.ffmpeg) for path in paths]
    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0 if results and all(item["valid"] for item in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())

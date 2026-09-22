"""Summarize debug probe evidence. Never promotes measured counters to a full QA pass.

Usage: python tools/analyze_live_probe.py app/build/live-probe
"""
import json
import sys
from pathlib import Path


def analyze(directory):
    events = [json.loads(line) for line in (directory / "events.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    by_type = lambda name: [event for event in events if event["event"] == name]
    result = {"run": directory.name, "fatal": by_type("fatal"), "complete": bool(by_type("files_complete"))}
    baseline = by_type("baseline_photo")
    if baseline:
        result.update(photos=len(baseline), resolution=[baseline[0]["width"], baseline[0]["height"]],
                      exposure_latency_ms=[event["latencyMs"] for event in baseline],
                      jpeg_ready_ms=[event["jpegReadyMs"] for event in baseline],
                      saf_timing="NOT_MEASURED")
        latency = [event["latencyMs"] for event in baseline]
        result["within_200ms_of_shutter"] = all(value is not None and abs(value) <= 200 for value in latency)
    exposures = by_type("exposure")
    exports = by_type("export")
    if exposures or by_type("capabilities"):
        result["capabilities"] = by_type("capabilities")
        result["summary"] = by_type("capture_summary")
        result["export_failures"] = by_type("export_failure")
        result["audio_warnings"] = by_type("audio_warning")
        delays = [item["latencyMs"] for item in exposures if item["latencyMs"] is not None]
        checks = {
            "50_exposures": len(exposures) == 50,
            "50_exports": len(exports) == 50,
            "latency_under_200ms": len(delays) == 50 and all(0 <= value <= 200 for value in delays),
            "photo_frame_under_33334us": len(exports) == 50 and all(item["nearestFrameErrorUs"] <= 33334 for item in exports),
            "normal_preroll": len(exports) == 50 and all(1_000_000 <= item["photoOffsetUs"] <= 1_500_000 for item in exports),
            "normal_postroll": len(exports) == 50 and all(966_666 <= item["postUs"] <= 1_000_000 for item in exports),
            "no_video_gap_over_100ms": len(exports) == 50 and all(item["maxFrameGapUs"] <= 100_000 for item in exports),
            "audio_present": len(exports) == 50 and all(item["audio"] for item in exports),
        }
        result.update(exposure_latency_ms=delays, exports=len(exports), checks=checks,
                      next_stage="BLOCKED" if not all(checks.values()) or result["fatal"] else "MANUAL_AV_QUALITY_AND_SOAK_QA_REQUIRED")
    result["full_acceptance"] = "NOT_PASSED: decoding/visual audio-sync, product SAF baseline and remaining scenarios require review"
    return result


if __name__ == "__main__":
    root = Path(sys.argv[1])
    results = [analyze(path.parent) for path in sorted(root.rglob("events.jsonl"))]
    print(json.dumps(results, ensure_ascii=False, indent=2))

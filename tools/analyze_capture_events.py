"""Analyze a Lenshelf product capture-events JSONL file exported with run-as."""

import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def ms_between(start, end):
    return (end["elapsedRealtimeNs"] - start["elapsedRealtimeNs"]) / 1_000_000


path = Path(sys.argv[1])
events = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
tasks = defaultdict(list)
for event in events:
    if event.get("taskId"):
        tasks[event["taskId"]].append(event)

rows = []
for task_id, task_events in tasks.items():
    by_name = {event["event"]: event for event in task_events}
    accepted = by_name.get("capture_accepted") or by_name.get("ordinary_capture_accepted")
    exposure = by_name.get("exposure_completed") or by_name.get("ordinary_exposure_completed")
    jpeg = by_name.get("jpeg_written") or by_name.get("ordinary_jpeg_completed")
    assembled = by_name.get("motion_assembled")
    saved = by_name.get("saf_saved")
    if not accepted:
        continue
    rows.append({
        "taskId": task_id,
        "kind": "live" if "capture_accepted" in by_name else "ordinary",
        "acceptedNs": accepted["elapsedRealtimeNs"],
        "exposureLatencyMs": exposure.get("acceptedToExposureMs") if exposure else None,
        "jpegLatencyMs": ms_between(accepted, jpeg) if jpeg else None,
        "assembledLatencyMs": ms_between(accepted, assembled) if assembled else None,
        "savedLatencyMs": ms_between(accepted, saved) if saved else None,
        "photoOffsetUs": by_name.get("segment_frozen", {}).get("photoOffsetUs"),
        "forcedShortTail": by_name.get("segment_frozen", {}).get("forcedShortTail"),
        "audio": by_name.get("segment_frozen", {}).get("audio"),
    })

rows.sort(key=lambda row: row["acceptedNs"])
intervals = [(b["acceptedNs"] - a["acceptedNs"]) / 1_000_000 for a, b in zip(rows, rows[1:])]
groups = []
for row in rows:
    if not groups or (row["acceptedNs"] - groups[-1][-1]["acceptedNs"]) / 1_000_000 > 2_000:
        groups.append([])
    groups[-1].append(row)
exposures = [row["exposureLatencyMs"] for row in rows if row["exposureLatencyMs"] is not None]
queue_counts = [event.get("count") for event in events if event["event"] == "queue_state"]
result = {
    "tasks": rows,
    "acceptedCount": len(rows),
    "groupCounts": [len(group) for group in groups],
    "allAcceptedSaved": bool(rows) and all(row["savedLatencyMs"] is not None for row in rows),
    "completeLiveCount": sum(
        row["kind"] == "live" and row["savedLatencyMs"] is not None
        and row["forcedShortTail"] is False and row["audio"] is True
        for row in rows
    ),
    "acceptedIntervalsMs": intervals,
    "exposureLatencyMs": exposures,
    "allExposureUnder200Ms": bool(exposures) and len(exposures) == len(rows) and all(0 <= value <= 200 for value in exposures),
    "meanExposureLatencyMs": statistics.mean(exposures) if exposures else None,
    "maxQueueCount": max(queue_counts) if queue_counts else None,
    "queueReachedEight": 8 in queue_counts,
    "rejectedCount": sum(event["event"] == "capture_rejected" for event in events),
    "failures": [event for event in events if event["event"] in {
        "capture_failed", "capture_submit_failed", "saf_failed", "photo_only", "recovery_failed"
    }],
}
print(json.dumps(result, ensure_ascii=False, indent=2))

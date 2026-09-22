"""Run the debug-only experiment on an explicitly selected, authorized Android device.

Does not install, grant permissions, erase app data, or write category folders.
The app must already contain LiveProbeActivity. Stops the previous app process.
"""
import argparse
import json
import subprocess
import time
from pathlib import Path

PACKAGE = "com.example.photocategorycamera"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["baseline", "zsl", "live1080", "live720"])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--groups", type=int, choices=range(1, 11), default=10)
    parser.add_argument("--warmup-seconds", type=float, default=2.5)
    parser.add_argument("--soak", action="store_true")
    parser.add_argument("--all-media", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    adb = [str(root / ".android-sdk/platform-tools/adb.exe"), "-s", args.serial]

    def call(*command, check=True):
        return subprocess.run([*adb, *command], capture_output=True, check=check, timeout=30)

    def files():
        result = call("shell", "run-as", PACKAGE, "ls", "files/live-probe", check=False)
        return set(result.stdout.decode().splitlines()) if result.returncode == 0 else set()

    before = files()
    call("shell", "am", "start", "-S", "-n", f"{PACKAGE}/.probe.LiveProbeActivity",
         "--es", "mode", args.mode, "--ei", "groups", str(args.groups),
         "--el", "warmupMs", str(round(max(1.0, min(15.0, args.warmup_seconds)) * 1000)),
         "--ez", "soak", str(args.soak).lower(), "--ez", "autorun", "true")
    deadline = time.monotonic() + (750 if args.soak else 100)
    run = None
    last_count = -1
    complete = False
    while time.monotonic() < deadline:
        if run is None:
            candidates = sorted(name for name in files() - before if name.endswith("_" + args.mode))
            run = candidates[-1] if candidates else None
        if run:
            content = call("exec-out", "run-as", PACKAGE, "cat", f"files/live-probe/{run}/events.jsonl").stdout
            # Ignore the final line if a concurrent append has not finished yet.
            lines = content.split(b"\n")[:-1]
            events = [json.loads(line) for line in lines if line.strip()]
            count = sum(event["event"] in ("export", "baseline_photo") for event in events)
            if count != last_count:
                print(f"{run}: {count} outputs", flush=True)
                last_count = count
            if any(event["event"] == "files_complete" for event in events):
                complete = True
                break
            if any(event["event"] == "fatal" for event in events):
                # Give asynchronous evidence writers time to finish.
                time.sleep(2)
                break
        time.sleep(2)
    if run is None:
        raise SystemExit("No run created: unlock the phone and check camera permission")
    output = root / "app/build/live-probe" / run
    output.mkdir(parents=True, exist_ok=True)
    names = call("shell", "run-as", PACKAGE, "ls", f"files/live-probe/{run}").stdout.decode().splitlines()
    for name in names:
        # Never turn arbitrary device filenames into host paths.
        if Path(name).name != name or "/" in name or "\\" in name:
            continue
        selected = name.endswith((".json", ".jsonl")) or args.all_media or name in {
            "baseline_0.jpg", "baseline_4.jpg", "shot_0.mp4", "shot_0_MP.jpg",
            "shot_24.mp4", "shot_24_MP.jpg", "shot_49.mp4", "shot_49_MP.jpg",
        }
        if selected:
            data = call("exec-out", "run-as", PACKAGE, "cat", f"files/live-probe/{run}/{name}").stdout
            (output / name).write_bytes(data)
    print(output, flush=True)
    if not complete:
        raise SystemExit("Probe incomplete/failed; inspect events.jsonl. This is not a pass.")


if __name__ == "__main__":
    main()

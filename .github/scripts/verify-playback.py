"""Verify native observations from the current CI run before allowing release publication."""

import json
import math
import os
import re
import sys
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_fields(pairs):
    fields = dict(pairs)
    require(len(fields) == len(pairs), "Duplicate JSON field")
    return fields


def integer(value, minimum=0):
    return type(value) is int and minimum <= value < 2**63


def verify(path, token, commit, video):
    require(re.fullmatch(r"[0-9a-f]{64}", token), "Missing or invalid workflow run token")
    require(re.fullmatch(r"[0-9a-f]{40}", commit), "Missing or invalid checkout commit")
    require(re.fullmatch(r"[A-Za-z0-9_-]{11}", video), "Invalid sample ID")
    require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= 65536, "Missing or invalid native report")
    report = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_fields)
    require(type(report["schema"]) is int and report["schema"] == 1, "Unsupported report schema")
    require((report["runToken"], report["commit"], report["video"]) == (token, commit, video),
            "Report belongs to another run, checkout or sample")
    hosts = {"youtube": "www.youtube.com", "nocookie": "www.youtube-nocookie.com", "yout-ube": "www.youtube-nocookie.com"}
    require(set(report["providers"]) == set(hosts), "Require exactly the three configured providers")

    for name, host in hosts.items():
        provider = report["providers"][name]
        require(provider["host"] == host, f"{name}: unexpected observed iframe host")
        selection = provider["selectionCommand"]
        require(integer(selection, 1), f"{name}: invalid selection command")

        def observation(point, state):
            require(point["ready"] is True and point["videoId"] == video and point["confirmedTargetVideoId"] == video,
                    f"{name}: SDK did not confirm the selected video")
            require(integer(point["appliedCommand"], selection), f"{name}: stale selection")
            require(type(point["state"]) is int and point["state"] == state and point["userPaused"] is (state == 2),
                    f"{name}: unexpected player state or manual-pause flag")
            seconds = point["currentTime"]
            require(type(seconds) in (int, float) and 0 <= seconds <= 1_000_000_000 and math.isfinite(seconds),
                    f"{name}: invalid playback clock")
            require(integer(point["receivedNanos"], -(2**63)), f"{name}: invalid observation timestamp")
            return seconds

        start = provider["playing"]
        advancing = provider["advancing"]
        start_time = observation(start, 1)
        require(observation(advancing, 1) >= start_time + 1.5 and advancing["receivedNanos"] > start["receivedNanos"],
                f"{name}: playback did not advance")
        if name != "youtube":
            continue

        t = provider["transport"]
        stages = ("beforePause", "paused", "held", "rewound", "resumed")
        require(set(t) == set(stages), "Missing or unexpected native transport observation")
        times = [observation(t[stage], state) for stage, state in zip(stages, (1, 2, 2, 2, 1))]
        before, paused, held, rewound, resumed = times
        stamps = [t[stage]["receivedNanos"] for stage in stages]
        require(advancing["receivedNanos"] <= stamps[0] and all(a < b for a, b in zip(stamps, stamps[1:])),
                "Transport observations must follow their native commands")
        for first, second in (("beforePause", "paused"), ("held", "rewound"), ("rewound", "resumed")):
            require(t[second]["appliedCommand"] > t[first]["appliedCommand"], f"No new native command acknowledged for {second}")
        require(before >= 4 and paused >= 4, "Pause did not preserve enough playback to test rewind")
        require(t["held"]["receivedNanos"] - t["paused"]["receivedNanos"] >= 700_000_000 and abs(held - paused) < 0.5,
                "Paused clock did not hold steady")
        require(abs(rewound - max(0, paused - 5)) < 1, "Native rewind did not reach the expected time")
        require(resumed >= rewound + 1.5, "Native resume did not advance playback")


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]), os.environ["EXPECTED_TOKEN"], os.environ["EXPECTED_COMMIT"], os.environ["EXPECTED_VIDEO"])
    except (OSError, KeyError, TypeError, ValueError) as error:
        sys.exit(f"Playback verification failed: {error}")
    print("Verified all three provider clocks and the native pause, rewind and resume observations.")

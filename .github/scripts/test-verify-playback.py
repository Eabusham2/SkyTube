"""Exercise the release policy with synthetic observations. No network or game session is used."""

import json
import runpy
import tempfile
import unittest
from pathlib import Path


verify = runpy.run_path(str(Path(__file__).with_name("verify-playback.py")))["verify"]
VIDEO = "aqz-KE-bpKQ"
TOKEN = "a" * 64
COMMIT = "f" * 40


def point(clock, timestamp, command=5, state=1):
    return {"ready": True, "videoId": VIDEO, "confirmedTargetVideoId": VIDEO, "appliedCommand": command,
            "state": state, "userPaused": state == 2, "currentTime": clock, "receivedNanos": timestamp}


def valid_report():
    providers = {}
    for key in ("youtube", "nocookie", "yout-ube"):
        host = "www.youtube.com" if key == "youtube" else "www.youtube-nocookie.com"
        providers[key] = {"host": host, "selectionCommand": 5,
                          "playing": point(1, 0), "advancing": point(4, 3_000_000_000)}
    providers["youtube"]["transport"] = {
        "beforePause": point(4, 3_000_000_000),
        "paused": point(4.2, 3_200_000_000, 6, 2),
        "held": point(4.2, 4_000_000_000, 6, 2),
        "rewound": point(0, 4_100_000_000, 7, 2),
        "resumed": point(1.8, 6_000_000_000, 8),
    }
    return {"schema": 1, "runToken": TOKEN, "commit": COMMIT, "video": VIDEO, "providers": providers}


class PlaybackVerificationTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "evidence.json"

    def check(self, report):
        self.path.write_text(json.dumps(report), encoding="utf-8")
        verify(self.path, TOKEN, COMMIT, VIDEO)

    def test_complete(self):
        self.check(valid_report())

    def test_unrun_and_stale(self):
        with self.assertRaises(ValueError):
            verify(self.path, TOKEN, COMMIT, VIDEO)
        for key, value in (("runToken", "b" * 64), ("commit", "c" * 40), ("video", "M7lc1UVf-VE")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check(valid_report() | {key: value})

    def test_exact_modes_hosts_and_sample(self):
        for case in ("missing", "extra", "host", "sample", "selection"):
            data = valid_report()
            modes = data["providers"]
            if case == "missing":
                del modes["nocookie"]
            elif case == "extra":
                modes["other"] = modes["youtube"]
            elif case == "host":
                modes["yout-ube"]["host"] = "www.youtube.com"
            elif case == "sample":
                modes["nocookie"]["advancing"]["confirmedTargetVideoId"] = "M7lc1UVf-VE"
            else:
                modes["youtube"]["playing"]["appliedCommand"] = 4
            with self.subTest(case=case), self.assertRaises(ValueError):
                self.check(data)

    def test_stalled_provider_and_absent_transport(self):
        data = valid_report()
        data["providers"]["yout-ube"]["advancing"]["currentTime"] = 1
        with self.assertRaises(ValueError):
            self.check(data)
        data = valid_report()
        del data["providers"]["youtube"]["transport"]["rewound"]
        with self.assertRaises(ValueError):
            self.check(data)

    def test_invalid_transport_observations(self):
        cases = (("held", "receivedNanos", 3_300_000_000), ("held", "currentTime", 5.2),
                 ("paused", "state", 1), ("rewound", "currentTime", 4.2),
                 ("resumed", "currentTime", 0.2), ("rewound", "appliedCommand", 6),
                 ("resumed", "currentTime", float("nan")))
        for stage, field, value in cases:
            data = valid_report()
            data["providers"]["youtube"]["transport"][stage][field] = value
            with self.subTest(stage=stage, field=field), self.assertRaises(ValueError):
                self.check(data)


if __name__ == "__main__":
    unittest.main()

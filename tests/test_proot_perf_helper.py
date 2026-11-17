import importlib.util
import json
from pathlib import Path
import signal
import tempfile
import unittest
from unittest.mock import patch


ASSET = Path(__file__).resolve().parents[1] / "app/src/main/assets/measure-proot.py"
SPEC = importlib.util.spec_from_file_location("measure_proot", ASSET)
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)


class ProotPerfHelperTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "snapshot.json"
        self.snapshot = {
            "version": 1, "tracer_pid": 123, "process_start_ticks": 456,
            "sequence": 7, "counters": {"events": 99},
        }
        self.path.write_text(json.dumps(self.snapshot))

    def test_current_session_snapshot_is_accepted(self):
        self.assertEqual(HELPER.read_snapshot(self.path, 123, 456), self.snapshot)

    def test_stale_pid_reuse_is_rejected_before_sending_signal(self):
        with patch.object(HELPER.os, "kill") as send:
            with self.assertRaises(RuntimeError):
                HELPER.request_snapshot(self.path, 123, 789)
            send.assert_not_called()

    def test_wrong_session_and_malformed_data_are_rejected(self):
        values = [[], dict(self.snapshot, version=2), dict(self.snapshot, tracer_pid=234),
                  dict(self.snapshot, sequence=False), dict(self.snapshot, counters=[]),
                  dict(self.snapshot, counters={"events": -1}),
                  dict(self.snapshot, counters={"events": True})]
        for value in values:
            with self.subTest(value=value):
                self.path.write_text(json.dumps(value))
                with self.assertRaises(RuntimeError):
                    HELPER.read_snapshot(self.path, 123, 456)

    def test_request_waits_for_new_sequence_with_one_signal(self):
        updated = dict(self.snapshot, sequence=8)
        with patch.object(HELPER, "read_snapshot", side_effect=[self.snapshot, self.snapshot, updated]), \
                patch.object(HELPER.os, "kill") as send, patch.object(HELPER.time, "sleep"):
            self.assertEqual(HELPER.request_snapshot(self.path, 123, 456), updated)
            send.assert_called_once_with(123, signal.SIGUSR2)

    def test_timeout_does_not_repeat_signal(self):
        with patch.object(HELPER, "read_snapshot", return_value=self.snapshot), \
                patch.object(HELPER.os, "kill") as send, patch.object(HELPER.time, "sleep"), \
                patch.object(HELPER.time, "monotonic", side_effect=[0, 0.1, 3.1]):
            with self.assertRaises(RuntimeError):
                HELPER.request_snapshot(self.path, 123, 456)
            send.assert_called_once_with(123, signal.SIGUSR2)

    def test_proc_stat_parser_handles_spaces_and_parentheses(self):
        fields = ["0"] * 30
        fields[0] = "S"
        fields[11], fields[12], fields[16], fields[19] = "55", "11", "0", "987"
        contents = "123 (a command ) with spaces) " + " ".join(fields)
        with patch.object(HELPER.Path, "read_text", return_value=contents):
            self.assertEqual(HELPER.process_stat(123),
                             {"cpu_ticks": 66, "nice": 0, "start_ticks": 987})


if __name__ == "__main__":
    unittest.main()

"""Exercise startup races without launching an emulator or waiting on real time."""

import subprocess
import unittest
from unittest.mock import Mock, patch

from tools.ci.run_emulator import (
    guest_diagnostics,
    install_fixture,
    wait_for_boot,
    wait_for_package_manager,
)


class GuestDiagnosticsTest(unittest.TestCase):
    @patch("builtins.print")
    def test_a_missing_service_does_not_prevent_other_crash_diagnostics(self, output):
        device = Mock(side_effect=[RuntimeError("offline"), "123", ""])
        guest_diagnostics(device)
        self.assertEqual(3, device.call_count)
        self.assertTrue(any("PIDs: 123" in c.args[0] for c in output.call_args_list))

    @patch("builtins.print")
    def test_crash_frames_and_classes_exclude_message_and_alert_text(self, output):
        device = Mock(
            side_effect=[
                "MemAvailable: 123 kB\nPrivate: secret",
                "123",
                (
                    "java.lang.OutOfMemoryError: secret alert body\n"
                    " at android.app.ActivityThread.main(ActivityThread.java:42)\n"
                    "Private message: secret"
                ),
            ]
        )
        guest_diagnostics(device)
        printed = "\n".join(c.args[0] for c in output.call_args_list)
        self.assertIn("OutOfMemoryError", printed)
        self.assertIn("ActivityThread.java:42", printed)
        self.assertNotIn("secret", printed)


class EmulatorBootTest(unittest.TestCase):
    def setUp(self):
        self.process = Mock()
        self.process.poll.return_value = None

    @patch("tools.ci.run_emulator.time.sleep")
    def test_an_initially_offline_transport_can_finish_booting(self, sleep):
        device = Mock(side_effect=[RuntimeError("device offline"), "1"])
        wait_for_boot(device, self.process)
        self.assertEqual(2, device.call_count)
        sleep.assert_called_once_with(2)

    @patch("tools.ci.run_emulator.time.sleep")
    def test_a_temporary_device_command_timeout_can_recover(self, sleep):
        device = Mock(side_effect=[subprocess.TimeoutExpired("adb", 10), "1"])
        wait_for_boot(device, self.process)
        self.assertEqual(2, device.call_count)

    def test_a_crashed_emulator_is_not_mistaken_for_a_transport_retry(self):
        self.process.poll.return_value = 1
        device = Mock()
        with self.assertRaisesRegex(RuntimeError, "exited before boot"):
            wait_for_boot(device, self.process)
        device.assert_not_called()

    @patch("tools.ci.run_emulator.time.sleep")
    @patch("tools.ci.run_emulator.time.monotonic", side_effect=[0, 0, 241])
    def test_an_unfinished_boot_stops_at_the_deadline(self, monotonic, sleep):
        device = Mock(return_value="0")
        with self.assertRaises(TimeoutError):
            wait_for_boot(device, self.process)
        device.assert_called_once()


class PackageReadinessTest(unittest.TestCase):
    @patch("tools.ci.run_emulator.time.sleep")
    def test_package_manager_can_finish_starting_after_boot_completed(self, sleep):
        process = Mock()
        process.poll.return_value = None
        device = Mock(
            side_effect=[
                RuntimeError("Broken pipe (32)"),
                "package:android\npackage:com.android.settings",
            ]
        )
        wait_for_package_manager(device, process)
        self.assertEqual(2, device.call_count)
        sleep.assert_called_once_with(1)

    @patch("tools.ci.run_emulator.time.sleep")
    def test_a_transient_install_transport_failure_can_recover(self, sleep):
        device = Mock(side_effect=[RuntimeError("Broken pipe (32)"), "Success"])
        install_fixture(device, "fixture.apk")
        self.assertEqual(2, device.call_count)

    @patch("tools.ci.run_emulator.time.sleep")
    def test_install_retries_are_bounded(self, sleep):
        device = Mock(side_effect=RuntimeError("Broken pipe (32)"))
        with self.assertRaises(RuntimeError):
            install_fixture(device, "fixture.apk")
        self.assertEqual(3, device.call_count)

    def test_a_permanent_package_rejection_is_not_retried(self):
        device = Mock(side_effect=RuntimeError("INSTALL_FAILED_INVALID_APK"))
        with self.assertRaises(RuntimeError):
            install_fixture(device, "fixture.apk")
        self.assertEqual(1, device.call_count)

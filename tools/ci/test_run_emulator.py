"""Exercise startup races without launching an emulator or waiting on real time."""

import subprocess
import unittest
from unittest.mock import Mock, patch

from tools.ci.run_emulator import (
    guest_diagnostics,
    install_fixture,
    user_zero_unlocked,
    wait_for_boot,
    wait_for_package_manager,
    wait_for_unlock,
)


class GuestUnlockTest(unittest.TestCase):
    def test_primary_user_state_handles_old_and_new_dumps(self):
        for value in ["{0=3, 10=1}", "[0=RUNNING_UNLOCKED, 10=RUNNING_LOCKED]"]:
            self.assertTrue(user_zero_unlocked("Started users state: " + value))
        for value in ["{0=1, 10=3}", "[10=RUNNING_UNLOCKED]", "[0=3, 0=1]", "[]"]:
            self.assertFalse(user_zero_unlocked("Started users state: " + value))
        self.assertFalse(user_zero_unlocked("State: RUNNING_UNLOCKED"))

    @patch("tools.ci.run_emulator.time.sleep")
    def test_a_locked_fresh_guest_uses_normal_keyguard_dismissal_before_retry(
        self, sleep
    ):
        device = Mock(
            side_effect=[
                "0",
                "Started users state: [0=RUNNING_LOCKED]",
                "",
                "",
                "Started users state: [0=RUNNING_UNLOCKED]",
            ]
        )
        wait_for_unlock(device)
        self.assertIn(
            ("shell", "wm", "dismiss-keyguard"), [c.args for c in device.call_args_list]
        )

    def test_a_different_current_user_is_not_automatically_unlocked(self):
        device = Mock(return_value="10")
        with self.assertRaisesRegex(RuntimeError, "primary user"):
            wait_for_unlock(device)
        device.assert_called_once()

    @patch("tools.ci.run_emulator.time.sleep")
    @patch("tools.ci.run_emulator.time.monotonic", side_effect=[0, 0, 91])
    def test_unlock_never_succeeds_from_another_profiles_unlocked_state(
        self, clock, sleep
    ):
        device = Mock(side_effect=["0", "Started users state: [0=1, 10=3]", "", ""])
        with self.assertRaises(TimeoutError):
            wait_for_unlock(device)


class GuestDiagnosticsTest(unittest.TestCase):
    @patch("builtins.print")
    def test_full_boot_reasons_require_the_explicit_pre_fixture_boundary(self, output):
        device = Mock(return_value="boot-only reason")
        guest_diagnostics(device)
        self.assertEqual(3, device.call_count)
        guest_diagnostics(device, boot_only=True)
        self.assertEqual(7, device.call_count)
        self.assertTrue(
            any(
                "Pre-fixture system failure log: boot-only reason" in c.args[0]
                for c in output.call_args_list
            )
        )

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

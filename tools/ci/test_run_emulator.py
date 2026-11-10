"""Exercise startup races without launching an emulator or waiting on real time."""

import base64
import subprocess
import unittest
from unittest.mock import Mock, patch

from tools.ci.run_emulator import (
    SYNTHETIC_PREVIEWS,
    export_synthetic_previews,
    guest_diagnostics,
    image_for_profile,
    install_fixture,
    main,
    own_notification_has_body,
    user_zero_unlocked,
    verify_avd_target,
    wait_for_alert_notification,
    wait_for_boot,
    wait_for_package_manager,
    wait_for_unlock,
)


class SyntheticPreviewTest(unittest.TestCase):
    @patch("builtins.print")
    def test_export_reads_only_explicit_synthetic_fixture_windows(self, output):
        encoded = base64.b64encode(b"\x89PNG\r\n\x1a\nsynthetic").decode()
        device = Mock(return_value=encoded)
        export_synthetic_previews(device)
        self.assertEqual(3, device.call_count)
        self.assertEqual(3, output.call_count)
        for call, name in zip(device.call_args_list, SYNTHETIC_PREVIEWS):
            self.assertEqual(
                (
                    "shell",
                    "run-as",
                    "xyz.gaon.codeclear",
                    "base64",
                    "-w",
                    "0",
                    "cache/" + name,
                ),
                call.args,
            )
            self.assertEqual({"timeout": 10}, call.kwargs)

    @patch("builtins.print")
    def test_oversized_preview_is_not_decoded_or_logged(self, output):
        with self.assertRaisesRegex(RuntimeError, "review limit"):
            export_synthetic_previews(Mock(return_value="A" * 524289))
        output.assert_not_called()

    @patch("builtins.print")
    def test_non_png_and_invalid_encodings_are_not_logged(self, output):
        for encoded in ("not base64!", base64.b64encode(b"private text").decode()):
            with self.subTest(encoded=encoded), self.assertRaises(RuntimeError):
                export_synthetic_previews(Mock(return_value=encoded))
        output.assert_not_called()

    def test_missing_review_images_fail_instead_of_silently_skipping_visual_checks(
        self,
    ):
        with self.assertRaisesRegex(RuntimeError, "missing fixture"):
            export_synthetic_previews(Mock(side_effect=RuntimeError("missing fixture")))


class NotificationDumpTest(unittest.TestCase):
    def dump(self, package="xyz.gaon.codeclear", tag="result-42"):
        return (
            "  Notification List:\n"
            f"    NotificationRecord(0x123: pkg={package} id=1 tag={tag} user=0)\n"
            "      android.text=String ([DRILL] Synthetic body)\n"
            "  mArchive=empty\n"
        )

    def test_only_an_owned_active_child_can_satisfy_the_body_check(self):
        self.assertTrue(
            own_notification_has_body(self.dump(), "[DRILL] Synthetic body")
        )
        for package, tag in [
            ("com.google.android.cellbroadcastreceiver", "result-42"),
            ("xyz.gaon.codeclear.other", "result-42"),
            ("xyz.gaon.codeclear", "translation-summary"),
        ]:
            self.assertFalse(
                own_notification_has_body(
                    self.dump(package, tag), "[DRILL] Synthetic body"
                )
            )

    def test_archived_or_different_body_notifications_do_not_prove_current_display(
        self,
    ):
        self.assertFalse(
            own_notification_has_body(self.dump(), "[DRILL] Different body")
        )
        self.assertFalse(
            own_notification_has_body(
                self.dump().replace(
                    "Notification List:", "Enqueued Notification List:"
                ),
                "[DRILL] Synthetic body",
            )
        )
        archived = "  Notification List:\n  mArchive=\n" + self.dump()
        self.assertFalse(own_notification_has_body(archived, "[DRILL] Synthetic body"))

    def test_physical_device_is_rejected_before_reading_notification_payloads(self):
        device = Mock(return_value="0")
        with self.assertRaisesRegex(RuntimeError, "disposable emulator"):
            wait_for_alert_notification(device, "[DRILL] Synthetic body")
        device.assert_called_once_with("shell", "getprop", "ro.kernel.qemu")


class DiagnosticArgumentsTest(unittest.TestCase):
    def test_dialer_probe_cannot_replace_capture_or_optimized_acceptance(self):
        for extra in ["--system-receiver", "--optimized"]:
            with (
                self.subTest(extra=extra),
                patch(
                    "sys.argv",
                    ["run_emulator", "--profile", "37-16k", "--dialer-only", extra],
                ),
                patch("sys.stderr"),
            ):
                with self.assertRaises(SystemExit) as error:
                    main()
                self.assertEqual(2, error.exception.code)

    def test_dialer_probe_rejects_unreviewed_profiles_before_any_environment_work(self):
        with (
            patch("sys.argv", ["run_emulator", "--profile", "30", "--dialer-only"]),
            patch("sys.stderr"),
        ):
            with self.assertRaises(SystemExit) as error:
                main()
            self.assertEqual(2, error.exception.code)


class SystemImageProfileTest(unittest.TestCase):
    def test_stale_zero_or_different_api_metadata_is_rejected_before_boot(self):
        for target in ["android-0", "android-36", "android-37beta", ""]:
            with self.assertRaises(RuntimeError):
                verify_avd_target("target=" + target, 37)
        with self.assertRaises(RuntimeError):
            verify_avd_target("target=android-37.0\ntarget=android-37.2", 37)
        for target in ["android-37", "android-37.0", "android-37.2"]:
            self.assertEqual(target, verify_avd_target("target=" + target + "\n", 37))

    def test_stable_16k_profiles_remain_distinct_from_default_images(self):
        self.assertEqual(
            "system-images;android-35;google_apis_ps16k;x86_64",
            image_for_profile("35-16k"),
        )
        self.assertEqual(
            "system-images;android-37.2;google_apis_ps16k;x86_64",
            image_for_profile("37-16k"),
        )
        self.assertEqual(
            "system-images;android-37.0;default;x86_64", image_for_profile("37")
        )
        self.assertEqual(
            "system-images;android-30;default;x86_64", image_for_profile("30")
        )
        with self.assertRaises(ValueError):
            image_for_profile("35;unexpected")


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

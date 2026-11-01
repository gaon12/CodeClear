"""Protect fixture consent boundaries and APK signer interpretation."""

import base64
import hashlib
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from tools.ci.optimized_runtime import (
    CONVERTED,
    ORIGINAL,
    certificate,
    local_fixture_options,
    replace_process,
    validate,
    verify_result,
)


class OptimizedFixtureTest(unittest.TestCase):
    def nodes(self, *texts):
        return [ET.Element("node", text=text) for text in texts]

    @patch("tools.ci.optimized_runtime.time.sleep")
    def test_both_exact_panels_must_belong_to_the_selected_fixture(self, sleep):
        ui = Mock()
        ui.layout.side_effect = [
            self.nodes("Close", ORIGINAL, CONVERTED),
            self.nodes("History"),
            self.nodes(ORIGINAL),
            self.nodes("View details", ORIGINAL),
            self.nodes(CONVERTED),
        ]
        verify_result(ui)
        self.assertEqual(
            ["Close", "History", ORIGINAL],
            [call.args[0].get("text") for call in ui.tap.call_args_list],
        )

    @patch("tools.ci.optimized_runtime.time.sleep")
    def test_a_prior_modal_result_cannot_mask_a_failed_selected_detail(self, sleep):
        ui = Mock()
        ui.layout.side_effect = [
            self.nodes("Close", ORIGINAL, CONVERTED),
            self.nodes("History"),
            self.nodes(ORIGINAL),
            *[
                self.nodes("View details", ORIGINAL, CONVERTED + " extra")
                for _ in range(12)
            ],
        ]
        with self.assertRaisesRegex(RuntimeError, "fixture detail"):
            verify_result(ui)

    def test_other_history_records_do_not_establish_fixture_capture(self):
        ui = Mock()
        ui.layout.side_effect = [
            self.nodes("History"),
            *[self.nodes("other") for _ in range(12)],
        ]
        with self.assertRaisesRegex(RuntimeError, "unique fixture"):
            verify_result(ui)

    def test_both_verified_apks_supply_pem_before_any_app_fixture_is_changed(self):
        properties = {
            "ro.kernel.qemu": "1",
            "ro.debuggable": "1",
            "ro.hardware": "ranchu",
            "ro.build.version.sdk": "30",
        }

        def device(*parts):
            if "getprop" in parts:
                return properties[parts[-1]]
            if "force-stop" in parts:
                raise AssertionError("Do not revoke capture access with force-stop")
            raise RuntimeError("fixture mutation checkpoint")

        def command(parts, **kwargs):
            if "debuggable" in parts:
                output = "false"
            elif "application-id" in parts:
                output = "xyz.gaon.codeclear"
            elif "verify" in parts:
                output = (
                    self.pem(b"same verified identity")
                    if "--print-certs-pem" in parts
                    else "V2 Signer: certificate SHA-256 digest: " + "a" * 64
                )
            else:
                output = ""
            return SimpleNamespace(stdout=output)

        with (
            patch("tools.ci.optimized_runtime.subprocess.run", side_effect=command),
            self.assertRaisesRegex(RuntimeError, "fixture mutation checkpoint"),
        ):
            validate(device, Path("/sdk"), "/android", "emulator-5554")

    def test_a_physical_device_is_rejected_before_any_fixture_or_signing_work(self):
        device = Mock(return_value="0")
        with self.assertRaisesRegex(RuntimeError, "owned disposable emulator"):
            validate(device, Path("/sdk"), "/android", "phone")
        device.assert_called_once_with("shell", "getprop", "ro.kernel.qemu")

    def test_a_16k_label_requires_the_actual_guest_page_size(self):
        properties = {
            "ro.kernel.qemu": "1",
            "ro.debuggable": "1",
            "ro.hardware": "ranchu",
            "ro.build.version.sdk": "37",
            "PAGE_SIZE": "4096",
        }
        for api in ["35", "37"]:
            properties["ro.build.version.sdk"] = api
            device = Mock(side_effect=lambda *parts: properties[parts[-1]])
            with self.assertRaisesRegex(RuntimeError, "actual 16 KiB pages"):
                validate(device, Path("/sdk"), "/android", "emulator-5554")
            self.assertFalse(
                any(
                    "install" in call.args or "am" in call.args
                    for call in device.call_args_list
                )
            )

    def test_an_unchanged_process_does_not_establish_restart_persistence(self):
        device = Mock(side_effect=["123", "Success", "123"])
        with self.assertRaisesRegex(RuntimeError, "did not replace the process"):
            replace_process(device, Path("candidate.apk"), Mock())

    def test_an_install_failure_does_not_launch_or_force_stop_the_app(self):
        device = Mock(side_effect=["123", "Failure"])
        start = Mock()
        with self.assertRaisesRegex(RuntimeError, "replacement failed"):
            replace_process(device, Path("candidate.apk"), start)
        start.assert_not_called()
        self.assertFalse(
            any("force-stop" in call.args for call in device.call_args_list)
        )

    def options(self, remote="false", notification="true"):
        return f'''<map>
        <boolean name="server_allowed" value="{remote}"/>
        <boolean name="notification_consent" value="{notification}"/>
        <boolean name="accessibility_consent" value="true"/>
        <boolean name="local_consent" value="false"/>
        <boolean name="setup_complete" value="true"/>
        <string name="target">ENGLISH</string>
        <string name="local_engine">mlkit</string>
        <string name="message_view_mode">SWITCH</string>
        <string name="ui_language"></string>
        </map>'''

    def test_the_local_fixture_retains_remote_denial_and_existing_capture_consents(
        self,
    ):
        nodes = {
            n.get("name"): n
            for n in ET.fromstring(local_fixture_options(self.options()))
        }
        self.assertEqual("false", nodes["server_allowed"].get("value"))
        self.assertEqual("true", nodes["notification_consent"].get("value"))
        self.assertEqual("true", nodes["accessibility_consent"].get("value"))
        self.assertEqual("TRADITIONAL_CHINESE", nodes["target"].text)

    def test_a_remote_enabled_profile_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "remote permission off"):
            local_fixture_options(self.options(remote="true"))

    def test_missing_normal_capture_consent_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "normal capture preparation"):
            local_fixture_options(self.options(notification="false"))

    def pem(self, value):
        return (
            "-----BEGIN CERTIFICATE-----\n"
            + base64.b64encode(value).decode("ascii")
            + "\n-----END CERTIFICATE-----"
        )

    def test_the_public_certificate_identity_does_not_depend_on_signer_labels(self):
        value = b"verified certificate fixture"
        self.assertEqual(
            hashlib.sha256(value).hexdigest(),
            certificate(
                "V3.0 Signer: (minSdkVersion=30, maxSdkVersion=2147483647)\n"
                + self.pem(value)
            ),
        )

    def test_multiple_signature_schemes_must_agree_on_one_identity(self):
        first = self.pem(b"same identity")
        self.assertEqual(certificate(first), certificate(first + "\n" + first))
        with self.assertRaisesRegex(RuntimeError, "one reviewed signing certificate"):
            certificate(first + "\n" + self.pem(b"different identity"))

    def test_missing_or_malformed_pem_certificates_are_rejected(self):
        for output in [
            "",
            "V2 Signer: certificate SHA-256 digest: " + "a" * 64,
            "-----BEGIN CERTIFICATE-----\n?\n-----END CERTIFICATE-----",
            "-----BEGIN CERTIFICATE-----\nA\n-----END CERTIFICATE-----",
        ]:
            with self.assertRaises(RuntimeError):
                certificate(output)

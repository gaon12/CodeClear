"""Protect fixture consent boundaries and APK signer interpretation."""

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import Mock

from tools.ci.optimized_runtime import certificate, local_fixture_options, validate


class OptimizedFixtureTest(unittest.TestCase):
    def test_a_physical_device_is_rejected_before_any_fixture_or_signing_work(self):
        device = Mock(return_value="0")
        with self.assertRaisesRegex(RuntimeError, "owned disposable emulator"):
            validate(device, Path("/sdk"), "/android", "phone")
        device.assert_called_once_with("shell", "getprop", "ro.kernel.qemu")

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

    def test_an_unsigned_or_ambiguous_certificate_report_is_rejected(self):
        for report in [
            "",
            "Signer #1 certificate SHA-256 digest: "
            + "a" * 64
            + "\n"
            + "Signer #1 certificate SHA-256 digest: "
            + "b" * 64,
        ]:
            with self.assertRaises(RuntimeError):
                certificate(report)

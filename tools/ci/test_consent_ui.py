"""Reject stale or unrelated windows before issuing consent UI input."""

import json
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import Mock, patch

from tools.ci.consent_ui import ConsentUi


class ConsentUiTest(unittest.TestCase):
    @patch("tools.ci.consent_ui.time.sleep")
    def test_empty_cold_start_snapshots_can_settle_without_issuing_input(self, sleep):
        ready = [ET.Element("node", text="Allow")]
        with patch.object(self.ui, "_layout_once", side_effect=[None, [], ready]):
            self.assertEqual(ready, self.ui.layout())
        self.assertEqual(2, sleep.call_count)
        self.device.assert_not_called()

    @patch("tools.ci.consent_ui.time.sleep")
    def test_an_empty_ui_stops_after_five_reads(self, sleep):
        with (
            patch.object(self.ui, "_layout_once", return_value=[]) as read,
            self.assertRaisesRegex(RuntimeError, "five snapshots"),
        ):
            self.ui.layout()
        self.assertEqual(5, read.call_count)

    def test_google_permission_windows_can_use_platform_permission_resources(self):
        package = "com.google.android.permissioncontroller"
        self.ui.permitted.add(package)
        self.device.return_value = self.focus.replace(
            "package=com.android.settings", "package=" + package
        )

        def dump(args, **kwargs):
            path = Path(
                next(a.split("=", 1)[1] for a in args if a.startswith("--output="))
            )
            path.write_text(
                json.dumps(
                    [
                        {
                            "type": "SYSTEM",
                            "layer": 8,
                            "content": [
                                {
                                    "text": "Allow",
                                    "resource-id": "com.android.permissioncontroller:id/permission_allow_button",
                                },
                                {
                                    "text": "foreign",
                                    "resource-id": "other.package:id/allow",
                                },
                            ],
                        }
                    ]
                )
            )

        with patch("tools.ci.consent_ui.subprocess.run", side_effect=dump):
            self.assertEqual(["Allow"], [n.get("text") for n in self.ui.layout()])

    def setUp(self):
        self.focus = (
            "mCurrentFocus=Window{abc u0 Friendly confirmation title}\n"
            "  Window #0 Window{abc u0 Friendly confirmation title}:\n"
            "    mOwnerUid=1000 showForAllUsers=false package=com.android.settings appop=NONE\n"
        )
        self.device = Mock(return_value=self.focus)
        self.ui = ConsentUi(
            self.device, "android", "emulator-5554", {"com.android.settings"}
        )

    def test_a_source_warning_cannot_be_tapped(self):
        self.device.return_value = self.focus.replace(
            "package=com.android.settings", "package=com.android.cellbroadcastreceiver"
        )
        with self.assertRaisesRegex(RuntimeError, "outside an owned"):
            self.ui.tap(ET.Element("node", package="com.android.settings"))
        self.assertEqual(1, self.device.call_count)

    def test_focus_changes_prevent_input(self):
        with self.assertRaisesRegex(RuntimeError, "changed before input"):
            self.ui.tap(ET.Element("node", package="xyz.gaon.codeclear"))
        self.assertEqual(1, self.device.call_count)

    def test_partial_offscreen_bounds_are_rejected(self):
        node = ET.Element(
            "node",
            package="com.android.settings",
            enabled="true",
            bounds="[0,1200][720,1300]",
        )
        with self.assertRaisesRegex(RuntimeError, "outside the owned viewport"):
            self.ui.tap(node)
        self.assertEqual(1, self.device.call_count)

    def test_background_hidden_and_foreign_nodes_are_excluded(self):
        def dump(args, **kwargs):
            path = Path(
                next(a.split("=", 1)[1] for a in args if a.startswith("--output="))
            )
            path.write_text(
                json.dumps(
                    [
                        {
                            "type": "APPLICATION",
                            "layer": 1,
                            "content": [{"text": "background"}],
                        },
                        {
                            "type": "APPLICATION",
                            "layer": 2,
                            "content": [
                                {"text": "Allow", "bounds": "[0,100][200,200]"},
                                {"text": "hidden", "hidden": True},
                                {"text": "offscreen", "off-screen": True},
                                {
                                    "text": "foreign",
                                    "resource-id": "other.package:id/button",
                                },
                            ],
                        },
                    ]
                )
            )

        with patch("tools.ci.consent_ui.subprocess.run", side_effect=dump):
            self.assertEqual(["Allow"], [n.get("text") for n in self.ui.layout()])

    def test_a_missing_fresh_output_is_not_replaced_with_an_old_dump(self):
        with (
            patch("tools.ci.consent_ui.subprocess.run"),
            self.assertRaises(FileNotFoundError),
        ):
            self.ui.layout()

    @patch("tools.ci.consent_ui.time.sleep")
    def test_unknown_focus_prevents_scrolling(self, sleep):
        self.device.return_value = "mCurrentFocus=null"
        with self.assertRaises(RuntimeError):
            self.ui.scroll()
        self.assertEqual(20, self.device.call_count)

    @patch("tools.ci.consent_ui.time.sleep")
    def test_a_temporary_null_focus_can_settle(self, sleep):
        self.device.side_effect = ["mCurrentFocus=null", self.focus]
        self.assertEqual("com.android.settings", self.ui.focused_package())
        sleep.assert_called_once_with(0.25)

    def test_focus_reads_the_display_section(self):
        self.assertEqual("com.android.settings", self.ui.focused_package())
        self.device.assert_called_once_with("shell", "dumpsys", "window")

    def test_a_settings_confirmation_can_use_a_package_only_window_title(self):
        self.device.return_value = self.focus.replace(
            "Friendly confirmation title", "com.android.settings"
        )
        self.assertEqual("com.android.settings", self.ui.focused_package())

    def test_an_unrelated_window_cannot_claim_a_permitted_owner_in_its_title(self):
        self.device.return_value = self.focus.replace(
            "Friendly confirmation title", "com.android.settings/.Settings"
        ).replace("package=com.android.settings", "package=other.package")
        with self.assertRaisesRegex(RuntimeError, "outside an owned"):
            self.ui.focused_package()

    def test_an_owned_settings_confirmation_can_be_a_system_window(self):
        def dump(args, **kwargs):
            path = Path(
                next(a.split("=", 1)[1] for a in args if a.startswith("--output="))
            )
            path.write_text(
                json.dumps(
                    [
                        {
                            "type": "SYSTEM",
                            "layer": 9,
                            "content": [
                                {
                                    "text": "status",
                                    "resource-id": "com.android.systemui:id/status",
                                },
                            ],
                        },
                        {
                            "type": "SYSTEM",
                            "layer": 8,
                            "content": [
                                {
                                    "text": "Allow",
                                    "resource-id": "com.android.settings:id/allow",
                                },
                            ],
                        },
                    ]
                )
            )

        with patch("tools.ci.consent_ui.subprocess.run", side_effect=dump):
            self.assertEqual(["Allow"], [n.get("text") for n in self.ui.layout()])

    def test_a_missing_sdk_settings_window_uses_the_scoped_confirmation_reader(self):
        def dump(args, **kwargs):
            path = Path(
                next(a.split("=", 1)[1] for a in args if a.startswith("--output="))
            )
            path.write_text("[]")

        with (
            patch("tools.ci.consent_ui.subprocess.run", side_effect=dump),
            patch.object(
                self.ui,
                "settings_confirmation",
                return_value=[ET.Element("node", text="Allow")],
            ) as fallback,
        ):
            self.assertEqual(["Allow"], [n.get("text") for n in self.ui.layout()])
            fallback.assert_called_once()

    def test_the_product_never_uses_the_transient_confirmation_reader(self):
        self.ui.permitted.add("xyz.gaon.codeclear")
        self.device.return_value = self.focus.replace(
            "package=com.android.settings", "package=xyz.gaon.codeclear"
        )

        def dump(args, **kwargs):
            path = Path(
                next(a.split("=", 1)[1] for a in args if a.startswith("--output="))
            )
            path.write_text("[]")

        with (
            patch("tools.ci.consent_ui.subprocess.run", side_effect=dump),
            patch.object(self.ui, "settings_confirmation") as fallback,
            self.assertRaisesRegex(RuntimeError, "No application window"),
        ):
            self.ui.layout()
        fallback.assert_not_called()

    def test_the_confirmation_reader_rejects_foreign_nodes_and_cleans_its_fresh_file(
        self,
    ):
        def command(*parts, **kwargs):
            if "dumpsys" in parts:
                return self.focus
            if "cat" in parts:
                return '<hierarchy><node package="com.android.cellbroadcastreceiver" text="OK"/></hierarchy>'
            return ""

        self.device.side_effect = command
        with self.assertRaisesRegex(RuntimeError, "confirmation was unavailable"):
            self.ui.settings_confirmation()
        calls = self.device.call_args_list
        dump = next(call.args[-1] for call in calls if "uiautomator" in call.args)
        self.assertRegex(dump, r"^/sdcard/codeclear-consent-[a-f0-9]{32}\.xml$")
        self.assertEqual(("shell", "rm", "-f", dump), calls[-1].args)

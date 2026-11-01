"""Reject stale or unrelated windows before issuing consent UI input."""

import json
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import Mock, patch

from tools.ci.consent_ui import ConsentUi


class ConsentUiTest(unittest.TestCase):
    def setUp(self):
        self.focus = "mCurrentFocus=Window{abc u0 com.android.settings/.Settings}"
        self.device = Mock(return_value=self.focus)
        self.ui = ConsentUi(
            self.device, "android", "emulator-5554", {"com.android.settings"}
        )

    def test_a_source_warning_cannot_be_tapped(self):
        self.device.return_value = (
            "mCurrentFocus=Window{abc u0 com.android.cellbroadcastreceiver/.Alert}"
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

    def test_unknown_focus_prevents_scrolling(self):
        self.device.return_value = "mCurrentFocus=null"
        with self.assertRaises(RuntimeError):
            self.ui.scroll()
        self.assertEqual(1, self.device.call_count)

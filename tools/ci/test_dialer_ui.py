"""Do not send testing-code keys to a missing or unrelated control."""

import unittest
import xml.etree.ElementTree as ET
from unittest.mock import Mock, patch

from tools.ci.dialer_ui import enter_testing_code


class DialerInputTest(unittest.TestCase):
    def google_pad(self, value):
        package = "com.google.android.dialer"
        return [
            ET.Element("node", {"resource-id": package + ":id/digits", "text": value}),
            *[
                ET.Element("node", {"resource-id": package + ":id/" + name})
                for name in ["deleteButton", "star", "pound", "two", "six", "seven"]
            ],
        ]

    def test_google_testing_code_uses_real_keypad_controls_and_never_calls(self):
        self.ui.focused_package.return_value = "com.google.android.dialer"
        first = self.google_pad("0")
        empty = self.google_pad("")
        self.ui.layout.side_effect = [first, first, empty, *[empty for _ in range(12)]]
        enter_testing_code(self.device, self.ui)
        names = [
            call.args[0].get("resource-id").split(":id/")[-1]
            for call in self.ui.tap.call_args_list
        ]
        self.assertEqual(
            [
                "digits",
                "deleteButton",
                "star",
                "pound",
                "star",
                "pound",
                "two",
                "six",
                "two",
                "seven",
                "pound",
                "star",
                "pound",
                "star",
            ],
            names,
        )
        self.assertFalse(
            any("KEYCODE_CALL" in call.args for call in self.device.call_args_list)
        )

    def test_unexpected_google_digits_are_neither_deleted_nor_called(self):
        self.ui.focused_package.return_value = "com.google.android.dialer"
        self.ui.layout.return_value = self.google_pad("123")
        with self.assertRaisesRegex(RuntimeError, "unexpected existing digits"):
            enter_testing_code(self.device, self.ui)
        self.ui.tap.assert_not_called()
        self.assertFalse(
            any("keyevent" in call.args for call in self.device.call_args_list)
        )

    @patch("tools.ci.dialer_ui.time.sleep")
    def test_google_clear_waits_for_the_next_layout_without_deleting_twice(self, sleep):
        self.ui.focused_package.return_value = "com.google.android.dialer"
        first = self.google_pad("0")
        empty = self.google_pad("")
        self.ui.layout.side_effect = [
            first,
            first,
            first,
            empty,
            *[empty for _ in range(12)],
        ]
        enter_testing_code(self.device, self.ui)
        sleep.assert_called_once_with(0.25)
        self.assertEqual(
            1,
            sum(
                call.args[0].get("resource-id").endswith("/deleteButton")
                for call in self.ui.tap.call_args_list
            ),
        )

    @patch("tools.ci.dialer_ui.time.sleep")
    def test_google_clear_stops_after_five_unchanged_snapshots(self, sleep):
        self.ui.focused_package.return_value = "com.google.android.dialer"
        self.ui.layout.return_value = self.google_pad("0")
        with self.assertRaisesRegex(RuntimeError, '"still_seed": true'):
            enter_testing_code(self.device, self.ui)
        self.assertEqual(7, self.ui.layout.call_count)
        self.assertEqual(4, sleep.call_count)
        self.assertEqual(2, self.ui.tap.call_count)

    def test_a_missing_google_symbol_stops_before_any_testing_code_is_typed(self):
        self.ui.focused_package.return_value = "com.google.android.dialer"
        first = self.google_pad("0")
        empty = [
            n for n in self.google_pad("") if not n.get("resource-id").endswith("/star")
        ]
        self.ui.layout.side_effect = [first, first, empty, empty]
        with self.assertRaisesRegex(RuntimeError, "keypad control is missing"):
            enter_testing_code(self.device, self.ui)
        self.assertEqual(2, self.ui.tap.call_count)

    def setUp(self):
        self.device = Mock(return_value="Status: ok")
        self.ui = Mock()
        self.ui.focused_package.return_value = "com.android.dialer"

    def test_a_missing_dialpad_does_not_receive_code_keys(self):
        self.ui.layout.return_value = []
        with self.assertRaisesRegex(RuntimeError, "normal dialpad"):
            enter_testing_code(self.device, self.ui)
        self.assertFalse(
            any("keyevent" in call.args for call in self.device.call_args_list)
        )
        self.ui.stop.assert_called_once()

    def test_focus_loss_prevents_code_input(self):
        self.ui.layout.return_value = [
            ET.Element("node", {"resource-id": "com.android.dialer:id/digits"})
        ]
        self.ui.focused_package.side_effect = ["com.android.dialer", "other.package"]
        with self.assertRaisesRegex(RuntimeError, "lost focus"):
            enter_testing_code(self.device, self.ui)
        self.assertFalse(
            any("keyevent" in call.args for call in self.device.call_args_list)
        )

    def test_the_digit_cursor_is_moved_before_clearing_the_intent_value(self):
        self.ui.layout.return_value = [
            ET.Element("node", {"resource-id": "com.android.dialer:id/digits"})
        ]
        enter_testing_code(self.device, self.ui)
        keys = self.device.call_args.args
        self.assertEqual(
            ("shell", "input", "keyevent", "KEYCODE_MOVE_END", "KEYCODE_DEL"), keys[:5]
        )
        self.assertNotIn("KEYCODE_CALL", keys)

    @patch("tools.ci.dialer_ui.time.sleep")
    def test_a_launcher_transition_can_finish_before_the_dialpad_is_read(self, sleep):
        self.ui.focused_package.side_effect = [
            RuntimeError("launcher still focused"),
            "com.android.dialer",
            "com.android.dialer",
        ]
        self.ui.layout.return_value = [
            ET.Element("node", {"resource-id": "com.android.dialer:id/digits"})
        ]
        enter_testing_code(self.device, self.ui)
        sleep.assert_called_once_with(0.5)

    @patch("tools.ci.dialer_ui.time.sleep")
    @patch("tools.ci.dialer_ui.time.monotonic", side_effect=[0, 0, 16])
    def test_an_unrelated_focused_app_never_receives_code_keys(self, monotonic, sleep):
        self.ui.focused_package.side_effect = RuntimeError("unrelated owner")
        with self.assertRaisesRegex(RuntimeError, "did not receive focus"):
            enter_testing_code(self.device, self.ui)
        self.assertFalse(
            any("keyevent" in call.args for call in self.device.call_args_list)
        )

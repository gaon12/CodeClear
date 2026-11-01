"""Do not send testing-code keys to a missing or unrelated control."""

import unittest
import xml.etree.ElementTree as ET
from unittest.mock import Mock, patch

from tools.ci.dialer_ui import enter_testing_code


class DialerInputTest(unittest.TestCase):
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

"""Do not send testing-code keys to a missing or unrelated control."""

import unittest
import xml.etree.ElementTree as ET
from unittest.mock import Mock

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

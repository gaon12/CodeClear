"""Enter the testing code only into the focused, owned system dialpad field."""

import json
import time


def tap_testing_code(device, ui, package, digits):
    if digits.get("text", "") not in {"", "0"}:
        raise RuntimeError("The owned dialer has unexpected existing digits")
    ui.tap(digits)
    if ui.focused_package() != package:
        raise RuntimeError("The dialer lost focus before testing-code input")
    device("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    if digits.get("text") == "0":
        delete = next(
            (
                n
                for n in ui.layout()
                if n.get("resource-id") == package + ":id/deleteButton"
            ),
            None,
        )
        if delete is None:
            raise RuntimeError("The owned dialpad backspace control is missing")
        ui.tap(delete)
    # Input injection can finish before the next non-idle layout reflects it.
    for attempt in range(5):
        cleared = [
            n for n in ui.layout() if n.get("resource-id") == package + ":id/digits"
        ]
        # The SDK reader omits the platform's showing-hint flag. The owned
        # English Google dialpad can expose its nonnumeric hint as node text.
        if len(cleared) == 1 and cleared[0].get("text", "") in {"", "Phone Number"}:
            break
        if attempt < 4:
            time.sleep(0.25)
    else:
        raise RuntimeError(
            "The dialer intent digit was not cleared through its keypad: "
            + json.dumps(
                {
                    "fields": len(cleared),
                    "still_seed": len(cleared) == 1 and cleared[0].get("text") == "0",
                    "text_lengths": [len(n.get("text", "")) for n in cleared[:3]],
                    "ascii_labels": [
                        text
                        for n in cleared[:3]
                        if (text := n.get("text", ""))
                        and len(text) <= 32
                        and all(c.isascii() and (c.isalpha() or c == " ") for c in text)
                    ],
                }
            )
        )
    # Follow the keypad's normal editing path for intent-filled digits.
    # Use only reviewed digit/symbol controls; never the call button.
    for name in [
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
    ]:
        button = next(
            (n for n in ui.layout() if n.get("resource-id") == package + ":id/" + name),
            None,
        )
        if button is None:
            raise RuntimeError(
                "The reviewed testing-code keypad control is missing: " + name
            )
        ui.tap(button)


def enter_testing_code(device, ui, *, keypad_controls=False):
    result = device(
        "shell", "am", "start", "-a", "android.intent.action.DIAL", "-d", "tel:0"
    )
    if "Error:" in result or not any(
        marker in result for marker in ["Starting:", "Status: ok"]
    ):
        raise RuntimeError("The normal dialer did not accept its launch intent")
    try:
        deadline = time.monotonic() + 15
        last_error = None
        while time.monotonic() < deadline:
            try:
                package = ui.focused_package()
                if package in {"com.android.dialer", "com.google.android.dialer"}:
                    break
            except RuntimeError as error:
                last_error = error
            time.sleep(0.5)
        else:
            raise RuntimeError(
                "The normal dialer did not receive focus: " + str(last_error)
            )
        nodes = ui.layout()
        print(
            "Dialer preparation: "
            + json.dumps(
                [
                    {
                        key: node.get(key, "")[:100]
                        for key in ["text", "content-desc", "resource-id"]
                    }
                    for node in nodes
                    if node.get("text") or node.get("content-desc")
                ][:25]
            ),
            flush=True,
        )
        digits = [
            node for node in nodes if node.get("resource-id") == package + ":id/digits"
        ]
        if len(digits) != 1:
            raise RuntimeError(
                "Review the normal dialpad before entering a testing code"
            )
        if keypad_controls and package == "com.google.android.dialer":
            tap_testing_code(device, ui, package, digits[0])
            return
        ui.tap(digits[0])
        ui.stop()
        if ui.focused_package() != package:
            raise RuntimeError("The dialer lost focus before testing-code input")
        # Clear the DIAL intent's digit regardless of the initial cursor position.
        # This also releases Dialer's intent-filled-digit guard through normal input.
        device(
            "shell",
            "input",
            "keyevent",
            "KEYCODE_MOVE_END",
            "KEYCODE_DEL",
            "KEYCODE_STAR",
            "KEYCODE_POUND",
            "KEYCODE_STAR",
            "KEYCODE_POUND",
            "KEYCODE_2",
            "KEYCODE_6",
            "KEYCODE_2",
            "KEYCODE_7",
            "KEYCODE_POUND",
            "KEYCODE_STAR",
            "KEYCODE_POUND",
            "KEYCODE_STAR",
        )
    finally:
        ui.stop()

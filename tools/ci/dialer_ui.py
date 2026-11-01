"""Enter the testing code only into the focused, owned system dialpad field."""

import json
import time


def enter_testing_code(device, ui):
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

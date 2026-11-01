"""Validate controlled receiver injection through ordinary app and Settings consent UI."""

import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

APP = "xyz.gaon.codeclear"
COMPONENTS = {
    "enabled_notification_listeners": "ProbeNotificationListener",
    "enabled_accessibility_services": "ProbeAccessibilityService",
}
BODIES = (
    "[DRILL] A: Stay indoors. Call 112.",
    "[DRILL] B: Avoid Gate 2. Call 112.",
    "[DRILL] C: Avoid Gate 3. Call 112.",
)


def validate(device, api):
    # Never clear an app, change grants, or inject a PDU on an owner's physical device.
    assert device("shell", "getprop", "ro.kernel.qemu") == "1"
    assert device("shell", "getprop", "ro.debuggable") == "1"
    assert device("shell", "getprop", "ro.hardware") in {"ranchu", "goldfish"}
    assert api in {30, 31, 35}
    assert "Success" in device("shell", "pm", "clear", APP)
    permitted_ui = {
        APP,
        "android",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    }

    def start_app():
        device("shell", "am", "start", "-f", "0x34000000", "-n", APP + "/.MainActivity")
        time.sleep(0.5)

    parents = {}

    def layout():
        nonlocal parents
        for _ in range(3):
            try:
                device(
                    "shell",
                    "uiautomator",
                    "dump",
                    "--compressed",
                    "/sdcard/codeclear-validation.xml",
                    timeout=20,
                )
                data = device("shell", "cat", "/sdcard/codeclear-validation.xml")
                root = ET.fromstring(data)
                parents = {child: parent for parent in root.iter() for child in parent}
                return list(root.iter("node"))
            except (ET.ParseError, subprocess.SubprocessError):
                time.sleep(0.5)
        raise RuntimeError("Could not read the disposable emulator's UI")

    def matches(node, labels):
        return any(
            label.casefold()
            in {line.casefold() for line in node.get("text", "").splitlines()}
            for label in labels
        )

    def tap(node):
        assert node.get("package") in permitted_ui, "Never acknowledge a source warning"
        assert node.get("enabled") == "true"
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        assert x2 > x1 and y2 > y1
        device("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(0.4)

    def click(*labels, scrolls=12):
        for attempt in range(scrolls + 1):
            nodes = [
                n
                for n in layout()
                if matches(n, labels)
                and n.get("enabled") == "true"
                and n.get("package") in permitted_ui
            ]
            if nodes:
                tap(nodes[-1])
                return
            if attempt < scrolls:
                device("shell", "input", "swipe", "360", "1030", "360", "380", "250")
        raise RuntimeError(
            "Expected consent/navigation control was unavailable: " + labels[0]
        )

    def grant_present(key):
        raw = device("shell", "settings", "get", "secure", key)
        return any(
            APP in part and part.endswith(COMPONENTS[key]) for part in raw.split(":")
        )

    def finish_grant(key, service_label):
        for _ in range(10):
            if grant_present(key):
                start_app()
                return
            nodes = layout()
            confirm = next(
                (
                    n
                    for n in nodes
                    if matches(n, ["Allow", "OK", "Use service"])
                    and n.get("enabled") == "true"
                ),
                None,
            )
            if confirm is not None:
                tap(confirm)
                continue
            entry = next(
                (
                    n
                    for n in nodes
                    if matches(n, [service_label, "CodeClear"])
                    and n.get("enabled") == "true"
                ),
                None,
            )
            if entry is not None:
                row = entry
                for _ in range(5):
                    switches = [
                        n for n in row.iter("node") if n.get("checkable") == "true"
                    ]
                    if len(switches) == 1 and switches[0].get("checked") == "false":
                        tap(switches[0])
                        break
                    row = parents.get(row)
                    if row is None:
                        tap(entry)
                        break
                else:
                    tap(entry)
                continue
            downloaded = next(
                (n for n in nodes if matches(n, ["Downloaded apps", "Installed apps"])),
                None,
            )
            if downloaded is not None:
                tap(downloaded)
            else:
                device("shell", "input", "swipe", "360", "1030", "360", "380", "250")
        raise RuntimeError(
            "System access was not enabled through the normal confirmation UI"
        )

    start_app()
    click("Next")
    if api >= 33:
        click("Result notifications")
        click("Allow", scrolls=0)
    click("Notification access")
    click("Open settings", scrolls=0)
    finish_grant(
        "enabled_notification_listeners", "CodeClear · Emergency notifications"
    )
    click("Accessibility access")
    click("Open settings", scrolls=0)
    finish_grant(
        "enabled_accessibility_services", "CodeClear · Emergency alert dialogs"
    )
    click("Next")
    click("Done")

    preferences = ET.fromstring(
        device("shell", "run-as", APP, "cat", "shared_prefs/mobile_options.xml")
    )
    values = {n.get("name"): n.get("value", n.text) for n in preferences}
    assert (
        values.get("notification_consent")
        == values.get("accessibility_consent")
        == "true"
    )
    assert values.get("server_allowed", "false") == "false"
    assert values.get("local_consent", "false") == "false"
    assert all(grant_present(key) for key in COMPONENTS)
    print(
        "Both app disclosures and Android capture confirmations completed; remote permission is off.",
        flush=True,
    )

    # All sources start with their fresh AVD defaults. No dialer testing mode is enabled.
    installed = set(device("shell", "pm", "list", "packages").splitlines())
    candidates = []
    for package in [
        "com.android.cellbroadcastreceiver",
        "com.android.cellbroadcastreceiver.module",
    ]:
        if "package:" + package in installed:
            path = device("shell", "pm", "path", package)
            assert path.startswith(
                ("package:/system/", "package:/product/", "package:/apex/")
            )
            candidates.append(package)
    assert len(candidates) == 1, (
        "Review an ambiguous or missing receiver before injection"
    )
    source = candidates[0]
    device(
        "shell",
        "am",
        "start",
        "-n",
        source + "/com.android.cellbroadcastreceiver.CellBroadcastListActivity",
    )
    device("shell", "input", "keyevent", "KEYCODE_HOME")

    def saved():
        literals = ",".join("'" + b.replace("'", "''") + "'" for b in BODIES)
        sql = (
            "SELECT DISTINCT original FROM alerts WHERE original IN (" + literals + ");"
        )
        output = device(
            "shell",
            shlex.join(
                ["run-as", APP, "sqlite3", "-readonly", "databases/alerts.db", sql]
            ),
        )
        return set(output.splitlines())

    def wait_for(expected):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if expected <= saved():
                return
            time.sleep(0.5)
        raise AssertionError(
            "The complete expected synthetic original set was not recovered"
        )

    def inject(body, sequence):
        payload = (
            sequence.to_bytes(2, "big")
            + bytes.fromhex("11124811")
            + body.encode("utf-16-be")
        )
        assert len(payload) <= 88 and len(payload) % 2 == 0
        payload += bytes.fromhex("000d") * ((88 - len(payload)) // 2)
        device(
            "shell",
            "am",
            "broadcast",
            "--user",
            "0",
            "-a",
            "com.android.internal.telephony.gsm.TEST_TRIGGER_CELL_BROADCAST",
            "--es",
            "pdu_string",
            payload.hex(),
            "--ei",
            "phone_id",
            "0",
        )

    inject(BODIES[0], 0x5101)
    wait_for({BODIES[0]})
    print(
        "Fresh-default receiver injection: exact original automatically persisted without a source action.",
        flush=True,
    )
    device("shell", "input", "keyevent", "KEYCODE_HOME")
    inject(BODIES[1], 0x5111)
    inject(BODIES[2], 0x5121)
    time.sleep(2)
    device("shell", "input", "keyevent", "KEYCODE_HOME")
    start_app()
    click("Open original system alert")
    # The source opens normally. Never press its confirmation or dismiss its warnings.
    wait_for(set(BODIES))
    print(
        "Stacked receiver injection: all three exact originals recovered, including the earlier original.",
        flush=True,
    )
    print(
        "Default source mode only; dialer testing-mode ON and physical RF are not validated.",
        flush=True,
    )

"""Validate controlled receiver injection through ordinary app and Settings consent UI."""

import json
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET


def _require(condition, message="Emulator validation condition failed"):
    if not condition:
        raise RuntimeError(message)


APP = "xyz.gaon.codeclear"
COMPONENTS = {
    "enabled_notification_listeners": "ProbeNotificationListener",
    "enabled_accessibility_services": "ProbeAccessibilityService",
}
BODIES = (
    "[DRILL] A: Stay indoors. Call 112.",
    "[DRILL] B: Avoid Gate 2. Call 112.",
    "[DRILL] C: Avoid Gate 3. Call 112.",
    "[DRILL] D: Stay clear. Call 112.",
    "[DRILL] E: Stay inside. Call 112.",
    "[DRILL] F: Avoid Gate 4. Call 112.",
    "[DRILL] G: Avoid Gate 5. Call 112.",
)


def validate(device, api):
    # Never clear an app, change grants, or inject a PDU on an owner's physical device.
    if device("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Receiver fixtures require a disposable emulator")
    if device("shell", "getprop", "ro.debuggable") != "1":
        raise RuntimeError("Receiver fixtures require a debug system image")
    if device("shell", "getprop", "ro.hardware") not in {"ranchu", "goldfish"}:
        raise RuntimeError("Receiver fixtures require reviewed emulator hardware")
    if api not in {30, 31, 35}:
        raise RuntimeError("Review this source profile before injection")
    _require("Success" in device("shell", "pm", "clear", APP))
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
        exposed = set()
        for attribute in ["text", "content-desc"]:
            value = node.get(attribute, "")
            exposed.add(" ".join(value.split()).casefold())
            exposed.update(
                " ".join(line.split()).casefold() for line in re.split(r"[\n,]", value)
            )
        return any(" ".join(label.split()).casefold() in exposed for label in labels)

    def tap(node):
        _require(
            node.get("package") in permitted_ui, "Never acknowledge a source warning"
        )
        _require(node.get("enabled") == "true")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        _require(x2 > x1 and y2 > y1)
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
        previous_screen = None
        for _ in range(10):
            if grant_present(key):
                start_app()
                return
            nodes = layout()
            # This runs only on a fresh owned AVD, before issuing any receiver messages.
            screen = [
                {
                    name: (n.get(name) or "")[:120]
                    for name in [
                        "text",
                        "content-desc",
                        "resource-id",
                        "class",
                        "checked",
                        "enabled",
                    ]
                }
                for n in nodes
                if n.get("package") in permitted_ui
                and (n.get("text") or n.get("content-desc"))
            ][:30]
            if screen != previous_screen:
                print(
                    "Capture grant screen: " + json.dumps(screen, ensure_ascii=False),
                    flush=True,
                )
                previous_screen = screen
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
            own_header = any(matches(n, [service_label, "CodeClear"]) for n in nodes)
            enable_row = next(
                (
                    n
                    for n in nodes
                    if own_header
                    and matches(
                        n,
                        [
                            "Allow notification access",
                            "Use CodeClear",
                            "Use " + service_label,
                        ],
                    )
                    and n.get("enabled") == "true"
                ),
                None,
            )
            if enable_row is not None:
                tap(enable_row)
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
    _require(
        values.get("notification_consent")
        == values.get("accessibility_consent")
        == "true"
    )
    _require(values.get("server_allowed", "false") == "false")
    _require(values.get("local_consent", "false") == "false")
    _require(all(grant_present(key) for key in COMPONENTS))
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
            _require(
                path.startswith(
                    ("package:/system/", "package:/product/", "package:/apex/")
                )
            )
            candidates.append(package)
    _require(
        len(candidates) == 1, "Review an ambiguous or missing receiver before injection"
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
        _require(len(payload) <= 88 and len(payload) % 2 == 0)
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
    wait_for(set(BODIES[:3]))
    print(
        "Stacked receiver injection: all three exact originals recovered, including the earlier original.",
        flush=True,
    )

    def source_label(name):
        for namespace in [source, "com.android.cellbroadcastreceiver"]:
            try:
                value = device(
                    "shell",
                    "cmd",
                    "overlay",
                    "lookup",
                    source,
                    namespace + ":string/" + name,
                )
                if value and len(value) <= 256 and "Error" not in value:
                    return value
            except RuntimeError:
                pass
        raise RuntimeError(
            "The installed source did not expose a reviewed testing-mode label"
        )

    enabled = source_label("testing_mode_enabled")
    disabled = source_label("testing_mode_disabled")
    _require(enabled != disabled)

    def mode_entries():
        raw = device(
            "shell",
            "logcat",
            "-d",
            "-t",
            "200",
            "-v",
            "epoch",
            "-s",
            "CellBroadcastReceiver:D",
        )
        return [line for line in raw.splitlines() if line.endswith((enabled, disabled))]

    def toggle_mode():
        before = set(mode_entries())
        device("shell", "input", "keyevent", "KEYCODE_HOME")
        device("shell", "am", "start", "-a", "android.intent.action.DIAL", "-d", "tel:")
        time.sleep(1)
        # Type through the ordinary dialer; never broadcast a privileged secret-code action.
        device("shell", shlex.join(["input", "text", "*#*#2627#*#*"]))
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            changed = [line for line in mode_entries() if line not in before]
            if changed:
                return changed[-1].endswith(enabled)
            time.sleep(0.5)
        raise RuntimeError(
            "The dialer did not produce a verified source testing-mode change"
        )

    # Observe the source's own state message, rather than assuming which state a toggle selected.
    if toggle_mode():
        _require(toggle_mode() is False)
    print(
        "Testing mode OFF confirmed by the installed source's own state message.",
        flush=True,
    )
    inject(BODIES[3], 0x5131)
    wait_for({BODIES[3]})
    print(
        "Explicit mode OFF: a fresh exact original was captured without a source action.",
        flush=True,
    )
    _require(toggle_mode() is True)
    try:
        print(
            "Testing mode ON confirmed by the installed source's own state message.",
            flush=True,
        )
        inject(BODIES[4], 0x5141)
        wait_for({BODIES[4]})
        device("shell", "input", "keyevent", "KEYCODE_HOME")
        inject(BODIES[5], 0x5151)
        inject(BODIES[6], 0x5161)
        time.sleep(2)
        device("shell", "input", "keyevent", "KEYCODE_HOME")
        start_app()
        click("Open original system alert")
        wait_for(set(BODIES))
        print(
            "Explicit mode ON: automatic capture and the complete seven-original set verified.",
            flush=True,
        )
    finally:
        _require(toggle_mode() is False)
    print(
        "Testing mode restored OFF. These receiver injections do not establish physical RF.",
        flush=True,
    )

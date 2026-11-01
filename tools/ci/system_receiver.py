"""Validate controlled receiver injection through ordinary app and Settings consent UI."""

import json
import re
import shlex
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


def validate(device, api, cli, serial, mode):
    from consent_ui import ConsentUi
    from dialer_ui import enter_testing_code

    _require(mode in {"off", "on"}, "Review the requested receiver mode")
    # Never clear an app, change grants, or inject a PDU on an owner's physical device.
    if device("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Receiver fixtures require a disposable emulator")
    if device("shell", "getprop", "ro.debuggable") != "1":
        raise RuntimeError("Receiver fixtures require a debug system image")
    if device("shell", "getprop", "ro.hardware") not in {"ranchu", "goldfish"}:
        raise RuntimeError("Receiver fixtures require reviewed emulator hardware")
    if api not in range(30, 38):
        raise RuntimeError("Review this source profile before injection")
    _require("Success" in device("shell", "pm", "clear", APP))
    application_label = device(
        "shell", "cmd", "overlay", "lookup", APP, APP + ":string/app_name"
    )
    _require(bool(application_label) and len(application_label) <= 128)
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

    ui = ConsentUi(device, cli, serial, permitted_ui)
    layout = ui.layout

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
        ui.tap(node)
        time.sleep(0.4)

    def click(*labels, scrolls=12):
        last_nodes = []
        for attempt in range(scrolls + 1):
            last_nodes = layout()
            nodes = [
                n
                for n in last_nodes
                if matches(n, labels)
                and n.get("enabled") == "true"
                and n.get("package") in permitted_ui
            ]
            if nodes:
                tap(nodes[-1])
                return
            if attempt < scrolls:
                ui.scroll()
        print(
            "Unavailable control screen: "
            + json.dumps(
                [
                    {
                        key: (n.get(key) or "")[:160]
                        for key in ["text", "content-desc", "package", "resource-id"]
                    }
                    for n in last_nodes
                    if n.get("package") in permitted_ui
                    and (n.get("text") or n.get("content-desc"))
                ][:35],
                ensure_ascii=False,
            ),
            flush=True,
        )
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
            own_header = any(
                matches(n, [service_label, application_label]) for n in nodes
            )
            enable_row = next(
                (
                    n
                    for n in nodes
                    if own_header
                    and matches(
                        n,
                        [
                            "Allow notification access",
                            "Use " + application_label,
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
                    if matches(n, [service_label, application_label])
                    and n.get("enabled") == "true"
                ),
                None,
            )
            if entry is not None:
                tap(entry)
                continue
            downloaded = next(
                (n for n in nodes if matches(n, ["Downloaded apps", "Installed apps"])),
                None,
            )
            if downloaded is not None:
                tap(downloaded)
            else:
                ui.scroll()
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

    def read_options():
        preferences = ET.fromstring(
            device("shell", "run-as", APP, "cat", "shared_prefs/mobile_options.xml")
        )
        return {n.get("name"): n.get("value", n.text) for n in preferences}

    for _ in range(6):
        if read_options().get("setup_complete") == "true":
            break
        click("Next", "Done")
    values = read_options()
    _require(
        values.get("setup_complete") == "true", "The normal tutorial did not finish"
    )
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

    def resume_capture():
        ui.stop()
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            metadata = device(
                "shell",
                "dumpsys",
                "activity",
                "service",
                APP + "/.probe.ProbeNotificationListener",
            )
            if "accessibilityConnected=true" in metadata:
                return
            time.sleep(0.5)
        raise RuntimeError("The capture service did not reconnect after UI automation")

    resume_capture()

    # All sources start with their fresh AVD defaults. No dialer testing mode is enabled.
    installed = set(device("shell", "pm", "list", "packages").splitlines())
    candidates = []
    for package in [
        "com.android.cellbroadcastreceiver",
        "com.android.cellbroadcastreceiver.module",
        "com.google.android.cellbroadcastreceiver",
    ]:
        if "package:" + package in installed:
            path = device("shell", "pm", "path", package)
            activity = device(
                "shell",
                "cmd",
                "package",
                "resolve-activity",
                "--brief",
                "-n",
                package
                + "/com.android.cellbroadcastreceiver.CellBroadcastListActivity",
            )
            print(
                "Installed receiver profile: "
                + json.dumps(
                    {
                        "package": package,
                        "path": path,
                        "activity": activity,
                    }
                ),
                flush=True,
            )
            _require(
                path.startswith(
                    ("package:/system/", "package:/product/", "package:/apex/")
                )
            )
            if package + "/" in activity:
                candidates.append(package)
    _require(
        len(candidates) == 1,
        "Review an ambiguous or missing receiver before injection: " + repr(candidates),
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
        absent = expected - saved()
        missing = [index + 1 for index, body in enumerate(BODIES) if body in absent]
        print("Missing synthetic original indices: " + repr(missing), flush=True)
        print(
            device(
                "shell",
                "dumpsys",
                "activity",
                "service",
                APP + "/.probe.ProbeNotificationListener",
            )[:12000],
            flush=True,
        )
        # Inspect only owned AVD logs; expose counts and known serials, never log text.
        logs = device("shell", "logcat", "-d", "-b", "all", "-t", "3000", "-v", "brief")
        serials = re.findall(
            r"(?:serialNumber|mSerialNumber|serial number)\s*[:=]\s*(0x[0-9a-fA-F]+|\d+)",
            logs,
        )
        known = {0x5101 + index * 0x10: index + 1 for index in range(len(BODIES))}
        seen = [
            known[value]
            for text in serials
            if (value := int(text, 16 if text.startswith("0x") else 10)) in known
        ]
        print("System pipeline fixture serial indices: " + repr(seen[-40:]), flush=True)
        print(
            "System pipeline event counts: "
            + json.dumps(
                {
                    phrase: logs.count(phrase)
                    for phrase in [
                        "TEST_TRIGGER_CELL_BROADCAST",
                        "Not a duplicate message",
                        "Duplicate message detected",
                        "Broadcast the message directly",
                        "Requesting location for geo-fencing",
                    ]
                }
            ),
            flush=True,
        )
        raise AssertionError(
            "The complete expected synthetic original set was not recovered"
        )

    incomplete_sets = []

    def check_complete_set(expected):
        try:
            wait_for(expected)
            return True
        except AssertionError:
            absent = expected - saved()
            missing = [index + 1 for index, body in enumerate(BODIES) if body in absent]
            incomplete_sets.append(missing)
            return False

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
            "3000",
            "-v",
            "epoch",
            "-s",
            "CellBroadcastReceiver:D",
        )
        return [line for line in raw.splitlines() if line.endswith((enabled, disabled))]

    def toggle_mode():
        before = set(mode_entries())
        device("shell", "input", "keyevent", "KEYCODE_HOME")
        dialer = ConsentUi(
            device, cli, serial, {"com.android.dialer", "com.google.android.dialer"}
        )
        enter_testing_code(device, dialer)
        resume_capture()
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            changed = [line for line in mode_entries() if line not in before]
            if changed:
                return changed[-1].endswith(enabled)
            time.sleep(0.5)
        raise RuntimeError(
            "The dialer did not produce a verified source testing-mode change"
        )

    # Each mode uses its own fresh guest so an older undismissed warning cannot
    # hide the first automatic-capture fixture in the other mode.
    desired = mode == "on"
    if toggle_mode() != desired:
        _require(toggle_mode() == desired)
    print(
        "Testing mode "
        + mode.upper()
        + " confirmed by the installed source's own state message.",
        flush=True,
    )
    try:
        inject(BODIES[0], 0x5101)
        wait_for({BODIES[0]})
        print(
            "Explicit mode "
            + mode.upper()
            + ": fresh exact original automatically persisted before any source action.",
            flush=True,
        )
        start_app()
        inject(BODIES[1], 0x5111)
        inject(BODIES[2], 0x5121)
        time.sleep(2)
        start_app()
        click("Open original system alert")
        resume_capture()
        if check_complete_set(set(BODIES[:3])):
            print(
                "Stacked receiver injection: all three exact originals recovered without acknowledging warnings.",
                flush=True,
            )
        start_app()
        for index in range(3, len(BODIES)):
            inject(BODIES[index], 0x5101 + index * 0x10)
        time.sleep(2)
        start_app()
        click("Open original system alert")
        resume_capture()
        if check_complete_set(set(BODIES)):
            print(
                "Explicit mode "
                + mode.upper()
                + ": complete seven-original set recovered.",
                flush=True,
            )
    finally:
        if desired:
            _require(toggle_mode() is False)
    _require(
        not incomplete_sets,
        "Incomplete synthetic original sets remain: " + repr(incomplete_sets),
    )
    print(
        "Testing mode OFF at completion. Receiver injection does not establish physical RF.",
        flush=True,
    )

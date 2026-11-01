"""Exercise an optimized APK using only disposable-guest fixtures and a CI debug key."""

import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

APP = "xyz.gaon.codeclear"
ORIGINAL = "【演练OPT】请远离车站，注意安全。"
CONVERTED = "【演練OPT】請遠離車站，注意安全。"


def local_fixture_options(xml):
    root = ET.fromstring(xml)
    values = {node.get("name"): node for node in root}
    if values["server_allowed"].get("value") != "false":
        raise RuntimeError("The optimized fixture requires remote permission off")
    for key in ["notification_consent", "accessibility_consent", "setup_complete"]:
        if values[key].get("value") != "true":
            raise RuntimeError(
                "Complete normal capture preparation before this fixture"
            )
    for key, value in {
        "target": "TRADITIONAL_CHINESE",
        "local_engine": "mlkit",
        "message_view_mode": "BOTH",
        "ui_language": "en",
    }.items():
        values[key].text = value
    values["local_consent"].set("value", "true")
    return ET.tostring(root, encoding="unicode")


def certificate(output):
    digests = set()
    for line in output.splitlines():
        if "certificate SHA-256 digest:" not in line:
            continue
        match = re.fullmatch(
            r"(?:Signer #\d+|V[1-4](?:\.1)? Signer):? certificate SHA-256 digest: ([0-9a-fA-F]{64})",
            line.strip(),
        )
        if match is None:
            raise RuntimeError("Review the unrecognized APK certificate report format")
        digests.add(match.group(1).lower())
    if len(digests) != 1:
        raise RuntimeError("The APK must expose one reviewed signing certificate")
    return next(iter(digests))


def validate(device, sdk, cli, serial):
    from consent_ui import ConsentUi

    if (
        device("shell", "getprop", "ro.kernel.qemu") != "1"
        or device("shell", "getprop", "ro.debuggable") != "1"
        or device("shell", "getprop", "ro.hardware") not in {"ranchu", "goldfish"}
        or device("shell", "getprop", "ro.build.version.sdk") not in {"30", "37"}
    ):
        raise RuntimeError("Optimized fixtures require an owned disposable emulator")

    if (
        device("shell", "getprop", "ro.build.version.sdk") == "37"
        and device("shell", "getconf", "PAGE_SIZE") != "16384"
    ):
        raise RuntimeError("The optimized API 37 fixture requires actual 16 KiB pages")

    def run(*parts):
        return subprocess.run(
            [str(part) for part in parts],
            check=True,
            capture_output=True,
            text=True,
            timeout=90,
        ).stdout.strip()

    unsigned = Path("app/build/outputs/apk/release/app-release-unsigned.apk")
    signed = unsigned.with_name("app-release-ci-fixture.apk")
    signer = sdk / "build-tools/37.0.0/apksigner"
    analyzer = sdk / "cmdline-tools/23.0/bin/apkanalyzer"
    if run(analyzer, "manifest", "debuggable", unsigned) != "false":
        raise RuntimeError("The optimized candidate is unexpectedly debuggable")
    if run(analyzer, "manifest", "application-id", unsigned) != APP:
        raise RuntimeError("The optimized candidate has an unexpected package")
    # This is the disposable runner's public Android debug key, never a production key.
    run(
        signer,
        "sign",
        "--ks",
        Path.home() / ".android/debug.keystore",
        "--ks-key-alias",
        "androiddebugkey",
        "--ks-pass",
        "pass:android",
        "--key-pass",
        "pass:android",
        "--out",
        signed,
        unsigned,
    )
    debug_certificate = certificate(
        run(
            signer,
            "verify",
            "--print-certs",
            "app/build/outputs/apk/debug/app-debug.apk",
        )
    )
    if certificate(run(signer, "verify", "--print-certs", signed)) != debug_certificate:
        raise RuntimeError(
            "Reject an optimized upgrade with a different signing certificate"
        )

    device("shell", "am", "force-stop", APP)
    options = device("shell", "run-as", APP, "cat", "shared_prefs/mobile_options.xml")
    # Seed only this debug fixture's local target/view choices before the non-debuggable
    # upgrade. Normal OS capture confirmations were already completed by the receiver test.
    device(
        "shell",
        shlex.join(
            ["run-as", APP, "sh", "-c", "cat > shared_prefs/mobile_options.xml"]
        ),
        input=local_fixture_options(options),
    )
    if "Success" not in device("install", "-r", str(signed)):
        raise RuntimeError("The optimized upgrade did not install")
    ui = ConsentUi(device, cli, serial, {APP})

    def start():
        output = device(
            "shell",
            "am",
            "start",
            "-W",
            "-f",
            "0x34000000",
            "-n",
            APP + "/.MainActivity",
        )
        if "Status: ok" not in output:
            raise RuntimeError("The optimized application did not start")
        time.sleep(1)

    def reconnect():
        ui.stop()
        for _ in range(30):
            meta = device(
                "shell",
                "dumpsys",
                "activity",
                "service",
                APP + "/.probe.ProbeNotificationListener",
            )
            if (
                "notificationConnected=true" in meta
                and "accessibilityConnected=true" in meta
            ):
                return
            time.sleep(0.5)
        raise RuntimeError(
            "Capture services did not reconnect after the optimized upgrade"
        )

    def open_source():
        for _ in range(12):
            nodes = ui.layout()
            found = next(
                (n for n in nodes if n.get("text") == "Open original system alert"),
                None,
            )
            if found is not None:
                ui.tap(found)
                reconnect()
                return
            ui.scroll()
        raise RuntimeError("The optimized source action was unavailable")

    def verify_result():
        for _ in range(8):
            ui.focused_package()
            device("shell", "input", "swipe", "360", "380", "360", "1030", "250")
        seen = set()
        for _ in range(8):
            seen.update(node.get("text") for node in ui.layout())
            if {ORIGINAL, CONVERTED} <= seen:
                return
            ui.scroll()
            time.sleep(0.5)
        raise RuntimeError(
            "The optimized UI did not expose the exact original and local conversion"
        )

    wifi = device("shell", "settings", "get", "global", "wifi_on") == "1"
    data = device("shell", "settings", "get", "global", "mobile_data") == "1"
    try:
        device("shell", "svc", "wifi", "disable")
        device("shell", "svc", "data", "disable")
        start()
        reconnect()
        payload = (
            (0x5201).to_bytes(2, "big")
            + bytes.fromhex("11124811")
            + ORIGINAL.encode("utf-16-be")
        )
        if len(payload) > 88 or len(payload) % 2:
            raise RuntimeError("Invalid optimized receiver fixture")
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
        time.sleep(3)
        start()
        open_source()
        time.sleep(2)
        start()
        verify_result()
        print(
            "Optimized non-debuggable APK: exact genuine-source original and local conversion matched offline.",
            flush=True,
        )
        ui.stop()
        device("shell", "am", "force-stop", APP)
        start()
        verify_result()
        print(
            "Optimized original and local result persisted after a process restart.",
            flush=True,
        )
    finally:
        ui.stop()
        device("shell", "svc", "wifi", "enable" if wifi else "disable")
        device("shell", "svc", "data", "enable" if data else "disable")

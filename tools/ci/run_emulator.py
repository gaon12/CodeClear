"""Run a reviewed fixture allowlist on a disposable, resource-bounded Linux AVD."""

import argparse
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path


def _require(condition, message="Emulator validation condition failed"):
    if not condition:
        raise RuntimeError(message)


# Exclude live providers, model downloads, installed voices, radios, and owner-granted access tests.
FIXTURES = {
    "mobile.SettingsDetailLayoutDeviceTest": 3,
    "mobile.AutomaticSpeechDeviceTest": 3,
    "mobile.MobileNavigationLayoutDeviceTest": 3,
    "mobile.StandardTranslationDeviceTest": 8,
    "mobile.StandardTranslationScreenDeviceTest": 1,
    "mobile.HomeCaptureStatusDeviceTest": 2,
    "mobile.MobileControllerDeviceTest": 22,
    "mobile.HistoryWorkerDeviceTest": 6,
    "mobile.AlertCaptureIngestorDeviceTest": 5,
    "mobile.AlertHistoryStoreDeviceTest": 25,
    "mobile.ObservationGroupsDeviceTest": 8,
    "mobile.ContentHistoryDeviceTest": 6,
    "mobile.MobileRuntimeDeviceTest": 1,
    "mobile.MobileOptionsStorageDeviceTest": 10,
    "nearby.NearbySharingDeviceTest": 5,
    "mobile.DeferredAlertPresentationDeviceTest": 3,
    "capture.SourcePayloadDeviceTest": 10,
    "capture.SourceTitleDeviceTest": 1,
    "capture.SourceActionDeviceTest": 1,
    "capture.CaptureObserverDeviceTest": 3,
    "capture.CaptureServiceComponentsDeviceTest": 2,
    "capture.SourceWindowVisibilityDeviceTest": 5,
    "mobile.MobileScreenAccessDeviceTest": 10,
    "mobile.MobileScreenModelSpeechDeviceTest": 7,
    "mobile.MobileScreenAlertDetailDeviceTest": 17,
    "mobile.MobileScreenNearbyDeviceTest": 4,
    "mobile.MobileScreenHistoryDeviceTest": 12,
    "mobile.MobileScreenSettingsDeviceTest": 14,
    "mobile.MobileScreenEngineDeviceTest": 16,
    "mobile.NotificationContentDeviceTest": 1,
    "mobile.TextTransferDeviceTest": 3,
    "translation.OwnedServerTransportDeviceTest": 7,
    "translation.ServerCredentialStoreDeviceTest": 4,
    "translation.OwnedServerEngineDeviceTest": 8,
    "mobile.UiLocaleResourcesDeviceTest": 1,
    "mobile.OwnedServerConsentDeviceTest": 3,
    "probe.PreviewDeviceTest": 3,
    "mobile.ModelInventoryDeviceTest": 4,
    "mobile.ModelPreparationDeviceTest": 7,
    "mobile.OriginalLanguageIdentifierDeviceTest": 1,
    "mobile.SpeechPlaybackDeviceTest#stopBeforeInitializationNeverQueuesSpeech": 1,
    "mobile.SpeechPlaybackDeviceTest#replacementDuringInitializationOnlySpeaksTheLatestText": 1,
    "mobile.SpeechPlaybackDeviceTest#cancellationDuringVoiceQueryAndLateCompletionCannotRestorePlayback": 1,
    "mobile.SpeechPlaybackDeviceTest#missingOrNetworkVoicesNeverSpeakAndInitializationFailureCanRetry": 1,
    "mobile.SpeechPlaybackDeviceTest#focusLossAndCompletionReleaseTheCurrentRequest": 1,
    "mobile.SpeechPlaybackDeviceTest#unicodeChunksPreserveTheWholeTextAndVoiceSelectionRequiresInstalledOfflineData": 1,
    "translation.SystemEngineDeviceTest": 8,
    "translation.SystemTranslationProtocolDeviceTest": 1,
    "translation.MyMemoryEngineDeviceTest": 1,
    "translation.MyMemoryTransportDeviceTest": 6,
    "translation.LocalEngineDeviceTest#bundledLanguageIdentificationRunsWithoutAModelDownload": 1,
    "translation.LocalEngineDeviceTest#chineseScriptConversionRunsOnTheDeviceAndPreservesNumbers": 1,
    "translation.LocalEngineDeviceTest#platformChineseConversionPreservesNumbersPunctuationAndOtherScriptsInBothDirections": 1,
}


def command(*args, timeout=120, **kwargs):
    return subprocess.run(args, check=True, text=True, timeout=timeout, **kwargs)


def own_notification_has_body(dump, body):
    active = re.search(r"(?ms)^  Notification List:[ \t]*\n(.*?)(?=^  \S|\Z)", dump)
    if active is None:
        return False
    for record in re.split(r"(?m)^[ \t]*NotificationRecord\(", active.group(1))[1:]:
        header = record.splitlines()[0]
        if not re.search(r"\bpkg=xyz\.gaon\.codeclear(?=[\s,)])", header):
            continue
        if not re.search(r"\btag=result-\d+(?=[\s,)])", header):
            continue
        values = re.findall(
            r"(?m)^[ \t]*android\.text=String[ \t]*\((.*)\)[ \t]*$", record
        )
        if body in values:
            return True
    return False


def wait_for_alert_notification(device, body):
    _require(
        device("shell", "getprop", "ro.kernel.qemu") == "1",
        "Notification fixtures require a disposable emulator",
    )
    deadline = time.monotonic() + 15
    dump = ""
    while time.monotonic() < deadline:
        dump = device("shell", "dumpsys", "notification", "--noredact", timeout=10)
        if own_notification_has_body(dump, body):
            print(
                "Own alert notification contains the exact source body with translation disabled.",
                flush=True,
            )
            return
        time.sleep(0.25)
    headers = [
        line.strip()[:300]
        for line in dump.splitlines()
        if line.lstrip().startswith("NotificationRecord(")
        and re.search(r"\bpkg=xyz\.gaon\.codeclear(?=[\s,)])", line)
    ]
    print("Own notification record metadata: " + repr(headers[:21]), flush=True)
    raise RuntimeError("The owned app did not post its captured synthetic alert body")


def wait_for_boot(device, process, timeout=240):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Emulator exited before boot; inspect emulator.log")
        try:
            if device("shell", "getprop", "sys.boot_completed", timeout=10) == "1":
                return
        except (subprocess.SubprocessError, OSError, RuntimeError):
            # The owned emulator can be absent or offline while its ADB transport starts.
            pass
        time.sleep(2)
    raise TimeoutError("Emulator did not finish booting within the allowed time")


def image_for_profile(profile):
    if profile not in {str(api) for api in range(30, 38)} | {"35-16k", "37-16k"}:
        raise ValueError("Review the requested system image profile")
    api = int(profile.split("-", 1)[0])
    platform = "37.2" if profile == "37-16k" else "37.0" if api == 37 else str(api)
    tag = "google_apis_ps16k" if profile.endswith("-16k") else "default"
    return f"system-images;android-{platform};{tag};x86_64"


def verify_avd_target(text, api):
    targets = re.findall(r"(?m)^target=(\S+)\s*$", text)
    if (
        len(targets) != 1
        or re.fullmatch(rf"android-{api}(?:\.[0-9]+)?", targets[0]) is None
    ):
        raise RuntimeError(
            "AVD metadata does not match the selected Android API: " + repr(targets)
        )
    return targets[0]


def wait_for_package_manager(device, process, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Emulator exited before PackageManager was ready")
        try:
            packages = device(
                "shell", "cmd", "package", "list", "packages", "android", timeout=10
            )
            if "package:android" in packages.splitlines():
                return
        except (subprocess.SubprocessError, OSError, RuntimeError):
            pass
        time.sleep(1)
    raise TimeoutError("PackageManager did not become ready before APK installation")


def install_fixture(device, apk):
    for attempt in range(3):
        try:
            result = device("install", "--no-streaming", "-r", "-t", apk, timeout=120)
            _require("Success" in result, "The fixture APK did not install")
            return
        except RuntimeError as error:
            transient = any(
                marker in str(error).casefold()
                for marker in [
                    "broken pipe",
                    "device offline",
                    "dead object",
                    "binder has died",
                ]
            )
            if not transient or attempt == 2:
                raise
            print(
                f"Retrying transient APK transport failure ({attempt + 1}/3).",
                flush=True,
            )
            time.sleep(2)


def user_zero_unlocked(output):
    match = re.search(r"(?m)^\s*Started users state: [\[{]([^\]}\n]*)[\]}]", output)
    if match is None:
        return False
    states = [
        entry.split("=", 1)[1].strip()
        for entry in match.group(1).split(",")
        if "=" in entry and entry.split("=", 1)[0].strip() == "0"
    ]
    return len(states) == 1 and states[0] in {"3", "RUNNING_UNLOCKED"}


def wait_for_unlock(device, timeout=90):
    if device("shell", "am", "get-current-user") != "0":
        raise RuntimeError("The fresh guest must use its primary user")
    deadline = time.monotonic() + timeout
    attempts = 0
    while time.monotonic() < deadline:
        if user_zero_unlocked(device("shell", "dumpsys", "user", timeout=10)):
            return
        if attempts < 3:
            # This fresh owned AVD has no credential. Dismiss only its keyguard,
            # before installing apps or issuing any receiver warnings.
            device("shell", "wm", "dismiss-keyguard")
            device("shell", "input", "swipe", "360", "1100", "360", "250", "300")
        attempts += 1
        time.sleep(1)
    raise TimeoutError("The guest primary user's credential storage did not unlock")


def guest_diagnostics(device, boot_only=False):
    """Keep system-death evidence bounded without printing receiver message text."""
    for parts in [
        ("shell", "cat", "/proc/meminfo"),
        ("shell", "pidof", "system_server"),
        ("shell", "logcat", "-b", "crash", "-d", "-t", "80"),
    ]:
        try:
            output = device(*parts, timeout=10)
            lines = []
            for line in output.splitlines():
                if parts[-1] == "/proc/meminfo":
                    if re.match(
                        r"Mem(?:Total|Free|Available):|Swap(?:Total|Free):", line
                    ):
                        lines.append(line)
                elif parts[-1] == "system_server":
                    if re.fullmatch(r"[0-9 ]*", line):
                        lines.append("system_server PIDs: " + line)
                else:
                    exception = re.search(r"([\w.$]+(?:Exception|Error))(?=:|$)", line)
                    frame = re.search(r"\bat ([\w.$]+\([^)]{0,180}\))", line)
                    if exception:
                        lines.append("Crash exception class: " + exception.group(1))
                    if frame:
                        lines.append("Crash frame: " + frame.group(1))
            print("Guest diagnostic: " + "\n".join(lines[:40]), flush=True)
        except (subprocess.SubprocessError, OSError, RuntimeError):
            print(
                "Guest diagnostic command unavailable: " + repr(parts[:3]), flush=True
            )
    if boot_only:
        # No fixtures or receiver messages exist yet on this disposable guest.
        # Retain boot failure reasons that cannot be inferred from class names.
        try:
            print(
                "Pre-fixture system failure log: "
                + device(
                    "shell", "logcat", "-b", "crash", "-d", "-t", "60", timeout=10
                )[-8000:],
                flush=True,
            )
        except (subprocess.SubprocessError, OSError, RuntimeError):
            print("Pre-fixture crash buffer unavailable.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    profiles = [str(api) for api in range(30, 38)] + ["35-16k", "37-16k"]
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--api", type=int, choices=range(30, 38))
    selection.add_argument("--profile", choices=profiles)
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--system-receiver", action="store_true")
    parser.add_argument("--receiver-mode", choices=["off", "on"], default="off")
    parser.add_argument("--optimized", action="store_true")
    parser.add_argument("--dialer-only", action="store_true")
    args = parser.parse_args()
    profile = args.profile or str(args.api)
    args.api = int(profile.split("-", 1)[0])
    if args.dialer_only and (
        profile != "37-16k" or args.system_receiver or args.optimized
    ):
        parser.error("--dialer-only is a separate preparation probe for profile 37-16k")
    if args.optimized and not args.system_receiver:
        parser.error("--optimized requires normal system receiver preparation")
    if args.optimized and profile not in {"30", "35-16k", "37-16k"}:
        parser.error("--optimized reviews API 30 and the API 35/37 16 KiB profiles")
    # SDK tools and the emulator must share the same owned AVD index and data directory.
    android_user = Path(os.environ["RUNNER_TEMP"]) / "codeclear-android"
    avd_root = android_user / "avd"
    avd_root.mkdir(parents=True, exist_ok=True)
    os.environ["ANDROID_USER_HOME"] = str(android_user)
    os.environ["ANDROID_EMULATOR_HOME"] = str(android_user)
    os.environ["ANDROID_AVD_HOME"] = str(avd_root)
    sdk = Path(os.environ["ANDROID_HOME"])
    cli = sdk / "cmdline-tools/latest/bin"
    image = image_for_profile(profile)
    name = "codeclear-" + profile
    sixteen_k = profile.endswith("-16k")
    partition_mib = 4096 if sixteen_k else 2048
    # Newer guests lost PackageManager or displayed launcher ANRs at 2 GiB.
    # These limits apply only to the isolated hosted VM, never the owner's PC.
    memory_mib = (
        4096 if args.api >= 37 or sixteen_k else 3072 if args.api >= 36 else 2048
    )
    if args.prepare:
        command(
            str(cli / "sdkmanager"),
            "--licenses",
            input="yes\n" * 100,
            timeout=180,
            stdout=subprocess.DEVNULL,
        )
        command(
            str(cli / "sdkmanager"),
            "cmdline-tools;23.0",
            timeout=600,
        )
        # The hosted runner's preinstalled `latest` can misread minor API levels
        # and create android-0 AVDs. Use the installed version for all image work.
        cli = sdk / "cmdline-tools/23.0/bin"
        command(
            str(cli / "sdkmanager"),
            "platform-tools",
            "emulator",
            "platforms;android-37.0",
            "build-tools;37.0.0",
            image,
            timeout=600,
        )
        command(
            str(cli / "avdmanager"),
            "create",
            "avd",
            "--name",
            name,
            "--package",
            image,
            "--device",
            "pixel_2",
            "--path",
            str(avd_root / (name + ".avd")),
            input="no\n",
        )
        target = verify_avd_target(
            (avd_root / (name + ".ini")).read_text(encoding="utf-8"), args.api
        )
        print("Verified AVD target: " + target + "; image " + image, flush=True)
        config = avd_root / (name + ".avd/config.ini")
        values = dict(
            line.split("=", 1)
            for line in config.read_text().splitlines()
            if "=" in line
        )
        values.update(
            {
                "disk.dataPartition.size": "4G" if sixteen_k else "2G",
                "hw.sdCard": "no",
                "hw.ramSize": str(memory_mib),
                "hw.lcd.width": "720",
                "hw.lcd.height": "1280",
                "hw.lcd.density": "320",
            }
        )
        config.write_text("".join(f"{key}={value}\n" for key, value in values.items()))
        return

    if not os.access("/dev/kvm", os.R_OK | os.W_OK):
        # Only the disposable hosted runner's emulator device is affected.
        command("sudo", "chmod", "a+rw", "/dev/kvm")
    adb = str(sdk / "platform-tools/adb")
    command(adb, "start-server")
    serial = "emulator-5554"

    def device(*parts, **kwargs):
        try:
            return command(
                adb, "-s", serial, *parts, capture_output=True, **kwargs
            ).stdout.strip()
        except subprocess.CalledProcessError as error:
            raise RuntimeError(
                f"Device command {parts[:3]} failed (exit {error.returncode}): "
                f"stderr={error.stderr[:1000]!r}; stdout={error.stdout[:1000]!r}"
            ) from error

    report = Path("app/build/reports/remote-validation")
    report.mkdir(parents=True, exist_ok=True)
    print(
        f"Runner free disk: {shutil.disk_usage('.').free // (1024**2)} MiB", flush=True
    )
    with (report / "emulator.log").open("w") as log:
        process = subprocess.Popen(
            [
                str(sdk / "emulator/emulator"),
                "-avd",
                name,
                "-port",
                "5554",
                "-memory",
                str(memory_mib),
                "-partition-size",
                str(partition_mib),
                "-cores",
                "2",
                "-gpu",
                "swiftshader",
                # Use GLES for this app's UI while investigating newer guest
                # compositor crashes; the tested app does not require Vulkan.
                *(["-feature", "-Vulkan"] if args.api >= 37 else []),
                "-no-window",
                "-no-audio",
                "-no-snapshot",
                "-no-boot-anim",
                "-camera-back",
                "none",
                "-camera-front",
                "none",
                "-no-metrics",
            ],
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        fixtures_started = False
        try:
            wait_for_boot(device, process)
            wait_for_package_manager(device, process)
            _require(device("shell", "getprop", "ro.kernel.qemu") == "1")
            _require(device("shell", "getprop", "ro.debuggable") == "1")
            _require(
                device("shell", "getprop", "ro.hardware") in {"ranchu", "goldfish"}
            )
            _require(
                device("shell", "getprop", "ro.build.version.sdk") == str(args.api)
            )
            print(
                f"Disposable profile {profile} (SDK {args.api}); page size "
                + device("shell", "getconf", "PAGE_SIZE")
                + f"; guest RAM {memory_mib} MiB; 2 cores",
                flush=True,
            )
            if sixteen_k:
                _require(
                    device("shell", "getconf", "PAGE_SIZE") == "16384",
                    "The requested 16 KiB profile has the wrong actual page size",
                )
            device("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            device("shell", "input", "keyevent", "KEYCODE_MENU")
            wait_for_unlock(device)
            wait_for_package_manager(device, process)
            rotation = "set-user-rotation" if args.api == 30 else "user-rotation"
            device("shell", "wm", rotation, "lock", "0")
            for key in [
                "window_animation_scale",
                "transition_animation_scale",
                "animator_duration_scale",
            ]:
                device("shell", "settings", "put", "global", key, "0")
            if args.dialer_only:
                from consent_ui import ConsentUi
                from dialer_ui import enter_testing_code, testing_code_metadata

                print(
                    "Dialer preparation only; no app, capture or RF acceptance.",
                    flush=True,
                )
                dialer = ConsentUi(
                    device,
                    sdk / "cmdline-tools/23.0/bin/android",
                    serial,
                    {"com.android.dialer", "com.google.android.dialer"},
                )
                enter_testing_code(device, dialer, keypad_controls=True)
                try:
                    nodes = dialer.layout()
                    print(
                        "Preparation field metadata: "
                        + json.dumps(
                            testing_code_metadata(nodes, dialer.focused_package())
                        ),
                        flush=True,
                    )
                finally:
                    dialer.stop()
                features = device("shell", "pm", "list", "features")
                print(
                    "Preparation telephony features: "
                    + json.dumps(
                        [
                            line
                            for line in features.splitlines()
                            if line.startswith("feature:android.hardware.telephony")
                        ][:20]
                    ),
                    flush=True,
                )
                holders = device(
                    "shell",
                    "cmd",
                    "role",
                    "get-role-holders",
                    "android.app.role.DIALER",
                )
                print(
                    "Preparation default dialer: "
                    + json.dumps(
                        [
                            p
                            for p in holders.splitlines()
                            if p in {"com.android.dialer", "com.google.android.dialer"}
                        ]
                    ),
                    flush=True,
                )
                # This fresh guest has received no PDU and contains no owner data.
                # Report only mode-related source diagnostics, never alert contents.
                source_log = device(
                    "shell",
                    "logcat",
                    "-d",
                    "-t",
                    "20000",
                    "-s",
                    "CellBroadcastReceiver:D",
                )
                for line in source_log.splitlines():
                    if "mode" in line.casefold():
                        print(
                            "Preparation source diagnostic: " + line[:300], flush=True
                        )
                return
            for apk in [
                "debug/app-debug.apk",
                "androidTest/debug/app-debug-androidTest.apk",
            ]:
                install_fixture(device, "app/build/outputs/apk/" + apk)

            fixtures = dict(FIXTURES)
            # Newer guests need a separate normal notification-consent flow.
            if args.api < 33:
                fixtures["mobile.ResultNotificationsDeviceTest"] = 6
            selected = ",".join("xyz.gaon.codeclear." + name for name in fixtures)
            fixtures_started = True
            output = device(
                "shell",
                "am",
                "instrument",
                "-w",
                "-r",
                "-e",
                "class",
                selected,
                "xyz.gaon.codeclear.test/androidx.test.runner.AndroidJUnitRunner",
                timeout=900,
            )
            (report / "fixtures.txt").write_text(output, encoding="utf-8")
            status = []
            for line in output.splitlines():
                status.append(line)
                if line.startswith("INSTRUMENTATION_STATUS_CODE:"):
                    if line.rsplit(":", 1)[-1].strip() in {"-1", "-2", "-3", "-4"}:
                        print("\n".join(status)[:10000], flush=True)
                    status = []
                if re.search(
                    r"Time: |OK \(|FAILURES!!!|Tests run:|INSTRUMENTATION_FAILED", line
                ):
                    print(line, flush=True)
            expected = sum(fixtures.values())
            passed = f"OK ({expected} tests)" in output and "FAILURES!!!" not in output
            if passed:
                print(
                    f"Passed {expected} software fixtures. This is not CellBroadcast or RF acceptance.",
                    flush=True,
                )
            else:
                # Every selected fixture is synthetic and runs only on this owned AVD.
                print("Incomplete instrumentation tail: " + output[-6000:], flush=True)
            if args.system_receiver:
                from system_receiver import BODIES, validate

                validate(
                    device,
                    args.api,
                    sdk / "cmdline-tools/23.0/bin/android",
                    serial,
                    args.receiver_mode,
                )
                wait_for_alert_notification(device, BODIES[-1])
            if args.optimized:
                from optimized_runtime import validate as validate_optimized

                validate_optimized(
                    device, sdk, sdk / "cmdline-tools/23.0/bin/android", serial
                )
            _require(
                passed,
                "Software fixture failures remain; receiver results are separate evidence",
            )
        except BaseException:
            guest_diagnostics(device, boot_only=not fixtures_started)
            log.flush()
            print("Emulator diagnostic tail:", flush=True)
            for line in (
                (report / "emulator.log").read_text(errors="replace").splitlines()[-40:]
            ):
                print(line[:500], flush=True)
            raise
        finally:
            try:
                device("emu", "kill", timeout=15)
            except (subprocess.SubprocessError, OSError, RuntimeError):
                process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


if __name__ == "__main__":
    main()

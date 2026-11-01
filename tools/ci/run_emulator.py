"""Run a reviewed fixture allowlist on a disposable, resource-bounded Linux AVD."""

import argparse
import os
import re
import subprocess
import time
from pathlib import Path

# Exclude live providers, model downloads, radio/Bluetooth tests, and owner-granted access tests.
FIXTURES = {
    "mobile.MobileControllerDeviceTest": 20,
    "mobile.HistoryWorkerDeviceTest": 6,
    "mobile.AlertCaptureIngestorDeviceTest": 4,
    "mobile.AlertHistoryStoreDeviceTest": 22,
    "mobile.MobileRuntimeDeviceTest": 1,
    "mobile.MobileOptionsStorageDeviceTest": 5,
    "capture.SourcePayloadDeviceTest": 10,
    "capture.SourceTitleDeviceTest": 1,
    "capture.SourceActionDeviceTest": 1,
    "capture.CaptureObserverDeviceTest": 2,
    "capture.CaptureServiceComponentsDeviceTest": 2,
    "mobile.MobileScreenDeviceTest": 56,
    "mobile.UiLocaleResourcesDeviceTest": 1,
    "probe.PreviewDeviceTest": 3,
    "mobile.ModelInventoryDeviceTest": 4,
    "mobile.ModelPreparationDeviceTest": 7,
    "mobile.OriginalLanguageIdentifierDeviceTest": 1,
    "mobile.SpeechPlaybackDeviceTest": 7,
    "translation.SystemEngineDeviceTest": 8,
    "translation.SystemTranslationProtocolDeviceTest": 1,
    "translation.MyMemoryEngineDeviceTest": 1,
    "translation.MyMemoryTransportDeviceTest": 6,
    "translation.LocalEngineDeviceTest#bundledLanguageIdentificationRunsWithoutAModelDownload": 1,
    "translation.LocalEngineDeviceTest#chineseScriptConversionRunsOnTheDeviceAndPreservesNumbers": 1,
}


def command(*args, timeout=120, **kwargs):
    return subprocess.run(args, check=True, text=True, timeout=timeout, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", type=int, choices=[30, 31, 35], required=True)
    parser.add_argument("--prepare", action="store_true")
    args = parser.parse_args()
    sdk = Path(os.environ["ANDROID_HOME"])
    cli = sdk / "cmdline-tools/latest/bin"
    image = f"system-images;android-{args.api};default;x86_64"
    name = f"codeclear-api-{args.api}"
    if args.prepare:
        command(str(cli / "sdkmanager"), "--licenses", input="yes\n" * 100, timeout=180)
        command(
            str(cli / "sdkmanager"),
            "platform-tools",
            "emulator",
            "platforms;android-37",
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
            input="no\n",
        )
        return

    if not os.access("/dev/kvm", os.R_OK | os.W_OK):
        # Only the disposable hosted runner's emulator device is affected.
        command("sudo", "chmod", "a+rw", "/dev/kvm")
    adb = str(sdk / "platform-tools/adb")
    serial = "emulator-5554"

    def device(*parts, **kwargs):
        return command(
            adb, "-s", serial, *parts, capture_output=True, **kwargs
        ).stdout.strip()

    report = Path("app/build/reports/remote-validation")
    report.mkdir(parents=True, exist_ok=True)
    with (report / "emulator.log").open("w") as log:
        process = subprocess.Popen(
            [
                str(sdk / "emulator/emulator"),
                "-avd",
                name,
                "-port",
                "5554",
                "-memory",
                "2048",
                "-cores",
                "2",
                "-gpu",
                "swiftshader",
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
        try:
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(
                        "Emulator exited before boot; inspect emulator.log"
                    )
                try:
                    if (
                        device("shell", "getprop", "sys.boot_completed", timeout=10)
                        == "1"
                    ):
                        break
                except (subprocess.SubprocessError, OSError):
                    pass
                time.sleep(2)
            else:
                raise TimeoutError("Emulator did not finish booting within 240 seconds")
            assert device("shell", "getprop", "ro.kernel.qemu") == "1"
            assert device("shell", "getprop", "ro.debuggable") == "1"
            assert device("shell", "getprop", "ro.hardware") in {"ranchu", "goldfish"}
            assert device("shell", "getprop", "ro.build.version.sdk") == str(args.api)
            print(
                f"Disposable AOSP API {args.api}; page size "
                + device("shell", "getconf", "PAGE_SIZE"),
                flush=True,
            )
            device("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            device("shell", "input", "keyevent", "KEYCODE_MENU")
            device("shell", "wm", "user-rotation", "lock", "0")
            for key in [
                "window_animation_scale",
                "transition_animation_scale",
                "animator_duration_scale",
            ]:
                device("shell", "settings", "put", "global", key, "0")
            for apk in [
                "debug/app-debug.apk",
                "androidTest/debug/app-debug-androidTest.apk",
            ]:
                assert "Success" in device(
                    "install", "-t", "app/build/outputs/apk/" + apk
                )

            selected = ",".join("xyz.gaon.codeclear." + name for name in FIXTURES)
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
            for line in output.splitlines():
                if re.search(
                    r"Time: |OK \(|FAILURES!!!|stack=|INSTRUMENTATION_FAILED", line
                ):
                    print(line, flush=True)
            expected = sum(FIXTURES.values())
            assert f"OK ({expected} tests)" in output and "FAILURES!!!" not in output
            print(
                f"Passed {expected} software fixtures. This is not CellBroadcast or RF acceptance.",
                flush=True,
            )
        finally:
            try:
                device("emu", "kill", timeout=15)
            except (subprocess.SubprocessError, OSError):
                process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


if __name__ == "__main__":
    main()

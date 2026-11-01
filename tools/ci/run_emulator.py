"""Run a reviewed fixture allowlist on a disposable, resource-bounded Linux AVD."""

import argparse
import os
import re
import shutil
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
    parser.add_argument("--system-receiver", action="store_true")
    args = parser.parse_args()
    # SDK tools and the emulator must share the same owned AVD index and data directory.
    android_user = Path(os.environ["RUNNER_TEMP"]) / "codeclear-android"
    avd_root = android_user / "avd"
    avd_root.mkdir(parents=True, exist_ok=True)
    os.environ["ANDROID_USER_HOME"] = str(android_user)
    os.environ["ANDROID_EMULATOR_HOME"] = str(android_user)
    os.environ["ANDROID_AVD_HOME"] = str(avd_root)
    sdk = Path(os.environ["ANDROID_HOME"])
    cli = sdk / "cmdline-tools/latest/bin"
    image = f"system-images;android-{args.api};default;x86_64"
    name = f"codeclear-api-{args.api}"
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
        config = avd_root / (name + ".avd/config.ini")
        values = dict(
            line.split("=", 1)
            for line in config.read_text().splitlines()
            if "=" in line
        )
        values.update(
            {
                "disk.dataPartition.size": "2G",
                "hw.sdCard": "no",
                "hw.ramSize": "2048",
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
    serial = "emulator-5554"

    def device(*parts, **kwargs):
        return command(
            adb, "-s", serial, *parts, capture_output=True, **kwargs
        ).stdout.strip()

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
                "2048",
                "-partition-size",
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
            if args.system_receiver:
                from system_receiver import validate

                validate(device, args.api)
        except BaseException:
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
            except (subprocess.SubprocessError, OSError):
                process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


if __name__ == "__main__":
    main()

"""Read one persistent UI session, restricted to consent and application windows."""

import json
import re
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path


class ConsentUi:
    def __init__(self, device, cli, serial, permitted):
        self.device = device
        self.cli = str(cli)
        self.serial = serial
        self.permitted = permitted

    def focused_package(self):
        # Current focus belongs to DisplayContent, outside the windows-only dump.
        for attempt in range(20):
            windows = self.device("shell", "dumpsys", "window")
            tokens = re.findall(r"mCurrentFocus=Window\{([^\s}]+)\s", windows)
            matches = []
            for token in tokens:
                block = re.search(
                    r"(?ms)^\s*Window #\d+ Window\{"
                    + re.escape(token)
                    + r"\s[^\n]*\n(.*?)(?=^\s*Window #|\Z)",
                    windows,
                )
                if block:
                    matches.extend(
                        re.findall(
                            r"mOwnerUid=\d+[^\n]*? package=([\w.]+)(?:\s|$)",
                            block.group(1),
                        )[:1]
                    )
            if tokens or attempt == 19:
                break
            time.sleep(0.25)
        if len(matches) != 1 or matches[0] not in self.permitted:
            raise RuntimeError(
                "Refusing UI input outside an owned consent window; focused packages: "
                + repr(matches[:3])
            )
        return matches[0]

    def layout(self):
        package = self.focused_package()
        with tempfile.TemporaryDirectory(prefix="codeclear-consent-") as temporary:
            output = Path(temporary) / "layout.json"
            subprocess.run(
                [
                    self.cli,
                    "layout",
                    "--device=" + self.serial,
                    "--flat",
                    "--full",
                    "--no-idle",
                    "--output=" + str(output),
                ],
                check=True,
                capture_output=True,
                timeout=40,
            )
            windows = json.loads(output.read_text(encoding="utf-8"))
        if self.focused_package() != package:
            raise RuntimeError("The focused window changed while reading its layout")
        applications = [
            w
            for w in windows
            if w.get("type") == "APPLICATION"
            or any(
                n.get("resource-id", "").startswith(package + ":")
                for n in w.get("content", [])
            )
        ]
        if not applications:
            if package == "com.android.settings":
                print(
                    "Settings window metadata: "
                    + json.dumps(
                        [
                            {
                                "type": w.get("type"),
                                "layer": w.get("layer"),
                                "nodes": len(w.get("content", [])),
                            }
                            for w in windows
                        ]
                    ),
                    flush=True,
                )
                return self.settings_confirmation()
            raise RuntimeError("No application window was exposed")
        window = max(applications, key=lambda w: w.get("layer", -1))
        nodes = []
        for raw in window.get("content", []):
            if raw.get("hidden") or raw.get("off-screen"):
                continue
            resource = raw.get("resource-id", "")
            if ":" in resource and resource.split(":", 1)[0] not in {
                package,
                "android",
            }:
                continue
            attributes = {
                key: str(raw.get(key, ""))
                for key in ["text", "content-desc", "resource-id", "class", "bounds"]
            }
            attributes.update(
                package=package,
                enabled="false" if "DISABLED" in raw.get("state", []) else "true",
            )
            nodes.append(ET.Element("node", attributes))
        return nodes

    def settings_confirmation(self):
        # The SDK agent can omit the privileged accessibility confirmation window.
        # Limit the legacy reader to Settings; never introduce per-dump reconnection
        # oscillation in the product's own capture-status layout.
        if self.focused_package() != "com.android.settings":
            raise RuntimeError("The legacy confirmation reader requires Settings focus")
        self.stop()
        path = "/sdcard/codeclear-consent-" + uuid.uuid4().hex + ".xml"
        try:
            self.device(
                "shell", "uiautomator", "dump", "--compressed", path, timeout=25
            )
            root = ET.fromstring(self.device("shell", "cat", path))
            if self.focused_package() != "com.android.settings":
                raise RuntimeError("Settings lost focus while reading its confirmation")
            nodes = []
            for node in root.iter("node"):
                if node.get("package") in {"android", "com.android.settings"}:
                    node.set("package", "com.android.settings")
                    nodes.append(node)
            if not nodes:
                raise RuntimeError("The owned Settings confirmation was unavailable")
            return nodes
        finally:
            self.device("shell", "rm", "-f", path)

    def tap(self, node):
        if self.focused_package() != node.get("package"):
            raise RuntimeError("The focused window changed before input")
        if node.get("enabled") != "true":
            raise RuntimeError("The requested control is disabled")
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if bounds is None:
            raise RuntimeError("The requested control has invalid bounds")
        x1, y1, x2, y2 = map(int, bounds.groups())
        if not (0 <= x1 < x2 <= 720 and 0 <= y1 < y2 <= 1280):
            raise RuntimeError("The requested control is outside the owned viewport")
        self.device("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def scroll(self):
        self.focused_package()
        self.device("shell", "input", "swipe", "360", "1030", "360", "380", "250")

    def stop(self):
        # A UI automation connection can suppress ordinary accessibility services.
        self.device(
            "shell", "am", "force-stop", "com.android.cli.interact.instrumentation"
        )

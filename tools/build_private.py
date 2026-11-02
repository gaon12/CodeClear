"""Inject a private service route without putting credentials in a mobile build."""

import argparse
import os
import re
import subprocess
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
ROUTE_KEY = "CODECLEAR_TRANSLATION_URL"
TOKEN_KEY = "CODECLEAR_TRANSLATION_TOKEN"


def private_environment(configuration: Path, inherited=None) -> dict[str, str]:
    configuration = configuration.expanduser().resolve()
    if configuration.is_relative_to(ROOT):
        raise ValueError("Private build configuration must be outside the repository")
    values = {}
    for line in configuration.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or key not in {ROUTE_KEY, TOKEN_KEY} or key in values:
            raise ValueError("Invalid private build configuration entry")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    route = values.get(ROUTE_KEY, "")
    try:
        parsed = urlsplit(route)
        valid = (
            1 <= len(route) <= 2048
            and route.isascii()
            and all(33 <= ord(character) <= 126 for character in route)
            and parsed.scheme == "https"
            and parsed.port in (None, 443)
            and parsed.username is None
            and parsed.password is None
            and "?" not in route
            and "#" not in route
            and re.fullmatch(
                r"(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}",
                parsed.hostname or "",
            )
        )
    except ValueError:
        valid = False
    if not valid:
        raise ValueError("Invalid private HTTPS translation route")
    environment = dict(os.environ if inherited is None else inherited)
    environment[ROUTE_KEY] = route
    # The token is for local API verification/provisioning, never an APK constant.
    environment.pop(TOKEN_KEY, None)
    return environment


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--config",
        type=Path,
        default=Path.home() / ".codeclear/server-build.env",
    )
    parser.add_argument("--validation", action="store_true")
    arguments = parser.parse_args()
    try:
        environment = private_environment(arguments.config)
    except (OSError, ValueError):
        print(
            "Private build configuration is missing or invalid; no private values were printed."
        )
        return 2
    if os.name == "nt" and "JAVA_HOME" not in environment:
        runtime = Path(environment.get("ProgramFiles", "C:/Program Files")) / (
            "Android/Android Studio/jbr"
        )
        if runtime.is_dir():
            environment["JAVA_HOME"] = str(runtime)
    command = [str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))]
    command.extend(["--no-daemon", "--max-workers=1", "--console=plain"])
    if arguments.validation:
        command.append("-Pcodeclear.validationBuild=true")
    command.append(":app:assembleDebug")
    if arguments.validation:
        command.append(":app:assembleDebugAndroidTest")
    print(
        "Private translation route injected; the client token is excluded from the build."
    )
    return subprocess.run(command, cwd=ROOT, env=environment, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())

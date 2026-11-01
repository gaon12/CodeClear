"""Release an unused tool only on an owned disposable GitHub-hosted Linux runner."""

import os
import shutil
import subprocess
import sys
from pathlib import Path


def main():
    if (
        sys.platform != "linux"
        or os.environ.get("GITHUB_ACTIONS") != "true"
        or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted"
    ):
        raise RuntimeError(
            "Disk preparation is restricted to disposable hosted Linux runners"
        )
    before = shutil.disk_usage(".").free
    unused = Path("/usr/share/dotnet")
    if unused.exists():
        if unused.is_symlink() or unused.resolve() != unused:
            raise RuntimeError("Refusing to follow an unexpected hosted tool path")
        # Android/Java/Python validation does not use this preinstalled .NET SDK.
        # Leave the workspace, Android SDK, JDKs and all owner devices untouched.
        subprocess.run(
            ["sudo", "rm", "-rf", "--", str(unused)], check=True, timeout=180
        )
    after = shutil.disk_usage(".").free
    print(
        f"Hosted free disk: {before // (1024**2)} -> {after // (1024**2)} MiB",
        flush=True,
    )


if __name__ == "__main__":
    main()

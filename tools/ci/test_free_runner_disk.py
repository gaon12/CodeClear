"""Keep hosted disk cleanup away from local and self-hosted machines."""

import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from tools.ci.free_runner_disk import main


class HostedDiskTest(unittest.TestCase):
    @patch("tools.ci.free_runner_disk.subprocess.run")
    def test_windows_and_non_hosted_runners_cannot_delete_anything(self, run):
        for platform, actions, runner in [
            ("win32", "true", "github-hosted"),
            ("linux", "true", "self-hosted"),
            ("linux", "false", "github-hosted"),
        ]:
            with (
                patch("tools.ci.free_runner_disk.sys.platform", platform),
                patch.dict(
                    "os.environ", GITHUB_ACTIONS=actions, RUNNER_ENVIRONMENT=runner
                ),
                self.assertRaises(RuntimeError),
            ):
                main()
        run.assert_not_called()

    def invoke_hosted(self, source):
        with (
            patch("tools.ci.free_runner_disk.sys.platform", "linux"),
            patch.dict(
                "os.environ", GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted"
            ),
            patch("tools.ci.free_runner_disk.Path", return_value=source),
            patch(
                "tools.ci.free_runner_disk.shutil.disk_usage",
                return_value=SimpleNamespace(free=16 * 1024**3),
            ),
        ):
            main()

    @patch("tools.ci.free_runner_disk.subprocess.run")
    def test_only_the_exact_unused_tool_directory_can_be_released(self, run):
        source = MagicMock()
        source.__str__.return_value = "/usr/share/dotnet"
        source.is_symlink.return_value = False
        source.resolve.return_value = source
        self.invoke_hosted(source)
        run.assert_called_once_with(
            ["sudo", "rm", "-rf", "--", "/usr/share/dotnet"], check=True, timeout=180
        )

    @patch("tools.ci.free_runner_disk.subprocess.run")
    def test_symlinks_cannot_redirect_cleanup(self, run):
        source = MagicMock()
        source.is_symlink.return_value = True
        with self.assertRaises(RuntimeError):
            self.invoke_hosted(source)
        run.assert_not_called()

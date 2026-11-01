"""Reject stale, binary, oversized or out-of-scope remote candidates."""

import base64
import unittest

from tools.ci.apply_candidate import candidate_patch, candidate_paths


class CandidateTest(unittest.TestCase):
    def encode(self, text):
        return base64.b64encode(text.encode()).decode()

    def test_candidate_bytes_require_the_exact_reviewed_base(self):
        value = "--- a/example\n+++ b/example\n"
        self.assertEqual(
            value.encode(), candidate_patch(self.encode(value), "a" * 40, "a" * 40)
        )
        with self.assertRaises(ValueError):
            candidate_patch(self.encode(value), "a" * 40, "b" * 40)

    def test_invalid_encoding_size_binary_and_symlink_modes_are_rejected(self):
        for value in [
            "!",
            "A" * 60001,
            self.encode("GIT binary patch"),
            self.encode("new file mode 120000\n"),
            self.encode("rename from private\n"),
        ]:
            with self.assertRaises(ValueError):
                candidate_patch(value, "a" * 40, "a" * 40)

    def test_only_normal_kotlin_sources_and_the_fixture_allowlist_are_accepted(self):
        path = "app/src/androidTest/kotlin/xyz/gaon/codeclear/ExampleTest.kt"
        self.assertEqual([path], candidate_paths("1\t0\t" + path + "\x00"))
        self.assertEqual(
            ["tools/ci/run_emulator.py"],
            candidate_paths("1\t1\ttools/ci/run_emulator.py\x00"),
        )
        for path in [
            "../private",
            ".github/workflows/android-validation.yml",
            "local.properties",
            "app/src/main/kotlin/../private.kt",
            "app/src/main/kotlin/symlink",
        ]:
            with self.assertRaises(ValueError):
                candidate_paths("1\t0\t" + path + "\x00")
        with self.assertRaises(ValueError):
            candidate_paths("-\t-\tapp/src/main/kotlin/File.kt\x00")

    def test_normal_workflow_dispatch_requires_no_candidate(self):
        self.assertIsNone(candidate_patch("", "", "a" * 40))

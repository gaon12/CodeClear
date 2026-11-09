"""Reject stale, binary, oversized or out-of-scope remote candidates."""

import base64
import gzip
import unittest

from tools.ci.apply_candidate import candidate_patch, candidate_paths


class CandidateTest(unittest.TestCase):
    def compressed(self, value):
        return "gzip:" + base64.b64encode(gzip.compress(value)).decode()

    def test_compressed_unicode_candidates_preserve_the_exact_reviewed_source(self):
        source = "--- a/example\n+++ b/example\n+훈련 警報\n".encode()
        self.assertEqual(
            source, candidate_patch(self.compressed(source), "a" * 40, "a" * 40)
        )

    def test_compression_cannot_bypass_expanded_size_or_source_mode_limits(self):
        for value in [b"x" * 120001, b"new file mode 120000\n", b"GIT binary patch"]:
            with self.assertRaises(ValueError):
                candidate_patch(self.compressed(value), "a" * 40, "a" * 40)

    def test_compressed_truncation_concatenation_and_trailing_data_are_rejected(self):
        payload = gzip.compress(b"--- a/example\n+++ b/example\n")
        for invalid in [payload[:-1], payload + payload, payload + b"trailing", b"bad"]:
            encoded = "gzip:" + base64.b64encode(invalid).decode()
            with self.assertRaises(ValueError):
                candidate_patch(encoded, "a" * 40, "a" * 40)

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

    def test_localized_mobile_labels_and_textual_icons_can_be_reviewed_before_commit(
        self,
    ):
        for path in [
            "app/src/main/res/values/mobile_strings.xml",
            "app/src/main/res/values-ko/mobile_strings.xml",
            "app/src/main/res/values-b+zh+Hant/mobile_strings.xml",
            "app/src/main/res/drawable/ic_copy.xml",
        ]:
            self.assertEqual([path], candidate_paths("1\t0\t" + path + "\x00"))

    def test_ui_candidates_cannot_modify_manifest_backup_rules_or_local_configuration(
        self,
    ):
        for path in [
            "app/src/main/AndroidManifest.xml",
            "app/src/main/res/xml/backup_rules.xml",
            "app/src/main/res/values/config.xml",
            "app/src/main/res/values-unknown/mobile_strings.xml",
            "app/src/main/res/drawable/../../local.properties",
            "app/src/main/res/drawable/ic_copy.png",
        ]:
            with self.assertRaises(ValueError):
                candidate_paths("1\t0\t" + path + "\x00")

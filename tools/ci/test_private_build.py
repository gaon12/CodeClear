"""Check redacted build inputs using synthetic configuration outside the checkout."""

import tempfile
import unittest
from pathlib import Path

from tools.build_private import ROOT, ROUTE_KEY, TOKEN_KEY, private_environment


class PrivateBuildTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="codeclear-private-build-")
        self.addCleanup(self.directory.cleanup)
        self.configuration = Path(self.directory.name) / "build.env"

    def configure(self, text):
        self.configuration.write_text(text, encoding="utf-8")
        return private_environment(
            self.configuration, {TOKEN_KEY: "inherited-synthetic-token"}
        )

    def test_only_route_reaches_build_environment(self):
        environment = self.configure(
            ROUTE_KEY
            + "=https://example.invalid/api.php\n"
            + TOKEN_KEY
            + "=synthetic-secret\n"
        )
        self.assertEqual("https://example.invalid/api.php", environment[ROUTE_KEY])
        self.assertNotIn(TOKEN_KEY, environment)
        self.assertNotIn("synthetic-secret", str(environment))

    def test_private_file_cannot_live_in_checkout(self):
        with self.assertRaisesRegex(ValueError, "outside the repository"):
            private_environment(ROOT / ".dev/not-read.env", {})

    def test_credentials_queries_fragments_and_non_https_are_rejected(self):
        for route in (
            "http://example.invalid/api.php",
            "https://user:password@example.invalid/api.php",
            "https://example.invalid/api.php?key=synthetic-secret",
            "https://example.invalid/api.php#fragment",
            "https://127.0.0.1/api.php",
            "https://localhost/api.php",
            "https://example.invalid:8443/api.php",
        ):
            with (
                self.subTest(route=route),
                self.assertRaisesRegex(ValueError, "Invalid private"),
            ):
                self.configure(ROUTE_KEY + "=" + route)

    def test_duplicate_and_unknown_entries_do_not_echo_values(self):
        for text in (
            ROUTE_KEY
            + "=https://example.invalid/api.php\n"
            + ROUTE_KEY
            + "=synthetic-secret",
            "UNKNOWN=synthetic-secret",
        ):
            with self.assertRaises(ValueError) as error:
                self.configure(text)
            self.assertNotIn("synthetic-secret", str(error.exception))

    def test_bom_quotes_and_literal_dollar_signs_are_data(self):
        route = "https://example.invalid/$literal/api.php"
        environment = self.configure("\ufeff" + ROUTE_KEY + '="' + route + '"\n')
        self.assertEqual(route, environment[ROUTE_KEY])

    def test_empty_malformed_and_oversized_routes_are_rejected(self):
        for route in (
            "",
            "https://example.invalid:bad/api.php",
            "https://example.invalid/" + "a" * 2048,
        ):
            with self.assertRaises(ValueError):
                self.configure(ROUTE_KEY + "=" + route)


if __name__ == "__main__":
    unittest.main()

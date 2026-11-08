"""Prevent engine completion or numeric preservation from masquerading as semantic review."""

import copy
import unittest

from tools.translation_review import evaluate, load_suite, template


class TranslationReviewTest(unittest.TestCase):
    def complete_review(self):
        review = template()
        review.update(
            engine="synthetic-test", engine_revision="fixture-1", reviewer="fixture"
        )
        suite, _ = load_suite()
        cases = {case["id"]: case for case in suite["cases"]}
        for result in review["results"]:
            result["text"] = cases[result["id"]]["reference"]
            result["checks"] = {name: True for name in result["checks"]}
        return review

    def test_empty_or_unreviewed_outputs_never_pass(self):
        self.assertEqual("pending", evaluate(template())["status"])
        review = self.complete_review()
        for result in review["results"]:
            result["checks"] = {name: None for name in result["checks"]}
        self.assertEqual("pending", evaluate(review)["status"])
        review = self.complete_review()
        review["reviewer"] = ""
        self.assertEqual("pending", evaluate(review)["status"])

    def test_reversed_instruction_fails_even_when_numbers_are_preserved(self):
        review = self.complete_review()
        review["results"][0]["text"] = (
            "[훈련] 밖으로 대피하세요. 도움이 필요하면 112로 전화하세요."
        )
        review["results"][0]["checks"]["negation"] = False
        result = evaluate(review)
        self.assertEqual("failed", result["status"])
        self.assertEqual(["negation"], result["cases"][0]["failures"])

    def test_missing_or_added_numbers_and_changed_links_fail(self):
        complete = self.complete_review()
        for old, new, expected in [
            ("112", "119", "numeric tokens"),
            ("112", "112 9", "numeric tokens"),
        ]:
            review = copy.deepcopy(complete)
            review["results"][0]["text"] = review["results"][0]["text"].replace(
                old, new
            )
            self.assertIn(expected, evaluate(review)["cases"][0]["failures"])
        original = complete["results"][5]["text"]
        for changed in [
            original.replace("example.org", "example.net"),
            original.replace("/drill", "/drill.evil"),
            original.replace("https://", "http://"),
            original + " https://example.net/extra",
        ]:
            review = copy.deepcopy(complete)
            review["results"][5]["text"] = changed
            self.assertIn("URL", evaluate(review)["cases"][5]["failures"])
        review = copy.deepcopy(complete)
        review["results"][5]["text"] += "."
        self.assertEqual("passed", evaluate(review)["status"])

    def test_stale_or_incomplete_case_sets_are_rejected(self):
        complete = self.complete_review()
        review = copy.deepcopy(complete)
        review["suite_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "exact current"):
            evaluate(review)
        for results in [
            complete["results"][:-1],
            complete["results"] + complete["results"][:1],
        ]:
            review = copy.deepcopy(complete)
            review["results"] = results
            with self.assertRaisesRegex(ValueError, "exactly once"):
                evaluate(review)

    def test_invalid_outputs_and_nonboolean_judgments_are_rejected(self):
        complete = self.complete_review()
        for text in [None, "x" * 16385]:
            review = copy.deepcopy(complete)
            review["results"][0]["text"] = text
            with self.assertRaises(ValueError):
                evaluate(review)
        review = copy.deepcopy(complete)
        review["results"][0]["checks"]["negation"] = "true"
        with self.assertRaisesRegex(ValueError, "true, false or null"):
            evaluate(review)

    def test_only_a_complete_review_can_report_passed(self):
        report = evaluate(self.complete_review())
        self.assertEqual("passed", report["status"])
        self.assertEqual(8, len(report["cases"]))
        self.assertTrue(all(case["status"] == "passed" for case in report["cases"]))


if __name__ == "__main__":
    unittest.main()

"""Review synthetic emergency translations offline; structural checks do not judge meaning."""

import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

SUITE = Path(__file__).with_name("translation_review_cases.json")
MAX_REVIEW_BYTES = 1024 * 1024


def load_suite():
    data = SUITE.read_bytes()
    return json.loads(data), hashlib.sha256(data).hexdigest()


def template():
    suite, digest = load_suite()
    return {
        "suite_sha256": digest,
        "engine": "",
        "engine_revision": "",
        "reviewer": "",
        "results": [
            {
                "id": case["id"],
                "text": "",
                "checks": {name: None for name in case["checks"]},
            }
            for case in suite["cases"]
        ],
    }


def evaluate(review):
    suite, digest = load_suite()
    if not isinstance(review, dict) or review.get("suite_sha256") != digest:
        raise ValueError("Review must match the exact current synthetic suite")
    results = review.get("results")
    if not isinstance(results, list) or not all(isinstance(r, dict) for r in results):
        raise ValueError("Review results must be a list of objects")
    expected = {case["id"] for case in suite["cases"]}
    ids = [result.get("id") for result in results]
    if any(not isinstance(value, str) for value in ids):
        raise ValueError("Every result needs a synthetic case ID")
    if len(ids) != len(expected) or set(ids) != expected:
        raise ValueError("Include every synthetic case exactly once")
    provenance = all(
        isinstance(review.get(name), str) and 0 < len(review[name].strip()) <= 256
        for name in ("engine", "engine_revision", "reviewer")
    )
    by_id = {result["id"]: result for result in results}
    report = []
    for case in suite["cases"]:
        result = by_id[case["id"]]
        text = result.get("text")
        checks = result.get("checks")
        if not isinstance(text, str) or len(text) > 16384:
            raise ValueError("Each output must be text of at most 16384 characters")
        if not isinstance(checks, dict) or set(checks) != set(case["checks"]):
            raise ValueError(
                "Each output must include the exact semantic review questions"
            )
        if any(
            value is not None and type(value) is not bool for value in checks.values()
        ):
            raise ValueError("Semantic judgments must be true, false or null")
        failures = [name for name, accepted in checks.items() if accepted is False]
        if text.strip():
            # This baseline deliberately requires the same numeric tokens and exact URLs.
            # Equivalent reformatted times/units need a separately reviewed suite revision.
            if Counter(re.findall(r"[0-9]+", case["source"])) != Counter(
                re.findall(r"[0-9]+", text)
            ):
                failures.append("numeric tokens")
            if any(
                url not in text for url in re.findall(r"https://\S+", case["source"])
            ):
                failures.append("URL")
        pending = (
            not text.strip()
            or not provenance
            or any(value is None for value in checks.values())
        )
        status = "failed" if failures else "pending" if pending else "passed"
        report.append({"id": case["id"], "status": status, "failures": failures})
    status = (
        "failed"
        if any(row["status"] == "failed" for row in report)
        else "pending"
        if any(row["status"] == "pending" for row in report)
        else "passed"
    )
    return {"status": status, "cases": report}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--template", type=Path, help="Create a new local review JSON")
    action.add_argument(
        "--review", type=Path, help="Check a completed local review JSON"
    )
    args = parser.parse_args()
    if args.template:
        # Exclusive creation avoids overwriting an existing review and its evidence.
        with args.template.open("x", encoding="utf-8") as output:
            json.dump(template(), output, ensure_ascii=False, indent=2)
            output.write("\n")
        return 0
    with args.review.open("rb") as source:
        data = source.read(MAX_REVIEW_BYTES + 1)
    if len(data) > MAX_REVIEW_BYTES:
        parser.error("Review exceeds the 1 MiB input limit")
    try:
        report = evaluate(json.loads(data))
    except (ValueError, UnicodeError) as error:
        parser.error(str(error))
    print(json.dumps(report, indent=2))
    return {"passed": 0, "failed": 1, "pending": 2}[report["status"]]


if __name__ == "__main__":
    raise SystemExit(main())

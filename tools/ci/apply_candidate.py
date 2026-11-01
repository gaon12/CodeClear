"""Apply a bounded public-source candidate to a matching disposable CI checkout."""

import base64
import hashlib
import os
import re
import subprocess


def candidate_patch(encoded, expected_base, current_base):
    if not encoded:
        return None
    if (
        not re.fullmatch(r"[0-9a-f]{40}", expected_base)
        or expected_base != current_base
    ):
        raise ValueError("The candidate must match the checked-out commit")
    if len(encoded) > 60000:
        raise ValueError("Candidate input exceeds the public-source size limit")
    try:
        patch = base64.b64decode(encoded, validate=True)
        source = patch.decode("utf-8")
    except (ValueError, UnicodeError) as error:
        raise ValueError(
            "Candidate input must be UTF-8 source encoded as base64"
        ) from error
    if not source or "\x00" in source or "GIT binary patch" in source:
        raise ValueError("Only a nonempty textual source patch is permitted")
    if re.search(r"(?m)^(?:rename|copy) (?:from|to) ", source):
        raise ValueError("Candidate patches cannot move files")
    for mode in re.findall(
        r"(?m)^(?:new file mode|deleted file mode|old mode|new mode) (\d+)$", source
    ):
        if mode != "100644":
            raise ValueError("Candidate files must be regular source files")
    for mode in re.findall(r"(?m)^index [0-9a-f.]+ (\d+)$", source):
        if mode != "100644":
            raise ValueError("Candidate files must be regular source files")
    return patch


def candidate_paths(numstat):
    paths = []
    for entry in numstat.split("\x00"):
        if not entry:
            continue
        added, removed, path = entry.split("\t", 2)
        if not added.isdecimal() or not removed.isdecimal():
            raise ValueError("Binary candidate files are not permitted")
        kotlin = re.fullmatch(
            r"app/src/(?:main|testDebug|androidTest)/kotlin/(?:[A-Za-z0-9_]+/)*[A-Za-z0-9_]+\.kt",
            path,
        )
        if not kotlin and path != "tools/ci/run_emulator.py":
            raise ValueError(
                "Candidate paths must be Kotlin sources or the fixture allowlist"
            )
        paths.append(path)
    if not paths or len(paths) > 16 or len(set(paths)) != len(paths):
        raise ValueError("Review the candidate file count")
    return paths


def main():
    encoded = os.environ.get("CANDIDATE_PATCH_B64", "")
    if not encoded:
        return
    current = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    patch = candidate_patch(encoded, os.environ.get("CANDIDATE_BASE_SHA", ""), current)
    result = subprocess.run(
        ["git", "apply", "--numstat", "-z", "-"],
        input=patch,
        capture_output=True,
        check=True,
    )
    paths = candidate_paths(result.stdout.decode("utf-8"))
    subprocess.run(["git", "apply", "--check", "-"], input=patch, check=True)
    subprocess.run(["git", "apply", "-"], input=patch, check=True)
    print(
        "Applied public-source candidate:",
        hashlib.sha256(patch).hexdigest(),
        "files:",
        len(paths),
        flush=True,
    )


if __name__ == "__main__":
    main()

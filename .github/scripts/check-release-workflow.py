#!/usr/bin/env python3
"""Static and negative checks for .github/workflows/release-images.yml.

Usage: check-release-workflow.py [path-to-workflow]

The release workflow's one guarantee is that only a real, full commit sha with green CI can be released. This
fails if the dispatch input can reach shell source, if the revision is not validated first and strictly, or if
the CI lookup, checkout or build stop using the validated value. It then runs the workflow's own validation step
against malformed values and requires every one to be refused -- including values carrying shell metacharacters,
which must be refused AS DATA: a sentinel proves nothing in them was executed.
"""
import os
import re
import subprocess
import sys
import tempfile

import yaml

REVISION_PATTERN = "^[0-9a-f]{40}$"
GOOD = "74b3674894af8c3758315446bb3f498b34e9eb4e"


def fail(message):
    print(f"release workflow check FAILED: {message}")
    sys.exit(1)


def main(argv):
    path = argv[0] if argv else ".github/workflows/release-images.yml"
    with open(path, encoding="utf-8") as handle:
        workflow = yaml.safe_load(handle)

    if workflow.get("permissions") != {"contents": "read", "packages": "write"}:
        fail(f"permissions must be exactly contents: read, packages: write, got {workflow.get('permissions')}")
    jobs = workflow.get("jobs", {})
    if list(jobs) != ["publish"]:
        fail("expected exactly one job, publish")
    job = jobs["publish"]
    steps = job.get("steps", [])

    # 1. No expression text in any shell source. Not only inputs.*: any ${{ }} inside run: is shell source written
    #    by the expression engine, and this workflow needs none.
    for index, step in enumerate(steps):
        if "run" in step and "${{" in step["run"]:
            fail(f"step {index} ({step.get('name', 'unnamed')}) interpolates an expression into shell source")

    # 2. The input is bound once, at job level, and is the only place inputs.revision appears.
    if job.get("env", {}).get("REVISION") != "${{ inputs.revision }}":
        fail("REVISION must be bound at job level from inputs.revision")
    text = open(path, encoding="utf-8").read()
    if text.count("inputs.revision") != 1:
        fail("inputs.revision must be referenced exactly once, in the job-level env binding")

    # 3. The first step validates, strictly, before anything is checked out.
    first = steps[0]
    if "run" not in first or f'[[ ! "$REVISION" =~ {REVISION_PATTERN} ]]' not in first["run"]:
        fail("the first step must refuse any REVISION not matching " + REVISION_PATTERN)
    checkouts = [i for i, s in enumerate(steps) if str(s.get("uses", "")).startswith("actions/checkout@")]
    if checkouts != [1] or steps[1].get("with", {}).get("ref") != "${{ env.REVISION }}":
        fail("the second step must check out exactly ${{ env.REVISION }}, after validation")

    # 4. The CI lookup and the build use the validated variable.
    runs = [s.get("run", "") for s in steps]
    if not any("actions/workflows/ci.yml/runs?head_sha=${REVISION}" in r
               and '.conclusion == "success" and .head_sha == $ENV.REVISION' in r for r in runs):
        fail("the CI lookup must require a successful ci.yml run whose head_sha is REVISION")
    if not any(re.search(r'infrastructure/release/build-release\.sh "[^"]*" "\$REVISION" ', r) for r in runs):
        fail('build-release.sh must receive "$REVISION"')

    # 5. The validator itself, executed: every malformed value refused, the good one accepted, nothing evaluated.
    validator = first["run"]
    with tempfile.TemporaryDirectory() as directory:
        sentinel = os.path.join(directory, "executed")
        malformed = {
            "not a sha": "not-a-sha",
            "39 hex": GOOD[:39],
            "41 hex": GOOD + "0",
            "uppercase hex": GOOD.upper(),
            "trailing newline": GOOD + "\n",
            "leading space": " " + GOOD,
            "empty": "",
            "command substitution": GOOD[:30] + "$(touch " + sentinel + ")",
            "backticks": GOOD[:30] + "`touch " + sentinel + "`",
            "separator": GOOD + ";touch " + sentinel,
        }
        for label, value in malformed.items():
            result = subprocess.run(["bash", "-c", validator], env={"REVISION": value, "PATH": os.environ["PATH"]},
                                    capture_output=True, text=True)
            if result.returncode == 0:
                fail(f"the validator accepted a malformed revision ({label})")
        if os.path.exists(sentinel):
            fail("a metacharacter in the revision was EXECUTED by the validator")
        accepted = subprocess.run(["bash", "-c", validator], env={"REVISION": GOOD, "PATH": os.environ["PATH"]},
                                  capture_output=True, text=True)
        if accepted.returncode != 0:
            fail("the validator refused a well-formed revision")
    print(f"release workflow check passed: {len(malformed)} malformed revisions refused, none executed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

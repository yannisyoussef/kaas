#!/usr/bin/env python3
"""Fails unless an evidence file states every expected key with exactly the expected value.

Usage: evidence-gate.py <evidence-file> key=value [key=value ...]

Evidence files are appended to by several tests, so a key can appear more than once. Every appearance must
carry the expected value: one right line beside one wrong line is a contradiction, not a pass. A missing file or
a missing key fails. Values are compared exactly; nothing is a substring or a pattern.
"""
import sys


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    path = argv[0]
    try:
        with open(path, encoding="utf-8") as evidence:
            lines = [line.rstrip("\n") for line in evidence if line.strip()]
    except OSError:
        print(f"{path}: MISSING -- the suite recorded nothing")
        return 1
    seen = {}
    for line in lines:
        key, _, value = line.partition("=")
        seen.setdefault(key, []).append(value)
    failed = False
    for expectation in argv[1:]:
        key, _, expected = expectation.partition("=")
        values = seen.get(key)
        if not values:
            print(f"{key}: ABSENT (expected {expected})")
            failed = True
        elif any(value != expected for value in values):
            print(f"{key}: {sorted(set(values))} (expected exactly {expected})")
            failed = True
        else:
            print(f"{key}={expected}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

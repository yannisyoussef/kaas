#!/usr/bin/env python3
"""Fails unless a JUnit result directory proves the named suites ran whole.

Usage: junit-gate.py <results-dir> --require <fully.qualified.Class>=<minimum-executed> [...]

For every required class: its XML exists, it executed at least the stated number of tests, and it skipped,
failed and errored none. A suite renamed out of the gate, a test deleted from it, a stale directory holding
another suite's results, or a skip -- each fails here by name. Counts are read from the XML's own attributes,
never from a grep that could match a test's name or a failure's message.
"""
import glob
import sys
import xml.etree.ElementTree as ET


def main(argv):
    if len(argv) < 3 or argv[1] != "--require" and "--require" not in argv:
        print(__doc__)
        return 2
    results = argv[0]
    required = {}
    for index, token in enumerate(argv):
        if token == "--require":
            name, minimum = argv[index + 1].split("=")
            required[name] = int(minimum)
    # Keyed by the result FILE, which Gradle names TEST-<fully qualified class>.xml. The testsuite element's own
    # name is the class's @DisplayName when it has one, so it cannot identify the class.
    suites = {}
    for path in glob.glob(results + "/TEST-*.xml"):
        name = path.rsplit("/", 1)[-1][len("TEST-"):-len(".xml")]
        root = ET.parse(path).getroot()
        suites[name] = root if root.tag == "testsuite" else next(root.iter("testsuite"))
    failed = False
    for name, minimum in required.items():
        suite = suites.get(name)
        if suite is None:
            print(f"{name}: NO RESULTS in {results}")
            failed = True
            continue
        tests = int(suite.get("tests", "0"))
        skipped = int(suite.get("skipped", "0"))
        failures = int(suite.get("failures", "0"))
        errors = int(suite.get("errors", "0"))
        print(f"{name}: executed={tests} skipped={skipped} failures={failures} errors={errors}")
        if tests < minimum or skipped != 0 or failures != 0 or errors != 0:
            print(f"{name}: expected at least {minimum} executed and no skip, failure or error")
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

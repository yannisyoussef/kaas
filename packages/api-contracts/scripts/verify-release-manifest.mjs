// Verifies a KaaS release manifest (KAAS-DEPLOY-001): the schema, the invariants a schema cannot state, and --
// with --check-labels -- that every image the manifest names really was built from the revision it claims.
//
//   node scripts/verify-release-manifest.mjs <manifest.json> [--expect-revision <sha>] [--check-labels]
//   node scripts/verify-release-manifest.mjs --self-test
//
// Exit 0 only when every check passes. Prints one line per finding and never an image's contents.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readdir, readFile } from "node:fs/promises";
import Ajv2020 from "ajv/dist/2020.js";

const COMPONENTS = ["api", "runner", "karate-engine", "egress-proxy", "security-probe"];
const REVISION_LABEL = "org.opencontainers.image.revision";

const schema = JSON.parse(await readFile(new URL("../release-manifest.schema.json", import.meta.url), "utf8"));
const validate = new Ajv2020({ allErrors: true, strict: true }).compile(schema);

/** Every problem with a manifest, or none. Pure: no registry access. */
export function problemsWith(manifest, expectedRevision) {
  const problems = [];
  if (!validate(manifest)) {
    for (const error of validate.errors) {
      problems.push(`schema: ${error.instancePath || "/"} ${error.message}`);
    }
    return problems;
  }
  const references = COMPONENTS.map((component) => manifest.images[component]);
  const digests = references.map((reference) => reference.slice(reference.indexOf("@sha256:") + 1));
  const repositories = references.map((reference) => reference.slice(0, reference.indexOf("@")));
  // Two components at one digest means one of them is not what its name says.
  if (new Set(digests).size !== digests.length) {
    problems.push("duplicate: two components resolve to the same image digest");
  }
  if (new Set(repositories).size !== repositories.length) {
    problems.push("duplicate: two components share one repository");
  }
  if (expectedRevision !== undefined && manifest.revision !== expectedRevision) {
    problems.push("revision: the manifest names a different revision than the one being released");
  }
  return problems;
}

/** Pulls each image BY DIGEST and reads its revision label. */
function labelProblems(manifest) {
  const problems = [];
  for (const component of COMPONENTS) {
    const reference = manifest.images[component];
    try {
      execFileSync("docker", ["pull", "--quiet", reference], { stdio: ["ignore", "ignore", "pipe"] });
      const label = execFileSync("docker", ["inspect", "--format", `{{index .Config.Labels "${REVISION_LABEL}"}}`,
        reference], { encoding: "utf8" }).trim();
      if (label !== manifest.revision) {
        problems.push(`label: ${component} carries ${REVISION_LABEL}=${label || "<absent>"}, not the manifest revision`);
      }
    } catch {
      problems.push(`label: ${component} could not be pulled by digest and inspected`);
    }
  }
  return problems;
}

async function selfTest() {
  const directory = new URL("../fixtures/release-manifest/", import.meta.url);
  const names = (await readdir(directory)).filter((name) => name.endsWith(".json")).sort();
  let checked = 0;
  for (const name of names) {
    const manifest = JSON.parse(await readFile(new URL(name, directory), "utf8"));
    const problems = problemsWith(manifest);
    if (name.startsWith("valid-")) {
      assert.deepEqual(problems, [], `${name} should verify`);
    } else {
      assert.ok(problems.length > 0, `${name} should be refused`);
    }
    if (name.startsWith("semantic-invalid-duplicate-digest")) {
      assert.ok(problems.some((problem) => problem.includes("same image digest")), name);
    }
    if (name.startsWith("semantic-invalid-duplicate-repository")) {
      assert.ok(problems.some((problem) => problem.includes("share one repository")), name);
    }
    checked += 1;
  }
  const valid = JSON.parse(await readFile(new URL("valid-release.json", directory), "utf8"));
  assert.ok(problemsWith(valid, "f".repeat(40)).some((problem) => problem.startsWith("revision:")),
    "a manifest for another revision must be refused");
  assert.ok(checked >= 10, "the self-test must exercise every fixture");
  console.log(`release-manifest verifier: ${checked} fixtures behaved as expected`);
}

const args = process.argv.slice(2);
if (args[0] === "--self-test") {
  await selfTest();
} else {
  const path = args[0];
  if (!path) {
    console.error("usage: verify-release-manifest.mjs <manifest.json> [--expect-revision <sha>] [--check-labels]");
    process.exit(2);
  }
  const expected = args.includes("--expect-revision") ? args[args.indexOf("--expect-revision") + 1] : undefined;
  let manifest;
  try {
    manifest = JSON.parse(await readFile(path, "utf8"));
  } catch {
    console.error("release_manifest=UNREADABLE");
    process.exit(1);
  }
  const problems = problemsWith(manifest, expected);
  if (problems.length === 0 && args.includes("--check-labels")) {
    problems.push(...labelProblems(manifest));
  }
  for (const problem of problems) {
    console.error(problem);
  }
  console.log(problems.length === 0 ? "release_manifest=VALID" : "release_manifest=INVALID");
  process.exit(problems.length === 0 ? 0 : 1);
}

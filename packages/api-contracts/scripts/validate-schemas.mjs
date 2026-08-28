import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const contracts = [
  { name: "runner-command", schema: "../runner-command.schema.json", fixtureDirectory: "../fixtures/runner-command", maxBytes: 4 * 1024 * 1024 },
  { name: "runner-result", schema: "../runner-result.schema.json", fixtureDirectory: "../fixtures/runner-result", maxBytes: 16 * 1024 * 1024 },
  { name: "artifact-manifest", schema: "../artifact-manifest.schema.json", fixtureDirectory: "../fixtures/artifact-manifest", maxBytes: 1024 * 1024 },
  { name: "live-event", schema: "../live-event.schema.json", fixtureDirectory: "../fixtures/live-event", maxBytes: 64 * 1024 },
];

const expectedKeywordByName = new Map([
  ["invalid-missing-identity.json", "required"],
  ["invalid-version.json", "const"],
  ["invalid-status.json", "enum"],
  ["invalid-extra-property.json", "additionalProperties"],
  ["invalid-logical-path.json", "pattern"],
  ["invalid-structured-error.json", "pattern"],
  ["invalid-outcome-combination.json", "const"],
  ["invalid-artifact-metadata.json", "minimum"],
  ["invalid-payload-type.json", "required"],
  ["invalid-message-type.json", "const"],
  ["invalid-stale-version.json", "minimum"],
]);

const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);

const validFixtures = new Map();

for (const contract of contracts) {
  const schema = await readJson(contract.schema);
  const validate = ajv.compile(schema);
  const fixtureNames = (await readdir(new URL(`${contract.fixtureDirectory}/`, import.meta.url))).filter((name) => name.endsWith(".json")).sort();
  const validNames = fixtureNames.filter((name) => name.startsWith("valid-"));
  const invalidNames = fixtureNames.filter((name) => name.startsWith("invalid-"));

  assert.ok(validNames.length >= 2, `${contract.name} must have at least two valid fixtures`);
  assert.ok(invalidNames.length >= 4, `${contract.name} must have at least four isolated invalid fixtures`);

  const fixtures = [];
  for (const fixtureName of validNames) {
    const fixture = await readJson(`${contract.fixtureDirectory}/${fixtureName}`);
    assert.ok(Buffer.byteLength(JSON.stringify(fixture)) <= contract.maxBytes, `${contract.name}/${fixtureName} exceeds decoded-byte policy`);
    assert.equal(validate(fixture), true, `${contract.name}/${fixtureName}: ${ajv.errorsText(validate.errors)}`);
    fixtures.push({ name: fixtureName, value: fixture });
  }
  validFixtures.set(contract.name, fixtures);

  for (const fixtureName of invalidNames) {
    const fixture = await readJson(`${contract.fixtureDirectory}/${fixtureName}`);
    assert.equal(validate(fixture), false, `${contract.name}/${fixtureName} unexpectedly passed`);
    const expectedKeyword = expectedKeywordByName.get(fixtureName);
    assert.ok(expectedKeyword, `missing expected AJV keyword for ${fixtureName}`);
    assert.ok(validate.errors?.some((error) => error.keyword === expectedKeyword), `${contract.name}/${fixtureName} did not fail on expected ${expectedKeyword}: ${ajv.errorsText(validate.errors)}`);
  }

  console.log(`validated ${contract.name}: schema compiled; ${validNames.length} valid and ${invalidNames.length} invalid fixtures behaved as expected`);
}

validateSemanticInvariants(validFixtures);
console.log("validated semantic invariants: chronology, summaries, identity binding, uniqueness, progress, and aggregate byte policies");

function validateSemanticInvariants(fixtures) {
  const command = findFixture(fixtures, "runner-command", "valid-canonical.json");
  const result = findFixture(fixtures, "runner-result", "valid-canonical.json");
  const manifest = findFixture(fixtures, "artifact-manifest", "valid-canonical.json");
  const events = fixtures.get("live-event").map(({ value }) => value).sort((left, right) => left.sequence - right.sequence);

  assert.ok(Date.parse(command.occurredAt) < Date.parse(command.deadline), "command deadline must follow creation");
  assert.notEqual(command.payload.environmentSnapshot.environmentId, command.payload.environmentSnapshot.environmentRevisionId, "environment identity and revision identity must remain distinct");
  assert.ok(Date.parse(result.startedAt) <= Date.parse(result.finishedAt), "result start must not follow finish");
  assert.ok(Date.parse(result.finishedAt) <= Date.parse(result.occurredAt), "result publication must not precede finish");

  for (const field of ["organizationId", "projectId", "runId", "attemptId", "attemptNumber", "assignmentEpoch", "commandId"]) {
    assert.equal(result[field], command[field], `result ${field} must match command`);
    assert.equal(manifest[field], command[field], `manifest ${field} must match command`);
  }

  const artifactIds = manifest.artifacts.map((artifact) => artifact.artifactId);
  const objectReferences = manifest.artifacts.map((artifact) => artifact.objectReferenceId);
  assert.equal(new Set(artifactIds).size, artifactIds.length, "artifact IDs must be unique within a manifest");
  assert.equal(new Set(objectReferences).size, objectReferences.length, "artifact references must be unique within a manifest");
  assert.ok(manifest.artifacts.reduce((sum, artifact) => sum + artifact.sizeBytes, 0) <= command.payload.artifactPolicy.maxTotalBytes, "manifest must honor command aggregate artifact limit");

  assert.deepEqual(events.map((event) => event.sequence), [1, 2, 3, 4, 5], "canonical SSE event sequence must be contiguous");
  for (const event of events) {
    assert.equal(event.runId, command.runId, "live event run identity must match command");
    if (event.eventType === "PROGRESS") {
      assert.ok(event.payload.completedScenarios <= event.payload.totalScenarios, "progress completed count cannot exceed total");
    }
  }

  const computed = computeFinalSummary(result.features);
  assert.deepEqual(result.summary, computed, "result summary must be recomputed from final logical outcomes");
  assert.equal(result.features.length, new Set(result.features.map((feature) => `${feature.featureId}:${feature.revisionId}`)).size, "feature revision identities must be unique");
  for (const feature of result.features) {
    assert.equal(feature.scenarios.length, new Set(feature.scenarios.map((scenario) => scenario.id)).size, "scenario identities must be unique within a feature");
    for (const scenario of feature.scenarios) {
      assert.deepEqual(scenario.attempts.map((attempt) => attempt.attemptNumber), scenario.attempts.map((_, index) => index + 1), "scenario retry attempts must be contiguous and one-based");
      assert.equal(scenario.status, scenario.attempts.at(-1).status, "scenario status must equal final test-level attempt status");
    }
  }
}

function computeFinalSummary(features) {
  const featureStatuses = features.map((feature) => feature.status);
  const scenarios = features.flatMap((feature) => feature.scenarios);
  const scenarioStatuses = scenarios.map((scenario) => scenario.status);
  const stepStatuses = scenarios.flatMap((scenario) => scenario.attempts.at(-1).steps.map((step) => step.status));
  return {
    features: countStatuses(featureStatuses),
    scenarios: countStatuses(scenarioStatuses),
    steps: countStatuses(stepStatuses),
  };
}

function countStatuses(statuses) {
  return {
    total: statuses.length,
    passed: statuses.filter((status) => status === "PASSED").length,
    failed: statuses.filter((status) => status === "FAILED").length,
    skipped: statuses.filter((status) => status === "SKIPPED").length,
    aborted: statuses.filter((status) => status === "ABORTED").length,
  };
}

function findFixture(fixtures, contractName, fixtureName) {
  return fixtures.get(contractName).find(({ name }) => name === fixtureName).value;
}

async function readJson(relativePath) {
  return JSON.parse(await readFile(new URL(relativePath, import.meta.url), "utf8"));
}

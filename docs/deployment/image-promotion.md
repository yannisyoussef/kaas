# Image promotion and the release handoff

KAAS-DEPLOY-001 · [ADR-035 §7](../adr/035-deployment-readiness-runner-claim-intake.md)

## Who owns what

| GitHub (this repository) | Operations / GitLab |
|---|---|
| tests and security gates (CI, eleven jobs) | release and deployment orchestration |
| building every image from one revision | pulling by digest; verifying before deploy |
| publishing to the registry | host configuration, secrets, keys, runsc, firewall |
| the release manifest: revision + digests | running migrate → serve → runner → deploy-check |

KaaS is never rebuilt in GitLab. Nothing in this repository calls GitLab: it holds no trigger, token or project
id, and none is guessed. The handoff is the manifest.

## The five images

| Component | Built from | Deployed as |
|---|---|---|
| `api` | `apps/api` (`:apps:api:apiImageContext`) | control plane; `kaas-api migrate`; `kaas-api deploy-check` |
| `runner` | `services/runner` (`:services:runner:runnerImageContext`) | the execution-host runner |
| `karate-engine` | `services/karate-engine` (`:services:karate-engine:engineImageContext`) | launched by the runner (`KAAS_RUNNER_ENGINE_IMAGE`) |
| `egress-proxy` | `services/egress-proxy` (`:services:egress-proxy:proxyImageContext`) | launched by the runner (`KAAS_RUNNER_EGRESS_PROXY_IMAGE`) |
| `security-probe` | `services/runner/src/main/docker/probe` | launched by the runner's attestation refresh (`KAAS_RUNNER_PROBE_IMAGE`) |

The Operations assessment listed four. There are five: the runner re-runs the security gates on the execution
host to refresh its evidence, and the gates run the trusted probe. Every base image is pinned by digest in its
Dockerfile.

Every image carries the OCI label **`org.opencontainers.image.revision=<full commit sha>`** (plus
`org.opencontainers.image.source` and `.title`), stamped by the release build. No estate-specific label
convention was found in the repository, so the OCI standard key is used.

## The release manifest

Schema: [`packages/api-contracts/release-manifest.schema.json`](../../packages/api-contracts/release-manifest.schema.json).

```json
{
  "schemaVersion": "kaas.release-manifest.v1",
  "revision": "<40 hex>",
  "images": {
    "api":            "ghcr.io/<owner>/kaas-api@sha256:<64 hex>",
    "runner":         "ghcr.io/<owner>/kaas-runner@sha256:<64 hex>",
    "karate-engine":  "ghcr.io/<owner>/kaas-karate-engine@sha256:<64 hex>",
    "egress-proxy":   "ghcr.io/<owner>/kaas-egress-proxy@sha256:<64 hex>",
    "security-probe": "ghcr.io/<owner>/kaas-security-probe@sha256:<64 hex>"
  }
}
```

Refused: a tag (alone or as `name:tag@digest`), a missing or unknown component, a short revision, another
schema version, two components at one digest, two components in one repository.

Verify it, including every label against the registry:

```
node packages/api-contracts/scripts/verify-release-manifest.mjs release-manifest.json \
     --expect-revision <sha> --check-labels
```

`release_manifest=VALID` and exit 0, or one line per problem and exit 1.

## Producing a release

[`.github/workflows/release-images.yml`](../../.github/workflows/release-images.yml), run manually with a
revision whose CI run succeeded:

1. refuses a revision with no successful CI run;
2. [`infrastructure/release/build-release.sh`](../../infrastructure/release/build-release.sh) refuses a revision
   that is not the checked-out HEAD or a dirty tree, assembles the contexts, builds the five images with the
   labels, pushes them to `ghcr.io/<owner>/kaas-<component>`, records the **digest the registry returned**, writes
   the manifest, and verifies it with `--check-labels`;
3. uploads the manifest as the artifact `kaas-release-manifest-<sha>`.

The `deployment-readiness` CI job runs the same script on every push against a registry that exists only for
that job, then checks that the verifier refuses a relabelled image, a tag and a missing component.

## What Operations' side should do with it

1. Fetch `kaas-release-manifest-<sha>`.
2. Run the verifier with `--expect-revision <sha> --check-labels` (or equivalent: every reference is
   `@sha256:`, every image's revision label equals the manifest revision).
3. Deploy by digest only: `api` → migrate, then serve; `runner` on the execution host with
   `KAAS_RUNNER_ENGINE_IMAGE`, `KAAS_RUNNER_PROBE_IMAGE` (and `KAAS_RUNNER_EGRESS_PROXY_IMAGE`) set to the
   manifest's references, and those three images present on the host.
4. Run `kaas-api deploy-check` and require exit 0.

## Not asserted

- Publication to GHCR has **not** been performed by this slice; the workflow is manual and was not dispatched.
- Branch protection: CI's jobs exist and none is skippable; whether merging requires them is repository
  administration that was not inspected.
- Bit-for-bit reproducibility is not claimed; bases and dependencies are pinned, and identity is the pushed
  digest.

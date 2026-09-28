#!/usr/bin/env bash
# Builds, publishes and hands off one KaaS release (KAAS-DEPLOY-001).
#
#   infrastructure/release/build-release.sh <registry-prefix> <revision> <manifest-out>
#
#   registry-prefix  where images are pushed, e.g. ghcr.io/<owner> or localhost:5000/kaas
#   revision         the FULL commit sha being released; must be the checked-out HEAD
#   manifest-out     where the release manifest is written
#
# GitHub owns this: it has just tested the revision, it builds the five images from it, stamps each with
# org.opencontainers.image.revision=<revision>, pushes them, and records each by the DIGEST THE REGISTRY
# RETURNED -- never a tag. The manifest is then verified against the registry itself: every image is pulled by
# digest and its revision label must equal the manifest's revision. Operations deploys from the manifest and
# nothing else; nothing here rebuilds in GitLab or touches a host.
set -euo pipefail

prefix="${1:?registry prefix}"
revision="${2:?revision}"
out="${3:?manifest output path}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

if ! [[ "$revision" =~ ^[0-9a-f]{40}$ ]]; then
  echo "revision must be a full 40-character commit sha" >&2
  exit 2
fi
if [[ "$(git rev-parse HEAD)" != "$revision" ]]; then
  echo "revision is not the checked-out HEAD; a release is built from exactly what it names" >&2
  exit 2
fi
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "the working tree has uncommitted changes; they would be in the images and not in the revision" >&2
  exit 2
fi

./gradlew --no-daemon -q :apps:api:apiImageContext :services:runner:runnerImageContext \
  :services:karate-engine:engineImageContext :services:egress-proxy:proxyImageContext

# Plain functions and variables rather than associative arrays, so this runs on any bash an operator has --
# including the 3.2 macOS still ships.
context_of() {
  case "$1" in
    api) echo apps/api/build/api-image-context ;;
    runner) echo services/runner/build/runner-image-context ;;
    karate-engine) echo services/karate-engine/build/engine-image-context ;;
    egress-proxy) echo services/egress-proxy/build/proxy-image-context ;;
    security-probe) echo services/runner/src/main/docker/probe ;;
    *) return 1 ;;
  esac
}
digests=()

for component in api runner karate-engine egress-proxy security-probe; do
  repository="$prefix/kaas-$component"
  # --pull=false: every base is pinned by digest in its Dockerfile, so there is nothing a pull could change
  # except by being wrong.
  docker build --pull=false \
    --label "org.opencontainers.image.revision=$revision" \
    --label "org.opencontainers.image.source=https://github.com/${GITHUB_REPOSITORY:-local/kaas}" \
    --label "org.opencontainers.image.title=kaas-$component" \
    -t "$repository:$revision" "$(context_of "$component")" >/dev/null
  docker push --quiet "$repository:$revision" >/dev/null
  pushed="$(docker inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$repository:$revision" \
    | grep -F "$repository@sha256:" | head -1)"
  if [[ -z "$pushed" ]]; then
    echo "no registry digest recorded for $component" >&2
    exit 1
  fi
  digests+=("$pushed")
  echo "image $component=$pushed"
done

python3 - "$out" "$revision" "${digests[@]}" <<'PY'
import json, sys
out, revision, api, runner, engine, proxy, probe = sys.argv[1:]
manifest = {
    "schemaVersion": "kaas.release-manifest.v1",
    "revision": revision,
    "images": {
        "api": api, "runner": runner, "karate-engine": engine, "egress-proxy": proxy, "security-probe": probe,
    },
}
with open(out, "w") as handle:
    json.dump(manifest, handle, indent=2)
    handle.write("\n")
PY

node packages/api-contracts/scripts/verify-release-manifest.mjs "$out" --expect-revision "$revision" --check-labels

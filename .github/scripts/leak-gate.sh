#!/usr/bin/env bash
# Fails if a mediated-runtime suite left anything behind: KaaS-managed containers, KaaS-managed networks, or a
# runsc sentry. The process check matches the executable (first token) rather than any mention of the runtime,
# for the reason recorded in the strong-runtime gate: this script's own command line mentions it.
set -euo pipefail
containers=$(docker ps -aq --filter label=kaas.managed=true | wc -l | tr -d ' ')
networks=$(docker network ls -q --filter label=kaas.managed=true | wc -l | tr -d ' ')
sentries=$(ps -eo args= | awk '{print $1}' | grep -cE '(^|/)runsc(-sandbox|-gofer)?$' || true)
echo "containers=$containers networks=$networks runsc_processes=${sentries:-0}"
if [ "$containers" -ne 0 ] || [ "$networks" -ne 0 ] || [ "${sentries:-0}" -ne 0 ]; then
  echo "Resources outlived the suite." >&2
  exit 1
fi

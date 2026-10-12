#!/bin/bash
# Runs the spec-contract gate by hand (design D5 of tighten-spec-contract).
# Compatible with bash 3.x (macOS) and bash 4+ (Linux).
#
# Usage:
#   ./scripts/check-spec-contract.sh               judge openspec/specs and the scenario links
#   ./scripts/check-spec-contract.sh --changes     also judge the unarchived deltas
#   ./scripts/check-spec-contract.sh --spec=a,b    judge only these spec ids
#
# A launcher only: every rule lives in the Gradle task `checkSpecContract` (SpecContractCheck in
# build-logic); the arguments are passed to it unchanged. Runs from the repository root wherever
# it is called from. The rules themselves: .claude/rules/traceability.md, delta-specs.md and
# testing.md.

set -euo pipefail

cd "$(dirname "$0")/.."
exec ./gradlew -q checkSpecContract "$@"

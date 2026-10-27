#!/bin/bash
# Previews the archive stage's verdict over one change: archives it in a scratch copy of
# openspec/ and validates every merged main spec strictly there (FR4 of
# split-long-spec-requirements, design D3, D4). `openspec validate <change> --strict` leaves a
# MODIFIED block unmeasured, so a requirement grown past OpenSpec's limit is otherwise seen only
# after the merge, by the archive round, which may not edit the delta.
# Compatible with bash 3.x (macOS) and bash 4+ (Linux).
#
# Usage:
#   scripts/check-merged-specs.sh <change>
#
# Runs from the repository root wherever it is called from. The repository is never written: the
# archive runs in a copy in a fresh directory under ${TMPDIR:-/tmp}, removed on every exit. The
# CLI is ${OPENSPEC:-openspec}, the one seam MergedSpecsPreviewScriptSpec hands a stand-in
# through; no other tool but POSIX utilities runs, and nothing reaches the network.
#
# Outcomes:
#   exit 0  the archive merged and every main spec validates strictly;
#   exit 1  the validation fails (the failing specs and OpenSpec's own lines are printed, then
#           what to do), the archive refuses (its own words), the CLI cannot be run, or the name
#           is not an active change — each with its cause, never 0.

set -euo pipefail

readonly ADVICE='check-merged-specs: split the requirement in the delta — one behavior per requirement — or, for a failure the change does not touch, escalate (.claude/rules/delta-specs.md)'

fail() {
    echo "check-merged-specs: $*" >&2
    exit 1
}

if [ "$#" -ne 1 ]; then
    fail "usage: $0 <change>"
fi
change=$1
case "$change" in
    archive | '' | [!a-z0-9]* | *[!a-z0-9-]*)
        fail "'$change' is not a change name"
        ;;
esac

cd "$(dirname "$0")/.."
root=$(pwd)
[ -d "openspec/changes/$change" ] || fail "'$change' is not an active change under openspec/changes/"

openspec=${OPENSPEC:-openspec}
command -v "$openspec" >/dev/null 2>&1 \
    || fail "the OpenSpec CLI '$openspec' cannot be run; put openspec on the PATH"

# An explicit template: macOS mktemp ignores TMPDIR without one.
tmp_root=${TMPDIR:-/tmp}
scratch=$(mktemp -d "${tmp_root%/}/check-merged-specs.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
cp -R "$root/openspec" "$scratch/openspec"
cd "$scratch"

if ! archived=$("$openspec" archive "$change" --yes 2>&1); then
    printf '%s\n' "$archived"
    fail "the archive of '$change' refused (its words above): the delta no longer applies to the main specs"
fi

if ! validated=$("$openspec" validate --specs --strict --no-interactive 2>&1); then
    printf '%s\n' "$validated" | grep -v '^✓' || true
    echo "check-merged-specs: the main specs merged with '$change' do not validate strictly"
    echo "$ADVICE"
    exit 1
fi
echo "check-merged-specs: the main specs merged with '$change' validate strictly"

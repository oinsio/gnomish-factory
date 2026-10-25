#!/bin/sh
# Selects the release the weekly sandbox base rebuild builds: the final release with the highest
# version. The rebuild workflow (.github/workflows/sandbox-image-rebuild.yml) runs it first and
# checks out the tag it prints. Implements FR8 of add-sandbox-base-image (design D6, D15). Kept
# out of the workflow YAML so LatestFinalReleaseScriptSpec in :bootstrap drives every outcome.
# POSIX sh; reaches `gh` and `jq` through PATH, with the job's token in GH_TOKEN.
#
# Usage:
#   scripts/latest-final-release.sh
#
# The listing is the raw JSON of `gh release list --exclude-drafts --exclude-pre-releases --limit
# 1000 --json tagName,isDraft,isPrerelease`, and the selection is made here, never by a `--jq`
# expression: a release counts when it is neither a draft nor a pre-release (whatever the listing
# returned) and its tag is vMAJOR.MINOR.PATCH with no suffix; the highest version wins, compared as
# numbers, never the latest creation date, so a later patch of an older line (v0.1.5 published
# after v0.2.0) does not take the rebuild from the current one.
#
# Outcomes:
#   exit 0  a final release exists: `tag` (v0.2.0) and `version` (0.2.0) are printed and appended
#           to $GITHUB_OUTPUT when it is set;
#   exit 0  no final release exists yet: a `::notice`, and `tag` and `version` empty, so the
#           workflow skips the build and the schedule is not red before the first release;
#   exit 1  gh or jq fails: an `::error` naming the command and "re-run"; the tool's own error is
#           printed above it, as the tool wrote it. A failure is never read as "no release".

set -eu

if [ "$#" -ne 0 ]; then
    echo "usage: $0" >&2
    exit 2
fi

readonly FINAL_TAG='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

# Prints each `key=value` and appends it to $GITHUB_OUTPUT when that is set.
emit() {
    printf '%s\n' "$@"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        printf '%s\n' "$@" >>"$GITHUB_OUTPUT"
    fi
}

if ! listing="$(gh release list --exclude-drafts --exclude-pre-releases --limit 1000 \
    --json tagName,isDraft,isPrerelease)"; then
    echo "::error title=Latest final release::gh release list failed (its error is printed above), so the release to rebuild is unknown and nothing was built; re-run once the GitHub API answers"
    exit 1
fi

if ! tags="$(printf '%s\n' "$listing" |
    jq -r '.[] | select(.isDraft == false and .isPrerelease == false) | .tagName')"; then
    echo "::error title=Latest final release::jq could not read the listing gh release list printed (its error is printed above), so the release to rebuild is unknown and nothing was built; re-run once the GitHub API answers"
    exit 1
fi

latest="$(printf '%s\n' "$tags" | grep -E "$FINAL_TAG" | sed 's/^v//' |
    sort -t . -k 1,1n -k 2,2n -k 3,3n | tail -n 1)"

if [ -z "$latest" ]; then
    echo "::notice title=Latest final release::no final release exists yet, so the weekly rebuild has nothing to build"
    emit 'tag=' 'version='
    exit 0
fi
emit "tag=v${latest}" "version=${latest}"

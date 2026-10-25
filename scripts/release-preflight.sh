#!/bin/bash
# Release preflight: the three checks the release workflow (.github/workflows/release.yml)
# runs before it builds anything. Implements FR1, FR2, NFR-O1 of add-release-pipeline
# (design D6, steps 1-3). Kept out of the workflow YAML so ReleasePreflightScriptSpec in
# :bootstrap can drive the red paths too. Compatible with bash 3.x (macOS) and bash 4+
# (Linux). Run from the repository root, in a checkout that has `origin/main`.
#
# Usage:
#   scripts/release-preflight.sh <tag> <commit-sha>
#   scripts/release-preflight.sh --base-tag <tag>
#
# Checks, in order, each failing before the next runs:
#   1. the tag is vMAJOR.MINOR.PATCH with an optional pre-release suffix (FR1);
#   2. the commit is reachable from origin/main (FR2);
#   3. the commit's CI run — ci.yml, on a push to main — concluded successfully (FR2).
#      The query filters by branch and event, so a run that reached the commit through
#      another ref (a branch push, a pull request) is never the one judged.
#
# Every failure is a GitHub `::error` annotation naming the check and the fix (NFR-O1).
# On success, `version` (the tag without its `v`), `prerelease` (true for a suffixed
# version), `base_tag` and `image_tags` are appended to $GITHUB_OUTPUT when it is set, and printed
# either way.
#
# `base_tag` is the tag of the sandbox base image the release names (design D11 of
# add-sandbox-base-image): MAJOR.MINOR for a tag without a suffix, the whole version for a
# pre-release, which never moves MAJOR.MINOR. `--base-tag <tag>` runs check 1 only and prints
# (and outputs) `base_tag` — no git and no gh call — for a job that needs the base tag of a tag
# whose other checks have nothing to do with it (the weekly base rebuild).
#
# `image_tags` is the JSON array of the tags the release's `image` job pushes the base under
# (design D6, D15 of add-sandbox-base-image; FR7 of add-sandbox-base-image): `["X.Y.Z","X.Y"]` for
# a final release, `["X.Y.Z-<pre>"]` for a pre-release, whose base tag is its whole version, so
# `X.Y` never moves to a pre-release. It is built from `version` and `base_tag` above, never by a
# second reading of the tag: the base tag equals the version exactly for a pre-release.

set -euo pipefail

readonly TAG_PATTERN='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$'
readonly MAIN_REF='origin/main'
readonly CI_WORKFLOW='ci.yml'

fail() {
    echo "::error title=$1::$2"
    exit 1
}

check_tag_shape() {
    local tag="$1"
    if ! [[ "$tag" =~ $TAG_PATTERN ]]; then
        fail 'Tag shape' "tag '${tag}' is not vMAJOR.MINOR.PATCH with an optional -suffix (e.g. v0.2.0, v0.2.0-rc.1); delete it with 'git push --delete origin ${tag}' and push a correctly shaped tag"
    fi
}

# Kept in sync with build-logic's product-version-conventions (the `baseTag` provider): both take
# MAJOR.MINOR of a version without a suffix and the whole version with one, so the base the release
# pushes is the base the factory names. BaseTagIdentityFunctionalSpec pins the two equal per tag.
# Runs after check_tag_shape, so the tag matches.
base_tag_of() {
    local tag="$1"
    [[ "$tag" =~ $TAG_PATTERN ]]
    if [ -z "${BASH_REMATCH[4]}" ]; then
        echo "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}"
    else
        echo "${tag#v}"
    fi
}

# Prints each `key=value` and appends it to $GITHUB_OUTPUT when that is set.
emit() {
    printf '%s\n' "$@"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        printf '%s\n' "$@" >>"$GITHUB_OUTPUT"
    fi
}

check_on_main() {
    local sha="$1"
    if ! git rev-parse --verify --quiet "${MAIN_REF}^{commit}" >/dev/null; then
        fail 'Commit on main' "${MAIN_REF} does not resolve; the release job must check out the full history (fetch-depth: 0)"
    fi
    if ! git merge-base --is-ancestor "$sha" "$MAIN_REF"; then
        fail 'Commit on main' "commit ${sha} is not reachable from main; merge the change to main, wait for its CI run, and tag the merged commit"
    fi
}

# The newest matching run decides: `gh run list` returns runs newest first.
check_ci_green() {
    local sha="$1" runs newest status url conclusion
    if ! runs="$(gh run list --workflow "$CI_WORKFLOW" --commit "$sha" --branch main --event push \
        --limit 20 --json status,conclusion,url --jq '.[] | "\(.status) \(.url) \(.conclusion)"')"; then
        fail 'CI status' "could not list the ${CI_WORKFLOW} runs for ${sha}; re-run this workflow once the GitHub API answers"
    fi
    if [ -z "$runs" ]; then
        fail 'CI status' "no ${CI_WORKFLOW} run on a push to main exists for ${sha}; the tagged commit must reach main by a push and pass CI there"
    fi
    newest="$(printf '%s\n' "$runs" | head -n 1)"
    # The conclusion goes last: it is empty while a run is in progress.
    read -r status url conclusion <<EOF
$newest
EOF
    if [ "$status" != 'completed' ]; then
        fail 'CI status' "the ${CI_WORKFLOW} run for ${sha} is still ${status} (${url}); re-run this workflow when it finishes"
    fi
    if [ "$conclusion" != 'success' ]; then
        fail 'CI status' "the ${CI_WORKFLOW} run for ${sha} concluded '${conclusion}' (${url}); fix main and tag a commit whose CI run is green"
    fi
}

if [ "$#" -eq 2 ] && [ "$1" = '--base-tag' ]; then
    check_tag_shape "$2"
    emit "base_tag=$(base_tag_of "$2")"
    exit 0
fi
if [ "$#" -ne 2 ]; then
    echo "usage: $0 <tag> <commit-sha> | $0 --base-tag <tag>" >&2
    exit 2
fi
readonly TAG="$1" SHA="$2"

check_tag_shape "$TAG"
base_tag="$(base_tag_of "$TAG")"
check_on_main "$SHA"
check_ci_green "$SHA"

version="${TAG#v}"
prerelease=false
case "$version" in
    *-*) prerelease=true ;;
esac
# One tag when the base tag is the version itself (a pre-release), the version and its line otherwise.
if [ "$base_tag" = "$version" ]; then
    image_tags="[\"${version}\"]"
else
    image_tags="[\"${version}\",\"${base_tag}\"]"
fi
emit "version=${version}" "prerelease=${prerelease}" "base_tag=${base_tag}" "image_tags=${image_tags}"

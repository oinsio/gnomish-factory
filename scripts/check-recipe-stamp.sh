#!/bin/sh
# Checks that an unpacked release archive ships the reference sandbox recipe stamped with the
# release's base tag: the release workflow (.github/workflows/release.yml) runs it beside the
# check that the archive reports the tag's version. Implements FR9 of add-sandbox-base-image
# (design D11, D15; single-owner row 4, the release branch of the stamp, which no CI test build
# reaches). Kept out of the workflow YAML so RecipeStampArchiveScriptSpec in :bootstrap drives it.
# POSIX sh; reads files only.
#
# Usage:
#   scripts/check-recipe-stamp.sh <unpacked archive> <base tag>
#
# The base tag is handed in — the release workflow passes the preflight job's `base_tag` output —
# and never derived here from a version. Each of `share/sandbox-image/Dockerfile` and
# `share/sandbox-image/README.md` under <unpacked archive> must exist and carry at least one line
# starting `ARG GNOMISH_BASE_TAG=` (the lines the packaging stamp rewrites), every one of them
# exactly `ARG GNOMISH_BASE_TAG=<base tag>`. Every file that fails is a GitHub `::error`
# annotation naming the file and what it carries; all are reported before the non-zero exit.

set -eu

if [ "$#" -ne 2 ] || [ -z "$2" ]; then
    echo "usage: $0 <unpacked archive> <base tag>" >&2
    exit 2
fi
readonly ARCHIVE="$1" BASE_TAG="$2"
readonly ARG='ARG GNOMISH_BASE_TAG='

failed=0
for name in Dockerfile README.md; do
    file="${ARCHIVE}/share/sandbox-image/${name}"
    if [ ! -f "$file" ]; then
        echo "::error title=Recipe stamp::${file} is missing from the archive"
        failed=1
        continue
    fi
    # The lines the packaging stamp rewrites: those starting with the argument.
    lines="$(grep "^${ARG}" "$file" || true)"
    if [ -z "$lines" ]; then
        echo "::error title=Recipe stamp::${file} has no '${ARG}' line; expected '${ARG}${BASE_TAG}'"
        failed=1
        continue
    fi
    found="$(printf '%s\n' "$lines" | grep -v -x -F "${ARG}${BASE_TAG}" | head -n 1 || true)"
    if [ -n "$found" ]; then
        echo "::error title=Recipe stamp::${file} carries '${found}', expected '${ARG}${BASE_TAG}'; the release's base tag did not reach the shipped recipe"
        failed=1
    fi
done
exit "$failed"

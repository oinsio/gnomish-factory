#!/bin/sh
# Refuses to publish the sandbox base over an image tag the registry already holds: the release
# workflow's `image` job (.github/workflows/release.yml) runs it before its push. Implements FR7,
# NFR-S3 of add-sandbox-base-image (design D6, D15). Kept out of the workflow YAML so
# ImageTagRefusalScriptSpec in :bootstrap drives every outcome. POSIX sh; reaches `docker` through
# PATH, after the job's registry login.
#
# Usage:
#   scripts/image-tag-refusal.sh <image> <tag>
#
# Outcomes, from `docker manifest inspect <image>:<tag>`:
#   exit 0  the registry answers that no such manifest exists: the tag is free to push;
#   exit 1  the manifest exists: a release tag is never overwritten, so the push must not run;
#           the message names the recovery of a tag an earlier run of this release left
#           without its attestation (attest it by its digest in a separate run, never delete it);
#   exit 2  docker fails any other way (login, network, rate limit, denied access): whether the
#           tag exists is unknown, so nothing is pushed and the message names the captured error
#           and the re-run. A failure is never read as an absent tag: that would overwrite a
#           release tag with a green build.
#
# The answers read as "no such manifest" are docker's own texts, matched whole, never by a
# substring: `manifest unknown` (ghcr's answer for a missing tag in an existing package, recorded
# with docker 29.4.0) and `no such manifest: <image>:<tag>` (docker's text for a registry's
# not-found answer). Any other text is exit 2 — a visible refusal, never a push. The library's
# copies of these texts are the stand-in answers `data/stderr-registry` the spec runs over.
#
# Every failure is a GitHub `::error` annotation naming the tag; the captured stderr is escaped
# the way workflow commands require (`%`, CR and LF), so a multi-line error stays one annotation.

set -eu

if [ "$#" -ne 2 ]; then
    echo "usage: $0 <image> <tag>" >&2
    exit 2
fi
readonly IMAGE="$1" TAG="$2"
readonly REF="${IMAGE}:${TAG}"

# The workflow-command form of a captured text: `%` first, then CR and LF.
escaped() {
    printf '%s' "$1" | awk 'BEGIN { ORS = "" } { gsub(/%/, "%25"); gsub(/\r/, "%0D"); if (NR > 1) printf "%%0A"; print }'
}

if err="$(docker manifest inspect "$REF" 2>&1 >/dev/null)"; then
    echo "::error title=Image tag exists::${REF} already exists in the registry, and a release tag is never overwritten; if an earlier run of this release pushed it and stopped before its attestation, do not delete it: attest it by its digest in a separate run (read the digest with 'docker buildx imagetools inspect ${REF}', attest that subject digest with push-to-registry, then scan it), as the developer guide's Releasing section describes"
    exit 1
fi

case "$err" in
    'manifest unknown' | "no such manifest: ${REF}")
        echo "${REF} does not exist yet; the release may push it"
        exit 0
        ;;
esac

echo "::error title=Image tag check::could not tell whether ${REF} exists, so nothing was pushed: docker manifest inspect failed with: $(escaped "$err"); re-run once the registry answers"
exit 2

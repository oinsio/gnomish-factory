#!/bin/bash
# The Linux spec runner (design D9 of make-pipe-reads-non-parking, as amended 2026-10-19;
# glossary: "Linux spec runner"). Runs named Gradle tasks and specs of this build on Linux, on
# the platform of the CI `build` job, from a developer's machine, so CI is never the first Linux
# signal (.claude/rules/verification-scope.md, step 5 and "The cheapest platform signal first").
# Compatible with bash 3.x (macOS) and bash 4+ (Linux). Needs docker and rsync on the host.
#
# Usage:
#   scripts/linux-specs.sh [--rebuild] [--repeat N] <gradle args>...
#
#   scripts/linux-specs.sh :subprocess:test
#   scripts/linux-specs.sh :adapters:agent:test --tests 'StreamDrain*'
#   scripts/linux-specs.sh --repeat 20 :subprocess:test --tests '*EscapedHolderSpec'
#
# What it does:
#   1. builds the image from scripts/linux-runner/Dockerfile, with scripts/ as the build context
#      (the image runs scripts/install-git.sh), when it is missing (or always, with --rebuild);
#      the tag carries the hash of both files, so an edit of either builds a new image;
#   2. copies the working tree, .git included and build/ and .gradle/ excluded, into
#      <scratch>/repo (refreshed by rsync on every run);
#   3. runs ./gradlew <gradle args> in the container as the developer's uid:gid, with HOME and
#      GRADLE_USER_HOME under <scratch>/home, which persists, so dependencies download once,
#      and USER and LOGNAME set as on a CI runner (a spec of the child environment reads USER);
#   4. prints where the reports are and exits with Gradle's exit code.
#   --repeat N runs the same arguments N times with --rerun appended (Gradle's built-in task
#   option, applied to the last task named), stops at the first failure and prints how many
#   runs passed.
#
# Every docker call runs under the committed client configuration, never the operator's: the
# operator's ~/.docker/config.json may name a credential helper (on macOS
# docker-credential-desktop, which raises a system prompt on the base image's pull), plugin hooks
# and proxies (testing.md, "The Docker client configuration is replaced"). DOCKER_HOST is the
# operator's own when set, otherwise the current context's endpoint, read before the switch;
# DOCKER_CONTEXT is removed.
# Kept in sync with build-logic's TestDockerEnvironment (and its DockerContextEndpoint): the
# committed file test-fixtures/src/main/resources/docker-config/config.json copied into a
# directory of our own, cliPluginsExtraDirs naming ${DOCKER_CONFIG:-$HOME/.docker}/cli-plugins
# when it exists, and the endpoint resolution with its refusal of ssh:// and TLS endpoints.
#
# The scratch directory is $LINUX_SPECS_DIR, default ${TMPDIR:-/tmp}/gnomish-linux-specs. The
# variable deliberately has no GNOMISH_ prefix: that namespace is the factory's own runtime
# configuration, which the test build strips (testing.md, "Test processes never inherit the
# operator's environment"); this is a developer tool on the host.

set -euo pipefail

usage() {
    sed -n '8,13p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

repeat=1
rebuild=0
while test $# -gt 0; do
    case "$1" in
        --rebuild) rebuild=1; shift ;;
        --repeat)
            case "${2:-}" in '' | *[!0-9]* | 0) usage ;; esac
            repeat=$2
            shift 2
            ;;
        *) break ;;
    esac
done
test $# -gt 0 || usage

repo=$(cd "$(dirname "$0")/.." && pwd)
dockerfile_dir="$repo/scripts/linux-runner"
tmp=${TMPDIR:-/tmp}
scratch=${LINUX_SPECS_DIR:-${tmp%/}/gnomish-linux-specs}
scratch=${scratch%/}
mkdir -p "$scratch/repo" "$scratch/home" "$scratch/docker-config"

# The endpoint, read under the operator's own configuration before it is replaced.
if test -z "${DOCKER_HOST:-}"; then
    answer=$(docker context inspect --format '{{.Endpoints.docker.Host}}|{{if .TLSMaterial}}tls{{end}}' \
        2>/dev/null || true)
    endpoint=${answer%|*}
    case "$endpoint|${answer##*|}" in
        ssh://* | *'|tls')
            echo "linux-specs: the current Docker context's endpoint ($endpoint) cannot be carried" \
                "by DOCKER_HOST alone; set DOCKER_HOST (and, for TLS, DOCKER_CERT_PATH and" \
                "DOCKER_TLS_VERIFY) in the environment that runs this script" >&2
            exit 2
            ;;
    esac
    if test -n "$endpoint"; then
        export DOCKER_HOST="$endpoint"
    fi
fi
unset DOCKER_CONTEXT

# The committed configuration, plus the operator's plugin directory (buildx) when it exists.
plugins="${DOCKER_CONFIG:-${HOME:-}/.docker}/cli-plugins"
committed=$(tr -d '\n' <"$repo/test-fixtures/src/main/resources/docker-config/config.json")
if test -n "${DOCKER_CONFIG:-}${HOME:-}" && test -d "$plugins"; then
    case "$plugins" in *'"'* | *\\*)
        echo "linux-specs: cannot write $plugins into a JSON string" >&2
        exit 2
        ;;
    esac
    entry="\"cliPluginsExtraDirs\": [\"$plugins\"]"
    if printf '%s' "$committed" | grep -Eq '^[[:space:]]*\{[[:space:]]*\}[[:space:]]*$'; then
        committed="{ $entry }"
    else
        committed="{ $entry, ${committed#*\{}"
    fi
fi
printf '%s\n' "$committed" >"$scratch/docker-config/config.json"
export DOCKER_CONFIG="$scratch/docker-config"

if command -v sha256sum >/dev/null 2>&1; then
    sha=(sha256sum)
else
    sha=(shasum -a 256)
fi
hash=$(cat "$dockerfile_dir/Dockerfile" "$repo/scripts/install-git.sh" | "${sha[@]}" | cut -c1-12)
image="gnomish-linux-specs:$hash"

if test "$rebuild" -eq 1 || ! docker image inspect "$image" >/dev/null 2>&1; then
    echo "linux-specs: building $image from scripts/linux-runner/Dockerfile" >&2
    if test "$rebuild" -eq 1; then
        docker build --pull --no-cache --tag "$image" \
            --file "$dockerfile_dir/Dockerfile" "$repo/scripts" >&2
    else
        docker build --tag "$image" --file "$dockerfile_dir/Dockerfile" "$repo/scripts" >&2
    fi
fi

echo "linux-specs: copying the working tree to $scratch/repo" >&2
rsync -a --delete \
    --exclude '/temporary-docs' --exclude 'build/' --exclude '.gradle/' --exclude '.idea/' \
    "$repo/" "$scratch/repo/"
# The configuration cache keeps the test task's environment from the run that stored it, and the
# environment this script hands the container is no input Gradle tracks: a stale entry ran
# specs without USER after the script began to pass it. Each run configures afresh.
rm -rf "$scratch/repo/.gradle/configuration-cache"

run_gradle() {
    docker run --rm --init \
        --user "$(id -u):$(id -g)" \
        -e HOME=/w/home -e GRADLE_USER_HOME=/w/home/.gradle \
        -e USER="$(id -un)" -e LOGNAME="$(id -un)" \
        -v "$scratch:/w" -w /w/repo \
        "$image" ./gradlew --no-daemon --console=plain "$@"
}

status=0
passed=0
if test "$repeat" -eq 1; then
    run_gradle "$@" || status=$?
else
    while test "$passed" -lt "$repeat"; do
        echo "linux-specs: run $((passed + 1)) of $repeat" >&2
        run_gradle "$@" --rerun || { status=$?; break; }
        passed=$((passed + 1))
    done
    echo "linux-specs: $passed of $repeat runs passed" >&2
fi

echo "linux-specs: reports under $scratch/repo/<module>/build/reports/ (tests/test/index.html)" >&2
exit "$status"

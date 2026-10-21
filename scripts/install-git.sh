#!/bin/bash
# Installs the git the factory's own build runs on, from a pinned kernel.org source release
# (design D11 of own-git-invocation-policy; task 8.4). Run by the CI `build` job
# (.github/workflows/ci.yml), the nightly mutation run (.github/workflows/pitest-nightly.yml)
# and the Linux spec runner's image (scripts/linux-runner/Dockerfile) — never needed on a
# developer's macOS machine, where Homebrew's git is current.
#
# Why a source build: the factory refuses a git below its floor (GitVersion.FLOOR, 2.56.0),
# ubuntu-latest ships 2.55.0 and no Ubuntu package source published 2.56.0 when the floor was
# raised. One script is the one source for both CI and the runner, so the two cannot drift. The
# next floor raise bumps VERSION and SHA256 below and nothing else.
#
# Usage:
#   scripts/install-git.sh [--prefix DIR]
#
#   --prefix DIR   install under DIR (default /usr/local); git lands in DIR/bin, which the
#                  caller puts first on PATH (/usr/local/bin already is on Ubuntu).
#
# What it does:
#   1. when DIR/bin/git already reports exactly VERSION (a restored CI cache), skips to step 5;
#   2. installs the build dependencies through apt-get (as root, or through sudo);
#   3. downloads git-VERSION.tar.xz from kernel.org and refuses it unless its SHA-256 equals
#      the pinned one (taken from kernel.org's signed sha256sums.asc);
#   4. builds and installs it without Rust (optional in 2.56, needs cargo), Tcl/Tk, gettext,
#      Perl, Python or OpenSSL (fewer build dependencies; https transport goes through libcurl,
#      git-remote-https);
#   5. refuses the result unless DIR/bin/git meets the floor (the same awk test as the CI step).
#
# The build dependencies stay installed: the runner image purges them in its own layer, and on
# a CI runner they are harmless. Linux only (apt-get); bash 3.x compatible like its siblings.

set -euo pipefail

VERSION=2.56.0
SHA256=26c56c296b38c0695b26fa95f475f1d01704d2d38e73465ca30b0b2f5dc789d3
URL="https://mirrors.edge.kernel.org/pub/software/scm/git/git-$VERSION.tar.xz"
BUILD_DEPS="build-essential curl xz-utils libcurl4-openssl-dev libexpat1-dev zlib1g-dev"

usage() {
    sed -n '13,17p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

prefix=/usr/local
while test $# -gt 0; do
    case "$1" in
        --prefix)
            test -n "${2:-}" || usage
            prefix=${2%/}
            shift 2
            ;;
        *) usage ;;
    esac
done

if test "$(id -u)" -eq 0; then
    sudo=""
else
    sudo=sudo
fi

meets_floor() {
    "$prefix/bin/git" version |
        awk '{ split($3, v, "."); exit !(v[1] > 2 || (v[1] == 2 && v[2] >= 56)) }'
}

installed=$("$prefix/bin/git" version 2>/dev/null || true)
if test "$installed" = "git version $VERSION"; then
    echo "install-git: $prefix/bin/git is already $VERSION; nothing to build" >&2
else
    echo "install-git: installing the build dependencies" >&2
    $sudo apt-get update
    # shellcheck disable=SC2086 # the list is meant to split into words
    DEBIAN_FRONTEND=noninteractive $sudo apt-get install -y --no-install-recommends $BUILD_DEPS

    work=$(mktemp -d)
    trap 'rm -rf "$work"' EXIT
    echo "install-git: downloading $URL" >&2
    curl --fail --silent --show-error --location --retry 3 --output "$work/git.tar.xz" "$URL"
    actual=$(sha256sum "$work/git.tar.xz" | cut -d' ' -f1)
    if test "$actual" != "$SHA256"; then
        echo "install-git: checksum mismatch for git-$VERSION.tar.xz:" \
            "expected $SHA256, got $actual; refusing to build it" >&2
        exit 1
    fi

    tar -xJf "$work/git.tar.xz" -C "$work"
    flags="prefix=$prefix NO_RUST=YesPlease NO_TCLTK=YesPlease NO_GETTEXT=YesPlease"
    flags="$flags NO_PERL=YesPlease NO_PYTHON=YesPlease NO_OPENSSL=YesPlease"
    flags="$flags INSTALL_SYMLINKS=YesPlease"
    echo "install-git: building git $VERSION into $prefix" >&2
    # shellcheck disable=SC2086 # the flags are meant to split into words
    make -C "$work/git-$VERSION" -j "$(nproc)" $flags all >&2
    mkdir -p "$prefix" 2>/dev/null || $sudo mkdir -p "$prefix"
    if test -w "$prefix"; then
        install_as=""
    else
        install_as=$sudo
    fi
    # shellcheck disable=SC2086
    $install_as make -C "$work/git-$VERSION" $flags install >&2
fi

"$prefix/bin/git" version
if ! meets_floor; then
    echo "install-git: $prefix/bin/git is below the factory's floor (2.56)" >&2
    exit 1
fi

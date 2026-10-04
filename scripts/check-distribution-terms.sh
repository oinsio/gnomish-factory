#!/bin/bash
# Distribution-terms checks of the license CI job (.github/workflows/license-gate.yml)
# and the release workflow (.github/workflows/release.yml). Implements FR1 and FR3 of
# add-project-license (design D2) and NFR-S2 of add-release-pipeline (design D6, step 6).
# Kept out of the workflow YAML so DistributionTermsScriptSpec in :bootstrap can drive
# the red paths too.
# Compatible with bash 3.x (macOS) and bash 4+ (Linux). Run from the repository root.
#
# Usage:
#   scripts/check-distribution-terms.sh presence
#       fails unless LICENSE and NOTICE exist in the current directory (FR1)
#   scripts/check-distribution-terms.sh identity <jar-dir>...
#       fails unless each directory holds exactly one jar whose META-INF/LICENSE and
#       META-INF/NOTICE equal the root files byte for byte (FR3)
#   scripts/check-distribution-terms.sh archive <unpacked-dir>
#       fails unless <unpacked-dir>/LICENSE and <unpacked-dir>/NOTICE equal the root files
#       byte for byte and <unpacked-dir>/lib passes `identity` (NFR-S2 of add-release-pipeline)
#
# Every failure is printed as a GitHub `::error` annotation naming the file (and the
# jar); all failures are reported before the non-zero exit.

set -euo pipefail

readonly TERMS="LICENSE NOTICE"

presence() {
    local missing=0 file
    for file in $TERMS; do
        if ! test -f "$file"; then
            echo "::error file=${file}::${file} is missing from the repository root"
            missing=1
        fi
    done
    return "$missing"
}

# `cmp` compares the bytes; a listing (`unzip -l`) would prove only the entry name.
# `pipefail` makes a missing entry fail even against an empty root file. Each jar
# directory must hold exactly one jar, so a stray artifact cannot make the check
# compare the wrong file.
identity() {
    local failed=0 dir jar file
    for dir in "$@"; do
        set -- "$dir"/*.jar
        if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
            echo "::error::expected exactly one jar in ${dir}, found: $*"
            failed=1
            continue
        fi
        jar="$1"
        for file in $TERMS; do
            if ! unzip -p "$jar" "META-INF/${file}" 2>/dev/null | cmp -s - "$file"; then
                echo "::error::${jar}: META-INF/${file} is missing or differs from the root ${file}"
                failed=1
            fi
        done
    done
    return "$failed"
}

# An unpacked distribution archive: its top-level copies are compared with the root files
# of the current directory, never with each other, which is why `identity` alone cannot be
# run inside the unpacked folder (design D6, step 6).
archive() {
    local dir="$1" failed=0 file
    for file in $TERMS; do
        if ! test -f "${dir}/${file}"; then
            echo "::error file=${dir}/${file}::${dir}/${file} is missing from the archive"
            failed=1
        elif ! cmp -s "${dir}/${file}" "$file"; then
            echo "::error file=${dir}/${file}::${dir}/${file} differs from the root ${file}"
            failed=1
        fi
    done
    identity "${dir}/lib" || failed=1
    return "$failed"
}

case "${1:-}" in
    presence)
        presence
        ;;
    identity)
        shift
        if [ "$#" -eq 0 ]; then
            echo "usage: $0 identity <jar-dir>..." >&2
            exit 2
        fi
        identity "$@"
        ;;
    archive)
        if [ "$#" -ne 2 ]; then
            echo "usage: $0 archive <unpacked-dir>" >&2
            exit 2
        fi
        archive "$2"
        ;;
    *)
        echo "usage: $0 presence | identity <jar-dir>... | archive <unpacked-dir>" >&2
        exit 2
        ;;
esac

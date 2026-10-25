#!/bin/sh
# The one translation of the image contract file into the sandbox base's build arguments.
# Implements FR6, FR7, FR8, FR10 of add-sandbox-base-image (design D2, D16; single-owner row 1).
# Every build of sandbox-image/Dockerfile takes its contract arguments from here: the release
# workflow's `image` job, the weekly rebuild (.github/workflows/sandbox-image-rebuild.yml) and the
# container E2E base fixture (SandboxBaseImage in :test-fixtures). ImageContractBuildArgsScriptSpec
# in :bootstrap pins the rule. POSIX sh; reads one file.
#
# Usage:
#   scripts/image-contract-build-args.sh [<properties file>]
#
# The file defaults to sandbox-image/image-contract.properties, relative to the working directory.
# Each `key=value` line prints one `NAME=value` line, in file order — the form of docker's
# `--build-arg` and of the `build-args` input of docker/build-push-action. The name is derived by
# rule, never from a table: the key upper-cased, each `-` replaced by `_` (`guard-port` names the
# argument `GUARD_PORT`), so a key added to the file reaches every build without an edit here. The
# Dockerfile declares each argument with no default and fails on an empty one, so it stays the
# check that the names match.
#
# Comment lines (`#` first) and empty lines are skipped. Every other line must be `key=value` with
# a key of [a-z][a-z0-9-]* and a non-empty value that neither begins nor ends with a whitespace
# character (a space, a tab, or the carriage return a CRLF line ending leaves): the factory's
# reader strips the value while its runtime check compares the image's label exactly, so a hidden
# tail kept on one side and stripped on the other would build a base every box is refused on. A
# line that fails is a GitHub `::error` annotation naming the file and the line, exit 1, and
# no argument line is printed — never a silent strip, never a partial list.

set -eu

if [ "$#" -gt 1 ]; then
    echo "usage: $0 [<properties file>]" >&2
    exit 2
fi
readonly FILE="${1:-sandbox-image/image-contract.properties}"

if [ ! -r "$FILE" ] || [ ! -f "$FILE" ]; then
    echo "::error title=Image contract::cannot read the image contract file ${FILE}"
    exit 1
fi

awk -v file="$FILE" '
    function refuse(why) {
        printf "::error title=Image contract,file=%s,line=%d::%s line %d %s\n", file, NR, file, NR, why
        failed = 1
        exit 1
    }
    /^#/ || /^$/ { next }
    !/^[a-z][a-z0-9-]*=/ { refuse("is not a key=value line with a key of [a-z][a-z0-9-]*") }
    {
        split_at = index($0, "=")
        key = substr($0, 1, split_at - 1)
        value = substr($0, split_at + 1)
        if (value == "") {
            refuse("gives " key " an empty value")
        }
        if (value ~ /^[ \t\r\f\v]/ || value ~ /[ \t\r\f\v]$/) {
            refuse("gives " key " a value that begins or ends with whitespace (a space, a tab, or the carriage return of a CRLF line ending); remove it")
        }
        name = toupper(key)
        gsub(/-/, "_", name)
        lines[++count] = name "=" value
    }
    END {
        if (failed) {
            exit 1
        }
        for (i = 1; i <= count; i++) {
            print lines[i]
        }
    }
' "$FILE"

#!/bin/sh
# Fake agent binary — stands in for `claude` behind the configurable CLI binary
# path (design D7, D11 of add-agent-executor). It does not parse its own
# invocation: real CLI flags (`-p`, `--output-format stream-json --verbose`,
# `--model`, ...) are accepted positionally on the command line for realism in
# ProcessBuilder logs, but the fake's behaviour is driven entirely by the
# GNOMISH_FAKE_SCENARIO env var, which names a scenario directory under
# scenarios/ (sibling to this script) — see README.md in that directory for
# the per-scenario file contract.
#
# Contract (task 2.1, FR15):
#   - prints the scenario's scripted stream-json to stdout, one event per line
#   - copies the scenario's workspace-files/ tree (if any) into the cwd the
#     caller launched us in — the fake's stand-in for "the agent wrote files"
#   - writes the scenario's decision.json (if any) to $GNOMISH_DECISION_FILE,
#     when that env var is set — the fake's stand-in for the decision-file
#     protocol (D1)
#   - sleeps for the scenario's sleep-seconds (if any) before exiting — the
#     fake's stand-in for a hung CLI, so roundTimeout-kill tests observe a
#     real killed OS process (FR13)
#   - exits with the scenario's exitcode (default 0)
#
# Optional, backward-compatible extras (task 9.5, opt-in, unset/absent by
# default everywhere else so no existing scenario/spec is affected):
#   - if $GNOMISH_FAKE_CAPTURE_ARGV names a file path, "$@" is appended to it
#     (one invocation per line) before anything else runs — lets a spec
#     inspect what CLI argv a later attempt actually received
#   - if $GNOMISH_FAKE_CAPTURE_STDIN names a file path, this invocation's stdin
#     (the round prompt — prompts travel on stdin since add-sandbox-core, FR24/
#     D18) is appended to it, one block per invocation terminated by a "---"
#     line — lets a spec inspect the prompt a later attempt actually received
#   - if the scenario directory contains next-scenario, this invocation
#     plays the CURRENT scenario only the first time in a given cwd (a
#     marker file, .gnomish-fake-attempt-marker, is dropped in cwd to
#     remember that); every later invocation in the same cwd re-execs as the
#     scenario named in next-scenario instead — the multi-attempt stand-in
#     for "the operator answered, so attempt 2 is a different round" (D1),
#     relying on the workspace persisting across attempts of the same stage
#
# Not production code: a test double, never PIT-mutated (Java-only gate).
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SCENARIOS_DIR="$SCRIPT_DIR/scenarios"

if [ -z "${GNOMISH_FAKE_SCENARIO:-}" ]; then
    echo "fake-agent.sh: GNOMISH_FAKE_SCENARIO is not set" >&2
    exit 64
fi

# Optional argv capture, opt-in only — a no-op when unset (every existing
# caller leaves this unset, so behaviour there is unchanged). Args are joined
# with newline, one arg per line: each invocation's block ends with a line
# containing only "---" so a reader can split invocations even though a real
# prompt argument may itself contain embedded newlines.
if [ -n "${GNOMISH_FAKE_CAPTURE_ARGV:-}" ]; then
    printf '%s\n' "$@" >> "$GNOMISH_FAKE_CAPTURE_ARGV"
    printf -- '---\n' >> "$GNOMISH_FAKE_CAPTURE_ARGV"
fi

# Optional stdin capture, opt-in only — a no-op when unset. Since add-sandbox-core the round
# prompt is delivered on stdin, never argv (FR24, D18): this reads that stdin into the named
# file, one block per invocation, each terminated by a line containing only "---" (the same
# convention as the argv capture above) so a reader can split invocations. Only invocations
# whose caller sets this var read stdin; every other scenario leaves stdin untouched.
if [ -n "${GNOMISH_FAKE_CAPTURE_STDIN:-}" ]; then
    cat >> "$GNOMISH_FAKE_CAPTURE_STDIN"
    printf -- '\n---\n' >> "$GNOMISH_FAKE_CAPTURE_STDIN"
fi

SCENARIO_DIR="$SCENARIOS_DIR/$GNOMISH_FAKE_SCENARIO"
if [ ! -d "$SCENARIO_DIR" ]; then
    echo "fake-agent.sh: unknown scenario '$GNOMISH_FAKE_SCENARIO' (no directory at $SCENARIO_DIR)" >&2
    exit 64
fi

# Multi-attempt redirect, opt-in only (scenario must contain next-scenario):
# the first invocation in a given cwd plays this scenario and drops a
# marker; every later invocation in the same cwd re-execs as the scenario
# named in next-scenario instead.
if [ -f "$SCENARIO_DIR/next-scenario" ]; then
    MARKER=".gnomish-fake-attempt-marker"
    if [ -f "$MARKER" ]; then
        GNOMISH_FAKE_SCENARIO=$(cat "$SCENARIO_DIR/next-scenario")
        export GNOMISH_FAKE_SCENARIO
        SCENARIO_DIR="$SCENARIOS_DIR/$GNOMISH_FAKE_SCENARIO"
    else
        touch "$MARKER"
    fi
fi

# 1a. Optional generated noise, emitted BEFORE the scripted stream so the
# scripted result event stays last (fix-round-stdout-drain task 4.1): the
# scenario names one stream-json line in noise-line and a repeat count in
# noise-repeat, and the fake prints that line that many times. A generator
# rather than a checked-in fixture — the point is a stream far larger than the
# ~64 KB OS pipe buffer, and a megabyte of committed JSON is not something a
# repository should carry. Writes are synchronous, so a scenario that exceeds
# the pipe buffer also stands in for a writer that would block on a full pipe.
if [ -f "$SCENARIO_DIR/noise-line" ] && [ -f "$SCENARIO_DIR/noise-repeat" ]; then
    NOISE_LINE=$(cat "$SCENARIO_DIR/noise-line")
    NOISE_REPEAT=$(cat "$SCENARIO_DIR/noise-repeat")
    i=0
    while [ "$i" -lt "$NOISE_REPEAT" ]; do
        printf '%s\n' "$NOISE_LINE"
        i=$((i + 1))
    done
fi

# 1b. Scripted stream-json to stdout, verbatim, one event per line.
if [ -f "$SCENARIO_DIR/stdout.jsonl" ]; then
    cat "$SCENARIO_DIR/stdout.jsonl"
fi

# 2. Workspace files the "agent" wrote this round, copied into the cwd the
# caller launched us in (the stage workspace, or a harness temp dir in tests).
if [ -d "$SCENARIO_DIR/workspace-files" ]; then
    cp -R "$SCENARIO_DIR/workspace-files/." .
fi

# 3. Decision-file protocol stand-in (D1): only written when the caller wired
# $GNOMISH_DECISION_FILE, mirroring the real adapter's per-round temp path.
# Parent directories are created like the real CLI's Write tool would — the
# in-branch transport of add-sandbox-core (FR23, D17) names a path under
# .gnomish-task/decisions/ that need not exist yet in a fresh working copy.
if [ -f "$SCENARIO_DIR/decision.json" ] && [ -n "${GNOMISH_DECISION_FILE:-}" ]; then
    mkdir -p "$(dirname "$GNOMISH_DECISION_FILE")"
    cp "$SCENARIO_DIR/decision.json" "$GNOMISH_DECISION_FILE"
fi

# 4. Optional sleep before exiting — the fake's stand-in for a hung CLI
# process (a scenario that outlives a test's roundTimeout budget).
if [ -f "$SCENARIO_DIR/sleep-seconds" ]; then
    sleep "$(cat "$SCENARIO_DIR/sleep-seconds")"
fi

# 5. Exit code — default 0 (a clean round) when the scenario has no opinion.
EXIT_CODE=0
if [ -f "$SCENARIO_DIR/exitcode" ]; then
    EXIT_CODE=$(cat "$SCENARIO_DIR/exitcode")
fi
exit "$EXIT_CODE"

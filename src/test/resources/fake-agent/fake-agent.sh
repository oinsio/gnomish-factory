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
# with NUL, not newline: a real prompt argument routinely contains embedded
# newlines, so a newline-delimited log would misrepresent invocation
# boundaries. Each invocation's block ends with a line containing only
# "---" so a reader can split invocations without parsing NUL bytes.
if [ -n "${GNOMISH_FAKE_CAPTURE_ARGV:-}" ]; then
    printf '%s\n' "$@" >> "$GNOMISH_FAKE_CAPTURE_ARGV"
    printf -- '---\n' >> "$GNOMISH_FAKE_CAPTURE_ARGV"
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

# 1. Scripted stream-json to stdout, verbatim, one event per line.
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
if [ -f "$SCENARIO_DIR/decision.json" ] && [ -n "${GNOMISH_DECISION_FILE:-}" ]; then
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

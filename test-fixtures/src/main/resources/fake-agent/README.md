# Fake agent binary (test infrastructure)

Stands in for the real `claude` CLI in tests (design D11, task 2 of
add-agent-executor): a script that reads args, prints scripted stream-json,
writes workspace files, and exits with a chosen code — a real
`ProcessBuilder`/pipes/exit-code round trip, deterministic and free. This is
**not production code**; it is never PIT-mutated (the mutation gate targets
`src/main` Java only).

## How a future adapter points at this binary

Task 4.1 (`FactoryProperties`, not yet implemented) is expected to expose a
configurable CLI binary path, defaulting to `claude` on `PATH` (design D7).
Tests for the CLI adapters (groups 6–7) should set that property (or its
equivalent constructor/builder argument before Spring wiring exists) to:

```
sh <this-directory>/fake-agent.sh
```

i.e. invoke the script via `sh <path>`, not the bare path — this project's
existing process-runner convention (`ShellCommandCheckRunner`,
`CommandProcessRunner`) always shells out rather than relying on the
filesystem executable bit, which Gradle's resource copy / git checkout does
not reliably preserve. `FakeAgentBinary.commandPrefix()` (in this package's
Spock harness, `adapter.agent.fake`) returns exactly this two-element command
list, ready to prepend to whatever adapter-specific args the real launcher
appends (`-p`, `--output-format stream-json --verbose`, `--model`, ...).

The fake ignores every CLI arg it receives — real transport flags may be
passed for ProcessBuilder-log realism, but behaviour is selected purely
through environment variables (below), matching how the real adapter has no
other channel to control a scripted double deterministically.

## Environment contract

| Variable                     | Required | Meaning                                                                                                                                                                                                                                                                                                                              |
|------------------------------|----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GNOMISH_FAKE_SCENARIO`      | yes      | Name of a subdirectory under `scenarios/` to play back                                                                                                                                                                                                                                                                               |
| `GNOMISH_DECISION_FILE`      | no       | Path the fake writes `decision.json` to, if the scenario has one                                                                                                                                                                                                                                                                     |
| `GNOMISH_FAKE_CAPTURE_ARGV`  | no       | Path to append this invocation's `"$@"` to, one arg per line, each invocation's block terminated by a `---` line (task 9.5) — unset everywhere except the spec that needs to inspect a later attempt's actual CLI argv                                                                                                                                                                  |
| `GNOMISH_FAKE_CAPTURE_STDIN` | no       | Path to append this invocation's stdin (the round prompt) to, one block per call terminated by a `---` line — unset everywhere except the spec that inspects the prompt. Since add-sandbox-core (FR24, D18) the prompt is delivered on stdin, never argv, so a spec asserting prompt content reads this rather than the argv capture |

Missing `GNOMISH_FAKE_SCENARIO`, or a scenario directory that does not exist,
is a harness misconfiguration: the script exits 64 (`EX_USAGE`) with a
diagnostic on stderr rather than emitting a silently empty round.

## Scenario directory contract

Each `scenarios/<name>/` directory may contain:

| File / dir                    | Effect                                                                                                                                                                                                                                                                                                                 |
|-------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `noise-line` + `noise-repeat` | the named stream-json line is printed `noise-repeat` times BEFORE `stdout.jsonl`, so the scripted result event stays last — the generator behind the large-output scenarios (fix-round-stdout-drain task 4.1). Both files must be present for either to take effect; a megabyte of noise is generated, never committed |
| `stdout.jsonl`                | printed verbatim to stdout, one stream-json event per line                                                                                                                                                                                                                                                             |
| `workspace-files/`            | copied recursively into the cwd the fake was launched in                                                                                                                                                                                                                                                               |
| `decision.json`               | copied to `$GNOMISH_DECISION_FILE` when that env var is set (parent directories created first, like the real CLI's Write — the in-branch transport names a not-yet-existing `.gnomish-task/decisions/` path)                                                                                                           |
| `sleep-seconds`               | the fake sleeps this many seconds before exiting — a stand-in for a hung CLI (roundTimeout-kill tests)                                                                                                                                                                                                                 |
| `exitcode`                    | process exit code (plain integer, no newline convention enforced); default `0`                                                                                                                                                                                                                                         |
| `next-scenario`               | name of the scenario to play on every invocation AFTER the first, in the same cwd (task 9.5) — see below                                                                                                                                                                                                               |

### Multi-attempt scenarios (task 9.5)

A scenario carrying `next-scenario` plays itself only on the first
invocation in a given cwd; it drops a marker file
(`.gnomish-fake-attempt-marker`) there, and every later invocation in the
same cwd re-execs as the scenario named in `next-scenario` instead. This
relies on the stage workspace persisting across attempts of the same stage
(it does — the real adapter reuses the same working copy for reruns), and
lets one scenario stand in for "attempt 1 asks a decision, attempt 2 — after
the operator answers — completes cleanly" without any test-harness support
for per-attempt env var overrides.

## Scenario library (task 2.2)

| Scenario                      | Covers                                                                                                                                                                                                                                                                                                                                                                                            |
|-------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `plain-round`                 | a clean round: init → tool_use/tool_result → final message → result                                                                                                                                                                                                                                                                                                                               |
| `plain-round-slow`            | `plain-round` plus a short `sleep-seconds` before exit, so the round stays in flight long enough for a heartbeat test to beat the held claim mid-round (add-claim-heartbeat task 6.1)                                                                                                                                                                                                             |
| `decision-needed`             | agent writes a well-formed decision file and exits (D1)                                                                                                                                                                                                                                                                                                                                           |
| `decision-garbage`            | agent writes an unparseable decision file (tolerant-read fixture)                                                                                                                                                                                                                                                                                                                                 |
| `subagent-round`              | a `Task` tool call with nested events carrying `parent_tool_use_id`, for top-level trace filtering (FR6)                                                                                                                                                                                                                                                                                          |
| `judge-verdict-pass`          | judge's final message is a clean `{"passed": true, ...}` JSON object                                                                                                                                                                                                                                                                                                                              |
| `judge-verdict-fail-fenced`   | judge's final message wraps a Fail verdict in a markdown code fence                                                                                                                                                                                                                                                                                                                               |
| `judge-verdict-garbage`       | judge's final message carries no parseable verdict (→ `CannotVerify`)                                                                                                                                                                                                                                                                                                                             |
| `garbage-output`              | syntactically broken / unknown stream-json lines mixed with valid ones (tolerant parsing)                                                                                                                                                                                                                                                                                                         |
| `missing-result-event`        | the stream ends without ever emitting a `result` event (infrastructure failure per FR4)                                                                                                                                                                                                                                                                                                           |
| `premature-death`             | process exits non-zero after an orphaned `tool_use` with no matching `tool_result` and no `result` event                                                                                                                                                                                                                                                                                          |
| `hangs-forever`               | process sleeps well past any test's `roundTimeout` budget before exiting, standing in for a hung CLI (task 6.5)                                                                                                                                                                                                                                                                                   |
| `decision-then-plain`         | multi-attempt: attempt 1 asks the same decision as `decision-needed`, attempt 2+ (same cwd) plays `plain-round` — the agent-raised decision round-trip (task 9.5, FR3, UX3, D1)                                                                                                                                                                                                                   |
| `megabyte-round`              | `plain-round` preceded by ~1.2 MB of generated stream-json noise — a stream far past any OS pipe buffer whose result event must still survive (fix-round-stdout-drain M1)                                                                                                                                                                                                                         |
| `judge-verdict-pass-megabyte` | `judge-verdict-pass` preceded by the same ~1.2 MB of noise — the judge half of M1                                                                                                                                                                                                                                                                                                                 |
| `chatty-sync-writer`          | `plain-round` preceded by ~100 KB of generated noise: a synchronous writer past the ~64 KB pipe buffer, which under the old post-exit read blocked until the `roundTimeout` kill (fix-round-stdout-drain M2)                                                                                                                                                                                      |
| `law-tamper-then-plain`       | multi-attempt: attempt 1 rewrites its own `instructions.md` control file in the workspace (a `TAMPERED LAW` `workspace-files/` overwrite) and produces no output, so a `files_exist` check fails and burns the attempt; attempt 2+ (same cwd) plays `plain-round` — the reward-hacking stand-in proving a mid-run law edit never re-enters the frozen law (FR19, NFR-S2, D14 of add-sandbox-core) |

Every scenario's `stdout.jsonl` is handwritten to resemble the real Claude
Code CLI's `--output-format stream-json --verbose` protocol (init event,
`assistant`/`user` events with `tool_use`/`tool_result` content blocks, a
final `result` event with `usage`/`modelUsage`) — see design D3 of
add-agent-executor. These are illustrative fixtures for exercising the fake
and harness mechanics, not the byte-real reference dumps that task 3.6 will
record from a live CLI run.

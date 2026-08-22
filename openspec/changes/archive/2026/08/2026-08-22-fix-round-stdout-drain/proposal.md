# Proposal: fix-round-stdout-drain

## Why

Both CLI round executions (gnome and judge) read the agent process's stdout only **after** `waitForExitOrTimeout` returns. The OS pipe holds ~64 KB (macOS/Linux); a round whose stream-json output exceeds that either hangs the child on a full pipe until the `roundTimeout` kill (synchronous writer), or — with Node-based CLIs like `claude`, whose `process.exit()` discards pending async stdout writes — silently loses the tail of the stream. The `result` event is always last, so it is precisely the event that is lost: the round dies with `MissingResultEventException` even though the agent finished its work successfully. This is a documented `java.lang.Process` contract violation ("failure to promptly read the output stream of the process may cause the process to block, or even deadlock") and was reproduced empirically: a 1 MB stream yields exactly 65 528 bytes read and no result event. Any real task with verbose tool traffic can cross 64 KB, so every long round is at risk of a false infrastructure failure that burns wall-clock time and escalates avoidably.

## What Changes

- **MODIFIED**: stream-json stdout is drained continuously from process launch on a dedicated reader thread, in both `ExecutorRoundExecution` and `JudgeRoundExecution`; the round wait covers both process exit and reader completion (with a bounded tail-drain grace after exit). Rounds whose output exceeds any pipe buffer size complete normally.
- **MODIFIED**: progress events (`tool started`, ...) are emitted to listeners as lines are read — near-real-time during the round — instead of in one burst after process exit.
- **MODIFIED**: `MissingResultEventException` diagnostics carry the number of bytes/events read, and when the read volume sits at a pipe-buffer boundary the message names truncation as the likely cause.
- No `ExecHandle` port change: `output()` already exposes the stream from launch; only the adapter's read timing changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `agent-executor`: the stream-reading contract changes — stdout SHALL be consumed concurrently with the running process (removing the output-size ceiling and the full-pipe hang), progress SHALL be live, and missing-result diagnostics SHALL report read volume with a truncation hint.

## Goals

- G1: A round whose stream-json output exceeds 1 MB completes with its result event intact (today it fails).
- G2: A synchronous writer that produces more than the pipe buffer no longer hangs until `roundTimeout`.
- G3: An operator watching the progress log sees tool-started events while the round is running, not as a single post-exit burst.

## Non-Goals

- NG1: No change to the stream-json wire protocol, event model, or `AgentRoundResultExtractor` result semantics.
- NG2: No change to the `ExecHandle` / `TaskExecutionEnvironment` ports or the sandbox adapters — the fix is confined to the agent adapter's read strategy.
- NG3: No fix on the child side (the `claude` CLI's exit behavior is not ours to change).
- NG4: No unbounded raw-output buffering: events keep being parsed on the fly and discarded when unknown, exactly as today.

## Users & Scenarios

- U1: A gnome runs a long implementation stage with heavy tool traffic; its stream-json exceeds 64 KB. The round must complete and report its result, usage, and trace.
- U2: A judge round on a large diff produces a verbose stream; the vote must be graded, not degraded to `CannotVerify`.
- U3: An operator tails the factory log during a round and sees tool activity as it happens.
- U4: A developer diagnosing a genuinely result-less stream (broken fake agent) gets an exception message that distinguishes "the CLI never emitted a result" from "the stream was cut short".

## Requirements

### Functional

- FR1: Both CLI round executions SHALL start reading and parsing the process stdout immediately after launch, on a dedicated reader (virtual thread), accumulating parsed events in a thread-safe collection.
- FR2: Round completion SHALL wait for both process exit (within `roundTimeout`, as today) and reader completion, with a bounded tail-drain grace period after exit; a reader still blocked after the grace SHALL be treated as the round's infrastructure failure, never as a silent partial stream.
- FR7: The tail-drain grace SHALL be configurable as an installation-level application property (never a manifest setting), defaulting to 5 seconds; a non-positive or malformed value SHALL be a startup error.
- FR3: A `roundTimeout` kill SHALL terminate the reader cleanly (stream close / EOF is the expected signal, not an error), preserving today's timeout classification (infrastructure failure, gnome: `RoundTimeoutException`; judge: `CannotVerify`).
- FR4: Progress listener events SHALL be emitted as each line is parsed, from the reader thread; listener exceptions remain swallowed as today.
- FR5: `MissingResultEventException` SHALL report how much was read (bytes and event count) and SHALL include a truncation hint when the read volume is consistent with a filled pipe buffer.
- FR6: Both executions SHALL share the drain mechanism — one implementation, not two parallel copies.

### Non-Functional Reliability

- NFR-R1: The reader thread SHALL never outlive the round: every exit path (normal, timeout kill, exception) joins or interrupts it, leaking no thread and no open stream.
- NFR-R2: Existing failure classification is preserved: a stream that genuinely carries no result event remains an infrastructure failure with no stage attempt burned.

### Non-Functional Observability

- NFR-O1: Timestamped events SHALL carry read-time timestamps that now reflect actual arrival time, keeping the adapter-side tool-timing contract (trace durations become more accurate, not less).

### Non-Functional Performance

- NFR-P1: The drain SHALL impose no per-round overhead beyond one virtual thread and the same single-pass parse performed today.

## Operator Experience Criteria

- UX1: Progress log lines for tool activity appear during the round with realistic spacing, not clustered in one millisecond after exit.
- UX2: When a result event is missing, the log/escalation message lets a human tell "truncated stream" apart from "agent emitted no result" without reading adapter source code.
- UX3: The tail-drain grace property is documented in the operator guide alongside the other installation-level `factory.*` properties, with its default and meaning.

## Success Metrics

- M1: A contract/integration test with a fake CLI emitting > 1 MB of stream-json followed by a valid result event passes for both the executor and the judge path (it fails against the current code).
- M2: A fake synchronous writer producing > 64 KB completes within seconds instead of burning the full `roundTimeout`.
- M3: All existing agent-adapter specs, contract suites, and the module's PIT gate stay green.

## Open Questions

- Q1: Exact tail-drain grace after process exit — resolved: an installation-level property with a 5-second default (FR7, design D2).

## Impact

- Modules: `adapters/agent` (`ExecutorRoundExecution`, `JudgeRoundExecution`, `StreamJsonParser` call site, `MissingResultEventException`, plus a shared drain component and its specs); `application` (`FactoryProperties` gains the tail-drain-grace property; `AgentActivityEnricher` reviewed for cross-thread visibility per D4).
- Ordering: overlaps with the active `fix-denial-attribution-durability` on `ExecutorRoundExecution`'s failure path (its `ExecutorFailure` wrap vs this change's drain interrupt-and-join in the shared catch block). Whichever change lands second must preserve both behaviors; coordinate before implementing in parallel.
- Documentation: the installation-properties table in `docs/guides/operator-guide-run.md` gains the new property row.
- Ports: none changed; `sandbox/core` `ExecHandle` is consumed as-is.
- Dependencies: none added (virtual threads are core Java 25).
- Tests: new large-output fake-CLI scenarios in the contract suites; listener thread-safety expectations documented and covered.

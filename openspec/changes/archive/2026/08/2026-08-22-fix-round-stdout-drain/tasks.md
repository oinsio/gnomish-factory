## 1. Shared stream drain component

- [x] 1.1 Write a failing Spock spec for `StreamDrain` (adapters/agent): starts reading at construction on a virtual thread, parses lines via the existing `StreamJsonParser`, exposes the event list after `await(grace)`; covers normal EOF, tail delivered after exit, grace expiry → infrastructure failure, and interrupt/close on every exit path (FR1, FR2, NFR-R1)
- [x] 1.2 Implement `StreamDrain` with byte counting (D1, D5): thread-safe event accumulation, `await(grace)` join taking the tail grace as a parameter, kill-induced `IOException`/EOF treated as normal end-of-stream while a live-process `IOException` propagates (D3); reuse the existing single-pass `StreamJsonParser` with its read-time timestamps, so arrival-time accuracy and the no-extra-overhead bound hold (NFR-O1, NFR-P1)
- [x] 1.3 Write failing specs + implement the `factory.agent-cli-tail-drain-grace` property on `FactoryProperties` (Duration, default 5 s): bound next to `agent-cli-binary`, threaded into both round executions; non-positive or malformed value is a startup error before any dialog (FR7, D2)
- [x] 1.4 Spec + implement the listener threading contract: callbacks fire per line from the drain thread, exceptions swallowed; MDC values captured at round start are applied inside the drain thread (D4); review `CompositeAgentProgressListener`, the SLF4J renderer, and the status enricher for cross-thread visibility

## 2. Wire into both round executions

- [x] 2.1 Write failing specs: an `ExecutorRoundExecution` round and a `JudgeRoundExecution` round whose fake stream exceeds the pipe buffer complete with the result event intact; progress events are observed before process exit (FR1, FR4)
- [x] 2.2 Rework `ExecutorRoundExecution`: start `StreamDrain` right after `launch`, keep `waitForExitOrTimeout` semantics, replace post-exit `parseStdout` with `drain.await`; preserve `RoundTimeoutException`, denial draining, and round-close order; update the stale "reading before waitForExitOrTimeout" comment (FR2, FR3, NFR-R2)
- [x] 2.3 Rework `JudgeRoundExecution` identically via the shared component; timeout still yields `CannotVerify` before events are consulted (FR3, FR6)

## 3. Truncation-aware diagnostics

- [x] 3.1 Write failing specs: `MissingResultEventException` message carries bytes-read and event-count; a result-less stream whose byte count sits at a 64 KiB boundary adds the probable-truncation hint (FR5, UX2)
- [x] 3.2 Extend `MissingResultEventException` and its raise site in `AgentRoundResultExtractor` (or the callers) to pass the drain's byte/event accounting through (D5)

## 4. Contract-level large-output coverage

- [x] 4.1 Extend the fake agent's scenario contract with a generator step (a noise stream-json line plus a repeat count in the scenario directory — not a checked-in megabyte fixture) emitting > 1 MB of noise events followed by a valid result event; drive both the executor and the judge through it in a standalone integration spec over the real `ProcessBuilder`/pipes path (M1) and add a synchronous-writer > 64 KB scenario asserting completion well under `roundTimeout` (M2)
- [x] 4.2 Verify the timeout contract scenarios still pass with the drain in place (killed process mid-stream → infrastructure, no attempt burned)

## 5. Documentation

- [x] 5.1 Add `factory.agent-cli-tail-drain-grace` (default `5s`, meaning: how long a round waits after process exit for the stdout drain to deliver the piped tail) to the installation-properties table in `docs/guides/operator-guide-run.md` (UX3)

## 6. Verification and traceability

- [x] 6.1 Run `:adapters:agent:check` — all specs, JaCoCo, and the module PIT gate green; add `@DoNotMutate` only for genuine join/interrupt timing races with the written rationale (per testing.md), no other exemptions (M3)
- [x] 6.2 Grep-verify every FR/NFR/UX of fix-round-stdout-drain has an implementing spec or code reference; run `openspec validate fix-round-stdout-drain`
- [x] 6.3 Run the full root `check` and recommend a commit message referencing fix-round-stdout-drain

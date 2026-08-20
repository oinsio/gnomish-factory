# Design: fix-denial-report-attachment

## Context

See proposal.md — Why. The mechanics exist (`EgressGuard.denialFindings()`,
`GuardDenialLog`) but are unreachable from the report path: the round and
check boundaries hold the `TaskExecutionEnvironment` port type, which has no
denial accessor, and `AttemptRecord` findings enter the report only through a
check `Verdict.Fail`. The existing pattern for round-scoped data reaching the
attempt record is `ExecutionResult`: usage and trace already ride it from the
adapter to the engine (`ExecutorRoundExecution` → `RoundExecution` →
`AttemptRecord`). One non-obvious constraint found in code: the guard's log
read is a cumulative container-log tail — the guard container lives across
rounds and attempts of a task's lease, so a naive re-read at every round close
would re-attach earlier rounds' denials to later attempts.

## Goals / Non-Goals

**Goals:** thread egress denials from the round's execution environment onto
that round's `AttemptRecord` and out through both report mappers, changing no
verdict (FR1–FR4, NFR-O1).

**Non-Goals:** denials of verification/judge environments (fresh boxes used
by checks after the round) — the attachment point built here is per-attempt;
`add-sandbox-hardening`'s findings funnel can extend coverage. Tracker
rendering (proposal NG1).

## Decisions

**D1 — Denials ride `ExecutionResult`, the same channel as usage and trace.**
`ExecutorRoundExecution` reads `round.environment().denialFindings()` right
after `closeRound()` and puts the list on `ExecutionResult`
(`Completed` and `DecisionNeeded` both — a decision round had a live round
too); `RoundExecution` threads it onto the `AttemptRecord` it builds.
*Rationale:* this is the established adapter→engine path for round-scoped
metrics, and the exact twin of the NFR-O2 fix (`PollStatus.Pass` gained a
field). *Alternative rejected:* downcasting to `SelfCheckedEnvironment` at
the round boundary — breaks the port contract and leaves host rounds
unrepresentable; a side-channel via the progress listener — denials are
round-close data, not progress events.

**D1a — A round that dies before its close drains its denials, attaching
them to nothing.** A `roundTimeout` kill or a missing result event throws
before `closeRound()`, and the engine shapes the throw into
`RoundOutcome.CannotExecute` — no `AttemptRecord` is built, so there is no
slot to attach to. `ExecutorRoundExecution` still reads the denials on that
failure path and logs them at WARN. *Rationale:* the read is what advances
D3's cursor, and an in-process escalation resume re-enters the engine with
the same lease and environment (`RunnerOutcomeLoop`), so an undrained failed
round would hand its denials to the *next* round's attempt record — the hung
round's blocked exfiltration reported as a later attempt's. The read is
wrapped so a throw from it cannot mask the infrastructure failure that
brought the round down (NFR-R1). *Alternative rejected:* carrying the
denials into `EscalationReport.CannotExecute` — the escalation model has no
findings slot, and adding one is a domain + both-mapper change beyond FR2 /
FR3, which speak of the attempt record; the operator log keeps the signal
until a change scopes that slot.

**D2 — Port accessor, guard-less adapters return empty.**
`TaskExecutionEnvironment` gains `List<Finding> denialFindings()`;
`SelfCheckedEnvironment` delegates to its guard, the host adapter (and any
guard-less environment) returns `List.of()` via a default method. The
concrete-only public `guard()` accessor is dropped (kept package-internal if
construction needs it); dropping it also turns `SelfCheckedEnvironment` from a
`record` into a `final class`, which removes its generated `delegate()` /
`selfCheck()` accessors and its value `equals`/`hashCode` — none of them a
contract surface, all three unused outside the class's own construction.
*Rationale:* consumers must reach denials through the
contract (FR1); a default method keeps every existing adapter and test fake
compiling. *Alternative rejected:* a separate optional capability interface
(`instanceof` probing) — heavier than the need; empty-list is a truthful
answer for a guard-less environment.

**D3 — Per-round delta via a docker-daemon `--since` cursor.**
The guard read gains a cursor: each `denialFindings()` call reads the log
from the previous read's daemon-side timestamp (`docker logs --since`,
first read = container start) and advances the cursor, so a round attaches
only its own denials. *Rationale:* the guard container outlives rounds; the
report must not repeat attempt 1's denials on attempt 2 (breaks UX2 and the
state/live report equivalence). Daemon timestamps are immune to in-box clock
skew. *Alternative rejected:* a count-based high-water mark over the parsed
findings — the read is a sliding log tail, so counting breaks silently once
the window slides; re-attaching the full cumulative list — duplicate noise
and a growing report.

**D3a — The cursor is durable, and matched to its source before use.**
D3's cursor lived only in the guard wrapper, which is rebuilt per lease while
the guard container is not: a resume onto a kept environment found `since ==
null` and re-read the container's whole surviving tail — up to the read's
1000-line cap — attaching rounds that already committed their own denial
lists to the resumed round. The cursor therefore travels in `state.json`,
committed by the same state commit that carries the round's denials, and is
offered back to the environment before the first read of the resumed lease
(FR5). *Rationale:* the position and the denials it delimits are written
atomically, so the two can never disagree; the port carries the cursor
(`denialCursor()` / `restoreDenialCursor()`), so the domain's `TaskState` —
which describes the task, not the box — stays untouched, and the field is
read off the wire DTO by the resume path.

The cursor is a `(source, position)` pair, and a restored one is an offer the
environment may refuse: the position is a daemon timestamp meaningful only
inside the container it was read from, so the guard applies it only when the
live container's runtime id equals `source`. A resume on another machine, or
onto a recreated container, faces a different log and a different daemon
clock — there the position is dropped and the new log is read from its start,
which is correct because that log holds no round the factory already
reported. *Rationale:* the failure mode of applying a foreign position is a
silent one — real denials filtered out of the report — and NFR-O1 makes
silence the worst outcome. *Alternative rejected:* a bare timestamp with no
source — cheaper, but exactly the silent-filtering case on a machine whose
clock runs behind; priming the cursor to the log's end at attach time — no
schema change, but it drops the denials of the round that was in flight when
the factory died, and offers nothing to a reviewer asking what happened.

**D4 — `AttemptRecord` gains `denials`, invisible to verdict logic.**
A `List<Finding> denials` component, defensively copied, possibly empty.
`overallVerdict` and the prior-failure feedback derive from `checkResults`
only — untouched; specs pin that denials never leak into either (FR2).
*Alternative rejected:* folding denials into a synthetic `CheckResult` —
changes the stage outcome (PASS → QUALITY_FAILURE), which NFR-O1 explicitly
must not.

**D5 — Additive v1 field in both mappers.**
`StateJsonMapper` and `StatusReportJsonMapper` serialize `denials` per
attempt using the existing finding DTO shape; readers default an absent field
to empty (existing documents stay readable, no version bump). Only
`state.json` is ever read back, so only `StateAttemptDto` normalizes
absent-to-empty; `status.json` is a write-only projection and its `AttemptDto`
carries no reader default — a mirror constructor there would guard a parse that
does not exist. The day `status.json` gains a reader, it gains that default
with it. `status-report-v1.reference.json` and the `StateJsonMapperSpec` round-trip
documents gain the field; the text renderer lists an attempt's denials
alongside its findings (UX1). *Rationale:* contract v1 already requires
readers to ignore unknown fields, so additive-with-default is the established
evolution path. *Alternative rejected:* version bump to v2 — refuses resume
of every existing task branch for a purely additive field.

## Risks / Trade-offs

- [Guard log tail is capped (200 events, bounded tail lines) — a denial storm
  can push older events out before round close, and D3's cursor then advances
  past what the tail dropped] → accepted; both caps warn rather than truncate
  silently. `GuardDenialLog` warns past 200 parsed events, and a read that
  comes back filling its `--tail` window warns too — a saturated window is the
  only signal that the daemon dropped lines the cursor is about to skip, so
  without it a lossy read is indistinguishable from a quiet round.
- [The `--since` cursor is committed in `state.json` (D3a), so what a crash
  can lose is only what happened between the last state commit and the crash]
  → the resumed round may re-attach the denials of the round that was in
  flight when the factory died, since that round never committed a position
  of its own. That window is one round, not the container's whole surviving
  log — the pre-D3a behavior, where a lease-scoped cursor started at null and
  the first read after every resume replayed up to the read's 1000-line tail
  cap onto the current round, misreporting *when* those denials happened and
  duplicating findings already recorded in earlier attempts.
- [A restored cursor whose source no longer exists is dropped, and the log
  read from its start] → correct wherever the source is genuinely new (a
  resume on another machine, a recreated guard container): such a log holds
  no round the factory already reported. The cost is that a cursor cannot be
  carried across machines, which is not a loss — neither can the log it
  describes.
- [New mapper/record code must clear the PIT 100% gate] → the added logic is
  plain value plumbing with asserted JSON output; the equivalence and
  reference-JSON specs kill mapper mutants.

## Migration Plan

None needed: the field is additive under contract v1; existing state files
and status documents parse unchanged with denials read as empty.

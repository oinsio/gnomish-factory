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

**D2 — Port accessor, guard-less adapters return empty.**
`TaskExecutionEnvironment` gains `List<Finding> denialFindings()`;
`SelfCheckedEnvironment` delegates to its guard, the host adapter (and any
guard-less environment) returns `List.of()` via a default method. The
concrete-only public `guard()` accessor is dropped (kept package-internal if
construction needs it). *Rationale:* consumers must reach denials through the
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
to empty (existing documents stay readable, no version bump);
`status-report-v1.reference.json` and the `StateJsonMapperSpec` round-trip
documents gain the field; the text renderer lists an attempt's denials
alongside its findings (UX1). *Rationale:* contract v1 already requires
readers to ignore unknown fields, so additive-with-default is the established
evolution path. *Alternative rejected:* version bump to v2 — refuses resume
of every existing task branch for a purely additive field.

## Risks / Trade-offs

- [Guard log tail is capped (200 events, bounded tail lines) — a denial storm
  can push older events out before round close] → accepted and already true
  of today's capture; the cap is the guard's documented truncation-with-
  warning behavior, and a storm's presence is still visible.
- [`--since` cursor state lives on the guard wrapper (lease-scoped, in
  memory) — a factory crash between round close and commit loses the cursor]
  → after resume the next round may re-attach a few pre-crash denials;
  duplicates-on-crash is benign observability noise, and every committed
  attempt keeps its own list.
- [New mapper/record code must clear the PIT 100% gate] → the added logic is
  plain value plumbing with asserted JSON output; the equivalence and
  reference-JSON specs kill mapper mutants.

## Migration Plan

None needed: the field is additive under contract v1; existing state files
and status documents parse unchanged with denials read as empty.

# Proposal: cap-abort-cause-length

## Why

The abort `cause` that `AbortHandler` hands to the tracker is unbounded, and its dominant
producer is a full rendered stack trace (`AttemptJournal` renders the persist failure via
`StackTraces.render`, chain and suppressed included). That text flows verbatim into the
GitHub abort comment body (`"🤖 gnomish: aborted: " + cause`) and into the fuse-trip
`park(INFRA)` report. Tracker comment bodies have hard limits — GitHub 65,536 characters,
Jira Cloud 32,767 — and a body over the limit is rejected (422). Because both abort writes
are best-effort, the rejection is silently swallowed: the abort marker never lands, the
task's consecutive-abort count under-counts, and a task that should have tripped its K fuse
keeps looping past it (FR14 of add-tracker-port). A cause that today happens to fit is one
deep exception chain away from breaking the fuse accounting.

## What Changes

- One choke-point truncation applied in `AbortHandler.handle` before the cause reaches
  either tracker write: the `AbortRecord` for `recordAbort` and the report text
  `AbortReportBuilder` builds for `park(INFRA)`.
- The cap is a named constant sized from the smallest supported tracker comment budget
  (Jira Cloud's 32,767 characters), with headroom reserved for the report's own framing —
  not a configurable property: it guards a hard API limit, not a matter of taste.
- Truncation keeps head and tail with an explicit `[N characters omitted]` marker between
  them: for a stack-trace cause the head holds the throw site, the tail holds the
  `Caused by` root — the two parts an operator actually reads (industry precedent: CI log
  truncation converged on head+tail after tail-only and head-only each lost the half that
  mattered).
- The ERROR log in `AbortHandler` and the branch state file keep the full, uncapped text:
  the limit is the tracker's, and the full trace is the diagnostic record (NFR-O1 of
  add-stage-engine stays intact).

## Goals

- G1: An abort's tracker writes (`recordAbort` marker, fuse-trip `park(INFRA)` report)
  succeed regardless of the cause text's size — the K-fuse accounting never loses an abort
  to a body-too-long rejection.
- G2: An operator reading a capped cause in the tracker still sees the throw site and the
  root cause, and can tell that (and how much) text was omitted.

## Non-Goals

- NG1: Capping other tracker-bound report bodies (escalation, checkpoint park, finish,
  decision acks) — none has an unbounded producer today; widening the guard is a separate
  change if one appears.
- NG2: Splitting oversized bodies into multiple tracker comments — an abort marker is one
  structural record; two comments would break the marker-per-tenure accounting.
- NG3: Making the cap configurable — it guards a hard API limit, not a preference.
- NG4: Changing what the ERROR log or the task branch's state records carry — the full
  text stays the diagnostic record (NFR-O1 of add-stage-engine is untouched).

## Users & Scenarios

- U1: An operator diagnosing a parked task reads the fuse-trip report in the tracker: the
  cause shows the top of the trace and the `Caused by` root, with an explicit
  omitted-characters marker between them.
- U2: A factory fleet relies on the shared consecutive-abort count: every abort's marker
  lands, so a task that keeps dying trips its fuse at K, not never.

## Requirements

### Functional

- FR1: Any abort-cause text handed to a tracker write is first capped to the abort-cause
  budget — a fixed limit sized so the resulting comment body fits every supported
  tracker (smallest known limit: Jira Cloud, 32,767 characters) with headroom for the
  report's framing. Over-budget text is truncated head+tail with an explicit marker
  naming the omitted character count; within-budget text passes through byte-for-byte.

### Non-Functional Reliability

- NFR-R1: The capped write path guarantees the abort marker and the park report are
  never rejected for body size, so the K-fuse count stays honest for arbitrarily large
  causes.

### Non-Functional Observability

- NFR-O1: Truncation is always visible (the marker), and never lossy where it matters:
  the head keeps the throw site, the tail keeps the rendered chain's root cause; the
  ERROR log always carries the full uncapped text.

## Operator Experience Criteria

- UX1: A capped cause in a tracker comment reads as one coherent text — head, one
  marker line, tail — not as garbage cut mid-word at an arbitrary point.

## Success Metrics

- M1: A cause of arbitrary size (spec-driven: well past 65,536 characters) produces
  tracker bodies under 32,767 characters in both write paths, asserted by spec.
- M2: Mutation score for the new truncation code is 100% (project gate).

## Open Questions

- Q1: Should a future port-level "body budget" generalize this guard to all tracker-bound
  report text? Deferred — see design.md Open Questions; answering it later changes
  nothing in this change's specs or tasks.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `tracker-take`: the abort protocol requirement gains a bound — any cause text handed to
  a tracker write (`recordAbort` marker, `park(INFRA)` report) is capped to the abort-cause
  budget via head+tail truncation with an explicit omission marker; the ERROR log keeps the
  full text.

## Impact

- `application/.../app/take/AbortHandler.java` — applies the cap before both tracker
  writes (single choke point; covers all three cause producers: `AttemptJournal`'s rendered
  trace, `TakeCrashAbort`'s crash string, both engine-execution modes via the shared
  `TakeOutcomeDispatch`).
- One new small class in `application/.../app/take/` holding the constant and the
  head+tail truncation (file-size rule: `AbortHandler` stays under the line target).
- `AbortReportBuilder` — unchanged interface; receives the already-capped cause.
- No port or adapter changes: `Tracker.recordAbort`/`park` signatures and the GitHub
  adapter are untouched.
- No new manual-sync pair: the cap sits in shared code below the declared
  `TakeEngineExecution`/`TakeContainerEngineExecution` pair (see design.md).

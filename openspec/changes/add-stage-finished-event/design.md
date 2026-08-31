# Design: add-stage-finished-event

## Context

See proposal.md — Why. Driven by FR1–FR5, NFR-R1/R2, NFR-P1, NFR-S1, UX1–UX3.

Current state, verified against the code:

- `EngineEvent` (`domain/.../engine/EngineEvent.java`) is sealed with seven variants; a
  stage boundary is inferable only from an `AttemptFinished` whose `newState.position()`
  advanced.
- `StageAttemptLoop.newState` records a pass via `recordPassAndAdvance` — the pass and its
  advancement are already one persisted commit (FR4 of harden-task-branch-contract) — and
  `AttemptJournal.commit` owns the persist → `AttemptFinished` ordering invariant.
  `Engine.runStages` receives `StageResult.Passed` and applies advancement in memory only.
- `Events.emit` swallows-and-logs listener exceptions; `CompositeEngineEventListener`
  fans out; `RunAssembler` (bootstrap) builds the listener list — so failure isolation for a
  notifier is structural (the Tekton/Airflow lesson from the proposal), not something this
  change has to build.
- `EgressAllowlist` (adapters `check/http`, package-private) implements the
  factory-egress-allowlist rules for the http check.
- `FactoryProperties` is the operator config record; `Engine.run` normalizes a resumed state
  with `startOfStage()`, which is what makes "no replay on resume" (FR2) fall out naturally.

## Goals / Non-Goals

Design-level only (proposal owns scope): pick the event name, fix the emission point against
the ordering invariant, place the notifier and its egress guard in the module graph, and
settle the sync-surface question. Non-goal: any notifier beyond one webhook adapter.

## Decisions

**D1 — Name the variant `StagePassed`, not `StageFinished`.** *Rationale:* the event fires
only when verify passes and advancement applies; the existing `*Finished` family
(`ExecutionFinished`, `CheckFinished`, `AttemptFinished`, `TaskFinished`) means "this unit
ended regardless of outcome" — an `AttemptFinished` fires for failed rounds too. A stage that
escalates also "finishes", and that is already `TaskFinished`'s job. `StagePassed` aligns
with the existing domain vocabulary `Verdict.Pass` and `StageResult.Passed`.
*Alternative rejected:* `StageFinished` — family-symmetric but semantically false: it would
imply emission on escalation/exhaustion, which never happens.

**D2 — Emit in `Engine.runStages`, after `StageResult.Passed` returns, before the
advancement switch's outcome.** *Rationale:* at that point the passing round is already
durably persisted (the loop commits before routing) and its `AttemptFinished` already
emitted by `AttemptJournal.commit`, so the required order persist → `AttemptFinished` →
`StagePassed` (FR1) holds with zero changes to `AttemptJournal`'s invariant (FR11 of
add-stage-engine). One emission site covers all three exits — AUTO advance, MANUAL pause,
final-stage completion (FR2) — by computing the advanced-to position from
`Advancement.nextStage`/`nextPosition`, which `runStages` already resolves. Payload:
`record StagePassed(String taskId, String stage, Position advancedTo)` — no `AttemptKey`
(the boundary is per stage, not per round; precedent: the two bookends carry `taskId`), no
`TaskState` (consumers wanting state already got it on `AttemptFinished`).
*Alternative rejected:* emitting inside `StageAttemptLoop`/`AttemptJournal` next to the
pass commit — it would need the advancement outcome (next stage vs pause vs end) threaded
into the loop, widening the loop's contract for what is purely run-level orchestration
knowledge, and would tempt reordering against the journal's invariant.

**D3 — Notifier = one `EngineEventListener` implementation in `application`, wired
conditionally in `RunAssembler`; no new port.** *Rationale:* reuse-check per the proposal:
`EngineEventListener` already IS the seam — synchronous, non-throwing-by-contract,
swallow-and-log guarded, composite-fanned. A parallel `StageNotifier` port would duplicate
the listener contract for zero new behavior. The listener filters `StagePassed` and
`TaskFinished` (FR4) and hands each payload to a fire-and-forget virtual thread running the
HTTP POST under the configured timeout, satisfying "return promptly" (NFR-P1); WARN + swallow
on any failure (NFR-R1). The HTTP door it posts through is a small interface so the unit
specs fake it and the WireMock suite drives the real one.
*Alternative rejected:* a dedicated `StageNotifier` port with an engine-side call site —
adds a second delivery path the engine must order and guard, recreating exactly what
`Events.emit` + composite already provide.

**D4 — Sync surfaces (mandatory decision): none touched; serve-observability is
deliberately NOT fed in this change.** The eighth sealed variant is propagated by the
compiler: every exhaustive no-`default` switch (`MdcEventListener`, `LoggingEventListener`,
`StatusEventListener`, `HeartbeatProgress`) fails to compile until it gains an arm — that is
compiler-enforced, not a manual sync pair. Checked against the `manual-sync-pairs.md`
registry: `StagePassed` does not enter snapshot or ledger JSON (NG4), so the
`SnapshotJsonMapper`/`SnapshotJsonReader` and `LedgerJsonMapper`/aggregator wire-token pairs
are untouched and no wire-vocabulary round-trip spec is owed; the notifier's own JSON payload
is write-only (no factory-side reader exists), so it creates no new pair either. If a later
change surfaces stage boundaries on the dashboard (Q2), that change owns the wire tokens and
their round-trip spec. *Alternative rejected:* adding the event to the snapshot vocabulary
now "while we're here" — it would drag two declared wire pairs plus their round-trip specs
into scope with no consumer.

**D5 — Existing listeners: add minimal arms, defer migration (NG5).**
- `LoggingEventListener`: real arm — one INFO line ("stage passed: X -> position").
- `MdcEventListener`: no-op arm with comment — the attempt events around the boundary
  already maintain the MDC keys, and `StagePassed` carries no attempt.
- `StatusEventListener`: no-op arm — the preceding `AttemptFinished` already delivered the
  advanced `TaskState` to the holder.
- `HeartbeatProgress`: no-op arm — the next stage's `AttemptStarted` supersedes any progress
  line within the same beat interval; migrating it to `StagePassed` buys nothing.
Migration of any position-diffing consumer is noted, not forced: none of the four currently
mis-infers, so forcing a rewrite adds churn without a defect to fix.

**D6 — Config: a typed `factory.notify.webhook` section on `FactoryProperties` (url,
optional timeout), validated at assembly.** *Rationale:* follows the existing
`FactoryPropertyDefaults` pattern; URL parse + https-scheme check happen at
startup/assembly so a typo fails fast naming the property (UX2), never at the first pass
(FR5). Absent section ⇒ notifier not constructed, listener list unchanged (FR3).
*Alternative rejected:* an untyped `Map` subsection like `check`/`connections` — those are
plugin-keyed open sets; the webhook is a closed two-field shape, and a typed record gets
startup validation for free.

**D7 — Egress guard: apply the factory-egress-allowlist rules to the webhook via a
notification-side guard equivalent to the http check's, with the operator's URL as its
implicit allowlist entry.** *Rationale:* the rules (https-only, blocked address classes,
redirect re-check, size/time bounds — NFR-S1) are capability-level policy; the existing
`EgressAllowlist` is package-private in `adapters/.../check/http` and keyed to
`factory.check.http.allowlist`. The notifier reuses the mechanism rather than the class's
config: the guard is seeded with exactly the configured webhook host (declaring the URL is
the operator's consent for that host), while address-class blocking, scheme, redirect
re-check, and bounds stay in force. Implementation direction: widen the reusable pieces of
the check/http guard (address classing, redirect re-check loop) to package-visible shared
code inside the adapters module rather than copying them — a copy would create a brand-new
undeclared manual-sync pair, which D4 just promised not to do; if extraction proves
disproportionate, the fallback is a declared pair with `Kept in sync with` markers at both
ends per `manual-sync-pairs.md` (preference order honored: abstraction first).
*Alternative rejected:* requiring the operator to also list the webhook host under
`factory.check.http.allowlist` — conflates two capabilities' configs and makes the http
check's reachability set silently widen when someone configures notifications.

## Risks / Trade-offs

- [Lost notification in the persist→emit kill window (NFR-R2)] → accepted by design
  (at-most-once, NG3); the WARN/INFO log stream remains the complete record.
- [Fire-and-forget virtual threads outliving the run at shutdown] → each delivery is bounded
  by the configured timeout; JVM exit may drop in-flight deliveries — same best-effort
  contract, noted in the notifier's javadoc.
- [Webhook endpoint slow/leaky as a covert channel] → payload contains only task id,
  boundary, stage/outcome classification — no diffs, no trace content, no secrets; egress
  guard bounds destination, size, and time (D7).
- [`StagePassed` tempts future listeners to treat it as an effect hook] → the listener port's
  "observability, never an effect" javadoc contract is restated on the new variant.
- [PIT on the notifier's HTTP hand-off] → the HTTP door interface keeps decisions unit-
  testable; any genuinely out-of-process line follows the established exemption bars in
  `.claude/rules/testing.md`, with the covering WireMock suite named in place.

## Migration Plan

Purely additive: no persisted format, wire vocabulary, or config key changes meaning.
Deploy is a normal release; rollback is a normal downgrade (the event is process-local, the
config section is ignored by older binaries). No data migration.

## Open Questions

None deferrable beyond the proposal's Q1–Q3, which do not affect this change's specs,
approach, or tasks.

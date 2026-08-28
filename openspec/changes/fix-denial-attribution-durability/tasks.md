# Tasks: fix-denial-attribution-durability

Prerequisite: `fix-denial-report-attachment` is applied and archived (done
2026-08-20) — this change builds on its denial slot, its cursor design
(`state.json` position + container stamp + resume offer; inert in
production until block 1 below), and its finding DTO.
`add-serve-sandbox-lifecycle` is archived (2026-08-22) — its guard-disposal
outcomes are already absorbed in NFR-R2 and D2.

Sequencing: implement after `harden-task-branch-contract` archives — this
change's restore rides its branch-shape classifier and its shared atomic
lifecycle writer, and the `git-task-persistence` MODIFIED block below is
rebased onto that change's merged text at archive time.

## 1. Wiring repair and the mechanical gate (FR6, FR9, G5, M4, M5, D7)

The predecessor's cursor feature is dead in production: `LeasedEnvironment`
forwards none of the port's three denial default methods, and no spec drives
the production wiring. This block revives it and closes the defect class.

- [ ] 1.1 `LeasedEnvironment`: forward `denialFindings()`, `denialCursor()`,
      and `restoreDenialCursor(...)` to the leased delegate; extend
      `LeasedEnvironmentSpec` to assert all three forwards.
- [ ] 1.2 Production-wiring spec (M4): build `EnvironmentAttemptPersistence`
      over a real `LeasedEnvironment` (supplier returning a denial-bearing
      double) and assert the committed `state.json` carries the cursor; drive
      the restore half through the same view. This is the spec whose absence
      let the feature ship dead.
- [ ] 1.3 `ObservedSandboxLifecyclePass`: override the three-arg `run` so the
      caller's extra sink joins the fanout instead of being dropped by the
      inherited default; spec the three-arg path.
- [ ] 1.4 Architecture rule (M5): `DelegatingDecoratorCompletenessSpec` in the
      bootstrap architecture package — a production class implementing
      interface `I` and holding a same-type delegate (fields, constructor
      params, record components, `Supplier<I>`) must override every default
      method of `I`; named allowlist for justified exemptions (currently
      `EpochRecordingTrackerFactory`'s self-delegating `create/4`); the
      rule's own spec seeds a violation and asserts it fails.

## 2. Failure channel — denials leave a round that throws (FR1, NFR-O1, D1)

- [ ] 2.1 Add `ExecutorFailure` to the engine port package: a
      `RuntimeException` carrying the original failure as its cause and an
      immutable `List<Finding> denials()`; javadoc traces FR1 and states that
      any other `RuntimeException` stays an empty-denial `CannotExecute`.
- [ ] 2.2 Add `List<Finding> denials` to `EscalationReport.CannotExecute`
      (defensively copied, possibly empty); update every construction and
      render site, keeping the escalation text unchanged.
- [ ] 2.3 In `RoundExecution` (engine), map a caught `ExecutorFailure` to
      `RoundOutcome.CannotExecute` — which gains a denials component — with the
      rendered **cause** (not the wrapper); in `StageAttemptLoop`, copy the
      outcome's denials onto `EscalationReport.CannotExecute`; every other
      `RuntimeException` keeps today's behavior with an empty list.
- [ ] 2.4 In `ExecutorRoundExecution` (agent adapter — distinct from the
      engine's `RoundExecution`), wrap the original exception in
      `ExecutorFailure` with the drained denials instead of only logging
      them; the round is still discarded exactly once by the caller.
- [ ] 2.5 Spec the channel end to end at the engine level (FR1): a round whose
      executor throws with denials escalates `CannotExecute` carrying them,
      `attemptsUsed` and the attempt history unchanged, and the escalation text
      identical to the same failure without denials.

## 3. Report surfaces (FR2, NFR-S1, UX1, UX2, D4)

- [ ] 3.1 `TaskJsonMapper`: serialize the escalation's `denials` with the
      finding DTO shape under `lastEscalation`; an absent field reads as empty
      (existing `task.json` documents parse unchanged).
- [ ] 3.2 `StatusReportJsonMapper`: the same field on the `cannotExecute`
      escalation; update `status-report-v1.reference.json`, which needs its
      first `cannotExecute` escalation sample (the current `lastEscalation`
      sample is `decisionNeeded`; the equivalence contract pins the document).
- [ ] 3.3 Text renderer: list the escalation's denials beside the escalation
      reason, through the funnel-fenced finding line; zero denials render
      nothing (UX2).
- [ ] 3.4 Keep the state↔live report equivalence contract green with escalation
      denials present, and extend it with a `cannotExecute` case.
- [ ] 3.5 Spec M1: a round killed on its round timeout with a denial shows that
      denial under the escalation in both documents, while `attemptsUsed` and
      `attempts` are unchanged.

## 4. Durable position for the drained read (FR3, FR4, FR5, NFR-R1, NFR-R2, NFR-R3, NFR-O2, D2, D3, D7)

The attempt-path mechanics (`GuardDenialReads`,
`EnvironmentAttemptPersistence`, `ContainerRunTermination.restoreDenialCursor`)
exist and are revived by block 1. This block extends the
position-with-the-record pattern to the escalation path over the atomic
commit machinery of `harden-task-branch-contract`; it builds no new storage
and does not touch the guard's cursor mechanics. The read stays bounded by
the existing guard log tail cap (NFR-C1 — no new task; D6 makes its
saturation visible).

- [ ] 4.1 One owner for the pair (D7): the environment read hands back
      `(findings, positionAfter)` as one value, and the position becomes
      durable only through the same call that persists the record; fold the
      failure-path drain and the attempt path onto this seam.
- [ ] 4.2 Add the cursor field to `task.json` — same DTO shape as
      `state.json`'s `egressCursor` (opaque position + source identity) —
      additive under contract v1; an absent field reads as "no cursor".
- [ ] 4.3 On the park that records a `CannotExecute` escalation with denials,
      the drained position rides the same lifecycle commit as the escalation,
      written through the shared atomic writer (`TaskLifecycleCommitWriter`) —
      best-effort on the environment read: an unanswerable cursor writes none
      and never fails the park (NFR-R1), so the position can lag the record
      but never lead it (FR3).
- [ ] 4.4 Cursor preservation (FR5): no lifecycle rewrite of `state.json`
      drops a committed cursor — `putTaskAndState` carries the tip's
      `egressCursor` forward (coordinate with `harden-task-branch-contract`:
      whichever change is still open carries the code; this change carries
      the kill-point spec either way).
- [ ] 4.5 Teach the resume restore to read the branch tip through the
      branch-shape classifier of the `task-branch-contract` capability:
      positions are offered only for shapes that carry one, a quarantining
      shape yields none, and the newest source-matching position across
      `state.json` and `task.json` wins (same-source positions are daemon
      timestamps, totally ordered). The environment's existing stamp check
      keeps dropping a position of a recreated or foreign container with a
      log line and a full tail read (FR4, NFR-O2, NFR-R2).
- [ ] 4.6 Spec the restore choice daemon-free against the docker fake: an
      escalation cursor newer than the attempt cursor wins; attempt-only and
      escalation-only tips restore what they have; a mismatched stamp still
      falls back to a full read and logs; a tip with neither cursor reads
      from container start; a RESUMED commit between attempt and restore
      does not lose the cursor (4.4).
- [ ] 4.7 Spec M2 at the environment level: a fresh wrapper standing in for a
      second factory process, resuming after a `CannotExecute` park, reports
      neither the attempts' nor the escalation's already-recorded denials.

## 5. Identity and loss visibility (FR7, FR8, NFR-O3, UX3, G6, D5, D6)

- [ ] 5.1 `GuardDenialLog`: keep the daemon nanosecond timestamp when parsing
      a denial line — each parsed denial pairs the finding with
      `(source id, event timestamp)`; the shared domain `Finding` type is
      untouched.
- [ ] 5.2 Persistence DTOs: denial entries in `state.json` and `task.json`
      gain the identity additively (absent reads as "unknown, keep");
      `status.json` and the text render do not carry it.
- [ ] 5.3 Idempotent attach (FR7): merging denials onto a record dedupes by
      identity against the denials already recorded at the branch tip; the
      FR4 fallback logs "re-read: N already present, M recovered".
- [ ] 5.4 Loss marker (FR8, D6): on `GuardLogCursor` saturation, or a
      committed cursor naming a source that no longer holds its log, emit a
      synthetic funnel-fenced loss finding into the same denials list; spec
      that it reaches both documents and the text render (UX3), and that a
      quiet task emits nothing.
- [ ] 5.5 Spec the merge across processes: a resume whose position is lost
      but whose identities survive re-reads the log and records zero
      duplicates; a resume that lost both reports duplicates plus the NFR-O2
      log line — never silence.

## 6. API compatibility (D1, predecessor precedent)

- [ ] 6.1 `EscalationReport.CannotExecute` is re-exposed by
      `gnomish-plugin-api`: bump the api version 0.3.0 → 0.4.0 (pre-1.0
      breaking = MINOR) and regenerate both `compat-baseline/` jars in this
      change's diff.

## 7. Verification and closure

- [ ] 7.1 Full build green: `./gradlew check` including the PIT 100% gate and
      the new architecture rule.
- [ ] 7.2 Verify FR/NFR/UX traceability coverage per
      `.claude/rules/traceability.md`; confirm the predecessor's design D1a is
      superseded by this change's D1 (its "log only" outcome no longer holds).
- [ ] 7.3 `docs/glossary.md`: update **Denial cursor** (restore now offers the
      newest committed position across both documents; lifecycle rewrites
      preserve it) and add **Denial identity** and **Loss marker** entries.
- [ ] 7.4 `docs/adr/0003-crash-consistency.md`: add the consumed-stream
      principle — a read position becomes durable only with the record it
      delimits, recorded events carry source-assigned identity for idempotent
      re-reads, and known loss is reported in-band — so later transitions
      cite the ADR (provenance: this change).
- [ ] 7.5 Recommend a Conventional Commits message referencing
      fix-denial-attribution-durability.

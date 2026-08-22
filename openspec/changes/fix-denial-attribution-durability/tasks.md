# Tasks: fix-denial-attribution-durability

Prerequisite: `fix-denial-report-attachment` is applied and archived (done
2026-08-20) — this change builds on its denial slot, its durable per-round
cursor (its FR5: `state.json` position + container stamp + resume offer),
and its finding DTO.

Sequencing: implement after the `add-serve-sandbox-lifecycle` branch merges —
both changes touch the bootstrap resume path (`ContainerRunSupport`), and
that branch changes `EgressGuard`/`GuardCommands` signatures next to the
guard's cursor wiring.

## 1. Failure channel — denials leave a round that throws (FR1, NFR-O1, D1)

- [ ] 1.1 Add `ExecutorFailure` to the engine port package: a
      `RuntimeException` carrying the original failure as its cause and an
      immutable `List<Finding> denials()`; javadoc traces FR1 and states that
      any other `RuntimeException` stays an empty-denial `CannotExecute`.
- [ ] 1.2 Add `List<Finding> denials` to `EscalationReport.CannotExecute`
      (defensively copied, possibly empty); update every construction and
      render site, keeping the escalation text unchanged.
- [ ] 1.3 In `RoundExecution`, map a caught `ExecutorFailure` to
      `RoundOutcome.CannotExecute` — which gains a denials component — with the
      rendered **cause** (not the wrapper); in `StageAttemptLoop`, copy the
      outcome's denials onto `EscalationReport.CannotExecute`; every other
      `RuntimeException` keeps today's behavior with an empty list.
- [ ] 1.4 In the agent adapter's failure-path drain, wrap the original
      exception in `ExecutorFailure` with the drained denials instead of only
      logging them; the round is still discarded exactly once by the caller.
- [ ] 1.5 Spec the channel end to end at the engine level (FR1): a round whose
      executor throws with denials escalates `CannotExecute` carrying them,
      `attemptsUsed` and the attempt history unchanged, and the escalation text
      identical to the same failure without denials.

## 2. Report surfaces (FR2, NFR-S1, UX1, UX2, D4)

- [ ] 2.1 `TaskJsonMapper`: serialize the escalation's `denials` with the
      finding DTO shape under `lastEscalation`; an absent field reads as empty
      (existing `task.json` documents parse unchanged).
- [ ] 2.2 `StatusReportJsonMapper`: the same field on the `cannotExecute`
      escalation; update `status-report-v1.reference.json`.
- [ ] 2.3 Text renderer: list the escalation's denials beside the escalation
      reason, through the funnel-fenced finding line; zero denials render
      nothing (UX2).
- [ ] 2.4 Keep the state↔live report equivalence contract green with escalation
      denials present, and extend it with a `cannotExecute` case.
- [ ] 2.5 Spec M1: a round killed on its round timeout with a denial shows that
      denial under the escalation in both documents, while `attemptsUsed` and
      `attempts` are unchanged.

## 3. Durable position for the drained read (FR3, FR4, FR5, NFR-R1, NFR-R2, NFR-O2, D2, D3)

The attempt-path durability already exists (predecessor FR5:
`GuardDenialReads`, `EnvironmentAttemptPersistence`,
`ContainerRunTermination.restoreDenialCursor`). This block extends the same
position-with-the-record pattern to the escalation path; it builds no new
storage and does not touch the guard's cursor mechanics.

- [ ] 3.1 Add the cursor field to `task.json` — same DTO shape as
      `state.json`'s `egressCursor` (opaque position + source identity) —
      additive under contract v1; an absent field reads as "no cursor".
- [ ] 3.2 On the park that records a `CannotExecute` escalation with denials,
      read the environment's `denialCursor()` after the drain and write it in
      the same `task.json` write as the escalation — best-effort: an
      unanswerable cursor writes none and never fails the park (NFR-R1), so
      the position can lag the record but never lead it (FR3).
- [ ] 3.3 Teach the resume restore to offer the newest source-matching
      position across the branch tip's `state.json` and `task.json`
      (same-source positions are daemon timestamps, totally ordered); the
      environment's existing stamp check keeps dropping a position of a
      recreated or foreign container with a log line and a full tail read
      (FR4, NFR-O2, NFR-R2).
- [ ] 3.4 Spec the restore choice daemon-free against the docker fake: an
      escalation cursor newer than the attempt cursor wins; attempt-only and
      escalation-only tips restore what they have; a mismatched stamp still
      falls back to a full read and logs; a tip with neither cursor reads
      from container start.
- [ ] 3.5 Spec M2 at the environment level: a fresh wrapper standing in for a
      second factory process, resuming after a `CannotExecute` park, reports
      neither the attempts' nor the escalation's already-recorded denials.

## 4. API compatibility (D1, predecessor 6.1b precedent)

- [ ] 4.1 `EscalationReport.CannotExecute` is re-exposed by
      `gnomish-plugin-api`: bump the api version (pre-1.0 breaking = MINOR) and
      regenerate `compat-baseline/` in this change's diff.

## 5. Verification and closure

- [ ] 5.1 Full build green: `./gradlew check` including the PIT 100% gate.
- [ ] 5.2 Verify FR/NFR/UX traceability coverage per
      `.claude/rules/traceability.md`; confirm the predecessor's design D1a is
      superseded by this change's D1 (its "log only" outcome no longer holds).
- [ ] 5.3 Update `docs/glossary.md` if this change introduces or shifts a
      domain term (the read position is a candidate).
- [ ] 5.4 Recommend a Conventional Commits message referencing
      fix-denial-attribution-durability.

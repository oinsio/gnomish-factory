# Tasks: harden-task-branch-contract

Sequencing: start after `bound-subprocess-commands` is implemented and archived — D14 and
task 6.1 consume its named invocation outcomes. TDD throughout: each task lands with its
failing-first Spock specs (`.claude/rules/testing.md`).

## 0. Durable documentation and coordination

- [ ] 0.1 Write the crash-consistency ADR in `docs/adr/` (D1, D15): principle, three
      mechanisms, per-shape roll-forward/discard table, rejected alternatives (saga journal,
      WAL, block counters, local fsync), durability point = successful push
- [ ] 0.2 Add the `.claude/rules/` crash-consistency checklist rule for future multi-step
      transitions (D15, G5)
- [ ] 0.3 Add glossary entries: branch shape, recovery owner, claim epoch, intent/receipt
      (D15); link from the ADR
- [ ] 0.4 Add a coordination note to `fix-denial-attribution-durability`'s proposal: its
      resume restore reads through the shape classifier (active change, editable)

## 1. Shape model and classifier

- [ ] 1.1 Domain shape model: sealed hierarchy per D4 with per-shape recovery-owner and
      roll-forward/discard declarations (FR1, FR2)
- [ ] 1.2 Tip-reader seam with three adapters — worktree tip, `git show`, bare objects (D3);
      classifier never reads the dirty worktree for factory files (FR5)
- [ ] 1.3 Classifier: total mapping file-set × envelope version × epoch → shape; content
      never throws (FR1, FR15, NFR-R2); delivered detection searches history for the cleanup
      commit (D4)
- [ ] 1.4 Property-based spec: generated tips always classify to exactly one shape (M2)
- [ ] 1.5 Repair observability: structured log per non-clean classification/repair, warning
      on repeated repair of one task (NFR-O1)

## 2. Writers honor the contract

- [ ] 2.1 Move the atomic file writer to a shared dependency-free leaf module; host and
      container persisters and the dashboard writer use it (D10, FR5)
- [ ] 2.2 STARTED commit carries the initial `state.json` synthesized from the frozen law
      (D2, FR3); pre-contract tip classifies as legal `Created` and resumes stage one
- [ ] 2.3 One transition = one commit: decision + attempt-counter reset land together;
      passing round lands with the advanced pipeline position (FR4); engine resume
      fast-forwards over a recorded pass (FR9, NFR-C1)
- [ ] 2.4 Shared factory-owned-paths salvage policy consumed by host and container salvage;
      factory files restore from tip (D11, FR5)

## 3. Intent→effect→receipt component and its flows

- [ ] 3.1 The shared intent→effect→receipt component: durable intent, effect probe,
      check-target-before-redrive, destructive-step-last (D5, FR10)
- [ ] 3.2 Host park and completion finish migrate onto it; `Completed`-without-cleanup tip
      finishes without re-entering the engine (FR9, FR10)
- [ ] 3.3 Container park records its outcome through the bare-objects repository with the
      pending marker and terminal-write confirmation (D12, FR10)
- [ ] 3.4 Decision flow: branch append before tracker acknowledge (FR12); escalated
      container resume disposes the kept box before the decision commit (D12, FR17)
- [ ] 3.5 Abort flow: marker posted before the ready flip — over-count fails safe (FR12)

## 4. Claim epoch and self-fencing

- [ ] 4.1 Epoch = claim comment id exposed as the tracker port's monotonic claim token (D6,
      FR13); stamped as a commit trailer and in comment markers
- [ ] 4.2 Stale-epoch artifacts classify as the `StaleEpoch` shape with a routed recovery
      (FR13, FR1)
- [ ] 4.3 Self-fencing: unconfirmed heartbeat freezes writes at the next boundary until the
      claim re-verifies (FR13); reap keeps lost-detection strictly before reassignment

## 5. Tracker adapter hardening

- [ ] 5.1 Marked-comment upsert primitive: hidden content-identity marker, find-then-upsert
      (D7, FR11, NFR-S1)
- [ ] 5.2 Migrate the five existing marker kinds onto the primitive (FR11, UX3)
- [ ] 5.3 Claim ordering: comment durable before the label transition (FR12)
- [ ] 5.4 Reaper owns "working label without a live claim": grace, then return to ready
      (FR12); covers the reap sequence's own kill windows
- [ ] 5.5 Label-operation and HTTP-transport failures join the retryable
      tracker-unavailable hierarchy (FR18)

## 6. Routing and replica reconciliation

- [ ] 6.1 Locate fetch classification on subprocess outcomes: only confirmed-missing-ref is
      "absent"; everything else is infrastructure — retry, then abort the take (D14, FR6);
      same split for the remote-tip probe
- [ ] 6.2 Load-bearing first push of a new branch: bounded retry, abort-before-round on
      exhaustion; later pushes stay best-effort (FR7)
- [ ] 6.3 Replica-pair reconciler consolidating the host and container divergence twins:
      keep / fast-forward / discard-under-lease; discard is a local-ref CAS against the
      decided tip; origin history untouched (D8, FR8, NFR-R3); harvest refusal maps to the
      same DIVERGED verdict
- [ ] 6.4 Route all take/resume/reconcile paths through the classifier; remove the
      `--discard-work` divergence gloss from the take surface (FR2, FR8)

## 7. Recovery budget and quarantine

- [ ] 7.1 Unified automatic-retry accounting: one persisted counter model with categorized
      causes replacing the separate crash fuse; one quarantine outcome with history (D9,
      FR14)
- [ ] 7.2 First-classification quarantine for `Corrupt` / `Unknown` / `UnsupportedVersion`
      with a diagnosis naming file, observed and expected shape (FR15, NFR-O2, UX2)

## 8. Shape-tolerant inspection

- [ ] 8.1 `status` list and single-task modes render every legal shape through the
      classifier; one bad branch degrades to a diagnostic row (FR16, UX4)
- [ ] 8.2 `usage` skips unreadable historical commits with a warning (FR16)

## 9. Kill-point harness

- [ ] 9.1 Table-driven harness: enumerate durable steps of every multi-step transition
      (host and container), kill after each, run pickup, assert converged shape; every
      recovery runs twice asserting no-op (D13, M1, NFR-R1)
- [ ] 9.2 Measure harness runtime; wire into `check` or a dedicated CI lane per Q4 and
      record the decision in the ADR

## 10. Verification

- [ ] 10.1 Audit-scenario specs green (M3): first-round-killed resume in both modes,
      container decision round-trip, Completed-without-cleanup finish, passed-stage
      fast-forward, diverged-branch continuation, working-label-orphan reap,
      decision-before-ack, mixed-shape status
- [ ] 10.2 Full `./gradlew check` green including mutation gates in every touched module
      (M4); update operator guides for changed behavior (divergence, quarantine reports)
- [ ] 10.3 Verify FR/NFR/UX traceability coverage by grep per `.claude/rules/traceability.md`

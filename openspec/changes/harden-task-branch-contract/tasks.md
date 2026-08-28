# Tasks: harden-task-branch-contract

Sequencing: start after `bound-subprocess-commands` is implemented and archived — D14 and
task 6.1 consume its named invocation outcomes. TDD throughout: each task lands with its
failing-first Spock specs (`.claude/rules/testing.md`).

## 0. Durable documentation and coordination

- [x] 0.1 Write the crash-consistency ADR in `docs/adr/` (D1, D15): principle, three
      mechanisms, per-shape roll-forward/discard table, the per-medium
      atomicity/durability table (host atomic writer / in-box commit granularity / bare
      objects) with its accepted non-mechanisms (no local fsync, no atomic in-box
      `putFile` — D10), rejected alternatives (saga journal, WAL, block counters),
      durability point = successful push
- [x] 0.2 Add the `.claude/rules/` crash-consistency checklist rule for future multi-step
      transitions (D15, G5); include the referencing rule: policy ownership is cited by
      capability (capabilities outlive archives), provenance by change name
- [x] 0.3 Add glossary entries: branch shape (pointing at the canonical eleven-shape table
      in the `task-branch-contract` spec, its single owner — never a copy), tracker shape
      (table owner: the `claim-heartbeat` spec), sweep universe, recovery owner, claim
      epoch, intent/receipt;
      quarantine (the automatic-recovery kind of park), fence (the claim-epoch
      self-fencing sense, beside the existing meanings); record `Escalated` and `Decision`
      as banned synonyms for the `Parked`
      and `Answered` shapes
      (D15, D16); link from the ADR
- [x] 0.4 Add a coordination note to `fix-denial-attribution-durability`'s proposal: its
      resume restore reads through the shape classifier, and its best-effort cursor
      persistence contract (its NFR-R1) changes — cursor writes ride the atomic
      single-commit transitions of FR4/FR5 (active change, editable)

## 1. Shape model and classifier

- [x] 1.1 Domain shape model: sealed hierarchy per D4 with per-shape recovery-owner and
      roll-forward/discard declarations (FR1, FR2)
- [x] 1.2 Tip-reader seam with three adapters — worktree tip, `git show`, bare objects (D3);
      classifier never reads the dirty worktree for factory files (FR5)
- [x] 1.3 Classifier: total mapping file-set × envelope version × epoch → shape; content
      never throws (FR1, FR15, NFR-R2); delivered detection searches history for the cleanup
      commit (D4). The epoch enters as an opaque ordered token — its issuance and source
      arrive with task 4.1, which 1.3 does not depend on
- [x] 1.4 Property-based spec: generated tips always classify to exactly one shape (M2)
- [x] 1.5 Repair observability: structured log per non-clean classification/repair, warning
      on repeated repair of one task (NFR-O1)

## 2. Writers honor the contract

- [x] 2.1 Move the atomic file writer to a shared dependency-free leaf module; the
      host-side `.gnomish-task/` writers adopt it (`GitAttemptPersistence`,
      `GitTaskRepository`, `TerminalWriteMarker`, `TraceLineWriter`); the dashboard writer
      moves with it; the container persister is commit-atomic already (D10, FR5)
- [x] 2.2 STARTED commit carries the initial `state.json` synthesized from the frozen law
      (D2, FR3); pre-contract tip classifies as legal `Created` and resumes stage one
- [x] 2.3 One transition = one commit: decision + attempt-counter reset land together;
      passing round lands with the advanced pipeline position (FR4); engine resume
      fast-forwards over a recorded pass (FR9, NFR-C1)
- [x] 2.4 Shared factory-owned-paths salvage policy consumed by host and container salvage;
      factory files restore from tip (D11, FR5)

## 3. Intent→effect→receipt component and its flows

- [x] 3.1 The shared intent→effect→receipt component: durable intent, effect probe,
      check-target-before-redrive, destructive-step-last (D5, FR10)
- [x] 3.2 Host park and completion finish migrate onto it; `Completed`-without-cleanup tip
      finishes without re-entering the engine (FR9, FR10)
- [x] 3.3 Container park records its outcome through the bare-objects repository (its
      `recordOutcome` exists but is never invoked for park today) with the pending marker,
      and adds the container-mode `confirmTerminalWrite` the bare-objects path lacks
      (D12, FR10)
- [x] 3.4 Decision flow: branch append before tracker acknowledge (FR12); escalated
      container resume disposes the kept box before the decision commit (D12, FR17)
- [x] 3.5 Abort, finish, and park flows: truth marker posted before the label flip —
      abort over-count fails safe; a killed finish keeps terminality derivable (FR12)

## 4. Claim epoch and self-fencing

- [x] 4.1 Epoch = claim comment id exposed as the tracker port's monotonic claim token (D6,
      FR13); stamped as a commit trailer and in comment markers — the marker half lands with
      task 5.2, where all eight marker kinds move onto the upsert primitive and one renderer
      can stamp them from the same tenure record; doing it here would have needed either a
      second, drift-prone tenure record inside the GitHub adapter or an SPI change
- [x] 4.2 Stale-epoch artifacts classify as the `StaleEpoch` shape with a routed recovery
      (FR13, FR1)
- [x] 4.3 Self-fencing: unconfirmed heartbeat freezes writes at the next boundary until the
      claim re-verifies (FR13); reap keeps lost-detection strictly before reassignment

## 5. Tracker adapter hardening

- [x] 5.1 Marked-comment upsert primitive: hidden content-identity marker, find-then-upsert
      (D7, FR11, NFR-S1)
- [x] 5.2 Migrate the eight existing marker kinds onto the primitive (FR11, UX3); the same
      renderer stamps each marker with the tenure's claim epoch, the marker half of 4.1
- [x] 5.3 Claim ordering per the sweep-universe rule: working label first, claim comment
      second, verify-read third — the kill window freezes `ClaimPending` (FR12)
- [x] 5.4 Facts-only listings: `GithubOpenQuery` stops omitting no-footprint working issues
      and reports label + claim facts; `listReady` entries gain claim facts from the
      existing enrichment read, no extra API calls (FR19)
- [x] 5.5 Tracker-shape classifier in core (sealed, total — the D16 mirror of D3), consumed
      exhaustively by take routing, the claim guard (`ClaimGuard`/`fetchTask` stop throwing
      on working-without-claim), and the board (FR19, FR2)
      - Deviation: the classifier is consumed by the sweep (reaper + observation memory) and
        the adapters stopped judging — `fetchTask` reports a working-without-claim task
        instead of throwing, so `ClaimGuard` and the board read a fact where they used to
        crash. Take routing and the board still switch on the port's `TrackerTaskState`
        projection, itself a sealed exhaustive set: routing on shapes instead would need
        `fetchTask` to return the fact triple, a port change beyond this task's scope and
        outside the FR19 defect (adapter-side judgment on the sweep path). Recorded here
        rather than silently narrowed.
- [x] 5.6 Generalized reaper repair: sweep = `listReady` + `listOpen`; `StalenessMemory`
      keeps TTL and gains window grace, drops the null-version eligibility filter; repairs
      go through the port — `removeStaleClaim` generalized to dead footprints, new
      `repairIndex` rolling `ClaimPending` back and completing `IndexLagging` flips;
      covers the reap sequence's own kill windows (FR19, FR12)
- [x] 5.7 Label-operation and HTTP-transport failures join the retryable
      tracker-unavailable hierarchy (FR18)

## 6. Routing and replica reconciliation

- [x] 6.1 Locate fetch classification on subprocess outcomes: only confirmed-missing-ref is
      "absent"; everything else is infrastructure — retry, then abort the take (D14, FR6);
      same split for the remote-tip probe; each execution medium maps its native outcome
      representation onto the taxonomy in one adapter-owned seam — in container mode the
      named interrupt arrives as `CapturedExec`'s `InterruptedIOException` cause, never
      branched on ad hoc at call sites (D14)
- [x] 6.2 Load-bearing first push of a new branch: bounded retry, abort-before-round on
      exhaustion; later pushes stay best-effort (FR7)
- [x] 6.3 Replica-pair reconciler consolidating the host and container divergence twins
      (`WorktreeDivergenceCheck`, `ContainerResumeBranch`) and `OriginReconciliation`'s
      touchpoint ancestry check:
      keep / fast-forward / discard-under-lease; discard is a local-ref CAS against the
      decided tip; origin history untouched (D8, FR8, NFR-R3); harvest refusal maps to the
      same DIVERGED verdict
- [x] 6.4 Route all take/resume/reconcile paths through the classifier; remove the
      `--discard-work` divergence gloss from the take surface (FR2, FR8)

## 7. Recovery budget and quarantine

- [x] 7.1 Unified automatic-retry accounting: one persisted counter model with categorized
      causes replacing the separate crash fuse; one quarantine outcome with history;
      defaults per Q3 — the existing crash-fuse K and its backoff, recorded in the operator
      guide (D9, FR14)
      - The counter model is the existing tracker-reconstructed abort accounting, now carrying
        the two categories (`RecoveryCause`: instance crash / recovery failure) as shares of one
        total — so the K threshold and the backoff keep computing over the whole counter and no
        second fuse exists. A failed repair of a non-clean shape is named at the routing table
        (`BranchRecoveryFailedException`), the one place that knows a repair was underway, and the
        crash boundary spends the category it names; the category rides in the marker's existing
        wire field, so pre-categorization markers read back as the crashes they meant.
- [x] 7.2 First-classification quarantine for the three non-recoverable shapes of D4's
      canonical table, with a diagnosis naming file, observed and expected shape — and for
      `UnsupportedVersion`, observed and supported version (FR15, NFR-O2, UX2)
      - The quarantine verdict already existed at the routing table; what lands here is its
        outcome: the take's claim lifecycle routes it to `TakeQuarantinePark` instead of the crash
        arm, parking `AwaitingHuman(INFRA)` with a report naming the shape, the diagnosis, and the
        attempts already consumed — and spending none of them, so no crash loop precedes the park.

## 8. Shape-tolerant inspection

- [x] 8.1 `status` list and single-task modes render every legal shape through the
      classifier; one bad branch degrades to a diagnostic row (FR16, UX4)
      - Single-task mode answers `BranchStateResult.Shaped` for a tip that carries no report —
        delivered, bare, pre-contract, or a quarantine shape — so an unsupported version is a named
        shape instead of a thrown version gate; the three quarantine shapes print their diagnosis
        and exit 7 (`BranchShapeRefusedException`), mutating nothing. Whether a shape's tip can be
        rendered is `BranchShape.tipCarriesState()`, one owner for the reader and the lister rather
        than a manual sync pair. List rows carry the domain shape (not a pre-rendered string) and
        deduplicate on branch name, so a task whose local tip is delivered and whose remote tip
        still carries its files is one row.
- [x] 8.2 `usage` skips unreadable historical commits with a warning (FR16)

## 9. Kill-point harness

- [x] 9.1 Table-driven harness: enumerate durable steps of every multi-step transition
      (host and container), kill after each, run pickup, assert converged shape; every
      recovery runs twice asserting no-op (D13, M1, NFR-R1, UX1); the no-op assertion
      tolerates `CapturedExec`'s conservative interrupt classification — at worst a re-run
      service commit, never paid work (D13, NFR-C1)
- [x] 9.1b Tracker kill windows: the in-memory reference adapter is atomic, so the GitHub
      adapter's own suite fails the connection after each write of the claim, abort,
      finish, park, and reap sequences (WireMock) and asserts every frozen state
      classifies to a named tracker shape owned by a retry or the sweep (FR19, M1).
      The classification half lives in `:application` — the classifier's own module, which
      the vendor bundle must not depend on (FR2 of split-into-modules); the two halves meet
      on one shared enumeration of the frozen fact combinations, so neither can drift
- [x] 9.2 Measure harness runtime against the NFR-P1 budget; wire into `check` or a
      dedicated CI lane per Q4 and record the decision in the ADR — measured ~7 s against a
      ~5 min budget, so it stays in the default `check` (ADR 0003, "The kill-point gate and
      where it runs")

## 10. Verification

- [x] 10.1 Audit-scenario specs green (M3): first-round-killed resume in both modes,
      container decision round-trip, Completed-without-cleanup finish, passed-stage
      fast-forward, diverged-branch continuation, working-label-orphan reap,
      decision-before-ack, mixed-shape status
      - Each of the eight has a green spec: first-round-killed resume — `GitTaskRepositorySpec` /
        `GitObjectsTaskRepositorySpec` FR3 with `BranchTipSourceSpec` (both media read the STARTED
        commit's initial `state.json`) and `ContainerRunSupportSpec`'s no-`state.json` fallback;
        container decision round-trip and decision-before-ack — `DecisionKillPoints` in both media
        plus `DecisionAckSpec`; Completed-without-cleanup finish — `FinishKillPoints` plus
        `TakeResumeShapeTailSpec` / `TakeContainerResumeRoutingSpec`; passed-stage fast-forward —
        `PassAdvanceOneCommitSpec` and `ResumeMatrixSpec`; diverged-branch continuation —
        `ReplicaPairReconcilerSpec` FR8; working-label-orphan reap — `GithubIndexRepairSpec`,
        `GithubStaleClaimRemovalSpec`, `GithubTaskFetcherSpec` FR19; mixed-shape status —
        `TaskListRendererSpec` FR16 and `StatusCommandSpec`
- [x] 10.2 Full `./gradlew check` green including mutation gates in every touched module
      (M4); update operator guides for changed behavior (divergence, quarantine reports)
      (UX1)
      - The gate found four modules short of 100%; closed by specs, not exemptions, except where
        the mutation was provably unkillable. New coverage: shape routing and the completion tail
        (`TakeResumeShapeTailSpec`), the completion's probe and claim-guard arms (`FinishEffectSpec`),
        the unaskable-tracker park re-drive (`TakeParkRetrySpec`), the quarantine park
        (`TakeClaimAndWorkSpec`), the container park receipt and in-box finish
        (`TakeContainerResumeRoutingSpec`, `ContainerRunSupportSpec`), and the FR14 recovery/crash
        split on both folding arms (`GithubCommentBoundarySpec`)
      - Two production changes came out of it: `TerminalEffectDrive.redeliver` now switches
        exhaustively over `EffectObservation` instead of testing `== LANDED`, so an observation
        outside the vocabulary fails loudly; and `DecisionAck`'s re-drive no longer carries a dead
        decision lambda. Two `@DoNotMutate` markers were added with their rationale in the method's
        own comment — `DecisionAck.deliver` (equivalent mutant: the flow discards the
        `EffectDelivery` and its receipt is a no-op) and `AbortFacts.crashCount` (the JVMTI
        record-redefinition RUN_ERROR of `.claude/rules/testing.md`)
      - Operator guides already carry the changed behavior: automatic divergence reconciliation in
        `docs/guides/operator-guide-run.md`, the quarantine report and its exit code 7 in
        `docs/guides/operator-guide.md` and `docs/guides/operator-guide-inspect.md`
- [x] 10.3 Verify FR/NFR/UX traceability coverage by grep per `.claude/rules/traceability.md`
      - All 19 FRs, 9 NFRs and 4 UX criteria have at least one implementing entity in code or tests
        attributing them to this change by name; verified by grepping every ID against the files
        that name `harden-task-branch-contract`

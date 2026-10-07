# Tasks

Sequenced after `make-run-headless` is applied and archived (its `EscalationResume.decide`
and the headless `resumePaused` arms are callers here); before `add-stage-iteration` is
applied. Root `./gradlew check` green after §2, §4, §7 and §6. Every task names its consumers and
its old-way sweep so an `apply` sub-agent checks a list (`implementation.md`). §7 (the round
token, design D10) is implemented before task 4.3 is ticked: 4.3's container E2E features
(`ContainerModeResumeE2ESpec`, `SandboxLifecycleZombieE2ESpec`) stay red until it lands.

## 1. Domain: the gate and the stop on the record

- [x] 1.1 Add `Position.AwaitingApproval(String stage)` (`:domain`, `engine`; design D1), blank
      name refused like `AtStage`. Make `Advancement.positionAfter(definition, stage)`
      mode-aware: `MANUAL` → `AwaitingApproval(stage)`, `AUTO` → next stage / `PipelineEnd`;
      add package-private `Advancement.afterGate(definition, stage)` (the AUTO branch) and the
      public `TaskState.approveGate(PipelineDefinition)` that delegates to it — the only way
      the `run`/`take` callers holding the pinned definition compute the approved state
      (design D2, D7); rewrite the javadoc that
      says the mode does not enter. Delete the `Advancement.nextPosition(next)` call in
      `Engine.runStages`' MANUAL arm (`Engine.java:185-187`): return
      `Paused(passed.state(), stage)` — the state the round persisted. Verify `AdvancementSpec`
      data table over both modes × middle/last stage; `EngineSpec` asserts the MANUAL arm
      returns the persisted state unchanged.
- [x] 1.2 `Engine.preflight`: a state at `AwaitingApproval(stage)` returns
      `TaskOutcome.Paused(state, stage)` with RunStarted/TaskFinished emitted and no port call
      (design D1, FR2). Verify `EngineSpec` with a throwing executor and throwing check runners:
      Paused returned, nothing invoked, for a middle and a last stage.
- [x] 1.3 Add `Stop` (`:domain`, `engine`; design D3): sealed `None | DecisionNeeded(UntrustedText
      question, List<UntrustedText> options) | CannotVerify(CheckRef check, UntrustedText reason,
      UntrustedText details)`; `AttemptRecord` gains the `stop` component (constructor requires
      it; `None` for PASSED/QUALITY_FAILURE). `StageAttemptLoop` sets it when recording a
      `DECISION_NEEDED` round (from the executor's decision request) and a `CANNOT_VERIFY` round
      (from the verdict). Fix every `AttemptRecord` construction site — production:
      `StageAttemptLoop`, `RoundExecution`; fixtures: `ReadyTaskFixtures`, the status-report
      reference fixture, `StateJsonMapper` reader, every `new AttemptRecord(` in `*/src/test`
      (grep and list in the report). Verify `StageAttemptLoopSpec` asserts the stop content on
      both results and `None` on a pass.
- [x] 1.4 `Engine.preflight` re-escalates from the record (design D3, FR6): when the current
      stage's recorded history is non-empty and its last round carries a `Stop` other than
      `None`, return `Escalated` with the matching `EscalationReport` rebuilt from the record,
      no port call, `attemptsUsed` unchanged. Order: PipelineMismatch → AwaitingApproval →
      recorded stop → attempt limit. Verify `EngineSpec`: DecisionNeeded and CannotVerify lost-park
      states re-escalate with identical content; a state whose history was reset
      (`resetAttempts()` / `startOfStage()`) runs the stage instead.
- [x] 1.5 Fix every `Position` reader to name the gate (design D1, G3) — the compiler lists them;
      by file: `TaskState`, `StatusReport` (position + `currentStage` describes the passed
      stage), `SummaryAccumulatorListener`, `MdcEventListener` (`stage` MDC = the gate's stage),
      `TaskSummaryAssembler`, `HeartbeatProgress`, `ResumeDecisionCommit`, `GitModeRunner`,
      `EscalationResume`, `ContainerResumeOutcomes`, `TakeContainerResumeRunner` (`reattachFor`
      the gate's stage — the box of the stage that passed), `TakeFinishReport`, `StageResult`
      (`:domain`), `status/json/PositionDto` and `StatusReportJsonMapper` (task 4.5),
      `TaskBranchLister` (`adapters/git`), `RunCheckRunContext` (`:bootstrap`),
      `StateJsonMapper`/`StatePositionDto` (task 2.1). Record the list and each arm's choice in this task. Verify each file's existing
      spec gains the gate case; `StatusTextRenderer` prints "awaiting approval after '<stage>'"
      (UX1).
- [x] 1.6 Rewrite `ResumeMatrixSpec` "a post-pause resume starts at the next stage" (M3): a run
      from the gate pauses; a run from the state `TaskState.approveGate` yields starts the next
      stage at round zero. Rewrite `stage-engine`'s "Manual pause on the last stage" expectation
      in every spec that asserts `PipelineEnd` after a MANUAL pass (grep `PipelineEnd` in
      `domain/src/test` and `application/src/test`; list the hits).

## 2. Wire and lifecycle writes

- [x] 2.1 `StateJsonMapper` / `StatePositionDto`: token `awaitingApproval(stage)`;
      `StateAttemptDto`: `stop` object (`none` | `decisionNeeded` | `cannotVerify`), absent read
      as `none`; version stays 1 (design D5, FR10). Verify the round-trip spec iterates
      `Position.class.getPermittedSubclasses()` and `Stop.class.getPermittedSubclasses()` and
      rejects an unknown `stop.type`; the version gate's specs unchanged — both
      `UnsupportedStateFileVersionExceptionSpec`s (`adapters/git`, `:application`) and
      `StateJsonMapperSpec`'s version cases.
- [x] 2.2 `TaskRepository.approveCheckpoint(taskId, TaskState approved)` (`:application`, `port`;
      design D2, FR3) and `TaskLifecycleStore`: implement in `GitTaskRepository` and
      `GitObjectsTaskRepository` — read the tip, refuse with
      `CheckpointApprovalRefusedException(actualPosition)` unless the position is
      `AwaitingApproval(s)` for the stage named and `approved.position()` is not a gate — the
      repositories hold no pipeline definition; the caller computed `approved` through
      `TaskState.approveGate` (task 1.1) — then one commit:
      `state.json` = `approved`, `task.json` outcome null, marker false, `lastEscalation` kept;
      pass through `PushBestEffortTaskLifecycleStore`. One WARN with a new `OperatorEvent` code
      on refusal, one INFO on success (NFR-O1). Verify `GitTaskRepositorySpec` /
      `GitObjectsTaskRepositorySpec`: approve, refuse on `AtStage`, refuse on another gate,
      refuse on `PipelineEnd`; the commit count is one.
- [x] 2.3 `TaskRepository.resumeFrom(taskId, TaskState reset)` (design D4, FR7): both
      repositories — refuse when the tip's `outcome` is null, else one commit: `state.json` =
      `reset`, outcome null, marker false; decorator pass-through; INFO line. Verify both
      repository specs: resumed commit content; refusal on a null outcome; `appendDecision`
      unchanged.
- [x] 2.4 `GitObjectsTaskRepository.recordOutcome` (FR9): `egressCursors.forEscalation(...)`
      receives the report of `outcome` when it is `Escalated`, otherwise no report — never the
      carried `lastEscalation`. Verify `GitObjectsTaskRepositorySpec`: a `Paused`/`Completed`/
      `Aborted` write after a tip carrying `cannotExecute` reads no cursor and preserves the
      committed one; the existing `GitObjectsTaskRepositorySpec` cursor cases unchanged
      (`LifecycleEgressCursor` has no spec of its own).
- [x] 2.5 Extend the existing `Kept in sync with` sentence of the already-declared pair
      `GitTaskRepository ↔ GitObjectsTaskRepository` (`GitTaskRepository.java:57`,
      `GitObjectsTaskRepository.java:63`; design D6) with the invariant "the three
      outcome-clearing writes (`appendDecision`, `approveCheckpoint`, `resumeFrom`) land the same
      `task.json`/`state.json` fields in one commit and refuse on the same tip conditions" — no
      second marker; add the row to `.claude/rules/manual-sync-pairs.md`'s registry only if the
      `{@link}` cannot resolve (both are in `adapters/git` — it resolves, so no row).
- [x] 2.6 Gate: `./gradlew check` green.

## 3. Branch shape

- [x] 3.1 `BranchTipFacts` gains the recorded position (`BranchTipFactsReader` reads it from
      `state.json`); `BranchShape.AwaitingApproval` added (design D8, FR11), classified before
      `Parked`/`InProgress` whenever the position is a gate, whatever `outcome` says; owner
      STAGE_ENGINE; `isClean` true (a gate is not a repair). Fix every `BranchShape` reader the
      compiler names: `TakeDispositionResume` (→ `routes().route(order)`), `BranchRepairLog`,
      `StatusCommand`/`BranchStateReader`, `GitResumeRunner` and `ContainerResumeRunner` shape
      arms, `TakeDisposition`. Verify `BranchShapeClassifierSpec` (three outcome variants at a
      gate → `AwaitingApproval`), `BranchShapeClassifierPropertySpec` generates positions;
      `ClaimlessGitBoundarySpec`/`EnvelopeMediumBoundarySpec` unchanged.
- [x] 3.2 Update `openspec`-independent docs the shape set is mirrored in:
      `docs/adr/0003-crash-consistency.md`'s disposition table gains `AwaitingApproval` (task
      6.1 carries the ADR text; the glossary holds no shape list — task 6.3).

## 4. Continuations

- [x] 4.1 `ResumeMechanics<B>` (design D2, D4, D6): add `approveCheckpoint(order, branch)` and
      `resumeFrom(order, branch, reset)`; implement in `HostResumeMechanics` (worktree
      repository) and `ContainerResumeMechanics` (bare-object repository, before
      materialization). Rewrite the `Kept in sync with` text of `TakeResumeRunner ↔
      TakeContainerResumeRunner` to say the two writes live in the mechanics.
- [x] 4.2 `take` arms (FR4, FR7): `TakeLoadedBranchRoutes.route` — a tip at a gate (shape
      `AwaitingApproval`) with the marker cleared → `mechanics.approveCheckpoint` then
      `resumeWithoutDecision` from the approved state; a gate with `outcome` null (park lost) →
      deliver the park (`GitOutcomeRecorder.recordIntent` / `recordPark` of `Paused(stage)`,
      then `TakePauseExit.finish`) and exit — no stage runs; a recorded non-gate outcome
      continued without a reply → `mechanics.resumeFrom(reset)` first (`TakeDecisionResume`
      bare-`AttemptsExhausted` arm: replace `finalState.resetAttempts()` passed in memory with
      the write; infrastructure returns in `resumeWithoutDecision`). Verify
      `TakeLoadedBranchRoutesSpec`, `TakeResumeRoutingSpec`, `TakeResumeRunnerWithoutDecisionSpec`
      (asserts the resumed commit, M3), `TakeDecisionResumeSpec`.
- [ ] 4.3 `run` arms (FR4, FR7): `GitResumeContinuation.resumePaused` and
      `ContainerResumeOutcomes.resumePaused` → `taskRepository.approveCheckpoint` then continue
      from the approved state; `GitResumeRunner`/`ContainerResumeRunner` routing switches on the
      shape (`AwaitingApproval` with any outcome → `resumePaused`); `EscalationResume.decide`
      null-decision path → `resumeFrom(reset)` instead of returning the reset in memory (the
      decision path keeps `appendDecision`); `--decision` over a gate → `UsageException`.
      Rewrite the `Kept in sync with` text of `GitResumeContinuation ↔ ContainerResumeOutcomes`
      (design D6). Verify `GitResumeRoutingSpec`, `ContainerResumeRoutingSpec`,
      `GitResumeOutcomeSpec`, `ContainerModeResumeE2ESpec`: one commit per continuation.
- [ ] 4.4 Terminal boundaries re-deliver a lost park (FR2, FR11): `GitModeRunner.run`,
      `GitResumeContinuation.runToTerminalBoundary`, `ContainerTerminalDrive.run`,
      `TakeEngineExecution`, `TakeContainerEngineExecution` already receive `Paused` from the
      Engine for a gate — confirm each records the park only when `task.json` lacks it (the
      repositories' `recordOutcome` on a tip already carrying the same park is a content no-op;
      assert no second commit) and renders through `TerminalOutcomeRender`. Old-way sweep:
      `grep -rn "nextPosition(\|positionAfter(" */src/main` returns `Advancement` and
      `StageAttemptLoop` only; record the result.
- [ ] 4.5 `status` (FR12, UX1): `StatusReport.build` for a gate — position
      `awaitingApproval(stage)`, `currentStage` = the passed stage with its passing round last;
      `StatusReportJsonMapper`/`PositionDto` tokens for the position and the attempt `stop` (the
      mapper only serializes; the read-back is `StatusReportEquivalenceContractSpec` on the
      reference); `status-report-v1.reference.json` regenerated with `stop` on the first attempt
      (the reference document changes — a pre-release amendment, noted in the spec). Verify
      `StatusReportJsonMapperSpec` round-trips every variant; `StatusReportEquivalenceContractSpec`
      green on the new reference.
- [ ] 4.6 Gate: `./gradlew check` green; `grep -rn "resetAttempts()" */src/main` lists only
      `TaskState` and the call sites that hand the result to `appendDecision` / `resumeFrom`
      (record the list).

## 5. Enforcement and crash consistency

- [ ] 5.1 `PositionConstructionGateSpec` (`:bootstrap`; design D7): `new Position.AwaitingApproval(`
      in `*/src/main` appears only in `Advancement.java` and `StateJsonMapper.java` (the wire
      reader) — the repositories' refusal checks pattern-match the variant, and
      `TaskState.approveGate` constructs `AtStage`/`PipelineEnd` through `Advancement.afterGate`,
      never the gate; allowlist by file, asserted reached. Red when a copy is planted in a scratch file.
- [ ] 5.2 `OutcomeConsumptionGateSpec` (`:bootstrap`; design D7): `resetAttempts()` in `*/src/main`
      appears only in `TaskState.java`, `EscalationResume.java`, `TakeDecisionResume.java`, and
      the two mechanics; allowlist by file, asserted reached.
- [ ] 5.3 Identity specs (`:bootstrap`, bare origin, both media; `testing.md` "Invariant specs
      across a flow"): `GateApprovalIdentitySpec` — after a `manual` pass and an approval, every
      tip in the branch history either carries `AwaitingApproval(s)` or carries the approval
      commit as itself/ancestor; the approved tip's position is past the gate iff `outcome` is
      null, and equals the position following the gate in the pinned definition (the
      repositories no longer check it, so this spec does). `ConsumedOutcomeIdentitySpec` — after each of the three continuations, `outcome ==
      null` iff `state.json` carries the continuation's state, in one commit.
- [ ] 5.4 Kill-point rows in `TransitionKillPointSpec` (design D8, NFR-R1): `GateKillPoints`
      (host + container; `Paused` and `DecisionNeeded`; steps round commit → park commit →
      tracker park → receipt; a barrier before the park commit; pickup through the real routes;
      the recording executor asserts the next stage never ran; the `manual`-last-stage variant
      asserts no `Completed` is recorded), `ApprovalKillPoints` (one step; second pickup no-op),
      `ResumedKillPoints` (one step; second pickup resets nothing). `KillPointWorld` gains a
      round-commit step with an advanced/gated position (reuse `ReclaimKillPoints.salvageStep`'s
      `GitAttemptPersistence.persist` pattern). Verify green on the fixtures.
- [ ] 5.5 PIT 100% on `:domain`, `adapters/git`, `:application`, `:bootstrap` (NFR-R3); record
      any `@DoNotMutate` added with its reason.

## 6. Durable record

- [ ] 6.1 `docs/adr/0003-crash-consistency.md`: new subsection under "The three mechanisms" —
      "A recorded position never implies an authorisation not yet recorded; gates are positions;
      the verdict rides the round record" — with the canon and the comparable systems in two
      sentences each, the disposition table row for `AwaitingApproval`, and the "See also".
      Plus the D10 corollary under "Consumed streams": "a reader of a medium that keeps the
      past judges liveness by identity, never by presence — the request carries the round
      token, the receiver matches it, deletion is hygiene" (task token, Correlation
      Identifier, fencing token; SQS delete-after-process as the hygiene precedent).
- [ ] 6.2 `.claude/rules/crash-consistency.md`: checklist item 12 "No write implies an
      authorisation a later write grants" with the question to ask of every earlier step.
- [ ] 6.3 `docs/glossary.md` (Crash consistency): *gate*, *awaiting approval*, *approval* (the
      pivot write), *resumed write*, *round token* (the identity of a container-mode round —
      the branch tip it opened on — that names its decision request and its snapshot; *Never:*
      "attempt" for it, which is the counted try and repeats). The *Attempt* entry notes that
      its number repeats across visits. The *Branch shape* entry carries no shape list (it defers
      to the `task-branch-contract` table and ADR 0003), so its *Never:* clause gains only:
      `Paused` for the `AwaitingApproval` shape (that name belongs to a `TaskOutcome` variant).
- [ ] 6.4 `openspec/changes/add-stage-iteration/design.md`: a review note (design D9) naming the
      three instances (D6 overflow decision, "cursor exhausted, no stage verdict", D8 repair
      adoption) and the rule they must follow; `openspec/changes/add-stage-finished-event/
      design.md`: `StagePassed.advancedTo` reads `Advancement.positionAfter`.
- [ ] 6.5 Operator guide (`docs/guides/operator-guide-run.md`, and the `take` section of
      `docs/guides/operator-guide.md`): a checkpoint reads
      "awaiting approval"; `--resume` approves; a return-to-ready approves.
- [ ] 6.6 Gate: root `./gradlew check` green; record M1–M6 in this task.
- [ ] 6.7 Layering of the overlapping delta (`delta-specs.md`): `openspec/changes/
      add-pipeline-entry-precondition/specs/git-task-persistence/spec.md` MODIFIES "State
      directory with one writer per file" too and is sequenced after this change — open its
      requirement with the `Layered on … as modified by make-checkpoint-gate-durable (sequenced
      before this change)` preamble, rewrite its text over this change's delta (token path,
      `stop` field), and add the sequencing line to its proposal. `add-decision-arbiter`'s
      delta changes the request's content schema, not its path — add a one-line note to its
      design that the path carries the round token.

## 7. Round token (design D10; FR13–FR16, NFR-R4, G4)

- [x] 7.1 `RoundToken` value type (`adapters/git`; a commit id, blank or non-hex refused) and
      the per-run `RoundTokenRef` (the `AttemptCommitRef` shape; `required()` throws when no
      round opened). `SandboxRoundEnvironmentSource.openRound` reads `refs/heads/<branch>`
      through the runner, records the token, and opens
      `BranchDecisionFile.open(environment, key, token)`; `HarvestedBoundaryCheck.decisionPath(key,
      token)` becomes the one spelling `decisions/<stage>-a<attempt>-<token>.json`. Delete the
      token-less `decisionPath(AttemptKey)` and `open(environment, key)` — no caller may build the
      old name. Consumers (design D7 row): the handle, `verify`'s carve-out, the snapshot
      subject (7.2), persistence (7.3). Old-way sweep: `grep -rn "decisionPath(\|DECISIONS_DIR\|
      -a\" + " */src/main adapters/*/src/main` — every hit listed and routed. Verify
      `BranchDecisionFileSpec` (reads exactly the token path; a same-key file under another
      token is not read), `HarvestedBoundaryCheckSpec` (token path passes; another token's
      path is a violation), `SandboxRoundEnvironmentSourceSpec` (the env fragment names the
      token path; the ref holds the open tip).
- [x] 7.2 Resume path (FR15): `ServiceCommitMessages.snapshot(stage, round, token)` →
      `gnomish: snapshot <stage>#<round> <token>`; `SnapshotTipCheck` parses the token and reads
      the request from the snapshot tree (`git show <snapshot>:<token path>`, factory clone,
      absent → none) into `PendingVerification(attemptCommit, stage, round, Optional<String>
      request)`; `ResumeVerificationStageExecutor` maps a present request through the shared
      `DecisionFileReader` to `DecisionNeeded` (same empty telemetry), none → `Completed` as
      today. Verify `SnapshotTipCheckSpec` (token parsed; malformed subject refused; request
      read; none), `ResumeVerificationStageExecutorSpec` (DecisionNeeded with the snapshot's
      question; Completed without), and the kill-point row `RequestSnapshotKillPoints` in
      `TransitionKillPointSpec` (container: kill after the snapshot carrying a request, before
      the state commit → the pickup escalates with that question, no agent round; second pickup
      no-op; then an answer, and the next round's read is empty).
- [ ] 7.3 `EnvironmentAttemptPersistence` takes the token from `RoundTokenRef` for the
      carve-out (`verify(taskId, openTip, snapshot, key, token)`) and as the diff base; remove
      the `previousTip` field and the `currentTip()` read at construction (one owner; confirm
      the constructor at `ContainerRunSupport.java:126-135` builds persistence and the round
      source over the same ref). The `GitAttemptPersistence ↔ EnvironmentAttemptPersistence`
      javadoc states why the host end is unchanged (design D6). Verify
      `EnvironmentAttemptPersistenceSpec`: carve-out by token; the diff base is the open tip.
- [ ] 7.4 The three outcome-clearing writes remove `.gnomish-task/decisions/` in their commit
      (FR14): `GitTaskRepository` (worktree: `git rm -r --ignore-unmatch`, staged into the same
      commit) and `GitObjectsTaskRepository` (tree edit dropping the entry) for
      `appendDecision`, `approveCheckpoint`, `resumeFrom`; extend the 2.5 pair invariant with
      the removal. Verify both repository specs: the tip after each write holds no `decisions/`
      entry, the tip before it still does; a tip without the directory writes unchanged.
- [ ] 7.5 Fixtures and E2E (M5): confirm the fake agent writes only to `$GNOMISH_DECISION_FILE`
      (`fake-agent.sh:147-149`; never a spelled name); `ContainerModeResumeE2ESpec:153-156`
      asserts the request under `decisions/work-a0-<token>.json` (match by prefix and the
      snapshot subject's token, not a literal), and both `ContainerModeResumeE2ESpec` and
      `SandboxLifecycleZombieE2ESpec` pass through the `--decision` answer; tick 4.3 after.
- [ ] 7.6 Gates (design D7): `DecisionPathOwnerSpec` (`:bootstrap`): `DECISIONS_DIR` and
      `decisionPath(` in `*/src/main` only in `EnvelopePaths`, `HarvestedBoundaryCheck`,
      `BranchDecisionFile`, `FactoryOwnedPaths`, `SnapshotTipCheck`, allowlisted by file,
      asserted reached. `RoundTokenIdentitySpec` (`:bootstrap`, bare origin, real adapters,
      fake agent asking): the request's file name token, the snapshot subject's token and the
      commit the round opened on are one value; after `appendDecision` the next round's handle
      names a path the tip does not hold. `ConsumedOutcomeIdentitySpec` (5.3) gains "no
      `decisions/` entry after each continuation". PIT 100% on the touched classes
      (`verification-scope.md`: `-PpitScope` over 7.1–7.4's classes).
- [ ] 7.7 Javadoc truth: `BranchDecisionFile` and `HarvestedBoundaryCheck` ("stale files are
      self-excluding" → "a request is live only under its round's token"), `AttemptKey`
      ("monotonic" → "unique within one visit of a stage; the round token is the identity
      across visits"), `TraceLineWriter` ("every stage attempt gets its own directory" → "the
      path names the round within one visit; a repeated visit overwrites at the tip, history
      keeps every version" — proposal NG6). Record the four diffs in this task.

# Tasks

Sequenced after `remove-interactive-console` is applied and archived; §1–§2 add the headless
shape beside the prompts, §3 switches and deletes, §4 documents. Root `check` green after §2
and after §4.

## 1. Terminal outcome without a prompt

- [x] 1.1 Add `TerminalOutcomeRender` (`:application`, `app`; design D3): `escalated(report,
      returnPath)`, `paused(passedStage, returnPath)` and `checkpointLine(passedStage)` ("Stage
      '<s>' passed. Manual checkpoint reached."), plus `renderEscalation(report, plane)` moved
      here from `EscalationResumeDialog` (one home; `TakeOutcomeMapper` and `TakeDecisionResume`
      update their import). The return-path line is built from `--dir` and the task id
      (`--decision="..."` shown as optional for escalations). Route `TakePauseExit.finish` and
      `TakeOutcomeMapper`'s paused arm through `checkpointLine` and delete their hand-spelled
      strings. Spock spec covering both stop renders, the five report kinds through
      `renderEscalation(…, CONSOLE)`, `checkpointLine`, and the return-path text. Verify PIT
      100% on the class and the `take` specs unchanged in outcome.
- [x] 1.2 Make `RunnerOutcomeLoop.run` return the terminal `TaskOutcome` (design D1):
      `Paused` and `Escalated` print through `TerminalOutcomeRender` and return; `Completed`
      prints the final status and returns; `Aborted` unchanged. Verify `RunnerOutcomeLoopSpec`
      shows: no `ConsoleIO.readLine` call on any path (a `ScriptedConsoleIO` with a throwing
      reader), and the returned outcome per case.
- [x] 1.3 Map outcome to exit: add `RunParkedException(TaskOutcome)` (`:application`, the
      `run` twin of `TakeExitCodeException`); `RunExitCodeMapper` maps it to 11 for `Paused`
      and 10 for `Escalated`; `RunExceptionReporting` lists it as already reported and logs the
      return path at INFO with the task id (NFR-O1); delete `EscalationEofException`,
      `CheckpointEofException` and their mapper arms. Verify `RunExitCodeMapperSpec` covers 10,
      11 by outcome and the EOF types are gone from `*/src` — the specs that name them today
      and must be rewritten: `RunExitCodeMapperSpec`, `RunnerOutcomeLoopSpec`,
      `ManualRunRunnerSpec`, `ContainerModeResumeE2ESpec`, `GiteaCrossInstanceResumeE2ESpec`,
      `SandboxLifecycleZombieE2ESpec`, `ExitCodeMatrixSpec` (the last in task 4.1).
- [x] 1.4 Record the park at every `run` terminal boundary (design D8, FR10) — the four
      consumers, by name: `GitModeRunner.run` and `GitResumeContinuation.runToTerminalBoundary`
      switch on the returned outcome: `Completed` as today; `Escalated`/`Paused` →
      `GitOutcomeRecorder.recordAndCleanUp(…, outcome)` (worktree kept by the adapter's
      outcome-driven disposal), then `throw new RunParkedException(outcome)`.
      `ContainerTerminalDrive.run` (fresh container run and every container resume arm):
      `Escalated`/`Paused` → `support.recordPark(outcome)` then `support.keepStopped()` — never
      `completeAndDispose`/`finishCleanup` — then the throw; expose `recordPark` on
      `SandboxRunSupport` for `run` (it exists on `ContainerRunTermination` for `take`). Update
      the `Kept in sync with` markers of `GitModeRunner` ↔ `ContainerGitModeRunner` with the
      park arm (design D6). Old-way sweep: `grep -rn "readRecordedState(worktree)\|readFinalState()" */src/main`
      — every hit sits behind a `Completed` arm; record the result here. Verify
      `RunParkRecordingSpec` (`:bootstrap`, bare origin): for each of the four boundaries, a run
      ending in `Escalated` and one ending in `Paused` leave `task.json` on the branch tip with
      that outcome (`lastEscalation` present for the escalation), no cleanup commit, the
      worktree present (host) / the box stopped and kept (container); exit code 10/11.
      *Sweep result (2026-10-06):* hits — `GitModeRunner.java`, `GitResumeContinuation.java`
      (`readRecordedState(worktree)`, both inside the `Completed` arm), `ContainerTerminalDrive.java`
      (`readFinalState()`, after the park arm returned), and three non-boundary hits:
      `SandboxRunSupport` (the port declaration), `ContainerRunSupport` (its realization) and
      `ContainerResumeMechanics` (`take`'s resume, not a `run` boundary). No survivor.
      *Implementation notes:* first landed with the park's receipt (`confirmTerminalWrite`)
      following its outcome commit as a second commit on both media; design D8 was revised on
      2026-10-06 to record a `run` park with no marker at all — task 1.6 replaces that second
      commit. The container half of the spec is `RunParkRecordingContainerE2ESpec`
      (Docker-gated, Gitea origin). `RunKills` (`:bootstrap` test) is the in-process kill before
      the park's outcome commit that the resume features — and the three E2E specs that used to
      "kill" through an EOF at the prompt — start from.
- [x] 1.5 Kill-point specs for the park (design "Crash consistency of the park", NFR-R3), for the
      windows this change owns: `RunParkKillPointSpec` kills after the outcome commit (before the
      push) and, in the container, after the push (before `keepStopped`), for an
      `AttemptsExhausted` escalation and a `paused` park on both media; runs `--resume`; asserts
      the shape named in the design table, the convergence (the park is delivered), and that a
      recovery pass over the same frozen state is a no-op. The window before the outcome commit
      is asserted only for `AttemptsExhausted` (it converges by derivation — already the
      precondition of `RunParkRecordingSpec`'s resume features); for `paused`, `DecisionNeeded`
      and `CannotVerify` it is **not** specced here — it is the kill-point row of the follow-up
      change `make-checkpoint-gate-durable`, named as this change's dependency. Verify green on
      the bare origin fixtures.
      *Result (2026-10-06):* `RunParkKillPointSpec` (host) and `RunParkKillPointContainerE2ESpec`
      (Docker, Gitea) with `RunKillPoint`, `PushBlackout`, `ParkPipelines`, `ContainerParkSpecBase`
      (shared with `RunParkRecordingContainerE2ESpec`); `RunKills` generalized to `killedAt`. Every
      window × kind × medium asserts the frozen shape, the convergence and a no-op second recovery
      pass. *Defect found and fixed:* with the park committed factory-side, the container resume
      reattached the kept box whose in-box clone was behind the tip, and the fast-forward-only
      harvest refused it (`CannotExecute`). `ContainerResumeOutcomes.resumeEscalated` /
      `resumePaused` now call `support.disposeExistingEnvironment()` before continuing — the
      precedent is `TakeContainerResumeRunner.appendDecision` (FR17 / D12 of
      `harden-task-branch-contract`); pinned by `ContainerResumeRoutingSpec`. Task 2.3 must keep
      that call. The "after push, before keep" window is recovered through the production
      `keepStopped` (a running box is reused as-is by `reattach`; see design, corrected).
- [x] 1.6 Record a `run` park with no pending marker (design D8, revised 2026-10-06):
      `TaskRepository.recordOutcome(taskId, outcome, TrackerWrite)` with `TrackerWrite.OWED |
      NONE` (`:application`, `port`), implemented by `GitTaskRepository` and
      `GitObjectsTaskRepository` (both set `trackerWritePending` only for `OWED` and a non-Aborted
      outcome) and passed through by `PushBestEffortTaskLifecycleStore`. Callers, by name:
      `OWED` — `GitOutcomeRecorder.recordIntent` as reached from `TakeEngineExecution`,
      `ContainerRunTermination.recordPark` / `completeAndDispose` as reached from
      `TakeContainerEngineExecution`; `NONE` — `GitOutcomeRecorder.recordAndCleanUp`'s park arm
      (`GitModeRunner`, `GitResumeContinuation`) and `ContainerTerminalDrive.parkAndKeep`. Delete
      the `confirmTerminalWrite` call both `run` arms make today and the receipt branch in
      `GitOutcomeRecorder.recordAndCleanUp`. The `Completed` arm of `run` is unchanged: its marker
      leaves with the envelope in the cleanup commit that follows at once. Old-way sweep:
      `grep -rn "confirmTerminalWrite" */src/main` returns only `take`'s receipt sites
      (`TakeEngineExecution`, `TakeContainerEngineExecution`, `TakeLoadedBranchRoutes` /
      `TakeReconcile`, the mechanics and the port/adapters) — record the result here. Verify
      `RunParkRecordingSpec` and `RunParkRecordingContainerE2ESpec` assert `trackerWritePending`
      is absent on the tip (not merely cleared) and that exactly one lifecycle commit follows the
      last round commit; `GitOutcomeRecorderSpec` asserts no `confirmTerminalWrite` for a park;
      `GitTaskRepositorySpec` / `GitObjectsTaskRepositorySpec` cover both `TrackerWrite` values.
      *Sweep result (2026-10-06):* `confirmTerminalWrite` survives only at `take`'s receipt sites
      (`TakeEngineExecution`, `TakeContainerEngineExecution`, `TakeLoadedBranchRoutes`,
      `TakeReconcile`), the resume mechanics (`ResumeMechanics`, `HostResumeMechanics`,
      `ContainerResumeMechanics`, `ContainerResumeBootstrap`), the ports (`SandboxRunSupport`,
      `TaskLifecycleStore`) and the adapters (`ContainerRunTermination`, `ContainerRunSupport`,
      `GitTaskRepository`, `GitObjectsTaskRepository`, `PushBestEffortTaskLifecycleStore`,
      `TerminalWriteMarker`); both `run` arms' calls removed. `recordOutcome(` / `recordPark(`:
      no two-argument form remains in any source set; `SandboxRunSupport.recordPark` is shared
      by `take` and `run`, so it takes the `TrackerWrite` from its caller. `Aborted` arms pass
      `OWED` (the adapter never sets the marker for `Aborted`; documented in place).

## 2. Resume by flags

- [x] 2.1 Add `--decision=<text>` to `RunArguments` / `RunArgumentsParser` (design D2):
      requires `--resume`, rejected with `--mode=in-place`, blank text is a usage error.
      Verify `RunArgumentsParserSpec` data table: with resume / without resume / with in-place /
      blank.
      *Result (2026-10-06):* `GitFlagsValidator.parseDecision` (blank → in-place → requires
      `--resume`, all before the task-source check so the conflict is named); `--decision` is the
      last record component. PIT on `:application` 100%.
- [x] 2.2 Replace `EscalationResumeDialog` by `EscalationResume`: `decide(context, escalated,
      @Nullable decision)` returns the `Resumption` (append + reset, or reset only) and throws
      `DecisionRequiredException` for a null decision over `DecisionNeeded`; `RunExitCodeMapper`
      maps that exception to 10 after `TerminalOutcomeRender` restates the question (FR4).
      Verify a spec table over the five report kinds × decision present/absent.
      *Result (2026-10-06):* `EscalationResume(console, clock, returnPath)` holds the console for
      the restatement print only (never reads); `DecisionRequiredException` carries the outcome
      and the `ReturnPath`, mapped to 10, INFO return-path line in `RunExceptionReporting`.
      `EscalationResumeDialog` and both callers are left for 2.3 (switching them to
      `decide(…, null)` is not behavior-preserving without the `--decision` plumbing). PIT 100%.
- [x] 2.3 Wire `GitResumeContinuation.resumeEscalated` / `resumePaused` and
      `ContainerResumeOutcomes`' escalated / paused arms to `EscalationResume` and
      `TerminalOutcomeRender`, passing the decision as the third argument of
      `ManualRunners.resume(order, taskId, decision)` → `GitResumeRunner` /
      `ContainerResumeRunner` (design D2; `RunOrder` is not widened). `resumePaused` no longer
      prints a checkpoint line at all: it continues. Both container arms keep the
      `support.disposeExistingEnvironment()` call added in task 1.5 before any branch write or
      round (the kept box's clone is behind the park commit; `ContainerResumeRoutingSpec` pins
      it); the host arm has no equivalent — its worktree is the branch. A `--decision` over a non-escalated
      recorded outcome raises `UsageException` in the resume router before any branch write
      (FR9). Rewrite both ends' `Kept in sync with` text per design D6. Verify
      `GitResumeRoutingSpec`, `ContainerResumeRoutingSpec`, `GitResumeOutcomeSpec` and
      `ContainerModeResumeE2ESpec` pass with their `ScriptedConsoleIO` decision lines turned
      into `--decision` resumes; NFR-R1: one commit per decision resume, asserted on the bare
      origin.
      *Result (2026-10-06):* `ResumeDecisionGuard.requireEscalatedFor` is the one FR9 check,
      shared by both routers; `EscalationResumeDialog` deleted; `GitResumeDecisionSpec` (bare
      origin) asserts NFR-R1. `ManualRunners.resume(order, taskId, decision)` →
      `GitResumeRunner.run(order, taskId, decision)` / `ContainerResumeRunner.run(order, taskId,
      decision, segments)`. Known: `ReferenceE2ESessionSpec` red until 4.1 (first process now
      exits 10). Open design points raised: see the orchestrator's notes after this task.
- [x] 2.4 Sweep for the second D7 row: `grep -rn "resetAttempts()" */src/main` returns
      `EscalationResume` and `TakeDecisionResume` only (the latter is the design's recorded
      exemption); record the result in this task.
      *Result (2026-10-06):* `EscalationResume.java:78` (owner), `TakeDecisionResume.java:69,89`
      (exemption), plus the declaration `TaskState.java:156` and a javadoc mention in
      `TaskRepository.java:95`. `EscalationResumeDialog`: zero hits in any source set.
- [x] 2.5 Gate: root `./gradlew check` green with `DialogConsole.prompt` still present but
      unused by `run`. (Reordered 2026-10-06: run after 4.1, since `ReferenceE2ESessionSpec`
      is red until its three-process rewrite.)
      *Result (2026-10-06):* root `check --continue` — compile, Error Prone/NullAway, Spotless
      green; PIT (branch scope) 100% (`:application` 2486, `:bootstrap` 440, `:adapters:git` 890).
      Fixed on the way: `RunParkKillPointContainerE2ESpec` now takes the epoch book from
      `TaskGitFixture.real().epochs()` (`ClaimlessGitBoundarySpec`); `CommittedTaskJson.text` renamed
      `committedText` (an ambiguous `text()` dropped out of `UntrustedTextSinkGateSpec`'s scan).
      Remaining red, not code: `DistributionLayoutSpec` (`git ls-files` lists the three unstaged
      deletions) and one flake of `ContainerDeclaredVolumesSpec` (a foreign anonymous volume from
      parallel Docker specs; green in isolation). `.prompt(` in `*/src/main`: only
      `Activity.AwaitingInput.prompt()` (task 3.2); `DialogConsole.prompt` has no caller.
- [x] 2.6 Make `recordOutcome` idempotent on identical content (design D8, "Idempotence";
      crash-consistency item 8; added 2026-10-06 after task 2.3 found the gap): in
      `GitTaskRepository` and `GitObjectsTaskRepository`, a `recordOutcome` whose `task.json`
      equals the tip's byte for byte makes no commit and returns normally (the tip already
      carries the record) instead of failing on git's "nothing to commit". Reachable today by a
      `--resume` without `--decision` over `AttemptsExhausted` whose rerun parks identically
      (the reset lives in memory only). Verify `GitTaskRepositorySpec` /
      `GitObjectsTaskRepositorySpec`: a second identical park → same tip, no new commit, no
      exception; a differing park → one new commit; `RunParkKillPointSpec`'s escalated row may
      drop the `--decision` workaround comment added in 2.3.
      *Result (2026-10-06):* one helper, `CommittedTaskJson.carries`, decides "unchanged" for both
      media from the tip text already read for the rewrite (no git error parsing, no extra git
      call); `RecordOutcomeIdempotenceSpec` (both media, 6 rows); `GitResumeDecisionSpec` pins
      the real flow (identical re-park → `RunParkedException`, one escalation commit, origin ==
      local); the `--decision` workaround in `RunParkKillPointSpec` removed. PIT `:adapters:git`
      890/890. Both repositories' `Kept in sync with` paragraphs gained the invariant.

## 3. Delete the console's input side

- [x] 3.1 Delete `DialogConsole.prompt`, `ask`, `readLine`, the `STATUS_COMMAND` /
      `STATUS_JSON_COMMAND` interception and both collaborator parameters, leaving one
      constructor `DialogConsole(ConsoleIO)` (design D4). Delete with them: the `ActivityTracker`
      port and `SnapshotActivityTracker` (whole classes), `ConsoleStatusRenderer` and the
      `StatusRenderer` port, `ResumeDialogConsoleFactory` (`:bootstrap`), the
      `SnapshotActivityTracker` construction in `RunAssembler`, `RecordingActivityTracker` and
      the `DialogConsoleSpec` features that drove prompts. Verify `DialogConsoleSpec` covers
      print/printMachine only, `StatusCommand` still renders text and JSON, and PIT is 100% on
      `DialogConsole`.
      *Result (2026-10-06):* deleted as listed (plus `ConsoleStatusRendererSpec`,
      `SnapshotActivityTrackerSpec`); `RunAssembly.dialogConsole(TaskContext, TaskState)` became
      `dialogConsole()` — its parameters fed only the deleted collaborators (callers:
      `ManualRunAssembly`, `GitResumeContinuation`, `ContainerResumeOutcomes`, `RunChainFakes`).
      `DialogConsoleSpec` one feature (print/printMachine); `StatusCommandSpec` 18/18; PIT
      `:application` 2471/2471, `:bootstrap` 439/439. Sweep: no hit for `ActivityTracker`,
      `StatusRenderer`, `ResumeDialogConsoleFactory`, `STATUS_COMMAND`, `.ask(`; `readLine(` only
      in the port, `SystemConsoleIO`, `ConsoleTakeoverConfirmation`, `BoundedTail` (subprocess
      reader) and test fakes; `.prompt(` only `Activity.AwaitingInput.prompt()` (task 3.2).
- [x] 3.2 Delete `Activity.AwaitingInput` with its arms in `StatusLineFormatter`,
      `StatusReportJsonMapper`, `ActivityDto.AwaitingInput` and any reader; remove the
      `Activity$AwaitingInput` row from `UntrustedTextGateSpec`'s allowlist; update the
      `StatusEventListener` / `LiveActivity` javadoc; update the `status-report` round-trip spec
      to iterate the two remaining variants and reject `awaitingInput` as unknown. Verify
      `StatusReportJsonMapperSpec` and `StatusReportEquivalenceContractSpec` green with the
      reference documents unchanged.
      *Result (2026-10-06):* variant, arms, DTO and allowlist row deleted; javadoc updated. No
      permits-iterating round-trip existed (design Risks assumed one): `StatusReportJsonMapperSpec`
      gained it (2 variants, wire tokens, DTO tree) plus `awaitingInput` → `InvalidTypeIdException`
      (Jackson's unknown-subtype default). `UntrustedTextSinkGateSpec` lost `prompt` from its
      ambiguous-names list (its only carrier was `AwaitingInput`). References unchanged;
      `StatusReportEquivalenceContractSpec` 3/3; PIT `:application` 100%. Left open:
      `StatusSnapshotHolder.current` and its feeders have no production caller (see 3.6).
- [x] 3.6 Delete the live report (design D4, added 2026-10-06; `status-report` "Fields
      partitioned by derivability", MODIFIED): `StatusSnapshotHolder.current(TaskContext)` and
      every member that, after it goes, has no production reader — expected `attemptLimit` /
      `updateAttemptLimit` with the constructor parameter and the initial-limit resolution in
      `RunAssembler`, `lastEscalation()`, `outcome()`, and whatever of `recordEscalation` /
      `recordOutcome` / `LiveActivity`'s escalation and outcome components then feeds nothing;
      decide each by its production readers (`AgentActivityEnricher` → `activity()`,
      `RunCheckRunContext` → `state()`), not by this list. Rewrite
      `AttemptBoundaryEquivalenceSpec` to the restated scenario (held state equals the persisted
      round's `TaskState`, activity idle) and update `StatusEventListenerSpec`'s `current` use.
      Old-way sweep: `git grep -n "\.current(\|attemptLimit()\|updateAttemptLimit"` over
      `*/src` — every hit removed or named with its production reader; record here.
      *Result (2026-10-06):* holder keeps `StatusSnapshotHolder(TaskState)`, `updateState`,
      `updateActivity`, `state()` (`RunCheckRunContext`), `activity()` → `@Nullable Activity`
      (`AgentActivityEnricher`); deleted `current`, the attempt limit and its seeding,
      `AttemptLimitResolver` (+spec), `lastEscalation`/`outcome`/`recordEscalation`/`recordOutcome`,
      `Outcome.from`; the listener's `TaskFinished` arm is a no-op. `LiveActivity` keeps its three
      components (`BranchStateReader` fills escalation and outcome from `task.json`).
      `AttemptBoundaryEquivalenceSpec` rewritten to the restated scenario. Targeted specs green
      (`:application` status/app, `:adapters:git` equivalence + `BranchStateReaderSpec`,
      `:bootstrap` assembly/run-check). Sweep: every holder hit removed; remaining
      `attemptLimit()` hits are `StatusReport`'s (rendered by `gnomish status`) and the pipeline
      stage limits; remaining `.current(` hits are unrelated (`FactoryVersion`, leases, listings,
      `ProcessHandle`).
- [x] 3.7 Delete the live activity and the attempt limit from the report (design D4, extended
      2026-10-06; `status-report` "JSON contract v1, state-derived" ADDED, "JSON contract v1" and
      "Fields partitioned by derivability" REMOVED, "Single report model" MODIFIED;
      `task-inspection` "External status reader" MODIFIED). Delete: `Activity` (sealed type) and
      `ActivityDto`; `AgentActivityEnricher` and its registration in `ExecutorAdapterSelector`
      (the composite keeps `LoggingAgentProgressListener`; fix `CompositeAgentProgressListener`'s
      javadoc); the activity arms of `StatusEventListener`, `StatusLineFormatter`,
      `StatusTextRenderer`, `StatusReportJsonMapper`; `StatusReport.activity` and
      `StatusReport.attemptLimit` with the `build` parameter (all 7 callers);
      `CurrentStageDto.attemptLimit`; the holder's activity (it keeps `TaskState` only, read by
      `RunCheckRunContext`). `LiveActivity` loses its activity component — what is left (recorded
      escalation + outcome) is no longer live: rename it after what it is or fold it into
      `StatusReport.build`'s parameters, whichever leaves fewer types. Remove
      `AttemptBoundaryEquivalenceSpec` if `StatusEventListenerSpec` already pins the fold's state
      at `AttemptFinished`, else keep that one assertion there. Update
      `status-report-v1.reference.json` and `StatusReportReferenceFixture` (no `activity`, no
      `attemptLimit`), the untrusted-text gate allowlists that name `Activity` members, and the
      specs of every class above. Old-way sweep: `git grep -n "Activity\b\|LiveActivity\|attemptLimit\|\"activity\""`
      over `*/src` and `docs/` — every hit removed or named (pipeline stage limits are a
      different `attemptLimit` and stay); record here.
      *Result (2026-10-06):* deleted `Activity`, `ActivityDto`, `AgentActivityEnricher`,
      `LiveActivity` (folded: `StatusReport.build(context, state, @Nullable lastEscalation,
      @Nullable outcome)`, no new term), `AttemptBoundaryEquivalenceSpec` (`StatusEventListenerSpec`
      pins the fold at `AttemptFinished`), `ActivityOutcomeSpec`, `AgentActivityEnricherSpec`.
      Transitively: `Run.holder` and `StatusEventListener`'s `Clock` (no production reader);
      `ExecutorAdapterSelector` passes `LoggingAgentProgressListener` directly (a one-element
      composite would remain otherwise; `CompositeAgentProgressListener` stays for
      `ExecutorRoundExecution`). Six production `build` callers (not seven: `AbortReportBuilder`
      only names it in javadoc). Reference JSON lost `activity` and `attemptLimit`; escalations
      corpus unchanged; `StatusReportEquivalenceContractSpec` now compares byte for byte. Targeted
      specs green in `:application`, `:adapters:git`, `:adapters:agent`, `:bootstrap`. Sweep (145
      hits): every live-activity and report-`attemptLimit` hit removed; kept — the withdrawal
      assertions (`StatusReportJsonMapperSpec`, gate-history comments), `WorktreeJanitor`'s
      `lastActivity`, and the pipeline stage limits (`AutonomyLimits`, mappers, `Engine`,
      `stage.yaml` fixtures).
- [x] 3.3 Extend `ConsoleOwnerGateSpec` (`:bootstrap`): `readLine(` in `*/src/main` appears
      only in `SystemConsoleIO` and `ConsoleTakeoverConfirmation` (third D7 row). Add
      `TerminalRenderOwnerSpec`: the literals `Manual checkpoint reached` and the return-path
      prefix each appear in exactly one production file, `TerminalOutcomeRender.java` (first D7
      row, `take` included). Verify both specs red when a copy is planted in a scratch file and
      green on the tree.
      *Result (2026-10-06):* `ConsoleOwnerGateSpec` gained `READ_LINE_SITES` — a whole-tree name
      scan (`\breadLine\b`, so `io::readLine` is caught) with reasons: `SystemConsoleIO`,
      `ConsoleTakeoverConfirmation`, the `ConsoleIO` port, and two subprocess readers
      (`BoundedTail`, `StreamJsonParser`, asserted not to mention `ConsoleIO`); set equality, so a
      stale row fails too; `test-fixtures` excluded as in the existing gate.
      `TerminalRenderOwnerSpec` pins `Manual checkpoint reached` and `To continue: gnomish run`
      (the prefix without its flags, so a copy cannot dodge by reordering) to
      `TerminalOutcomeRender.java`, comments stripped. Planted `ScratchHandCopy.java` → 3 red
      features naming it; removed → green (17 + 6).
- [x] 3.4 Old-way sweep: `grep -rn "Press Enter\|Decision (empty\|\.prompt(\|\.ask(" */src/main`
      returns nothing; `grep -rn "ConsoleClosedException" */src/main` returns only the port, its
      owner `SystemConsoleIO` and `ConsoleTakeoverConfirmation` (revised 2026-10-06: the
      `RunExceptionReporting` `INPUT_EXHAUSTED` line was found unreachable — the takeover
      confirmation catches its own EOF — and deleted); `grep -rln "ScriptedConsoleIO" */src/test`
      lists only specs asserting output. Record all three in this task (M1, M2).
      *Result (2026-10-06):* sweep 1 — no hit. Sweep 2 — the port (`ConsoleClosedException`,
      `ConsoleIO`), `SystemConsoleIO`, `ConsoleTakeoverConfirmation`, and the `ScriptedConsoleIO`
      fake in `test-fixtures`; `ConsoleClosedException`'s stale "flows through the engine"
      javadoc rewritten; `INPUT_EXHAUSTED` and its three spec rows deleted (the exception now
      falls to the generic path, unreachable in production). Sweep 3 — 17 files: output-only
      users, four FR6 "never read" specs overriding `readLine`, and `ScriptedConsoleIOSpec`;
      `ScriptedConsoleIO` lost its scripted-input parameter (only its own spec fed it) and reads
      as EOF; name kept (proposal Impact names it). M1 — console reads only in
      `ConsoleTakeoverConfirmation` and `SystemConsoleIO`, pinned by `ConsoleOwnerGateSpec`
      (`READ_LINE_SITES`); M2 — met.
- [x] 3.5 Update `ManualRunRunner.IN_PLACE_REMINDER` (`:bootstrap`) per design D5 and its spec.
      *Result (2026-10-06):* "in-place mode: no git, no resume — the task's state lives in memory
      only and dies with this process; an escalation or a checkpoint ends the task for good."
      `ManualRunRunnerSpec` asserts equality with the constant plus the three facts; no E2E spec
      asserts the reminder.

## 4. Journeys and documentation

- [x] 4.1 Rewrite `ReferenceE2ESessionSpec` as three processes with empty stdin
      (M3): run → exit 10 with the question and the return-path line on stdout; `--resume
      --decision` → quality retry, pause, exit 11 with the checkpoint line; `--resume` → exit 0.
      Rewrite the `ExitCodeMatrixSpec` rows for 10 and 11 as outcome exits (no Ctrl-D) and add
      "unanswered DecisionNeeded resumed without --decision exits 10 again with the question
      restated and no round run". Verify `e2eTest` green.
      *Result (2026-10-06):* `ReferenceE2ESessionSpec` moved to git mode (host binding, own
      published clone; in-place cannot resume) — three closed-stdin processes, exits 10/11/0,
      origin commit subjects asserted per step, NFR-S1 restated as "the operator's clone gains
      nothing". The 10/11 rows moved out of `ExitCodeMatrixSpec` (already over the 200-line cap)
      into `ParkExitCodeSpec` (in-place escalation and checkpoint, plus the FR4 restatement row in
      git mode: tip unchanged). Clone-publishing helpers moved to `E2eGitTree`. E2E specs run
      under `:bootstrap:test` (no separate `e2eTest` task): all green.
- [x] 4.2 `docs/guides/operator-guide-run.md`: replace the dialog paragraphs ("The operator's
      part is the escalation decision and the manual-checkpoint confirmation at the prompt",
      "At any prompt you can type status…", "escalated → re-opens the decision dialog",
      "paused → asks for confirmation") with the headless behavior and the park on the branch;
      add the section "Driving `run` from a script or an agent" with the table exit code → next
      command; add `--decision` to the flag table; the in-place paragraph states the loss (UX1,
      UX2). `README.md` `run` paragraph and `docs/glossary.md` ("Park" entry: `run` parks on its
      branch, with no tracker status) updated. Verify
      `grep -rn "prompt\|Press Enter\|dialog" docs/guides/operator-guide-run.md README.md` returns
      only the takeover `[y/N]` mention and the "Git never prompts" note.
      *Result (2026-10-06):* run guide — headless intro, `--decision` row and usage errors,
      status from another terminal, in-place loss, resume by recorded outcome (incl. aborted →
      exit 2), new section "Driving `run` from a script or an agent" (park, last lines, exit
      code → next command table), exit-table rows 10/11, a "What reaches origin" bullet for the
      `run` park; `README.md` `run` paragraph; glossary "Park", "Escalation", "Operator console";
      `operator-guide-inspect.md` and `operator-guide-serve.md` lost their dialog/in-process
      status mentions. Task grep: only "Git never prompts" and `README.md:76` (stage prompt
      files); the takeover `[y/N]` lives in `operator-guide.md`, outside the grep. Found: the
      printed return path used `--dir <dir> --resume <task>`, which the Spring option parser
      rejects (exit 2) — task 4.5.
- [x] 4.3 `.claude/rules/manual-sync-pairs.md`: no registry row is touched; add a sentence under
      the envelope-names paragraph recording that the five-copy checkpoint sentence was
      dissolved into `TerminalOutcomeRender` by this change rather than declared (design D3,
      D6). Verify the file renders.
      *Result (2026-10-06):* the envelope-names paragraph gained the checkpoint sentence's
      provenance (five hand copies → `TerminalOutcomeRender`, with the return-path line; held by
      `TerminalRenderOwnerSpec`). No registry row names anything this change deleted; tables intact.
- [x] 4.5 Make the printed return path runnable (FR1, UX1; added 2026-10-06 when task 4.2 found
      `To continue: gnomish run --dir <dir> --resume <task>` fails with "--dir requires a value",
      exit 2 — the CLI takes values only as `--key=value`): `TerminalOutcomeRender.ReturnPath`
      prints `--dir=<dir> --resume=<task>` (shell-quoting of the dir kept) and the optional
      `[--decision="..."]`; update `TerminalOutcomeRenderSpec`, `TerminalRenderOwnerSpec`'s literal
      if it changes, and every spec comparing the line. `ReferenceE2ESessionSpec` (or
      `ParkExitCodeSpec`) takes the resume command **from the stdout line** of the exit-11 run and
      executes it, instead of building `--resume=` itself — the spec that would have caught this.
      Also fix the stale `TerminalPark` javadoc ("record a `pendingTrackerWrite` marker"; `run`
      parks record `TrackerWrite.NONE`). Check the guide's commands still match.
      *Result (2026-10-06):* defect confirmed (`DefaultApplicationArguments` reads `--dir /x` as a
      valueless option; red E2E reproduced it). Line now `To continue: gnomish run
      --dir=<dir> --resume=<task> [--decision="..."]`, shell quoting after the `=`.
      `TerminalOutcomeRenderSpec` splits the line through `sh -c printf` and parses it with the
      CLI's own parser (plain, space, apostrophe, `$HOME` dirs); `ReferenceE2ESessionSpec` runs
      both resumes from the printed lines; `ParkExitCodeSpec`'s in-place check now
      `!contains('To continue:')`. `TerminalPark` javadoc fixed; guide quotes the line once.
- [x] 4.4 Gate: root `./gradlew check` green; record M1–M3 in this task.
      *Result (2026-10-06):* root `check --continue` 1 h 27 min — compile, Error Prone/NullAway,
      unused-code, dependency analysis, Spotless and every gate spec green; two stale return-path
      comparisons missed by 4.5 (`ContainerResumeRoutingSpec`, `GitModeRunnerFreshRunSpec`) fixed,
      then `:application:check` green (2706 tests, PIT 2430/2430). PIT 100% in every module
      (`:adapters` 996, `:adapters:git` 890, `:adapters:github` 600, `:sandbox:docker` 600,
      `:bootstrap` 438, `:domain` 347, `:adapters:agent` 298, plugin API 115, `:sandbox:core` 89,
      `gitobjects` 84). Only red: `ContainerDeclaredVolumesSpec` (2 features) — the known flake
      under parallel Docker specs (whole-daemon volume set), green in isolation, file untouched.
      M1 — `ConsoleOwnerGateSpec` 17/17; console reads only `ConsoleTakeoverConfirmation` and
      `SystemConsoleIO`. M2 — no `Press Enter` in production; `Manual checkpoint reached` only in
      `TerminalOutcomeRender.java` (`TerminalRenderOwnerSpec` 6/6). M3 — `ReferenceE2ESessionSpec`
      1/1: three closed-stdin processes exit 10, 11, 0.

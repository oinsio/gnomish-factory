## 0. Precondition and fresh baseline

- [ ] 0.1 Verify the working tree is clean and `add-base-ref-resolution` is committed —
      this change edits 42 signatures and cannot be told apart from uncommitted work on
      another change otherwise.
- [ ] 0.2 Verify `fix-claim-epoch-fence` has landed and re-take the parameter-count scan;
      record the fresh baseline beside the 2026-09-12 figure of 63. Every metric in
      `proposal.md` is stated against the old scan — if the fresh count differs, correct the
      metrics through `/opsx:update` before implementing, rather than reporting against a
      stale number.
- [ ] 0.3 Verify `TakeDispositionResume.afterReconciliation` is gone (deleted by
      `fix-claim-epoch-fence`); if it still exists, stop and reconcile the consumer table.

## 1. The two order types

- [ ] 1.1 Add `RunOrder` as a record in `:application` `com.github.oinsio.gnomish.app` with
      exactly the five components of FR1 (`Path cloneDir`, `@Nullable String base`,
      `PipelineDefinition definition`, `RunArguments.InteractiveMode interactiveMode`,
      `boolean discardWork`), javadoc carrying `Implements FR1 of introduce-take-order`;
      verify `:application:compileJava` passes and the record has no method beyond its
      accessors at this point.
- [ ] 1.2 Add `TakeOrder` as a record beside it with the four components of FR2 (`RunOrder
      run`, `TrackerTask trackerTask`, `Tracker tracker`, `InstanceId instanceId`) plus the
      two derivations of FR3/D2 — `TaskRef ref()` and `String taskId()`; verify a new
      `TakeOrderSpec` asserts both derivations against a built `TrackerTask` and that
      `taskId()` equals `trackerTask.snapshot().id()`.
- [ ] 1.3 Add glossary entries for the new domain terms (*order*, *run order*, *take
      order*) to `docs/glossary.md` under the Task coordination context, per
      `process-invariants.md`; verify each entry states what the term means and names the
      type, and that no banned synonym is introduced.

## 2. Manual-run paths take `RunOrder` (D5)

- [ ] 2.1 Change `GitModeRunner.run:106` and `ContainerGitModeRunner.run:68` to take one
      `RunOrder`, keeping both ends of the declared pair identical; verify their existing
      specs pass with no expectation edited (NFR-R1).
- [ ] 2.2 Change `GitResumeRunner.run:100`, `GitResumeRunner.continueFrom:139` and
      `ContainerResumeRunner.run:86` to take one `RunOrder`, keeping that declared pair
      identical; verify the resume specs pass unedited.
- [ ] 2.3 Change `ContainerResumeOutcomes.resumeFromRecordedPosition:37`,
      `ContainerResumeOutcomes.resumePaused:111` and `ContainerTerminalDrive.run:29` to
      take `RunOrder`; verify each drops below eight parameters and the container resume
      specs pass unedited.
- [ ] 2.4 Change `RunAssembler.assemble:68` (`:bootstrap`) to take `RunOrder` in place of
      `definition` and `interactiveMode`; verify it drops from eight parameters to seven,
      and that the claim `add-base-ref-resolution`'s design makes about this signature —
      corrected 2026-09-13 to say it is one over the limit — now holds.

## 3. Tracker-driven chain takes `TakeOrder`

- [ ] 3.1 Change the entry points — `TakeDisposition.dispose:132`, `TakeTakeover.take:67`,
      `TakeBareAuto.run:129`, `BareTakeClaimWalk.resolve:46` and the `TakeSlotRunner`
      constructor's order fields — to build and pass one `TakeOrder`; verify the take and
      serve specs pass unedited.
- [ ] 3.2 Change the claim layer — `TakeClaimAndWork.claimAndWork:114` and
      `dispatchAfterClaim:179`, `TakeCrashAbort.onCrash:78` — to take `TakeOrder`; verify
      `TakeClaimAndWork`'s specs pass unedited and both methods drop to three parameters
      or fewer.
- [ ] 3.3 Change the routing layer — `TakeWorkRouter.locateAndWork:37`, `freshClaim:78`,
      `resume:132`, and `TakeDispositionResume.resumeExisting:45`, `routeByShape:71`,
      and `TakeLoadedBranchRoutes.route:42` — to take
      `TakeOrder`; verify the routing specs pass unedited.
- [ ] 3.4 Change the fresh-claim pair — `TakeFreshClaim.claim:62`/`claimAt:114` and
      `TakeContainerFreshClaim.claim:44`/`claimAt:96` — to take `TakeOrder`, editing both
      ends together (FR6); verify each of the four drops from 15–16 parameters and that
      both classes' specs pass unedited.
- [ ] 3.5 Change the resume pair — `TakeResumeRunner.resumeWithoutDecision:119`/
      `resumeDecided:169` and `TakeContainerResumeRunner.resumeWithoutDecision:91`/
      `resumeDecided:138` — plus `TakeResumeBootstrap` / `TakeContainerResumeBootstrap`, to
      take `TakeOrder`, both ends together; verify their specs pass unedited.
- [ ] 3.6 Change the `ResumeMechanics<B>` interface's `resumeWithoutDecision` and
      `resumeDecided` to take `TakeOrder` (D4) and follow with both implementations,
      `HostResumeMechanics:85,105` and `ContainerResumeMechanics:53,77`; verify the
      interface's contract specs pass unedited and both methods drop to three parameters.
- [ ] 3.7 Change the engine-execution pair — `TakeEngineExecution.run:120` and
      `TakeContainerEngineExecution.run:78` — and the decision-resume path
      `TakeDecisionResume.resume:64`/`ackAndResume:107` and `TakeReconcileFinish
      .deliverCompleted:60`, to take `TakeOrder`; verify their specs pass unedited.
- [ ] 3.8 Change the terminal-report and park-guard sites — `TakeFinishReport.finish:134`,
      `TakePauseExit.finish:116`, and `GuardedPark` ctor:59 with `attempt:103` — to take
      `TakeOrder`; verify each drops from eight or nine parameters to seven or fewer and
      their specs pass unedited. These four were over the limit and named in no change
      until the 2026-09-13 consumer re-verification.

## 4. Sync-pair bookkeeping (FR6)

- [ ] 4.1 Re-read the `Kept in sync with` sentence on both ends of all seven pairs listed
      in the design's Sync surfaces table and update any that names a parameter the change
      removed; verify `grep -rn "Kept in sync with" */src/main` still enumerates fourteen
      markers and that each names its twin with a resolvable `{@link}`.
- [ ] 4.2 Update the affected rows of `.claude/rules/manual-sync-pairs.md` where the
      synchronized-invariant text describes a parameter list that no longer exists; verify
      each updated row names an invariant that is still true after this change.

## 5. Old-way sweep and enforcement (design's single-owner table)

- [ ] 5.1 Run `grep -rn "snapshot().id()" application/src/main bootstrap/src/main` and
      route or delete every hit; verify the only survivor is `TakeOrder.taskId()`'s own
      body, and record the grep and its hits in the task report per
      `.claude/rules/implementation.md`.
- [ ] 5.2 Run the parameter-count scan over `src/main` and record the new count; verify
      the signatures listed in the design's consumer table no longer appear and that the
      total has fallen from 63 to 44 (M2) — the 19 violations this change resolves.
- [ ] 5.3 Verify NFR-S1 by checking that no order type is passed whole to a logger and
      that no new `toString` of a credential-bearing field exists; record the grep used.

## 6. Gates and downstream

- [ ] 6.1 Run `./gradlew :application:check :bootstrap:check` — Spotless, Error Prone /
      NullAway, Spock, JaCoCo and PIT — and verify every gate passes with no spec
      expectation edited (NFR-R1, M3) and the mutation score unchanged (M4).
- [ ] 6.2 Update the task text of the queued changes that edit these signatures —
      `add-claim-return`, `fix-claim-epoch-fence`, `add-pipeline-routing` — through
      `/opsx:update`; verify each names the post-refactor signature, and report any whose
      plan the refactor invalidated rather than merely renamed.
- [ ] 6.3 Recommend a Conventional Commits subject line for the diff since the last commit
      (the agent never commits, `process-invariants.md`).

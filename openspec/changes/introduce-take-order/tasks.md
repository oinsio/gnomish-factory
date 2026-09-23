## 0. Precondition and fresh baseline

- [ ] 0.1 Verify the working tree is clean — this change edits over forty signatures and
      cannot be told apart from uncommitted work on another change otherwise. (Both
      predecessors are archived: `add-base-ref-resolution` 2026-09-13, `fix-claim-epoch-fence`
      2026-09-14.)
- [ ] 0.2 Re-take the parameter-count scan and record the fresh baseline beside the
      2026-09-12 figure of 63, which predates `fix-claim-epoch-fence`. Every metric in
      `proposal.md` is stated against the old scan — if the fresh count differs, correct the
      metrics through `/opsx:update` before implementing, rather than reporting against a
      stale number.
- [ ] 0.3 Verify `grep -rn "afterReconciliation\|StaleEpoch" application bootstrap` returns
      nothing (it did on 2026-09-23); a hit means the consumer table must be reconciled
      before any signature is edited.
- [ ] 0.4 Re-verify every `Class.method:line` reference in `design.md` and this file against
      the current tree (they were last regenerated 2026-09-23 at `0b631240`); correct any
      that has drifted before handing a task to a sub-agent, since the task text is the only
      pointer it receives.

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

*Call-site-only edits* (NFR-R1), used in every task below: a spec file changes only where it
constructs or invokes a signature from the design's consumer table — it builds an order and
passes it. No `then:` block, no `where:` table, no asserted log line, no `LogCaptureSupport`
attachment and no expected console or tracker output changes. 48 spec files in
`:application` and `:bootstrap` are such call sites (counted 2026-09-23); editing them is
expected, editing an expectation stops the task.

- [ ] 2.1 Change `GitModeRunner.run:109` and `ContainerGitModeRunner.run:70` to take one
      `RunOrder`, keeping both ends of the declared pair identical; verify their existing
      specs pass with call-site-only edits (NFR-R1).
- [ ] 2.2 Change `GitResumeRunner.run:103`, `GitResumeRunner.continueFrom:149` and
      `ContainerResumeRunner.run:86` to take one `RunOrder`, keeping that declared pair
      identical; verify the resume specs pass with call-site-only edits (NFR-R1).
- [ ] 2.3 Change `ContainerResumeOutcomes.resumeFromRecordedPosition:48`,
      `ContainerResumeOutcomes.resumePaused:122`, their declared twins
      `GitResumeContinuation.resumeFromRecordedPosition:76` /
      `GitResumeContinuation.resumePaused:129` (both ends of the pair, FR6) and
      `ContainerTerminalDrive.run:29` to take `RunOrder`; verify each drops below eight
      parameters and both the host and the container resume specs pass with call-site-only edits (NFR-R1).
- [ ] 2.4 Change `RunAssembler.assemble:68` (`:bootstrap`) to take `RunOrder` in place of
      `definition` and `interactiveMode`; verify it drops from eight parameters to seven,
      and that the claim `add-base-ref-resolution`'s design makes about this signature —
      corrected 2026-09-13 to say it is one over the limit — now holds.
- [ ] 2.5 Change `ManualRunDrive.driveResume` and `ManualRunDrive.driveGit` (`:bootstrap`) to
      build one `RunOrder` from the parsed `RunArguments` and the loaded definition and pass
      it to the four runners of 2.1–2.2 — the only place a `RunArguments` becomes a
      `RunOrder` (D1); verify no runner call in `:bootstrap` still spells `runArguments.dir()`
      or `runArguments.base()` and the manual-run E2E specs pass with call-site-only edits (NFR-R1).

## 3. Tracker-driven chain takes `TakeOrder`

- [ ] 3.1 Change the entry points — `TakeDisposition.dispose:128` and `TakeTakeover.take:68`
      — to build and pass one `TakeOrder`. The three pre-claim sites — `TakeBareAuto.run:126`,
      `BareTakeClaimWalk.resolve:47` and the `TakeSlotRunner` constructor's order fields —
      take a `RunOrder` instead (no `TrackerTask` exists there yet; `tracker` and
      `instanceId` stay separate until `introduce-slot-wiring`), built by their callers
      `TakeDispatcher.runBare:163` (from `TakeArguments`) and `ServeAssembly:63` (from the serve
      arguments) — the take-side assembly points of the design table, and the `TakeOrder` is
      assembled at the two points named in the design table: `BareTakeClaimWalk.resolve`
      right after `fetchTask`, before `dispatchAfterClaim`, and `TakeSlotRunner.run` right
      after `fetchTask`; verify the take and serve specs pass with call-site-only edits (NFR-R1).
- [ ] 3.2 Change the claim layer — `TakeClaimAndWork.claimAndWork:108` and
      `dispatchAfterClaim:173`, `TakeCrashAbort.onCrash:79` — to take `TakeOrder`; verify
      `TakeClaimAndWork`'s specs pass with call-site-only edits (NFR-R1) and both methods drop to three parameters
      or fewer.
- [ ] 3.3 Change the routing layer — `TakeWorkRouter.locateAndWork:37`, `freshClaim:78`,
      `resume:132`, and `TakeDispositionResume.resumeExisting:45`, `routeByShape:71`,
      and `TakeLoadedBranchRoutes.route:42` — to take
      `TakeOrder`; verify the routing specs pass with call-site-only edits (NFR-R1).
- [ ] 3.4 Change the fresh-claim pair — `TakeFreshClaim.claim:62`/`claimAt:114` and
      `TakeContainerFreshClaim.claim:44`/`claimAt:96` — to take `TakeOrder`, editing both
      ends together (FR6); verify each of the four drops from 15–16 parameters and that
      both classes' specs pass with call-site-only edits (NFR-R1).
- [ ] 3.5 Change the resume pair — `TakeResumeRunner.resumeWithoutDecision:122`/
      `resumeDecided:172` and `TakeContainerResumeRunner.resumeWithoutDecision:91`/
      `resumeDecided:138` — plus `TakeResumeBootstrap` / `TakeContainerResumeBootstrap`, to
      take `TakeOrder`, both ends together; verify their specs pass with call-site-only edits (NFR-R1).
- [ ] 3.6 Change the `ResumeMechanics<B>` interface's `resumeWithoutDecision` and
      `resumeDecided` to take `TakeOrder` (D4) and follow with both implementations,
      `HostResumeMechanics:75,95` and `ContainerResumeMechanics:53,77`; verify the
      interface's contract specs pass with call-site-only edits (NFR-R1) and both methods drop to three parameters.
- [ ] 3.7 Change the engine-execution pair — `TakeEngineExecution.run:120` and
      `TakeContainerEngineExecution.run:89` — and the decision-resume path
      `TakeDecisionResume.resume:65`/`ackAndResume:108` and `TakeReconcileFinish
      .deliverCompleted:60`, to take `TakeOrder`; verify their specs pass with call-site-only edits (NFR-R1).
- [ ] 3.8 Change the terminal chain to carry `TakeOrder` from the engine result to the park
      guard — the three intermediate callers `TakeOutcomeDispatch.dispatch:55`,
      `TakeEscalationExit.exit:110`, `TakeReconcile.deliverPark:94`, and the four ends
      `TakeFinishReport.finish:140`, `TakePauseExit.finish:119`, `GuardedPark` ctor:64 with
      `attempt:109` — replacing the `tracker, ref, instanceId` triple in each; verify the
      four ends drop from eight or nine parameters to seven or fewer, `dispatch` drops from
      eleven to nine (still over the limit — recorded in the design, not resolved here),
      and their specs pass with call-site-only edits (NFR-R1). The four ends were over the limit and named in no
      change until the 2026-09-13 re-verification; the three intermediates were added on
      2026-09-23 because without them the order cannot reach the ends.
- [ ] 3.9 Change the two identity consumers that receive a `TrackerTask` only to read its
      id — `TaskTierLaw.bind:103` (called from both `claimAt` sites of 3.4) and
      `TakeQuarantinePark.onQuarantine:52` (called from `TakeClaimAndWork.dispatchAfterClaim`)
      — to take `TakeOrder` and read `order.taskId()` / `order.ref()`; verify no
      `TrackerTask` parameter survives on either and their specs pass with call-site-only edits (NFR-R1).

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
- [ ] 5.2 Run the parameter-count scan over `src/main` with the same scanner as 0.2 and
      record the new count; verify the signatures listed in the design's consumer table no
      longer appear and that the total equals the fresh baseline from 0.2 minus the number
      of consumer-table signatures that baseline showed over the limit (M2). The 2026-09-12
      figures — 63 to 44, 19 resolved — are the stale scan's and are not the pass criterion.
- [ ] 5.3 Verify NFR-S1 by checking that no order type is passed whole to a logger and
      that no new `toString` of a credential-bearing field exists; record the grep used.

## 6. Gates and downstream

- [ ] 6.1 Run `./gradlew :application:check :bootstrap:check checkLogExpectationGate` —
      Spotless, Error Prone / NullAway, Spock, JaCoCo, PIT, and the build-wide
      log-expectation gate, which is a root task no module's `check` runs (NFR-O1) — and
      verify every gate passes and the mutation score is unchanged (M4). Then verify
      call-site-only edits mechanically: `git diff HEAD --stat -- '*/src/test/*'` lists only
      spec files that construct or invoke a consumer-table signature, and reading each
      `src/test` hunk shows no `then:`, `where:`, log-capture or expected-output change
      (NFR-R1, M3). UX1 is evidenced by the same run: the `:bootstrap` E2E and console specs
      that assert console output, log lines and tracker writes pass with call-site-only
      edits, so no operator-visible surface moved.
- [ ] 6.2 Update the task text of the queued changes that edit these signatures —
      `add-claim-return`, `add-pipeline-routing` — through `/opsx:update`; verify each
      names the post-refactor signature (for `add-claim-return`, that `ClaimIdentity` is
      derived from the order at the release sites, D4), and report any whose plan the
      refactor invalidated rather than merely renamed. `fix-claim-epoch-fence` is archived
      and immutable; it is not on this list.
- [ ] 6.3 Recommend a Conventional Commits subject line for the diff since the last commit
      (the agent never commits, `process-invariants.md`).

## 0. Precondition and fresh baseline

- [x] 0.1 Verify the working tree is clean — this change edits over forty signatures and
      cannot be told apart from uncommitted work on another change otherwise. (Both
      predecessors are archived: `add-base-ref-resolution` 2026-09-13, `fix-claim-epoch-fence`
      2026-09-14.)
- [x] 0.2 Re-take the parameter-count scan and record the fresh baseline beside the
      2026-09-12 figure of 63, which predates `fix-claim-epoch-fence`. Every metric in
      `proposal.md` is stated against the old scan — if the fresh count differs, correct the
      metrics through `/opsx:update` before implementing, rather than reporting against a
      stale number.
      *Fresh baseline, 2026-09-23 at `ceec1e30`:* **65** (was 63 on 2026-09-12) — 49
      `:application`, 6 `:bootstrap`, 4 `adapters`, 6 `sandbox`. Scanner: the JDK compiler
      tree API (`JavacTask.parse`) over every `*/src/main/java/**/*.java`, counting method
      and constructor declarations with more than seven parameters; members declared inside
      a `record` and methods annotated `@Override` are excluded, interface abstract methods
      are counted. Consumer-table signatures over the limit in this scan: **27** — 21 that
      drop to seven or fewer (`ContainerResumeOutcomes` ×2, `ContainerTerminalDrive.run`,
      `RunAssembler.assemble`, `TakeDisposition.dispose`, `TakeClaimAndWork` ×2,
      `TakeWorkRouter` ×3, the four resume-runner methods,
      the two `ResumeMechanics` interface declarations, `TakeTakeover.take`,
      `TakeFinishReport.finish`, `TakePauseExit.finish`, `GuardedPark` ×2) and 6 that stay
      over (`TakeOutcomeDispatch.dispatch` 11 → 9, the `TakeSlotRunner` constructor 16 →
      15: it carries only `cloneDir` and `definition` today, and the four fresh-claim
      methods 15–16 → 9–10: seven order fields fold into one, and the rest is slot wiring).
      Expected after the change: 65 − 21 = **44** (corrected 2026-09-24: the plan first
      counted the fresh-claim four as dropping and expected 40).
- [x] 0.3 Verify `grep -rn "afterReconciliation\|StaleEpoch" application bootstrap` returns
      nothing (it did on 2026-09-23); a hit means the consumer table must be reconciled
      before any signature is edited.
- [x] 0.4 Re-verify every `Class.method:line` reference in `design.md` and this file against
      the current tree (they were last regenerated 2026-09-23 at `0b631240`); correct any
      that has drifted before handing a task to a sub-agent, since the task text is the only
      pointer it receives.

## 1. The two order types

- [x] 1.1 Add `RunOrder` as a record in `:application` `com.github.oinsio.gnomish.app` with
      exactly the five components of FR1 (`Path cloneDir`, `@Nullable String base`,
      `PipelineDefinition definition`, `RunArguments.InteractiveMode interactiveMode`,
      `boolean discardWork`), javadoc carrying `Implements FR1 of introduce-take-order`;
      verify `:application:compileJava` passes and the record has no method beyond its
      accessors at this point.
- [x] 1.2 Add `TakeOrder` as a record beside it with the four components of FR2 (`RunOrder
      run`, `TrackerTask trackerTask`, `Tracker tracker`, `InstanceId instanceId`) plus the
      two derivations of FR3/D2 — `TaskRef ref()` and `String taskId()`; verify a new
      `TakeOrderSpec` asserts both derivations against a built `TrackerTask` and that
      `taskId()` equals `trackerTask.snapshot().id()`.
- [x] 1.3 Add glossary entries for the new domain terms (*order*, *run order*, *take
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

- [x] 2.1 Change `GitModeRunner.run:109` and `ContainerGitModeRunner.run:70` to take one
      `RunOrder`, keeping both ends of the declared pair identical; verify their existing
      specs pass with call-site-only edits (NFR-R1).
- [x] 2.2 Change `GitResumeRunner.run:103`, `GitResumeRunner.continueFrom:149` and
      `ContainerResumeRunner.run:86` to take one `RunOrder`, keeping that declared pair
      identical; verify the resume specs pass with call-site-only edits (NFR-R1).
- [x] 2.3 Change `ContainerResumeOutcomes.resumeFromRecordedPosition:48`,
      `ContainerResumeOutcomes.resumePaused:122`, their declared twins
      `GitResumeContinuation.resumeFromRecordedPosition:76` /
      `GitResumeContinuation.resumePaused:129` (both ends of the pair, FR6) and
      `ContainerTerminalDrive.run:29` to take `RunOrder`; verify each drops below eight
      parameters and both the host and the container resume specs pass with call-site-only edits (NFR-R1).
      *Applied 2026-09-23:* `resumeEscalated` on both ends of the pair and the private
      `GitResumeContinuation.runToTerminalBoundary` also take `RunOrder` — leaving them would
      make two arms of one declared pair take the order fields differently (G3).
- *2.4 moved to 3.10 on 2026-09-23* — `RunAssembler.assemble`'s only caller is the `@Override`
  `ManualRunAssembly.assemble`, which holds no `cloneDir`, `base` or `discardWork`, so an order
  cannot be built there without fabricated fields; the `RunAssembly.assemble` interface has to
  carry it, and two of its callers hold an order only after 3.7.
- [x] 2.5 Change `ManualRunDrive.driveResume` and `ManualRunDrive.driveGit` (`:bootstrap`) to
      build one `RunOrder` from the parsed `RunArguments` and the loaded definition and pass
      it to the four runners of 2.1–2.2 — the only place a `RunArguments` becomes a
      `RunOrder` (D1); verify no runner call in `:bootstrap` still spells `runArguments.dir()`
      or `runArguments.base()` and the manual-run E2E specs pass with call-site-only edits (NFR-R1).

## 3. Tracker-driven chain takes `TakeOrder`

- [x] 3.1 Change the entry points — `TakeDisposition.dispose:128` and `TakeTakeover.take:68`
      — to build and pass one `TakeOrder`. The three pre-claim sites — `TakeBareAuto.run:126`,
      `BareTakeClaimWalk.resolve:47` and the `TakeSlotRunner` constructor's order fields —
      take a `RunOrder` instead (no `TrackerTask` exists there yet; `tracker` and
      `instanceId` stay separate until `introduce-slot-wiring`), built by their callers
      `TakeDispatcher.runBare:163` (from `TakeArguments`) and `ServeAssembly:63` (from the serve
      arguments) — the take-side assembly points of the design table, and the `TakeOrder` is
      assembled at the two points named in the design table: `BareTakeClaimWalk.resolve`
      right after `fetchTask`, before `dispatchAfterClaim`, and `TakeSlotRunner.run` right
      after `fetchTask`, plus the explicit-mode `TakeDispatcher.runOneRef` right after its
      `fetchTask` (added 2026-09-24, it was missing from the design table). `runBare` keeps
      today's `discardWork = false` and `base = null` for bare take, even though the parser
      accepts `--discard-work` there; changing that would be a behavior change (NG4); verify the take and serve specs pass with call-site-only edits (NFR-R1).
- [x] 3.2 Change the claim layer — `TakeClaimAndWork.claimAndWork:108` and
      `dispatchAfterClaim:173`, `TakeCrashAbort.onCrash:79` — to take `TakeOrder`; verify
      `TakeClaimAndWork`'s specs pass with call-site-only edits (NFR-R1) and both methods drop to three parameters
      or fewer.
- [x] 3.3 Change the routing layer — `TakeWorkRouter.locateAndWork:37`, `freshClaim:78`,
      `resume:132`, and `TakeDispositionResume.resumeExisting:45`, `routeByShape:71`,
      and `TakeLoadedBranchRoutes.route:42` — to take
      `TakeOrder`; verify the routing specs pass with call-site-only edits (NFR-R1).
- [x] 3.4 Change the fresh-claim pair — `TakeFreshClaim.claim:62`/`claimAt:114` and
      `TakeContainerFreshClaim.claim:44`/`claimAt:96` — to take `TakeOrder`, editing both
      ends together (FR6); verify each of the four drops from 15–16 parameters and that
      both classes' specs pass with call-site-only edits (NFR-R1). Each `claimAt` re-binds
      the order to the task's law with `TakeOrder.withDefinition` (D6, added 2026-09-24), right
      after `TaskTierLaw.bind` returns `Bound`, and passes only the re-bound copy downstream.
      Add the wither (and `RunOrder.withDefinition` behind it) with a `TakeOrderSpec` feature,
      plus one new feature each in `TakeFreshClaimSpec` and `TakeContainerFreshClaimSpec`: the
      startup definition differs from the task's, and the engine must run the task's stage.
      These are new specs, not edits to an expectation.
- [x] 3.5 Change the resume pair — `TakeResumeRunner.resumeWithoutDecision:122`/
      `resumeDecided:172` and `TakeContainerResumeRunner.resumeWithoutDecision:91`/
      `resumeDecided:138` — to take `TakeOrder`, both ends together; verify their specs pass
      with call-site-only edits (NFR-R1). `TakeResumeBootstrap` / `TakeContainerResumeBootstrap`
      and `ResumeMechanics.loadBranch` stay unchanged (corrected 2026-09-24, design Sync
      surfaces): the host bootstrap is shared with manual `run --resume`, where FR5 forbids a
      `TakeOrder`.
- [x] 3.6 Change the `ResumeMechanics<B>` interface's `resumeWithoutDecision` and
      `resumeDecided` to take `TakeOrder` (D4) and follow with both implementations,
      `HostResumeMechanics:75,95` and `ContainerResumeMechanics:53,77`; verify the
      interface's contract specs pass with call-site-only edits (NFR-R1) and both methods drop to three parameters.
- [x] 3.7 Change the engine-execution pair — `TakeEngineExecution.run:120` and
      `TakeContainerEngineExecution.run:89` — and the decision-resume path
      `TakeDecisionResume.resume:65`/`ackAndResume:108` and `TakeReconcileFinish
      .deliverCompleted:60`, to take `TakeOrder`; verify their specs pass with call-site-only edits (NFR-R1).
- [x] 3.8 Change the terminal chain to carry `TakeOrder` from the engine result to the park
      guard — the three intermediate callers `TakeOutcomeDispatch.dispatch:55`,
      `TakeEscalationExit.exit:110`, `TakeReconcile.deliverPark:94`, and the four ends
      `TakeFinishReport.finish:140`, `TakePauseExit.finish:119`, `GuardedPark` ctor:64 with
      `attempt:109` — replacing the `tracker, ref, instanceId` triple in each, together with
      `TakeReconcileFinish.finishUncleaned` and the spec-only convenience overloads
      `TakeFinishReport.finish` (six and seven parameters), `TakePauseExit.finish` (six) and
      `TakeEscalationExit.exit` (four), all added 2026-09-24; verify the
      four ends drop from eight or nine parameters to seven or fewer, `dispatch` drops from
      eleven to nine (still over the limit — recorded in the design, not resolved here),
      and their specs pass with call-site-only edits (NFR-R1). The four ends were over the limit and named in no
      change until the 2026-09-13 re-verification; the three intermediates were added on
      2026-09-23 because without them the order cannot reach the ends.
- [x] 3.9 Change the two identity consumers that receive a `TrackerTask` only to read its
      id — `TaskTierLaw.bind:103` (called from both `claimAt` sites of 3.4) and
      `TakeQuarantinePark.onQuarantine:52` (called from `TakeClaimAndWork.dispatchAfterClaim`)
      — to take `TakeOrder` and read `order.taskId()` / `order.ref()`; verify no
      `TrackerTask` parameter survives on either and their specs pass with call-site-only edits (NFR-R1).
- [x] 3.10 Change the `RunAssembly.assemble` interface (`:application`) to take one `RunOrder`
      in place of `definition` and `interactiveMode` (seven parameters become six), and follow
      with its implementation `ManualRunAssembly.assemble` (`@Override`) and the helper it
      delegates to, `RunAssembler.assemble:68` (`:bootstrap`). Route every caller:
      `GitModeRunner.run`, `GitResumeContinuation.runToTerminalBoundary` and
      `ContainerTerminalDrive.run` pass the `RunOrder` they hold since group 2;
      `TakeEngineExecution.run` and `TakeContainerEngineExecution.run` pass `order.run()` (hence
      after 3.7); `ManualRunDrive.driveInPlace` builds its order through the same
      `ManualRunDrive.order(runArguments, definition)` helper as `driveResume`/`driveGit`, which
      stays the only place a `RunArguments` becomes a `RunOrder` (D1). Spec edits are call-site
      and fixture only (NFR-R1): the direct `assembly.assemble(...)` calls in
      `ManualRunAssemblySpec`, `ManualRunAssemblyWiringSpec`,
      `ManualRunAssemblyCheckClientWiringSpec` and `AgentDecisionRoundTripSpec`, and the two
      map-coerced `assemble:` closures in `RunChainFakes`. Verify `RunAssembler.assemble` drops
      from eight parameters to seven, and that the claim `add-base-ref-resolution`'s design
      makes about this signature — corrected 2026-09-13 to say it is one over the limit — now
      holds.

## 4. Sync-pair bookkeeping (FR6)

- [x] 4.1 Re-read the `Kept in sync with` sentence on both ends of all seven pairs listed
      in the design's Sync surfaces table and update any that names a parameter the change
      removed; verify `grep -rn "Kept in sync with" */src/main` still enumerates fourteen
      markers and that each names its twin with a resolvable `{@link}`.
      *Applied 2026-09-24:* none of the fourteen chain markers names a parameter; each states
      an invariant. The fresh-claim pair's markers gained the D6 sentence (both hand on only
      the re-bound order past the task-tier bind).
- [x] 4.2 Update the affected rows of `.claude/rules/manual-sync-pairs.md` where the
      synchronized-invariant text describes a parameter list that no longer exists; verify
      each updated row names an invariant that is still true after this change.
      *Applied 2026-09-24:* no edit needed. The seven chain pairs carry markers on both ends, so
      none has a registry row, and no remaining row names an order field.

## 5. Old-way sweep and enforcement (design's single-owner table)

- [x] 5.1 Run `grep -rn "snapshot().id()" application/src/main bootstrap/src/main` and
      route or delete every hit; verify the only survivor is `TakeOrder.taskId()`'s own
      body, and record the grep and its hits in the task report per
      `.claude/rules/implementation.md`.
      *Sweep 2026-09-24:* `grep -rn "snapshot().id()" application/src/main bootstrap/src/main`
      gives one hit, `TakeOrder.java:40` (the `taskId()` body). The five sites the design listed
      are gone: `TakeWorkRouter`, both fresh claims, `TaskTierLaw`, `TakeQuarantinePark`.
- [x] 5.2 Run the parameter-count scan over `src/main` with the same scanner as 0.2 and
      record the new count; verify that the total is **44** — the 0.2 baseline of 65 minus
      the 21 consumer-table signatures it showed over the limit that drop to seven or fewer
      (M2) — and that the only consumer-table signatures still in the scan are
      `TakeOutcomeDispatch.dispatch` (9), the `TakeSlotRunner` constructor (15), and
      `TakeFreshClaim.claim`/`claimAt` (9 each) and `TakeContainerFreshClaim.claim` (10) /
      `claimAt` (9).
      *Scan 2026-09-24:* **44**. Consumer-table survivors: `TakeOutcomeDispatch.dispatch` (9),
      the `TakeSlotRunner` constructor (15), `TakeFreshClaim.claim`/`claimAt` (9/9) and
      `TakeContainerFreshClaim.claim`/`claimAt` (10/9).
- [x] 5.3 Verify NFR-S1 by checking that no order type is passed whole to a logger and
      that no new `toString` of a credential-bearing field exists; record the grep used.
      *Checked 2026-09-24:* `grep -rnE "log\.(trace|debug|info|warn|error)\(.*\b(order|run|lawBound)\b[,)]"`
      and a grep for string concatenation or `toString()` on an order: no hits. Neither record
      has a credential component. The generated `toString` reaches the tracker adapter, whose
      token sits in `GithubHttpClient`, a plain class with no `toString` override, so it prints
      only `ClassName@hash`.

## 6. Gates and downstream

- [x] 6.1 Run `./gradlew :application:check :bootstrap:check checkLogExpectationGate` —
      Spotless, Error Prone / NullAway, Spock, JaCoCo, PIT, and the build-wide
      log-expectation gate, which is a root task no module's `check` runs (NFR-O1) — and
      verify every gate passes and the mutation score is unchanged (M4). Then verify
      call-site-only edits mechanically: `git diff HEAD --stat -- '*/src/test/*'` lists only
      spec files that construct or invoke a consumer-table signature, and reading each
      `src/test` hunk shows no `then:`, `where:`, log-capture or expected-output change
      (NFR-R1, M3). UX1 is evidenced by the same run: the `:bootstrap` E2E and console specs
      that assert console output, log lines and tracker writes pass with call-site-only
      edits, so no operator-visible surface moved.
      *Run 2026-09-24:*
      - Gates:
        - Spotless, Error Prone / NullAway, JaCoCo: green.
        - `:bootstrap` PIT: green.
        - `:application` PIT: 2274 of 2274 killed, `pitestVerifyAllKilled` green.
        - `checkLogExpectationGate`: green.
        - `:application:test`: 2457 tests. `:bootstrap:test`: 1935 tests.
      - Three specs went red under the parallel full build and passed alone and on rerun:
        - `ProcessSupervisorTreeKillSpec` (`:subprocess`, untouched).
        - `DockerCliSpec` (`:sandbox:docker`, untouched).
        - `TakeSlotRunnerContainerConcurrencySpec`: a race that predates this change. Two slots
          call `harden` on one clone, and git's `.git/config` lock refuses the second. The call
          is not in this diff.
      - `:application` PIT also produced one RUN_ERROR on the first run, in the untouched
        `LivenessOracle` (a minion crash under load). A clean rerun had none.
      - Call-site-only check: `git diff HEAD -U0 -- '*/src/test/*'` (68 files across groups 2
        and 3) has no removed line with an assertion, interaction, `where:`, `thrown` or
        log-capture token.
- [x] 6.2 Update the task text of the queued changes that edit these signatures —
      `add-claim-return`, `add-pipeline-routing` — through `/opsx:update`; verify each
      names the post-refactor signature (for `add-claim-return`, that `ClaimIdentity` is
      derived from the order at the release sites, D4), and report any whose plan the
      refactor invalidated rather than merely renamed. `fix-claim-epoch-fence` is archived
      and immutable; it is not on this list.
      *Done 2026-09-24:*
      - `add-claim-return`: rename plus one gap. `ClaimIdentity` is derived from the order
        through a `ClaimReturn` built from `TakeOrder` and `TaskGit.epochs()`. It replaces the
        `(ref, tracker)` pair in both binding helpers (D2 there). The rebase found a fifth
        `release` site, the container end of the declared revocation pair
        (`TakeContainerEngineExecution`), which predates this change. It was added to that
        change's consumers, tasks 4.5–4.7 and M3, and its release gate now matches a tracker
        receiver only, so `SlotLedger` and `TakeBatch` no longer trip it.
      - `add-pipeline-routing`: plan partly invalidated. The fresh-claim half renames onto
        `claimAt` and `withDefinition`. Running the pinned pipeline on resume needs a second
        rebinding site, which D6 here names as the trigger for a bound-order type. It is
        recorded as an open question in that design, and its task 4.3 resume half is blocked
        until it is decided.
- [x] 6.3 Recommend a Conventional Commits subject line for the diff since the last commit
      (the agent never commits, `process-invariants.md`).

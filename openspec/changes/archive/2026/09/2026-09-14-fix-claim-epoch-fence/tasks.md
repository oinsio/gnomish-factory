# Tasks: fix-claim-epoch-fence

> Sequencing: no upstream dependency; `introduce-take-order`, `introduce-slot-wiring`,
> `collapse-composition-roots`, and `add-parameter-count-gate` wait for this change (proposal,
> Impact). Task 1.0 wires the two lifecycle fixtures by hand so that 1.1 is red with the
> quarantine while the fence still exists; the rest of group 1 stays red until groups 2–6 land, and
> M2 requires that order to be recorded in the task report.

## 1. Red specs on the real medium (FR5, FR6, NFR-R1, M2, M3)

- [x] 1.0 Make the two lifecycle fixtures stamp and record like production, by hand and temporarily: `TakeCommandFixture` and `TwoInstanceTakeFixture` each build one `ClaimEpochBook`, pass it to `TaskGitFixture.real(book)`, wrap the tracker they register in `EpochRecordingTracker(tracker, book)`, and pass `TakeCommandSeams.DEFAULTS.withEpochs(book)`; verify the existing `InMemoryTakeLifecycleEscalateResumeSpec` now fails on the reclaim with `BranchQuarantineException` — the production defect reproduced in a spec (FR6, M2). Task 6.1 replaces this hand wiring with the owner
- [x] 1.1 Extend `TakeLifecycleEscalateResumeSpecBase` (one concrete adapter today, `InMemoryTakeLifecycleEscalateResumeSpec`; a GitHub variant only if the harness supports it) with the spec scenario "Escalated, returned, reclaimed": before the second claim assert the tip's `Gnomish-Claim-Epoch` trailer equals the first tenure's epoch as the tracker issued it; after delivery assert every commit of the second tenure carries the second epoch and that the branch was routed as `Answered`; verify the spec is red after 1.0 with `BranchQuarantineException` in the log or exit code (FR6, M2)
- [x] 1.2 Add `TakeLifecycleCrashReapReclaimSpecBase` over `TwoInstanceTakeFixture` (in-memory concrete spec; GitHub variant if the harness supports a mid-round kill): instance A dies mid-round leaving a salvaged tip stamped with its epoch, the reaper returns the task, instance B claims, resumes as `InProgress` at the recorded position, and a second pickup of the same branch changes nothing; verify red today (FR6, NFR-R1)
- [x] 1.3 Add `ClaimlessGitBoundarySpec` to `:bootstrap` (`architecture` package, `RepoSourceTree.productionSources` and `testSources`) implementing the two rules of design D4: rule 1 over `application/src/main`, `bootstrap/src/main`, `test-fixtures/src/main` (`ClaimEpochSource.NONE` only in `ContainerRunSupportFactory`, `ManualRunRunner`, `TaskGitFixture`, and the two single-component seed fixtures `SeededCloneFixture` and `TaskSeedFixture`, which build a `GitTaskRepository`/`GitAttemptPersistence` directly and never a bundle; `new ClaimEpochBook()` only in `ManualRunConfiguration`, `TaskGitFixture`), rule 2 over `application/src/test` and `bootstrap/src/test` (`ClaimEpochSource.NONE` only in the files 6.1 allowlists; no file holding both `TaskGitFixture.real(` and `new ClaimEpochBook()` — a hand-built `new TaskGit(` is deliberately not counted, since after 5.1 it must be handed a book and that inline mint IS the bundle's record), asserting the scan reached every allowlisted file; verify it is red today, its failure message lists each offending file, and today's list includes `AppAssemblyFixture` and `ServeObservabilityFixture` under rule 2 (FR5, M3)

## 2. Domain: the shape set without the fence (FR1, FR2)

- [x] 2.1 Remove `StaleEpoch` from `BranchShape` and its four switches (`recoveryOwner`, `disposition`, `tipCarriesState`, `isClean`), remove `RecoveryDisposition.DISCARD` (its only user), and update `BranchShapeSpec` including the "each shape declares its recovery owner and disposition" table; verify `:domain:test` compiles only after every switch is fixed and passes (FR1)
- [x] 2.2 Remove `liveEpoch` and `tipEpoch` from `BranchTipFacts`, the `isStale` rule from `BranchShapeClassifier` (order becomes delivery → envelope → progression, javadoc updated), and `ClaimEpoch.isStaleAgainst`; keep `Comparable` only if `grep -rn "compareTo" --include='*.java'` finds a production consumer, otherwise remove it; update `BranchShapeClassifierSpec`, `BranchShapeClassifierPropertySpec` (generator loses the epoch dimensions), and `ClaimEpochSpec`; verify `:domain:check` including PIT passes with no new exemption (FR1, FR2, M4)

## 3. Git adapter: no epoch reaches the classifier (FR1, FR3)

- [x] 3.1 Drop the live-epoch argument from `BranchTipFactsReader.read` and the `epochs.epochFor` read in `GitTaskBranches.shapeAt`; `GitTaskBranches` keeps its `ClaimEpochSource` for `ContainerResumeBranch` stamping but loses its one-argument constructor that defaulted to `ClaimEpochSource.NONE` (no production caller; test callers spell `NONE` out); `TipEnvelopeReader` passes nothing; verify `BranchTipFactsReaderSpec`, `GitTaskBranchesSpec`, `BranchStateReaderSpec` pass and `grep -rn "new GitTaskBranches(runner)\|new GitTaskBranches(new GitProcessRunner())" src` is empty (FR1, FR4)
- [x] 3.2 Delete the tip-epoch read path whose only consumer was the classifier: `BranchTipSource.tipEpoch`, `GitShowTip.tipEpoch`, `RefTipSource.tipEpoch`, and `ClaimEpochTrailer.parse` (`stamp` stays); re-point `GitShowTipTerminationSpec` at another `GitShowTip` read, trim `BranchTipSourceSpec`, `TipRecordedDenialsSpec`, and `ClaimEpochTrailerSpec` to the stamp half; specs that assert a stamp keep reading the trailer with `git log -1 --format=%B` as `TakeDispositionSpec` does; verify `:adapters:git:check` including PIT passes (FR3, M4)

## 4. Application: routes and renderers (FR1, FR2, NFR-O1, UX2)

- [x] 4.1 Remove the `StaleEpoch` arm and `afterReconciliation` from `TakeDispositionResume`, the `StaleEpoch` arms from `BranchShapeDiagnosis` and `BranchRepairLog`, and the `DISCARD` phrase from `BranchRepairAction`; update `TakeShapeRoutingSpec`, `TakeResumeShapeTailSpec`, `BranchRepairLogSpec`, `BranchRepairActionSpec`, `TakeDispositionMatrixSpec`; verify `:application:check` including PIT passes and the repair log line still carries task, shape, live epoch, action, and recovery count (FR1, FR2, NFR-O1)
- [x] 4.2 Confirm `BranchQuarantineReport`'s closing hint is true for every remaining producer (`UnsupportedVersion`, `Corrupt`, `Unknown`, and the `Bare` routing defect) and adjust the `Bare` wording only if a spec shows it misleading; verify `BranchQuarantineReportSpec` (or the spec that pins the text) passes (UX2)

## 5. One owner for the tenure record (FR4)

- [x] 5.1 Add `ClaimEpochBook epochs` to the `TaskGit` record with javadoc naming it the process's tenure record; every constructor (the three- and four-argument convenience forms included) takes it, none defaults it, and `withBaseRefs` carries it; build it in `ManualRunConfiguration.taskGit` from the `claimEpochBook` bean; fix every hand-built `TaskGit` in port-fake specs (28 files on 2026-09-13: pass a fresh book; list them in the task report); verify `:application:compileJava`, `:bootstrap:compileJava`, and `grep -rn "new TaskGit(" src` shows no call without a book (FR4)
- [x] 5.2 Make the claiming funnel `resolveTracker` take the bundle's book, call the four-argument `TrackerAdapterFactory.create(..., book)`, and wrap the result in `EpochRecordingTracker`; route `ServeCommand.provisionTracker` through the same funnel (today it calls `factory.create` directly); delete the separate `ClaimEpochBook` parameter of `TakeCommandFactory.of`, `SubcommandDispatchFactory.of`, the `ServeCommand` constructor, and the `TakeClaimAndWork` constructor so `TakeCommand`/`TakeWorkRouter`/`TakeClaimAndWork`/`ServeCommand` read the book from `TaskGit`; verify `TakeCommandSpec`, `ServeCommandSpec`, `TakeClaimAndWorkSpec`, `SubcommandDispatchSpec`, and `EpochRecordingTrackerSpec` pass and `grep -rn "ClaimEpochBook epochs" application/src/main bootstrap/src/main` finds only the `TaskGit` record's own components and the one parameter of the `TrackerResolution` funnel that receives the bundle's book — no other holder of a book of its own. If `TakeCommandSupport` exceeds the file-size target as a result, split the tracker-resolution methods into their own class (`TrackerResolution`) — a file-size split only, no behavior moved (FR4)
- [x] 5.3 Delete `TakeCommandSeams.epochs` and `withEpochs`, the wrapping in `TrackerAdapterConfiguration.trackerAdapterRegistry` (the discovery report stays), `EpochRecordingTrackerFactory`, and `EpochRecordingTrackerFactorySpec`; update `TrackerAdapterConfigurationSpec` and `DelegatingDecoratorCompletenessSpec` (the decorator class `EpochRecordingTracker` stays in its scan); verify `:bootstrap:test` passes and `grep -rn "EpochRecordingTrackerFactory\|withEpochs"` over `src` is empty (FR4)
- [x] 5.4 Replace the separate `ClaimEpochSource epochs` parameter of `ContainerRunSupportFactory.create`/`ContainerRunSupport` and of `ManualRunRunner`'s take/serve assembly with the bundle's book, keeping plain `run` on the same bean (never issued, so effectively claimless); verify `ContainerRunSupportSpec`, `ManualRunRunnerSpec`, and the container E2E suite that is already gated pass (FR4)

## 6. Fixtures through the owner; group 1 goes green (FR5, FR6, NFR-R1, M2, M3)

- [x] 6.1 `TaskGitFixture.real()` builds a fresh `ClaimEpochBook`; add `realClaimless()` for `StatusCommand`, `UsageCommand`, and board fixtures; remove the hand wiring of 1.0 and move `TakeCommandFixture`, `TwoInstanceTakeFixture`, `AppAssemblyFixture` (one book, from the bundle — no second `new ClaimEpochBook()`), `ServeObservabilityFixture` (same), `ContainerSupportFixture`, and `ResumeSpecFixtureBase` onto the bundle's book; `KillPointWorlds` builds one book per world and passes it to its `GitTaskRepository` and `GitObjectsTaskRepository`. Then disposition every other `ClaimEpochSource.NONE` file of the two test trees by the rule of design D3 (claims → book; never claims → allowlist with reason), recording the outcome per file in the report — `bootstrap/src/test`: `TakeParkReconcileLifecycleSpecBase`, `GitKillResumeSalvageCompletionSpec`, `SubcommandDispatchSpec`, `GitModeMidRoundPushSpec`, `ContainerResumeSpecBase`, `ContainerModeResumeE2ESpec`, `TakeContainerLifecycleE2ESpec`, `ContainerLifecycleCoverageGapsE2ESpec`, `ContainerTerminalDriveSpec`, `ContainerRunTerminationSweepSpec`, `ContainerGitModeRunnerSpec`, `ContainerRunSupportSpec`, `ManualRunRunnerSpec`, `SandboxLifecycleRemnantReapE2ESpec`, `SandboxLifecycleZombieE2ESpec`, `SandboxLifecycleProjectScopingE2ESpec`, `SandboxLifecycleLegacyIdentityE2ESpec`, `SandboxLifecycleLaunchRaceE2ESpec`, `SandboxLifecycleCrossInstanceE2ESpec`; `application/src/test`: `StatusCommandSpec`, `UsageCommandSpec`, `StatusUsageReadOnlySpec`, `StatusInterruptedHonestySpec`, `RevocationHandlerSpec`, `ZombieFenceSpec`, `ClaimEpochBookSpec` (the last three and the four status/usage specs are expected allowlist entries); the kill-point world records `CreationWorld` and `KillPointWorld` are expected allowlist entries too — each builds a claimless `GitTaskBranches` only for its tip-label helper, which classifies and never writes, and classification takes no epoch at all after FR1; verify 1.3 is green with that allowlist and every `:bootstrap` and `:application` spec still passes (FR5, M3)
- [x] 6.2 Verify 1.1 and 1.2 are green on the in-memory adapter (and on a GitHub variant if 1.1 or 1.2 added one), and record in the task report the run in which 1.1 was red after 1.0 and green after group 2 (M2)
- [x] 6.3 Add the two reclaim cycles to the kill-point matrix (`TransitionKillPointSpec` rows over a salvaged `InProgress` tip and a `Parked` tip reclaimed by a second world) with the second-pickup no-op assertion; verify the matrix passes twice in a row (NFR-R1)
- [x] 6.4 Cover the second call site of the claiming funnel: `ServeClaimEpochStampSpec` drives one real `gnomish serve --drain` over a real project and asserts, through `ClaimWatchingTrackerFactory`, that every commit the round pushed carries the epoch the tracker issued for that claim — the take-path flow specs of 1.1/1.2 never reach `ServeCommand.provisionTracker`; verified red when that call resolves the tracker over a book its git writers do not read (FR4, FR6)

## 7. Durable record (FR7, M1)

- [x] 7.1 `docs/adr/0003-crash-consistency.md`: drop the `StaleEpoch` row, add the principle sentence of design D4 to the "Block-allocated sequence counters" paragraph, rewrite that paragraph's "epochs make zombie writes detectable and classifiable" clause to provenance plus the two real fences, and mention this change as provenance; verify the table renders and `grep -rin "StaleEpoch\|stale.epoch" docs/adr` is empty (FR7, M1)
- [x] 7.2 `docs/glossary.md`: rewrite *Fence* (fast-forward push and round-boundary revocation check) and *Claim epoch* (provenance on the branch, identity at the tracker); confirm the `tracker-port` and `claim-heartbeat` deltas of this change restate their requirements verbatim except the removed sentence; verify no banned synonym is introduced and `grep -rin "StaleEpoch\|stale.epoch" docs openspec/specs` returns only the accepted scenario heading of M1 after sync (FR7, M1)
- [x] 7.3 `.claude/rules/testing.md`: add the "Fixtures assemble through production owners" section of design D4 (owner as a value the command receives; an architecture spec pins the fixture path; name `ClaimlessGitBoundarySpec` as the precedent); verify the section is under 25 lines and references `implementation.md` item 4 (FR7)
- [x] 7.4 Sweep javadoc and comments: `ClaimEpochTrailer`, `EpochRecordingTracker`, `ClaimEpochBook`, `TakeClaimAndWork`, `TrackerAdapterFactory.create`, `GitTaskBranches`, `BranchShapeClassifier`, `TakeDispositionResume`, operator guides under `docs/guides/` — every sentence that says a reader classifies an older epoch as stale is rewritten to provenance plus the two real fences; verify `grep -rin "StaleEpoch\|stale.epoch" --include='*.java' --include='*.groovy' --include='*.md' src docs openspec/specs .claude` returns nothing but the archived change and the accepted scenario heading of M1 (M1)
- [x] 7.5 Record the surface break where a plugin author reads it: bump `:gnomish-plugin-api` `0.5.0` -> `0.6.0` with a `build.gradle` header paragraph naming the four removals (`BranchShape.StaleEpoch`, the `BranchTipFacts` components, `RecoveryDisposition.DISCARD`, `ClaimEpoch`'s `Comparable`) and why a pre-1.0 break is a MINOR bump; rename `compat-baseline/gnomish-plugin-api-0.5.0.jar` to `-0.6.0.jar` and regenerate `compat-baseline/domain-0.1.0-SNAPSHOT.jar` in the same commit; verify `japicmpApiGate` is green and `grep -n "version = " gnomish-plugin-api/build.gradle` shows `0.6.0` (FR1, FR2, FR7)

## 8. Verification and report (M1–M4)

- [x] 8.1 Run the root `check` (all modules, PIT under each module's gate, `checkTestTimeInjection`, Spotless, dependency analysis) and record the result; verify no new `@DoNotMutate` or `excludedClasses` entry was added (M4)
- [x] 8.2 Old-way sweep per `.claude/rules/implementation.md`: run `grep -rn "ClaimEpochSource.NONE\|new ClaimEpochBook()\|epochFor(\|ClaimEpochBook epochs" --include='*.java' --include='*.groovy' src` and reconcile every hit against the single-owner table in design.md (owner, listed consumer, or listed exemption); list the hits and their disposition in the task report. In the same review, read the diff of every path a reclaim takes and confirm it adds no commit, push, fetch, or tracker write and removes the reconcile pass's fetch (NFR-R2, NFR-C1) (FR4, FR5, NFR-R2, NFR-C1)
- [x] 8.3 Recommend the commit message (subject ≤ 72 chars, trailer with the change name and FR IDs) — the human commits

## 9. Task report (M2, M3, FR4, FR5)

The four reporting obligations of 5.1, 6.1, 6.2 and 8.2 land here rather than in a commit body,
so each is checkable after the fact.

- [x] 9.1 **M2 — the red run.** The production defect is reproduced by
  `InMemoryTakeLifecycleEscalateResumeSpec` (scenario "Escalated, returned, reclaimed" on
  `TakeLifecycleEscalateResumeSpecBase`): after task 1.0 wired the two lifecycle fixtures like
  production, the reclaim failed with `BranchQuarantineException` — the read-side fence
  classifying the first tenure's own tip as `StaleEpoch`. It went green after group 2 removed the
  shape and stayed green through group 6, which replaced 1.0's hand wiring with the owner. To see
  the red again: restore `StaleEpoch` and the `isStale` rule in `BranchShapeClassifier` and run
  `./gradlew :bootstrap:test --tests '*InMemoryTakeLifecycleEscalateResumeSpec'`
- [x] 9.2 **M3 — the per-file disposition, materialized.** 6.1's disposition of every
  `ClaimEpochSource.NONE` site is not a prose list: it is the three allowlists of
  `ClaimlessGitBoundarySpec` — `CLAIMLESS_PRODUCTION` (5 files), `BOOK_OWNERS` (2 files) and
  `CLAIMLESS_SPECS` (25 specs, with the four accepted reasons in its javadoc). Every other file of
  the owned trees is an offender the spec fails on, and the scan asserts it reached each
  allowlisted path, so a moved or renamed exemption is red rather than silent. 5.1's "28 hand-built
  `TaskGit` files" needs no list either: the book is a component of the record and both remaining
  `public TaskGit(` constructors take it, so the 31 construction sites are compiler-checked — the
  escape hatch of `implementation.md` item 3 is gone
- [x] 9.3 **8.2 — the old-way sweep result.** `grep -rn "ClaimEpochSource.NONE|new ClaimEpochBook()|epochFor(|ClaimEpochBook epochs"`
  over `*/src`, reconciled against design.md's single-owner table, leaves four buckets and no
  unlisted survivor: (a) `ClaimEpochSource.NONE` in a main tree — only the port's own constant and
  its javadoc in `:gnomish-plugin-api`, plus the three `:test-fixtures` files of
  `CLAIMLESS_PRODUCTION`; (b) `new ClaimEpochBook()` in a main tree — only
  `ManualRunConfiguration` (the bean) and `TaskGitFixture` (the fixture's stand-in for it), the
  two `BOOK_OWNERS`; (c) `ClaimEpochBook epochs` in `application`/`bootstrap` main — the `TaskGit`
  components and the `TrackerResolution` funnel parameter that receives the bundle's book
  (FR4's listed consumer); (d) `epochFor(` in a main tree — the book's own method, the port
  method it implements, and one call site, `TakeWorkRouter`, reading it off `w.git.epochs()`.
  Test-tree hits are governed by rule 2 of `ClaimlessGitBoundarySpec`, not by this sweep. The
  reclaim paths were read in the same pass: no commit, push, fetch or tracker write is added, and
  the reconcile pass's fetch is gone (NFR-R2, NFR-C1)

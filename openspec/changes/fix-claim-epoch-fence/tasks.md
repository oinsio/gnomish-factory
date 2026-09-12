# Tasks: fix-claim-epoch-fence

> Sequencing: independent of the other active changes (proposal, Impact). Tasks in group 1 are
> written red first and stay red until groups 2–6 land; M2 requires that order to be recorded in
> the task report.

## 1. Red specs on the real medium (FR5, FR6, NFR-R1, M2, M3)

- [ ] 1.1 Extend `TakeLifecycleEscalateResumeSpecBase` (both concrete adapters) with the spec scenario "Escalated, returned, reclaimed": before the second claim assert the tip's `Gnomish-Claim-Epoch` trailer equals the first tenure's epoch as the tracker issued it; after delivery assert every commit of the second tenure carries the second epoch and that the branch was routed as `Answered`; verify the spec is red today with `BranchQuarantineException` in the log or exit code (FR6, M2)
- [ ] 1.2 Add `TakeLifecycleCrashReapReclaimSpecBase` over `TwoInstanceTakeFixture` (in-memory concrete spec; GitHub variant if the harness supports a mid-round kill): instance A dies mid-round leaving a salvaged tip stamped with its epoch, the reaper returns the task, instance B claims, resumes as `InProgress` at the recorded position, and a second pickup of the same branch changes nothing; verify red today (FR6, NFR-R1)
- [ ] 1.3 Add `ClaimlessGitBoundarySpec` to `:bootstrap` (`architecture` package, `RepoSourceTree` scan over `application/src`, `bootstrap/src`, `test-fixtures/src`) failing on `ClaimEpochSource.NONE` and `new ClaimEpochBook()` outside the allowlist of design D4, and asserting the scan reached every listed file; verify it is red today and its failure message lists each offending file (FR5, M3)

## 2. Domain: the shape set without the fence (FR1, FR2)

- [ ] 2.1 Remove `StaleEpoch` from `BranchShape` and its three switches (`recoveryOwner`, `disposition`, `tipCarriesState`, `isClean`), remove `RecoveryDisposition.DISCARD` (its only user), and update `BranchShapeSpec` including the "each shape declares its recovery owner and disposition" table; verify `:domain:test` compiles only after every switch is fixed and passes (FR1)
- [ ] 2.2 Remove `liveEpoch` and `tipEpoch` from `BranchTipFacts`, the `isStale` rule from `BranchShapeClassifier` (order becomes delivery → envelope → progression, javadoc updated), and `ClaimEpoch.isStaleAgainst`; keep `Comparable` only if `grep -rn "compareTo" --include='*.java'` finds a production consumer, otherwise remove it; update `BranchShapeClassifierSpec`, `BranchShapeClassifierPropertySpec` (generator loses the epoch dimensions), and `ClaimEpochSpec`; verify `:domain:check` including PIT passes with no new exemption (FR1, FR2, M4)

## 3. Git adapter: no epoch reaches the classifier (FR1, FR3)

- [ ] 3.1 Drop the live-epoch argument from `BranchTipFactsReader.read` and the `epochs.epochFor` read in `GitTaskBranches.shapeAt`; `GitTaskBranches` keeps its `ClaimEpochSource` for `ContainerResumeBranch` stamping; `TipEnvelopeReader` passes nothing; verify `BranchTipFactsReaderSpec`, `GitTaskBranchesSpec`, `BranchStateReaderSpec` pass (FR1)
- [ ] 3.2 Delete the tip-epoch read path whose only consumer was the classifier: `TipSource.tipEpoch`, `GitShowTip.tipEpoch`, `RefTipSource.tipEpoch`, and `ClaimEpochTrailer.parse` (`stamp` stays); re-point `GitShowTipTerminationSpec` at another `GitShowTip` read, trim `BranchTipSourceSpec`, `TipRecordedDenialsSpec`, and `ClaimEpochTrailerSpec` to the stamp half; specs that assert a stamp keep reading the trailer with `git log -1 --format=%B` as `TakeDispositionSpec` does; verify `:adapters:git:check` including PIT passes (FR3, M4)

## 4. Application: routes and renderers (FR1, FR2, NFR-O1, UX2)

- [ ] 4.1 Remove the `StaleEpoch` arm and `afterReconciliation` from `TakeDispositionResume`, the `StaleEpoch` arms from `TakeLoadedBranchRoutes`, `BranchShapeDiagnosis`, and `BranchRepairAction` (with the `DISCARD` phrase); update `TakeShapeRoutingSpec`, `TakeResumeShapeTailSpec`, `BranchRepairLogSpec`, `BranchRepairActionSpec`, `TakeDispositionMatrixSpec`; verify `:application:check` including PIT passes and the repair log line still carries task, shape, live epoch, action, and recovery count (FR1, FR2, NFR-O1)
- [ ] 4.2 Confirm `BranchQuarantineReport`'s closing hint is true for every remaining producer (`UnsupportedVersion`, `Corrupt`, `Unknown`, and the `Bare` routing defect) and adjust the `Bare` wording only if a spec shows it misleading; verify `BranchQuarantineReportSpec` (or the spec that pins the text) passes (UX2)

## 5. One owner for the tenure record (FR4)

- [ ] 5.1 Add `ClaimEpochBook epochs` to the `TaskGit` record with javadoc naming it the process's tenure record; build it in `ManualRunConfiguration.taskGit` from the `claimEpochBook` bean; fix every hand-built `TaskGit` in port-fake specs (pass a fresh book; list them in the task report); verify `:application:compileJava` and `:bootstrap:compileJava` (FR4)
- [ ] 5.2 Make `TakeCommandSupport.resolveTracker` take the bundle's book, call the four-argument `TrackerAdapterFactory.create(..., book)`, and wrap the result in `EpochRecordingTracker`; route `ServeCommand.provisionTracker` through the same funnel; `TakeCommand`/`TakeWorkRouter`/`TakeClaimAndWork` read the book from `TaskGit`; verify `TakeCommandSpec`, `ServeCommandSpec`, `TakeClaimAndWorkSpec`, and `EpochRecordingTrackerSpec` pass (FR4)
- [ ] 5.3 Delete `TakeCommandSeams.epochs` and `withEpochs`, the wrapping in `TrackerAdapterConfiguration.trackerAdapterRegistry` (the discovery report stays), `EpochRecordingTrackerFactory`, and `EpochRecordingTrackerFactorySpec`; update `TrackerAdapterConfigurationSpec` and `DelegatingDecoratorCompletenessSpec` (the decorator class `EpochRecordingTracker` stays in its scan); verify `:bootstrap:test` passes and `grep -rn "EpochRecordingTrackerFactory\|withEpochs"` over `src` is empty (FR4)
- [ ] 5.4 Replace the separate `ClaimEpochSource epochs` parameter of `ContainerRunSupportFactory.create`/`ContainerRunSupport` and of `ManualRunRunner`'s take/serve assembly with the bundle's book, keeping plain `run` on the same bean (never issued, so effectively claimless); verify `ContainerRunSupportSpec`, `ManualRunRunnerSpec`, and the container E2E suite that is already gated pass (FR4)

## 6. Fixtures through the owner; group 1 goes green (FR5, FR6, NFR-R1, M2, M3)

- [ ] 6.1 `TaskGitFixture.real()` builds a fresh `ClaimEpochBook`; add `realClaimless()` for `StatusCommand`, `UsageCommand`, and board fixtures; move `TakeCommandFixture`, `TwoInstanceTakeFixture`, `AppAssemblyFixture` (one book, from the bundle — no second `new ClaimEpochBook()`), `ServeObservabilityFixture`, `ContainerSupportFixture`, `ResumeSpecFixtureBase`, and `KillPointWorlds` onto the bundle's book; verify 1.3 is green and every `:bootstrap` spec still passes (FR5, M3)
- [ ] 6.2 Verify 1.1 and 1.2 are green on both concrete adapters where present, and record in the task report the run in which 1.1 was red before group 2 and green after (M2)
- [ ] 6.3 Add the two reclaim cycles to the kill-point matrix (`TransitionKillPointSpec` rows over a salvaged `InProgress` tip and a `Parked` tip reclaimed by a second world) with the second-pickup no-op assertion; verify the matrix passes twice in a row (NFR-R1)

## 7. Durable record (FR7, M1)

- [ ] 7.1 `docs/adr/0003-crash-consistency.md`: drop the `StaleEpoch` row, add the principle sentence of design D4 to the "Block-allocated sequence counters" paragraph, and mention this change as provenance; verify the table renders and `grep StaleEpoch docs/` is empty (FR7, M1)
- [ ] 7.2 `docs/glossary.md`: rewrite *Fence* (fast-forward push and round-boundary revocation check) and *Claim epoch* (provenance on the branch, identity at the tracker); verify no banned synonym is introduced and `grep -rn "StaleEpoch\|stale epoch" docs openspec/specs` is empty after sync (FR7, M1)
- [ ] 7.3 `.claude/rules/testing.md`: add the "Fixtures assemble through production owners" section of design D4 (owner as a value the command receives; an architecture spec pins the fixture path; name `ClaimlessGitBoundarySpec` as the precedent); verify the section is under 25 lines and references `implementation.md` item 4 (FR7)
- [ ] 7.4 Sweep javadoc and comments: `ClaimEpochTrailer`, `EpochRecordingTracker`, `ClaimEpochBook`, `TakeClaimAndWork`, `TrackerAdapterFactory.create`, `GitTaskBranches`, `BranchShapeClassifier`, `TakeDispositionResume`, operator guides under `docs/guides/` — every sentence that says a reader classifies an older epoch as stale is rewritten to provenance plus the two real fences; verify `grep -rn "StaleEpoch" --include='*.java' --include='*.groovy' --include='*.md' src docs openspec/specs .claude` is empty except the archived change (M1)

## 8. Verification and report (M1–M4)

- [ ] 8.1 Run the root `check` (all modules, PIT under each module's gate, `checkTestTimeInjection`, Spotless, dependency analysis) and record the result; verify no new `@DoNotMutate` or `excludedClasses` entry was added (M4)
- [ ] 8.2 Old-way sweep per `.claude/rules/implementation.md`: run `grep -rn "ClaimEpochSource.NONE\|new ClaimEpochBook()\|epochFor(" --include='*.java' --include='*.groovy' src` and reconcile every hit against the single-owner table in design.md (owner, listed consumer, or listed exemption); list the hits and their disposition in the task report (FR4, FR5)
- [ ] 8.3 Recommend the commit message (subject ≤ 72 chars, trailer with the change name and FR IDs) — the human commits

# Tasks: refactor-app-spec-fixtures

## 1. Fixture trait

- [x] 1.1 Create `src/test/groovy/com/github/oinsio/gnomish/app/AppAssemblyFixture.groovy`
      trait with `testProperties(Map overrides = [:])` and
      `newAssembly(input, output, factoryProperties)` per design D3;
      doc comments carry `Implements FR1/FR2 of refactor-app-spec-fixtures`;
      keep the file within the 100–120-line budget (FR1, FR2, NFR-R1)
- [x] 1.2 Confirm the trait covers every existing variant before migrating:
      re-run the survey greps (`new ManualRunAssembly`,
      `new FactoryProperties(` under `app/`) and map each site's deviations
      to a trait parameter; extend D3 signatures if a variant does not fit
      (FR2)

## 2. Migrate spec bases and shared fixtures

- [x] 2.1 Migrate `TakeResumeSpecBase`, `GitResumeSpecBase`,
      `TakeLifecycleReadyToDeliveredSpecBase`, `TakeLifecycleRevocationSpecBase`,
      `AbortLifecycleFixture`, and `TwoInstanceTakeFixture`
      to implement `AppAssemblyFixture`; delete their local construction
      helpers that the trait replaces (FR3)
- [x] 2.2 Run the specs extending these bases and confirm green with
      unchanged executed-test count (M2, NFR-R1) — baseline recorded:
      2348 tests, 0 failures, 0 errors, 7 skipped

## 3. Migrate individual app-layer specs

- [x] 3.1 Migrate the git-mode specs: `GitModeRunnerSpec`,
      `GitModeRunCloneUntouchedSpec`, `GitModeWorkspaceHygieneSpec`,
      `GitKillResumeSalvageCompletionSpec`,
      `GitResumeContinuationEdgeCasesSpec`, `AgentDecisionRoundTripSpec`
      (FR3)
- [x] 3.2 Migrate the take/dispatch specs: `TakeCommandSpec`,
      `TakeCommandMdcSpec`, `TakeCommandCredentialScrubSpec`,
      `TakeBareAutoSpec`, `TakeDispositionSpec`, `SubcommandDispatchSpec`
      (FR3)
- [x] 3.3 Migrate `ManualRunAssemblySpec` (uses the trait; its subject is
      assembly behavior, not construction — design D4) (FR3)
- [x] 3.4 Migrate the Gitea E2E specs: `GiteaBestEffortPushE2ESpec`,
      `GiteaCrossInstanceResumeE2ESpec` (FR3)
- [x] 3.5 Collapse remaining literal `new FactoryProperties(...)` values in
      the app package into `testProperties(...)` calls where the spec is
      already on the trait (FR2, FR3)

## 4. Verification

- [x] 4.1 M1: `grep -rn "new ManualRunAssembly" src/test/groovy` returns
      exactly one site — inside `AppAssemblyFixture` (FR1, FR3), down from
      21 sites across the app package — confirmed: exactly one hit
      (`AppAssemblyFixture.groovy:71`)
- [x] 4.2 M2: `./gradlew test` is green with the same executed-test count
      as on the pre-change commit; record both counts in the PR/commit
      notes (G3, NFR-R1) — confirmed: 2348 tests, 0 failures, 0 errors,
      7 skipped (unchanged from the task-2.2 baseline)
- [x] 4.3 FR4: `git diff --stat` shows changes confined to
      `src/test/groovy` (plus OpenSpec artifacts) — confirmed: 22 files
      under `src/test/groovy/.../app/` plus this change's `tasks.md`;
      no production source touched
- [x] 4.4 `./gradlew check` passes (Spotless/Groovy formatting, static
      analysis unaffected); PIT outcome unchanged since only Groovy test
      sources moved (M3) — confirmed: `BUILD SUCCESSFUL`, `pitestVerifyAllKilled`
      passed (100% mutation threshold on Java/production classes, untouched
      by this change)

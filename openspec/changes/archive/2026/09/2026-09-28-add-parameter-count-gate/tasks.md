## 0. Sequencing

- [x] 0.1 Confirm `fix-operator-blockers` task 2.2 has landed (its role-passing edits to
      `ExecutorRoundExecution` and `JudgeRoundExecution`), or record in the task report that
      this change is being applied first and D6 will be rebased under it; verify with
      `git log --oneline -- adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/ExecutorRoundExecution.java`.
      *Report (2026-09-27):* not landed — the last commit touching the file is `4748d9f5`
      (#50), `fix-operator-blockers` is uncommitted with 0 tasks done, and no `AgentRole`
      exists in the source. This change is applied first; `fix-operator-blockers` task 2.2
      will pass the role into `ExecutorRoundExecution.run` / `JudgeRoundExecution.run`
      alongside the `AgentRoundEquipment` parameter D6 introduces (rebase onto D6).

## 1. The check, built and proven before anything depends on it

- [x] 1.1 Add the `build-checks` included build (D9): its `settings.gradle` importing the
      shared catalog by file as `build-logic/settings.gradle` does, two Java projects
      `parameter-count-check` and `parameter-count-annotations` under group
      `com.github.oinsio.build`, dependency locking on, `includeBuild 'build-checks'` in the
      root `settings.gradle`; add `errorprone-check-api` and `errorprone-test-helpers` to
      `gradle/libs.versions.toml` on the existing `errorprone` version ref. Verify
      `./gradlew -p build-checks build` passes standalone and both lockfiles are generated.
      *Report:* done; Java package `com.github.oinsio.buildchecks.parametercount` (a
      `…/build/…` source directory would be swallowed by the root `.gitignore`). Found at
      apply: the main build's dependency verification covers the included build, so the
      new artifacts (`error_prone_check_api`'s and the test helpers' transitives) were added
      to `gradle/verification-metadata.xml` with `--write-verification-metadata sha256`; the
      subproject build files joined `build-metadata-conventions`' Spotless target.
- [x] 1.2 Implement `ParameterLimitExemption` in `parameter-count-annotations` (D5):
      `@Target({METHOD, CONSTRUCTOR})`, source retention, one element `String reason()`, no
      default; verify the artifact has no dependencies.
- [x] 1.3 Implement the check in `parameter-count-check`: report a constructor or method with
      more than seven parameters, with a message naming the count, the limit and the
      transformation to use (FR1, NFR-O1); declare
      `@BugPattern(suppressionAnnotations = ParameterLimitExemption.class)` and report an
      annotated site whose reason is blank (FR4); verify the message reads usefully on its
      own, without the rule file.
      *Report:* deviation agreed with the human 2026-09-27 and recorded in D5 — the check
      declares `suppressionAnnotations = {}` and reads the annotation itself; with the
      annotation handed to Error Prone's suppression the blank-reason report was unreachable
      and local/anonymous classes inside an exempted method were exempted too.
- [x] 1.4 Implement the two exemptions from the syntax tree (D3): never report a record's
      canonical, compact or explicit constructor (FR2) — an ordinary method of a record is
      reported; never report an overriding or implementing method (FR3).
- [x] 1.5 Write the unit spec in `parameter-count-check` on `CompilationTestHelper` (D10,
      NFR-R1) covering the seven cases: eight parameters fails, seven passes, a record
      constructor passes, a method housed in a record with eight parameters fails, an
      override passes, an annotated site with a reason passes, a blank reason fails, and
      `@SuppressWarnings` on the enclosing class does not suppress; verify each case is red
      with the corresponding branch of the check removed (M2).
      *Report:* `ParameterCountLimitSpec`, 12 features (the seven cases, plus an eight-parameter
      constructor, the D5 subtree case and a javac-generated constructor). Hand mutations, each
      killed: `<=`→`<`; never report; record branch removed; record branch widened to every
      member; override branch removed; blank reason accepted; annotation ignored; default
      `@SuppressWarnings` suppression restored; annotation handed to Error Prone's suppression.
      One branch survived — `isGeneratedConstructor` — because Error Prone never visits a
      constructor javac writes; the branch was deleted as dead code, and its case stays as a pin.
- [x] 1.6 Decide the wiring question (Risks): against one consuming module, add
      `errorprone 'com.github.oinsio.build:parameter-count-check'` and
      `compileOnly 'com.github.oinsio.build:parameter-count-annotations'` by hand and confirm
      the included-build substitution puts the check on that module's processor path and the
      annotation on its compile classpath (a deliberate eight-parameter method fails
      `compileJava`, then is removed). If it does not, stop and take D1's recorded fallback,
      writing down the approximation limits accepted; verify the decision and its reason are
      in the task report either way, and revert the hand edit.
      *Report:* decision — the included-build wiring works; D1 stands, no fallback. Against
      `:atomicfile`, an eight-parameter method failed `compileJava` with `[ParameterCountLimit]`
      and the full message, and a sibling carrying `@ParameterLimitExemption` with a reason
      compiled (the annotation resolved on the compile classpath). Hand edit and probe reverted.

## 2. The last thirteen signatures (FR6)

- [x] 2.1 Introduce `AgentRoundEquipment` (D6) as one record over `FactoryProperties, Clock,
      AgentProgressListener, AgentRoundResultExtractor`, built once in the constructors of
      `CliStageExecutor` and `CliJudgeVoter` (replacing their four fields with one) and
      taken whole by `ExecutorRoundExecution.run:49` and `JudgeRoundExecution.run:48`; verify
      both `run` methods are at five parameters, the public constructors of the two callers
      are unchanged, the four direct-calling specs (`RoundInterruptedWaitSpec`,
      `ExecutorRoundDrainTimeoutSpec`, `JudgeCannotVerifyLoggingSpec`,
      `JudgeRoundDrainTimeoutSpec`) change at the call site only, and `:adapters:agent:check`
      passes with no expectation edited (NFR-R2). Sweep:
      `grep -rn "AgentRoundResultExtractor resultExtractor" adapters/agent/src/main` shows only
      the record's component; record the output.
      *Report:* both `run` methods at five parameters; the four fields of each caller replaced
      by one `equipment` field built in the canonical constructor; public constructors
      unchanged; the four specs edited at the `run` call only (four hand-listed arguments →
      one `new AgentRoundEquipment(...)`). `:adapters:agent:check` passes, PIT 290/290. Sweep
      output: `AgentRoundEquipment.java:27:        AgentRoundResultExtractor resultExtractor) {}`
      — the record's component only.
- [x] 2.2 Add the `Kept in sync with` marker to both ends of the newly declared
      executor/judge round pair, naming `AgentRoundEquipment` and the launch/failure invariant;
      verify `grep -rn "Kept in sync with" adapters/agent/src/main` returns both ends (plus
      the pre-existing `HostRoundEnvironmentSource` marker). No registry row is added
      (design, Sync surfaces).
      *Report:* the grep returns `ExecutorRoundExecution.java:29`, `JudgeRoundExecution.java:35`
      and the pre-existing `HostRoundEnvironmentSource.java:22`; each new marker names
      `AgentRoundEquipment`, the shared launch sequence and the single failure-mapping site.
- [x] 2.3 Bring the container environment builders under the limit (D11): add the records
      `TaskContainerSettings` (absorbing `requireImage` in its compact constructor), `BoxGitLink`
      (with `harvest(container, branch)`) and `BoxTiming` in `sandbox.environment`; turn
      `ContainerEnvironmentBuilder` and `ContainerMaterializer` into instances holding their
      equipment as fields; bring the signatures to D11's table — `ContainerEnvironments.forTask`
      (7), its constructor (3, taking the builder; `ownershipMode`/`scrubsCredential` delegate),
      `ContainerEnvironmentBuilder` ctor (7) + `build(key)`, `ContainerTaskExecutionEnvironment`
      ctor (7), `ContainerMaterializer` ctor (5) + `reattach(name, inspect, branch, pin)` +
      `create(branch, pin)`; `guardConfigRoot` stays a parameter of its own. Update the callers
      at the call site only: `ContainerRunSupportFactory` (bootstrap, production), the six
      container-environment specs under `adapters/git/src/test/.../sandbox/environment/`,
      `ContainerModeIsolationE2ESpec` (bootstrap), the five `sandbox/docker` specs and fixtures
      named in the proposal's Impact, and `ScriptedSandboxDocker` (test-fixtures). List
      `ContainerEnvironmentBuilder` in `:sandbox:docker`'s `pitest { excludedClasses }` with the
      arid-wiring rationale naming `ContainerEnvironmentsSeamSpec` and
      `ContainerTaskExecutionEnvironmentUnitSpec`. Verify each signature is at seven or fewer,
      `:sandbox:docker:check :adapters:git:check :bootstrap:check :test-fixtures:check` pass with
      no expectation edited (NFR-R2), and run the two D11 sweeps from the single-owner table,
      recording their output.
      *Report:* every signature of D11's table is at its "after" count — `forTask` 7,
      `ContainerEnvironments` ctor 3, `ContainerEnvironmentBuilder` ctor 7 + `build(key)`,
      `ContainerTaskExecutionEnvironment` ctor 7, `ContainerMaterializer` ctor 5 + `reattach(name,
      inspect, branch, pin)` + `create(branch, pin)`. `ObjectOwnership` became public (it sits on the
      public `forTask`). Three deviations from D11's letter, none from its intent: (1) the image
      check is the record's explicit canonical constructor, not a compact one — the parameter is
      `@Nullable` because `SandboxProperties.image()` is, while the component stays non-null, which a
      compact constructor cannot express under NullAway; (2) the settings are read off
      `SandboxProperties` in `build(key)`, not in the builder's constructor, so an unset image still
      fails at the first environment build, exactly where `requireImage` fired before; (3) a
      `TaskContainerSettings.of(SandboxProperties)` factory was written and then inlined into the
      builder — its only caller is the excluded builder, so PIT reported its null-return mutant as
      the one survivor. `:sandbox:docker:check` passes (PIT 596/596; one RUN_ERROR on an untouched
      class, `DockerUnavailableException`, on a single run — the dead-minion flake
      `pitest-conventions` documents — killed on the rerun). `:test-fixtures:check` and
      `:adapters:git:check` pass. `:bootstrap:check`: PIT 868/868, 1970 tests, 2 failures unrelated
      to this task and pre-existing from task 1.1 — `GitTransferBoundarySpec` ("scanned source
      roots are exactly the build's module set": `build-checks/parameter-count-check` and
      `…-annotations` scanned, `build-checks` declared) and `ModuleBuildFileSpec` (the three
      `build-checks/**/build.gradle` files apply no build-logic convention). Both need a decision
      about how `build-checks` joins those two scans; left for the change owner. No test
      expectation was edited; every spec change is a constructor call site. Sweeps:
      `grep -rn "new ContainerTaskExecutionEnvironment(" sandbox adapters bootstrap test-fixtures`
      → `ContainerEnvironmentBuilder.java:69` plus the ten unit/integration specs that construct
      the environment directly (four in `sandbox/docker`, six in `adapters/git`);
      `grep -rn "requireImage\|factory.sandbox.image must be set" sandbox/docker/src/main` →
      `TaskContainerSettings.java` only (lines 14, 29, 35, 37). `ContainerEnvironmentBuilder` is
      listed in `:sandbox:docker`'s `pitest { excludedClasses }` naming the two suites.
      *Revised 2026-09-28 (audit):* the exclusion was removed — the builder computes the
      `scrubsCredential` probe, so it fails the arid-wiring bar (D11); `:sandbox:docker` PIT
      600/600 with its four mutants killed by `ContainerEnvironmentsSeamSpec`.
      *Also changed, outside D11's table:* `ContainerRunSupportFactory` (bootstrap) turned from a
      `final class` into a `record` keeping its explicit `create` — the record-method shape D8 names
      as the hcoles/pitest#1285 risk; `:bootstrap` PIT ran it without a RUN_ERROR (`create` KILLED,
      868/868), so it stays in the mutation scope as ADR 0010 requires.
- [x] 2.4 Bring `GithubMarkerJson` ctor:62 and `PipelineModelBuilder.mapAndValidate:50`
      under the limit (D12): move the eight fields into `record GithubMarkerFields` (components
      only; `@JsonInclude(NON_NULL)`, `@JsonPropertyOrder` and `@JsonProperty` per component, no
      `@JsonCreator`), keep `serialize`/`deserialize`/`identity` in `GithubMarkerJson` as the codec
      over it, replace the "plain final class rather than a record" javadoc with the `TaskJsonDto`
      precedent, and update `GithubMarker.render:134`/`parse:167`; make `mapAndValidate` take the
      `ParsedTree` whole (6) and update `PipelineLoader:212`. Verify the marker's wire key order
      and NON_NULL omission are unchanged (the existing round-trip specs stay green unedited),
      and `:adapters:github:check` and `:adapters:check` pass with no expectation edited —
      including PIT over the record's accessors.
      *Report:* new `record GithubMarkerFields` (eight components, `@JsonInclude(NON_NULL)`,
      `@JsonPropertyOrder`, `@JsonProperty` per component, no `@JsonCreator`, no methods);
      `GithubMarkerJson` is now the stateless codec — `serialize(fields)`, `deserialize(json)`,
      `identity(fields)` — with the "plain final class" javadoc replaced by the `TaskJsonDto`
      precedent. `GithubMarker.render`/`parse` updated at the call site. `mapAndValidate` takes
      `ParsedTree` whole (6 parameters); `PipelineLoader` passes `tree`. Both remaining signatures
      at ≤7. `:adapters:github:check` and `:adapters:check` pass with no expectation edited;
      PIT `:adapters:github` 599/599, `:adapters` 1005/1005, no RUN_ERROR on the record's
      accessors, so no `@DoNotMutate` was needed. Pre-existing and untouched: `GithubMarker.java`
      stands at 231 lines, over the file-size cap, before and after this task.
- [x] 2.5 Split `FeedAutomaton` (D7): add package-private `FeedAssembly` in `app.serve`,
      constructed with `Sleeper, Clock, IdleTiming, int wipLimit`, whose
      `feedAutomaton(Tracker, InstanceId, SlotLedger, SlotRunner, DirtyNotifier,
      RemoteOutageGate)` builds `FeedTracker`, `FeedOutageRetry` (with its
      `RepeatSuppressor.system()` default), `FeedResilience`, `FeedCycle` and
      `FeedViewTracker` and returns the automaton; reduce `FeedAutomaton`'s constructor to
      `SlotLedger, Sleeper, Clock, IdleTiming, int wipLimit, FeedCycle, FeedViewTracker`;
      route `ServeAssembly.feedAutomaton:122` and `FeedAutomatonFixture` through
      `FeedAssembly`, update `RemoteOutageServeEndToEndSpec:150` at the call site; list
      `FeedAssembly` in `:application`'s `pitest { excludedClasses }` with the arid-wiring
      rationale naming `ServeRuntimeWiringSpec` and `FeedAutomatonOutageIntegrationSpec`.
      Verify the constructor is at seven, `step`/`drain`/`view` are byte-for-byte unchanged,
      `:application:check :bootstrap:check :test-fixtures:check` pass with no expectation
      edited, and the sweep `grep -rn "new FeedCycle(\|new FeedViewTracker(" application
      test-fixtures` shows only `FeedAssembly`.
      *Report (2026-09-27):* `FeedAssembly` added in `app.serve`, holding `Sleeper, Clock, IdleTiming,
      int wipLimit`; `feedAutomaton(...)` (6) builds the five collaborators and returns the automaton.
      `FeedAutomaton`'s constructor is at seven and **package-private** (its `FeedCycle` and
      `FeedViewTracker` parameters are package-private types), so `FeedAssembly` is the one door;
      `step`/`drain`/`view`/`run` are byte-identical to `HEAD` (diffed). The state logger is now the
      cycle's own instance, read back through the record accessor, so `step()` and the cycle's
      slot-filled vantage point keep sharing one latch. One deviation from D7's letter: `FeedAssembly`
      is `public`, not package-private — `ServeAssembly` lives in `app`, one package up, and cannot
      reach a package-private type in `app.serve`. Callers updated at the call site only:
      `ServeAssembly.feedAutomaton` (unchanged signature), `FeedAutomatonFixture`,
      `RemoteOutageServeEndToEndSpec`. `FeedAssembly` listed in `application/verification.gradle`'s
      `excludedClasses` with the arid-wiring rationale. Verification: `:test-fixtures:check` passes;
      `:application:test` 2488 tests, 1 failure — `ServeAssemblyEquipmentSpec` "writes its ledger",
      **pre-existing and calendar-dependent**, unrelated to this task: it pins the writer's clock at
      `2026-09-27T10:00Z` while `new SlotLedger(1)` stamps `since` from the system clock, so once the
      wall clock passed that instant `TaskSummary.wall` went negative; `:bootstrap:test` 1970 tests,
      the 2 known `build-checks` failures only (`GitTransferBoundarySpec`, `ModuleBuildFileSpec`). Those
      red specs block PIT ("requires a green suite"), so `:application:pitest` was run once with the
      calendar-broken spec temporarily excluded (edit reverted): 2299 mutations, 2295 killed, 0
      survived; the 4 `NO_COVERAGE` are `ServeAssembly`'s other builders, covered only by the excluded
      spec — `FeedAutomaton` and `ServeAssembly.feedAutomaton` fully killed. No expectation edited.
      Sweep output: `FeedAssembly.java:73: var cycle = new FeedCycle(` and `FeedAssembly.java:81: var
      viewTracker = new FeedViewTracker(` (in `src/main`; the `FeedCycle`/`FeedViewTracker` unit specs
      construct their own subjects as before).
- [x] 2.6 Bring `AbortHandler.handle:126` under the limit (D8): add `record AbortTrigger(
      RecoveryCause category, @Nullable Throwable crash)` in `app.take` with the factories
      `engineAborted(RecoveryCause)` and `crashed(RecoveryCause, Throwable)`; add
      `AbortFuse.handle(ref, finalState, cause, facts, instanceId, trigger)` (6) relaying its own
      threshold; collapse `AbortHandler`'s three overloads into one package-private
      `handle(ref, finalState, cause, facts, threshold, instanceId, trigger)` (7); route
      `TakeCrashAbort:82` and `TakeOutcomeDispatch:72` through `abortFuse.handle(...)`; update
      `AbortHandlerSpec` and `AbortCauseCapWiringSpec` at the call site only. Verify
      `:application:check` passes with no expectation edited; if PIT reports RUN_ERROR on
      `AbortFuse.handle`, mark it `@DoNotMutate` under the JVMTI reason naming the covering
      suites (D8, Risk named), and record that. Sweep:
      `grep -rn "handler().handle(\|abortFuse.threshold()" application/src/main bootstrap/src/main`
      must be empty; record the output.
      *Report:* `AbortTrigger` (record, two factories) added; `AbortFuse.handle` at six parameters
      relays its own threshold; `AbortHandler`'s three overloads collapsed into one package-private
      seven-parameter `handle(…, int threshold, InstanceId, AbortTrigger)` — the log branch reads
      `trigger.crash()`; `TakeCrashAbort.onCrash` and `TakeOutcomeDispatch.dispatch` call
      `abortFuse.handle(...)` with `AbortTrigger.crashed(categoryOf(crash), crash)` /
      `AbortTrigger.engineAborted(RecoveryCause.INSTANCE_CRASH)`. `AbortHandlerSpec` and
      `AbortCauseCapWiringSpec` edited at the call site only (trailing category/crash arguments →
      one trigger); `:bootstrap:compileTestGroovy` passes untouched. `:application:check` passes,
      PIT 2299/2299 killed. PIT first reported RUN_ERROR (zero tests run) on the null-return
      mutation of `AbortTrigger.engineAborted` — the explicit-method-inside-a-record shape of
      hcoles/pitest#1285 that D8 named as a risk for `AbortFuse.handle`, which itself mutated cleanly
      — so that one factory carries `@DoNotMutate` under the JVMTI reason, naming `AbortHandlerSpec`,
      `AbortCauseCapWiringSpec` and the `TakeOutcomeDispatch` specs as its cover; `crashed` stays in
      the mutation scope. A second run showed one TIMED_OUT on the untouched `IdleTiming.selection`,
      killed on the runs before and after (a load transient, not a gap). Sweep output: empty.
- [x] 2.7 Bring `BoardModel.build:130` under the limit (D8): add `record EligibilityInputs(
      Duration base, Duration cap, int openFrontCount, int wipLimit)` in `board`,
      taken by `BoardModel.build` (5) and `EligibilityPolicy.resolve` (3, with `generatedAt`); update
      `BoardComposition:53` (production) and the three test callers of the nine-parameter form
      — `BoardReferenceFixture`, `BoardModelEligibilitySpec`, `BoardJsonMapperSpec` — at the call
      site only; the four-parameter `build` overload is untouched. Verify `:application:check`
      passes with no expectation edited.
      *Report:* `record EligibilityInputs(base, cap, openFrontCount, wipLimit)` added in
      `board` (components only, no validation — NullAway owns the non-null contract);
      `BoardModel.build` at five parameters, `EligibilityPolicy.resolve` at three — `now` left the
      record after review: every caller passed `generatedAt` for it, so `build` now does (D8), the
      four-parameter `build` overload untouched and building its defaults into the record.
      `BoardComposition` and the three test callers (`BoardReferenceFixture`,
      `BoardModelEligibilitySpec`, `BoardJsonMapperSpec`) edited at the call site only.
      `:application:check` passes, PIT 2299/2299. Found on the way: `ServeAssemblyEquipmentSpec`
      built its `SlotLedger` on the system clock against a writer fixed at
      `2026-09-27T10:00Z`, so it went red after that hour; the ledger now takes the spec's own
      `ENGINE_CLOCK` (fixture fix, expectation unchanged).
- [x] 2.8 Run the parameter-count scan over every module's `src/main`, counting a method
      housed in a record and exempting only record constructors and overrides (the gate's
      own rule), and verify the count is zero before the gate is wired (G1, NG5); record the
      scan output in the task report.
      *Report (2026-09-27):* scanned with the gate's own rule by wiring the two dependency lines
      into `java-conventions.gradle` temporarily and running `./gradlew compileJava --continue`
      over the whole build: every module's `compileJava` (20 of them, `:test-fixtures` has no
      Java) compiled with `[ParameterCountLimit]` on the processor path and reported **zero**
      diagnostics. The check was proven active in the same wiring: a planted eight-parameter
      method in `:atomicfile` failed with `error: [ParameterCountLimit] 8 parameters; the limit
      is 7. …`, then was removed. The temporary lines were reverted (`git status` shows
      `java-conventions.gradle` unchanged); task 3.1 wires them for good.

## 3. Switching the gate on

- [x] 3.1 Wire the two dependency lines into `java-conventions.gradle` so every module gets
      the check and the annotation with no per-module configuration (FR5), at error
      severity; verify a deliberately added eight-parameter method in one module fails
      `compileJava`, then remove it.
      *Report (2026-09-27):* the two lines sit in the shared `dependencies` block beside
      `errorprone-core` and `nullaway`; severity is the check's own `ERROR`. A planted
      eight-parameter method in `:atomicfile` failed `:atomicfile:compileJava` with
      `error: [ParameterCountLimit] 8 parameters; the limit is 7. …`, then was removed
      (`git status` shows no leftover).
- [x] 3.2 Add the wiring scenario to `build-logic`'s `functionalTest` (D10, NFR-R1): a
      miniature project applying `java-conventions`, whose settings `includeBuild` the real
      `build-checks` directory passed as a system property, fails `compileJava` on an
      eight-parameter method and passes on seven; verify the scenario fails when the two
      lines are removed from `java-conventions.gradle` (M2).
      *Report (2026-09-27):* `ParameterCountGateFunctionalSpec`, two features. The mini
      settings import the repository's catalog file and `includeBuild` the real `build-checks`
      directory, both passed as system properties (`gnomish.buildChecksDir`,
      `gnomish.versionCatalog`) and declared as inputs of `functionalTest`. Green with the
      wiring; with the two lines deleted from `java-conventions.gradle` the eight-parameter
      feature failed (`2 tests completed, 1 failed`), then the lines were restored. Note:
      the suite runs through the root (`./gradlew :build-logic:functionalTest`) — `-p
      build-logic` skips the daemon-JVM pin and compiles under the machine's default JDK.
- [x] 3.3 Run `./gradlew check` across every module with the gate on; verify it passes with
      zero exemption annotations in the repository (M1, M3) —
      `grep -rn "ParameterLimitExemption" --include='*.java' . | grep -v build-checks` is
      empty; record that.
      *Report (2026-09-27):* the grep is empty — the only two hits in the repository are the
      annotation's own declaration and the check that reads it. `./gradlew check --continue`
      over the whole build (44 min 46 s, PIT included) was green in every module but
      `:bootstrap`, then `:bootstrap:check` passed (24 min 45 s, 0 surviving mutants) after
      three findings, none a regression of the thirteen refactors:
      (1) `verifyModuleLayering` reported every module "reaching
      `:parameter-count-annotations`" — the gate counted a project of the included build as a
      sibling; it now counts only projects of the module's own build (design D9, revised);
      (2) `GitTransferBoundarySpec` enumerates source roots from `settings.gradle` and read
      `includeBuild 'build-checks'` as one root while the walk found its two subprojects — an
      included build now contributes its own `include` entries; `ModuleBuildFileSpec` scanned
      the `build-checks` build files for a convention id, which they cannot carry (D9's
      exemption by layout) — they are excluded beside `build-logic`'s;
      (3) root `check` compiled `build-checks` but never ran `ParameterCountLimitSpec` (the
      `build-logic` precedent, FR9 of add-functional-api-gate-test) — root `build.gradle` now
      depends on `:parameter-count-check:check` of the included build; the spec runs (12 of
      12) under `./gradlew check`. One flake seen and not reproduced: `GithubFeedQuerySpec`
      "filters out entries carrying a pull_request field" got HTTP 404 from its own WireMock
      stub once; the module was green on rerun and in the `--continue` run.
- [x] 3.4 Measure clean-build wall time before and after — median of three
      `./gradlew build --no-build-cache --rerun-tasks` runs each, same machine — and verify
      the after-median is within 5 % of the before-median (NFR-C1, M4); record all six
      numbers.
      *Report (2026-09-27/28, one machine, 14 cores / 36 GB, nine heavy JVMs):* measured as
      `./gradlew check --no-build-cache --rerun-tasks --continue`, not `build` — `build` is
      `assemble` + `check`, and `assemble` is red on this tree for a reason outside the change
      (`:gnomish-plugin-api:javadoc` fails on the untouched `ExternalCheckPinContributor.java:11`,
      a `{@link}` to a class outside the module; CI runs `check`, so it never surfaced — reported
      to the human, not fixed here). `--continue` keeps a flaky test from cutting a run short. In
      the "before" posture (the two wiring lines removed) the wiring spec of task 3.2 was set
      aside, since it must fail without the wiring (M2); everything else was identical.
      Before: 3429 s, 3503 s, 3483 s — median **3483 s**. After: 3385 s, 3454 s, 3445 s —
      median **3445 s**. After/before = 0.989, a −1.1 % difference, inside the noise and well
      within the 5 % bound (M4). Three of the six runs ended with `rc=1` on load-induced flakes,
      none in code this change touches, each completing every task: before-1
      (`UsageCommandSpec` NoSuchFileException, `TakeSlotRunnerContainerConcurrencySpec` — the
      known race, `:subprocess:pitest`), after-1 (`GithubWorkflowRunPollWireMockSpec` inside
      PIT's coverage pass), after-2 (`UsageCommandSpec` again, `FactoryCloneHardeningSpec`).
      The tree was restored and verified after the runs: the wiring lines present, the spec
      byte-identical.
- [x] 3.5 Regenerate the affected dependency lockfiles (root, `build-checks`, every module
      that gained the two compile-time lines) and verify OSV-Scanner's inputs still resolve,
      per the locking convention in `java-conventions.gradle`.
      *Report (2026-09-27):* both full `check` runs above ran with `--write-locks`; no module
      lockfile changed. The two coordinates are substituted by the included build, and Gradle
      locks module components only, so a project dependency leaves no lockfile entry; the
      check's own transitives are internal (`parameter-count-annotations`) or `compileOnly`.
      The `build-checks` lockfiles (`settings-gradle.lockfile`, one `gradle.lockfile` per
      project) were generated at task 1.1 and are unchanged; OSV-Scanner's workflow walks every
      `gradle.lockfile` in the tree, so they are scanned without a workflow edit.

## 4. Rules and closing the loop

- [x] 4.1 Rewrite the parameter-count section of `.claude/rules/process-invariants.md`
      (FR7): the limit, the two exemptions and why each is by construction (record
      constructors only, overrides), `ParameterLimitExemption` with its "reason on the
      declaration" requirement and the absence of any bulk form, and the gate as the named
      enforcement — replacing the "a mechanical build gate should land together with..."
      sentence, which this change discharges.
- [x] 4.2 Add D4's criterion to the same section: when a record earns the exemption
      (recurring group, domain name, absorbed behavior) and when it is an argument bag
      wearing it; verify the text names the three tests explicitly, since no gate can apply
      them.
- [x] 4.3 Add glossary entries in `docs/glossary.md` for `AgentRoundEquipment` (the
      equipment one agent round is launched with), `FeedAssembly` (the serve feed's assembly
      object), `TaskContainerSettings`, `BoxGitLink` and `BoxTiming` (D11), `AbortTrigger` and
      `EligibilityInputs` (D8), and `GithubMarkerFields` (D12), following the entries
      `collapse-composition-roots` added for its facades; verify no banned synonym is introduced.
- [x] 4.4 Amend ADR 0010's `FeedAutomaton` row (`docs/adr/0010-facade-over-parameter-object.md`)
      from "left over the limit at ten; follow-up" to the split this change made, citing D7.
- [x] 4.5 Verify the whole family's outcome end to end: 63 violations at the start of
      `introduce-take-order`, 0 now, gate on, and record the final scan beside the
      2026-09-12 baseline.
      *Report (2026-09-28):* the chain, each figure from the artifact that recorded it —
      **63** on 2026-09-12 (the investigation `introduce-take-order` opened with), **65** on
      2026-09-23 at `ceec1e30` (that change's fresh baseline, task 0), **13** on 2026-09-27
      after `collapse-composition-roots` (this proposal's Impact, under FR2's narrowed record
      exemption), **0** now with the gate on: every module's `compileJava` runs
      `ParameterCountLimit` and the full `check` of task 3.3 is green with no
      `ParameterLimitExemption` anywhere. The 2026-09-12 baseline has no durable home outside
      the archived `introduce-take-order` tasks and this change's proposal, so the final figure
      is recorded here and in the proposal's Impact beside the baseline it closes.
- [x] 4.6 Recommend a Conventional Commits subject line for the diff since the last commit
      (the agent never commits).
      *Report:* given in the session's closing message. The working tree also holds untracked
      artifacts of other changes (`openspec/changes/add-doctor-command`, `add-project-registry`,
      `add-release-pipeline`, `add-sandbox-base-image`, `fix-operator-blockers`,
      `make-run-headless`, `remove-interactive-console`, two `temporary-docs` notes) that are
      not part of this change and should be staged separately.

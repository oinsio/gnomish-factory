## 0. Sequencing

- [ ] 0.1 Confirm `fix-operator-blockers` task 2.2 has landed (its role-passing edits to
      `ExecutorRoundExecution` and `JudgeRoundExecution`), or record in the task report that
      this change is being applied first and D6 will be rebased under it; verify with
      `git log --oneline -- adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/ExecutorRoundExecution.java`.

## 1. The check, built and proven before anything depends on it

- [ ] 1.1 Add the `build-checks` included build (D9): its `settings.gradle` importing the
      shared catalog by file as `build-logic/settings.gradle` does, two Java projects
      `parameter-count-check` and `parameter-count-annotations` under group
      `com.github.oinsio.build`, dependency locking on, `includeBuild 'build-checks'` in the
      root `settings.gradle`; add `errorprone-check-api` and `errorprone-test-helpers` to
      `gradle/libs.versions.toml` on the existing `errorprone` version ref. Verify
      `./gradlew -p build-checks build` passes standalone and both lockfiles are generated.
- [ ] 1.2 Implement `ParameterLimitExemption` in `parameter-count-annotations` (D5):
      `@Target({METHOD, CONSTRUCTOR})`, source retention, one element `String reason()`, no
      default; verify the artifact has no dependencies.
- [ ] 1.3 Implement the check in `parameter-count-check`: report a constructor or method with
      more than seven parameters, with a message naming the count, the limit and the
      transformation to use (FR1, NFR-O1); declare
      `@BugPattern(suppressionAnnotations = ParameterLimitExemption.class)` and report an
      annotated site whose reason is blank (FR4); verify the message reads usefully on its
      own, without the rule file.
- [ ] 1.4 Implement the two exemptions from the syntax tree (D3): never report a record's
      canonical, compact or explicit constructor (FR2) — an ordinary method of a record is
      reported; never report an overriding or implementing method (FR3).
- [ ] 1.5 Write the unit spec in `parameter-count-check` on `CompilationTestHelper` (D10,
      NFR-R1) covering the seven cases: eight parameters fails, seven passes, a record
      constructor passes, a method housed in a record with eight parameters fails, an
      override passes, an annotated site with a reason passes, a blank reason fails, and
      `@SuppressWarnings` on the enclosing class does not suppress; verify each case is red
      with the corresponding branch of the check removed (M2).
- [ ] 1.6 Decide the wiring question (Risks): against one consuming module, add
      `errorprone 'com.github.oinsio.build:parameter-count-check'` and
      `compileOnly 'com.github.oinsio.build:parameter-count-annotations'` by hand and confirm
      the included-build substitution puts the check on that module's processor path and the
      annotation on its compile classpath (a deliberate eight-parameter method fails
      `compileJava`, then is removed). If it does not, stop and take D1's recorded fallback,
      writing down the approximation limits accepted; verify the decision and its reason are
      in the task report either way, and revert the hand edit.

## 2. The last thirteen signatures (FR6)

- [ ] 2.1 Introduce `AgentRoundEquipment` (D6) as one record over `FactoryProperties, Clock,
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
- [ ] 2.2 Add the `Kept in sync with` marker to both ends of the newly declared
      executor/judge round pair, naming `AgentRoundEquipment` and the launch/failure invariant;
      verify `grep -rn "Kept in sync with" adapters/agent/src/main` returns both ends (plus
      the pre-existing `HostRoundEnvironmentSource` marker). No registry row is added
      (design, Sync surfaces).
- [ ] 2.3 Bring the container environment builders under the limit —
      `ContainerEnvironments` ctor:106 and `forTask:64`,
      `ContainerTaskExecutionEnvironment` ctor:83, `ContainerMaterializer.create:74` and
      `reattach:42`, `ContainerEnvironmentBuilder.build:22`; verify each takes seven
      parameters or fewer and `:sandbox:docker:check :adapters:git:check :test-fixtures:check`
      pass with no expectation edited — the six container-environment specs under
      `adapters/git/src/test/.../sandbox/environment/` and `ScriptedSandboxDocker` in
      `test-fixtures` are call-site updates only (NFR-R2).
- [ ] 2.4 Bring `GithubMarkerJson` ctor:62 and `PipelineModelBuilder.mapAndValidate:50`
      under the limit; verify `:adapters:github:check` and `:adapters:check` pass with no
      expectation edited.
- [ ] 2.5 Split `FeedAutomaton` (D7): add package-private `FeedAssembly` in `app.serve`,
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
- [ ] 2.6 Bring `AbortHandler.handle:126` under the limit (D8): the per-run constants its
      one caller `TakeCrashAbort:82` supplies (`threshold`, `instanceId`) join the record's
      components; verify the three `handle` overloads are at seven or fewer and
      `:application:check` passes with no expectation edited.
- [ ] 2.7 Bring `BoardModel.build:130` under the limit (D8): the eligibility inputs it hands
      to `EligibilityPolicy.resolve` (`base, cap, now, openFrontCount, wipLimit`, or the
      subset the policy names as one concept) travel as one value the policy also takes;
      verify `BoardComposition:53` is the only caller updated and `:application:check` passes
      with no expectation edited.
- [ ] 2.8 Run the parameter-count scan over every module's `src/main`, counting a method
      housed in a record and exempting only record constructors and overrides (the gate's
      own rule), and verify the count is zero before the gate is wired (G1, NG5); record the
      scan output in the task report.

## 3. Switching the gate on

- [ ] 3.1 Wire the two dependency lines into `java-conventions.gradle` so every module gets
      the check and the annotation with no per-module configuration (FR5), at error
      severity; verify a deliberately added eight-parameter method in one module fails
      `compileJava`, then remove it.
- [ ] 3.2 Add the wiring scenario to `build-logic`'s `functionalTest` (D10, NFR-R1): a
      miniature project applying `java-conventions`, whose settings `includeBuild` the real
      `build-checks` directory passed as a system property, fails `compileJava` on an
      eight-parameter method and passes on seven; verify the scenario fails when the two
      lines are removed from `java-conventions.gradle` (M2).
- [ ] 3.3 Run `./gradlew check` across every module with the gate on; verify it passes with
      zero exemption annotations in the repository (M1, M3) —
      `grep -rn "ParameterLimitExemption" --include='*.java' . | grep -v build-checks` is
      empty; record that.
- [ ] 3.4 Measure clean-build wall time before and after — median of three
      `./gradlew build --no-build-cache --rerun-tasks` runs each, same machine — and verify
      the after-median is within 5 % of the before-median (NFR-C1, M4); record all six
      numbers.
- [ ] 3.5 Regenerate the affected dependency lockfiles (root, `build-checks`, every module
      that gained the two compile-time lines) and verify OSV-Scanner's inputs still resolve,
      per the locking convention in `java-conventions.gradle`.

## 4. Rules and closing the loop

- [ ] 4.1 Rewrite the parameter-count section of `.claude/rules/process-invariants.md`
      (FR7): the limit, the two exemptions and why each is by construction (record
      constructors only, overrides), `ParameterLimitExemption` with its "reason on the
      declaration" requirement and the absence of any bulk form, and the gate as the named
      enforcement — replacing the "a mechanical build gate should land together with..."
      sentence, which this change discharges.
- [ ] 4.2 Add D4's criterion to the same section: when a record earns the exemption
      (recurring group, domain name, absorbed behavior) and when it is an argument bag
      wearing it; verify the text names the three tests explicitly, since no gate can apply
      them.
- [ ] 4.3 Add glossary entries in `docs/glossary.md` for `AgentRoundEquipment` (the
      equipment one agent round is launched with) and `FeedAssembly` (the serve feed's
      assembly object), following the entries `collapse-composition-roots` added for its
      facades; verify no banned synonym is introduced.
- [ ] 4.4 Amend ADR 0010's `FeedAutomaton` row (`docs/adr/0010-facade-over-parameter-object.md`)
      from "left over the limit at ten; follow-up" to the split this change made, citing D7.
- [ ] 4.5 Verify the whole family's outcome end to end: 63 violations at the start of
      `introduce-take-order`, 0 now, gate on, and record the final scan beside the
      2026-09-12 baseline.
- [ ] 4.6 Recommend a Conventional Commits subject line for the diff since the last commit
      (the agent never commits).

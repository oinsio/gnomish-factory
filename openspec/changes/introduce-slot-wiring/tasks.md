## 0. Precondition

- [x] 0.1 Verify `fix-claim-epoch-fence` has landed: `TaskGit` exposes `epochs()` and
      `grep -rnE "ClaimEpochBook epochs|ClaimEpochSource epochs" application/src/main
      bootstrap/src/main` returns only the known hits (2026-09-25), each either the owner or
      fed from `git.epochs()`: `TaskGit.java:51,58,68` (the owner's own constructors);
      `TrackerResolution.java:82` (`resolveTracker`, fed `git.epochs()` by
      `TakeCommand.java:174` and `ServeCommand.java:235`); and in `:bootstrap`
      `ContainerRunSupport.java:75,90,139`, `ContainerRunSupportFactory.java:70` and
      `ManualRunRunner.java:282`, fed `git.epochs()` at `ManualRunRunner.java:224,236` (D5).
      Any other hit, or one of these fed from anywhere but `git.epochs()`, is a second owner
      of the tenure book: stop.

## 1. The wiring types

- [x] 1.1 Reuse the existing `AbortFuse` record (`app.take`) as the abort pair (FR2) — no
      new type; verify an `AbortFuseSpec` pins its rejection of a non-positive threshold
      (adding it if absent) and that `TakeCrashAbort` takes the fuse rather than the pair.
- [x] 1.2 Add `ClaimTenure` as a record carrying the claim beat and the claim-loss flag —
      and NOT the epoch book (FR3, D5) — and a `TakeHeartbeat.tenure()` accessor that is its
      only production construction; verify the epoch grep of 0.1 returns the same hits as
      before (no `ClaimTenure` or `SlotWiring` among them), that
      `grep -rn "new ClaimTenure(" application/src/main bootstrap/src/main` returns only
      `TakeHeartbeat`, that a spec asserts `tenure()` carries
      the same `ClaimLossFlag` instance as `flag()`, and that a spec pins the
      `ClaimBeat.NONE` combination the specs already pass.
- [x] 1.3 Add `SlotWiring` as a record with exactly the nine components of FR1 (D1); verify
      `:application:compileJava` passes and that the record exposes no accessor beyond its
      components at this point.
- [x] 1.4 Add glossary entries for `slot wiring` and `claim tenure` (the existing *abort fuse*
      entry already names the abort pair) to
      `docs/glossary.md`, per `process-invariants.md`; verify each names its type and
      states the lifetime that distinguishes it from the order, and that `claim tenure`
      matches the glossary's existing use of *tenure* (one holding of a claim, identified by
      its claim epoch) and says it carries the tenure's beat and loss signal but not the
      epoch.

## 2. Recipes become objects (D2, FR5)

- [x] 2.1 Convert `TakeClaimAndWorkFactory` from a static factory taking eleven parameters
      into an instance constructed with `SlotWiring`; verify it takes one constructor
      argument and that `TakeClaimAndWork`'s specs pass unedited.
- [x] 2.2 Convert `TakeFreshClaim` and `TakeContainerFreshClaim` — both ends of the
      declared pair together (FR6) — from static recipes into instances holding
      `SlotWiring`, their `claim`/`claimAt` steps becoming methods; verify all four methods
      take the order (plus `segments` on the container side, D4) and that both classes'
      specs pass unedited.
- [x] 2.3 Convert `TakeWorkRouter` into an instance that `TakeClaimAndWork` constructs once
      from `(SlotWiring, TakeResumeRunner, TakeContainerResumeRunner)` and holds (D2); verify
      `grep -n "TakeClaimAndWork w" application/src/main/java/com/github/oinsio/gnomish/app/TakeWorkRouter.java`
      returns nothing (today four hits: `:31`, `:62`, `:96`, `:113`), that
      `locateAndWork`, `freshClaim` and `resume` are instance methods taking the order (and
      the shape for `resume`) only, and that the routing specs pass unedited.
- [x] 2.4 Re-read the javadoc of each converted class and replace the "split purely to
      respect the file-size guidance" sentence with the responsibility the class now owns;
      verify no converted class still claims a split that transferred no ownership (M3).

## 3. Constructors take the wiring

- [x] 3.1 Change `TakeClaimAndWork` ctor:66, `TakeResumeRunner` ctor:63,
      `TakeContainerResumeRunner` ctor:48 and `TakeResumeExecution` record:26 to take
      `SlotWiring`, moving both resume-runner ends together (FR6); verify each drops to
      three constructor parameters or fewer and their specs pass unedited.
- [x] 3.2 Change `TakeDisposition` ctor:76, `TakeBareAuto` ctor:73 and `TakeSlotRunner`
      ctor:101 to take `SlotWiring`; verify each drops from 14–16 parameters to seven or
      fewer and the take/serve specs pass unedited.
- [x] 3.3 Build the take side's one `SlotWiring` in `TakeCommand.run`, right after
      `takeAssembly`:201 (design, "Where a `SlotWiring` is built"), with one `AbortHandler`
      for the invocation in place of the per-ref `TakeDispatcher.newAbortHandler:226`, and
      hand it to the `TakeDispatcher` record:34, which holds it in place of `git`,
      `worktreesRoot`, `taskIdMdcKey`, `containerTakeSupport` and `trustedBase`. Drop the
      relayed `credentialEnvVarsToScrub`, `takeAssembly` and `heartbeat` from
      `TakeDispatcher.runExplicit:46`, `runOneRef:77`, `runBare:133`, `runBatch:200`,
      `TakeBatch.dispatch:116` and `TakeRefDispatch.run:25`. Verify `TakeDispatcher` has six
      components; `runExplicit` and `runBatch` take seven parameters, `runBare` five, and
      `runOneRef`, `TakeBatch.dispatch` and `TakeRefDispatch.run` eight, eight and nine (the
      three residuals of the design's exemption row); the `TakeCommand` constructor and both
      `TakeCommandFactory.of` keep their signatures; and the take specs pass unedited.
- [x] 3.4 Build the serve side's one `SlotWiring` in `ServeRuntimeAssembly.assemble`, right
      after `serveAssembly`:81, moving the `AbortHandler` built at `ServeAssembly.slotRunner:62`
      with it, and pass it to `ServeAssembly.slotRunner:46` in place of the members it
      carries. Its `git` member is decorated there through a new public
      `RemoteOutageGates.signaling(TaskGit, RemoteOutageGate)` (D6); `TakeSlotRunner` drops
      its `RemoteOutageGate` parameter and its own `withBaseRefs` wrap. Adjust the three
      fixtures that construct `TakeSlotRunner`: `ServeShutdownWiringSpec` and
      `TakeSlotRunnerContainerConcurrencySpec` drop their inert gate; `TakeSlotRunnerSpec.
      newSlotRunner()` decorates through `RemoteOutageGates.signaling` so its gate scenario
      still sees the refresh — no expectation edited. Verify `slotRunner` takes seven
      parameters or fewer, the `ServeCommand` constructor, `ServeRuntimeAssembly.assemble`
      and `SubcommandDispatchFactory.of` keep their signatures, `grep -rn "new SlotWiring("
      */src/main` shows exactly the two assembly points of the design, `grep -rn
      "RemoteOutageSignalingBaseRefGit(" */src/main` returns only the record and
      `RemoteOutageGates`, and the serve specs pass unedited.

## 4. Rule amendment (FR7, D3)

- [x] 4.1 Extend the existing "splitting a file must split a responsibility" paragraph of
      the file-size section of `.claude/rules/process-invariants.md` (which already names
      "passing 20-parameter bundles" as a symptom) to state that a split converting fields
      into parameters is not a responsibility split, and to name the field-holding
      transformation as the correct one for an oversized collaborator-holding class;
      verify the new text cites the failure it prevents, in the shape the file's other
      sections use.
- [x] 4.2 Update the `Kept in sync with` sentences of the two pairs in the design's Sync
      surfaces table (neither pair has a row in `.claude/rules/manual-sync-pairs.md`, so
      the registry is not edited); verify `grep -rn "Kept in sync with" */src/main`
      still enumerates every marker in pairs and that no sentence names a removed parameter.

## 5. Old-way sweep and enforcement (design's single-owner table)

- [x] 5.1 Run `grep -rn "abortThreshold" application/src/main bootstrap/src/main` and
      verify no signature takes it beside a bare `AbortHandler`; record the grep, the hits
      and the disposition of each per `.claude/rules/implementation.md`.
      *Sweep 2026-09-25:* the first run found one unlisted survivor,
      `TakeOutcomeDispatch.dispatch` (`AbortHandler abortHandler, int abortThreshold`
      adjacent), fed by both engine executions unpacking their `AbortFuse` into the pair.
      Routed through the owner: it now takes `AbortFuse abortFuse` (9 → 8 parameters, still
      in the scan's residual list), both callers pass the fuse whole, and the design's
      `AbortFuse` row lists it as a consumer. Re-run: two hits, `TakeCommand.java:215` and
      `ServeRuntimeAssembly.java:111`, both `trackerConfig.abortThreshold()` read into
      `new AbortFuse(…)` at the two assembly points. No signature takes a threshold.
- [x] 5.2 Run `grep -rn "credentialEnvVarsToScrub\|worktreesRoot\|taskIdMdcKey\|RemoteOutageSignalingBaseRefGit("
      application/src/main bootstrap/src/main` and verify every surviving hit is either
      inside `SlotWiring` itself, at one of its two assembly points, inside a site named in
      the design's exemption row (the command constructors and factories upstream of the
      tracker, the two composition roots, the `ContainerRunSupport`/`ContainerRunSupportFactory`
      container-bundle builders, or the `GitResumeRunner`/`ContainerResumeRunner` manual
      resume pair and the `ManualRunRunner` that builds it), or a read through the wiring;
      record the hits and dispositions.
      *Sweep 2026-09-25* (grep as written; 41 files): every hit is one of —
      (a) `SlotWiring` itself; (b) the two assembly points `TakeCommand.run` and
      `ServeRuntimeAssembly.assemble`, plus `RemoteOutageGates.signaling` and the record's own
      declaration for the `RemoteOutageSignalingBaseRefGit(` term; (c) the exemption row:
      `TakeCommand` ctor, `TakeCommandFactory.of` ×2, `ServeCommand` ctor,
      `SubcommandDispatchFactory.of`, `ServeRuntimeAssembly.assemble` as a signature,
      `ContainerRunSupport`/`ContainerRunSupportFactory`, `GitResumeRunner`/`ContainerResumeRunner`
      and `ManualRunRunner`; (d) reads through the wiring — `TakeDispatcher`, `TakeBareAuto`,
      `TakeWorkRouter`, `TakeFreshClaim`, `TakeContainerFreshClaim`, `TakeResumeRunner`,
      `TakeContainerResumeRunner`, `TakeResumeExecution`, `TakeSlotRunner`; (e) not the
      slot's wiring at all — the same names as parameters of the git ports
      (`TaskStoreGit`, `TaskWorktreeGit`, `TaskWorktreePath`), of the claimless `run` path
      (`GitModeRunner`, `ManualRunAssembly`, `ManualRunConfiguration`, `RunAssembler`,
      `CheckProviderWiring`, `ContainerSupportFactory`, `RunAssembly.assemble`), and of
      `StatusCommand`, `WorktreeJanitor`, `ServeAssembly.worktreeJanitor`, `HostResumeMechanics`,
      `BareTakeClaimWalk`, `TakeResumeBootstrap`, `TakeContainerResumeBootstrap` — leaves
      each fed one or two members by a wiring-holding caller.
      One unlisted survivor class was found and **decided rather than wired**:
      `TakeEngineExecution` (5 members + `lawBinding`) and `TakeContainerEngineExecution`
      (3 + `lawBinding`) are leaf consumers built per run from `wiring.*` reads; they keep
      their exact member lists (design exemption row, 2026-09-25; rule "the parameter object
      stops at the last relay" added to `process-invariants.md`). No code change for this
      task; the code change of 5.1 (`TakeOutcomeDispatch` takes `AbortFuse`) stands, as that
      was a relayed adjacent pair, not a leaf's use.
- [x] 5.3 Verify NFR-S1: no `SlotWiring` value reaches a logger whole, and the change adds
      no new rendering of a credential name; record the grep used.
      *Checked 2026-09-25:* `grep -rnE "(log|LOG|logger)\.(trace|debug|info|warn|error)\([^;]*\b(wiring|tenure|slotWiring)\b"`,
      a multi-line (perl) pass over every log call in the files naming `wiring`, and a grep
      for concatenation, `String.valueOf`, `format`/`formatted` and `toString()` on
      `wiring`/`tenure`: no hits. `git diff HEAD` adds no line rendering a credential name.
      Neither record overrides `toString`; `SlotWiring`'s javadoc forbids logging it whole.
- [x] 5.4 Re-run the parameter-count scan over `src/main` and record the count; verify it
      has fallen from 44 to 32 (M2), with the scanner of `introduce-take-order` task 0.2
      (record members and `@Override` methods exempt); verify the 12 signatures the design's
      "Measured effect on the scan" list says drop are gone from it and that the design's
      consumer list has no survivor outside the stated exemptions.
      *Scan 2026-09-25:* the same scanner (JDK `JavacTask.parse` over every
      `*/src/main/java/**/*.java`) gives **44** on `HEAD` (calibration) and **32** on the
      working tree. The diff between the two lists is exactly the design's 12:
      `TakeClaimAndWorkFactory.forSlot`, `TakeClaimAndWork` ctor, `TakeDisposition` ctor,
      `TakeBareAuto` ctor, `TakeSlotRunner` ctor, `ServeAssembly.slotRunner`,
      `TakeFreshClaim.claim`/`claimAt`, `TakeContainerFreshClaim.claim`/`claimAt`,
      `TakeResumeRunner` ctor, `TakeContainerResumeRunner` ctor. Consumer-table survivors:
      `TakeBatch.dispatch` (8) and `TakeRefDispatch.run` (9), both in the exemption row.
      `TakeOutcomeDispatch.dispatch` moved 9 → 8 (task 5.1) and stays in the residual list.

## 6. Gates and handoff

- [x] 6.1 Run `./gradlew :application:check :bootstrap:check` and verify every gate passes
      — Spotless, Error Prone / NullAway, Spock, JaCoCo, PIT — with no spec expectation
      edited (NFR-R1, M4) and the log-expectation gate green with no expectation file
      edited (NFR-O1). UX1 is evidenced by the same run: the `:bootstrap` E2E and console
      specs pass with no expectation edited.
      *Run 2026-09-25:* the first pass failed PIT in `:application` on one `NO_COVERAGE`
      mutant — `RemoteOutageGates.signaling` (D6) returning null — because its only test
      caller, `TakeSlotRunnerSpec`, lives in `:bootstrap`, outside `:application`'s mutation
      scope. Closed by a new `:application` spec, `RemoteOutageGatesSignalingSpec`, which
      asserts the owner's own contract against a real gate on virtual time (the copy keeps
      every other capability; a read through it opens the gate); no expectation edited.
      Second pass: `BUILD SUCCESSFUL`, PIT `:application` 2275/2275 killed, `:bootstrap`
      225/225 killed, JaCoCo and both `pitestVerifyAllKilled` green; `checkLogExpectationGate`
      (the root, build-wide task) run separately and green; no expectation file in the diff.
      Its first run stopped in `:subprocess:test` on `ProcessSupervisorTreeKillSpec` ("a child
      forked while the tree is being killed"), a module this change does not touch; the spec
      passed alone on re-run and the gate's second run was green — recorded as a flake of a
      real-process race, not a regression of this change.
- [x] 6.2 Record the residual violations, grouped by cluster, as the input list for
      `collapse-composition-roots`; verify the list names each survivor by file and line.
      *Residual list 2026-09-25* (scanner of `introduce-take-order` 0.2 over the working
      tree; 32 total, every one already in the two follow-up changes' tables — the one stale
      cell, `TakeOutcomeDispatch.dispatch` 9 → 9 in the `collapse-composition-roots` design,
      corrected to 9 → 8 per task 5.1):
      **To `collapse-composition-roots` (22)** —
      *composition roots and command constructors (9):*
      `bootstrap/.../app/ManualRunRunner.java:152` ctor (28),
      `application/.../app/SubcommandDispatchFactory.java:30` `of` (19),
      `application/.../app/ServeRuntimeAssembly.java:52` `assemble` (19),
      `bootstrap/.../app/ManualRunAssembly.java:89` ctor (14) and `:125` ctor (10),
      `application/.../app/TakeCommand.java:109` ctor (16),
      `application/.../app/ServeCommand.java:94` ctor (16),
      `application/.../app/TakeCommandFactory.java:24` `of` (11) and `:53` `of` (12);
      *observability assembly (3):*
      `application/.../app/ObservabilityAssembly.java:103` `assemble` (16) and `:175`
      `assembleSnapshot` (14), `application/.../app/ObservabilityWiring.java:47` ctor (9);
      *feed automaton (4):*
      `application/.../app/serve/FeedAutomaton.java:65` ctor (11), `:104` ctor (12), `:142`
      ctor (13), `application/.../app/ServeAssembly.java:60` `feedAutomaton` (10);
      *take dispatch chain (3):*
      `application/.../app/TakeBatch.java:117` `dispatch` (8),
      `application/.../app/TakeRefDispatch.java:25` `run` (9),
      `application/.../app/TakeOutcomeDispatch.java:50` `dispatch` (8) — plus
      `TakeDispatcher.runOneRef` (8 by hand count, a record member outside the scan);
      *container bundle builders (2):*
      `bootstrap/.../app/ContainerRunSupport.java:130` `create` (9),
      `bootstrap/.../app/ContainerRunSupportFactory.java:61` `create` (9);
      *lease (1):* `application/.../app/lease/InstanceHeartbeat.java:118` ctor (8).
      **To `add-parameter-count-gate` (10)** —
      *sandbox environment builders (6):*
      `sandbox/docker/.../environment/ContainerEnvironmentBuilder.java:22` `build` (10),
      `.../ContainerEnvironments.java:64` `forTask` (11) and `:106` ctor (11),
      `.../ContainerMaterializer.java:42` `reattach` (9) and `:74` `create` (10),
      `.../ContainerTaskExecutionEnvironment.java:83` ctor (11);
      *agent round executions (2):*
      `adapters/agent/.../ExecutorRoundExecution.java:49` `run` (8),
      `adapters/agent/.../JudgeRoundExecution.java:48` `run` (8);
      *out-of-chain (2):* `adapters/github/.../GithubMarkerJson.java:61` ctor (8),
      `adapters/.../pipeline/PipelineModelBuilder.java:50` `mapAndValidate` (8).
- [x] 6.3 Recommend a Conventional Commits subject line for the diff since the last commit
      (the agent never commits). *Recommended 2026-09-25:*
      `refactor(take): introduce SlotWiring so the take chain holds its equipment as fields`.

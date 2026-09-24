## 0. Precondition

- [ ] 0.1 Verify `fix-claim-epoch-fence` has landed: `TaskGit` exposes `epochs()` and
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

- [ ] 1.1 Add `AbortPolicy` as a record in `:application` `com.github.oinsio.gnomish.app`
      carrying the abort handler and its threshold (FR2), rejecting a non-positive
      threshold in its compact constructor; verify a new `AbortPolicySpec` pins the
      rejection and that `TakeCrashAbort` takes the policy rather than the pair.
- [ ] 1.2 Add `ClaimTenure` as a record carrying the claim beat and the claim-loss flag —
      and NOT the epoch book (FR3, D5) — and a `TakeHeartbeat.tenure()` accessor that is its
      only production construction; verify the epoch grep of 0.1 returns the same hits as
      before (no `ClaimTenure` or `SlotWiring` among them), that
      `grep -rn "new ClaimTenure(" application/src/main bootstrap/src/main` returns only
      `TakeHeartbeat`, that a spec asserts `tenure()` carries
      the same `ClaimLossFlag` instance as `flag()`, and that a spec pins the
      `ClaimBeat.NONE` combination the specs already pass.
- [ ] 1.3 Add `SlotWiring` as a record with exactly the nine components of FR1 (D1); verify
      `:application:compileJava` passes and that the record exposes no accessor beyond its
      components at this point.
- [ ] 1.4 Add glossary entries for `slot wiring`, `abort policy` and `claim tenure` to
      `docs/glossary.md`, per `process-invariants.md`; verify each names its type and
      states the lifetime that distinguishes it from the order, and that `claim tenure`
      matches the glossary's existing use of *tenure* (one holding of a claim, identified by
      its claim epoch) and says it carries the tenure's beat and loss signal but not the
      epoch.

## 2. Recipes become objects (D2, FR5)

- [ ] 2.1 Convert `TakeClaimAndWorkFactory` from a static factory taking eleven parameters
      into an instance constructed with `SlotWiring`; verify it takes one constructor
      argument and that `TakeClaimAndWork`'s specs pass unedited.
- [ ] 2.2 Convert `TakeFreshClaim` and `TakeContainerFreshClaim` — both ends of the
      declared pair together (FR6) — from static recipes into instances holding
      `SlotWiring`, their `claim`/`claimAt` steps becoming methods; verify all four methods
      take the order (plus `segments` on the container side, D4) and that both classes'
      specs pass unedited.
- [ ] 2.3 Convert `TakeWorkRouter` into an instance that `TakeClaimAndWork` constructs once
      from `(SlotWiring, TakeResumeRunner, TakeContainerResumeRunner)` and holds (D2); verify
      `grep -n "TakeClaimAndWork w" application/src/main/java/com/github/oinsio/gnomish/app/TakeWorkRouter.java`
      returns nothing (today four hits: `:31`, `:62`, `:96`, `:113`), that
      `locateAndWork`, `freshClaim` and `resume` are instance methods taking the order (and
      the shape for `resume`) only, and that the routing specs pass unedited.
- [ ] 2.4 Re-read the javadoc of each converted class and replace the "split purely to
      respect the file-size guidance" sentence with the responsibility the class now owns;
      verify no converted class still claims a split that transferred no ownership (M3).

## 3. Constructors take the wiring

- [ ] 3.1 Change `TakeClaimAndWork` ctor:66, `TakeResumeRunner` ctor:63,
      `TakeContainerResumeRunner` ctor:48 and `TakeResumeExecution` record:26 to take
      `SlotWiring`, moving both resume-runner ends together (FR6); verify each drops to
      three constructor parameters or fewer and their specs pass unedited.
- [ ] 3.2 Change `TakeDisposition` ctor:76, `TakeBareAuto` ctor:73 and `TakeSlotRunner`
      ctor:101 to take `SlotWiring`; verify each drops from 14–16 parameters to seven or
      fewer and the take/serve specs pass unedited.
- [ ] 3.3 Build the take side's one `SlotWiring` in `TakeCommand.run`, right after
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
- [ ] 3.4 Build the serve side's one `SlotWiring` in `ServeRuntimeAssembly.assemble`, right
      after `serveAssembly`:81, moving the `AbortHandler` built at `ServeAssembly.slotRunner:62`
      with it, and pass it to `ServeAssembly.slotRunner:46` in place of the members it
      carries; verify `slotRunner` takes seven parameters or fewer, the `ServeCommand`
      constructor, `ServeRuntimeAssembly.assemble` and `SubcommandDispatchFactory.of` keep
      their signatures, `grep -rn "new SlotWiring(" */src/main` shows exactly the two
      assembly points of the design, and the serve specs pass unedited.

## 4. Rule amendment (FR7, D3)

- [ ] 4.1 Extend the existing "splitting a file must split a responsibility" paragraph of
      the file-size section of `.claude/rules/process-invariants.md` (which already names
      "passing 20-parameter bundles" as a symptom) to state that a split converting fields
      into parameters is not a responsibility split, and to name the field-holding
      transformation as the correct one for an oversized collaborator-holding class;
      verify the new text cites the failure it prevents, in the shape the file's other
      sections use.
- [ ] 4.2 Update the `Kept in sync with` sentences of the two pairs in the design's Sync
      surfaces table (neither pair has a row in `.claude/rules/manual-sync-pairs.md`, so
      the registry is not edited); verify `grep -rn "Kept in sync with" */src/main`
      still enumerates every marker in pairs and that no sentence names a removed parameter.

## 5. Old-way sweep and enforcement (design's single-owner table)

- [ ] 5.1 Run `grep -rn "abortThreshold" application/src/main bootstrap/src/main` and
      verify no signature takes it beside a bare `AbortHandler`; record the grep, the hits
      and the disposition of each per `.claude/rules/implementation.md`.
- [ ] 5.2 Run `grep -rn "credentialEnvVarsToScrub\|worktreesRoot\|taskIdMdcKey"
      application/src/main bootstrap/src/main` and verify every surviving hit is either
      inside `SlotWiring` itself, at one of its two assembly points, inside a site named in
      the design's exemption row (the command constructors and factories upstream of the
      tracker, the two composition roots, the `ContainerRunSupport`/`ContainerRunSupportFactory`
      container-bundle builders, or the `GitResumeRunner`/`ContainerResumeRunner` manual
      resume pair and the `ManualRunRunner` that builds it), or a read through the wiring;
      record the hits and dispositions.
- [ ] 5.3 Verify NFR-S1: no `SlotWiring` value reaches a logger whole, and the change adds
      no new rendering of a credential name; record the grep used.
- [ ] 5.4 Re-run the parameter-count scan over `src/main` and record the count; verify it
      has fallen from 44 to 32 (M2), with the scanner of `introduce-take-order` task 0.2
      (record members and `@Override` methods exempt); verify the 12 signatures the design's
      "Measured effect on the scan" list says drop are gone from it and that the design's
      consumer list has no survivor outside the stated exemptions.

## 6. Gates and handoff

- [ ] 6.1 Run `./gradlew :application:check :bootstrap:check` and verify every gate passes
      — Spotless, Error Prone / NullAway, Spock, JaCoCo, PIT — with no spec expectation
      edited (NFR-R1, M4) and the log-expectation gate green with no expectation file
      edited (NFR-O1). UX1 is evidenced by the same run: the `:bootstrap` E2E and console
      specs pass with no expectation edited.
- [ ] 6.2 Record the residual violations, grouped by cluster, as the input list for
      `collapse-composition-roots`; verify the list names each survivor by file and line.
- [ ] 6.3 Recommend a Conventional Commits subject line for the diff since the last commit
      (the agent never commits).

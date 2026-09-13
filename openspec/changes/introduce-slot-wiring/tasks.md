## 0. Precondition

- [ ] 0.1 Verify `fix-claim-epoch-fence` has landed: `TaskGit` exposes `epochs()` and
      `grep -rn "ClaimEpochBook epochs" application/src/main bootstrap/src/main` shows no
      signature taking the book as a separate parameter (D6). If it has not landed, stop —
      this change would create a second owner of the tenure book.

## 1. The wiring types

- [ ] 1.1 Add `AbortPolicy` as a record in `:application` `com.github.oinsio.gnomish.app`
      carrying the abort handler and its threshold (FR2), rejecting a non-positive
      threshold in its compact constructor; verify a new `AbortPolicySpec` pins the
      rejection and that `TakeCrashAbort` takes the policy rather than the pair.
- [ ] 1.2 Add `ClaimTenure` as a record carrying the claim beat and the claim-loss flag —
      and NOT the epoch book (FR3, D6); verify `grep -rn "ClaimEpochBook" application/src/main`
      shows the book reaching consumers only through `TaskGit.epochs()`, and that a spec
      pins the `ClaimBeat.NONE` combination production already passes.
- [ ] 1.3 Add `SlotWiring` as a record with exactly the nine components of FR1 (D1); verify
      `:application:compileJava` passes and that the record exposes no accessor beyond its
      components at this point.
- [ ] 1.4 Add glossary entries for `slot wiring`, `abort policy` and `claim tenure` to
      `docs/glossary.md`, per `process-invariants.md`; verify each names its type and
      states the lifetime that distinguishes it from the order.

## 2. Recipes become objects (D2, FR5)

- [ ] 2.1 Convert `TakeClaimAndWorkFactory` from a static factory taking twelve parameters
      into an instance constructed with `SlotWiring`; verify it takes one constructor
      argument and that `TakeClaimAndWork`'s specs pass unedited.
- [ ] 2.2 Convert `TakeFreshClaim` and `TakeContainerFreshClaim` — both ends of the
      declared pair together (FR6) — from static recipes into instances holding
      `SlotWiring`, their `claim`/`claimAt` steps becoming methods; verify all four methods
      take the order (plus `segments` on the container side, D4) and that both classes'
      specs pass unedited.
- [ ] 2.3 Convert `TakeWorkRouter` into an instance holding `SlotWiring`; verify
      `locateAndWork`, `freshClaim` and `resume` each take three parameters or fewer and
      the routing specs pass unedited.
- [ ] 2.4 Re-read the javadoc of each converted class and replace the "split purely to
      respect the file-size guidance" sentence with the responsibility the class now owns;
      verify no converted class still claims a split that transferred no ownership (M3).

## 3. Constructors take the wiring

- [ ] 3.1 Change `TakeClaimAndWork` ctor:74, `TakeResumeRunner` ctor:66,
      `TakeContainerResumeRunner` ctor:51 and `TakeResumeExecution` ctor:36 to take
      `SlotWiring`, moving both resume-runner ends together (FR6); verify each drops to
      three constructor parameters or fewer and their specs pass unedited.
- [ ] 3.2 Change `GitResumeRunner` ctor:76 and its `ContainerResumeRunner` pair end to take
      `SlotWiring`; verify the pair stays aligned and the manual resume specs pass
      unedited.
- [ ] 3.3 Change `TakeDisposition` ctor:81, `TakeBareAuto` ctor:75 and `TakeSlotRunner`
      ctor:102 to take `SlotWiring`; verify each drops from 15–17 parameters to seven or
      fewer and the take/serve specs pass unedited.
- [ ] 3.4 Change `TakeCommand` ctor:112, `TakeCommandFactory.of:24` and `:53`, and
      `ServeCommand` ctor:93 to build one `SlotWiring` and pass it on; verify each drops
      below eight parameters and the command specs pass unedited.
- [ ] 3.5 Reduce `ServeAssembly.slotRunner:47`, `ServeRuntimeAssembly.assemble:51` and
      `SubcommandDispatchFactory.of:30` by the wiring members only, leaving the rest, and
      add the `// collapse-composition-roots` note the design's exemption row requires;
      verify each is smaller than before and that the note names the follow-up change.

- [ ] 3.6 Change `ContainerRunSupport.create:130` and `ContainerRunSupportFactory.create:60`
      (`:bootstrap`) to take `SlotWiring`; verify each drops from nine parameters to seven
      and the container run-support specs pass unedited. Both were over the limit and named
      in no change until the 2026-09-13 consumer re-verification.

## 4. Rule amendment (FR7, D3)

- [ ] 4.1 Amend the file-size section of `.claude/rules/process-invariants.md` to state
      that a split converting fields into parameters is not a responsibility split, and to
      name the field-holding transformation as the correct one for an oversized
      collaborator-holding class; verify the new text cites the failure it prevents, in the
      shape the file's other sections use.
- [ ] 4.2 Update the affected rows of `.claude/rules/manual-sync-pairs.md` and the
      `Kept in sync with` sentences of the three pairs in the design's Sync surfaces table;
      verify `grep -rn "Kept in sync with" */src/main` still enumerates every marker in
      pairs and that no sentence names a removed parameter.

## 5. Old-way sweep and enforcement (design's single-owner table)

- [ ] 5.1 Run `grep -rn "abortThreshold" application/src/main bootstrap/src/main` and
      verify no signature takes it beside a bare `AbortHandler`; record the grep, the hits
      and the disposition of each per `.claude/rules/implementation.md`.
- [ ] 5.2 Run `grep -rn "credentialEnvVarsToScrub\|worktreesRoot\|taskIdMdcKey"
      application/src/main bootstrap/src/main` and verify every surviving hit is either
      inside `SlotWiring` itself, inside a composition root named in the design's exemption
      row, or a read through the wiring; record the hits and dispositions.
- [ ] 5.3 Verify NFR-S1: no `SlotWiring` value reaches a logger whole, and the change adds
      no new rendering of a credential name; record the grep used.
- [ ] 5.4 Re-run the parameter-count scan over `src/main` and record the count; verify it
      has fallen from 44 to 30 (M2) and that the design's consumer list has no survivor
      outside the stated exemptions.

## 6. Gates and handoff

- [ ] 6.1 Run `./gradlew :application:check :bootstrap:check` and verify every gate passes
      — Spotless, Error Prone / NullAway, Spock, JaCoCo, PIT — with no spec expectation
      edited (NFR-R1, M4) and the log-expectation gate green with no expectation file
      edited (NFR-O1).
- [ ] 6.2 Record the residual violations, grouped by cluster, as the input list for
      `collapse-composition-roots`; verify the list names each survivor by file and line.
- [ ] 6.3 Recommend a Conventional Commits subject line for the diff since the last commit
      (the agent never commits).

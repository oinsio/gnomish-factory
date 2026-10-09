# Tasks

Sequenced after `scope-pit-locally` (archived) and before `add-subprocess-access-log`
(proposal, Impact). Tasks that touch `test-fixtures/src/` widen the
mutation scope to every module, and a touch under `adapters/git/src/test` widens it to that whole
module — which is the measurement this change needs. Each task's sub-agent runs
`./gradlew :adapters:git:check` (or root `check` for `build-logic` and `:bootstrap` work) and
records timings where the task says so.

## 1. The stalling stand-in owner (D4; FR2, UX2; M3, M5)

- [x] 1.1 Measure first: run `./gradlew :adapters:git:test --tests '*ContainerHarvestFetchSpec*'`
      and record the "FR7: a fetch cut off on its deadline" feature time from the XML report
      (baseline 62.5 s). Then, in a scratch copy of the spec's `stallingGit()`, add
      `case "$1" in rev-parse|version|status) exit 0 ;; esac` before the sleep and re-measure. Verify:
      the feature drops to ≤ 5 s — confirming the clone-key resolution diagnosis (design Context).
      If it does not, STOP and report: the production path is then suspect and NG2 applies.
- [x] 1.2 Add the `StallingGit` builder to
      `test-fixtures/src/main/groovy/com/github/oinsio/gnomish/adapter/git/`: `stallOn(String...)`,
      `stallOnEverything()`, `stall(Duration)`, `beforeStall(String shellLine)`,
      `markOnStall(Path)` (= `beforeStall` of a `touch`), `answer(List<String> argvPrefix, stdout,
      exit)` (declaration order, first match wins) with `answer(subcommand, stdout, exit)` as the
      one-element case, `answerWith(subcommand, shellFragment)` for file-backed answers computed at
      run time, `localDelay(Duration)`, `write(Path dir)` (D4); always strips leading `-c` pairs;
      default answer for an unlisted subcommand is exit 0 with no output. Javadoc: the
      local-command sentence and the nine-copies history (UX2). Verify: a `StallingGitSpec` in
      `:bootstrap` (`bootstrap/src/test/groovy/com/github/oinsio/gnomish/adapter/git/`, beside
      `AdversarialGitConfigSpec` — `:test-fixtures` has no test source set) runs the script with
      `version` (returns in < 100 ms), with `ls-remote` under a 200 ms stall (returns after
      ≥ 200 ms), with `localDelay(300 ms)` and `version` (≥ 300 ms), with an `answer` row (stdout
      and exit code as given), with two `answer` rows on one subcommand qualified by the second
      argument (the longer prefix declared first wins), with an `answerWith` reading a file
      rewritten after `write` (the new content is answered), with `beforeStall` lines (run in
      order before the stall), and with `markOnStall` (marker present once the stall began).
- [x] 1.3 Rewire the six `:adapters:git` scripts to the builder per the D4 disposition table —
      `GitProcessRunnerBoundedNetworkSpec.stallingGit()` (with `localDelay(1 s)` and the
      `status` answer so "FR1, NG3: a local command … is not bounded" keeps its premise),
      `ContainerHarvestFetchSpec.stallingGit()`, `GitProcessRunnerShutdownReportSpec`,
      `TaskBranchLocatorSpec`, `UsageHistoryWalkerTerminationSpec.stallingOn()`,
      `ReplicaPairReconcilerTerminationSpec.stallingOn()` — deleting each hand-written script.
      Verify: all six specs green with their assertions unchanged (NG3);
      `ContainerHarvestFetchSpec`'s FR7 feature ≤ 5 s (M3); the module `test` task's summed
      feature time is ≥ 60 s lower than baseline (compare XML totals).
- [x] 1.4 Make `StallingGitFixture` and `StallingReadGitFixture` build their scripts through the
      builder per the D4 table (`StallingGitFixture`: `beforeStall` for the attempts line, the
      hook and the started marker; `answerWith` for `ls-remote`; the argv-qualified `rev-parse`
      rows), keeping their names, scenario files and `await*Started` loops; replace both
      "Kept in sync with" markers by one sentence naming `StallingGit` as the owner of the stall
      mechanics. Verify: every spec implementing either trait green (`./gradlew check` — the
      fixtures change widens the scope to every module); `grep -rn "Kept in sync with"
      test-fixtures/src/main` no longer pairs the two.
- [x] 1.5 Add `StallingGitOwnerSpec` to `:bootstrap` (precedent `ClaimlessGitBoundarySpec`): scan
      every `*.groovy` under `adapters/git/src/test` and `test-fixtures/src/main` for a script
      literal containing `sleep`, allowlist `StallingGit` itself and the three exemptions by file
      name with their reasons (`GitProcessRunnerBoundedNetworkSpec` leaky-git,
      `TipStateCursorTerminationSpec`, `CloneMutationConcurrencySpec`), assert the scan reached the
      eight consumers and the three exemptions by name, and fail on any other hit. Verify: green;
      red when a `sleep 1` script literal is planted in a scratch spec; red when a listed consumer
      is renamed away.

## 2. Fast killers for `GitProcessRunner.execute` (D1; FR1, NFR-R1, NFR-R2; M2)

- [x] 2.1 Add a `RecordingGit` helper in `adapters/git/src/test` (same package as the specs),
      extracted from `GitProcessRunnerTransferSpec.recordingRunner()` (D1): a script that answers
      the subcommands the caller names before recording (the clone-key `rev-parse` → `.git`), then
      **appends** one block per invocation — the record lines the caller names, each a key and the
      shell expression it prints (`"$@"`, `GIT_SSH_COMMAND`, the transfer-owner variables) — to a
      record file, optionally sleeping `N` ms first, then `exit 0`. Rewire
      `GitProcessRunnerTransferSpec.recordingRunner()` to build its script through it, with its
      five record lines unchanged and its assertions unchanged (NG3), deleting the inline script.
      Verify: a feature makes two invocations and reads back two blocks in order;
      `GitProcessRunnerTransferSpec` green.
- [ ] 2.2 Write `GitProcessRunnerNetworkBranchSpec`: (a) `version` carries no stall-detection `-c`
      options and no `GIT_SSH_COMMAND` (kills the argv-choice and SSH-guard negations); (b)
      `ls-remote` carries both (kills the SSH-call removal); (c) with a 50 ms network timeout, a
      150 ms-sleeping `version` is `EXITED` and a 150 ms-sleeping `ls-remote` is `TIMED_OUT`
      (kills the deadline negation); (d) the `TIMED_OUT` WARN, captured through the
      log-expectation fixture, reports `elapsed` below one minute (kills the Math mutant, which
      reports decades); (e) an operator-set `GIT_SSH_COMMAND` in the parent environment reaches
      the child unchanged — written as an environment assertion so that when
      `add-subprocess-access-log` minimizes the child environment the feature moves to that
      change's retained set rather than being deleted. Each feature must be < 500 ms (FR1), wait on
      no timeout longer than 100 ms (NFR-R1) and use no `system()` factory (NFR-R2). Verify: spec
      green; scoped PIT run (`-PpitScope=com.github.oinsio.gnomish.adapter.git.GitProcessRunner`)
      shows each of the five mutants with `killingTest` = this spec and `numberOfTestsRun` ≤ 20.
- [ ] 2.3 Make the transfer-environment kill fast: the existing "FR6, NFR-S2" feature of
      `GitProcessRunnerTransferSpec` (now built on `RecordingGit`) already runs a transfer through
      the typed entry and asserts the allowlist is set and the inherited per-process configuration
      is gone. Verify: as 2.2, the VoidMethodCall mutant on the transfer-environment application
      ≤ 20 tests, `killingTest` = that feature. Only if the scoped PIT run still records it as
      slow, state the measured cause in the task report and then add a feature that runs a
      transfer whose owner environment sets a marker variable and asserts the transfer's block
      (the clone-key `rev-parse` is answered, not recorded) saw it and that a variable the owner
      unsets is absent.

## 3. Fast killers for the mapper and the lock (D2, D3; FR1; M2)

- [ ] 3.1 Write `StateUsageMapperSpec`: a two-tool `ToolUsage` list and a two-model
      `tokensByModel` round-trip to the DTOs and back, asserting equality and sizes; a zero-tool
      and an empty-model edge. Verify: green; scoped PIT shows the `toByTool` and both
      `toTokensByModel` mutants with `killingTest` = this spec and ≤ 20 tests (today:
      `StatusReportEquivalenceContractSpec` at 200, `UsageHistoryWalkerSpec` at 162).
- [ ] 3.2 Add the hand-off feature to `CloneMutationLockSpec`: first `runLocked` on a key from
      one virtual thread completes; a second on the same key from another thread returns within
      2 s — no latch, no negative wait. Verify: green in < 50 ms; scoped PIT shows the unlock
      mutant with `killingTest` = this feature and ≤ 20 tests (today: the overlap feature at 162);
      the existing overlap feature untouched.

## 4. The mutation-cost report (D5, D6; FR3, FR4, NFR-O1, NFR-C1, UX1; M4)

- [ ] 4.1 Add `build-logic/src/main/groovy/pitest-cost-conventions.gradle` registering
      `pitestCostReport`: parses `build/reports/pitest/mutations.xml` once, selects
      `numberOfTestsRun > 100` (constant with a comment naming proposal Q1 and the 2026-10-05
      distribution), writes `expensive-mutants.txt` (class, method, line, mutator, count, killing
      test; descending), logs the one lifecycle line, never fails; takes both `onlyIf` predicates
      `pitestVerifyAllKilled` takes — the scope skip (`mutationScopeSkip` /
      `mutationScopeSkipReason` extra properties) and "the XML exists". `pitest` is `finalizedBy`
      it; `pitest-gate-conventions` applies the plugin. No subprocess, no network, no second read
      (NFR-C1). Verify: `./gradlew :atomicfile:check -PpitScope=all` writes the file with "0 above
      threshold"; file ≤ 120 lines.
- [ ] 4.2 Write a TestKit scenario in `build-logic/src/functionalTest` over a canned
      `mutations.xml` (three mutants above, two below): asserts the file's order and columns, the
      log line's format `class.method:line (mutator) — N tests`, the skip when the XML is absent,
      the skip when the module is scope-skipped while a stale `mutations.xml` sits in its build
      directory (precedent `STALE_REPORT` in `MutationScopeConventionsFunctionalSpec`), that a
      build with a 10 000-count mutant still succeeds (NG4), and that the report task's own
      duration in the build's `--profile` output is under 5 s (NFR-C1, loose on purpose).
      Verify: root `./gradlew check` runs it green; red with the threshold set to 0 in a scratch
      copy.
- [ ] 4.3 Document the report in `docs/guides/developer-guide.md` "Per-module verification":
      where it is, what the threshold means, and that a hotspot is answered with a fast killing
      spec, not with an exemption. Verify: paragraph present; links resolve.

## 5. Measurement and sweep (G1, M1, M2, M5)

- [ ] 5.1 On the reference machine with a hot daemon, run `./gradlew :adapters:git:check
      -PpitScope=all` and record: PIT total and mutation-phase time from the PIT summary
      (baseline 24.9 min / 20.8 min), and the maximum `numberOfTestsRun` over the hotspot list
      from `mutations.xml` (baseline 669). Verify: M1 ≤ 15 min and M2 ≤ 20 in the task report;
      if M1 is missed, list the next ten most expensive mutants from `expensive-mutants.txt`.
- [ ] 5.2 Sweep: `grep -rn "sleep" adapters/git/src/test test-fixtures/src/main` — every hit is
      the owner, one of the three exemptions, or a non-script sleep (virtual sleepers, poll
      loops); `grep -rn "stallingGit()\|stallingNetworkGit()\|stallingOn(" adapters/git/src/test` — no hand-written
      script survives; `grep -rn "Kept in sync with" test-fixtures/src/main` — the two traits no
      longer name each other. Report the greps and the disposition of every hit against the D4
      table (M5).

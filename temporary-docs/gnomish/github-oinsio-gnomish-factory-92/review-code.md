## Implementation Audit: kill-expensive-mutants

Mode: `quick` (the implement stage ran the full root `./gradlew check` on this commit; see
`temporary-docs/gnomish/github-oinsio-gnomish-factory-92/implement.md`, "Final gate"). Diff base:
`origin/main...HEAD`. Every `file:line` below was opened and read; the implementation record was
used only to find evidence, never as evidence. `openspec validate kill-expensive-mutants` is valid.

### Summary
| Dimension        | Result                                   |
|------------------|------------------------------------------|
| Tasks            | 15/15 verified done (0 mismatches)       |
| Requirements     | 10/10 traced and tested; 4/4 delta scenarios covered; metric M1 missed, reported |
| Quality gate     | skipped (quick)                          |
| Project rules    | 1 finding (noted, no recommendation — see below) |
| Code quality     | 0 findings                               |
| Test quality     | 0 findings (two observations, no recommendation) |
| Security/prod    | 0 findings                               |

### Tasks

**Section 1 — the stalling stand-in owner**

- **1.1 Measure first** — ✅. Measurement-only task; no source change is expected and none exists (the diff holds no scratch edit of `ContainerHarvestFetchSpec.stallingGit()` beyond the rewiring of 1.3). The record states 62.405 s → 2.592 s with the clone-key answer, confirming the diagnosis in the design Context; NG2 was not triggered (no production file is in the diff: `git diff origin/main...HEAD --stat` lists no `src/main/java` file).
- **1.2 `StallingGit` builder** — ✅. `test-fixtures/src/main/groovy/com/github/oinsio/gnomish/adapter/git/StallingGit.groovy:46-110` carries `stallOn`, `stallOnEverything`, `stall`, `beforeStall`, `markOnStall` (= `beforeStall` of a `touch`, line 68-70), `answer(List, String, int)` with the one-element overload (76-86), `answerWith` (94-96), `localDelay` (99-102), `write` (105-110). The script always strips leading `-c` pairs (`StallingGit.groovy:115`); an unlisted subcommand ends on `exit 0` with no output (129); answers are tried in declaration order, first match wins (128, 133-136); a stall wins over any answer (120-124 precede the answers). The javadoc states the local-command path in one paragraph (13-17) and the nine-copies history (19-23) — UX2. The spec `bootstrap/src/test/groovy/com/github/oinsio/gnomish/adapter/git/StallingGitSpec.groovy` covers every prescribed case: `version` < 100 ms after one warm-up run (35-49), 200 ms stall on `ls-remote` (51-57), `localDelay(300 ms)` (59-68), an answer row with stdout and exit (70-80), two `rev-parse` rows by prefix (82-97), `answerWith` over a file rewritten after `write` (99-112), `beforeStall` order (114-130), `markOnStall` (132-145). It runs the script with two leading `-c` pairs on every feature (156-162), so the strip is exercised each time.
- **1.3 Rewire the six `:adapters:git` scripts** — ✅. Each hand-written script is deleted and replaced by a builder call: `ContainerHarvestFetchSpec.groovy:156-158` (`stallOn('fetch')`, 60 s), `GitProcessRunnerBoundedNetworkSpec.groovy:92-99` (network four, `rev-parse` → `.git`, `status` → `local done`, `localDelay(1 s)` — the "local is not bounded" feature at line 46-49 drives `status`, so its premise holds), `GitProcessRunnerShutdownReportSpec.groovy:31-33` (`stallOnEverything()`), `TaskBranchLocatorSpec.groovy:301-310` (stall on `fetch`/`ls-remote`, argv-qualified `rev-parse` rows, `remote` → 128; the only reader of `remote get-url` is `OriginRemote.isConfigured`, `adapters/git/src/main/java/.../OriginRemote.java:64-70`, which checks the exit code only, so the dropped stderr text changes nothing), `UsageHistoryWalkerTerminationSpec.groovy:64-71` and `ReplicaPairReconcilerTerminationSpec.groovy:83-98`. The two termination specs stall on first-argument subcommands only (`log`, `show`; `rev-parse`, `merge-base`, `update-ref` — lines 28/36 and 36/44/52), which is what the builder's `$1` match covers; the reconciler's qualified `rev-parse` row names exactly the ref the production code probes (`ReplicaPairReconciler.java:115,228` builds `refs/remotes/origin/` + branch, the spec reconciles `gnomish/PROJ-1`, line 66). The `STALL_SECONDS` constants are removed. `then`/`expect` blocks are untouched in every diff hunk (NG3). The record reports FR7 at 2.39 s (M3) and a summed drop of 60.3 s.
- **1.4 The two traits build through the builder** — ✅. `StallingGitFixture.groovy:77-93`: `stallOn('push')`, `beforeStall` for the attempts line and the hook, `markOnStall` for `push-started` (the design's order), the qualified `rev-parse --git-common-dir` row before the bare `rev-parse` row, `answerWith` for `ls-remote` and `symbolic-ref` (file-backed), `answer` rows for `remote` and `merge-base`. `StallingReadGitFixture.groovy:30-32`: `stallOnEverything().markOnStall(...)`. Names, scenario files and both `await*Started` loops kept (61-67; 35-41). Both "Kept in sync with" markers are gone; each javadoc names `StallingGit` as owner (`StallingGitFixture.groovy:21-23`, `StallingReadGitFixture.groovy:15-17`). `grep -rn "Kept in sync with" test-fixtures/src/main` returns neither trait.
- **1.5 `StallingGitOwnerSpec`** — ✅. `bootstrap/src/test/groovy/com/github/oinsio/gnomish/architecture/StallingGitOwnerSpec.groovy`: owner and the three exemptions as file paths (26, 55-59), the eight consumers of the design table (29-38), a scan of both trees asserted to have reached > 100 sources (114-123), feature 1 fails on any sleeping literal outside the allowlist and on an exemption that no longer sleeps (63-79), feature 2 fails when a consumer is not reached or does not build through `new StallingGit()` in code (82-92; `StallingScriptRule.buildsThroughOwner` reads comment-stripped source via `RepoSourceTree.code`, `StallingScriptRule.groovy:40-42`), feature 3 pins the rule on ten seeded sources (95-111). The rule lexer (`StallingScriptRule.groovy:45-116`) extracts string literals with comments skipped and GString interpolations kept, so `Thread.sleep(20)` and prose are not hits and `"sleep ${STALL}"` is. The record reports both red cases (planted `sleep 1`, renamed consumer) observed and reverted.

**Section 2 — fast killers for `GitProcessRunner.execute`**

- **2.1 `RecordingGit`** — ✅. `adapters/git/src/test/groovy/com/github/oinsio/gnomish/adapter/git/RecordingGit.groovy`: `answer` (unrecorded, 41-44), `record(key, shellExpression)` (50-53), `delay` (56-59), `write` appending one block closed by `--` (62-78), `blocks` reading them back in order (81-94). The delay is not a `sleep` literal: it runs a `StallingGit().stallOnEverything().stall(delay)` script (66-67), which keeps the owner spec's allowlist as designed. `GitProcessRunnerTransferSpec.groovy:38-52` builds through it with its five record lines (`argv`, `allow`, `count`/`key0`, `askpass`/`ssh`, `global`) unchanged; the inline script is deleted (diff hunk at 229-234) and no assertion hunk is in the diff (NG3). `RecordingGitSpec.groovy` has the two-invocation order feature (24-39), the unrecorded answer (41-52) and the delay (54-66).
- **2.2 `GitProcessRunnerNetworkBranchSpec`** — ✅. `adapters/git/src/test/groovy/.../GitProcessRunnerNetworkBranchSpec.groovy`: (a) `version` carries no `-c` pairs and the parent's `GIT_SSH_COMMAND` or none (96-106); (b) `ls-remote origin` carries the stall-detection pairs and the default ssh limits (108-116; `GitNetworkCommands.applySshStallDetection` is `putIfAbsent`, `GitNetworkCommands.java:159-161`, which is exactly what the `parentSshCommand() ?: DEFAULT_SSH_COMMAND` expectation encodes); (c) 50 ms deadline, 150 ms child: `version` → `EXITED`, `ls-remote` → `TIMED_OUT` with one WARN (137-157); (d) the WARN captured through `LogCaptureSupport`, `elapsed` ≥ deadline and < 1 min (121-135); (e) the operator-set `GIT_SSH_COMMAND` pass-through, guarded by `@Requires` (159-169). Deviation from the task text: (d) uses a zero deadline rather than the 50 ms one; the javadoc at 47-57 gives the measured reason (ordering among covering tests) and why the outcome cannot race (the child sleeps 150 ms). All waits are ≤ 50 ms deadlines (NFR-R1); no `system()` factory (NFR-R2). The record's scoped PIT: lines 253/269/270/276/279 killed by this spec, max 20 tests.
- **2.3 Fast transfer-environment kill** — ✅. `GitProcessRunnerTransferSpec.groovy:31-54`: `tempDir`, `record` and the stand-in are `@Shared`, written once in `setupSpec` and launched once there; `setup()` clears the record file. The javadoc (22-28) states the measured cause (first-launch cost per fresh executable on macOS). No marker-variable feature was added, as the task allows when the scoped run no longer records the feature as slow; the record reports line 274 at 13 tests killed by the `FR6 #kind` feature of this spec. No production code changed.

**Section 3 — mapper and lock**

- **3.1 `StateUsageMapperSpec`** — ✅. `adapters/git/src/test/groovy/.../state/StateUsageMapperSpec.groovy:52-61` two-tool/two-model round trip with sizes and equality; 63-70 zero-tool edge; 72-79 empty-model edge; constants built once, `setupSpec` warms the mapping (48-50).
- **3.2 Hand-off feature in `CloneMutationLockSpec`** — ✅. `adapters/git/src/test/groovy/.../CloneMutationLockSpec.groovy:33-59`: first `runLocked` on a virtual thread completes (`get(2 s)`), a second virtual thread's `runLocked` on the same key completes within 2 s; the comment at 45-46 states why a second thread is required (reentrancy). The overlap feature is untouched (diff shows no hunk in it); `setupSpec` warms the virtual-thread start (21-26).

**Section 4 — the mutation-cost report**

- **4.1 `pitest-cost-conventions.gradle`** — ✅. `build-logic/src/main/groovy/pitest-cost-conventions.gradle` (79 lines ≤ 120): threshold constant with the Q1 comment (15-19); both `onlyIf` predicates in `pitestVerifyAllKilled`'s order (36-40, mirrors `pitest-gate-conventions.gradle:32-34`); one `XmlSlurper` parse, one file write, one lifecycle line, never a failure (41-72); `pitest` `finalizedBy` the report (77-79); applied by `pitest-gate-conventions.gradle:12-13`. Configuration-time values are captured as locals (23-25). The record reports `:atomicfile:check -PpitScope=all` writing the "0 above threshold" file.
- **4.2 TestKit scenario** — ✅. `build-logic/src/functionalTest/groovy/.../PitestCostReportFunctionalSpec.groovy`: three above / two at-or-below, order and columns (42-61); the single log line's `class.method:line (mutator) — N tests` format (63-78); skip with no XML (80-88); skip when scope-skipped over a stale XML (90-101); a 10 000-count mutant still SUCCESS and `--profile` duration < 5 s (103-114). The record reports 2/5 red with `threshold = 0` in a scratch copy.
- **4.3 Developer guide** — ✅. `docs/guides/developer-guide.md:85-87`: the report path, the log line, the threshold constant's location, "answer a hotspot with a fast killing spec, not an exemption", the exact-name `-PpitScope` advice; both relative links resolve to existing files.

**Section 5 — measurement and sweep**

- **5.1 Measurement** — ✅ as a task (performed and reported as written): PIT total 20 min 8 s, mutation phase 16 min 48 s, max `numberOfTestsRun` over the hotspot list 20, cost line "0 above threshold", and the next ten most expensive mutants listed as the task prescribes when M1 is missed. M1 itself is not met — see Requirements.
- **5.2 Sweep** — ✅. The three greps re-run during this audit agree with the record: every shell-sleep literal in `adapters/git/src/test` and `test-fixtures/src/main` is in `StallingGit.groovy`, the leaky-git script of `GitProcessRunnerBoundedNetworkSpec`, `TipStateCursorTerminationSpec` or `CloneMutationConcurrencySpec` (the gate's own allowlist, pinned by `StallingGitOwnerSpec`); the six helper names all build through `new StallingGit()`; no trait names the other.

### Requirements

- **FR1** — ✅ implemented and tested: `GitProcessRunnerNetworkBranchSpec` (argv choice, SSH guard and call, deadline, elapsed — javadoc 15-24 names FR1), `GitProcessRunnerTransferSpec` (transfer environment, 22-28), `StateUsageMapperSpec` (`toByTool`, `toTokensByModel`), `CloneMutationLockSpec:33` (unlock). Each covering feature runs without network, Docker or a repository; the record's scoped runs show every hotspot mutant killed by one of these at ≤ 20 tests.
- **FR2** — ✅: `StallingGit.groovy` (owner, javadoc line 32), the eight consumers listed under task 1.3/1.4, `StallingGitOwnerSpec` with the three exemptions named with reasons (40-59).
- **FR3** — ✅: `pitest-cost-conventions.gradle:41-72`; `PitestCostReportFunctionalSpec:42-78`.
- **FR4** — ✅: `finalizedBy` on `pitest` (`pitest-cost-conventions.gradle:77-79`), applied through the shared gate conventions so every module and `pitestAll` (which depends on each module's `pitest`, `pitest-gate-conventions.gradle:64-67`) reach it; skip without XML pinned at `PitestCostReportFunctionalSpec:80-88`.
- **NFR-R1** — ✅: the one timing assertion of the new killers has a 1-minute bound over a 150 ms sleep (`GitProcessRunnerNetworkBranchSpec:131-134`); the longest green-path wait among the new `:adapters:git` specs is the 100 ms delay feature of `RecordingGitSpec:54-66`; the 2 s bounds in `CloneMutationLockSpec` are failure bounds only (design D3).
- **NFR-R2** — ✅: every WARN the new spec provokes, `setupSpec` included, runs under `LogCaptureSupport.capture` (`GitProcessRunnerNetworkBranchSpec:88,150,183`); no `.system(` call in any new spec.
- **NFR-O1** — ✅: `pitest-cost-conventions.gradle:64-70`; format asserted at `PitestCostReportFunctionalSpec:72-77`.
- **NFR-C1** — ✅: one parse, one write, no subprocess, no declared `pitest` input (`pitest-cost-conventions.gradle:27-30`); duration asserted at `PitestCostReportFunctionalSpec:113`.
- **UX1** — ✅: `pitest-cost-conventions.gradle:63-70` ("a fast killing spec answers it; full list: …"), never throws; `PitestCostReportFunctionalSpec:103-114` (10 000-count mutant, SUCCESS).
- **UX2** — ✅: `StallingGit.groovy:13-17` (the local-command path in one paragraph); `StallingGitOwnerSpec:9-15` repeats the failure story at the gate.

Delta spec `specs/quality-gates/spec.md`:
- *Hotspots are listed* — ✅ `PitestCostReportFunctionalSpec:42-78`.
- *A cheap module reports nothing to look at* — ✅ `pitest-cost-conventions.gradle:56-57,65-66`; exercised by every module's real run (record, "Final gate": every module's line reads 0 above threshold); no canned-XML feature for the zero case — the behaviour is one branch with a fixed string and is observed on every `check`, so no recommendation.
- *A skipped gate writes no report* — ✅ `PitestCostReportFunctionalSpec:80-101`.
- *Cost never gates* — ✅ `PitestCostReportFunctionalSpec:103-114`.

Success metrics (from the record, not re-measured here): **M1 missed** (20 min 8 s against ≤ 15 min; baseline 24.9 min). The task text anticipates the miss and asks for the next-ten list, which is present with the reading that the remaining time is per-test duration with < 3 tests per mutation — a lever outside this change (NG3 forbids weakening the real-git specs; NG5 limits the scope to what the report shows). No recommendation: a fix would be a new change, and "write a follow-up" is advice, not an edit. **M2 met** (20 ≤ 20, at the bound; the record notes ±1 run-to-run variation on line 276 and that a process launch is the floor without production changes). **M3 met** (2.39 s; summed drop 60.3 s against ≥ 60 s). **M4 met** (every module's line in the final `check`). **M5 met** (the sweep above).

### Quality gate

Skipped (quick). The implement record reports the full root `./gradlew check` BUILD SUCCESSFUL on this commit after one rerun; the first run's only failure was `ProcessSupervisorTreeKillSpec` in `:subprocess`, a module this change does not touch — a timing-sensitive spec worth watching, outside this change.

### Project rules

- **File size** (`process-invariants.md`): `GitProcessRunnerNetworkBranchSpec.groovy` is 212 lines, over the 200-line hard cap. Noted, not recommended: 220 specs in the tree already exceed 200 lines, so the cap is not applied to specs in practice; the overrun is 12 lines, most of it the two measured-rationale javadocs the change needs (25-34, 47-56); and a split would move the deadline features and their warm-ups into a second class, which re-opens the M2 measurement the record made with this exact layout (line 276 sits at the 20-test bound). The fix is riskier than the gap ("Worth the cost"), so it is dropped from the recommendations. All other new files are ≤ 176 lines; `StallingGit.groovy` (152) and `StallingGitSpec.groovy` (169) are within the cap. No sibling-internal import; English only.
- **crash-consistency.md**: no multi-step durable transition in the diff (tests and build logic only). N/A.
- **manual-sync-pairs.md**: the declared pair `StallingGitFixture` ↔ `StallingReadGitFixture` is dissolved into `StallingGit` (both markers removed, owner named at both ends); the undeclared pair of the two termination specs' `stallingOn()` is dissolved the same way; `RecordingGit` extracts the transfer spec's script instead of adding a copy. The registry carried no row for either pair, so none needs removal. `grep -rn "Kept in sync with" test-fixtures/src/main` lists `BareGitRepoFixture` and `AdversarialGitConfig` only. ✅
- **testing.md**: no new `@DoNotMutate`, `excludedClasses` or `excludedTestClasses` entry (NG1). Specs are Spock, one capability per file. No `.system(` in test sources. Logging asserted through `LogCaptureSupport`. ✅
- **logging.md**: no new production log line (no production file in the diff). The one new WARN-provoking spec captures every emission. ✅
- **design-decisions.md adherence**: D1 — `RecordingGit` and the network-branch spec as designed, with the documented deadline deviation for feature (d); D2, D3 — as designed; D4 — builder surface, the eight consumers and the three exemptions match the disposition table row by row; D5 — both predicates, report-only, finalizer; D6 — separate file applied by the gate conventions. Sync surfaces and the single-owner table are present in `design.md` and match the code. ✅
- **implementation.md / single-owner table**: consumers — all eight visited above, each builds through `new StallingGit()`; old way — the six scripts and two inline trait scripts deleted (every diff hunk shows the removal), the three exemptions named with reasons in the gate; escape hatch — no consumer can obtain the shape elsewhere once the scripts are gone, and the owner spec rejects a new literal; enforcement — `StallingGitOwnerSpec` exists and asserts it reached every allowlisted file. No identity claim, so no identity spec is owed. ✅
- **diagrams.md / docs**: the developer guide paragraph is prose; no diagram describes PIT's gate. ✅

### Code quality

No findings. Reviewed `StallingGit.groovy`, `RecordingGit.groovy`, `StallingScriptRule.groovy`, `pitest-cost-conventions.gradle` and the two traits:
- Scripts are written with `Files.createTempFile` under the spec's temp directory and every embedded path or answer goes through one quoting function (`StallingGit.quote`, 149-151; used by `RecordingGit` and `StallingGitFixture`), so a path with a quote cannot break the script.
- `localDelay` exempts exactly the runner's clone-key invocation and the javadoc says why (`StallingGit.groovy:28-30`); the exemption is pinned by `StallingGitSpec:59-68`.
- The report task captures configuration-time values as locals and declares no `pitest` input or output, matching NFR-C1; the sort is total (count, then class, method, line), so the file order is deterministic.
- Reinvention check: the shell-sleep regex, the literal lexer and the record-block format are new responsibilities with no existing equivalent (`RepoSourceTree.code` strips comments but does not lex literals; the lexer reuses `RepoSourceTree` for the scan and the relative path). No duplicated constant: `STALL_SECONDS` copies are deleted rather than kept.
- No subprocess, catch block, non-final field on a concurrent path, or log call is added to production code.

### Test quality

No findings. Two observations without a recommendation:
- `GitProcessRunnerNetworkBranchSpec:159-169` (feature e) is skipped wherever the test JVM carries no `GIT_SSH_COMMAND`, which is every build the project defines; the pass-through rule is nevertheless asserted on both branches by features (a) and (b) against the parent's value, and the feature exists by design D1 (e) to move to `add-subprocess-access-log`'s retained set. Harmless as it stands.
- `StallingGitSpec:48,67` compare a process launch against 100 ms and 300 ms upper bounds. The values are the ones task 1.2 prescribes; the macOS first-launch cost is paid by an unmeasured warm-up run (39-40); the spec lives in `:bootstrap`, whose PIT scope holds no class it covers, so PIT's minion load does not apply. Watch for flakiness on a loaded runner; no edit is warranted now.
- Every new spec asserts observable behaviour (script output, exit codes, record blocks, task outcome, file content, log event head and fields), tests the failure branch where one exists (TIMED_OUT, skipped task, stale report), and cleans up (temp dirs, `StallingGitSpec.cleanup()` destroys the stalled processes and their descendants, 28-33).

### Security & prod readiness

No findings. No credential or token appears in the diff; the leaky-git script that exercises credential scrubbing stays as the named exemption, so that coverage is unchanged. Scripts receive their arguments as argv from the runner, never through a shell string; every embedded path is single-quoted. The report task reads one build-owned XML and writes one build-owned file; it cannot fail the build and adds no dependency. No sandbox or egress behaviour is touched.

### Recommendations, ordered

None. The one rule deviation (a 212-line spec) and the missed success metric M1 are recorded above with the reason each is not worth a fix inside this change.

### Verdict

**ready to archive** — every task of `tasks.md` is done with evidence in the code, every requirement and delta scenario is implemented and tested, the single-owner mechanism of D4 is enforced by an architecture spec, and no CRITICAL or WARNING finding exists. M1 (PIT wall time ≤ 15 min) is reported honestly as missed, with the measured reason and the next ten mutants listed; closing it is a separate change.

Nothing in the project was modified by this audit; the human decides what to apply.

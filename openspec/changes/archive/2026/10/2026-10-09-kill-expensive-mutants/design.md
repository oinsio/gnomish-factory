# Design: kill-expensive-mutants

## Context

See proposal.md — Why. What shapes the approach:

- PIT's default test prioritiser keeps the tests whose recorded line coverage reaches the mutated
  line, orders them by the execution time recorded in the coverage pass, and stops at the first
  kill. A mutant's cost is therefore the sum of the covering tests faster than its first killer.
  `GitProcessRunner.execute` is on the path of every git command in the module, so some 500 fast
  specs cover its lines without observing the network branch. The killers of record in the
  2026-10-05 report are all specs whose recorded time is seconds: `PushTerminationLoggingSpec`
  (elapsed subtraction), `GitVersionCheckSpec` (argv choice), `GitNetworkCommandsSpec` feature 6
  (SSH stall detection, guard and call), `GitProcessRunnerBoundedNetworkSpec` (deadline),
  `GitProcessRunnerTransferSpec` (transfer environment), `StatusReportEquivalenceContractSpec`
  (`toByTool`), `UsageHistoryWalkerSpec` (`toTokensByModel`), and the overlap feature of
  `CloneMutationLockSpec` (unlock). Tasks 2–3 compare `killingTest` before and after.
- `GitProcessRunner.run` serializes mutating subcommands per clone (`CloneMutationLock`) and
  resolves the clone key with a local `git rev-parse --git-common-dir` through the same binary.
  Local commands are unbounded by requirement (`git-task-persistence`, "Bounded git network
  invocations": "Purely local invocations SHALL remain unbounded"). A stand-in that sleeps on
  every subcommand therefore sleeps on the key resolution first.
- `CaptureRunner.DEFAULT_DRAIN_JOIN_BOUND` is 2 s and `ProcessSupervisor.terminate` kills the
  snapshotted descendants, so the "grandchild holding the pipe" hypothesis from the analysis does
  not fit the 62.5 s figure (it would give ~4 s); the key-resolution explanation fits exactly
  (60 s sleep + 2 s deadline). Task 1.1 confirms it by measurement before anything is rewired.
- Stalling `git` stand-ins already have two shared ends: `StallingGitFixture` (answers every read,
  stalls on `push`, scripts a push scenario through files) and `StallingReadGitFixture` (stalls on
  everything, touches a marker) in `:test-fixtures`, each carrying a "Kept in sync with" marker for
  the other. Beside them the `:adapters:git` test tree spells the same mechanics by hand in nine
  scripts in the module (eight stand-ins and one tracing wrapper), plus the two traits (table
  under D4). One spec among them needs the opposite of "local commands answer at
  once": `GitProcessRunnerBoundedNetworkSpec` proves a local command is *not* bounded by running
  one that outlives the deadline.
- `pitest-gate-conventions.gradle` (81 lines) parses `mutations.xml` once per module for
  `pitestVerifyAllKilled`; both of its tasks take two `onlyIf` predicates — the scope skip shared
  with `pitest` and "a report was produced" — so a `mutations.xml` an earlier run left in a module
  the scope skips is never judged (`scope-pit-locally`, risk "A stale report in a skipped module").
  The cost report must take both, or it describes that stale file.
- The module's test tree has a package-private typed entry on the runner for transfers (FR8 of
  `own-git-transfer-argv`) and log-capture fixtures that the log-expectation gate recognizes
  (`PushTerminationLoggingSpec` uses them). A transfer is repo-level mutating, so the typed entry
  makes two invocations of the binary: the key resolution, then the transfer itself.
- `GitProcessRunner.execute` is rewritten after this one (proposal, Impact):
  `add-subprocess-access-log` (active) minimizes the child environment, and any later routing of
  the argv through one invocation policy moves the same lines. The hotspot list is therefore named
  by mutation, not by line.

## Goals / Non-Goals

**Goals:** each hotspot mutant has a sub-second first killer (FR1); one owner of the stall
mechanics (FR2); a cost report that is a report (FR3, FR4, NG4); no production change (NG2).

**Non-Goals:** changing PIT's prioritiser or adding a PIT plugin; tuning `threads`,
`timeoutFactor` or the heavy-JVM budget; touching the real-git specs' assertions; rewriting the
scenario scripts that stay exemptions (D4) into the builder.

## Decisions

**D1 — Kill by observation of the decision, not by re-running the slow path faster.** For the
network branch the fast spec runs a *recording* fake `git` — a shell script that appends its argv
and selected environment variables (`GIT_SSH_COMMAND`, the transfer-owner variables) as one block
per invocation to a record file and exits 0 — once with a local subcommand (`version`) and once
with a non-mutating network one (`ls-remote`, which bypasses the clone lock and its key
resolution). Assertions: the local run carries no stall-detection `-c` options, no
`GIT_SSH_COMMAND`, no deadline (a 50 ms network timeout and a 150 ms local sleep still return
`EXITED`); the network run carries both, is `TIMED_OUT` under the same timeout when the stand-in
sleeps, and its WARN reports an elapsed time below one minute — the Math mutant turns the
subtraction into an addition and reports decades, so a loose bound kills it and no minion load can
fail it. That set kills the argv choice, the SSH guard and call, the deadline and the elapsed
subtraction. The transfer-environment application is killed through the typed transfer entry with
an environment the recording script prints; because a transfer is mutating, the runner first
resolves the clone key, which the stand-in answers (`rev-parse` → `.git`) without recording, so
the record holds the transfer's block only.
The recording stand-in is `RecordingGit`, extracted from the shape
`GitProcessRunnerTransferSpec.recordingRunner()` already spells (answer the clone-key `rev-parse`,
append the argv and chosen environment variables to a record file, exit 0): that spec builds its
script through it, keeping its `argv=`, `allow=`, `count=`/`key0=`, `askpass=`/`ssh=`, `global=`
record lines, so `RecordingGit` lets the caller name each record line (a key and the shell
expression it prints) and the subcommands answered before recording. The transfer-environment
feature is therefore `GitProcessRunnerTransferSpec`'s existing "FR6, NFR-S2" feature made first
killer, not a second one; a new feature is added only if the scoped PIT run shows that one still
slow, with the measured cause stated. `GitVersionCheckSpec.fakeGit(...)` and
`ContainerHarvestFetchSpec.fakeGit(int, String, Path)` also record argv, but each then prints a
scripted answer (a version string; a stderr text and an exit code): decision stand-ins, not
recorders, and they stay their own.
(e) An operator-set `GIT_SSH_COMMAND` in the parent environment is passed through unchanged —
true while the child inherits the environment; when `add-subprocess-access-log` minimizes it,
this feature asserts the variable is in that change's retained set instead. *Rationale:* FR1,
NFR-R1 — every assertion is about argv, environment or a termination kind; the one wait is the
50 ms deadline, and the one timing assertion has a margin of three orders of magnitude.
*Alternative rejected:* making the existing bound specs faster by shortening their sleeps — they
prove the real binary's behaviour on a real stall and must stay as they are (NG3); a 2 s real
stall is the shortest that is still clearly "stalled" on a loaded CI runner.

**D2 — `StateUsageMapper` gets a direct round-trip spec.** A `StateUsageMapperSpec` builds a
non-empty `ToolUsage` list and a two-model `tokensByModel`, maps forward and back, and asserts
equality and element count. *Rationale:* the mapper is pure; its only killers today are
`StatusReportEquivalenceContractSpec` (a real clone, 200 tests in) and `UsageHistoryWalkerSpec`
(real commits, 162 tests in). A pure spec runs in microseconds and is first in every ordering.
*Alternative rejected:* relying on the equivalence contract — it proves a different thing (status
report equivalence across media) and should not be the mapper's unit test by accident.

**D3 — `CloneMutationLock` proves the unlock by a hand-off that completes, not by a wait that
times out.** A new feature runs `runLocked` on a key from one virtual thread, then from a second
one after the first returned, asserting the second returns within 2 s. Under the dropped-unlock
mutant the second blocks and the `get(2 s)` fails; green, it completes in milliseconds. The
existing overlap feature keeps its 200 ms negative wait — it proves exclusion, this one proves
release. *Rationale:* the existing killer is the slowest feature in a spec whose other features
are fast, and ~160 covering tests sit before it. *Alternative rejected:* lowering the existing
200 ms wait — it is a negative assertion ("the second has NOT started") and shortening it weakens
it.

**D4 — One `StallingGit` builder in `:test-fixtures` owns the stall mechanics; the two traits
build through it; every plain-stall script in `:adapters:git` is rewired; three scenario scripts
stay as named exemptions.** `StallingGit` (`test-fixtures/src/main/groovy/.../adapter/git/`) is a
builder: `stallOn(String... subcommands)` or `stallOnEverything()`, `stall(Duration)`,
`beforeStall(String shellLine)` (appended in order before the `sleep` of every stalled
subcommand), `markOnStall(Path)` (defined as `beforeStall("touch '<path>'")`),
`answer(List<String> argvPrefix, String stdout, int exit)` matched in declaration order, first
match wins, with `answer(String subcommand, String stdout, int exit)` as the one-element case
(default for an unlisted subcommand: exit 0, no output — so `rev-parse` yields an empty answer
and the clone key falls back to the working directory, as today), `answerWith(String subcommand,
String shellFragment)` for an answer computed at run time (a file the spec rewrites after the
script was written — the javadoc says it is for file-backed answers, not a second way to spell a
stall, and the owner spec's `sleep` scan still catches a stall written through it),
`localDelay(Duration)` (default zero), and `write(Path dir)` returning the script. It always
strips the leading `-c` pairs. The javadoc states the local-command path and the nine-copies
history (UX2). `StallingGitFixture` and
`StallingReadGitFixture` keep their names and their scenario files (push hook, marker, answer
files) but build their script through the builder, and their mutual "Kept in sync with" markers
are replaced by one sentence naming the owner — the pair dissolves into one implementation.
*Rationale:* FR2; `manual-sync-pairs.md` preference order — a third implementation of a rule that
already has a declared pair extracts the abstraction, and the module held eight stand-ins. *Alternative
rejected:* a module-local `StallingGit` beside the pair in `:test-fixtures` (the first draft of this
change) — a third parallel implementation, exactly what the rule forbids; also rejected:
`exec sleep 60` in the harvest spec's script — it hides the actual cause (the key resolution)
and leaves every other copy.

Disposition of every script that sleeps in `adapters/git/src/test` and `test-fixtures/src/main`
(the old-way sweep, `implementation.md` item 2):

| Script | Shape today | Disposition |
|--------|-------------|-------------|
| `GitProcessRunnerBoundedNetworkSpec.stallingGit()` | answers `rev-parse`, stalls on the network four, local commands sleep 1 s and print `local done` | rewired: `stallOn(network four)`, `answer('rev-parse', '.git', 0)`, `localDelay(1 s)`, `answer('status', 'local done', 0)` — the "local is not bounded" feature keeps its premise |
| `ContainerHarvestFetchSpec.stallingGit()` | `sleep 60` on everything | rewired: `stallOn('fetch')`, `stall(60 s)` — the key resolution answers at once |
| `GitProcessRunnerShutdownReportSpec` (`sleep 600`) | stalls on everything | rewired: `stallOnEverything()` |
| `TaskBranchLocatorSpec.stallingNetworkGit()` (writes `stalling-network-git.sh`) | answer table (`rev-parse --git-common-dir` → `.git`, any other `rev-parse` → 1, `remote` → 128), stalls on `fetch|ls-remote`, default exit 1 | rewired: `stallOn('fetch','ls-remote')`, `answer(['rev-parse','--git-common-dir'], '.git', 0)`, `answer('rev-parse', '', 1)`, `answer('remote', '', 128)` (an `answerWith` if the spec turns out to read the stderr text); the default-exit-1 becomes an explicit `answer` for the subcommands the spec drives |
| `UsageHistoryWalkerTerminationSpec.stallingOn()` | stalls on one named argument, touches a marker, answers `log`/`rev-parse` | rewired: `stallOn(named)`, `markOnStall`, `answer` rows |
| `ReplicaPairReconcilerTerminationSpec.stallingOn()` | same shape as the previous row (an undeclared pair today) | rewired the same way; the pair dissolves |
| `StallingGitFixture.stallingGit()` | answers reads from files, runs a hook, stalls on `push` | builds through the builder: `stallOn('push')`; `beforeStall` lines for the attempts line, the hook and the started marker, in that order; `answer(['rev-parse','--git-common-dir'], '.git', 0)` before `answer('rev-parse', <stalled tip>, 0)`; `answerWith('ls-remote', …)` reading the scenario files at run time; `answer` rows for the rest; the hook is a `beforeStall` line |
| `StallingReadGitFixture.stallingGit()` | stalls on everything, touches a marker | builds through the builder: `stallOnEverything()`, `markOnStall` |
| `GitProcessRunnerBoundedNetworkSpec` leaky-git (line 78) | prints a credential-bearing line to stderr, then stalls | **exemption**: the stderr text before the stall is the subject; a one-line script says it better than a builder option nobody else needs |
| `TipStateCursorTerminationSpec` (`exec 1>&-; sleep 600`) | closes stdout, then stalls | **exemption**: the closed pipe is the subject |
| `CloneMutationConcurrencySpec` (`sleep 0.15` wrapper) | wraps the real `git`, logs START/END around it | **exemption**: not a stand-in — a tracing wrapper over the real binary |

**D5 — The cost report is a `pitestCostReport` task in the PIT gate conventions, finalizing
`pitest`, report-only.** It parses `mutations.xml`, selects `numberOfTestsRun > THRESHOLD`
(100, proposal Q1), writes `build/reports/pitest/expensive-mutants.txt`, and logs the one line.
It takes both `onlyIf` predicates `pitestVerifyAllKilled` takes — the scope skip shared with
`pitest`, then "a report was produced" — so a stale report in a scope-skipped module is never
described as this build's. It does not fail. *Rationale:* FR3, FR4, NG4 — the count is
load-dependent (PIT orders by recorded timings, which shift between a cold daemon and a hot one);
a gate would flap, and a flapping gate teaches agents to retry rather than fix. *Alternative
rejected:* PIT's own `--verbose` slowest-test line — it names the slowest *test*, not the mutant
that needed the most tests, and is lost in a 1 000-line log; also rejected: a gate with a
generous threshold — see above; also rejected: the file-exists predicate alone — it reopens the
stale-report hole `scope-pit-locally` closed.

**D6 — The report task lives in a new `pitest-cost-conventions.gradle` applied by
`pitest-gate-conventions`.** *Rationale:* the gate file is at 81 lines and holds one
responsibility (what happens to a verdict); the report is a second one. *Alternative rejected:*
appending to the gate file — within the cap, but "one file is one thing".

**Sync surfaces: the declared pair `StallingGitFixture` ↔ `StallingReadGitFixture` is dissolved
into the shared abstraction `StallingGit` (D4), and the undeclared pair
`UsageHistoryWalkerTerminationSpec.stallingOn` ↔ `ReplicaPairReconcilerTerminationSpec.stallingOn`
with it.** No other declared pair is touched. The recording stand-in of D1 extracts the shape
`GitProcessRunnerTransferSpec.recordingRunner()` already spelled into `RecordingGit`, which that
spec then builds through, so no second copy is added (shared abstraction, preference 1 of
`manual-sync-pairs.md`); the argv-recording `fakeGit` helpers of `GitVersionCheckSpec` and
`ContainerHarvestFetchSpec` are scripted-answer stand-ins, not recorders (D1).

**Single-owner mechanisms:**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `StallingGit` builder in `:test-fixtures` (D4) | `Path` to the written script; what is owned is the script's *shape* (strip `-c`, stall on the chosen set, answer the rest, local delay) | `GitProcessRunnerBoundedNetworkSpec`, `ContainerHarvestFetchSpec`, `GitProcessRunnerShutdownReportSpec`, `TaskBranchLocatorSpec`, `UsageHistoryWalkerTerminationSpec`, `ReplicaPairReconcilerTerminationSpec` (all `adapters/git/src/test`), `StallingGitFixture`, `StallingReadGitFixture` (`test-fixtures/src/main`) | the six hand-written scripts and the two traits' inline scripts, deleted; exemptions with reasons: the leaky-git script of `GitProcessRunnerBoundedNetworkSpec`, `TipStateCursorTerminationSpec`, `CloneMutationConcurrencySpec` (D4 table) | `StallingGitOwnerSpec` in `:bootstrap`: a whole-tree scan of `adapters/git/src/test` and `test-fixtures/src/main` for a script literal containing `sleep`, allowlisting the owner and the three exemptions by file name, asserting it reached the eight consumers and the three exemptions by name, and failing on any other hit (`ClaimlessGitBoundarySpec` is the precedent) |

The `Path` is a primitive, but no consumer can obtain the shape elsewhere once the scripts are
deleted; the scan is what keeps a tenth copy from appearing.

## Risks / Trade-offs

- **The fast killer is not first under some orderings** (a coverage pass on a loaded machine
  records it slower than a few trivial specs) → it is still among the first tens, not the first
  hundreds; M2's ≤ 20 is measured on the reference machine with a hot daemon, and the report (D5)
  shows any regression.
- **The recording stand-in on `ls-remote` still goes through `isNetwork`** → that is the point;
  but if a future change makes `ls-remote` mutating (locked), the spec starts paying the key
  resolution — the recording script exits immediately, so the cost is one spawn, not a sleep.
- **The 62.5 s diagnosis is from reading, not running** → task 1.1 measures before and after;
  if the fixed stand-in does not bring the feature under 5 s, the production path is suspect and
  the task stops and reports instead of proceeding (NG2).
- **Rewiring eight scripts touches `test-fixtures/src/`, which widens the mutation scope to every
  module** → the measuring runs (tasks 1.3, 5.1) are whole-tree runs anyway; the cost lands once,
  on this change's own `check`.
- **`add-subprocess-access-log` (and any later rewrite of `GitProcessRunner.execute`) moves the
  mutated lines** → the hotspot list is named by mutation; the scoped PIT runs of tasks 2–3 are
  re-run as the verify step when such a change rebases over this one.
- **A report nobody reads** → the lifecycle line is in every `check` output and the nightly run's
  log; the developer guide names it as the first place to look when a module's gate slows.

## Migration Plan

Tests and build logic only. Rollback is a revert; no artifact or state outlives a build.

## Open Questions

None affecting specs or tasks. The threshold (Q1) is a constant with a comment.

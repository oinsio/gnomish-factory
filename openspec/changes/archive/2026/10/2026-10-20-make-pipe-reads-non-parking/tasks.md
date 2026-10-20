# Tasks

Per-task verification follows `verification-scope.md`: the task's own specs by name, scoped
PIT on the classes it changed, the full root `check` once at the end (group 7). Every task's
report ends with the old-way sweep it ran (`implementation.md`).

## 0. Review before code

- [x] 0.1 `/review-artifacts make-pipe-reads-non-parking`: freshness of D1–D9 against the code
  (the `Drain`, `StreamDrain`, `ExecHandle`, `FakeProcess` signatures quoted in design.md), the
  single-owner table, the REMOVED/ADDED pair of the delta; recommendations resolved through
  `/update-from-review` before 1.1 starts.

## 1. The owner in `:subprocess` (FR1, FR2, FR3, FR4, NFR-P1, NFR-P2, NFR-R1, NFR-R3)

- [x] 1.1 `FakePipe` in `:test-fixtures` (D6): write/available/read/release semantics, read-side
  close releases no parked reader; `FakeProcess` keeps its contract over it;
  `ScriptedSandboxDocker`'s inner process stops overriding `getInputStream()` and feeds its canned
  output through `writeStdout` + `releaseStdout`. Spec `FakePipeSpec` in `:bootstrap` (package
  `testfixtures`, beside `StandInLibrarySpec`; `:test-fixtures` has no test tree): a parked
  reader is still parked after `close()` of the read end, `available()` is exact, `release()`
  yields `-1`; marker `// Scenario: lifecycle/subprocess-supervision / The fake process behaves
  as a Linux pipe`; plus the fixture gate of the `FakePipe` row: `extends Process` appears in the
  test trees and `test-fixtures/src/main` only in `FakeProcess` and its three listed subclasses,
  none of which overrides `getInputStream`/`getErrorStream`, every listed file asserted reached.
  Verify: `FakePipeSpec` green; every existing `FakeProcess` spec still green
  (`:subprocess:test`, `:sandbox:docker:test --tests '*Spec'` named in the task report).
- [x] 1.2 `ChildOutput` (D1, D2): `stdout(Process)`, `stderr(Process)`, the non-parking loop
  with the backoff tick, the grace, the stop flag, in both forms over one implementation — the
  push drain and the stream view `stream()` whose `read` runs the same steps on the caller's
  thread and whose `close()` is `stop()` (D1, D5); `Thread.sleep` in this one file, as D2 records.
  `ChildOutputSpec` on `FakeProcess`: bytes before the exit all captured when the exit lands
  between `isAlive` and `available` (the risk row's hook); the drain ends after one grace tick
  with nothing pending (marker "The drain ends on its own after the exit"); no `read` is issued
  while nothing is available and `stop()` ends the loop within a tick (marker "No read is issued
  for bytes that are not there"); a written line is read within one tick and an idle stream
  costs one query per tick (marker "A line arrives within a tick"); an `IOException` from the
  stream is reported as the drain's failure; every feature runs once through the push form and
  once through the view (a data-driven `where:` over the two). Verify: spec green; `-PpitScope`
  on `ChildOutput*` 100%.
- [x] 1.3 `Drain` on `ChildOutput` (D1, D3, D4): the `InputStream` overloads deleted,
  `closeQuietly` deleted, `joinAll` stops cut drains; `DrainJoinAllSpec`, `DrainCapSpec`,
  `DrainFailureSpec`, `DrainInterruptSpec`, `DrainSinkSpec` on `FakeProcess`, `HeldStream`
  deleted. Verify: the five specs green; scoped PIT on `Drain*` 100%; grep
  `Drain.start(.*getInputStream` over `src/main` = 0 hits.
- [x] 1.4 `CaptureRunner` on `ChildOutput`; `CaptureRunnerPostExitSpec`, `CaptureRunnerDrainSpec`,
  `CaptureRunnerSweepSpec`, `CaptureRunnerLaunchSpec`, `CutCaptureNoticeSpec`,
  `ProcessSupervisorInterruptSpec` adjusted where they built a stream. Verify: specs green;
  scoped PIT on `CaptureRunner*`.
- [x] 1.5 The escaped-holder spec (D7, FR9): stand-in `process/escaped-holder.sh` (own session
  on both platforms, ignores `SIGPIPE`, pid to `$1`, speaks, writes a line every 50 ms, exits 0
  on its own after speaking), `StandInLibrarySpec` row; `EscapedHolderSpec` in `:subprocess`
  through a real `CaptureRunner`, marker "An escaped holder pins no thread", asserting return
  well inside the bound, the spoken text in the capture and the capture not flagged (D7 as amended
  2026-10-18), no thread in `FileInputStream.readBytes`, `onExit` completes, holder alive until
  cleanup. Verify: 20
  consecutive local runs green on macOS (M3); `StandInOwnerSpec` green.
- [x] 1.6 `DrainCloseReleaseSpec` deleted (its scenario left the spec) — its surviving
  assertions live in 1.2 and 1.5. Verify: no marker in the repository names "Closing the pipe
  releases the parked drain" (`grep -rn` = 0); `./gradlew checkSpecContract --changes` green.

## 2. The readers outside `:subprocess` (FR5, FR6, NFR-R2)

Order note: 2.2 ran before 1.3 — `Drain`'s `InputStream` overloads could not be deleted while
`CapturedExec` and `BoundedTail` held only the stream `ExecHandle.output()` returned; 2.2 left
`output().stream()` at those call sites until 1.3, 2.4 and 2.5 typed them.

- [x] 2.1 `GitExec` on `ChildOutput`; `GitExecPumpThreadsSpec`, `GitExecInterruptSpec`,
  `GitExecLaunchFailureSpec`, `GitObjectsResolveRefSpec`, `CommitBuilderObjectIdSpec` adjusted.
  Verify: specs green; scoped PIT on `GitExec*`.
- [x] 2.2 `ExecHandle.output()` returns `ChildOutput` and `sandbox/core/build.gradle` moves
  `:subprocess` from `implementation` to `api` (D1); `HostExecHandle`, `OomAnnotatedExecHandle`,
  `CapturedExec` migrated; the `ExecHandle` doubles that live outside the agent adapter rebuilt
  over `FakeProcess`: `CannedExecHandle` (`HeldStream` and `SlowStream` deleted),
  `ScriptedExecHandle` (`:test-fixtures`), `ObservedBox` (`:bootstrap` tests);
  `CapturedExecPostExitSpec` on the rebuilt `CannedExecHandle`. Verify: `:sandbox:core:test`,
  `HostExecHandle*`, `CapturedExec*` green; `:bootstrap:compileTestGroovy` green; scoped PIT.
- [x] 2.3 `ContainerFileChannel` on `ChildOutput`; `ContainerFileChannelPostExitSpec`,
  `ScriptedFileChannelDockerCli`, `DockerCliTruncatedCaptureSpec` adjusted; `ScriptedFileChannelProcess`
  stops overriding the streams (`StalledInputStream`, `SlowEofStream`, `EofSignallingStream`, the
  throwing anonymous stream of `ContainerFileChannelPostExitSpec` deleted, their features on
  `FakeProcess`/`WritingHolder`), and the `@PendingFeature` of `FakePipeGateSpec` is removed. Verify: specs green;
  scoped PIT on `ContainerFileChannel*`.
- [x] 2.4 `BoundedTail` on `ChildOutput`; `CommandProcessRunnerCutTailSpec` (its
  `FakeExecHandle` over `FakeProcess`), `ShellCommandCheckRunnerTimeoutSpec` adjusted. Verify:
  specs green; scoped PIT on `BoundedTail*`.
- [x] 2.4a `InBoxGitCommandSpec` (`:adapters:git`): its two `FakeHandle` doubles mint `ChildOutput`
  from a `FakeProcess`. Verify: spec green; `:adapters:git:compileTestGroovy` green.
- [x] 2.5 `StreamDrain` on `ChildOutput` (D5): `close()` is the owner's stop plus the interrupt,
  `closeSource` deleted, the try-with-resources in `drain()` now closes the view (a stop);
  `StreamDrainSpec` on `FakeProcess` (`spinningStream`, `PipedInputStream` and
  `ByteArrayInputStream` sources replaced; a second marker `// Scenario:
  lifecycle/subprocess-supervision / No read is issued for bytes that are not there` on the
  feature that stops the drain through the view); `StreamDrainLogScopeSpec` on `FakeProcess`;
  the four `ExecHandle` doubles of `:adapters:agent` over `FakeProcess`: `ExitedExecHandle`
  (`ExecutorRoundDrainTimeoutSpec`, shared with `JudgeRoundDrainTimeoutSpec`),
  `InterruptedWaitExecHandle` (`RoundInterruptedWaitSpec`), `ScratchStderrHandle`,
  `ScriptedHandle` (`AgentRoundTeardownSpec`, whose `TrackedStream.closed` assertion becomes
  "the drain thread has ended and no read is pending" under its existing marker `// Scenario:
  executor/agent-executor / No reader outlives the round`); `StreamDrainCloseReleaseSpec`
  rewritten as the round's close on a real process whose holder lives, marker `// Scenario:
  executor/agent-executor / The round's close leaves no drain thread`, asserting the close returns
  at once and no drain thread remains within a tick. Verify: `:adapters:agent:test` green (the
  module is small), 20 consecutive runs of the rewritten close spec (M3); scoped PIT on
  `StreamDrain*`; `ExecutorRoundExecution` and `JudgeRoundExecution` compile with no change but
  the type.
- [x] 2.6 Old-way sweep (`implementation.md`): `grep -rn "getInputStream()\|getErrorStream()"
  --include='*.java'` over every `src/main` = `ChildOutput.java` only; `grep -rn "Drain\.\(start\|
  capped\|into\)(" --include='*.java'` shows only `ChildOutput` arguments; `grep -rn "InputStream
  output()"` = 0. Record the three greps and their hits in the task report. Verify: M1, M2 met.

## 3. The gates (FR8, G3)

- [x] 3.1 `DrainOwnerGateSpec` (D8): detectors in call shape (`\.get(?:Input|Error)Stream\s*\(`,
  `\.(?:input|error)Stream\b`), a `.read(` on a child's stream, and `Thread.sleep(` over every
  production source `RepoSourceTree` walks (`:test-fixtures` included), allowed in
  `ChildOutput.java` only (the sleep also in `ThreadSleeper.java`), with the fixture
  definitions that still match (`FakeProcess.groovy`; `ScriptedSandboxDocker.groovy` stopped
  overriding the accessors in 1.1, so it is listed only if it still matches) and the five
  test-time sites of D8 as amended 2026-10-19 (`LocalGitRepoFixture`, `SeedTransferFixture`,
  `GuardImageAvailability`, `GroupLeader`, `StallingGit.awaitStall`), each with its reason and its
  revisit condition in the spec's comment, as listed exemptions asserted reached; the `READERS` map re-described (the "migrated by 8.x" notes replaced by the owner's
  consumers); markers "Only the owner touches a child's output stream" and "A drain cannot be
  started on a bare stream" (the latter on a feature that asserts the deleted overloads are
  absent by reflection). Verify: spec green; a planted `process.getInputStream()` in a reader and
  a planted `Thread.sleep(1)` in `Drain.java` each turn it red (shown in the report, then
  removed).
- [x] 3.2 `RawCaptureOwners`: entries of `CaptureRunner`, `GitExec`, `HostExecHandle`,
  `ContainerFileChannel` removed, `ChildOutput` added under the mechanics carve-out. Verify:
  `RawCaptureGateSpec` green, including its "every listed file still captures" direction.
- [x] 3.3 `LaunchOwnerGateSpec`, `ProcessSpawnBoundarySpec`, `StandInOwnerSpec`: green with the
  new stand-in and the fixture change; any allowlist row the move needs is added with its reason.

## 4. Documentation (G1, M5, D9)

- [x] 4.1 `docs/adr/0018-non-parking-pipe-reads.md`: the rule, the measurements table, the
  rejected alternatives (close at the bound, FFM poll, file redirection, abandon), the method
  rule, and the paragraph on real time in a leaf (D2, D9: the one admissible wait, its gate, the
  relation to ADR 0014). Verify: the file exists, the glossary entries of 4.3 cite it (the directory keeps no
  index; the glossary is where ADRs are referenced), and `design.md`'s D9 points at it.
- [x] 4.2 `.claude/rules/lock-scope.md`, "A lock inside the JDK": the read-side sentence replaced
  by the ADR's rule; `.claude/rules/design-decisions.md`: the paragraph "A platform fact is
  measured on every platform CI runs". Verify: `grep -rn "wakes a parked" .claude docs` = 0.
- [x] 4.3 `docs/glossary.md`: **Drain**, **Post-exit bound**, **Cut capture** rewritten; **Child
  output** added. Verify: no entry says a close wakes or releases a drain.
- [x] 4.4 Javadocs of `Drain`, `CaptureRunner`, `GitExec`, `CapturedExec`,
  `ContainerFileChannel`, `BoundedTail`, `StreamDrain`, `ExecutorRoundExecution` and the comments
  "A cut drain's failure is the close that ended it" (`CapturedExec`, `ContainerFileChannel`)
  rewritten to the stop rule. Verify: `grep -rn "wakes the parked\|closes the stream\|close
  lock\|releases the JDK's reaper\|the close that ended it" --include='*.java'` = 0 (M5).

## 5. The main spec and the CI unblock (FR10, Q3)

- [x] 5.1 The deltas' markers: every new scenario title of the two deltas has at least one marker
  (1.1, 1.2, 1.5, 2.5, 3.1; "No read is issued for bytes that are not there" has two) and every
  surviving title of the modified `agent-executor` requirement keeps its existing marker;
  `./gradlew checkSpecContract --changes` green.
- [x] 5.2 Main-spec notes that the delta rewrites are not edited by hand (they merge at archive);
  the archived `fix-terminal-receipt-convergence` is not touched. Verify: `git diff --stat
  openspec/specs openspec/changes/archive` empty for this change.
- [x] 5.3 If a `@PendingFeatureIf({ os.linux })` landed on `DrainCloseReleaseSpec` or
  `StreamDrainCloseReleaseSpec` before this change, it is gone with the rewrite (1.6, 2.5);
  `grep -rn "PendingFeatureIf" --include='*.groovy' subprocess adapters/agent` = 0.

- [x] 5.4 The Linux spec runner (D9 as amended 2026-10-19): `scripts/linux-runner/Dockerfile`
  (`eclipse-temurin:25-jdk-noble` plus `ppa:git-core/ppa` git ≥ 2.51, `perl`, `setsid`, `rsync`
  — the CI `build` job's platform, never `FROM` the sandbox base image of
  `add-sandbox-base-image`, which is a box image with the guard's proxy in its environment) and
  `scripts/linux-specs.sh <gradle args>`: builds the image when missing, copies the working tree
  without `build/` and `.gradle/` into a scratch directory, runs the given Gradle arguments in the
  container as a non-root user with its own `GRADLE_USER_HOME`, prints where the reports are and
  exits with Gradle's code; `--repeat N` reruns the given `--tests` N times with `--rerun`.
  `.claude/rules/verification-scope.md` names it (step 5 and "The cheapest platform signal
  first"); `docs/glossary.md` gains **Linux spec runner** (*Not:* the sandbox base image).
  Verify: `scripts/linux-specs.sh :subprocess:test` green; a failing `--tests` filter returns
  non-zero; the script passes `shellcheck` if available.

## 6. Measurement (M3, NFR-P1, NFR-P3)

- [x] 6.1 The Linux gate: first locally — `scripts/linux-specs.sh --repeat 20` on
  `EscapedHolderSpec` and `StreamDrainCloseReleaseSpec` (M3 on Linux) and the four modules
  below once, all green; only then push the branch and run the CI `build` job as the last
  confirmation; `:subprocess:test`,
  `:adapters:agent:test`, `:sandbox:core:test`, `:sandbox:docker:test` green on
  `ubuntu-latest`. Record the run id in the task report.
- [x] 6.2 Local measurement on both JDKs (the macOS toolchain and `eclipse-temurin:25-jdk` in
  Docker, the way the proposal's table was produced): a 20 000-line burst through
  `CaptureRunner` is complete and the invocation returns within 50 ms of the exit; record the
  five numbers in the report (lines, lost, ms after exit, queries, sleeps).

- [x] 6.3 The exit tail (D2, FR3 as amended 2026-10-19; found by task 7.1). Current signatures:
  `ChildOutput(Process process, InputStream stream, Tick tick)`, `ChildOutput.Tick.pause(long
  millis) throws InterruptedException`, `ChildOutput.stdout(Process)`, `ChildOutput.stderr(Process)`,
  production tick `ChildOutput::sleep` (`Thread.sleep`). Change: the grace is removed (`next` ends
  at the first query that finds `exited` and nothing available), and the production tick of
  `stdout`/`stderr` waits on the child's exit — `process.onExit().get(millis, MILLISECONDS)`, a
  `TimeoutException` being the ordinary end of a tick, an `InterruptedException` passed on as
  today — built once per `ChildOutput`; `Tick`'s signature and the `RecordedTicks` specs keep their
  shape; `waitFor(timeout)` is not used (it runs `FakeProcess`'s `onWaitReturn` hook);
  `Thread.sleep` leaves `ChildOutput.java`. Specs: `ChildOutputSpec`/`ChildOutputTickSpec` assert
  no tick after the exit (the grace's counts removed) and a feature on `FakeProcess` whose exit
  lands during a long tick ends that tick at once (marker "The drain ends on its own after the
  exit"); `ChildOutputOwnerGateSpec`'s sleep detector allows `ThreadSleeper.java` only.
  `WritingHolder` (`:test-fixtures`) writes before *every* query, not every other one: its
  alternation was built against the grace's two idle queries, and without the grace one idle
  query after the exit ends the drain, so every cut case of its sixteen users would end inside the
  bound (javadoc and `WritingHolderSpec` follow; the cut specs run by name). The texts naming the
  grace follow too: `ChildOutputSpec`, `CaptureRunnerPostExitSpec`, `ProcessSupervisorInterruptSpec`,
  `CaptureRunnerDrainSpec` (`grep -rn grace` over the test trees, kill grace excluded). Docs: ADR
  0018 ("The rule" and its diagram, "Real time in a leaf"), the `ChildOutput` javadoc, the
  glossary entry **Child output** if it names the grace. Platform fact (`design-decisions.md`):
  measure on macOS and through `scripts/linux-specs.sh` that a capture's drain ends ≤ 1 ms after
  the exit on a real `git --version` (median of 200). Verify: the named specs, PIT scoped to
  `com.github.oinsio.gnomish.subprocess.ChildOutput*`, the `:subprocess` and `:adapters:agent`
  platform specs on Linux, and `TakeCommandStandingReaperWiringSpec` timed three times alternately
  against HEAD — parity within noise recorded in the report.

## 7. Final gate

- [x] 7.1 Root `./gradlew check` once, after the local Linux run of 6.1 is green (and again after 6.3, which the first run, 2026-10-19, made necessary): every module's PIT at 100% with no new exemption,
  `checkSpecContract`, the architecture specs of group 3. Recommend the commit message.

## Workflow follow-up

- `/opsx:archive make-pipe-reads-non-parking` after the review requirements are satisfied; the
  archive merges the REMOVED/ADDED pair into `openspec/specs/lifecycle/subprocess-supervision`
  and the MODIFIED round-drain requirement into `openspec/specs/executor/agent-executor`.
- Then `add-subprocess-access-log` and `extract-agent-round-runner` are rebased on the typed
  `ExecHandle.output()` through `/opsx:update`.

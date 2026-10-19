# Design: make-pipe-reads-non-parking

## Context

See `proposal.md`, Why, for the measurements. What shapes the design:

- Six readers, one drain. `Drain` (`:subprocess`) runs a virtual thread in
  `while ((read = stream.read(chunk)) != -1)` (`Drain.java:89`); `joinAll` joins with the bound,
  sets `cut = thread.isAlive()` and then closes every stream (`:128-144`). `StreamDrain`
  (`:adapters:agent`) runs its own thread over a `BufferedReader` and ends it by
  `thread.interrupt()` plus `source.close()` (`StreamDrain.java:128-131`). The four other readers
  (`GitExec`, `ContainerFileChannel`, `CapturedExec`, `BoundedTail`) are `Drain` callers. Every
  one starts from a bare `InputStream` taken by `getInputStream()`/`getErrorStream()` or by
  `ExecHandle.output()`.
- The JDK 25 facts, from `ProcessImpl.ProcessPipeInputStream` (read from the toolchain's
  `src.zip`): the stream subclasses `BufferedInputStream`, so `read()` **and** `available()` are
  `synchronized` on the stream; `processExited()` is `synchronized` too, drains `while
  (available() > 0)`, closes the descriptor and swaps the inner stream for a
  `ByteArrayInputStream` of the stragglers; `close()` takes a separate `closeLock`. So a close
  never contends with a read, and a read parked in the kernel is beyond every JDK mechanism.
- `:subprocess` depends on nothing (`module-layering`, "The subprocess leaf stays empty of
  dependencies"): no `Sleeper`, no `TimeEquipment`, no logger. `ProcessSupervisor` already
  waits on real time (`waitFor`, `onExit`).
- `FakeProcess` (`:test-fixtures`) is the one `Process` double (D16 of
  `fix-terminal-receipt-convergence`). Its pipes are NIO `Pipe` ends behind
  `Channels.newInputStream`, whose `available()` is always 0 for a non-seekable channel
  (`sun.nio.ch.ChannelInputStream.available`): a reader that reads only what is available would
  never read a byte from it. Its close wakes a parked reader on every OS.
- The post-exit bound (7 s: kill grace 5 s + margin 2 s) is carried across the environment port
  as `ExecHandle.postExitBound()`; the cut verdict is `CutCaptureException.refuses`; both stay.

## Goals / Non-Goals

**Goals (design level):** one class owns the reading strategy; a bare stream cannot reach a
drain; the factory never closes a child's pipe; the doubles are at least as strict as Linux; the
new real-process spec states no scheduling order.

**Non-Goals (design level):** a kernel wake-up (NG3), ending escaped holders (NG1), any change
to the bound, the sweep, the launcher or the stdin side.

## Decisions

**D1 — The readable side of a child's pipe is a typed value, `ChildOutput`, minted only from a
`Process`, and the only thing a drain can be started from.** Context: FR1, FR5, G3.
`ChildOutput.stdout(Process)` and `ChildOutput.stderr(Process)` in `:subprocess` hold the
process and its stream; `Drain.start/capped/into(ChildOutput, …)` replace the `InputStream`
overloads, `StreamDrain.start(ChildOutput, …)` likewise, and `ExecHandle.output()` returns
`ChildOutput` (`:sandbox:core` holds `:subprocess` as `implementation` today,
`sandbox/core/build.gradle:49`; a port that returns the type makes it `api`). The value carries
the exit signal the reader needs (`process.isAlive()`), so the stop rule of D2 is wired by
construction. The owner offers the loop of D2 in two forms over one implementation: the push
form (`Drain`, bytes handed to a sink on the owner's thread) and the pull form, a stream view
(`ChildOutput.stream()`) whose `read` runs the same steps on the caller's thread — it returns the
bytes the pipe reports, waits the tick of D2 when nothing is there, returns `-1` on `stop()` and
once the exit is seen with nothing pending, and whose `close()` is `stop()` — so a reader that must parse as it
reads (`StreamDrain`'s `BufferedReader` over `CountingInputStream`) parks no more than `Drain`
does. *Rationale:* `implementation.md` item 3 — a port that still accepts the raw form is
not enforced; the compiler does what no gate does reliably. *Alternative zero — delete the
post-exit close of D15 and keep the parked read:* nothing is left per run but the bound and the
sweep; an escaped holder then pins two threads and the reaper on both OSes. Rejected: it is the
leak class the proposal names. *Alternative rejected — a `NonParkingInputStream` wrapper over
the bare stream, API unchanged:* every caller could still start a drain on an unwrapped stream,
and `FakeProcess`'s stream would pass the type check while reporting nothing available.

**D2 — The reader never issues a `read()` for bytes the pipe has not reported.** Context: FR2,
FR3, NFR-P1, NFR-P2, NFR-R1. The loop, on the drain's own virtual thread:

1. `exited = !process.isAlive()` — read **before** the availability query, so a byte the child
   wrote before its exit is always seen by the query that follows.
2. `available = stream.available()`; if `> 0`, `read(chunk, 0, min(available, CHUNK))` — a read
   that returns without blocking because the bytes are there; a `-1` (the reaper's
   `ByteArrayInputStream` exhausted, or a holder-less end-of-file) ends the drain.
3. Else if `exited`: end (FR3). After the reaper's swap the query reads the stragglers' buffer,
   so no byte written before the exit is lost; after the exit the pipe is the JDK's, closed at
   `processExited`.
4. Else wait the tick for the exit: `process.onExit().get(tick)`, which returns at the tick or at
   once when the child exits, whichever comes first — 1 ms after bytes, doubling while idle,
   capped at 5 ms (NFR-P1 is "one tick", so the cap is the promise), reset to 1 ms on bytes.
5. A `stop` flag (D4) is checked at the top of every iteration; an interrupt of the sleep ends
   the loop the same way.

*Amended 2026-10-19 (task 7.1): no grace, and the tick wakes at the exit.* The first form slept
the tick (`Thread.sleep`) and, after the exit, waited one more tick before the last query. Every
launch then ended 6–13 ms after its child's exit — the backed-off tick to notice the exit plus the
grace — where a parked read had seen the end at the exit; a `take`, which runs hundreds of `git`
children, ran 20–28 % longer (`TakeCommandStandingReaperWiringSpec`, three alternate runs: 7.4 s
and 10.2 s per feature against 6.1 s and 8.0 s at HEAD), and under the root `check`'s load that
spec's 5 s wait for the first task timed out. The cost was nowhere in the risk list, which counted
per-line latency and idle queries only. The grace bought nothing: the JDK sets `hasExited` only
after `waitpid` reaped the child (`ProcessImpl`'s exit handler), so every byte the child wrote is
in the pipe by then, and `processExited` moves the pipe's content into the stragglers' buffer
under the monitor `available()` also takes, so "exited, and nothing available" already means the
child's own output is all read; the grace caught only a descendant's write in the next ≤ 5 ms,
which NG2 drops anyway. The test double of a writing holder (`WritingHolder`, D6) follows: it
writes before every query, since one idle query after the exit now ends the drain. *Alternatives rejected:* drop the grace and keep sleeping the tick — the
tail shrinks to one tick (≤ 5 ms) per launch, still paid by every launch; wait with
`process.waitFor(tick)` — `FakeProcess.waitFor` runs the spec's one-shot `onWaitReturn` hook, so
the drain would take the hook meant for the supervisor's wait, and the order specs that gate the
supervisor would lose it; raise the spec's 5 s wait — the extra seconds stay on every path that
launches `git`.

The text below records the first form, kept as the reasoning the amendment replaced: the wait was `Thread.sleep` on the drain's own thread — a recorded deviation from ADR 0014 and
`testing.md` ("Real time is built in one place": `ThreadSleeper` in `:bootstrap` is the only
`Thread.sleep(` in production today): `:subprocess` cannot take `Sleeper` (dependency-free leaf,
`module-layering`), and the wait is a property of the pipe, as `ProcessSupervisor`'s `waitFor`
already is, not a schedule a spec would want to drive virtually; the specs drive real processes and
`FakeProcess`, whose byte count the spec controls, so no spec sleeps for an order. What keeps the
deviation to one file: `DrainOwnerGateSpec` (D8) allows `Thread.sleep(` in production only in
`ChildOutput.java` and `bootstrap/.../ThreadSleeper.java`, and ADR 0018 records where real time is
admissible in a leaf (D9).
*Rationale:* the shape the JDK's reaper applies (`drainInputStream`), and the invariant that
nobody ever holds the stream's `synchronized` monitor across a wait — which is what frees the
reaper (NFR-R3) and what makes `available()` (itself `synchronized`) safe to call. *Alternative
rejected — `poll(2)` / `O_NONBLOCK` through FFM:* the descriptor is private to `FileDescriptor`
(`--add-opens`), and `O_NONBLOCK` sits on the open file description the reaper's drain shares, so
the reaper's own `read` would see `EAGAIN`. *Alternative rejected — a fixed 5 ms tick:* simpler,
but an idle `serve` with many slots pays 200 queries per second per stream for nothing; the
backoff costs one counter.

**D3 — The factory closes no pipe of a child.** Context: FR3, NFR-R3. `Drain.closeQuietly` and
`StreamDrain.closeSource` are deleted; `joinAll` and `StreamDrain.close()` set the stop flag
(D4) and nothing else. The reaper closes the descriptor at the exit; a reader stopped early
leaves whatever the reaper buffered to the garbage collector. *Rationale:* on Linux the close
does nothing to a parked read and, measured, turns a holder's first write into a lost chunk and
a "Stream closed" failure; with D2 there is no parked read for a close to help. *Alternative
rejected — keep the close as belt and braces:* it reintroduces the one path on which a reader
can see `IOException("Stream closed")` from `available()`, which D2 would then have to classify.

**D4 — The bound stops, it does not close; the cut verdict is unchanged.** Context: FR4,
NFR-R2. `Drain.joinAll(bound, drains)` joins each drain with the shared bound as today, sets
`cut = thread.isAlive()`, and calls `stop()` on every cut drain: a volatile flag the loop sees
within a tick. `CutCaptureException.refuses(termination, exitCode, cut)` and every consumer stay
as they are; `DrainOwnerGateSpec`'s M12/M16 features stay green. *Rationale:* with D2 the bound
is reached only when bytes keep arriving every tick after the exit, which the reaper's close at
the exit makes impossible for a pipe holder — so the bound is the safety net the spec promises,
and the stop keeps it honest. The one path on which a real process is still flagged: a holder
that writes faster than the reaper's `drainInputStream` (`while (available() > 0)`) at the exit
keeps the reaper inside the `synchronized` `processExited`, the drain's `available()` waits on
that monitor, and the bound expires; measured, the reaper caught up with a busy shell writer
(6815 bytes of stragglers). *Alternative rejected — drop the bound since D2 ends on its own:*
the promise "returns within the bound" would then rest on the reaper's behaviour alone, which
this change exists to stop trusting.

**D5 — `StreamDrain.close()` is the owner's stop.** Context: FR6. `close()` calls
`output.stop()` — the `ChildOutput`'s stop, observed by the stream view's next `read` (D1), so
the `BufferedReader` above it sees end-of-stream within a tick — and keeps the interrupt for a
thread inside the parser; it joins nothing (D17 of `fix-terminal-receipt-convergence` stands: the
wait is `await`'s, with the handle's bound). `CountingInputStream` wraps the view; the
try-with-resources in `drain()` (`StreamDrain.java:142`) stays and now closes the view, which is
`stop()`, not a close of the pipe, so D3 holds on the end-of-file path too. The promise lives in
`executor/agent-executor` ("The round drains its stdout from launch within the handle's post-exit
bound"): the THEN of "No reader outlives the round" loses "the stdout stream is closed" and gains
"no read of the stream is pending", and the scenario "The round's close leaves no drain thread" is
added there — the delta of that capability in this change, not a second requirement under
`subprocess-supervision`. `AgentRoundTeardownSpec`, which asserts the round's close through
`TrackedStream.closed`, is rewritten on `FakeProcess`. *Rationale:* the round's promise
NFR-R1 of `fix-round-stdout-drain` ("no drain thread outlives the round") is restored on Linux by
the same mechanism `Drain` uses, not a second one. *Alternative rejected — a `StreamDrain` of
its own polling loop:* a second implementation of the stop rule, the sync-pair shape
`manual-sync-pairs.md` forbids at the second instance when one abstraction serves both.

**D6 — `FakeProcess` is backed by an in-memory pipe that behaves as a Linux pipe.** Context:
FR7, G4. A `FakePipe` in `:test-fixtures`: `write` appends under a lock and signals;
`available()` is the exact unread count; `read` returns what is there, blocks on empty while the
write end is open, returns `-1` once the write end is closed (`release`); closing the **read**
end releases no reader parked in `read` — the Linux fact, the stricter of the two platforms.
`holdOpen`, `release`, `releaseStderr`, `releaseStdout`, `awaitStdoutRead`, `onWaitReturn` keep
their contracts. The `HeldStream` of `DrainJoinAllSpec`, the `HeldStream` and `SlowStream` of
`CannedExecHandle`, and `StreamDrainSpec.spinningStream` are deleted; their features drive
`FakeProcess` through a `ChildOutput`. *Rationale:* a double that is laxer than the platform is
how every double-based spec stayed green on Linux (proposal, Why); a double stricter than any
platform catches a parked read on the developer's macOS. *Alternative rejected — keep the NIO
pipe and override `available()`:* its close still wakes a reader, and a reader that parks keeps
passing.

**D7 — One real-process spec with an escaped writing holder, in the shape of Go's `Exit-hang`
test.** Context: FR9, M3. A new stand-in process script, `escaped-holder.sh`, starts a writer in
its own session (`setsid` on Linux, `/usr/bin/perl -e 'POSIX::setsid()…'` on macOS — the two
forms `HostLauncher` already uses) that ignores `SIGPIPE` (`trap '' PIPE`) and keeps writing,
records its pid, speaks, and exits 0. The spec runs it through a real `CaptureRunner` with a short
kill grace and asserts: the invocation returns well inside the bound, the capture holds the
spoken text and is not flagged, no live thread has `FileInputStream.readBytes` on its stack,
`process.onExit()` completes, and the holder is alive at the assertion (killed in cleanup).
*Amended 2026-10-18 (decision review on task 1.4's finding):* the original assertions —
`CutCaptureException` with the spoken prefix — contradicted D4: the JDK's
`ProcessPipeInputStream.processExited` (`synchronized`; drains `while (available() > 0)`, closes
the descriptor, swaps in a `ByteArrayInputStream` of the stragglers) runs at the exit because no
factory thread holds the stream's monitor, so the drain ends at its next query after the exit (a grace tick later in the first form of D2) with the capture
unflagged; measured on macOS JDK 25.0.3 (18–35 ms; a holder that does not ignore `SIGPIPE` dies
of it) and Linux JDK 25.0.4.1 (4–17 ms), in-group and escaped, busy and 50 ms writers alike. What
the flag was for (FR17 of `fix-terminal-receipt-convergence`: no capture consumed as a value
while a holder may extend the stream) is kept by construction — the stream ends at the exit and
a value's frame check is the second layer; its operator line now fires on the safety net only
(NFR-O1, accepted). *Alternatives rejected:* flag a capture whose reaper buffer held stragglers —
every ordinary child whose output was still in the pipe at its exit would be refused, a false
`CutCaptureException` on any `git rev-parse`; keep the stream's monitor so the reaper cannot
close the pipe — a parked read, against D2. No order is stated: the holder writes
continuously, so "parked before exit" is not a premise. *Rationale:* `testing.md`, "A scheduling
order is never a premise"; the Linux CI job is the gate this change was opened for. *Alternative
rejected — keep `DrainCloseReleaseSpec`'s thread-dump choreography:* it exists to make the order
"parked, then exit", which D2 makes meaningless.

**D8 — The gate grows to the reading strategy.** Context: FR8, G3. `DrainOwnerGateSpec` adds
four detectors over every production source (`RepoSourceTree`, which walks every `src/main` of
the build, `:test-fixtures` included) — the call shapes `\.get(?:Input|Error)Stream\s*\(` and
`\.(?:input|error)Stream\b` (the forms `RawCaptureGateSpec.PROCESS_STREAM` already recognizes,
so a method *definition* such as `FakeProcess.groovy:143` is not a hit), a `.read(` on a
`ChildOutput`'s stream, and `Thread.sleep(` (D2) — allowed only in `ChildOutput.java` (and, for
the sleep, `ThreadSleeper.java`; *amended 2026-10-19:* only in `ThreadSleeper.java`, since D2's
tick now waits on the exit and `ChildOutput.java` sleeps nowhere), with two listed exemptions, each asserted reached: the
`FakeProcess` and `ScriptedSandboxDocker` definitions of the two accessors in `:test-fixtures`,
which implement `Process` rather than read a child; and asserts the `READERS` map is reached.
*Amended 2026-10-19 (task 2.6's sweep):* five more sites in `:test-fixtures` match the detectors
and are listed exemptions with their reasons, each asserted reached — the test-time readers
`LocalGitRepoFixture`, `SeedTransferFixture`, `GuardImageAvailability` (each reads to end of
stream a process it launched itself) and `GroupLeader` (one readiness line of a live stand-in,
which a drain that waits for the end of the stream cannot give), the four rows `RawCaptureOwners`
already carries as "test-time only"; and the poll of `StallingGit.awaitStall` (`Thread.sleep(20)`
while waiting for a stand-in's log). None is on a runtime path: a parked read there can hang a
spec, never the factory. *Alternatives rejected:* scope the detectors to the running factory's
sources — a new fixture that reads a child's stream itself would pass unseen; route the readers
through `ChildOutput` — `GroupLeader` would need a line read on the owner that only tests use.
*Revisit when* — the exemption list is reconsidered, by `/architect` and this design's successor,
on any of: a spec hangs or flakes on a parked read in one of these fixtures (the Linux CI job or
a local run); a sixth fixture reader or sleep is proposed for the list; one of these fixtures is
used outside the test classpath; or `ChildOutput` gains a line read or a capture-to-end form for
a production reader, at which point the four readers move onto it and their rows leave. `RawCaptureOwners` drops the entries of `CaptureRunner`, `GitExec`,
`HostExecHandle` and `ContainerFileChannel` (they no longer capture a stream) and gains
`ChildOutput` under the `:subprocess` mechanics carve-out; its "every listed file still captures"
check is what forces the drop. *Alternative rejected — convention plus review:* `implementation.md`
item 4.

**D9 — Where the principle lands.** Context: G1, M5. `docs/adr/0018-non-parking-pipe-reads.md`
states the rule (a factory thread never parks in a blocking read of a child's pipe; the pipe is
the JDK's to close), the method rule that produced it (a JDK or OS fact a design rests on is
measured on Linux and macOS before it is written down), and where real time is admissible in a
leaf (D2: a wait that is a property of the medium — a pipe's tick, a process's `waitFor` — in a
module that may take no `Sleeper`, held to one file by a gate; everything else takes
`TimeEquipment`, ADR 0014). `lock-scope.md`, "A lock inside the JDK":
the sentence "The read side is different: … wakes a parked `read()`" is replaced by the ADR's
rule. `design-decisions.md` gains one paragraph, "A platform fact is measured on every platform
CI runs". `docs/glossary.md`: **Drain** (reads what is available, never parks), **Post-exit
bound** (the reader stops the drain; nothing is closed), **Cut capture** ("the drain had not
ended"), and a new entry **Child output**. The main spec's notes are rewritten through the delta.
*Amended 2026-10-19:* the method rule covers verification too — a spec whose subject is the
platform runs on Linux before the root `check` and before a push, through a committed Linux
spec runner (`scripts/linux-specs.sh` over `scripts/linux-runner/Dockerfile`, the CI `build`
job's platform), named by `verification-scope.md`; CI is the last confirmation, not the first
Linux signal. *Alternative rejected:* building the runner `FROM` the sandbox base image of
`add-sandbox-base-image` — a box image that carries the guard's proxy in its environment and
mirrors the box, not CI; revisit if that base ever ships without the proxy and on CI's
platform.

**Sync surfaces:** none added. This change touches no declared pair of `manual-sync-pairs.md`.
The push form (`Drain`) and the pull form (the stream view) of the loop of D2 are one
implementation in `ChildOutput` with two entry points, not a pair: both step through the same
method, and `ChildOutputSpec` drives each scenario through both. It dissolves an undeclared pair
of test doubles (`HeldStream` in `:subprocess` tests and `HeldStream` in `:sandbox:core` tests,
the same blocking pipe written twice) into `FakePipe`.

**Single-owner mechanisms:**

| Owner | Value (type) | Requires | Consumers | Old way removed | Enforced by |
|-------|--------------|----------|-----------|-----------------|-------------|
| `ChildOutput` (`:subprocess`): the readable side of a child's pipe, read without parking (D1, D2) | `ChildOutput` | a started `Process` | `CaptureRunner` (stdout, stderr), `GitExec` (stdout, stderr), `ContainerFileChannel` (stdout, stderr), `HostExecHandle.output()` → `CapturedExec`, `BoundedTail` (via `CommandProcessRunner`), `StreamDrain` (via `ExecutorRoundExecution`, `JudgeRoundExecution`); and every `ExecHandle` double that must now mint the value from a `FakeProcess`: `CannedExecHandle` (`:sandbox:core` tests), `ScriptedExecHandle` (`:test-fixtures`), `ObservedBox` (`:bootstrap` tests), `FakeExecHandle` of `CommandProcessRunnerCutTailSpec` (`:adapters` tests), `FakeHandle` ×2 of `InBoxGitCommandSpec` (`:adapters:git` tests), `ExitedExecHandle` of `ExecutorRoundDrainTimeoutSpec` (shared with `JudgeRoundDrainTimeoutSpec`), `InterruptedWaitExecHandle` of `RoundInterruptedWaitSpec`, `ScratchStderrHandle`, `ScriptedHandle` of `AgentRoundTeardownSpec`, and the `Stub(ExecHandle)` doubles of `JudgeRoundGuardProbeSpec`, `JudgeCannotVerifyLoggingSpec` and `JudgeRoundDrainTimeoutSpec` (`:adapters:agent` tests) | `Drain.start/capped/into(InputStream, …)`, `StreamDrain.start(InputStream, …)`, `ExecHandle.output(): InputStream`, `Process.getInputStream()`/`getErrorStream()` in `CaptureRunner`, `GitExec`, `HostExecHandle`, `ContainerFileChannel` — all deleted; no exemption | the parameter type; `DrainOwnerGateSpec` (D8); `RawCaptureOwners` |
| `FakePipe` (`:test-fixtures`): the one in-memory double of a child's pipe (D6) | `FakeProcess`'s streams | nothing | every spec that holds a pipe past the exit: `CaptureRunnerPostExitSpec`, `CaptureRunnerDrainSpec`, `DrainJoinAllSpec`, `CapturedExecPostExitSpec`, `ContainerFileChannelPostExitSpec`, `GitProcessRunnerTruncatedCaptureSpec`, `DockerCliTruncatedCaptureSpec`, `CommandProcessRunnerCutTailSpec`, `StreamDrainSpec`, `StreamDrainLogScopeSpec`, `AgentRoundTeardownSpec`, and the nine `ExecHandle` doubles of the row above | `HeldStream` ×2, `SlowStream`, `spinningStream`, `TrackedStream`, `PipedInputStream`/`ByteArrayInputStream` handed to `StreamDrain` or returned by an `ExecHandle` double, and `ScriptedSandboxDocker`'s override of `getInputStream()` with a `ByteArrayInputStream` (`ScriptedSandboxDocker.groovy:153`, routed through `writeStdout`), the private writing-holder copy in `CannedExecHandle` (routed through `WritingHolder` in `:test-fixtures`, the one double of a holder that writes after the exit), and the streams `ScriptedFileChannelProcess` takes from its callers — `StalledInputStream` and `SlowEofStream` (`ContainerFileChannelStallSpec`), `EofSignallingStream` (`ScriptedFileChannelDockerCli`), the throwing anonymous stream of `ContainerFileChannelPostExitSpec` (task 2.3) — deleted | the `ChildOutput` parameter (a stream double cannot be passed); `FakePipeGateSpec` (`:bootstrap`, split from `FakePipeSpec` in task 1.1) asserts over every test tree and `test-fixtures/src/main` that `extends Process` appears only in `FakeProcess` and its listed subclasses (`ScriptedFileChannelDockerCli`, `ScriptedDocker`, `ScriptedSandboxDocker`) and that none of them overrides `getInputStream`/`getErrorStream` — no gate pins this today (`LaunchOwnerGateSpec` checks `ProcessBuilder…start()` and `ProcessLaunch` implementations, not `Process` doubles) |

Identity: the design claims "the drain's stop rule is wired by construction" — the real-medium
spec is D7's, which runs the whole path (launch, drain, bound, refusal) on a real process.

## Risks / Trade-offs

- [A tail after every exit: the end of a capture waits for the reader to notice the exit] → the
  tick wakes at the exit (D2 as amended 2026-10-19), so a launch pays no tick; measured as parity
  of `TakeCommandStandingReaperWiringSpec` with HEAD (task 6.3). The first form's 6–13 ms per
  launch cost a `take` 20–28 % and was found only by the root `check`.
- [Up to one tick of added latency per live line] → the cap is 5 ms, below anything a progress
  listener or a round's wall clock can see; NFR-P1 is pinned by a `FakeProcess` feature that
  counts ticks.
- [An idle stream costs a query per tick] → backoff to the 5 ms cap; measured 200 syscalls per
  second per stream at the cap, which a `serve` with a dozen slots does not notice.
- [The `isAlive` → `available` window: the reaper swaps the stream between the two calls] → the
  query after the swap reads the reaper's buffer, which holds everything the pipe held, so the
  order of D2 step 1 loses nothing; pinned by a `FakeProcess` feature that exits between the two
  calls (`onWaitReturn`-style hook on `isAlive`).
- [A holder writing continuously after the exit keeps `available() > 0` until the reaper closes
  the pipe] → the reaper runs at once (nobody holds the monitor), and the bound stops the drain
  in any case (D4).
- [PIT: the backoff constants (and, in the first form of D2, the grace) are timing] → `FakePipe` counts queries, so a mutant
  that removes the backoff or the end at the exit changes a count a spec asserts; a `@DoNotMutate` is not
  expected and would need its own justification under `testing.md`.
- [Twenty-five test files touch `FakeProcess`] → its public contract (`running`, `exit`,
  `writeStdout`, `holdOpen`, `release`, `awaitStdoutRead`, `onWaitReturn`, `leading`) is kept;
  only the streams' backing changes, so the subclasses (`ScriptedProcess`, `FakeExecProcess`)
  compile unchanged.
- [`add-subprocess-access-log` and `extract-agent-round-runner` touch the same files] → this
  change lands first; both are unapplied and their artifacts reference `Captured` and
  `StreamDrain` by behaviour, not by the stream type.
- [`own-git-invocation-policy` (`GitExec`, `CaptureRunner`: the `ProcessBuilder` policy) and
  `fix-docker-exec-env-argv` (`HostExecHandle`, `ContainerFileChannel`: the `docker.start` chains)
  edit the same files on other lines] → either order; whichever lands second is rebased through
  `/opsx:update`, nothing in those changes names the stream type.

## Migration Plan

Internal only: no configuration, no persisted format, no operator-visible behaviour changes.
Rollback is `git revert` of the change's commits.

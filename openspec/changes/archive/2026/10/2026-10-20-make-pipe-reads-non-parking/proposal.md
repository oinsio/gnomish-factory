# Proposal: make-pipe-reads-non-parking

## Why

Every reader of a child's output in the factory parks a thread in a blocking `read()` on the
child's pipe, and the post-exit mechanism of design D15 of `fix-terminal-receipt-convergence`
ends that read, when a descendant still holds the pipe after the child exited, by closing the
stream at the post-exit bound. That close wakes the parked read on macOS and does nothing on
Linux: Linux's `close(2)` leaves a blocking read holding the open file description, and a Java
interrupt does not reach a `FileInputStream` read either. The fact was measured on macOS only.

Measured on Temurin 25.0.3 (macOS and `eclipse-temurin:25-jdk`), 2026-10-18:

| Experiment                                            | macOS               | Linux                                                           |
|-------------------------------------------------------|---------------------|-----------------------------------------------------------------|
| `close()` from another thread wakes a parked `read()` | 0 ms                | never (virtual and platform threads alike, > 3 s)               |
| `Thread.interrupt()` wakes it                         | no                  | no                                                              |
| A holder's first write after the close                | holder gets SIGPIPE | write succeeds, wakes the read with "Stream closed", bytes lost |
| A silent holder                                       | read released       | thread parked until the holder dies                             |

The first Linux run of `:subprocess:test` after that change (CI run 39117545428) is red on
`DrainCloseReleaseSpec`, and `StreamDrainCloseReleaseSpec` states the same premise. Every spec
on a test double stays green on Linux, because the doubles model macOS: `FakeProcess` builds its
stdout on a NIO `Pipe`, whose close wakes a reader on every OS, and `HeldStream` releases its
reader in `close()`.

In production the defect is a leak, not a stall: every reader's join is bounded, the cut verdict
is decided before the close, and the group sweep ends every holder still in the child's process
group, so the parked read gets end-of-file. A holder that left the group (`setsid`, `setpgid`) is
the sweep's named limit, and on Linux it now pins the drain's thread and the JDK's reaper thread
(parked in the `synchronized` `processExited`, JDK-8169565) for its lifetime; in `serve` those
accumulate. The promise "no thread of the factory waits on the descendant" and NFR-R1 of
`fix-round-stdout-drain` ("no drain thread outlives the round") are not kept on Linux.

The systemic answer, which the JDK's own reaper, Go's `os/exec`, libuv, Python's
`communicate()` and Rust's `read2` all take, is to never park in a pipe read that may have to be
abandoned: read only what the pipe reports as available, wait a tick otherwise, and stop when the
child has exited and nothing is pending. The reaper then drains and closes the pipe itself at the
exit, since nothing holds the stream's monitor. The post-exit close of D15 is deleted rather than
fixed.

## What Changes

- **MODIFIED** `lifecycle/subprocess-supervision`:
  - The requirement "Post-exit output is bounded" is **REMOVED** and re-stated as "Post-exit
    output is bounded without a parked read": no thread of the factory parks in a read of a
    child's pipe; a drain reads what the pipe reports available, ends on its own once the child
    exited and nothing is pending, and the supervisor closes nothing. The scenario "Closing the
    pipe releases the parked drain" — a promise the code cannot keep on Linux — goes with the
    requirement, by this decision review (`spec-mismatch.md`), and is replaced by "The drain ends
    on its own after the exit" and "An escaped holder pins no thread". The surviving scenarios
    keep their titles.
  - The requirement "One launch owner" gains the scenario "The fake process behaves as a Linux
    pipe": a test double's held pipe wakes no reader on close.
- **MODIFIED** `executor/agent-executor`: the requirement "The round drains its stdout from launch
  within the handle's post-exit bound" loses the promise "the stdout stream is closed" from the
  scenario "No reader outlives the round" (the pipe is the JDK's to close) and gains "The round's
  close leaves no drain thread"; `AgentRoundTeardownSpec`, which asserts the close today, is
  rewritten on the fake process.
- One owner of the readable side of a child's pipe in `:subprocess`, a typed value a drain can
  only be started from; `Drain` and `StreamDrain` no longer accept a bare `InputStream`
  (**BREAKING** for a caller outside the repository that started a `Drain` on its own stream —
  none exists; `:subprocess` is not a published API).
- All six readers migrate: `CaptureRunner`, `GitExec`, `ContainerFileChannel`, `CapturedExec`,
  `BoundedTail`, `StreamDrain`. The `ExecHandle` port's `output()` hands out the typed value.
- `FakeProcess` becomes a Linux-faithful double: an in-memory pipe whose `available()` is exact and
  whose close releases no parked reader. The `HeldStream`, `SlowStream` and `spinningStream`
  doubles are deleted; their specs drive `FakeProcess`.
- The build gate `DrainOwnerGateSpec` grows: no production source but the owner calls
  `getInputStream()`, `getErrorStream()` or `read(` on a child's stream.
- One real-process spec in the shape of Go's `Exit-hang` test (exit 0, a `setsid`-escaped writing
  holder): the invocation returns at the exit, well inside the bound, the capture is complete and
  not flagged, no thread is left parked, the reaper is released, the holder still runs. It runs on both operating systems; Linux CI is the gate.
- Docs: the false statement is corrected in `lock-scope.md` ("the read side is different"),
  `docs/glossary.md` (**Post-exit bound**, **Drain**, **Cut capture**), the main spec's notes and
  the javadocs of the six readers and their specs; an ADR records the principle and the method
  rule (a JDK or OS fact a design rests on is measured on Linux and macOS).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `lifecycle/subprocess-supervision`: "Post-exit output is bounded" is removed and re-added as
  "Post-exit output is bounded without a parked read" (the supervisor closes no pipe; the drain
  never parks; the reaper closes the pipe at the exit); "One launch owner" gains the fake-process
  fidelity scenario.
- `executor/agent-executor`: "The round drains its stdout from launch within the handle's post-exit
  bound" — the round's close is the owner's stop, not a close of the pipe; one scenario's THEN
  rewritten, one scenario added.

## Goals

- **G1**: No thread of the factory is ever parked in a blocking read of a child's pipe, on any
  operating system the factory runs on.
- **G2**: After a child's exit every drain of its output ends on its own, within a tick of the
  reader, whether or not a descendant holds the pipe; the JDK's exit handling of that process
  completes.
- **G3**: One owner of the pipe-reading strategy, enforced by the type a drain is started from
  and by a build gate; adding a reader cannot reintroduce a parked read.
- **G4**: The test doubles of a child's pipe behave as a Linux pipe, so a spec on a double goes
  red where production would on Linux.

## Non-Goals

- **NG1**: Ending a `setsid`-escaped holder itself (a `/proc` parent walk, a subreaper, a cgroup
  kill). The group sweep's named limit stands; this change makes the limit cost nothing. The
  change that gives the launcher a per-invocation cgroup or PID namespace on the host picks it up.
- **NG2**: Capturing what a holder writes after the child exited. The JDK's reaper drops it at
  the exit already; the policy "post-exit output is dropped" is kept and stated. A change that
  needs a holder's post-exit output must give that holder its own stream.
- **NG3**: Kernel wake-ups instead of a tick — `poll(2)` through FFM, `O_NONBLOCK` on the fd. The
  fd is reachable only through `--add-opens`, and `O_NONBLOCK` sits on the open file description
  the reaper's own drain shares. A change that adopts FFM for process control may revisit it.
- **NG4**: Redirecting a live stream to a file. The agent round parses stream-json line by line
  as it arrives; a file loses back-pressure and still needs a tail poller. The round's unread
  stderr already goes to a file.
- **NG5**: The write side. Design D20 of `fix-terminal-receipt-convergence` removed every stdin
  pump; nothing here touches `StdinSource`.
- **NG6**: Changing the post-exit bound's value or derivation. It stays the kill grace plus the
  margin, and stays the one safety net on the join.
- **NG7**: The container's PID namespace, in-box processes, and the `docker exec` client's own
  behaviour with an in-box holder. The client is a host process read like any other.

## Users & Scenarios

- **U1**: A `serve` daemon on Linux runs a gnome whose agent CLI started a language server with
  `setsid`. The round ends, the server lives on. No drain thread and no reaper thread stays parked
  behind it; the round's result is complete, and the daemon's thread count does not grow per round.
- **U2**: The factory runs `git cat-file --batch` through `GitExec` on Linux; the capture is
  complete and the invocation returns at the exit with no wait, exactly as on macOS.
- **U3**: A command check's script backgrounds a process that keeps the check's stdout. The tail
  is complete and not flagged, the verdict stands, the sweep ends the background process, and no thread of
  the factory is left on it on either OS.
- **U4**: A developer writes a spec of a reader against `FakeProcess.holdOpen()`. If the reader
  parks in `read()`, the spec fails on macOS the way CI would fail on Linux.

## Requirements

### Functional

- **FR1**: The readable side of a child's output pipe SHALL be a typed value of the supervision
  module, minted only from a started `Process`, and a drain SHALL be started only from that value;
  no production code outside the owner SHALL call `getInputStream()`, `getErrorStream()` or a
  `read` on a child's stream.
- **FR2**: The owner SHALL read a child's pipe without parking: a `read` is issued only for the
  byte count the pipe reports as available, and when nothing is available the reader waits one
  tick on its own thread, never inside the read.
- **FR3**: A drain SHALL end on its own as soon as it sees that the child has exited and the pipe
  reports nothing available, with no further wait, and its wait for bytes SHALL end at the
  child's exit; the supervisor SHALL close no pipe, so the JDK's reaper drains and closes it at
  the exit. (Amended 2026-10-19: the first form waited one tick of grace, design D2.)
- **FR4**: A drain still open at the post-exit bound SHALL be stopped by the join, and the
  capture flagged as cut exactly as today (`CutCaptureException.refuses` unchanged); the stop is
  a flag the reader observes within a tick, never a close or an interrupt of the read.
- **FR5**: All six readers — `CaptureRunner`, `GitExec`, `ContainerFileChannel`, `CapturedExec`,
  `BoundedTail`, `StreamDrain` — SHALL read through the owner, whether they take the bytes as a
  push drain or pull them through the owner's stream view; the `ExecHandle` port's `output()`
  SHALL hand out the typed value, and every test double of the port SHALL mint it from the fake
  process.
- **FR6**: `StreamDrain.close()` SHALL stop the drain by the owner's stop, so that no drain
  thread outlives the round on any OS (NFR-R1 of `fix-round-stdout-drain` restored on Linux).
- **FR7**: `FakeProcess` SHALL back its pipes with an in-memory pipe whose `available()` reports
  the unread count exactly and whose `close()` releases no reader parked in `read()`; the
  `HeldStream`, `SlowStream` and `spinningStream` doubles SHALL be deleted.
- **FR8**: A build gate SHALL fail on a production source outside the owner that calls
  `getInputStream()`, `getErrorStream()` or `read(` on a child's stream, and SHALL assert it
  reached every reader it lists.
- **FR9**: One real-process spec SHALL exit 0 while a holder that left the process group keeps
  writing to stdout, and SHALL assert: return well inside the bound, every byte the child wrote
  before its exit in the capture and the capture not flagged, no factory thread parked on the
  pipe, the reaper released (`onExit` completes), the holder still running — on macOS and on
  Linux.
- **FR10**: The scenario "Closing the pipe releases the parked drain" SHALL leave the main spec
  with its requirement, and the two specs that carry it (`DrainCloseReleaseSpec`,
  `StreamDrainCloseReleaseSpec`) SHALL be rewritten to the new scenarios, not annotated away.

### Non-Functional Performance

- **NFR-P1**: A line of live stream-json output reaches its listener no later than one tick
  after it is written; the tick is at most 5 ms.
- **NFR-P2**: An idle drain costs at most one `available()` call per tick per stream; the owner
  backs the tick off while a stream stays idle, up to a bound, and resets on bytes.
- **NFR-P3**: A capture larger than the OS pipe buffer (≥ 240 KiB in one burst) is complete and
  the drain ends within a tick of the exit (measured 20 000 lines, 0 lost, on both OSes).

### Non-Functional Reliability

- **NFR-R1**: No byte the child wrote before its exit is lost: either the reader took it, or the
  reaper's own drain at the exit took it and the reader reads it from the reaper's buffer.
- **NFR-R2**: A reader that is stopped — by the bound or by the round's close — ends within one
  tick on every path, the interrupt path included.
- **NFR-R3**: The drain's own thread is the only thread that ever touches the stream, so the
  stream's monitor is never held across a wait and the reaper is never blocked by the factory.

### Non-Functional Observability

- **NFR-O1**: A cut capture keeps its one WARN with the catalog codes of today
  (`GIT_CAPTURE_TRUNCATED`, `DOCKER_CAPTURE_TRUNCATED`); no new operator event is introduced, and
  no line is emitted per tick. On a real process the WARN is reached only on the post-exit bound's
  safety net (a holder outwriting the JDK reaper's own drain at the exit): the reaper closes the
  pipe at the exit, so a pipe holder no longer produces it, and the holder line of NFR-O3 and UX5
  of `fix-terminal-receipt-convergence` survives on the safety net only — accepted 2026-10-18; an
  in-group holder is visible through the group sweep's DEBUG survivor line, an escaped one is NG1.

### Non-Functional Security

- Considered; none applies beyond the existing untrusted-text handling of captured output, which
  this change does not touch.

### Non-Functional Cost

- Considered; none applies. No tokens are involved.

## Operator Experience Criteria

- **UX1**: The cut notice, its codes and the exit codes are unchanged where the bound is reached;
  a pipe holder no longer produces a notice on a real process, because the invocation returns at
  the exit with the child's output complete (NFR-O1). The difference is a Linux daemon whose thread count no longer grows with
  escaped holders.

## Success Metrics

- **M1**: Production call sites of `getInputStream()`/`getErrorStream()` on a child: 5 files
  today (`CaptureRunner`, `GitExec`, `HostExecHandle`, `ContainerFileChannel`, plus the fixture
  entries) → 1 (the owner).
- **M2**: Readers started from a bare `InputStream`: 6 → 0; `Drain.start(InputStream, …)` and
  `StreamDrain.start(InputStream, …)` no longer compile.
- **M3**: `DrainCloseReleaseSpec`, `StreamDrainCloseReleaseSpec` and the new escaped-holder spec
  green on Linux CI and macOS, 20 consecutive local runs each.
- **M4**: In-JVM doubles of a held pipe: 3 (`HeldStream` ×2, `spinningStream`) → 0; every
  double-based post-exit spec drives `FakeProcess`.
- **M5**: Sentences stating that a close wakes a parked read, in rules, glossary, specs and
  javadocs: 15 found → 0.

## Open Questions

- **Q1** (resolved in design): fixed tick or backoff — backoff, bounded, reset on bytes (D2).
- **Q2** (resolved in design): where the "exited" signal comes from — the owner holds the
  `Process` and reads `isAlive()` (D1).
- **Q3**: Whether the neighbouring session's temporary `@PendingFeatureIf({ os.linux })` on the
  two specs lands before this change. If it does, this change removes it (task 5.3); if not, the
  task is a no-op.

## Impact

- `:subprocess`: new owner of the pipe's readable side; `Drain` rewritten on it; `CaptureRunner`
  migrated; `DrainCloseReleaseSpec` rewritten; the new escaped-holder spec; a `setsid`-escaping
  stand-in preset in `:test-fixtures`.
- `:gitobjects`: `GitExec` migrated.
- `:sandbox:core`: `ExecHandle.output()` typed, `:subprocess` becomes an `api` dependency;
  `CapturedExec` migrated; `CannedExecHandle`'s doubles replaced by `FakeProcess`.
- `:sandbox:docker`: `HostExecHandle`, `ContainerFileChannel` migrated.
- `:adapters`: `BoundedTail` migrated.
- `:adapters:agent`: `StreamDrain` migrated; `StreamDrainCloseReleaseSpec`, `StreamDrainSpec`,
  `StreamDrainLogScopeSpec`, `AgentRoundTeardownSpec` and the module's four `ExecHandle` doubles
  rewritten on `FakeProcess`.
- `:adapters:git`, `:bootstrap` (tests): the `ExecHandle` doubles of `InBoxGitCommandSpec` and
  `ObservedBox` mint the typed value.
- `:test-fixtures`: `FakeProcess` on an in-memory Linux-faithful pipe; `ScriptedExecHandle` and
  `ScriptedSandboxDocker` on it.
- `:bootstrap`: `DrainOwnerGateSpec` grows; `RawCaptureOwners` entries shrink to the owner.
- Docs: `docs/adr/0018-non-parking-pipe-reads.md` (0017 is reserved by
  `fix-docker-exec-env-argv`), `.claude/rules/lock-scope.md`, `docs/glossary.md`.
- Sequencing: before `add-subprocess-access-log` (its delta modifies "Supervised subprocess
  invocations", a different requirement; its design records terminations through the same
  readers) and before `extract-agent-round-runner` (which moves `StreamDrain`). Layered on
  `fix-terminal-receipt-convergence` (archived 2026-10-18): the REMOVED block names the
  requirement as that change left it. `own-git-invocation-policy` (`GitExec`, `CaptureRunner`)
  and `fix-docker-exec-env-argv` (`HostExecHandle`, `ContainerFileChannel`) edit the same files
  on other lines: either order; whichever lands second is rebased through `/opsx:update`.

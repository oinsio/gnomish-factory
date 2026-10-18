# Spec Delta: lifecycle/subprocess-supervision

## ADDED Requirements

Added 2026-10-16 (design D15–D20): the "An inherited pipe cannot block the return" scenario of
"Supervised subprocess invocations" bounds the kill path only; these requirements bound the
normal exit the same way, give the launch one owner, and kill what the descendant snapshot
cannot see. The requirement `add-subprocess-access-log` modifies is not touched here.

### Requirement: Post-exit output is bounded
After a supervised invocation's exit — normal, expired or interrupted — the supervisor SHALL wait for each output drain at most one post-exit bound, derived from the kill grace plus a fixed margin, then close its own ends of the pipes, and SHALL return the capture read until then, flagged as possibly a prefix when a drain was cut.
**Implements**: FR16, FR17, FR19, NFR-P1, NFR-O3, UX5 of fix-terminal-receipt-convergence
**Notes**: A descendant that inherited the pipe keeps it open after the child exited; the JDK's reaper cannot close it while a drain is parked on it (JDK-8169565), so the supervisor closes it.
**Notes**: Output the descendant writes after the bound is dropped; the JDK drops it already whenever the drain lost the exit race, so the loss becomes bounded and visible rather than new.
**Notes**: The flag says "the stream reached no end-of-file inside the bound", not "bytes were lost": a holder that writes nothing leaves the capture complete, a holder that writes and closes inside the bound contaminates it unflagged. So the refusal is the conservative reading, and a parser of a value asserts its own frame as the second layer (an object id's length and alphabet, a NUL-framed record, a JSON document).

#### Scenario: A clean exit with a held pipe returns within the bound
- **WHEN** a supervised invocation exits normally while a descendant still holds its stdout open
- **THEN** the invocation returns within the post-exit bound plus a small margin, reporting the
  exit code and the output read until then, with the descendant still running

#### Scenario: A clean exit with no holder pays nothing
- **WHEN** a supervised invocation exits normally and nothing else holds its pipes
- **THEN** the drains end at end-of-file and the invocation returns at once, with the capture
  complete and not flagged

#### Scenario: Closing the pipe releases the parked drain
- **WHEN** a drain is parked reading a pipe a descendant holds and the post-exit bound expires
- **THEN** the supervisor's close ends the drain's read within milliseconds and the process's
  exit handling completes, so no thread of the factory waits on the descendant

#### Scenario: Every reader waits with the one bound
- **WHEN** any factory component reads a child's stdout or stderr — git plumbing, docker
  management, a file channel into a box, a self-check probe, an agent round, a check command
- **THEN** it drains through the one drain of the supervision module, joined only with a bound
  and interruptibly — a reader outside the module taking the bound from the handle it holds, never
  from a constant of its own — and the build fails on a second drain implementation or an
  unbounded join

### Requirement: A cut capture is refused as a value
An invocation that exited on its own with code zero and whose drain the post-exit bound cut SHALL fail with a typed refusal carrying the capture — the prefix, stderr, the stream and the bound — raised by one owner of the supervision module, so that no reader consumes the prefix as a value; an invocation that exited non-zero, timed out or was interrupted SHALL keep its own failure with its partial output readable.
**Implements**: FR17, NFR-O3 of fix-terminal-receipt-convergence
**Notes**: The exit error wins (Go's `os/exec` precedence): a non-zero exit, a timeout or an interruption is already a failure and keeps its partial output readable for diagnostics; the cut is named in that failure's detail, never raised beside it. Amended 2026-10-17: the former "the caller never fails an otherwise successful command" fits diagnostic output only; applied to the runners it let the flag die at the first reader.
**Notes**: A reader of the exit code alone is failed too, deliberately: the runner cannot tell it from a parser, and an external effect is recovered through its receipt, never through the prefix.

#### Scenario: A cut capture says so
- **WHEN** a drain is still open when the post-exit bound expires after the child exited on its
  own with code zero
- **THEN** the runner logs the holder once with its catalog code, the command and the bound, and
  the invocation fails with a typed refusal that carries the prefix read so far, the stderr, which
  stream was cut and the bound — the exit code and the termination keep their meaning inside it,
  and no reader of the invocation, of the exit code alone included, receives the prefix as a
  value

#### Scenario: A failed or killed invocation keeps its partial output
- **WHEN** a drain is still open when the post-exit bound expires after a non-zero exit, a timeout
  or an interruption
- **THEN** the invocation's own failure stands, its partial stdout and stderr are readable as
  diagnostics, and the cut is named in that failure's detail without a second finding

#### Scenario: A value reader asserts its frame
- **WHEN** a reader turns a capture into an object id
- **THEN** a string that is not 40 or 64 hexadecimal characters is refused, so a prefix a holder's
  writes shortened or lengthened fails on its own, whether or not the drain was flagged

### Requirement: One launch owner
Every supervised process SHALL be started through one launch owner of the supervision module, a role interface over the process builder's start, so that a spec can hand the supervisor a fake process whose exit and pipes it controls, and the build SHALL fail on a process started anywhere else.
**Implements**: FR18, NFR-R4, G9 of fix-terminal-receipt-convergence
**Notes**: The order of a drain's first read and the child's exit is a scheduling fact a loaded runner decides the other way; a spec that states it as a premise is the failure this exists for.

#### Scenario: Only the launch owner starts a process
- **WHEN** the architecture gate scans the production sources
- **THEN** a process builder is started only in the launch owner's files, and the gate asserts it
  reached every allowlisted file

#### Scenario: A spec controls the exit and the pipes
- **WHEN** a spec of the supervisor drives it through a fake process
- **THEN** the spec releases the exit and closes or holds each pipe by its own latches, so the
  outcome it asserts does not depend on which thread the scheduler ran first

### Requirement: A child's input is a file or nothing
The launch owner SHALL set every child's standard input from the source the caller passes: `/dev/null` when there is no input, otherwise a file created atomically with owner-only permissions in the input directory the launch owner is given (the JVM's temp directory by default), written in full before the start and deleted right after it; no production code outside the owner SHALL open a child's stdin stream or set an input redirect.
**Implements**: FR23, FR18, G8 of fix-terminal-receipt-convergence
**Notes**: A pump into a child's stdin cannot be bounded on the JDK: a write parked on a full pipe holds the stream's monitor, so a close from another thread blocks on it, an interrupt does nothing, and the reaper blocks behind it until the holder dies. The only robust shape is no pump — the shape git, systemd and the CI runners use.
**Notes**: The child and every descendant read the file through the descriptor they inherited; deleting it after the start takes nothing from them, and the content is on disk by name only for the length of the launch.

#### Scenario: A child with no input reads end-of-file at once
- **WHEN** a supervised process is launched with no input
- **THEN** its standard input is `/dev/null`, a read of it returns end-of-file, and no pipe
  exists for a descendant to inherit

#### Scenario: Input reaches the child from a file the owner deletes
- **WHEN** a supervised process is launched with input larger than a pipe buffer
- **THEN** the child reads the whole input, the file is gone from the directory once the start
  returned, and the launch failed before any process started if the file could not be written

#### Scenario: A held stdin stalls no thread of the factory
- **WHEN** a child exits while a descendant still holds the child's standard input open and
  never reads it
- **THEN** the invocation returns at the exit with no thread of the factory left waiting on the
  input, and the descendant is the sweep's to end

#### Scenario: Only the launch owner touches a child's stdin
- **WHEN** the architecture gate scans the production sources
- **THEN** a child's stdin stream is opened and an input redirect is set in the launch owner's
  file only, and no stdin pump class remains

### Requirement: Launched processes form their own process group
The launch owner SHALL start a supervised process as the leader of a new process group whose id is the child's pid, and after the exit the supervisor SHALL signal the whole group until a zero-signal probe of the group is refused; the owner SHALL have one production form, and nothing inside a box SHALL be swept by the host; at the factory's exit, for every command, the termination path SHALL sweep every group no waiter has swept yet.
**Implements**: FR21, FR22, G8 of fix-terminal-receipt-convergence
**Notes**: Amended 2026-10-17: with a launcher in front, `execvp`'s `ENOENT` no longer reaches the parent as an exception — the launcher starts and its exec fails with 127 — so the four sites that classify a thrown launch failure (git not found, docker unavailable, exec not started, plumbing not launched) would go quiet, and a missing git would read as "ref does not exist". Resolving first is Go's `exec.LookPath` before `Start`; refusing the working directory is its `ErrDot`.
**Notes**: The 126/127 convention is POSIX's for non-shell launchers (util-linux `setsid` exits 127 on `ENOENT`, 126 otherwise, with `failed to execute` on stderr), but the codes recur one layer down — `docker run` returns them for the contained command, any program may exit 127 — so the code is never the classifier alone.
**Notes**: The group kill is best-effort hygiene, not a fence (Bazel's process-wrapper author: "not a security mechanism"): a member that changed its uid or called `setsid` itself is beyond it; `setsid --wait` is never used (it reports the raw wait status, not `128+n`), and `--fork` is never needed (the JVM's child is not a group leader, so the launcher execs in place and the pid is the program's).
**Notes**: A descendant reparented away from the child dies with the invocation this way. Every process the owner starts is a host process — the `docker` client of a box command included — so there is no container form; the box's PID namespace ends with the box.
**Notes**: The descendant snapshot of the cooperative kill cannot see a reparented orphan; a process group is the only portable identity that still names it (Linux `setsid`, macOS through its own Perl).
**Notes**: A writing descendant that lost its pipe exits on the broken pipe first; the sweep is for the silent ones.
**Notes**: The end of the sweep is judged by the probe, never by the text of the `kill` binary's error, which differs between platforms while the exit code does not; a member that changed its uid is beyond the sweep and is the sweep's named limit.

#### Scenario: A reparented orphan dies with the invocation
- **WHEN** a supervised host invocation's child spawned a process that outlived the child, so the
  descendant snapshot no longer lists it
- **THEN** after the invocation returns, the sweep has killed it, and the invocation's result is
  unchanged by the sweep

#### Scenario: The sweep repeats until nothing answers
- **WHEN** a process of the group forks between two rounds of the sweep
- **THEN** the next round kills it too, and the sweep ends within a fixed number of rounds once
  the zero-signal probe of the group is refused

#### Scenario: A missing launcher fails the invocation
- **WHEN** the platform's launcher helper is not present on the host
- **THEN** the invocation fails before any process starts, naming the helper, and never runs
  ungrouped

#### Scenario: A child in its own session is ended by the factory
- **WHEN** the operator interrupts a foreground `gnomish run` or `gnomish take` from the terminal
- **THEN** the child, which has no controlling terminal, receives nothing from the terminal; the
  factory's shutdown step kills it and sweeps its group before the process exits

#### Scenario: A daemon's stop sweeps the groups no waiter has swept
- **WHEN** `serve` stops while an invocation left an orphan the descendant snapshot misses and its
  waiter has not swept the group yet
- **THEN** the shutdown step sweeps that group before the daemon exits

#### Scenario: The docker client of a box command is a host process
- **WHEN** a command runs inside a box through the `docker` client the factory starts
- **THEN** that client is launched by the one grouped form and its group is swept like any other
  host invocation, nothing inside the box is signalled by the host, and the build fails on a
  second implementation of the launch owner in production sources

### Requirement: The launch owner resolves the executable before the launcher
The owner SHALL resolve the command's executable and its `#!` interpreter chain as the kernel would before prefixing the launcher, failing the launch when any hop is missing; the launcher SHALL write only its refusal line, only on a failed exec; a whole-capture waiter SHALL classify a 126 or 127 exit carrying that line as a launch failure, a streaming waiter SHALL carry it in its diagnostics; the sweep SHALL run only over a started process.
**Implements**: FR21 of fix-terminal-receipt-convergence
**Notes**: Amended 2026-10-17: a missing binary keeps the thrown classification it had without the launcher (git not found, docker unavailable, exec not started, plumbing not launched), which the launcher's exit code would otherwise silence. Amended again the same day (design D19, second amendment): the interpreter chain is resolved too, since a script whose interpreter is absent threw before the launcher existed; the residue the resolver cannot see is judged by the launcher's own line, which a streaming waiter holds only in its stderr file or merged output — so the promise names the two waiter shapes rather than every invocation.

#### Scenario: A missing binary fails the launch before any process starts
- **WHEN** the command's executable cannot be resolved — a path that does not exist or is not
  executable, or a bare name found nowhere on the child's `PATH`
- **THEN** the launch fails with the same thrown failure it raised before the launcher existed,
  naming the binary, no process is started, and a bare name is never resolved against the
  working directory

#### Scenario: A script whose interpreter is missing fails the launch before any process starts
- **WHEN** the command's executable resolves to a file that opens with `#!` naming an interpreter
  that does not exist or is not executable, at any hop of the chain
- **THEN** the launch fails with the same thrown failure a missing binary raises, naming the
  command, and no process is started; an interpreter that exists — `env` among them — is not
  judged further, and what it does next is the command's own result

#### Scenario: A launcher that could not exec is a launch failure where stderr is captured whole
- **WHEN** the launcher started but could not exec the resolved command, exiting 126 or 127 with
  its own refusal line on stderr, and the waiter captures the invocation's stderr whole
- **THEN** the invocation is classified as a launch failure, not as the command's own result; a
  program that itself exits 126 or 127 without the launcher's line is a command result as before

#### Scenario: A launcher's refusal reaches a streaming waiter in its diagnostics
- **WHEN** the launcher started but could not exec the resolved command, and the waiter streams
  the command's output with its stderr in a file or merged into that output
- **THEN** the invocation ends in the failure class an absent result gives it, and the launcher's
  refusal line is inside the stderr tail or the output tail that failure carries, so an operator
  reads why the command never ran

#### Scenario: The launcher writes nothing on a successful exec
- **WHEN** the launcher execs the resolved command
- **THEN** nothing of the launcher's own reaches the command's stderr: the refusal line is the only
  text the launcher can ever write, and it is written only when the exec failed

#### Scenario: A failed launch is never swept
- **WHEN** the launch threw before a process existed
- **THEN** no signal is sent to any group: the sweep runs only over a process that was started,
  with its pid asserted above one

## MODIFIED Requirements

Keeps the dependency rule of "The supervision module is dependency-free" as it stands in
`openspec/specs/` and restates the split of duties it names: D16, D19 and D20 move the launch,
the group sweep and the medium of a child's standard input into the module, so "the module owns
only wait, kill, drain, and outcome" no longer holds. The callers keep the bytes they give a
child and every policy over what they read back. The scenario is unchanged.

### Requirement: The supervision module is dependency-free
The module carrying the supervision mechanics SHALL depend on nothing — no other module of the
factory, no framework, no logging library, no domain types — so that domain-independent modules
can use it without acquiring a dependency on the factory. Callers own their logging, the bytes
they give a child as input, and their output policy (captures, caps, scrubbing); the module owns
the launch, how the input reaches the child (a file or nothing), wait, kill, drain, the group
sweep, and outcome.
**Implements**: FR9, NFR-S3 of bound-subprocess-commands; FR18, FR21, FR23 of fix-terminal-receipt-convergence

#### Scenario: The build proves the module stands alone
- **WHEN** the build's dependency gates run
- **THEN** the supervision module resolves no internal or external implementation dependency

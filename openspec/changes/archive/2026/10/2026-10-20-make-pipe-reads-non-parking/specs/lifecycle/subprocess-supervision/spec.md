# subprocess-supervision — delta for make-pipe-reads-non-parking

Layered on the main spec as `fix-terminal-receipt-convergence` (archived 2026-10-18) left it. The
REMOVED block names the requirement of that change; the ADDED block re-states it under a new
header, because a scenario cannot leave a requirement through MODIFIED (`delta-specs.md`) and the
scenario "Closing the pipe releases the parked drain" promises what the code cannot keep on
Linux (proposal, Why). `add-subprocess-access-log` modifies "Supervised subprocess invocations"
only, so the two deltas do not overlap. The round's own promise ("No reader outlives the round")
lives in `executor/agent-executor` and is modified by this change's delta of that capability, not
re-stated here.

## REMOVED Requirements

### Requirement: Post-exit output is bounded
**Reason**: Its mechanism — "then close its own ends of the pipes" — rests on the JDK fact that a
close wakes a parked pipe read, which holds on macOS and not on Linux (`close(2)`: a blocking
read keeps the open file description). The scenario "Closing the pipe releases the parked drain"
is therefore a promise no code on Linux can keep, and is retired with the requirement by this
decision review (`spec-mismatch.md`), not narrowed.
**Migration**: The behaviour the surviving scenarios promise — a bounded return, a complete
capture when nothing holds the pipe, one bound for every reader — is restated under "Post-exit
output is bounded without a parked read", with the same scenario titles so every marker resolves
unchanged. The retired scenario's two specs are rewritten to the new scenarios "The drain ends on
its own after the exit" and "An escaped holder pins no thread".

## ADDED Requirements

### Requirement: A drain never parks
A drain of a child's output SHALL read only the bytes the pipe reports as available, wait a tick on its own thread when nothing is available, and end on its own once the child has exited and nothing is pending; no thread of the factory SHALL park in a blocking read of a child's pipe, and the factory SHALL close no pipe of a child.
**Implements**: FR2, FR3, NFR-P1, NFR-P2, NFR-R1, NFR-R3 of make-pipe-reads-non-parking
**Notes**: A descendant that inherited the pipe keeps it open after the child exited; since no factory thread holds the stream's monitor, the JDK's reaper drains what is pending and closes the pipe itself at the exit (JDK-8169565 cannot occur), and a close by the factory would do nothing on Linux anyway.
**Notes**: The stop rule is the one the JDK's reaper applies and the one Go's `WaitDelay`, libuv and Python's `communicate()` apply: never block in a read of a pipe you may have to abandon.
**Notes**: Output the descendant writes after the exit is dropped; the JDK's reaper drops it at the exit already, so the loss is the platform's and is not flagged: the factory cannot tell a descendant's post-exit writes from none, and the child's own output is complete by construction.

#### Scenario: The drain ends on its own after the exit
- **WHEN** a child exits while a descendant still holds its stdout open and has written nothing
  since the last read
- **THEN** the drain ends as soon as it sees the exit, with no tick awaited after it, without any close by the
  factory, the process's exit handling completes, every byte the child wrote before the exit is
  in the capture, and the capture is not flagged

#### Scenario: No read is issued for bytes that are not there
- **WHEN** a supervised invocation's child is alive and its pipe reports nothing available
- **THEN** the drain's thread waits on its own tick, never inside the stream's read, so a stop
  signal — the bound's or a round's close — ends the drain within one tick on every platform,
  whether the reader takes the bytes as a push drain or pulls them through the owner's stream
  view

#### Scenario: A line arrives within a tick
- **WHEN** a child writes a line of output while running
- **THEN** a listener reading the stream live receives the line no later than one tick after the
  write, and an idle stream costs the reader one availability query per tick

### Requirement: Post-exit output is bounded without a parked read
After a supervised invocation's exit — normal, expired or interrupted — the supervisor SHALL wait for each output drain at most one post-exit bound, derived from the kill grace plus a fixed margin, stop a drain still open at the bound by a signal the drain observes within a tick, and SHALL return the capture read until then, flagged as possibly a prefix when a drain was stopped.
**Implements**: FR4, FR9, NFR-R2 of make-pipe-reads-non-parking; FR16, FR17, FR19, NFR-P1, NFR-O3, UX5 of fix-terminal-receipt-convergence
**Notes**: The flag says "the drain had not ended when the bound expired", not "bytes were lost": a holder that writes nothing leaves the capture complete, a holder that writes and closes inside the bound contaminates it unflagged, so the refusal is the conservative reading, and a parser of a value asserts its own frame as the second layer (an object id's length and alphabet, a NUL-framed record, a JSON document).
**Notes**: With a drain that never parks, the bound is reached only when bytes keep arriving every tick after the exit; it stays as the safety net the promise names, not as the mechanism the drain's end relies on.
**Notes**: On a real process the JDK's reaper closes the pipe at the exit, so the bound and the cut WARN are reached only on the safety net — a holder writing faster than the reaper's own drain at the exit; a pipe holder's attribution on a real process is the group sweep's DEBUG survivor line, and a holder that left the group is invisible to the factory (NG1 of make-pipe-reads-non-parking). Measured 2026-10-18 on macOS (JDK 25.0.3) and Linux (JDK 25.0.4.1): a writing holder, in the group or escaped, returned in 4–35 ms with the capture not flagged.

#### Scenario: A clean exit with a held pipe returns within the bound
- **WHEN** a supervised invocation exits normally while a descendant still holds its stdout open
- **THEN** the invocation returns within the post-exit bound plus a small margin, reporting the
  exit code and the output read until then, with the descendant still running

#### Scenario: A clean exit with no holder pays nothing
- **WHEN** a supervised invocation exits normally and nothing else holds its pipes
- **THEN** the drains end at end-of-file and the invocation returns at once, with the capture
  complete and not flagged

#### Scenario: An escaped holder pins no thread
- **WHEN** a child exits with code zero while a descendant that left the child's process group
  keeps writing to the inherited stdout
- **THEN** the invocation returns at once, well inside the post-exit bound, with every byte the
  child wrote before its exit in the capture and the capture not flagged, the exit handling of
  the process completes, no thread of the factory is left on the pipe, and the descendant is still
  running, on Linux as on macOS

#### Scenario: Every reader waits with the one bound
- **WHEN** any factory component reads a child's stdout or stderr — git plumbing, docker
  management, a file channel into a box, a self-check probe, an agent round, a check command
- **THEN** it drains through the one drain of the supervision module, joined only with a bound
  and interruptibly — a reader outside the module taking the bound from the handle it holds, never
  from a constant of its own — and the build fails on a second drain implementation or an
  unbounded join

### Requirement: One owner of a child's readable pipe
The readable side of a child's output pipe SHALL be a typed value of the supervision module, minted only from a started process, and a drain SHALL be started only from that value; no production source outside the owner SHALL obtain a child's output stream or read from it, and the build SHALL fail on one that does.
**Implements**: FR1, FR5, FR8 of make-pipe-reads-non-parking

#### Scenario: A drain cannot be started on a bare stream
- **WHEN** a reader of the factory starts a drain
- **THEN** it hands the drain the owner's typed value, which carries the process the pipe belongs
  to, so the drain's stop rule is wired by construction and a bare stream does not compile

#### Scenario: Only the owner touches a child's output stream
- **WHEN** the architecture gate scans the production sources
- **THEN** a child's output or error stream is obtained and read in the owner's file only, every
  listed reader is reached, and a reader that reads a child's stream itself fails the build

## MODIFIED Requirements

### Requirement: One launch owner
Every supervised process SHALL be started through one launch owner of the supervision module, a role interface over the process builder's start, so that a spec can hand the supervisor a fake process whose exit and pipes it controls, and the build SHALL fail on a process started anywhere else.
**Implements**: FR18, NFR-R4, G9 of fix-terminal-receipt-convergence; FR7 of make-pipe-reads-non-parking
**Notes**: The order of a drain's first read and the child's exit is a scheduling fact a loaded runner decides the other way; a spec that states it as a premise is the failure this exists for.
**Notes**: The fake's pipes behave as a Linux pipe, the stricter platform: a double whose close woke its reader let every spec on it pass on Linux while the real pipe left the reader parked (proposal, Why).

#### Scenario: Only the launch owner starts a process
- **WHEN** the architecture gate scans the production sources
- **THEN** a process builder is started only in the launch owner's files, and the gate asserts it
  reached every allowlisted file

#### Scenario: A spec controls the exit and the pipes
- **WHEN** a spec of the supervisor drives it through a fake process
- **THEN** the spec releases the exit and closes or holds each pipe by its own latches, so the
  outcome it asserts does not depend on which thread the scheduler ran first

#### Scenario: The fake process behaves as a Linux pipe
- **WHEN** a spec holds the fake process's pipe open past the exit and a reader is parked in a
  blocking read of it
- **THEN** closing the stream releases that reader no more than Linux would, the pipe reports its
  unread byte count exactly, and a reader that parks is caught by the spec on every platform

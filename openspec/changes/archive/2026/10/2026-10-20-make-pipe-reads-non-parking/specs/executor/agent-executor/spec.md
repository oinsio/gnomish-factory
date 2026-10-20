# agent-executor — delta for make-pipe-reads-non-parking

Layered on the main spec as `fix-terminal-receipt-convergence` (archived 2026-10-18) left it. No
other active change modifies the requirement below. The round's drain is one of the six readers
this change moves onto the owner of a child's readable pipe (`lifecycle/subprocess-supervision`,
"One owner of a child's readable pipe"); the promise "the stdout stream is closed" rested on the
round closing the pipe itself, which design D3 of this change retires — the pipe is the JDK's to
close at the exit, and the round's close stops the drain instead.

## MODIFIED Requirements

### Requirement: The round drains its stdout from launch within the handle's post-exit bound
Both CLI round executions (executor and judge) SHALL consume and parse the process's stream-json stdout concurrently with the running process, starting immediately after launch — never deferring the read until after process exit — through one shared drain; after the exit the round SHALL wait for that drain at most the post-exit bound of the round's handle, never a bound of its own.
**Implements**: FR1, FR2, FR3, FR6, FR7, NFR-R1, NFR-R2, NFR-P1 of fix-round-stdout-drain; FR19 of fix-terminal-receipt-convergence; FR6, NFR-R2 of make-pipe-reads-non-parking
**Notes**: A `roundTimeout` kill ends the process; the JDK's reaper closes the pipe at the exit and the drain ends on its own, so the end of the stream is the drain's expected end signal there, not an error.
**Notes**: The bound replaces the retired installation property `factory.agent-cli-tail-drain-grace`; after the exit only one pipe buffer remains to read.
**Notes**: The round closes no pipe: its close is the owner's stop (`lifecycle/subprocess-supervision`, "A drain never parks"), observed by the drain within a tick on every platform; the pipe is the JDK's to close at the exit (ADR 0018).

#### Scenario: Megabyte stream keeps its result event
- **WHEN** a round's CLI emits over 1 MB of stream-json noise followed by a valid result event and exits
- **THEN** the round completes normally with the result event, usage, and trace extracted

#### Scenario: Chatty synchronous writer does not hang
- **WHEN** the CLI writes more than the pipe buffer synchronously before exiting
- **THEN** the process is never blocked on a full pipe and the round completes well within `roundTimeout`

#### Scenario: Timeout kill still classifies as infrastructure
- **WHEN** the process outlives `roundTimeout` and is killed mid-stream
- **THEN** the drain ends without error and the round is an infrastructure failure, exactly as before this change

#### Scenario: An interrupted wait is not blamed on the bound
- **WHEN** the round thread is interrupted while waiting for its drain to finish
- **THEN** the round is an infrastructure failure of its own kind — reported as an interruption, never as an expired post-exit bound

#### Scenario: No reader outlives the round
- **WHEN** a round ends by any path — normal exit, timeout kill, or adapter exception
- **THEN** the drain thread has ended and no read of the stream is pending; the round closed no
  pipe, since the pipe is the JDK's to close at the exit

#### Scenario: The round's close leaves no drain thread
- **WHEN** an agent round ends while a descendant of the agent process still holds its stdout
- **THEN** the round's close returns at once, the drain thread ends within one tick, and no thread
  is left parked on the pipe, on Linux as on macOS

#### Scenario: The round waits for its drain after exit
- **WHEN** the process exits while the tail of its stream is still in the pipe
- **THEN** the round completes only after the drain has consumed that tail, waiting at most the post-exit bound of the round's handle

#### Scenario: A drain unfinished after the post-exit bound is infrastructure
- **WHEN** the drain has not finished when the post-exit bound of the round's handle runs out
- **THEN** the round is an infrastructure failure, never a round completed on a partial stream

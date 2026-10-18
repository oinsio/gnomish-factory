# Spec Delta: executor/agent-executor

## ADDED Requirements

Added 2026-10-16 (design D18): the round's stdout is drained live by "Stdout is drained
continuously from launch"; its stderr was a pipe nothing read.

### Requirement: The round's stderr is kept
The agent round SHALL direct the CLI process's stderr to a file in the round scratch directory — a host directory the round owns, on the host as for a box round — never to a pipe nothing reads, and SHALL read that file after the exit into the round's diagnostics as untrusted text; the round SHALL remove the scratch directory on every exit path.
**Implements**: FR20, NG12 of fix-terminal-receipt-convergence
**Notes**: The stdout stays a live pipe because the stream-json events are parsed per line while the process runs; a file has no live tail.
**Notes**: The decision file is not the neighbour: in container mode it lives in the task branch, which is the envelope and no place for a host diagnostic; the scratch directory is the one host place a round writes outside the branch.

#### Scenario: A chatty stderr cannot block the round
- **WHEN** the CLI writes more than an OS pipe buffer to its stderr during a round
- **THEN** the round completes on the CLI's exit, and the diagnostics carry the tail of that
  stderr

#### Scenario: The stderr file leaves with the round
- **WHEN** a round ends, however it ended — on the host or for a box
- **THEN** no file of the round remains on the host: the round scratch directory and the stderr
  file in it are removed by the round itself

#### Scenario: A failed round carries its stderr tail
- **WHEN** a round ends without success — the CLI failed to start, exited without a result event,
  timed out, or was interrupted
- **THEN** the failure's detail carries the tail of the CLI's stderr as untrusted text, and a
  successful round logs that tail at DEBUG only

Added 2026-10-17 (design D17 as amended): the round's post-exit wait takes the bound of its
handle; it replaces "Stdout is drained continuously from launch", removed below with the
installation property it named.

### Requirement: The round drains its stdout from launch within the handle's post-exit bound
Both CLI round executions (executor and judge) SHALL consume and parse the process's stream-json stdout concurrently with the running process, starting immediately after launch — never deferring the read until after process exit — through one shared drain; after the exit the round SHALL wait for that drain at most the post-exit bound of the round's handle, never a bound of its own.
**Implements**: FR1, FR2, FR3, FR6, FR7, NFR-R1, NFR-R2, NFR-P1 of fix-round-stdout-drain; FR19 of fix-terminal-receipt-convergence
**Notes**: A `roundTimeout` kill closes the stream, so EOF is the drain's expected end signal there, not an error.
**Notes**: The bound replaces the retired installation property `factory.agent-cli-tail-drain-grace`; after the exit only one pipe buffer remains to read.

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
- **THEN** the drain thread is finished or terminated and the stdout stream is closed

#### Scenario: The round waits for its drain after exit
- **WHEN** the process exits while the tail of its stream is still in the pipe
- **THEN** the round completes only after the drain has consumed that tail, waiting at most the post-exit bound of the round's handle

#### Scenario: A drain unfinished after the post-exit bound is infrastructure
- **WHEN** the drain has not finished when the post-exit bound of the round's handle runs out
- **THEN** the round is an infrastructure failure, never a round completed on a partial stream

## REMOVED Requirements

### Requirement: Stdout is drained continuously from launch
**Reason**: the round's post-exit wait takes the post-exit bound of its handle (FR19, design D17 as amended 2026-10-17), and the installation property `factory.agent-cli-tail-drain-grace` it named is retired; the surviving behavior moves to "The round drains its stdout from launch within the handle's post-exit bound".
**Migration**: an installation may delete `factory.agent-cli-tail-drain-grace` from its configuration; the key no longer has any effect.

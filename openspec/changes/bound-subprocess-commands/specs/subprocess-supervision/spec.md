## ADDED Requirements

### Requirement: Supervised subprocess invocations
The factory SHALL supervise its subprocess invocations through one shared mechanism: output
streams drained concurrently with the running process, an optional hard deadline on the wait, and
on expiry or interruption a two-phase termination of the whole process tree — a cooperative
terminate, a short kill grace so the child can remove its lock and temporary files, then a forced
kill of the re-snapshotted tree — followed by reaping, so no process or descendant survives its
invocation. Drain completion SHALL be awaited within a bound on the kill path, so a descendant
holding an inherited pipe open cannot block the return. The outcome SHALL name how the invocation
ended — ran to exit, expired on its deadline, or was interrupted — distinctly from the exit code,
carrying whatever output was captured.
<!-- implements FR2, FR3, FR6, FR9, NFR-R1, NFR-R2, G5 of bound-subprocess-commands -->

#### Scenario: A stalled child cannot outlive its deadline
- **WHEN** a supervised invocation with a deadline produces no exit while holding its output open
- **THEN** the invocation returns within the deadline plus the kill grace and a small margin,
  reporting a timed-out outcome with the output captured so far

#### Scenario: The whole tree dies with the invocation
- **WHEN** a supervised invocation is terminated on expiry or interruption and its child had
  spawned processes of its own
- **THEN** neither the child nor any of its descendants remains running

#### Scenario: Cooperative termination is attempted first
- **WHEN** a supervised invocation is terminated and the child exits promptly on the cooperative
  signal
- **THEN** the child is not force-killed, and the outcome still reports how the invocation ended

#### Scenario: Full pipes never deadlock the wait
- **WHEN** a supervised invocation writes more than an OS pipe buffer to both streams
- **THEN** the invocation completes normally with both streams captured in full

### Requirement: The supervision module is dependency-free
The module carrying the supervision mechanics SHALL depend on nothing — no other module of the
factory, no framework, no logging library, no domain types — so that domain-independent modules
can use it without acquiring a dependency on the factory. Callers own their logging and their
input/output policy (captures, caps, stdin, scrubbing); the module owns only wait, kill, drain,
and outcome.
<!-- implements FR9, NFR-S3 of bound-subprocess-commands -->

#### Scenario: The build proves the module stands alone
- **WHEN** the build's dependency gates run
- **THEN** the supervision module resolves no internal or external implementation dependency

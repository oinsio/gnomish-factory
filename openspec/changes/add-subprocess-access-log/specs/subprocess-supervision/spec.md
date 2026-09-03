# subprocess-supervision — delta for add-subprocess-access-log

## MODIFIED Requirements

### Requirement: Supervised subprocess invocations
The factory SHALL supervise its subprocess invocations through one shared mechanism: output
streams drained concurrently with the running process, an optional hard deadline on the wait, and
on expiry or interruption a two-phase termination of the whole process tree — a cooperative
terminate, a short kill grace so the child can remove its lock and temporary files, then a forced
kill of the re-snapshotted tree — followed by reaping, so no process or descendant survives its
invocation. Drain completion SHALL be awaited within a bound on the kill path, so a descendant
holding an inherited pipe open cannot block the return. The outcome SHALL name how the invocation
ended — ran to exit, expired on its deadline, or was interrupted — distinctly from the exit code,
carrying whatever output was captured. The deadline stays optional in the mechanism, but not
silently at the call sites: a factory wait that passes no deadline SHALL carry a written
justification in place; the container file channel's executions, the environment self-check
probes, and in-box service git commands SHALL pass deadlines whose expiry follows each seam's
standard timeout handling; and an operator-configured command bound SHALL reach every
construction site that takes one — the docker runtime probe included, which SHALL NOT fall back
to a default while the operator has configured a bound.
<!-- implements FR2, FR3, FR6, FR9, NFR-R1, NFR-R2, G5 of bound-subprocess-commands -->
<!-- implements NFR-R2 of add-subprocess-access-log -->

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

#### Scenario: A wedged file-channel execution cannot hang the take
- **WHEN** a container file-channel write or read produces no exit within the configured
  docker-command bound
- **THEN** the wait expires, the channel reports its standard failure for the operation, and the
  take proceeds through its normal failure handling instead of blocking forever

#### Scenario: A wedged self-check probe cannot block every round
- **WHEN** an environment self-check probe produces no exit within its bound
- **THEN** the probe wait expires and the self-check reports its failure through its existing
  classification, instead of holding the round open indefinitely

#### Scenario: A deliberately unbounded wait says so in place
- **WHEN** a factory call site passes no deadline to the supervised wait
- **THEN** a written justification stands at that call site, and review can enumerate every such
  site by searching for it

## ADDED Requirements

### Requirement: Factory client subprocesses start from a composed environment
The factory's own client subprocesses — the git and docker command-line clients — SHALL start
from a cleared environment to which the factory re-adds only the variables each client needs
(documented in place at the composition site), instead of inheriting the factory process's full
environment; no credential foreign to the client's task (tracker tokens, AI tokens, check
credentials) SHALL be present in a client child's environment unless that client needs it.
<!-- implements FR15 of add-subprocess-access-log -->

#### Scenario: The docker client carries no tracker credential
- **WHEN** the factory runs any docker command while holding tracker, AI, and check credentials
  in its own environment
- **THEN** the docker client child's environment contains the composed set only, and the tracker
  and check credentials are absent

#### Scenario: The git client carries no AI credential
- **WHEN** the factory runs a git command while holding the AI auth token in its own environment
- **THEN** the git client child's environment contains only git's documented needs
  (credential-helper resolution included), and the AI token is absent

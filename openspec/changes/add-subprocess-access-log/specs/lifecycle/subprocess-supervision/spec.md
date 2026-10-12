# subprocess-supervision — delta for add-subprocess-access-log

## MODIFIED Requirements

Layered on `Supervised subprocess invocations` as modified by `tighten-spec-contract` (sequenced
before this change): that delta cut the body to the shared mechanism and moved the two-phase
termination, the drain bound and the outcome naming into the scenarios "A child ignoring the
terminate is killed after the grace", "An inherited pipe cannot block the return" and "The
outcome names how the invocation ended"; this text adds the justified-unbounded-wait rule to the
body and the deadline scenarios at the call sites — "A wedged file-channel execution cannot hang
the take", "A wedged self-check probe cannot block every round", "A deliberately unbounded wait
says so in place", "In-box service git commands pass a deadline" and "The operator's command
bound reaches every construction site" — and both must survive the later sync.

### Requirement: Supervised subprocess invocations
The factory SHALL supervise its subprocess invocations through one shared mechanism: output streams
drained concurrently with the running process, an optional hard deadline on the wait, and on expiry
or interruption a termination of the whole process tree followed by reaping, so no process or
descendant survives its invocation. A factory wait that passes no deadline SHALL carry a written
justification in place.
**Implements**: FR2, FR3, FR6, FR9, NFR-R1, NFR-R2, G5 of bound-subprocess-commands; NFR-R2 of add-subprocess-access-log
**Notes**: The short kill grace between the cooperative terminate and the forced kill lets the child remove its lock and temporary files.

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

#### Scenario: A child ignoring the terminate is killed after the grace
- **WHEN** a supervised invocation is terminated and the child does not exit on the cooperative
  signal
- **THEN** after a short kill grace the re-snapshotted process tree is force-killed and reaped

#### Scenario: An inherited pipe cannot block the return
- **WHEN** a descendant still holds an inherited output pipe open when the invocation is killed
- **THEN** drain completion is awaited only within a bound, and the invocation returns

#### Scenario: The outcome names how the invocation ended
- **WHEN** a supervised invocation ends
- **THEN** its outcome names whether it ran to exit, expired on its deadline, or was interrupted,
  distinctly from the exit code, and carries whatever output was captured

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

#### Scenario: In-box service git commands pass a deadline
- **WHEN** the factory runs a service git command inside a box and it produces no exit
- **THEN** the wait expires on a deadline and the expiry follows that seam's standard timeout
  handling

#### Scenario: The operator's command bound reaches every construction site
- **WHEN** the operator configures a command bound
- **THEN** every construction site that takes one receives it, the docker runtime probe
  included, and none falls back to a default while the bound is configured

## ADDED Requirements

### Requirement: Factory client subprocesses start from a composed environment
The factory's own client subprocesses — the git and docker command-line clients — SHALL start
from a cleared environment to which the factory re-adds only the variables each client needs
(documented in place at the composition site), instead of inheriting the factory process's full
environment; no credential foreign to the client's task (tracker tokens, AI tokens, check
credentials) SHALL be present in a client child's environment unless that client needs it.
**Implements**: FR15 of add-subprocess-access-log

#### Scenario: The docker client carries no tracker credential
- **WHEN** the factory runs any docker command while holding tracker, AI, and check credentials
  in its own environment
- **THEN** the docker client child's environment contains the composed set only, and the tracker
  and check credentials are absent

#### Scenario: The git client carries no AI credential
- **WHEN** the factory runs a git command while holding the AI auth token in its own environment
- **THEN** the git client child's environment contains only git's documented needs
  (credential-helper resolution included), and the AI token is absent

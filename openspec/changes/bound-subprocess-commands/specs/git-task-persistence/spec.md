## ADDED Requirements

### Requirement: Bounded git network invocations
Every git invocation that talks to a remote — fetching, pushing, listing remote refs, cloning,
updating a remote — SHALL be bounded: it terminates within a configured deadline plus a small kill
margin no matter what the remote does, including a connection that is accepted and then never
answers. Purely local invocations SHALL remain unbounded. Output SHALL be drained concurrently with
the running process, so a full output pipe can neither deadlock the wait nor hide the deadline. On
expiry the factory SHALL forcibly terminate the invocation and every process it spawned, and report
a timed-out outcome carrying whatever output was captured; no process spawned by the invocation
survives it.
<!-- implements FR1, FR2, FR3, FR5, NFR-R1, NFR-R2 of bound-subprocess-commands -->

#### Scenario: A silent remote does not hang the run
- **WHEN** a push is issued to a remote that accepts the connection and then sends nothing
- **THEN** the invocation ends within the configured deadline, is reported as timed out, and the run
  continues to its next step

#### Scenario: No process outlives the deadline
- **WHEN** an invocation is terminated on deadline expiry
- **THEN** neither it nor any process it spawned remains running

#### Scenario: Local commands are untouched
- **WHEN** a local command such as a commit or a ref read is issued
- **THEN** it is not bounded and its exit code, output, and error text are exactly as before

### Requirement: Stall detection governs a progressing transfer
The factory SHALL enable git's own no-progress detection for the transports it uses — an abort when
throughput stays below a floor for a sustained window over HTTP, and connect plus keepalive limits
over SSH — configured per invocation only. A transfer that keeps making progress SHALL NOT be
terminated by the deadline: the deadline is the backstop for a wedged process, the stall detection
is the primary mechanism for a dead connection. These settings SHALL NOT be written into any git
configuration the operator owns.
<!-- implements FR4, NFR-S1, G3 of bound-subprocess-commands -->

#### Scenario: A slow but progressing transfer completes
- **WHEN** a large fetch over a slow link keeps transferring data past the deadline's nominal window
- **THEN** it is allowed to finish rather than killed

#### Scenario: Operator git configuration is not modified
- **WHEN** any bounded invocation runs
- **THEN** the settings it uses apply to that invocation alone and no file of the operator's git
  configuration is written

### Requirement: Interruption and timeout are named outcomes
An invocation that was interrupted (a shutdown, a revoked claim) and one that expired on its
deadline SHALL each be a distinct outcome, never reported as an ordinary non-zero git exit. A caller
holding a bounded re-attempt SHALL NOT spend it on an interrupted or timed-out invocation. A push
that never ran to a verdict SHALL NOT be reported to the operator as a failed push, and an
interrupted delivery check SHALL report that delivery could not be verified rather than asserting
that the remote is behind. A timed-out push is an unknown remote outcome — the transfer may have
landed even though the local command was killed — so a delivery check SHALL claim the remote is
behind only after a bounded re-check of the remote tip confirms the tip absent; when that re-check
itself cannot answer, the check reports that delivery could not be verified. Captured error text
SHALL be scrubbed of credentials on these paths exactly as on the normal path, including the
partial output of a terminated process.
<!-- implements FR6, FR7, FR8, NFR-O1, NFR-O2, NFR-S2, UX2, UX3 of bound-subprocess-commands -->

#### Scenario: An interrupted delivery check spends no re-attempt
- **WHEN** the park delivery check is interrupted mid-push
- **THEN** no second push is attempted and the park report carries no claim that the remote is behind

#### Scenario: A timed-out push reads as a dead remote
- **WHEN** a best-effort push expires on its deadline
- **THEN** one warning is logged naming the timeout, the elapsed time, and the configured deadline,
  distinct from the warning a rejected push produces, and the round continues

#### Scenario: A timed-out delivery push is re-verified before any claim
- **WHEN** the park delivery push expires on its deadline
- **THEN** no second push is attempted, the remote tip is re-checked once within its own bound, and
  the park report claims the remote is behind only if that check confirms the tip absent — an
  unanswerable check yields "delivery could not be verified" instead

#### Scenario: Partial output of a killed process is still scrubbed
- **WHEN** a terminated invocation captured error text containing remote-URL credentials
- **THEN** the credentials are removed before the text reaches any log or operator-visible report

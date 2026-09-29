## ADDED Requirements

### Requirement: An interrupted call is a cancellation, not an outage
A tracker adapter whose call is interrupted — the calling thread's interrupt
arrives while the call waits on the tracker — SHALL end the call without a
further attempt, SHALL leave the calling thread's interrupt set when it
returns control, and SHALL NOT report the interrupt as a
`TrackerUnavailableException` or any other outage the port's callers retry.
A caller therefore reads "the call failed because this thread was told to
stop" from the thread's own interrupt, whichever adapter is bound.
<!-- implements FR11, NFR-R3 of fix-operator-blockers -->

#### Scenario: Interrupted read leaves the interrupt set
- **WHEN** a thread's interrupt arrives while a `listOpen` call waits on the
  tracker
- **THEN** the call fails, the thread's interrupt is still set when the
  failure reaches the caller, and no second request is sent

#### Scenario: Interrupted write is not a retryable outage
- **WHEN** a thread's interrupt arrives while a tracker write waits on the
  tracker
- **THEN** the failure is not a `TrackerUnavailableException`, so a bounded
  terminal-write retry does not spend its budget on it

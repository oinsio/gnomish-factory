## ADDED Requirements

### Requirement: An interrupt is not a transport failure
The GitHub HTTP client shared by the tracker and the external-check adapters
SHALL treat an interrupt of the calling thread during a request as a
cancellation: it SHALL make no further attempt under its retry policy, SHALL
leave the thread's interrupt set, and SHALL fail with a cancellation that is
distinct from the exhausted-transport failure — so the tracker adapter's
translation of transport failures into a retryable outage never applies to
it, the external-check poll SHALL NOT report it as a cannot-verify outcome
but let it propagate as the stop of the polling thread, and no message calls
it a failure "after retries".
<!-- implements FR11, NFR-R3 of fix-operator-blockers -->

#### Scenario: One attempt, then a cancellation
- **WHEN** the calling thread is interrupted while GitHub has not yet
  answered a request
- **THEN** GitHub receives exactly one request, the client fails with the
  cancellation rather than a transport failure, and the thread's interrupt
  is set

#### Scenario: Tracker write interrupted mid-request
- **WHEN** a tracker write (park, finish, decline, note) is interrupted
  mid-request
- **THEN** the failure is not translated into a tracker-unavailable outage

#### Scenario: Interrupted external-check poll is not "cannot verify"
- **WHEN** the workflow-runs query of an external check is interrupted
  mid-request
- **THEN** the poll returns no cannot-verify status; the cancellation
  propagates out of the poll unchanged and no outcome line calls it an
  infrastructure failure

#### Scenario: A real transport failure keeps its retries
- **WHEN** a request fails with a connection reset and the thread is not
  interrupted
- **THEN** the client retries under its policy exactly as before

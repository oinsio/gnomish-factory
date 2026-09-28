## ADDED Requirements

### Requirement: Daemon workers treat an interrupt as a stop, not a failure
Every daemon site that catches a failed tracker call and would otherwise
report or account for it as a tracker failure — the reaper's sweep listing,
the feed's decline of finished tasks, the tracker health counter, and the
claim heartbeat's beat — SHALL first check whether its own thread's interrupt
is set. If it is, the failure
SHALL be treated as the stop that caused it: no WARN or ERROR line, no stack
trace (a DEBUG line at most), no outage bookkeeping (observation windows are
not forgotten, no listing-failed signal is sent, no failure is counted), and
no further tracker call from that loop. A failure on a thread whose interrupt
is not set keeps today's handling unchanged. This is how the stop-caused
interrupts of the "SIGTERM stops cleanly within grace" requirement are
classified on these paths.
<!-- implements FR12, NFR-O2 of fix-operator-blockers -->

#### Scenario: Stopping an idle daemon is quiet
- **WHEN** an idle daemon is stopped by a signal while the standing reaper's
  sweep listing waits on the tracker
- **THEN** the log after the serve-stopping anchor holds no WARN or ERROR
  line and no stack trace, and the reaper's thread ends

#### Scenario: Interrupted sweep keeps the observation windows
- **WHEN** a sweep listing fails on a thread whose interrupt is set
- **THEN** the reaper returns without forgetting its observation windows and
  without a listing-failed signal

#### Scenario: A real outage is still reported
- **WHEN** a sweep listing fails on a thread whose interrupt is not set
- **THEN** the reaper logs the sweep-listing-failed warning with the failure
  and forgets its observation windows, as before

#### Scenario: Interrupted decline stops the loop
- **WHEN** two finished tasks sit in the feed and the decline of the first
  fails on a thread whose interrupt is set
- **THEN** no warning is logged and the second task's decline is not
  attempted

#### Scenario: An interrupted call is not a tracker failure in the snapshot
- **WHEN** a tracker call fails on a thread whose interrupt is set
- **THEN** the tracker health counter's consecutive-failure count is
  unchanged

#### Scenario: Interrupted beat is not a beat failure
- **WHEN** a claim heartbeat's `heartbeat` call fails on a thread whose
  interrupt is set
- **THEN** no beat-failed warning is logged and the beat's outcome is
  unconfirmed, leaving the worker's loop to end on the interrupt

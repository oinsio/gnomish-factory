## MODIFIED Requirements

Layered on `SIGTERM stops cleanly within grace` as modified by `tighten-spec-contract`
(sequenced before this change): that delta cut the body and moved its cases into the scenarios
"A round outliving grace is left to the lease", "One shutdown sequence for signal and normal
exit" and "A death outside the shutdown phase keeps ERROR", with the phase rule in "Shutdown-caused
death is not an alarm"; this text replaces the release with the fenced return in the body and in
"Graceful release", and adds the scenarios "Shutdown return is fenced like any other",
"Unanswering tracker does not hold the exit" and "A genuine claim loss keeps the plain release";
both must survive the later sync.

### Requirement: SIGTERM stops cleanly within grace
On SIGTERM the daemon SHALL immediately stop claiming, let each slot stop at
its next round boundary within the configured grace window, and give back
the tasks of slots stopped this way through the fenced return
(`returnToReady`, reason "daemon shutting down") — `Ready` as soon as the
return lands, with no TTL wait. The stop SHALL run as one owned, idempotent
shutdown sequence, and on any exit the daemon SHALL kill its process group so
no gnome subprocess survives it.
**Implements**: FR11 of add-factory-serve; FR9, NFR-R1 of harden-logging-observability; FR6, NFR-R3, UX1, UX3 of add-claim-return
**Notes**: The shutdown reaches the round boundary through the same claim-loss hand-off a heartbeat-detected loss uses, with a typed cause that tells the two apart.

#### Scenario: Graceful release
- **WHEN** SIGTERM arrives while two slots sit between rounds
- **THEN** both tasks are returned within the grace window and are `Ready`
  immediately — on the in-memory tracker and on GitHub alike — with their
  branches carrying the committed rounds and one "returned to ready" marker
  each, and no "work stopped" note

#### Scenario: No orphan gnome
- **WHEN** the daemon exits while a gnome subprocess is still running
- **THEN** the gnome process is terminated with the daemon's process group

#### Scenario: Drain outcome survives the signal
- **WHEN** SIGTERM arrives mid-drain and in-flight slots finish within grace
- **THEN** each slot's terminal line and summary are present in the log file
  after the process exits, followed by the serve-stopping anchor

#### Scenario: Shutdown-caused death is not an alarm
- **WHEN** a slot crashes or the heartbeat worker dies once the shutdown phase
  has begun, whatever caused the death
- **THEN** the event is logged once at WARN without a stack trace, and no
  ERROR line attributes it to an application fault

#### Scenario: Second pass is a no-op
- **WHEN** the shutdown sequence runs after a completed drain has already
  stopped everything
- **THEN** it changes nothing and adds no error lines

#### Scenario: A round outliving grace is left to the lease
- **WHEN** a slot's round is still running when the grace window ends
- **THEN** the daemon does not release that claim and adds no mechanism of
  its own: the task returns through the lease path — TTL, reaper, resume
  from the branch

#### Scenario: One shutdown sequence for signal and normal exit
- **WHEN** `serve` stops on SIGTERM, and separately when a drain run exits
  normally
- **THEN** both run the same sequence — drain the slots, then close the
  application context, then stop logging with a final flush — and no other
  shutdown hook, the framework's or the logging framework's, closes the
  context or stops logging while slots still drain

#### Scenario: A death outside the shutdown phase keeps ERROR
- **WHEN** a slot crashes or the heartbeat worker dies before the shutdown
  phase has begun
- **THEN** it is logged at ERROR with its stack trace

#### Scenario: Shutdown return is fenced like any other
- **WHEN** SIGTERM arrives after a slot's claim was already reaped by another
  instance
- **THEN** that slot's return is a no-op, the new holder's claim is untouched,
  and the daemon still exits within grace

#### Scenario: Unanswering tracker does not hold the exit
- **WHEN** the tracker fails the return of a drained slot, or does not answer within the grace
  window
- **THEN** the failure is logged under its own code, the task is left to the
  reaper's TTL, never awaited past grace, and the process-tree kill and exit proceed on schedule

#### Scenario: A genuine claim loss keeps the plain release
- **WHEN** a heartbeat finds a slot's claim lost while the daemon is not shutting down
- **THEN** the revocation protocol runs with the plain release and its "work stopped" note, and
  no "returned to ready" marker is written

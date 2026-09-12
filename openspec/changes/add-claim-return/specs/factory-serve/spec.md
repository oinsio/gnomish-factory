## MODIFIED Requirements

### Requirement: SIGTERM stops cleanly within grace
On SIGTERM the daemon SHALL immediately stop claiming, let each slot stop at
its next round boundary within the configured grace window, and give back
the tasks of slots stopped this way through the fenced return
(`returnToReady`, reason "daemon shutting down") — `Ready` as soon as the
return lands, with no TTL wait. The shutdown SHALL reach the round boundary
through the same claim-loss hand-off a heartbeat-detected loss uses, but
with a typed cause: a shutdown-caused stop returns the task and posts no
"work stopped" note; a genuine loss keeps the revocation protocol and the
plain release unchanged. A return the tracker does not accept within the
grace window is abandoned to the lease path like a round that outlives the
window — logged, never awaited past grace. Rounds outliving the grace window
are abandoned to the lease path (TTL, reaper, resume from the branch) — no
additional mechanism. On any exit the daemon SHALL kill its process group so
no gnome subprocess survives it.

The shutdown SHALL be one owned, idempotent sequence covering the signal and
normal-exit paths alike: drain the slots, then close the application context,
then stop logging with a final flush — no concurrently racing shutdown hook
(framework-registered or logging-framework-registered) may close the context
or stop logging while slots still drain. Terminal slot lines, summaries, and
the serve-stopping anchor therefore survive a signal-initiated stop. Once the
shutdown phase has begun, child-process deaths and daemon-thread interrupts
caused by the stop SHALL be classified as shutdown-caused and reported without
stack traces at WARN or below; only genuinely independent failures keep ERROR.
<!-- implements FR11 of add-factory-serve -->
<!-- implements FR9, NFR-R1 of harden-logging-observability -->
<!-- implements FR6, NFR-R3, UX1, UX3 of add-claim-return -->

#### Scenario: Graceful release
- **WHEN** SIGTERM arrives while two slots sit between rounds
- **THEN** both tasks are returned within the grace window and are `Ready`
  immediately — on the in-memory tracker and on GitHub alike — with their
  branches carrying the committed rounds and one "returned to ready" marker
  each, and no "work stopped" note

#### Scenario: Shutdown return is fenced like any other
- **WHEN** SIGTERM arrives after a slot's claim was already reaped by another
  instance
- **THEN** that slot's return is a no-op, the new holder's claim is untouched,
  and the daemon still exits within grace

#### Scenario: Unanswering tracker does not hold the exit
- **WHEN** the tracker fails the return of a drained slot
- **THEN** the failure is logged under its own code, the task is left to the
  reaper's TTL, and the process-tree kill and exit proceed on schedule

#### Scenario: No orphan gnome
- **WHEN** the daemon exits while a gnome subprocess is still running
- **THEN** the gnome process is terminated with the daemon's process group

#### Scenario: Drain outcome survives the signal
- **WHEN** SIGTERM arrives mid-drain and in-flight slots finish within grace
- **THEN** each slot's terminal line and summary are present in the log file
  after the process exits, followed by the serve-stopping anchor

#### Scenario: Shutdown-caused death is not an alarm
- **WHEN** the stop kills a gnome subprocess or interrupts a daemon worker
  during the shutdown phase
- **THEN** the event is logged once without a stack trace, and no ERROR line
  attributes it to an application fault

#### Scenario: Second pass is a no-op
- **WHEN** the shutdown sequence runs after a completed drain has already
  stopped everything
- **THEN** it changes nothing and adds no error lines

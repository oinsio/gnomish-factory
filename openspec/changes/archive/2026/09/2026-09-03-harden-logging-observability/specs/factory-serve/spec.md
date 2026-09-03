# factory-serve — delta for harden-logging-observability

## MODIFIED Requirements

### Requirement: SIGTERM stops cleanly within grace
On SIGTERM the daemon SHALL immediately stop claiming, let each slot stop at
its next round boundary within the configured grace window, and explicitly
release the claims of tasks stopped this way — an instant return to `Ready`
with no TTL wait. Rounds outliving the grace window are abandoned to the
lease path (TTL, reaper, resume from the branch) — no additional mechanism.
On any exit the daemon SHALL kill its process group so no gnome subprocess
survives it.

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

#### Scenario: Graceful release
- **WHEN** SIGTERM arrives while two slots sit between rounds
- **THEN** both claims are released within the grace window and both tasks
  are `Ready` immediately, with their branches carrying the committed rounds

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

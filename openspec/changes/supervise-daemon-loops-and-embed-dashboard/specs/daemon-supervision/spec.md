# Spec Delta

## Purpose

Keeps the long-lived background loops of the factory daemon alive and
observable: a loop's failures never end it silently, a dead loop thread is
respawned or explicitly given up, and a stop ends it without racing a respawn.

## ADDED Requirements

### Requirement: A daemon loop survives failures of its work and its wait
A supervised daemon loop SHALL repeat its work on a cadence, either work then
wait or wait then work, where the wait is a fixed interval or an interval cut
short by a wake signal. Any failure thrown by the work or by the wait SHALL be
logged and SHALL NOT end the loop: the loop continues with its next wait.
<!-- implements FR1, FR2 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: An Error in the work does not end the loop
- **WHEN** one run of a loop's work throws an `Error`
- **THEN** a WARN line with the loop's component name and a stable operator
  event code is logged, and the work runs again after the next wait

#### Scenario: A throwing wait does not end the loop
- **WHEN** the loop's wait throws
- **THEN** the failure is logged and the loop runs its work again

#### Scenario: Wake signals coalesce
- **WHEN** many wake signals arrive while the work of a signal-woken loop is running
- **THEN** at most one further run follows before the loop waits its full
  interval again

### Requirement: Repeated failures of a loop log edges
A loop whose work fails on consecutive runs SHALL log the first failure (or a
changed reason) at WARN, repeats at DEBUG with a periodic counted roll-up, and
one recovery line on the first clean run after a failure. The roll-up window
SHALL be measured on the time source the loop stamps its own state with.
<!-- implements FR2, FR19, NFR-O1 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: A persistently failing loop floods no console
- **WHEN** a loop's work fails on fifty consecutive runs for the same reason
- **THEN** the console shows one WARN and counted roll-ups, not fifty WARN lines

#### Scenario: Recovery is announced
- **WHEN** the work succeeds after failing runs
- **THEN** one INFO line reports the recovery

#### Scenario: The roll-up period outlives the loop's own interval
- **WHEN** a loop whose interval is five minutes fails for a full hour
- **THEN** the console shows the first WARN and at most one roll-up per six runs, never one per run

#### Scenario: Suppression runs on the loop's time source
- **WHEN** a loop is built on a virtual instant source and its failures span a roll-up period on that source
- **THEN** the roll-up and the recovery are observed without real time passing

#### Scenario: Heartbeat suppression runs on the heartbeat's own time source
- **WHEN** the claim heartbeat is built on a virtual instant source and its beat fails across a
  roll-up period on that source
- **THEN** the roll-up and, after a successful beat, the recovery are observed without real time
  passing, on the same source its `alive-at` stamps come from

### Requirement: The Unbounded restart policy respawns a dead loop thread
If a loop's thread dies despite the failure guard and the loop's restart
policy is Unbounded, the loop SHALL be respawned after an exponential backoff,
logging ERROR with a lifetime restart count, and SHALL never give up. The
process SHALL keep running.
<!-- implements FR3 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Unbounded policy respawns with growing backoff
- **WHEN** an Unbounded loop's thread dies three times in a row without a clean
  run in between
- **THEN** each death logs an ERROR with restart counts 1, 2, 3, the backoffs
  double, and the loop runs its work again after each backoff

#### Scenario: A clean run resets the backoff
- **WHEN** a respawned loop completes one clean run and later dies again
- **THEN** the next backoff starts from the base interval while the restart
  count keeps increasing

### Requirement: The Bounded restart policy gives up on a loop that keeps dying
Under the Bounded restart policy a dead loop thread SHALL be respawned as under
the Unbounded policy until the restarts within a configured window exceed a
configured maximum. After that, one ERROR SHALL say the loop is disabled, no
respawn SHALL follow, and the process SHALL keep running.
<!-- implements FR3 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Bounded policy gives up
- **WHEN** a Bounded loop with a maximum of 5 restarts in 10 minutes dies a
  sixth time within 10 minutes
- **THEN** one ERROR says the loop is disabled, no respawn follows, and the
  daemon's other loops and slots keep running

### Requirement: A stop ends a loop without racing a respawn
A stop SHALL end the loop at its next check without a WARN or ERROR line,
SHALL interrupt the loop only while it waits, never while its work runs, and
SHALL prevent any respawn afterward, including one already waiting out a backoff.
A joining stop SHALL return only after the loop's current thread, including a
respawned one, has exited. Stopping an already stopped loop SHALL change nothing.
<!-- implements FR4, NFR-R2 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Stop during a respawn backoff
- **WHEN** a loop's thread has died and the loop is waiting out its backoff
  when the stop arrives
- **THEN** no thread is respawned and no further work runs

#### Scenario: Joining stop waits for the respawned thread
- **WHEN** a joining stop is requested while a respawn is starting a new thread
- **THEN** the call returns only after that new thread has exited, and no work
  runs after the call returns

#### Scenario: Stop during a run lets the run finish without a warning
- **WHEN** a stop is requested while the loop's work is writing a file
- **THEN** the work is not interrupted, the write completes, no WARN or ERROR
  line is logged, and the loop ends before its next wait

#### Scenario: Waiters are not blocked by a backoff
- **WHEN** a loop is waiting out a respawn backoff and another thread requests
  a stop
- **THEN** the stop call returns promptly, without waiting for the backoff

### Requirement: An interrupt never makes a loop spin
An interrupt on a loop's thread SHALL end the loop when a stop has been
requested. Otherwise the interrupt SHALL be cleared, logged once, and the loop
SHALL continue with its normal wait. No interrupt SHALL cause the work to run
again without a wait in between.
<!-- implements FR5 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Stray interrupt
- **WHEN** a running loop's thread is interrupted with no stop requested
- **THEN** one WARN line records the stray interrupt, and the next run of the
  work happens only after a full wait

#### Scenario: Interrupt from a stop is quiet
- **WHEN** a stop interrupts the loop's thread during its wait
- **THEN** the loop ends with no WARN or ERROR line and no stack trace

### Requirement: The daemon's long-lived loops are supervised
`gnomish serve` SHALL run the standing reaper, the worktree cleaner, the
sandbox sweep tick and the snapshot writer as supervised daemon loops under the
Unbounded policy, keeping each loop's cadence, its order of work and wait, and
the component name its log lines carry. The claim heartbeat SHALL remain
unsupervised: its abnormal death keeps degrading to the lease path.
<!-- implements FR6 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: The worktree cleaner survives an Error
- **WHEN** a worktree cleaner run throws an `Error`
- **THEN** the cleaner runs again on its next cadence

#### Scenario: Reaper restarts stay visible in the snapshot
- **WHEN** the standing reaper's thread dies and is respawned
- **THEN** a subsequent snapshot shows a grown `vitals.reaper.restartCount`

#### Scenario: Heartbeat death is still not resurrected
- **WHEN** the claim heartbeat's thread dies abnormally
- **THEN** it is not respawned, and the held claims go stale and are reaped as before

### Requirement: Long-lived loops are written only through the supervised loop
Production code of the application layer, which holds the daemon's loops,
SHALL start a long-lived, repeating daemon thread only through the supervised
daemon loop. Finite threads (a slot, a batch), the feed thread, shutdown hooks
and the claim heartbeat are the declared exemptions. A build gate SHALL fail on
any other thread start in that layer, and on any scheduled executor or timer
there.
<!-- implements FR16 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: A hand-written daemon loop fails the build
- **WHEN** an application-layer production class outside the exemptions starts
  its own virtual thread
- **THEN** the architecture gate fails, naming the file

#### Scenario: A scheduled executor fails the build
- **WHEN** an application-layer production class creates a scheduled executor
- **THEN** the architecture gate fails, naming the file

#### Scenario: Guidance exists before the code
- **WHEN** an author looks up how to add a new periodic daemon task
- **THEN** the process rules, an ADR and the glossary name the supervised
  daemon loop, when to use it, when not to, and how to pick its restart policy

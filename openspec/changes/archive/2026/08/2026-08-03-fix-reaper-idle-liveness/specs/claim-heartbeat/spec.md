## MODIFIED Requirements

### Requirement: Reaper returns stale claims to circulation
Stale-claim detection SHALL be a standing duty running for the whole run's
lifetime — a `take` invocation or the `serve` daemon — on its own thread,
independent of how many claims the instance currently holds, including zero. It
SHALL NOT be gated on the beat thread: an instance with no held claim still
lists open tasks with claim versions on each tick, updates its observation
memory, and for every stale `Working` claim invokes the port's stale-claim
removal — recording the holder-transition audit marker, removing the dead
claim, and returning the task to `Ready`. The reaper SHALL NOT claim the task
for itself; the task re-enters the ordinary queue. Two instances reaping the
same stale claim SHALL converge safely: the removal is idempotent in effect and
subsequent claiming follows the ordinary lease. The heartbeat thread SHALL NOT
run the reaper duty in any mode; its sole duty is beating the instance's own
held claims. The reaper SHALL tick on the heartbeat's beat interval; the
interval and TTL keep coming only from the factory's own clone of
`.gnomish/config.yaml` — this change introduces no new gnome-writable input.

The reaper SHALL NEVER remove a claim currently held **and actively beaten** by
its own instance: before judging staleness it excludes a live snapshot of the
claims its heartbeat is beating (empty when the heartbeat is not running), so a
run whose beats are failing while its `listOpen` still succeeds can never reap
its own live claim — only a foreign observer may (a running instance knows it is
alive; a bare "version unchanged" cannot mean "holder dead" for the holder
itself). When the heartbeat is not beating a claim — a fresh instance holding
nothing, or one whose beat thread has died — that claim is excluded from nothing
and is reaped once its TTL elapses like any other stale claim. A
`removeStaleClaim` that fails with an infrastructure error SHALL re-arm that
claim for retry on a later tick, so an attempted-but-failed removal never leaves
a stale claim silently un-reaped until its version changes.
<!-- implements FR4 of add-claim-heartbeat -->
<!-- implements NFR-R2 of add-claim-heartbeat -->
<!-- implements FR1, FR2, FR5 of fix-reaper-idle-liveness -->
<!-- implements NFR-R1, NFR-S1 of fix-reaper-idle-liveness -->

#### Scenario: Reaping continues while the instance holds no claim
- **WHEN** an instance holds and beats no claim of its own while a foreign
  `Working` claim in the listing stays at an unchanged version for TTL on the
  instance's monotonic clock
- **THEN** the reaper still ticks on its own thread, removes the stale foreign
  claim, and returns the task to `Ready` — no held claim of its own is required
  for reaping to run

#### Scenario: A restarted daemon returns its previous life's claims with nothing to claim
- **WHEN** a `serve` daemon that held two claims is killed and restarted, and
  the ready queue is empty so the new instance claims nothing
- **THEN** the standing reaper still observes the two claims left under the old
  instance id, reaps them once their TTL elapses, and returns both to `Ready`,
  without the new instance ever holding a claim first

#### Scenario: A dead heartbeat stops shielding its instance's claims
- **WHEN** an instance's heartbeat thread dies abnormally while its slots keep
  working, so its claims' versions stay unchanged for TTL
- **THEN** the instance's own standing reaper returns those claims to `Ready`;
  a slot still working such a task is a zombie from that point on and is
  neutralized by the ordinary fence path — the non-fast-forward push refusal
  or the pre-write claim check — on its next write

#### Scenario: An instance never reaps its own claim
- **WHEN** an instance's beats fail for longer than TTL while its `listOpen`
  keeps returning its own claim at an unchanged version
- **THEN** the instance never removes its own claim while its heartbeat is still
  beating it, and a foreign stale claim in the same listing is still reaped

#### Scenario: A failed removal is retried, not silently dropped
- **WHEN** the reaper's `removeStaleClaim` for a stale claim fails with an
  infrastructure error
- **THEN** the same unchanged version is emitted and retried on a later tick
  instead of being suppressed until the version changes or the instance
  restarts

#### Scenario: Dead instance's task returns without a human
- **WHEN** instance A dies mid-task and instance B works another task longer
  than TTL
- **THEN** B's standing reaper returns A's task to `Ready` with an audit
  marker, without claiming it, and a later run picks it up normally

#### Scenario: Double reap converges
- **WHEN** two instances detect the same stale claim and both invoke removal
- **THEN** the task ends in `Ready` exactly once — no error, no duplicate
  state transition, and at most one set of audit artifacts the thread can
  carry coherently

## ADDED Requirements

### Requirement: The reaper thread survives abnormal faults
The standing reaper SHALL keep reaping for the whole life of the process after
any single abnormal fault. Its loop SHALL catch every `Throwable` around both
the reap tick and the interval wait, logging the fault and continuing on the
next tick, so an `Error` raised deep in an adapter or a sleeper that throws does
not end reaping. Only an intentional stop SHALL exit the loop. Should the thread
nonetheless die, it SHALL be restarted (supervised) on an exponential backoff
capped at 10 minutes; the backoff SHALL reset once a respawned reaper completes
one full tick without dying. Restarts SHALL be unbounded — the reaper never
gives up and its failure SHALL NOT kill the daemon — and an intentional stop
SHALL NOT trigger a restart. An abnormal death and each restart SHALL be logged
at ERROR, each restart line carrying a monotonic restart count — the log is the
exposure surface; an ordinary tick fault SHALL be logged at WARN.
<!-- implements FR3, FR4 of fix-reaper-idle-liveness -->
<!-- implements NFR-R2, NFR-O1 of fix-reaper-idle-liveness -->

#### Scenario: An Error on one tick does not end reaping
- **WHEN** the reap tick raises an `Error` from deep in an adapter on one
  interval
- **THEN** the fault is logged, the thread does not die, and the next tick reaps
  a stale claim normally

#### Scenario: A throwing sleeper does not end reaping
- **WHEN** the interval sleeper throws on one wait
- **THEN** the fault is logged, the loop continues, and reaping resumes on the
  following interval

#### Scenario: A truly dead thread is respawned, an intentional stop is not
- **WHEN** the reaper thread dies from an unhandled failure while the run is
  still active
- **THEN** it is restarted with backoff and reaping resumes; **AND WHEN** the
  run stops it via `stop()` instead, no replacement thread is started

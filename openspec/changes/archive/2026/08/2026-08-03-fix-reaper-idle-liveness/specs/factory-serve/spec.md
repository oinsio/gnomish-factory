## MODIFIED Requirements

### Requirement: Serve maintains the lease and reaps in every state
`serve` SHALL run the instance heartbeat thread for all slots' `Working` tasks
and, independently, a standing reaper thread that lives for the daemon's whole
lifetime. The reaper SHALL keep observing and returning stale claims in every
feed state — Filling, Idle-empty, Idle-blocked, and Full — and regardless of how
many claims the instance currently holds, including zero. Reaping SHALL NOT be
gated on the beat thread: an idle daemon, or one freshly restarted with no
claims of its own, still reaps. In Full and Idle-blocked, a reaped `Working`
front also releases W budget with no human involved.
<!-- implements FR13 of add-factory-serve -->
<!-- implements FR1, FR5 of fix-reaper-idle-liveness -->
<!-- implements NFR-R1 of fix-reaper-idle-liveness -->

#### Scenario: Reaping while saturated
- **WHEN** all slots are busy and a foreign claim goes stale
- **THEN** the stale claim is still removed within one reaper tick and its
  task returns to `Ready`, lowering the open-front count

#### Scenario: Reaping while idle with no claims of its own
- **WHEN** the instance holds no claim of its own — Idle-empty, or just
  restarted — and a foreign `Working` claim goes stale
- **THEN** the standing reaper still removes it within one reaper interval and
  the task returns to `Ready`, without the instance needing to hold a claim
  first

### Requirement: Restart is a clean start
A restarted daemon SHALL recognize no previous claims as its own: claims
under a prior instance id are left to the lease protocol — reaped after TTL
or explicitly taken over — and may well be re-claimed by the new process
through the ordinary queue. No instance-local state survives or is needed.
Recovery of the previous life's claims SHALL NOT depend on the new process
claiming any fresh task first: the standing reaper returns them even when the
new process holds nothing.
<!-- implements FR12 of add-factory-serve -->
<!-- implements FR1 of fix-reaper-idle-liveness -->
<!-- implements NFR-R1 of fix-reaper-idle-liveness -->

#### Scenario: Claims of the previous life
- **WHEN** the daemon is killed and restarted while it held two claims
- **THEN** the new process starts clean, the old claims go stale, and the
  reaper returns their tasks to circulation for ordinary re-claiming

#### Scenario: Restart against an empty queue still recovers
- **WHEN** the daemon is killed while it held two claims and restarts against an
  empty ready queue, so it claims nothing and would otherwise sit Idle-empty or
  Idle-blocked forever
- **THEN** the standing reaper still returns both prior-life claims to
  circulation once their TTL elapses, without the new process ever holding a
  claim of its own

### Requirement: Scheduler runs N slots over the existing take cycle
The scheduler SHALL run up to N concurrent slots, each executing the existing
take cycle (claim → run → react to the outcome) unchanged; N SHALL be instance
configuration with a modest default. Claiming SHALL happen in the feed — a
slot receives an already-claimed task — the scheduler SHALL never hand one
task to two slots of the same instance, and concurrent claim attempts SHALL
never exceed the instance's free slots. The feed SHALL NOT claim a task that
still occupies one of this instance's slots, even when it shows `Ready` — the
shape left behind when the heartbeat thread died abnormally and the instance's
own standing reaper returned a still-running slot's task: the skip SHALL be
logged, the old slot is neutralized by the ordinary revocation check at its
next round boundary (its task is no longer `Working` under this instance), and
the task stays claimable by a foreign instance at any time — or by this
instance once the old slot ends.
<!-- implements FR1, FR9 of add-factory-serve -->
<!-- implements NFR-R1 of add-factory-serve -->
<!-- implements FR2 of fix-reaper-idle-liveness -->

#### Scenario: No double assignment
- **WHEN** the feed fills an instance with N free slots under randomized
  interleavings
- **THEN** every claimed task runs in exactly one slot, and at no point are
  more claim attempts in flight than free slots

#### Scenario: Slot body unchanged
- **WHEN** a slot's task escalates
- **THEN** park, report, and exit-state handling behave exactly as a single
  `take` of that task would

#### Scenario: Self-reaped task is not re-claimed while its old slot lives
- **WHEN** the daemon's heartbeat thread dies abnormally, its own standing
  reaper returns a still-running slot's task to `Ready` after TTL, and the
  feed's next poll offers that task as a claim candidate
- **THEN** the feed skips it, logging the skip, and claims other candidates
  instead; the old slot stops via the ordinary revocation path at its next
  round boundary, and only after its slot is released may this instance claim
  the task again — a foreign instance may claim it at any time, fenced as
  usual

### Requirement: Daemon tolerates tracker outages
A tracker outage SHALL not kill the daemon: the feed, the heartbeat, and the
standing reaper retry with backoff and recover when the tracker returns;
running slots continue (outcomes stay durable in branches and terminal writes
reconcile); staleness makes no progress without observations, so no false
reaping occurs.
<!-- implements NFR-R3 of add-factory-serve -->
<!-- implements FR3 of fix-reaper-idle-liveness -->

#### Scenario: Outage passes through
- **WHEN** the tracker is unreachable for an hour while two slots work
- **THEN** the daemon is still running when it returns, both tasks proceed,
  and no claim was falsely reaped by anyone

# factory-serve

## Purpose

The autonomous factory frame: the `gnomish serve` daemon, the N-slot scheduler
shared with batch take, the feed automaton, the project-wide WIP limit,
multi-instance conduct, the process lifecycle, and the worktree cleaner. The
slot body is the existing take cycle — this capability frames it, never
changes it.

## Requirements

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

### Requirement: serve command surface
`gnomish serve` SHALL continuously serve the ready queue with the scheduler
until stopped, always in git mode, supporting `--dir` and the drain flag —
and SHALL be unconditionally non-interactive: no serve path prompts on a TTY;
escalation always parks with a tracker report. Startup SHALL run label
provisioning as a binding smoke test: an unreachable repository is death on
startup with a clear error, before anything is claimed.
<!-- implements FR2, FR4, FR12 of add-factory-serve -->

#### Scenario: Escalation parks without a prompt
- **WHEN** a slot's task escalates while `serve` runs with a TTY attached
- **THEN** the task parks with its report and no dialog is shown

#### Scenario: Startup smoke test
- **WHEN** `serve` starts against an unreachable repository binding
- **THEN** the process exits on startup with an error naming the binding,
  having claimed nothing

### Requirement: Feed automaton with a single idle interval
The feed SHALL cycle through four states: Filling (free slot and an eligible
task — poll, claim, immediately again, no pause), Idle-empty (free slot but no
backoff-eligible ready work — an empty or fully backoff-suppressed queue),
Idle-blocked (free slot and backoff-eligible ready work exists, but open ≥ W
holds back the fresh start), and Full (no free slot — no queue polling at all;
wakes on the local slot-freed event).
One idle-poll interval — factory configuration, default 30 s — SHALL drive
both Idle states; polls SHALL use conditional requests so an unchanged queue
costs no rate-limit budget; the poll phase SHALL be jittered so instances do
not synchronize.
<!-- implements FR5, FR9 of add-factory-serve -->
<!-- implements NFR-P1 of add-factory-serve -->

#### Scenario: Full does not poll
- **WHEN** all slots are busy
- **THEN** the instance sends no queue polls until a slot frees, and the
  freed slot triggers an immediate poll without waiting for the timer

#### Scenario: Idle polls on the timer
- **WHEN** a slot is free and nothing is eligible
- **THEN** the feed polls on the idle interval and claims within one interval
  of a task becoming eligible

### Requirement: WIP limit bounds fresh starts
Every autonomous claim — the `serve` feed and bare auto `take` — SHALL
respect the project WIP limit W: fresh tasks are claimed only while open
fronts (count of `Working` plus `AwaitingHuman` from `listOpen`) are below W;
returned tasks SHALL be claimable always — outside the limit and ahead of
fresh ones ("stop starting, start finishing"). The limit is soft across
instances: racing instances may overshoot by at most one task each. The
explicit `take <ref>` mandate does not consult W.
<!-- implements FR6, FR8 of add-factory-serve -->
<!-- implements NFR-C1 of add-factory-serve -->

#### Scenario: Limit reached blocks fresh work
- **WHEN** open fronts equal W and the queue holds only fresh ready tasks
- **THEN** the feed claims nothing and logs that W fronts await human
  decisions and no fresh work starts

#### Scenario: Returned task bypasses the limit
- **WHEN** open ≥ W and a parked task returns to ready
- **THEN** the feed claims the returned task ahead of any fresh one

### Requirement: Feed declines finished-reopened tasks
The `serve` feed cycle SHALL treat `finished = true` entries from `listReady`
as terminal, never as work: they are excluded from claim candidates (neither
returned-priority nor fresh; they never occupy a slot and never count toward
or against the WIP limit), and each one observed SHALL be declined via the
tracker port's decline operation — restoring its terminal status and posting
the explanation — before candidate selection proceeds over the remaining
entries. A failed decline SHALL be logged and retried naturally on the next
poll cycle (the task simply reappears in the feed); no instance-local
"already declined" memory is kept.
<!-- implements FR3, FR4 of enforce-finish-terminality -->
<!-- implements NFR-R2, NFR-R3, NFR-O1 of enforce-finish-terminality -->

#### Scenario: Reopened finished task is declined within one poll
- **WHEN** a human moves a finished task back to ready while `serve` is
  polling
- **THEN** within one poll cycle the task's terminal status is restored, the
  explanation comment is posted, no claim is attempted, and no slot is
  consumed

#### Scenario: Decline failure converges on the next cycle
- **WHEN** the decline write fails with a transient tracker error
- **THEN** the cycle logs the failure, claims nothing for that entry, and the
  next poll observes the task still in the feed and declines again

#### Scenario: Finished entries do not distort the WIP gate
- **WHEN** the feed lists W fresh tasks and one reopened finished task with
  open fronts below W
- **THEN** candidate selection runs over the fresh tasks as if the finished
  entry were absent

### Requirement: Drain mode exits when the work runs out
With the drain flag, "nothing eligible to claim" SHALL become the
stop-claiming signal instead of an idle sleep: occupied slots run their tasks
to terminal results and the process exits cleanly, reporting what it worked,
when all slots are empty.
<!-- implements FR10 of add-factory-serve -->
<!-- implements NFR-O2 of add-factory-serve -->

#### Scenario: Nightly drain
- **WHEN** cron fires `serve` with the drain flag against a queue of three
  eligible tasks and N = 2
- **THEN** the run works all three, exits 0 when the last slot empties, and
  the closing report names each task's outcome

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

### Requirement: Concurrent slots share one clone safely
N slots operating on one target clone — fetch, worktree add and remove,
push — SHALL be safe under concurrency, serialized where git requires it;
verified by tests exercising overlapping slot lifecycles in one repository.
<!-- implements NFR-R2 of add-factory-serve -->

#### Scenario: Overlapping slot lifecycles
- **WHEN** two slots simultaneously materialize worktrees and push branches
  in the same clone
- **THEN** both tasks complete with correct branches and no git-level
  corruption or spurious failure

### Requirement: Container-bound stages run in slots
`gnomish serve` slots SHALL execute container-bound stages through the same container assembly as `gnomish take`, concurrently across slots, each slot's environment isolated by its own task key. At task end the slot SHALL stop-keep a non-disposed environment (stop the box, retain volume and network) so the kept population is well-formed for the reaper. Host-mode slots SHALL be unchanged.
<!-- implements FR1, FR6 of add-serve-sandbox-lifecycle -->

#### Scenario: Two slots run containers concurrently
- **WHEN** two slots hold container-bound tasks at once
- **THEN** each task runs in its own box, volume, and network, and neither slot's lifecycle operations touch the other's objects

#### Scenario: Slot end leaves a kept environment
- **WHEN** a slot's task exits non-completed (escalated, paused)
- **THEN** the box is stopped with volume and network retained, and the environment appears in the kept inventory

### Requirement: Daemon schedules the sandbox sweep tick
The daemon SHALL run the `sandbox-lifecycle` sweep and aged reaper on a periodic tick for its whole lifetime, starting with an immediate startup tick. The tick SHALL run off the slot path — slot launch latency is unaffected — and a failed tick is logged and retried at the next cadence, never killing the daemon. Tick cadence SHALL be configurable.
<!-- implements FR6, NFR-P1, NFR-R3 of add-serve-sandbox-lifecycle -->

#### Scenario: Sweep tick coexists with launching slots
- **WHEN** a tick evaluates the host while a slot is mid-materialize
- **THEN** the launching objects are protected (labels from birth plus minimum age) and the tick completes without delaying the launch

#### Scenario: Dead sibling reclaimed without its reboot
- **WHEN** another instance died mid-task and its claim went stale
- **THEN** this daemon's next tick stops the abandoned running box; no restart of the dead instance is involved

### Requirement: Worktree cleaner disposes aged task environments
The daemon SHALL dispose of host worktree environments whose last file activity is older than a configured age and which do not currently occupy a slot of this instance; ended tasks (delivered, escalated, revoked) stop touching their worktrees and are the population this policy targets. Disposal SHALL go through the bound task environment port. For host worktrees, tracker status SHALL NOT be consulted — worktrees are instance-local and a disposed-too-early worktree costs only a re-materialize on resume, never correctness. A task currently occupying a slot of this instance SHALL never be disposed regardless of age, and a same-instance resume SHALL keep reusing a still-present environment. Sandboxed (container) environments are NOT governed by this cleaner: they live in a host-global namespace and are governed by the ownership-based sweep and aged reaper of `sandbox-lifecycle`.
<!-- implements FR14 of add-factory-serve -->
<!-- implements FR5, FR6 of add-serve-sandbox-lifecycle -->

#### Scenario: Aged environment removed
- **WHEN** an escalated task's worktree exceeds the age threshold
- **THEN** the cleaner removes it, and a later resume rematerializes the
  worktree from the branch

#### Scenario: Aged container environment removed by runtime age
- **WHEN** an escalated task's stopped container exceeds the age threshold per its runtime metadata
- **THEN** this cleaner leaves it untouched — it is not a host worktree — and the `sandbox-lifecycle` aged reaper disposes container, volume, and network by the same runtime-metadata age, so a later resume still materializes a fresh environment from the branch

#### Scenario: Working task untouched
- **WHEN** the cleaner runs while a task is `Working` in a slot of this
  instance
- **THEN** that task's environment is not considered for disposal

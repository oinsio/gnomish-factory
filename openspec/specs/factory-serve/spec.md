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
never exceed the instance's free slots.
<!-- implements FR1, FR9 of add-factory-serve -->
<!-- implements NFR-R1 of add-factory-serve -->

#### Scenario: No double assignment
- **WHEN** the feed fills an instance with N free slots under randomized
  interleavings
- **THEN** every claimed task runs in exactly one slot, and at no point are
  more claim attempts in flight than free slots

#### Scenario: Slot body unchanged
- **WHEN** a slot's task escalates
- **THEN** park, report, and exit-state handling behave exactly as a single
  `take` of that task would

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
<!-- implements FR11 of add-factory-serve -->

#### Scenario: Graceful release
- **WHEN** SIGTERM arrives while two slots sit between rounds
- **THEN** both claims are released within the grace window and both tasks
  are `Ready` immediately, with their branches carrying the committed rounds

#### Scenario: No orphan gnome
- **WHEN** the daemon exits while a gnome subprocess is still running
- **THEN** the gnome process is terminated with the daemon's process group

### Requirement: Restart is a clean start
A restarted daemon SHALL recognize no previous claims as its own: claims
under a prior instance id are left to the lease protocol — reaped after TTL
or explicitly taken over — and may well be re-claimed by the new process
through the ordinary queue. No instance-local state survives or is needed.
<!-- implements FR12 of add-factory-serve -->

#### Scenario: Claims of the previous life
- **WHEN** the daemon is killed and restarted while it held two claims
- **THEN** the new process starts clean, the old claims go stale, and the
  reaper returns their tasks to circulation for ordinary re-claiming

### Requirement: Serve maintains the lease and reaps in every state
`serve` SHALL run the instance heartbeat thread for all slots' `Working`
tasks; the reaper duty on that thread SHALL keep observing and returning
stale claims in every feed state — including Full and Idle-blocked, where a
reaped `Working` front also releases W budget with no human involved.
<!-- implements FR13 of add-factory-serve -->

#### Scenario: Reaping while saturated
- **WHEN** all slots are busy and a foreign claim goes stale
- **THEN** the stale claim is still removed within one beat tick and its
  task returns to `Ready`, lowering the open-front count

### Requirement: Daemon tolerates tracker outages
A tracker outage SHALL not kill the daemon: the feed and the heartbeat retry
with backoff and recover when the tracker returns; running slots continue
(outcomes stay durable in branches and terminal writes reconcile); staleness
makes no progress without observations, so no false reaping occurs.
<!-- implements NFR-R3 of add-factory-serve -->

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

### Requirement: Worktree cleaner disposes aged task environments
The daemon SHALL dispose of task environments whose last file activity is
older than a configured age and which do not currently occupy a slot of
this instance; ended tasks (delivered, escalated, revoked) stop touching
their worktrees and are the population this policy targets, but tracker
status SHALL NOT be consulted — a disposed-too-early worktree costs only a
re-clone on resume, never correctness. Disposal is a localized "dispose of
a task's environment by age" responsibility whose callers never assume the
environment is a host worktree — the future sandbox change replaces its
inside, not its callers. A task currently occupying a slot of this
instance SHALL never be disposed regardless of age, and a same-instance
resume SHALL keep reusing a still-present worktree.
<!-- implements FR14 of add-factory-serve -->

#### Scenario: Aged environment removed
- **WHEN** an escalated task's worktree exceeds the age threshold
- **THEN** the cleaner removes it, and a later resume rematerializes the
  worktree from the branch

#### Scenario: Working task untouched
- **WHEN** the cleaner runs while a task is `Working` in a slot of this
  instance
- **THEN** that task's worktree is not considered for disposal
</content>

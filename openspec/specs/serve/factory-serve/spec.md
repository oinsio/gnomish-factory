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
never exceed the instance's free slots, nor claim a task that still occupies
one of this instance's slots.
**Implements**: FR1, FR9 of add-factory-serve; NFR-R1 of add-factory-serve; FR2 of fix-reaper-idle-liveness
**Notes**: A task still in a slot shows `Ready` when the heartbeat thread died abnormally and the instance's own standing reaper returned it; the old slot ends at its next round boundary because its task is no longer `Working` under this instance.

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
until stopped, always in git mode, supporting `--dir`, the drain flag,
`--dashboard` and `--dashboard-out=<path>`, and SHALL be unconditionally
non-interactive: no serve path prompts on a TTY; escalation always parks with
a tracker report. Startup SHALL run label provisioning as a binding smoke
test before anything is claimed.
**Implements**: FR2, FR4, FR12 of add-factory-serve; FR8 of supervise-daemon-loops-and-embed-dashboard

#### Scenario: Escalation parks without a prompt
- **WHEN** a slot's task escalates while `serve` runs with a TTY attached
- **THEN** the task parks with its report and no dialog is shown

#### Scenario: Startup smoke test
- **WHEN** `serve` starts against an unreachable repository binding
- **THEN** the process exits on startup with an error naming the binding,
  having claimed nothing

#### Scenario: Property enables the dashboard
- **WHEN** the host configuration sets `factory.serve.dashboard: true` and
  `serve` runs without `--dashboard`
- **THEN** the daemon renders the dashboard page

#### Scenario: Output path without the dashboard
- **WHEN** `serve --dashboard-out=page.html` runs with the dashboard disabled
- **THEN** the command fails with a usage error before anything is claimed

#### Scenario: The flag enables the dashboard
- **WHEN** `serve --dashboard` runs and the host configuration leaves
  `factory.serve.dashboard` unset
- **THEN** the daemon renders the dashboard page at the default output path

#### Scenario: The dashboard is off by default
- **WHEN** `serve` runs without `--dashboard` and the host configuration
  leaves `factory.serve.dashboard` unset
- **THEN** no dashboard page is rendered

### Requirement: Feed automaton with a single idle interval
The feed SHALL cycle through four states: Filling (free slot and an eligible
task — poll, claim, immediately again, no pause), Idle-empty (free slot but no
backoff-eligible ready work — an empty or fully backoff-suppressed queue),
Idle-blocked (free slot and backoff-eligible ready work exists, but open ≥ W
holds back the fresh start), and Full (no free slot — no queue polling at all;
wakes on the local slot-freed event).
**Implements**: FR5, FR9 of add-factory-serve; NFR-P1 of add-factory-serve

#### Scenario: Full does not poll
- **WHEN** all slots are busy
- **THEN** the instance sends no queue polls until a slot frees, and the
  freed slot triggers an immediate poll without waiting for the timer

#### Scenario: Idle polls on the timer
- **WHEN** a slot is free and nothing is eligible
- **THEN** the feed polls on the idle interval and claims within one interval
  of a task becoming eligible

#### Scenario: One interval drives both idle states
- **WHEN** the feed is Idle-empty and later Idle-blocked, with the idle-poll
  interval left at its factory default
- **THEN** both states wait the same interval between polls, 30 s before
  jitter

#### Scenario: An unchanged queue costs no rate-limit budget
- **WHEN** the feed polls a ready queue that has not changed since its last
  poll
- **THEN** the poll is a conditional request answered as unchanged, and it
  consumes no rate-limit budget

#### Scenario: Poll phases are jittered
- **WHEN** the feed sleeps the idle interval repeatedly
- **THEN** each sleep adds a random jitter to the interval, so instances
  started together do not poll in step

### Requirement: WIP limit bounds fresh starts
Every autonomous claim — the `serve` feed and bare auto `take` — SHALL
respect the project WIP limit W: fresh tasks are claimed only while open
fronts (count of `Working` plus `AwaitingHuman` from `listOpen`) are below W;
returned tasks SHALL be claimable always — outside the limit and ahead of
fresh ones ("stop starting, start finishing"). The limit is soft across
instances: racing instances may overshoot by at most one task each. The
explicit `take <ref>` mandate does not consult W.
**Implements**: FR6, FR8 of add-factory-serve; NFR-C1 of add-factory-serve

#### Scenario: Limit reached blocks fresh work
- **WHEN** open fronts equal W and the queue holds only fresh ready tasks
- **THEN** the feed claims nothing and logs that W fronts await human
  decisions and no fresh work starts

#### Scenario: Returned task bypasses the limit
- **WHEN** open ≥ W and a parked task returns to ready
- **THEN** the feed claims the returned task ahead of any fresh one

### Requirement: Feed declines finished-reopened tasks
The `serve` feed cycle SHALL treat `finished = true` entries from `listReady`
as terminal, never as work: they are excluded from claim candidates, and
each one observed SHALL be declined via the tracker port's decline operation
— restoring its terminal status and posting the explanation — before
candidate selection proceeds over the remaining entries.
**Implements**: FR3, FR4 of enforce-finish-terminality; NFR-R2, NFR-R3, NFR-O1 of enforce-finish-terminality

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
**Implements**: FR10 of add-factory-serve; NFR-O2 of add-factory-serve

#### Scenario: Nightly drain
- **WHEN** cron fires `serve` with the drain flag against a queue of three
  eligible tasks and N = 2
- **THEN** the run works all three, exits 0 when the last slot empties, and
  the closing report names each task's outcome

### Requirement: SIGTERM stops cleanly within grace
On SIGTERM the daemon SHALL immediately stop claiming, let each slot stop at
its next round boundary within the configured grace window, and explicitly
release the claims of tasks stopped this way — an instant return to `Ready`
with no TTL wait. The stop SHALL run as one owned, idempotent shutdown
sequence, and on any exit the daemon SHALL kill its process group so no gnome
subprocess survives it.
**Implements**: FR11 of add-factory-serve; FR9, NFR-R1 of harden-logging-observability

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

### Requirement: Restart is a clean start
A restarted daemon SHALL recognize no previous claims as its own: claims
under a prior instance id are left to the lease protocol — reaped after TTL
or explicitly taken over — and may well be re-claimed by the new process
through the ordinary queue. No instance-local state survives or is needed.
Recovery of the previous life's claims SHALL NOT depend on the new process
claiming any fresh task first: the standing reaper returns them even when the
new process holds nothing.
**Implements**: FR12 of add-factory-serve; FR1 of fix-reaper-idle-liveness; NFR-R1 of fix-reaper-idle-liveness

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
many claims the instance currently holds, including zero.
**Implements**: FR13 of add-factory-serve; FR1, FR5 of fix-reaper-idle-liveness; NFR-R1 of fix-reaper-idle-liveness

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

#### Scenario: Reaping does not wait on the beat thread
- **WHEN** the instance's heartbeat thread is idle or dead and a foreign
  `Working` claim goes stale
- **THEN** the standing reaper still removes it within one reaper interval

#### Scenario: Reaping unblocks a WIP-blocked feed
- **WHEN** the feed is Idle-blocked with open fronts at W and one `Working`
  front goes stale
- **THEN** the reaper returns that task to `Ready`, the open-front count
  drops below W, and the feed claims work again with no human involved —
  the returned task first, ahead of fresh work, as returned tasks always are

### Requirement: Daemon tolerates tracker outages
A tracker outage SHALL not kill the daemon: the feed, the heartbeat, and the
standing reaper retry with backoff and recover when the tracker returns;
running slots continue (outcomes stay durable in branches and terminal writes
reconcile); staleness makes no progress without observations, so no false
reaping occurs.
**Implements**: NFR-R3 of add-factory-serve; FR3 of fix-reaper-idle-liveness

#### Scenario: Outage passes through
- **WHEN** the tracker is unreachable for an hour while two slots work
- **THEN** the daemon is still running when it returns, both tasks proceed,
  and no claim was falsely reaped by anyone

### Requirement: Concurrent slots share one clone safely
N slots operating on one target clone — fetch, worktree add and remove,
push — SHALL be safe under concurrency, serialized where git requires it;
verified by tests exercising overlapping slot lifecycles in one repository.
**Implements**: NFR-R2 of add-factory-serve

#### Scenario: Overlapping slot lifecycles
- **WHEN** two slots simultaneously materialize worktrees and push branches
  in the same clone
- **THEN** both tasks complete with correct branches and no git-level
  corruption or spurious failure

### Requirement: Container-bound stages run in slots
`gnomish serve` slots SHALL execute container-bound stages through the same container assembly as `gnomish take`, concurrently across slots, each slot's environment isolated by its own task key. At task end the slot SHALL stop-keep a non-disposed environment (stop the box, retain volume and network) so the kept population is well-formed for the reaper. Host-mode slots SHALL be unchanged.
**Implements**: FR1, FR6 of add-serve-sandbox-lifecycle

#### Scenario: Two slots run containers concurrently
- **WHEN** two slots hold container-bound tasks at once
- **THEN** each task runs in its own box, volume, and network, and neither slot's lifecycle operations touch the other's objects

#### Scenario: Slot end leaves a kept environment
- **WHEN** a slot's task exits non-completed (escalated, paused)
- **THEN** the box is stopped with volume and network retained, and the environment appears in the kept inventory

### Requirement: Daemon schedules the sandbox sweep tick
The daemon SHALL run the `sandbox-lifecycle` sweep and aged reaper on a periodic tick for its whole lifetime, starting with an immediate startup tick. The tick SHALL run off the slot path — slot launch latency is unaffected — and a failed tick is logged and retried at the next cadence, never killing the daemon. Tick cadence SHALL be configurable.
**Implements**: FR6, NFR-P1, NFR-R3 of add-serve-sandbox-lifecycle

#### Scenario: Sweep tick coexists with launching slots
- **WHEN** a tick evaluates the host while a slot is mid-materialize
- **THEN** the launching objects are protected (labels from birth plus minimum age) and the tick completes without delaying the launch

#### Scenario: Dead sibling reclaimed without its reboot
- **WHEN** another instance died mid-task and its claim went stale
- **THEN** this daemon's next tick stops the abandoned running box; no restart of the dead instance is involved

### Requirement: Worktree cleaner disposes aged task environments
The daemon SHALL dispose of host worktree environments whose last file activity is at least as old as a configured age and which do not currently occupy a slot of this instance, through the bound task environment port. On every tick the cleaner SHALL also forward each `CompletedUncleaned` or `Unregistered` task branch of the clone to the one claimless owner, which fetches before it classifies and claims nothing, and SHALL log every branch it does not forward.
**Implements**: FR4, FR11, FR12, UX2, NFR-C2, NFR-O2 of fix-terminal-receipt-convergence; FR14 of add-factory-serve; FR5, FR6 of add-serve-sandbox-lifecycle
**Notes**: Ended tasks (delivered, escalated, revoked) stop touching their worktrees and are the population the age policy targets; worktrees are instance-local, so one disposed too early costs only a re-materialize on resume, never correctness.
**Notes**: Container environments live in a host-global namespace; the ownership-based sweep and aged reaper of `sandbox-lifecycle` govern them.

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

#### Scenario: The cleaner consults no tracker status
- **WHEN** the cleaner evaluates an aged host worktree that occupies no slot
  of this instance
- **THEN** it disposes the worktree without reading the task's tracker
  status; only the branch pass reads the tracker, and only for a `CompletedUncleaned` tip

#### Scenario: Resume reuses a still-present worktree
- **WHEN** a task resumes on the same instance while its worktree is still
  present
- **THEN** the slot reuses that worktree rather than materializing a new one

#### Scenario: A finished task's branch is cleaned on the branch pass
- **WHEN** the clone holds a `gnomish/*` branch whose tip records `Completed` with its envelope
  still there and the tracker reports the task finished
- **THEN** the tick lands the cleanup commit, pushes it, disposes the environment, writes no
  claim, and the next tick finds the branch `Delivered` and writes nothing

#### Scenario: A branch fetched without its register is read through the fetch before it is judged
- **WHEN** the clone holds a `gnomish/*` branch only as a remote-tracking ref with no register
  for it — as an operator's or an IDE's `git fetch` leaves it — while origin's register names a
  `CompletedUncleaned` tip and the tracker reports the task finished
- **THEN** the pass forwards the branch, the owner's locate fetches the branch and its register
  in place, the tip classifies `CompletedUncleaned`, one tracker read confirms the finish, the
  branch is adopted, the cleanup lands and is pushed, and the next tick finds it `Delivered`

#### Scenario: A branch the pass does not forward costs nothing
- **WHEN** the clone holds a `Delivered`, an `InProgress` and a `Parked` branch
- **THEN** the pass runs no fetch and no tracker read for them and logs one DEBUG line per
  branch naming the shape it left alone

#### Scenario: A branch still unregistered after the owner's read is named, not skipped
- **WHEN** a forwarded `Unregistered` branch is still `Unregistered` after the owner's read —
  origin delivered no register for the task, origin could not be reached, or the clone holds it
  as a local head, which the locate does not fetch
- **THEN** the pass logs one aggregate line per pass naming such branches and the location kind
  of each, without asserting which cause holds, writes nothing to them, and the next tick names them again rather than skipping them silently

### Requirement: Serve survives base-refresh outages without charging tasks
A base-resolution infrastructure failure in a serve slot SHALL NOT kill the
slot or the daemon: the claim is released per tracker-take, the slot returns
to the feed, and running slots continue. The failure SHALL NOT be recorded as
an abort: no abort marker, no comment, no change to the task's backoff or to
the K-fuse accounting.
**Implements**: FR9, G5 of add-base-ref-resolution
**Notes**: The outage belongs to the daemon (the remote outage gate below), not to any task; its ledger treatment is serve-observability's (one `remoteOutage` line per closed outage, no `taskOutcome` line for a released task).

#### Scenario: Released tasks carry no abort history
- **WHEN** a slot's base refresh fails against a dead remote and the task is
  claimed again after the remote returns
- **THEN** the task's abort facts read zero, its backoff is unexpired, and
  the tracker thread holds no abort marker and no comment from the outage

#### Scenario: Recovery needs no operator action
- **WHEN** the remote returns after an outage
- **THEN** the next feed cycle claims a task, resolves and refreshes its
  base normally, and no residue of the outage (claims, statuses, comments)
  needs manual cleanup

#### Scenario: An outage posts no escalation
- **WHEN** a slot's base refresh fails against a dead remote
- **THEN** the task is neither parked nor escalated, and no tracker
  escalation report is posted for it

#### Scenario: The outage release is its own outcome kind
- **WHEN** a slot releases its claim because the base refresh hit a dead
  remote
- **THEN** serve's outcome log records the slot under the typed
  released-for-infrastructure kind, never as a free-text skip

### Requirement: Remote outage gate holds the feed off the tracker
`serve` SHALL keep one set of daemon conditions with one owner, one condition per precondition that can change while the daemon runs: the remote (per remote target), the container runtime, the clone's ref format, the image contract and the box's git floor. A slot's base-refresh infrastructure failure SHALL open the remote condition; a typed precondition refusal from a slot or the janitor SHALL open its condition. The feed SHALL read one gate over all conditions before every claim and SHALL claim nothing while any is open. While a condition is open the daemon SHALL probe it by repeating the check that opened it, on the growing, jittered, capped cadence, and only a passing probe closes it. Slots already working SHALL continue under an open condition.
**Implements**: FR14, NFR-R3, M4 of add-base-ref-resolution; FR2, FR3, NFR-O1, UX1 of signal-outage-gate-on-origin-contact; FR15, FR16 of add-sandbox-base-image
**Notes**: The remote outage gate's state machine, schedule and three-phase probe are unchanged; it is the first condition of the set (design D19).

#### Scenario: At most one claim per slot for the whole outage
- **WHEN** the remote is unreachable for an hour under a three-slot serve
- **THEN** the tracker sees at most three claims and three releases from the
  outage, none after the gate opened, and the daemon is still running

#### Scenario: A probe, not a claim, ends the outage
- **WHEN** the remote returns while the gate is open
- **THEN** the next probe succeeds, the gate closes, and only then does the
  feed claim a task — the first successful `ls-remote` precedes the first
  claim

#### Scenario: A flapping remote does not restart the interval
- **WHEN** the gate closes on a successful probe and the very next base
  refresh fails again before any refresh succeeded
- **THEN** the gate reopens with an interval longer than the one before the
  close, and the interval returns to idle only after a base refresh
  succeeds

#### Scenario: Probe intervals grow with jitter and stop at the cap
- **WHEN** eight consecutive probes fail on virtual time
- **THEN** the intervals between them grow from the idle interval toward the
  cap, no two are identical, and none exceeds the cap

#### Scenario: A clone-served success after a close keeps the grown interval
- **WHEN** the gate closes on a successful probe, the next claim's base is a
  commit SHA the clone already holds, and the base refresh after that fails
  against the remote
- **THEN** the gate reopens with an interval longer than the one before the
  close, the remote's last successful contact still names the closing probe,
  and only a later refresh that contacted origin returns the interval to
  idle

#### Scenario: Probes make no tracker call
- **WHEN** the gate is open and the daemon probes the remote
- **THEN** each probe is a reachability check of the remote alone, and no
  probe sends a tracker request

#### Scenario: Slot signals come from the base read, not the outcome
- **WHEN** a slot's base refresh returns, failed or successful, and the
  slot's task later ends in any terminal result
- **THEN** the gate receives the refresh's signal at the instant the base
  read returned, and nothing is signaled from the task's terminal result

#### Scenario: A restart forgets the gate
- **WHEN** the daemon restarts while its remote outage gate is open
- **THEN** the new process starts with the gate closed and opens it again
  only on its own next base-refresh failure

#### Scenario: One gate read covers every condition
- **WHEN** any condition but the remote is open and the feed's poll yields a claim candidate
- **THEN** the feed abandons its permit with no tracker claim call, exactly as it does for the open remote gate

#### Scenario: A condition's probe is its own check
- **WHEN** the image-contract condition is open and the operator rebuilds the image on the right contract
- **THEN** the next probe reads the image's labels through the same check that refused it, the condition closes, and only then does the feed claim a task

#### Scenario: A held feed asks the tracker nothing
- **WHEN** a condition is open for an hour and the tracker holds Ready tasks
- **THEN** no tracker read is made by the feed in that hour, the feed waits the idle interval between cycles, and its view reads idle-blocked

#### Scenario: A drain stops while the feed is held
- **WHEN** `serve --drain` runs while a condition is open and the tracker holds Ready tasks
- **THEN** the feed claims nothing, the running slots finish, and the process exits as on an empty queue

#### Scenario: A probe that cannot answer is a failed probe
- **WHEN** a condition is open and its probe throws instead of answering (the image inspection refused because the tag is gone, a git binary missing)
- **THEN** the feed thread survives and steps again, the condition stays open with the failure's words as its last error, the next probe waits for the grown interval, and the failure's trace is logged once at DEBUG in the probe streak

#### Scenario: A container start is the box git floor's probe cadence
- **WHEN** the box-git-floor condition is open for an hour
- **THEN** every probe is one one-shot container running the image's `git --version`, the intervals grow to the cap, and no box, volume or network is created

### Requirement: Gate transitions are the outage's log and snapshot signal
Opening a condition SHALL log one WARN with an operator-event code naming the condition, the cause and the fix; closing SHALL log one INFO recovery line with the duration and probe count; failures and probes in between SHALL be DEBUG with a periodic roll-up. A condition open for at least a configured duration SHALL log ERROR once with its own event code. The serve snapshot SHALL carry a `conditions` section (one entry per condition: state, open since, last error, next probe time, consecutive failures) beside `tracker`; the remote's entry is the former `remote` section.
**Implements**: NFR-O1, NFR-O3, UX6 of add-base-ref-resolution; NFR-O2, UX4 of add-sandbox-base-image

#### Scenario: One WARN, one recovery line, roll-ups between
- **WHEN** the remote is unreachable for an hour and then returns
- **THEN** the log carries exactly one WARN on open and one INFO on close,
  every probe in between is DEBUG or a roll-up, and the ERROR line appears
  once only if the outage lasted at least the sustained-open duration

#### Scenario: Blocked on the remote is readable without logs
- **WHEN** an operator opens the dashboard during an outage
- **THEN** the `remote` section shows the gate open, since when, the last
  error, and the next probe time

#### Scenario: A precondition condition logs the fix once
- **WHEN** the clone's ref format refuses under a running daemon and is migrated an hour later
- **THEN** the log carries exactly one WARN naming the condition and the migration remedy, one INFO on close, no uncaught-crash event, and every refusal in between is DEBUG or a roll-up

#### Scenario: An open precondition is a dashboard field
- **WHEN** an operator opens the dashboard while the container-runtime condition is open
- **THEN** the `conditions` section names that condition open, since when, the last cause and the next probe time, beside the remote's entry

### Requirement: Serve validates the default branch at startup and binds law per task
At startup the daemon SHALL load and validate the full definition from the
refreshed repository default branch and exit on a load error, exactly as
before; that definition supplies the trusted configuration tier and the
`board`/`dashboard` view. Each slot SHALL bind its task's law from the task's
law commit after base resolution.
**Implements**: FR13, UX5 of add-base-ref-resolution
**Notes**: A base that fails to load parks rather than returning to Ready because the failure is deterministic: a retry loop would only repeat it.

#### Scenario: Two bases, one broken
- **WHEN** serve holds tasks resolving to `develop` (valid `.gnomish/`) and
  `release/1.18` (invalid `.gnomish/`)
- **THEN** the `develop` task runs, the `release/1.18` task parks with the
  located errors and the base ref in its report, and the daemon keeps running

#### Scenario: A broken task base is parked, never released
- **WHEN** a slot's task base fails to load
- **THEN** the task parks with a configuration report and is not released
  back to `Ready`; the slot returns to the feed and the other slots are
  unaffected

#### Scenario: A broken default branch stops startup
- **WHEN** `serve` starts and the full definition on the refreshed default
  branch fails to load
- **THEN** the daemon exits with the load error before anything is claimed

### Requirement: Daemon workers treat an interrupt as a stop, not a failure
Every daemon site that catches a failed tracker call and would otherwise
report or account for it as a tracker failure — the reaper's sweep listing,
the feed's decline of finished tasks, the tracker health counter, and the
claim heartbeat's beat — SHALL first check whether its own thread's interrupt
is set. If it is, the failure SHALL be treated as the stop that caused it,
not as a tracker failure.
**Implements**: FR12, NFR-O2 of fix-operator-blockers
**Notes**: This is how the stop-caused interrupts of the "SIGTERM stops cleanly within grace" requirement are classified on these paths.

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

### Requirement: Serve renders the dashboard in-process when enabled
With the dashboard enabled, `serve` SHALL render the same page `gnomish
dashboard --watch` renders for the same instance: same blocks, cadences and
default output path, `--dashboard-out` overriding the path. It SHALL print the
page's absolute path before the first claim and record the setting in the
start anchor. A standalone `gnomish dashboard` SHALL keep working unchanged.
**Implements**: FR9, UX1, NFR-O2 of supervise-daemon-loops-and-embed-dashboard

#### Scenario: Default path
- **WHEN** `gnomish serve --dashboard` starts for project `widgets` with the
  default instance name
- **THEN** it prints the path `GNOMISH_HOME/projects/widgets/serve/default/dashboard.html`
  and the page appears there within one render cadence

#### Scenario: Standalone dashboard unaffected
- **WHEN** a daemon runs without the dashboard and an operator runs
  `gnomish dashboard --watch` in another process
- **THEN** the page renders exactly as before this change

### Requirement: The embedded dashboard never harms the daemon
A failure of the embedded dashboard of any kind, including render failures,
file write failures, tracker failures of its board reads, and its loop being
disabled, SHALL NOT stop, delay or fail task work, the feed, or the daemon's
other loops. Its board reads SHALL NOT count as failures of the daemon's
tracker.
**Implements**: NFR-R1, FR10 of supervise-daemon-loops-and-embed-dashboard

#### Scenario: Board outage is not a daemon tracker failure
- **WHEN** every board read of the embedded dashboard fails for ten minutes
  while the daemon's own tracker calls succeed
- **THEN** the snapshot's tracker `consecutiveFailures` stays 0

#### Scenario: Disabled dashboard, working daemon
- **WHEN** the embedded dashboard's loop dies often enough to be disabled
- **THEN** one ERROR says so and the daemon keeps claiming and completing tasks

### Requirement: The embedded dashboard reads the configuration serve bound
The embedded dashboard SHALL read the tracker binding and the WIP limit only
from the configuration `serve` bound at startup from origin's default branch,
never from the checkout, and SHALL add no tracker reads beyond one ready
listing and one open listing per board interval.
**Implements**: FR10, NFR-S1, NFR-P1 of supervise-daemon-loops-and-embed-dashboard

#### Scenario: Checkout differs from origin
- **WHEN** the clone's checked-out `.gnomish/config.yaml` sets `wip-limit: 3`
  while origin's default branch sets `wip-limit: 10`, and `serve --dashboard` runs
- **THEN** the page's WIP stat shows the limit 10

#### Scenario: Tracker reads bounded
- **WHEN** `serve --dashboard` runs for an hour with default cadences
- **THEN** the dashboard performs at most one `listReady` and one `listOpen`
  per board interval

### Requirement: Shutdown stops every supervised loop and renders a final page
The shutdown sequence SHALL stop the worktree cleaner, the sandbox sweep tick
and the standing reaper before it waits out the grace window, and the snapshot
writer at its final `stopped` write. With the dashboard enabled, it SHALL then
render the page once more, so the page shows the stopped state. A second pass
SHALL change nothing.
**Implements**: FR6, FR11, UX2 of supervise-daemon-loops-and-embed-dashboard

#### Scenario: Page after Ctrl-C
- **WHEN** `serve --dashboard` receives SIGINT and finishes its shutdown
- **THEN** the page file's last render shows the stopped state and reason

#### Scenario: No cleaner run during drain
- **WHEN** SIGTERM arrives and slots are still releasing within the grace window
- **THEN** no worktree cleaner or sandbox sweep run starts after the stop

### Requirement: Serve assesses operator preconditions at startup
Before any tracker claim, `serve` SHALL run the checks a slot runs for the operator preconditions whose answer does not depend on a task — the factory clone's ref format, the sandbox plan, the image contract, the box's git floor — in the order a slot meets them, and SHALL exit as a startup failure on the first refusal, printing that check's own message. The assessment SHALL write nothing to the tracker, the clone or the container runtime.
**Implements**: FR13, NFR-R3, UX3, G6 of add-sandbox-base-image
**Notes**: The factory's own git version check is the precedent; the per-task checks stay for what the operator can change under a running daemon (design D17).

#### Scenario: A clone on the files backend refuses the daemon at startup
- **WHEN** `serve` starts on a factory clone whose ref backend is `files` and the tracker holds a `Ready` task
- **THEN** the process exits as a startup failure with the ref-format refusal and its migration remedy, and the tracker shows no claim and no working label

#### Scenario: An unreachable container runtime refuses the daemon at startup
- **WHEN** `serve` starts with a container-bound pipeline while the container runtime is unreachable
- **THEN** the process exits as a startup failure naming the runtime, before the label-provisioning smoke test runs

#### Scenario: A stale image refuses the daemon at startup
- **WHEN** `serve` starts with a sandbox image whose contract label is missing or names another version
- **THEN** the process exits as a startup failure printing the contract refusal with its `FROM` line, and no box was created

#### Scenario: Preconditions that hold let startup continue unchanged
- **WHEN** every precondition holds
- **THEN** startup proceeds to the label-provisioning smoke test and the assessment left no trace: no claim, no clone write, one container start at most (the floor probe)

### Requirement: A precondition refusal in a slot or the janitor is a daemon condition
A typed operator-precondition refusal met after a claim SHALL end the claimed run with a typed result that reaches every sink a terminal result reaches, SHALL release the claim with no abort marker, backoff or fuse charge, and SHALL open that precondition's condition. The janitor's branch pass SHALL report the same refusals to their condition and continue. Only a typed refusal SHALL open a condition.
**Implements**: FR14, FR15, FR17, NFR-R2, NFR-O3, G7 of add-sandbox-base-image
**Notes**: The refusals are the clone's ref format, the container runtime (met first by the sandbox plan), the box's git floor and the image contract; anything untyped stays a crash with its abort accounting (design D18, D19, D21).
**Notes**: The release is the lease path's (the reaper returns the task after TTL) until `add-claim-return` lands; the open condition is what stops the feed from claiming the task again.

#### Scenario: A slot's ref-format refusal is a result, not a crash
- **WHEN** the operator migrates the factory clone to the `files` backend under a running daemon and the next slot's first touch of the clone refuses it
- **THEN** the slot's outcome log, the drain report and the ledger each record the typed precondition result, no uncaught-crash event is logged, the claim is released, and the task's abort facts read zero

#### Scenario: One claim per free slot for the whole episode
- **WHEN** a precondition is broken under a three-slot daemon for an hour and then fixed
- **THEN** the tracker sees at most three claims and three releases from the episode, none after the condition opened, and the daemon is still running

#### Scenario: The janitor's refusal opens the condition and the pass continues
- **WHEN** the janitor's branch pass meets the ref-format refusal on one branch
- **THEN** the condition opens, the pass logs no failed-tick event, the remaining branches are visited, and the next tick names the condition instead of refusing again at WARN

#### Scenario: The container runtime stops under a running daemon
- **WHEN** the container runtime becomes unreachable while `serve` runs a container-bound pipeline and the next slot's sandbox plan meets it
- **THEN** the slot ends with the typed precondition result, no uncaught-crash event is logged, the claim is released, the container-runtime condition opens, and no task is claimed until its probe passes

#### Scenario: An unclassified failure opens no condition
- **WHEN** a slot's run dies with a runtime exception no arm classifies
- **THEN** no condition opens, the crash arm runs the abort protocol, and the feed keeps claiming

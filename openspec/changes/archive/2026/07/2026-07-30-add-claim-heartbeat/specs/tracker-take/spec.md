# tracker-take — delta

## MODIFIED Requirements

### Requirement: Explicit-mode disposition by task state
`take <ref>` SHALL act as an operator mandate per the task's logical state:
`Ready` (or readiness criterion unmet) → claim and work, overriding the readiness
criterion and abort backoff without resetting the abort counter — resuming from
the branch outcome when one is recorded (a pending reply is collected and
acknowledged; a recorded `DecisionNeeded` with no reply parks again restating
the question; any other recorded outcome continues on the return alone);
`AwaitingHuman` (any reason) → refuse, naming the pending report and the return
path (reply if needed, move the task back to ready); `Working` held by another
instance → the takeover path: show the claim facts (holder, age of the last
beat) and require explicit confirmation — a TTY dialog, or the dedicated
takeover flag when headless; confirmed → remove the old claim via the
stale-claim removal (audit marker, dead claim deleted), then claim normally
and resume from the branch; unconfirmed → refuse with an error naming the
holder, changing nothing. This confirmation is the one deliberate deviation
from "identical with and without a TTY": a pre-claim gate, never an in-run
wait. `Finished` → skip reporting "already done"; `Gone` (closed or
nonexistent) → skip with a clear error.
<!-- implements FR9 of add-tracker-port -->
<!-- implements FR6 of add-claim-heartbeat -->

#### Scenario: Mandate overrides readiness and backoff
- **WHEN** `take <ref>` targets an open task without the ready label and with
  unexpired abort backoff
- **THEN** the task is claimed and worked, and the abort counter is not reset by
  the mandate itself

#### Scenario: Parked task is refused
- **WHEN** `take <ref>` targets a task in `AwaitingHuman`
- **THEN** the run refuses, restating the parked report and telling the operator
  to reply (if a question is pending) and move the task back to ready

#### Scenario: Held task shows facts and asks
- **WHEN** `take <ref>` targets a task claimed by instance B with a TTY
  attached
- **THEN** the run prints the holder and the age of the last beat and asks for
  confirmation; declining leaves the tracker untouched and exits as a refusal
  naming instance B

#### Scenario: Confirmed takeover resumes the task
- **WHEN** the operator confirms the takeover of a task whose holder died
  mid-work
- **THEN** the thread records the holder-transition marker, the old claim is
  removed, the run claims the task by the ordinary lease, and work resumes
  from the branch state

#### Scenario: Headless takeover needs the flag
- **WHEN** `take <ref>` targets a `Working` task without a TTY and without the
  takeover flag
- **THEN** the run refuses naming the holder and mentioning the flag; with the
  flag it proceeds as a confirmed takeover

#### Scenario: Finished task is skipped
- **WHEN** `take <ref>` targets a delivered task
- **THEN** the run reports it as already done and does not resume it

### Requirement: Operator guide
The change SHALL ship an operator guide (`docs/operator-guide.md`) covering: quick
start (tracker config section, token env variable, factory config layers), handing
off a task via the ready label and automatic label provisioning, the label
dictionary with who moves what, the escalation/decision/ack flow (reply, return
to ready, re-run), snapshot behavior (issue edits do not affect a taken
task; influence via decisions or revoke-and-recreate), stuck-`Working`
recovery — automatic reaping whenever an instance with a live claim is
running, the confirmed `take <ref>` takeover with its headless flag, and the
honest limitation that one-shot cron runs cannot observe longer than TTL so
cron-only operation keeps the manual label flip until `serve` exists — the
heartbeat/TTL settings with the shared write-budget coupling (beat interval ×
concurrent tasks vs the shared token's write limits), Projects v2 boards as a
display-only parallel universe with the shipped reference "column → ready label"
cron workflow (`docs/examples/board-bridge.yml`), the fork warning ("fix
`tracker.repo`"), and the `take` CLI reference with exit behavior.
<!-- implements FR19 of add-tracker-port -->
<!-- implements FR6 of add-claim-heartbeat -->
<!-- implements NFR-P1, UX2, UX3 of add-claim-heartbeat -->

#### Scenario: Guide covers the operator surface
- **WHEN** an operator follows the guide against a fresh repository
- **THEN** every step from configuration to first delivered task and first
  escalation round-trip is described without reference to factory source code

#### Scenario: Guide states when recovery is automatic
- **WHEN** an operator reads the stuck-`Working` section
- **THEN** it distinguishes automatic reaping (long-lived runs), explicit
  takeover (any time, confirmed), and the cron-only manual escape hatch, and
  names the write-budget consequence of shortening the beat interval

## ADDED Requirements

### Requirement: Take runs the heartbeat thread and the reaper duty
A take run SHALL start the instance heartbeat thread at its first successful
claim and stop it when no claim is held (terminal result reached or claim
lost). While running, the thread beats every held claim on the configured
interval and performs the reaper duty each tick: list open tasks, update
observations, remove stale claims. Reaping is a byproduct of holding a claim —
a take run whose task outlives a foreign TTL returns that foreign task to
circulation.
<!-- implements FR1, FR4 of add-claim-heartbeat -->

#### Scenario: Beat starts with the claim
- **WHEN** bare `take` claims the queue head
- **THEN** the claim comment starts receiving beats within one interval, until
  the run reaches its terminal result

#### Scenario: Long run reaps a neighbor
- **WHEN** a take run works a multi-hour task while another instance died
  holding a claim
- **THEN** before the run ends, the dead claim is removed and its task is
  `Ready` — unclaimed by the reaping run

### Requirement: Reconcile precedes resume
Every claim of a task with an existing branch SHALL begin with the reconcile
check: when the branch records a terminal outcome whose tracker counterpart is
missing (finish or park never landed), the run SHALL complete the deferred
tracker write and end — executing no stage. Only when branch and tracker
agree does the ordinary resume (decision collection, engine run) proceed.
<!-- implements FR10 of add-claim-heartbeat -->
<!-- implements NFR-C1 of add-claim-heartbeat -->

#### Scenario: Deferred finish is delivered, not re-run
- **WHEN** a take run claims a task whose branch records `Completed` but whose
  tracker state is still open after its holder died mid-outage
- **THEN** the run posts the final report, transitions the task to `Finished`,
  and exits with the delivery exit code without invoking any executor

### Requirement: Terminal tracker writes retry against an outage
When a terminal outcome (finish, park, abort record) cannot be written because
the tracker is unreachable, the run SHALL keep the outcome durable in the
branch and retry the write with backoff for a bounded period before giving up
with an ERROR naming the unreconciled state; the reconcile-on-resume closes
the gap whenever the process dies or gives up first. Abort-path writes remain
best-effort as before — a dead tracker never blocks the abort itself.
<!-- implements FR10 of add-claim-heartbeat -->
<!-- implements NFR-R3 of add-claim-heartbeat -->

#### Scenario: Finish lands after the outage
- **WHEN** the tracker recovers while a completed run is still retrying its
  final write
- **THEN** the delivery completes normally with the final report, and the
  thread shows nothing unusual beyond the beat gap

#### Scenario: Give-up past the bound names the unreconciled state
- **WHEN** the tracker stays unreachable until the retry bound elapses, so a
  finish or park write is given up as deferred
- **THEN** the run logs exactly one ERROR naming the task and its unreconciled
  tracker-write state (the pending finish, or the "tracker-write pending" park),
  keeps the outcome durable in the branch, and returns the mapped terminal
  result for reconcile-on-resume to close later

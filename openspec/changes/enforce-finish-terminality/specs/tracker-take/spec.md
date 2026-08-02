# tracker-take — delta

## MODIFIED Requirements

### Requirement: Explicit-mode disposition by task state
`take <ref>` SHALL act as an operator mandate per the task's logical state:
`Ready` (or readiness criterion unmet) → claim and work, overriding the readiness
criterion and abort backoff without resetting the abort counter — resuming from
the branch outcome when one is recorded (a pending reply is collected and
acknowledged; a recorded `DecisionNeeded` with no reply parks again restating
the question; any other recorded outcome continues on the return alone) —
UNLESS the task's history carries a finish report: then the mandate is
refused, the decline protocol runs (terminal status restored, explanation
posted directing the operator to open a new task or bug), and the run exits
with a clear non-success outcome;
`AwaitingHuman` (any reason) → refuse, naming the pending report and the return
path (reply if needed, move the task back to ready);
`Working` held by another
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
<!-- implements FR5 of enforce-finish-terminality -->

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

#### Scenario: Reopened finished task is declined, not worked
- **WHEN** `take <ref>` targets a task a human moved back to ready after it
  was finished
- **THEN** the run refuses even under the explicit mandate: the terminal
  status is restored, the explanation comment is posted, and the exit
  outcome clearly signals the refusal

### Requirement: Bare auto mode takes the head of the queue
Bare `gnomish take` SHALL fetch the ready queue via `listReady`, hide tasks
whose abort backoff (exponential from base, capped; computed by core from
adapter abort facts) has not expired, exclude finished tasks entirely —
declining each via the decline protocol instead of claiming — prefer
returned tasks over fresh ones, respect the WIP limit for fresh tasks
(claimed only while open fronts < W; returned tasks always claimable), claim
from the head zone — a random pick among the first K eligible, oldest-first
as a soft preference — process exactly one task to its terminal result, and
exit. An empty or fully blocked queue SHALL be a clean no-op run naming the
reason (nothing eligible, or the WIP limit). Losing the claim race SHALL
fall through to the next eligible task.
<!-- implements FR10 of add-tracker-port -->
<!-- implements NFR-C1 of add-tracker-port -->
<!-- implements FR6, FR9 of add-factory-serve -->
<!-- implements FR3 of enforce-finish-terminality -->

#### Scenario: One task per run
- **WHEN** the queue holds three ready tasks and bare `take` runs
- **THEN** exactly one task from the head zone is processed and the process
  exits after its terminal result

#### Scenario: Backoff hides a task
- **WHEN** the queue head aborted moments ago and its backoff has not expired
- **THEN** bare `take` claims the next eligible task instead

#### Scenario: WIP limit blocks a fresh start
- **WHEN** open fronts equal W and only fresh tasks are ready
- **THEN** bare `take` exits as a clean no-op naming the WIP limit

#### Scenario: Returned task preferred
- **WHEN** the queue holds an older fresh task and a younger returned task
- **THEN** bare `take` claims the returned task

#### Scenario: Finished task never claimed from the feed
- **WHEN** the queue lists a reopened finished task ahead of a fresh task
- **THEN** bare `take` declines the finished task and claims the fresh one

# tracker-take

## Purpose

The `gnomish take` CLI: single-task tracker modes (explicit ref and bare
auto-take), the disposition matrix, snapshot at claim, tracker-driven resume
with decision collection, abort handling with the K fuse, revocation at round
boundaries, delivery, and the operator guide.

## ADDED Requirements

### Requirement: take subcommand surface
`gnomish take` SHALL be a separate subcommand, always in git mode, with two forms:
`take <ref>` (explicit mode) and bare `take` (auto mode). Supported flags:
`--dir`, `--interactive[=executor|judge]`, `--base` (explicit-mode start only),
`--discard-work` (resume with diverged branches). `take` SHALL have no `--mode`,
no ad-hoc source flags (`--task`, `--task-file`, `--task-id`, `--resume`), and no
`--from-stage`; the bare form SHALL reject start modifiers (`--base`). The
`gnomish run` flag matrix SHALL remain unchanged. Short refs (`42`, `#42`) expand
via the configured binding; a full canonical id naming a foreign repo is an error
(subject to the adapter's rename tolerance).
<!-- implements FR9 of add-tracker-port -->

#### Scenario: Flag validation
- **WHEN** `take` is invoked with `--mode`, `--task`, `--resume`, or bare `take`
  with `--base`
- **THEN** each invocation fails with a validation error before touching the
  tracker

#### Scenario: Short ref expansion
- **WHEN** the operator runs `take 42` with a configured GitHub binding
- **THEN** the run targets the canonical id built from the binding and issue 42

### Requirement: Explicit-mode disposition by task state
`take <ref>` SHALL act as an operator mandate per the task's logical state:
`Ready` (or readiness criterion unmet) → claim and work, overriding the readiness
criterion and abort backoff without resetting the abort counter — resuming from
the branch outcome when one is recorded (a pending reply is collected and
acknowledged; a recorded `DecisionNeeded` with no reply parks again restating
the question; any other recorded outcome continues on the return alone);
`AwaitingHuman` (any reason) → refuse, naming the pending report and the return
path (reply if needed, move the task back to ready); `Working` held by another
instance → refuse with an error naming the holder; `Finished` → skip reporting
"already done"; `Gone` (closed or nonexistent) → skip with a clear error.
<!-- implements FR9 of add-tracker-port -->

#### Scenario: Mandate overrides readiness and backoff
- **WHEN** `take <ref>` targets an open task without the ready label and with
  unexpired abort backoff
- **THEN** the task is claimed and worked, and the abort counter is not reset by
  the mandate itself

#### Scenario: Parked task is refused
- **WHEN** `take <ref>` targets a task in `AwaitingHuman`
- **THEN** the run refuses, restating the parked report and telling the operator
  to reply (if a question is pending) and move the task back to ready

#### Scenario: Held task is refused
- **WHEN** `take <ref>` targets a task claimed by instance B
- **THEN** the run refuses with an error naming instance B and changes nothing in
  the tracker

#### Scenario: Finished task is skipped
- **WHEN** `take <ref>` targets a delivered task
- **THEN** the run reports it as already done and does not resume it

### Requirement: Bare auto mode takes the head of the queue
Bare `gnomish take` SHALL fetch the ready queue via `listReady`, hide tasks whose
abort backoff (exponential from base, capped; computed by core from adapter abort
facts) has not expired, claim the head, process exactly one task to its terminal
result, and exit. An empty queue SHALL be a clean no-op run. Losing the claim race
for the head SHALL fall through to the next eligible task.
<!-- implements FR10 of add-tracker-port -->
<!-- implements NFR-C1 of add-tracker-port -->

#### Scenario: One task per run
- **WHEN** the queue holds three ready tasks and bare `take` runs
- **THEN** exactly one task (the oldest eligible) is processed and the process
  exits after its terminal result

#### Scenario: Backoff hides a recently aborted task
- **WHEN** the queue head aborted moments ago and its backoff has not expired
- **THEN** bare `take` skips it and claims the next eligible task

#### Scenario: Empty queue
- **WHEN** no task is ready
- **THEN** the run exits cleanly reporting an empty queue, having claimed nothing

### Requirement: Snapshot at first claim
At the first claim of a task the factory SHALL read id/title/body once into
`TaskContext` and persist them in `task.json`. Later issue edits SHALL NOT affect
the running or parked task; resume SHALL NOT re-read the snapshot — it collects
decisions only. Status output takes the title from the snapshot.
<!-- implements FR11 of add-tracker-port -->

#### Scenario: Issue edited mid-task
- **WHEN** a human edits the issue body while the task is `Working` or parked
- **THEN** the gnome's context and `task.json` retain the claim-time snapshot,
  and resume proceeds from the snapshot plus collected decisions

### Requirement: Decision consumption always leaves an ack
At resume claim the factory SHALL collect decisions posted after the last ack;
consuming a decision SHALL post an "acting on decision: <text>" ack comment
before acting. The ack records which reply the factory acted on and anchors
future decision collection.
<!-- implements FR12 of add-tracker-port -->

#### Scenario: Ack precedes acting
- **WHEN** a resumed run consumes a human reply
- **THEN** the tracker shows the "acting on decision" ack before any further
  work is recorded

#### Scenario: Reply consumed on resume
- **WHEN** an instance claims a returned task whose thread holds a reply after
  the last ack
- **THEN** the reply is acknowledged and recorded as the decision driving the
  resumed run

### Requirement: Escalation parks and exits
An escalation SHALL end the take run identically with or without a TTY: park
the task with its report, then exit telling the operator where the question is
and how to return the task ("reply in the tracker and move the task back to
ready"). There is no in-run decision wait. A resume claim that finds a recorded
`DecisionNeeded` outcome and no pending reply SHALL park the task again with
the question restated.
<!-- implements FR13 of add-tracker-port -->

#### Scenario: Escalation ends the run
- **WHEN** a take run escalates while a TTY is attached
- **THEN** the task is parked with the report and the run exits with the
  return-path message — no console prompt is opened

#### Scenario: Returned without an answer
- **WHEN** a human moves a `DecisionNeeded`-parked task back to ready without
  replying and a take run claims it
- **THEN** the task is parked again with the question restated

### Requirement: Abort protocol with a K fuse
An infrastructure abort is either an engine `Aborted` outcome (durable persist
failed) or an uncaught exception of the take run itself. On an infrastructure
abort the factory SHALL log ERROR, then best-effort: post the
structural abort comment, release the claim, and return the task to `Ready` via
`recordAbort` — a dead tracker never blocks the abort itself. When the
consecutive-abort count (shared across instances via tracker facts) reaches the
configured `abort-threshold` K, the factory SHALL instead park the task as
`AwaitingHuman(infra)` with an infrastructure report carrying the abort history.
The counter resets on the first durably persisted round after claim.
<!-- implements FR14 of add-tracker-port -->
<!-- implements NFR-R2 of add-tracker-port -->
<!-- implements NFR-C1 of add-tracker-port -->

#### Scenario: Abort below the fuse
- **WHEN** a run aborts with prior abort count K−2
- **THEN** the task returns to `Ready` with a structural abort comment and the
  slot-free process exits

#### Scenario: Fuse trips at K
- **WHEN** a run aborts and the shared count reaches K
- **THEN** the task is parked as `AwaitingHuman(infra)` with the abort history of
  all instances in the report

#### Scenario: Runner crash is an abort
- **WHEN** the take run dies with an uncaught exception mid-run
- **THEN** the best-effort abort protocol runs: structural abort comment, claim
  released, task back to `Ready` (or parked at the fuse threshold)

#### Scenario: Progress resets the counter
- **WHEN** a claim is followed by a durably persisted round and later an abort
- **THEN** the abort counter starts again from one

### Requirement: Revocation detected at round boundaries
After every durably persisted round the factory SHALL verify the task is still
ours and alive in one tracker query — not closed, claim intact, state not changed
by a human. On revocation, it SHALL salvage-commit uncommitted work, best-effort
push, post a structural "work stopped" note, release the claim, leave the tracker
state untouched, and keep the branch and worktree. Revocation SHALL surface as a
runner-level result, not as an engine `TaskOutcome`.
<!-- implements FR15 of add-tracker-port -->

#### Scenario: Issue closed under a working gnome
- **WHEN** a human closes the issue while a round is executing
- **THEN** at the next round boundary the run stops, salvages and pushes the work,
  posts the stop note, releases the claim, and reports the revocation result

### Requirement: Delivery posts a final report and ends the factory's involvement
On engine `Completed` the factory SHALL transition the task to `Finished` with a
final report comment rendered from the status-report model (stages with attempts
and results, cumulative usage, branch reference, wall time) and SHALL never touch
the task again — re-running a finished task is a new task, not a resume. All
lifecycle actions SHALL be logged with the canonical task id in MDC.
<!-- implements FR18 of add-tracker-port -->
<!-- implements NFR-O1 of add-tracker-port -->

#### Scenario: Full delivery
- **WHEN** the engine completes the last stage
- **THEN** the tracker shows the delivered state and a final report comment, and
  subsequent runs treat the task only per the disposition matrix ("already done")

### Requirement: Any instance can pick up a returned task
Resume of a returned task SHALL require nothing instance-local: claim, decisions,
abort facts, and reports live in the tracker; work artifacts and state live on the
task branch. A different instance than the one that escalated SHALL be able to
claim, collect the decision, and continue from the recorded pipeline position.
<!-- implements NFR-R3 of add-tracker-port -->

#### Scenario: Cross-instance resume
- **WHEN** instance A escalates a task and instance B runs `take <ref>` after a
  human reply and return to ready
- **THEN** B claims, collects the reply, acknowledges it, and resumes from the
  branch state without any data from A

### Requirement: Exit codes by take result
`gnomish take` SHALL exit with: 0 — Delivered, or a clean bare-mode no-op
(empty queue); 1 — failure outside a claimed run (tracker unreachable at
startup, label provisioning); 2 — usage error; 3 — pipeline load failure;
10 — parked as escalation; 11 — parked as checkpoint; 12 — infrastructure
abort below the fuse; 13 — parked as infra (fuse trip or infrastructure
escalation); 14 — revoked; 15 — refused or skipped (held by another instance,
already done, closed or nonexistent, foreign repo). Codes shared with
`gnomish run` SHALL keep the same meaning. An uncaught exception follows the
abort protocol and exits 12 or 13, never a bare 1.
<!-- implements FR9 of add-tracker-port -->
<!-- implements FR10 of add-tracker-port -->
<!-- implements FR15 of add-tracker-port -->

#### Scenario: Empty queue exits clean
- **WHEN** bare `take` finds no eligible ready task
- **THEN** the process exits 0 reporting an empty queue

#### Scenario: Escalation park exit
- **WHEN** a take run parks its task as an escalation
- **THEN** the process exits 10

#### Scenario: Refusal exit
- **WHEN** `take <ref>` refuses a task held by another instance
- **THEN** the process exits 15 naming the holder

### Requirement: Operator guide
The change SHALL ship an operator guide (`docs/operator-guide.md`) covering: quick
start (tracker config section, token env variable, factory config layers), handing
off a task via the ready label and automatic label provisioning, the label
dictionary with who moves what, the escalation/decision/ack flow (reply, return
to ready, re-run), snapshot behavior (issue edits do not affect a taken
task; influence via decisions or revoke-and-recreate), the stuck-`Working` escape
hatch (manual label flip until heartbeat exists), Projects v2 boards as a
display-only parallel universe with the shipped reference "column → ready label"
cron workflow (`docs/examples/board-bridge.yml`), the fork warning ("fix
`tracker.repo`"), and the `take` CLI reference with exit behavior.
<!-- implements FR19 of add-tracker-port -->

#### Scenario: Guide covers the operator surface
- **WHEN** an operator follows the guide against a fresh repository
- **THEN** every step from configuration to first delivered task and first
  escalation round-trip is described without reference to factory source code

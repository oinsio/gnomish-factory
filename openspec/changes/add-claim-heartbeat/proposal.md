# Proposal: add-claim-heartbeat

> First of the two factory-loop changes; `add-factory-serve` builds on this one.

## Why

A claim today is a static fact: an instance that dies mid-task leaves its task
in `Working` forever, and the only recovery is the operator manually flipping
labels (the documented escape hatch). Before a continuous factory daemon can
exist — and already for today's multi-hour `take` runs — a claim must be a
lease maintained over time: heartbeat while alive, staleness detection,
automatic return of orphaned tasks to circulation, safe explicit takeover, and
fencing against zombie holders. This change gives the existing `take` command
exactly that, closing the stuck-claim footgun.

## What Changes

### ADDED

- Heartbeat: an instance-level thread edits the existing claim comment in place
  on a configured interval for every `Working` task the instance holds — no new
  comments, one write per beat, the comment id (the lease anchor) never
  changes. The beat payload is human-readable progress from engine events
  (stage, attempt, alive-at) — live status in the issue thread for free.
  Beating is the instance's duty, never the gnome's: a gnome blocked for hours
  inside a round is still covered.
- Staleness by local observation: claim version = (comment id, updated-at);
  stale = version unchanged for TTL on the observer's monotonic clock since
  the observer's own first sighting. No cross-instance clock arithmetic; a
  fresh instance cannot declare a foreign claim stale earlier than TTL from
  its first look — a grace period by construction.
- Reaper: a duty of the same heartbeat thread (alive while the instance holds
  any claim). It lists `Working` tasks with claim versions via the port,
  detects stale claims, and returns them to `Ready` — structural
  "stale claim removed" boundary marker (holder-transition audit), deletion of
  the dead claim comment, label flip. It never claims the task for itself;
  concurrent reapers racing on the same stale claim are safe by idempotency.
- Explicit takeover: `take <ref>` on a `Working` task shows the facts (holder,
  age of last beat) and requires explicit confirmation — a TTY dialog, or an
  explicit flag when headless; without confirmation the refusal names the
  holder. The human is the authority (no TTL wait for the operator); mistakes
  are insured by the fence.
- Zombie fencing, two layers: (1) a "claim still ours" check at every round
  boundary and cheaply (conditional read) before unfenced tracker writes
  (park/finish/release); (2) the git non-fast-forward push as the hard fence —
  **the task branch is NEVER force-pushed, by anyone** — the late writer gets
  a persist refusal and takes the normal `Aborted` path.
- Beat-failure taxonomy: network/5xx → WARN and keep working (the round
  boundary decides); 404 "comment gone" → claim lost — stop at the nearest
  round boundary, best-effort push, free the slot, write no tracker state.
- Tracker-outage tolerance: no observation → no TTL progress, so a total
  tracker outage causes no false reaping; feed and beats retry with backoff.
- Terminal-write reconcile: outcomes are durable in the branch before the
  tracker write; on outage the instance holds the task and retries the
  terminal write; resume MUST recognize a terminal outcome already in the
  branch and complete the deferred tracker write instead of replaying work.
- Port growth: one listing of open tasks (`Working` + `AwaitingHuman`) with
  claim versions, the heartbeat write, and the stale-claim removal write
  (shared by reaper and confirmed takeover) — port + in-memory reference +
  GitHub adapter + contract-spec extension, in one pass.
- Config: `tracker` section of `.gnomish/config.yaml` gains the beat interval
  (default 5 min) and the TTL multiplier (integer ≥ 3, default 3 → TTL
  15 min) — protocol constants shared by all instances, read only from the
  factory's own clone.
- Credential scrub for command checks: the command-check runner constructs its
  child environment excluding the tracker-declared credential variables — the
  same declared-scrub-list already applied to the agent launcher (D17).

### MODIFIED

- `gnomish take` disposition matrix: `Working` is no longer a flat refusal —
  it becomes the confirmed-takeover path above. Resume gains the reconcile
  step. The take run starts/stops the heartbeat thread.
- Operator guide: stuck-claim section rewritten — automatic reaping inside
  long-lived runs, explicit takeover, and the honest limitation: a one-shot
  cron `take` cannot observe longer than TTL, so cron-only operators keep the
  manual escape hatch until `serve` exists.

### REMOVED

- Nothing.

## Capabilities

### New Capabilities

- `claim-heartbeat`: the lease-maintenance protocol — heartbeat physics and
  payload, staleness-by-observation, reaper, takeover protocol, zombie
  fencing, outage tolerance, terminal-write reconcile.

### Modified Capabilities

- `tracker-port`: the port grows two operations (open-tasks listing with claim
  versions, heartbeat write); reference adapter and contract spec extended.
- `github-tracker`: physical mapping of the new operations — claim-comment
  PATCH as the beat, label-scoped listing with ETag, claim-comment deletion
  and the boundary marker for takeover.
- `tracker-take`: disposition-matrix change for `Working`, heartbeat thread
  lifecycle in the run, reaper duty, reconcile-on-resume, 404 reaction.
- `pipeline-config`: two new keys in the `tracker` section (beat interval,
  TTL multiplier) with validation.
- `manual-run`: the command runner's environment is no longer fully inherited —
  tracker-declared credential variables are scrubbed.

## Goals

- **G1**: A task held by a dead instance returns to circulation automatically —
  within TTL plus one reaper pass — whenever any instance with a live claim is
  running; no human involved.
- **G2**: A live holder never loses its task to a false takeover: staleness
  cannot fire before TTL from first observation, and a zombie that does come
  back cannot corrupt the branch or the tracker state (fence + pre-write
  checks).
- **G3**: An operator can take over a visibly stuck `Working` task explicitly,
  seeing the facts before confirming, with a full audit trail in the thread.
- **G4**: A tracker outage neither causes false reaping nor loses a finished
  task's outcome — the branch is the source of truth and the tracker catches
  up via reconcile.

## Non-Goals

- **NG1**: Scheduler, slots, `serve`/batch/drain modes, WIP limit, feed
  automaton — all of `add-factory-serve` (the second factory-loop change).
- **NG2**: Heartbeat for anything but `Working` tasks (hold/release slot
  policy was cancelled; parked tasks are not beaten).
- **NG3**: Mid-round cancellation of a running gnome (unchanged from
  tracker-port NG5; a stolen claim is detected within one beat interval, the
  reaction stays at the round boundary).
- **NG4**: Execution sandbox — only the credential scrub ships here; the
  sandbox is a separate future change.
- **NG5**: Making one-shot cron runs reap: staleness needs an observer that
  outlives TTL; the cron scenario keeps the manual escape hatch until `serve`.

## Users & Scenarios

- **U1 — Instance dies mid-task**: a VM is killed with a task in `Working`;
  another instance running a long `take` detects the stale claim after TTL,
  returns the task to `Ready` with an audit marker; a later `take` resumes it
  from the branch.
- **U2 — Operator takes over a stuck task**: `take <ref>` on a `Working` task
  shows "held by instance X, last beat 47 min ago" and asks for confirmation;
  after it, the takeover is recorded in the thread and the task resumes.
- **U3 — Zombie comes back**: an instance freezes, its claim is reaped and the
  task re-claimed; the frozen instance thaws and tries to push — the
  non-fast-forward fence rejects it; it aborts cleanly without touching the
  new holder's tracker state.
- **U4 — Tracker outage at the finish line**: the gnome completes during an
  outage; the outcome is committed to the branch; the instance retries the
  final tracker write — and if it dies first, the next holder's resume posts
  the deferred finish instead of re-running the work.

## Requirements

### Functional

- **FR1**: The heartbeat SHALL be an in-place edit of the existing claim
  comment — one write per beat, comment id unchanged — performed by an
  instance-level thread on the configured interval for every `Working` task
  the instance holds, independent of what the gnome or the slot thread is
  doing; the payload SHALL carry human-readable progress derived from engine
  events (stage, attempt, alive-at). Gnome liveness is out of scope: a hung
  gnome under a live instance is the per-stage `roundTimeout`'s job.
- **FR2**: Staleness SHALL be determined only by local observation: claim
  version = (comment id, updated-at); a claim is stale when its version has
  not changed for TTL on the observer's monotonic clock measured from the
  observer's own first observation. Instance and server clocks SHALL never be
  compared; TTL = multiplier × beat interval.
- **FR3**: The beat interval and TTL multiplier SHALL live in the `tracker`
  section of `.gnomish/config.yaml` (defaults: 5 min, ×3) as protocol
  constants shared by all instances; the multiplier is an integer ≥ 3, so an
  inconsistent beat/TTL pair is inexpressible; both SHALL be read only from
  the factory's own clone, never from anything the gnome can write.
- **FR4**: The reaper SHALL run as a duty of the heartbeat thread: list open
  tasks with claim versions, track observations, and on staleness return the
  task to `Ready` — structural "stale claim removed" marker, deletion of the
  dead claim comment, `Working` → `Ready` — never claiming it for itself.
  Concurrent reapers on the same claim SHALL be safe: both operations are
  idempotent in effect, and subsequent claiming follows the ordinary lease.
- **FR5**: The `Tracker` port SHALL gain exactly three operations: a listing
  of open tasks (`Working` + `AwaitingHuman`) carrying claim versions, the
  heartbeat write, and the stale-claim removal (marker + dead-claim cleanup +
  return to `Ready`, used by both reaper and confirmed takeover); all
  implemented by the in-memory reference and the GitHub adapter and covered
  by the shared contract spec. Observation memory and the TTL policy live in
  core, not in adapters. `listReady` is unchanged.
- **FR6**: `take <ref>` on a `Working` task SHALL show the claim facts
  (holder, age of the last beat) and require explicit confirmation — a TTY
  dialog, or a dedicated flag when headless; without confirmation it SHALL
  refuse, naming the holder. Confirmed takeover uses the same marker/delete/
  flip sequence as the reaper, then claims normally. This is the one
  deliberate deviation from "identical with and without a TTY" (0.2/5.8):
  a pre-claim confirmation, not an in-run wait.
- **FR7**: Zombie protection SHALL be two-layered: the round-boundary check
  extends to "claim still ours"; every tracker write that is not git-fenced
  (park, finish, release) is preceded by a cheap conditional "claim still
  ours" read; and the task branch is NEVER force-pushed by any party — the
  git non-fast-forward refusal is the hard fence, sending the late writer
  down the normal `Aborted` path. The residual TOCTOU window may cost a
  stray label/comment, never data corruption, and converges with the new
  holder's next write.
- **FR8**: Beat failures SHALL be classified, not counted: network/5xx →
  WARN and continue (the next round boundary resolves it); 404 "claim comment
  gone" → the claim is lost — at the nearest round boundary stop, salvage
  push best-effort (the fence arbitrates), free the slot, and write no
  tracker state for a task that is no longer ours.
- **FR9**: During a tracker outage no TTL SHALL elapse (no observation → no
  staleness progress), so an outage of any length causes no false reaping;
  polls and beats retry with backoff and recover when the tracker returns.
- **FR10**: A terminal outcome SHALL be durable in the task branch before the
  corresponding tracker write; while the tracker is down the instance holds
  the task and retries the terminal write with backoff. Resume SHALL begin
  with a reconcile: a terminal outcome recorded in the branch without its
  tracker write means "finish the bookkeeping, do not replay the work".
- **FR11**: The command-check runner SHALL construct the check's process
  environment excluding the credential variables declared by the active
  tracker adapter — the same declared-scrub-list the agent launcher applies;
  full-environment inheritance for command checks ends with this change.

### Non-Functional — Reliability

- **NFR-R1**: No false staleness: under any interleaving, a claim whose
  comment was beaten within TTL is never reaped, and no observer reaps
  earlier than TTL after its own first observation — enforced by the contract
  spec and core unit tests with a controlled clock.
- **NFR-R2**: Reap and takeover SHALL be idempotent and race-safe: two
  instances reaping (or one reaping while an operator takes over) the same
  stale claim converge to one `Ready` task and one eventual new holder.
- **NFR-R3**: All new coordination facts (boundary markers, beat payloads)
  SHALL be recoverable from the tracker by a fresh instance, preserving the
  "no instance-local state needed to resume" property.

### Non-Functional — Performance

- **NFR-P1**: Write economy: one beat = one write; at defaults a working task
  costs 12 writes/hour; the reaper's listing is a conditional (ETag) read per
  beat tick. Steady state stays far inside GitHub's primary and secondary
  limits; the shared-token write budget and its coupling to beat interval are
  named in the operator guide.

### Non-Functional — Observability

- **NFR-O1**: The issue thread SHALL show live progress (the beaten claim
  comment) and a complete holder-transition audit (claim → stale-removed
  marker → new claim), all human-readable — the thread alone still tells the
  task's story. All new actions log with the canonical task id in MDC.

### Non-Functional — Security

- **NFR-S1**: Tracker credentials SHALL be absent from command-check process
  environments (extension of tracker-port NFR-S1 to the second execution
  surface); protocol constants (beat interval, TTL multiplier) SHALL NOT be
  modifiable by the gnome — a gnome must not be able to extend its own TTL.

### Non-Functional — Cost

- **NFR-C1**: Reconcile-on-resume SHALL prevent re-running paid agent rounds
  for a task whose outcome already exists in the branch — a dead tracker or a
  dead instance costs bookkeeping, never a repeated gnome run.

## Operator Experience Criteria

- **UX1**: The claim comment doubles as a live status line — stage, attempt,
  last-alive time — without comment spam in the thread.
- **UX2**: Taking over a stuck task is one confirmed command; the operator
  sees who held it and how stale it is before saying yes, and the thread
  records the transition afterwards.
- **UX3**: The operator guide states plainly when automatic recovery works
  (an instance with a live claim is running) and when the manual escape hatch
  is still needed (cron-only operation, until `serve`).

## Success Metrics

- **M1**: The extended contract spec (listing round-trip, beat versioning,
  reap idempotency) passes against both adapters with zero exemptions.
- **M2**: Simulated death/steal scenarios: stale claim reaped and task
  resumed by another instance in 100% of contract-test runs; double-reap race
  converges safely in 100% of runs.
- **M3**: Zombie fence integration test: of two holders writing the same task
  branch, exactly one push lands; the loser ends in `Aborted` with no force
  push anywhere in the code path.
- **M4**: Reconcile test: branch with a terminal outcome + missing tracker
  write → resume completes the tracker write and runs zero engine rounds.
- **M5**: Coverage and mutation gates per `.claude/rules/testing.md` hold for
  all new production code.

## Open Questions

- **Q1**: Exact port signatures — shape of the open-tasks listing entry and
  the claim-version value object. (design)
- **Q2**: Terminal-write retry policy in a single take: how long the process
  holds the slot against a dead tracker before giving up. (design)
- **Q3**: Name of the headless takeover flag and the exact confirmation
  wording. (design)
- **Q4**: Structural format of the "stale claim removed" boundary marker and
  the beat payload fields. (design)
- **Q5**: Where the reconcile step hooks into the existing resume mechanics
  (tracker-port task 5.6). (design)

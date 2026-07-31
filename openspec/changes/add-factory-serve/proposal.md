# Proposal: add-factory-serve

> Second of the two factory-loop changes; consumes `add-claim-heartbeat` (Change 1) —
> the order is strict: a daemon without claim leasing would leak dead
> instances' claims.

## Why

Today the factory serves a queue one task per invocation: bare `take` claims
the head and exits, and nothing runs unattended — an operator or cron must
fire every run, and one instance never works two tasks at once. With claim
leasing in place (`add-claim-heartbeat`), the missing piece is the frame that
turns the existing take cycle into a continuous factory: a daemon that feeds
itself from the queue, runs N tasks concurrently, respects a project-wide
limit of open work, shuts down cleanly, and cleans up after itself.

The limit is not an accessory: a large ready queue plus a realistic share of
early escalations would otherwise turn an autonomous factory into a
Ready → AwaitingHuman conveyor — hundreds of parked tasks overnight, branches
diverging past mergeability, tokens burnt to the escalation point, and an
unsurveyable human inbox. Backpressure must ship in the same change as
autonomy.

## What Changes

### ADDED

- Scheduler with N slots: one machinery for all multitask modes; the slot
  body is the existing take cycle (claim → run → react to the outcome),
  unchanged. The scheduler never hands one task to two slots. N is instance
  (factory) configuration with a modest default.
- `gnomish serve` — the daemon: continuous queue service until told to stop;
  name chosen for the `run` / `take` / `serve` symmetry.
- Batch mode: `gnomish take <ref> <ref> ...` — the same intent as a single
  explicit take per ref, disposition matrix and skips as specified today,
  plus a final summary and an aggregate exit code (the "tool failed" family
  dominates the "legitimate outcome" family).
- Drain flag on `serve`: "empty queue means exit instead of sleep" — cron
  operation without a long-lived process; occupied slots finish their tasks
  first.
- Feed automaton with four states — Filling (poll → claim → again, no pause),
  Idle-empty, Idle-blocked (WIP limit reached), Full (no polling at all;
  wakes on the local slot-freed event). Claiming happens in the feed, slots
  receive already-claimed tasks; a single idle-poll interval (factory config,
  default ~30 s) covers both Idle states.
- WIP limit W — release-only backpressure: open fronts = count(`Working`) +
  count(`AwaitingHuman`) project-wide; fresh tasks are taken only while
  open < W; returned tasks are outside the limit and prioritized ("stop
  starting, start finishing"); at the limit the daemon says so explicitly.
  W is a protocol constant of the project (`.gnomish/config.yaml` `tracker`
  section, default 10, W ≥ N), shared by all instances.
- Returned-task fact: `ReadyTask` gains an adapter-derived "returned" flag
  (a park marker exists in the task's history) so the feed can prioritize
  fronts that close over fronts that open. The W count reuses the Change 1
  open-tasks listing — no further port operations.
- Multi-instance conduct: poll-phase jitter and claiming from the head zone
  (a random pick among the first K eligible) instead of the strict head —
  an explicit delta to the "takes the head of the queue" contract wording
  (oldest-first becomes a soft preference); in-flight claim attempts are
  capped by free slots, never by queue length.
- Process lifecycle: SIGTERM = stop claiming, stop at round boundaries
  within the grace window, explicitly release surviving claims (no TTL
  wait); anything past grace follows the Change 1 stale-claim path — hard
  death needs no new mechanism. The process group is killed on exit so no
  gnome subprocess outlives the daemon. Restart is a clean start: old claims
  under the old instance id are left to the lease protocol; startup reuses
  label provisioning as a binding smoke test.
- Worktree cleaner: age-based disposal of worktrees left by ended and
  escalated tasks — a localized "dispose of a task's environment by age"
  responsibility (the future sandbox change replaces its inside, not its
  callers); same-instance resume keeps reusing an existing worktree.
- Operator guide: `serve`/batch/drain reference; the shared write budget and
  its coupling to the beat interval (ΣN ≲ 20 concurrent tasks at defaults);
  the WIP method boundary (W limits how many branches are open, not whether
  they conflict — integration discipline stays with the pipeline author);
  the autonomy gate ("who can set `ready` can execute code on the host" —
  never auto-`ready` from untrusted sources); CI hygiene for gnome branches
  (workflows triggered by `gnomish/*` pushes must carry no privileged
  secrets; `GITHUB_TOKEN` read-only).

### MODIFIED

- `gnomish take` grows the varargs batch form; single `take <ref>` and bare
  `take` are otherwise unchanged (no scheduler in the single form).
- Bare-auto claim order: "claim the head" relaxes to "claim from the head
  zone" (soft oldest-first) — shared by bare `take` and the `serve` feed.
- Escalation interactivity: batch and daemon modes are unconditionally
  non-interactive — escalation always parks with a tracker report, never a
  TTY dialog (N tasks share one console). This restates 0.2/5.8; the only
  interactive moment anywhere remains the Change 1 pre-claim takeover
  confirmation of the single explicit `take`.
- Decision revision: the hold/release slot policy is cancelled.
  Its real function — backpressure on unfinished work — is taken over by
  release-only slots plus the global WIP limit; held claims, parked-task
  beats, and the hold/release configuration knob all disappear before ever
  being built.

### REMOVED

- Nothing (the cancelled hold/release policy was never implemented).

## Capabilities

### New Capabilities

- `factory-serve`: the daemon frame — scheduler and slots, run modes and
  their stop conditions, feed automaton, WIP-limit policy, multi-instance
  conduct, process lifecycle, worktree cleanup, operator guidance.

### Modified Capabilities

- `tracker-take`: varargs batch form with summary and aggregate exit code;
  head-zone claim wording for bare auto mode; non-interactive escalation in
  multitask modes.
- `tracker-port`: `ReadyTask` gains the "returned" fact; the open-tasks
  listing is documented as the W-count source (no new operations).
- `github-tracker`: physical mapping of the "returned" fact (park marker in
  the issue history) and of the open-fronts count via label-scoped listing.
- `pipeline-config`: the `tracker` section gains the `wip-limit` key
  (integer ≥ 1, default 10) with validation.

## Impact

- New daemon command and scheduler/feed machinery in the app layer; the
  engine and stage machinery are untouched (the slot body is the existing
  take cycle).
- `Tracker` port surface: one new fact on `ReadyTask`; in-memory reference
  adapter, GitHub adapter, and the shared contract spec extended in one
  pass.
- Config: one protocol key (`wip-limit`) in `.gnomish/config.yaml`; instance
  keys (slots N, idle-poll interval) in factory configuration.
- Consumes `add-claim-heartbeat`: heartbeat thread, reaper, staleness,
  fencing, reconcile-on-resume are prerequisites, used as-is.
- Docs: operator guide gains the serve/batch/drain reference and the
  autonomy-gate and CI-hygiene requirements.

## Goals

- **G1**: One command keeps a project's queue continuously served: ready
  tasks are picked up without human triggering, up to N concurrently per
  instance, across any number of instances.
- **G2**: Open work is bounded: the number of open fronts
  (`Working` + `AwaitingHuman`) never grows past W by more than the count of
  concurrently racing instances, and returned tasks are drained in
  preference to starting fresh ones.
- **G3**: The daemon stops cleanly: SIGTERM releases what it can within the
  grace window (no TTL wait for those tasks); drain mode exits on its own
  when the queue is empty and slots are idle; a killed daemon loses nothing
  that the lease protocol cannot recover.
- **G4**: An operator running several instances stays inside the shared
  GitHub write budget by construction at default settings, and the guide
  names the knobs when scaling past them.

## Non-Goals

- **NG1**: Execution sandbox — autonomous `serve` executes pipeline commands
  on the host under the trusted-repo envelope; the sandbox is a
  separate future change. Only the
  operator-guide autonomy gate ships here.
- **NG2**: Mid-round cancellation of a running gnome (unchanged; SIGTERM
  waits for round boundaries within grace, then relies on the lease).
- **NG3**: Webhooks or any inbound HTTP — the feed polls; conditional
  requests keep polling free.
- **NG4**: Cross-branch conflict management — W bounds how much is open,
  not whether branches merge; rebase stages and task slicing remain the
  pipeline author's discipline (named in the guide).
- **NG5**: Per-project fairness or priorities beyond "returned first, then
  oldest-first as a soft preference".
- **NG6**: Hold/release slot policy — cancelled (see Decision revision);
  a slot never idles holding a parked task.

## Users & Scenarios

- **U1 — Long-running daemon**: an operator starts `gnomish serve` on a VM;
  it claims up to N tasks, heartbeats them, parks escalations, picks up
  human-returned tasks in priority, and keeps going for days; the issue
  threads show live progress throughout.
- **U2 — Escalation-heavy queue**: 200 ready tasks with badly specified
  criteria; escalations park task after task. At open = W the daemon stops
  starting fresh work and says "W fronts await human decisions"; the human
  answers a few, the answered tasks are drained first, the factory resumes.
- **U3 — Cron operator**: no long-lived process allowed; cron fires
  `serve --drain` nightly — the instance works the queue with N slots until
  it is empty, then exits 0; stuck claims from a previous crashed run were
  reaped meanwhile by the heartbeat thread's reaper duty.
- **U4 — Deploy restart**: SIGTERM arrives mid-run; the daemon stops
  claiming, two slots finish their rounds inside the grace window and
  release their claims (tasks return to `Ready` instantly), one slot is
  mid-round past grace — SIGKILL; its task returns via TTL + reaper; the
  new daemon process starts clean.
- **U5 — Batch of three**: `gnomish take 42 43 44` — 42 delivers, 43 is
  already `Working` elsewhere (skipped, named holder), 44 parks as an
  escalation; the summary lists all three and the exit code reflects the
  legitimate-outcome family.

## Requirements

### Functional

- **FR1**: The scheduler SHALL run up to N slots concurrently, where a slot
  executes the existing take cycle (claim → run → react to the outcome)
  unchanged; N SHALL be instance configuration with a modest default. The
  scheduler SHALL never assign one task to two slots of the same instance —
  claiming happens in the feed, and a slot receives an already-claimed task.
- **FR2**: Three run modes SHALL share this machinery: single `take`
  (existing, schedulerless), batch `take <ref> <ref> ...` (work the given
  list to exhaustion), and `serve` (feed from `listReady` until stopped).
  The N limit applies to batch and serve.
- **FR3**: Batch mode SHALL apply the explicit-mode disposition matrix to
  each ref independently — skipped refs are reported with their reason, the
  run continues — and SHALL end with a summary of every ref's outcome and
  one aggregate exit code in which the "tool could not operate" family
  (codes < 10) dominates the "legitimate outcome" family (codes ≥ 10).
- **FR4**: Batch and serve SHALL be unconditionally non-interactive: no TTY
  dialog exists in any of their paths; escalation always parks with a
  tracker report. The Change 1 takeover confirmation in these modes SHALL
  be available only via its explicit headless flag.
- **FR5**: The feed SHALL be an automaton with four states: Filling (a free
  slot and an eligible task: poll → claim → immediately again, no pause),
  Idle-empty (free slot, open < W, no eligible ready task), Idle-blocked
  (free slot, open ≥ W), and Full (no free slot: no tracker polling at all;
  wakes on the local slot-freed event). One idle-poll interval — factory
  configuration, default ~30 s — SHALL apply in both Idle states; Filling
  has no pause and Full no timer.
- **FR6**: The WIP limit SHALL bound fresh starts: open fronts =
  count(`Working`) + count(`AwaitingHuman`) project-wide, counted from the
  Change 1 open-tasks listing; fresh tasks are claimed only while
  open < W; returned tasks (see FR7) are claimable always, outside the
  limit and ahead of fresh ones. W SHALL be a protocol constant in the
  `tracker` section of `.gnomish/config.yaml` (integer ≥ 1, default 10,
  operator-documented expectation W ≥ N), read only from the factory's own
  clone. The limit is soft: concurrent instances may overshoot by at most
  units, one per racing instance.
- **FR7**: `ReadyTask` SHALL carry an adapter-derived "returned" fact — true
  when the task's history holds a park marker (human-returned) or a
  stale-claim-removed marker (reaper-returned) — implemented by the
  in-memory reference and the GitHub adapter and covered by the shared
  contract spec. No other port operations are added by this change.
- **FR8**: The explicit `take <ref>` mandate SHALL pierce the abort-backoff
  filter and the WIP limit for `Ready` tasks only; parked tasks keep the
  existing refusal (return via human label flip), and `Working` tasks keep
  the Change 1 takeover protocol.
- **FR9**: Multi-instance conduct: the feed SHALL jitter its poll phase and
  claim from the head zone — a random pick among the first K eligible ready
  tasks (oldest-first becomes a soft preference; bare single `take` follows
  the same rule) — and SHALL keep concurrent claim attempts ≤ the
  instance's free slots.
- **FR10**: Drain mode (`serve` flag) SHALL treat "no eligible task" as the
  stop-claiming signal instead of sleeping: occupied slots run their tasks
  to terminal results, and the process exits cleanly when all slots are
  empty.
- **FR11**: On SIGTERM the daemon SHALL immediately stop claiming, let each
  slot stop at its next round boundary within the grace window, and
  explicitly release the claims of tasks stopped this way — an instant
  return to `Ready` instead of a TTL wait. Tasks whose rounds outlive the
  grace window need no new mechanism: the process dies and the Change 1
  lease path (TTL, reaper, resume from the branch) recovers them. On any
  exit the daemon SHALL kill its process group so no gnome subprocess
  survives it.
- **FR12**: Daemon startup SHALL be a clean start: claims under a previous
  instance id are not recognized as own and are left to the lease protocol;
  the existing label-provisioning step runs as a startup smoke test of the
  tracker binding — an unreachable repository is death on startup with a
  clear error.
- **FR13**: `serve` SHALL run the Change 1 heartbeat thread for all slots'
  `Working` tasks; the reaper duty on that thread SHALL keep observing and
  returning stale claims in every feed state — including Full and
  Idle-blocked, where a reaped front also releases W budget without human
  involvement.
- **FR14**: The worktree cleaner SHALL dispose of worktrees belonging to
  ended (delivered, escalated, revoked) tasks past a configured age, as a
  localized "dispose of a task's environment by age" responsibility whose
  callers do not know the environment is a host worktree (the future
  sandbox seam); a same-instance resume SHALL keep reusing a still-present
  worktree.

### Non-Functional — Reliability

- **NFR-R1**: No double execution inside an instance: across randomized
  interleavings, a task is never worked by two slots of one instance
  (contract-level scheduler test); across instances the Change 1 lease
  remains the arbiter.
- **NFR-R2**: Shared-clone git physics under N slots: concurrent fetch,
  worktree add/remove, and push against one target clone SHALL be safe —
  serialized where git requires it — verified by tests exercising
  concurrent slot lifecycles.
- **NFR-R3**: A tracker outage SHALL not kill the daemon: the feed retries
  with backoff and recovers; running slots continue (outcomes durable in
  branches, terminal writes reconcile per Change 1); no false reaping by
  construction.

### Non-Functional — Performance

- **NFR-P1**: Idle polling SHALL use conditional requests (304 on no
  change) so its rate-limit cost is zero at any instance count; the idle
  interval needs no multi-instance adjustment.
- **NFR-P2**: Write economy at defaults SHALL keep the operator inside
  GitHub's shared per-token budget: heartbeat dominates steady-state writes
  (≈ ΣN × 12/hour), bounding total concurrency to ΣN ≲ 20 at the default
  beat interval with headroom reserved for claims, transitions, reports,
  and reaping; the beat interval — not instance count — is the scaling
  knob, and the guide says so.

### Non-Functional — Observability

- **NFR-O1**: The daemon SHALL log feed-state transitions and, at the WIP
  limit, state explicitly "N fronts await human decisions; not starting
  fresh work" — the bottleneck (the human) is named, not silent. All slot
  work carries the canonical task id in MDC; interleaved logs stay
  attributable.
- **NFR-O2**: A batch run SHALL end with a machine-findable summary naming
  every ref's outcome; a drain run SHALL report what it worked before
  exiting.

### Non-Functional — Security

- **NFR-S1**: The operator guide SHALL state the autonomy gate as a
  requirement: the ability to set the ready label equals the ability to
  execute code on the factory host; auto-`ready` bridges from untrusted
  sources are forbidden under the trusted-repo envelope.
- **NFR-S2**: The guide SHALL require CI hygiene for gnome branches:
  workflows triggered by `gnomish/*` pushes run without privileged secrets
  and with a read-only `GITHUB_TOKEN` — a gnome branch is gnome-authored
  code pushed without a human.
- **NFR-S3**: W SHALL be readable only from the factory's own clone of
  `.gnomish/config.yaml`, like the other protocol constants — a gnome must
  not be able to raise the project's WIP limit.

### Non-Functional — Cost

- **NFR-C1**: The WIP limit SHALL cap the tokens a runaway queue can burn:
  once open = W, no fresh task consumes agent rounds until a front closes —
  the escalation-conveyor scenario costs at most W tasks' worth of work.

## Operator Experience Criteria

- **UX1**: Running the factory continuously is one command with a handful of
  instance knobs (N, idle interval, drain, grace); protocol behavior needs
  no per-instance tuning because its constants live in the project repo.
- **UX2**: When the factory stalls on the WIP limit, the operator learns it
  from one log line (and the parked tasks' reports in the tracker) — the
  next action (answer escalations) is obvious, not archaeological.
- **UX3**: A batch run reads like a checklist afterwards: every ref, its
  outcome, one exit code meaningful to scripts.
- **UX4**: The guide gives the operator a working mental model of the
  budget: what writes cost, why ΣN is bounded, which knob to turn first
  when scaling.

## Success Metrics

- **M1**: The extended contract spec (returned fact round-trip, open-tasks
  listing as W source) passes against both adapters with zero exemptions.
- **M2**: Scheduler property holds in 100% of randomized-interleaving test
  runs: no task is ever assigned to two slots of one instance; claim
  attempts in flight never exceed free slots.
- **M3**: Lifecycle tests: SIGTERM with all slots at round boundaries
  releases every claim within the grace window; `--drain` on an empty queue
  exits 0 with an empty-run report; no gnome process survives daemon exit.
- **M4**: WIP simulation: an escalation-heavy queue against W = 10 and M
  racing instances never exceeds 10 + M open fronts, and returned tasks are
  always claimed before fresh ones in 100% of runs.
- **M5**: Coverage and mutation gates per `.claude/rules/testing.md` hold
  for all new production code.

## Open Questions

- **Q1**: Names and defaults of the instance knobs — the drain flag name,
  N's default (2 vs 3), the SIGTERM grace default. (design)
- **Q2**: Head-zone size K and the jitter shape. (design)
- **Q3**: Exit-code aggregation inside the families (which code wins within
  < 10 and within ≥ 10) and `serve`'s own exit codes. (design)
- **Q4**: Worktree-cleaner age threshold default and trigger cadence
  (feed tick vs own timer). (design)
- **Q5**: How the headless takeover flag composes with batch varargs —
  per-ref or whole-run. (design)
- **Q6**: Where the slot-freed event and the feed automaton live relative
  to the existing take-cycle code (structured concurrency shape). (design)

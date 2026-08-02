## Why

The stale-claim reaper is a passenger of the heartbeat thread, which only runs
while the instance holds at least one of its own claims. An instance with zero
held claims runs no beat thread, so no ticks, so no reaping — exactly the state
a daemon lands in right after a restart. A single-instance `serve` that is
killed while saturated (open fronts ≥ WIP limit) or restarts against an empty
ready queue can never reap its own previous life's stale claims: fresh claims
are blocked by the limit, there are no returned tasks (the reaper that would
create them never runs), and the daemon sits idle forever. Recovery-after-death
is precisely when the reaper is needed most and precisely when it is absent.

## What Changes

- **MODIFIED** — The reaper becomes a **standing duty for the run's lifetime**,
  on its own thread, decoupled from whether the instance currently holds any
  claim (including zero). It no longer rides the heartbeat tick.
- **MODIFIED** — The heartbeat thread reverts to a single responsibility:
  beating the instance's own held claims. It no longer runs the reaper duty in
  any mode.
- **MODIFIED** — The reaper's "never reap my own live claim" exclusion reads a
  live snapshot of *actively-beaten* claims, so a dead heartbeat stops shielding
  its now-unbeaten claims and they can be returned.
- **ADDED** — The reaper thread survives abnormal faults (an `Error` from deep
  in an adapter, a throwing sleeper) and, if it still dies, is supervised and
  restarted; an intentional shutdown never triggers a restart.
- **UNIFIED** — `take` and `serve` wire the reaper through the same mechanism;
  the only difference is run scope (one invocation vs the daemon lifetime). No
  per-mode reaper divergence remains.
- **MODIFIED** — `factory-serve`'s FR13 ("reaps in every state") and FR12
  ("restart is a clean start") are re-grounded on the standing reaper: reaping
  no longer depends on the heartbeat tick, so an idle or freshly restarted
  daemon with zero own claims still recovers stale claims.
- **MODIFIED** — `tracker-take`'s "reaping is a byproduct of holding a claim"
  framing is replaced: a take run starts the standing reaper for the whole
  invocation, and the operator guide's stuck-`Working` recovery drops the
  "live claim required" condition — automatic reaping needs only a running
  instance.

## Capabilities

### New Capabilities
<!-- none: this change fixes an existing capability's behavior -->

### Modified Capabilities
- `claim-heartbeat`: the "Reaper returns stale claims to circulation"
  requirement is retargeted from "a duty of the heartbeat thread, running as
  long as the instance holds at least one claim" to a standing duty for the
  run's lifetime, independent of held claims; a new requirement covers the
  reaper thread's resilience to abnormal death.
- `factory-serve`: "Serve maintains the lease and reaps in every state" (FR13)
  is retargeted from the beat-thread reaper duty to a standing reaper thread
  that reaps in every feed state and with zero held claims; "Restart is a clean
  start" (FR12) gains an explicit guarantee that recovery does not depend on the
  new process claiming a fresh task first; "Scheduler runs N slots" scopes its
  no-double-assignment guarantee to live claims (a self-reaped task re-claimed
  by the same instance follows the fence path, like any foreign re-claim);
  "Daemon tolerates tracker outages" adds the standing reaper to the actors
  that retry with backoff.
- `tracker-take`: "Take runs the heartbeat thread and the reaper duty" is
  retargeted from a per-tick beat duty ("byproduct of holding a claim") to the
  standing reaper scoped to the invocation; the "Operator guide" requirement's
  automatic-recovery wording changes to "whenever an instance runs, claim or
  no claim".

## Goals

- **G1**: Reaping never depends on the instance currently holding its own work —
  a zero-claim instance still returns foreign and prior-life stale claims.
- **G2**: One reaper mechanism across `take` and `serve`; no per-mode behavior
  divergence.
- **G3**: A single abnormal fault cannot permanently disable reaping for the
  life of the process.

## Non-Goals

- **NG1**: No change to heartbeat physics, payload, staleness math, TTL, the
  takeover protocol, or zombie fencing.
- **NG2**: No new cross-instance coordination or distributed reaping beyond what
  `claim-heartbeat` already specifies (soft, convergent, idempotent removal).
- **NG3**: No change to `take`'s claim→work→terminal core or to `serve`'s feed
  automaton, WIP limit, or drain/shutdown logic.

## Users & Scenarios

- **U1**: An operator runs a single `serve` daemon that is killed while
  saturated (open fronts ≥ W) and restarted; they expect the previous life's
  stale claims to be reaped and work to resume, with no manual `take --takeover`
  or label surgery.
- **U2**: An operator's `serve` daemon crash-restarts against an empty ready
  queue while it had held two tasks; they expect those two tasks to return to
  circulation on their own.
- **U3**: A developer runs a one-shot `take`; the reaper behaves identically to
  `serve`'s, just bounded to that invocation.

## Requirements

### Functional

- **FR1**: The reaper SHALL run as a standing duty for the whole run's lifetime,
  on its own thread, regardless of how many claims the instance currently holds
  — including zero.
- **FR2**: The reaper's own-claim exclusion SHALL read a live snapshot of the
  instance's actively-beaten claims; an instance beating no claim SHALL exclude
  nothing and MAY reap prior-life stale claims left under its own former id.
  A claim whose heartbeat thread has died SHALL likewise stop being excluded
  and MAY be reaped by its own instance once stale; a slot still working such
  a task becomes a zombie handled by the existing fence path, and a
  same-instance re-claim of it is equivalent to a foreign re-claim.
- **FR3**: The reaper's loop SHALL catch every `Throwable` around both the reap
  tick and the interval wait, so an `Error` or a throwing sleeper is logged and
  the next tick still runs; only an intentional stop exits the loop.
- **FR4**: If the reaper thread dies unexpectedly despite FR3, it SHALL be
  restarted (supervised) with bounded backoff; an intentional shutdown SHALL NOT
  trigger a restart.
- **FR5**: The reaper SHALL be assembled and wired identically for `take` and
  `serve`, differing only in run scope; the heartbeat thread SHALL NOT run the
  reaper duty in any mode.

### Non-Functional — Reliability

- **NFR-R1**: A restarted single-instance `serve` with zero own claims and
  either a saturated WIP limit or an empty ready queue SHALL still reap its
  previous life's stale claims within one TTL window and recover autonomously.
- **NFR-R2**: Reaping SHALL remain available for the life of the process after
  any single abnormal reaper fault (FR3/FR4 guarantee).

### Non-Functional — Observability

- **NFR-O1**: An abnormal reaper death and each supervised restart SHALL be
  logged at ERROR with enough context to diagnose the cause, each restart line
  carrying a monotonic restart count (the log is the only exposure surface —
  the factory has no metrics endpoint); ordinary tick failures SHALL be logged
  at WARN.

### Non-Functional — Security

- **NFR-S1**: The reaper's interval and TTL SHALL continue to come only from the
  factory's own clone of `.gnomish/config.yaml` (unchanged from
  `claim-heartbeat` FR3); this change introduces no new gnome-writable input.

## Operator Experience Criteria

- **UX1**: After a restart, the operator sees the previous life's tasks return
  to `Ready` and be re-claimed with no manual intervention — the same observable
  outcome `claim-heartbeat`'s reaper already promised, now honored when the
  instance holds nothing.
- **UX2**: A reaper fault is visible in the logs (ERROR + restart), never a
  silent loss of the reaping guarantee.

## Success Metrics

- **M1**: An integration test proves a post-restart single-instance daemon
  returns prior-life stale claims to circulation with an empty ready queue and
  zero manual intervention.
- **M2**: Mutation score on the new standing-reaper unit is 100% (≥95% only with
  explicit justification, per the testing rule).
- **M3**: A test proves the reaper survives an injected `Error` and a throwing
  sleeper and reaps on the next tick, and that supervision respawns a truly
  dead thread while `stop()` suppresses respawn.

## Open Questions

- None outstanding. (Supervised-restart policy resolved: exponential backoff
  capped at 10 min, unbounded restarts, ERROR + restart counter, daemon never
  killed — see design.md D5.)

## Impact

- **Code (new)**: a standing-reaper unit in `app.lease` owning its own virtual
  thread, un-killable loop, and supervision.
- **Code (modified)**: `InstanceHeartbeat` drops the reaper from its tick and
  exposes a live-claims snapshot; `TakeHeartbeat.forRun` assembly builds the
  standing reaper and a beat-only heartbeat; `TakeCommand` starts/stops the
  reaper around the invocation; `ServeCommand`/`ServeShutdown` start/stop it for
  the daemon lifetime; `ReaperDuty` ownership moves from `InstanceHeartbeat` to
  the standing reaper.
- **Specs**: modifies `claim-heartbeat` (the reaper machinery),
  `factory-serve` (FR13 "reaps in every state", FR12 "restart is a clean
  start", the scheduler's no-double-assignment scoping, tracker-outage
  actors), and `tracker-take` (take-mode reaper wiring, operator-guide
  recovery wording).
- **Docs**: `docs/operator-guide.md` — the stuck-`Working` recovery section
  drops the "a live claim must be held" condition; automatic reaping now needs
  only a running instance.
- **Tests**: `ReaperSpec`, `RestartCleanlinessSpec`, and
  `ReapingWhileSaturatedSpec` are repointed from the manually-driven heartbeat
  tick to the standing reaper; new specs cover zero-claim reaping, abnormal-death
  resilience, supervision, `stop()`, the live-claims snapshot, and both-mode
  wiring.
- **No new dependencies.**

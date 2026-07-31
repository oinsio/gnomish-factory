# Design: add-factory-serve

## Context

Change 2 of the factory-loop seam: the lease protocol exists
(`add-claim-heartbeat`), the slot body exists (the take cycle of
`add-tracker-port`); this change adds the frame — scheduler, feed, WIP
limit, daemon lifecycle, worktree cleanup (FR1–FR14). Decisions below settle
the proposal's open questions Q1–Q6. Per-stage
gnome timeouts need nothing here: `roundTimeout` already lives per stage in
`executor.settings` (verified during the final explore review). Protocol
constants — `wip-limit` beside the heartbeat keys — are read only from the
factory's own clone of `.gnomish/config.yaml`, never from anything the gnome
can write.

## Goals / Non-Goals

- **Goals**: N-slot scheduler over the unchanged take cycle; serve/batch/
  drain modes; feed automaton with WIP backpressure; multi-instance conduct
  (jitter, head zone); clean lifecycle; aged-worktree disposal.
- **Non-Goals**: execution sandbox (separate change; the cleaner is designed
  as its future `dispose()` seam); mid-round cancellation; priorities beyond
  "returned first, oldest-first as a soft preference"; webhooks.

## Decisions

**D1 — One feed loop, virtual-thread slots, a semaphore as the slot ledger.**
(FR1, FR5, Q6) The scheduler is one feed loop (platform thread) plus one
virtual thread per running slot. Free capacity is a `Semaphore(N)`: the feed
acquires a permit *before* attempting a claim (claim attempts ≤ free slots by
construction), starts the slot thread with the claimed task, and the slot
releases the permit on terminal result — the release also wakes the feed
(the local slot-freed event; no tracker poll in Full). Bare single `take`
keeps its current schedulerless path. *Rationale:* the permit-before-claim
order makes the "attempts ≤ free slots" invariant structural, not checked;
virtual threads make a blocked slot free. *Alternative rejected:* a
fixed-pool `ExecutorService` with a task queue — queued pre-claimed tasks
hold claims without working them, exactly what the lease protocol punishes.

**D2 — Feed policy is one core component shared by serve and bare take.**
(FR2, FR6, FR9) Eligibility (backoff filter, returned-first priority, W
check for fresh tasks, head-zone pick) is a single policy object; `serve`
drives it in a loop, bare `take` calls it once. *Rationale:* one place for
the claim policy — the bare-auto contract and the daemon cannot drift apart.
*Alternative rejected:* daemon-only W enforcement — bare `take` in a cron
loop would quietly bypass the project's backpressure.

**D3 — Instance knobs: `--slots` default 2, idle interval 30 s, grace 30 s,
`--drain`.** (FR2, FR5, FR10, FR11, Q1) All instance (factory-config) level
with CLI override, deliberately few: slots N = 2 (modest per the explore
decision), one idle-poll interval for both Idle states, SIGTERM grace
30 s, and the drain flag named `--drain`. *Rationale:* "knobs by pain"
— every additional knob must earn itself; two idle tempos were explicitly
rejected in explore. *Alternative rejected:* N defaulting to CPU-derived
values — slot cost is dominated by external agents and budgets, not local
cores.

**D4 — Head zone K = 5, fixed; jitter = up to +20% on the idle interval.**
(FR9, Q2) The feed claims a uniformly random pick among the first K = 5
eligible tasks; K is a constant, not configuration. Each idle sleep adds a
uniform random 0–20% to desynchronize instances. *Rationale:* the herd
effect needs only mild decorrelation; a constant avoids a knob nobody
can reason about; oldest-first stays a soft preference within one zone
width. *Alternative rejected:* K scaling with queue length or slots — more
surface, no observed pain; backoff-on-lost-claim — punishes the instance
that lost an honest race.

**D5 — Open-front count from `listOpen` size, checked per fresh claim.**
(FR6) The feed reads the open-front count as the size of the Change 1
`listOpen` listing (conditional ETag read, already fetched each beat tick
for the reaper) and re-checks it before every fresh claim attempt.
*Rationale:* reuses the one listing the protocol already pays for; the
per-claim re-check plus D1's permit ordering bounds overshoot to one task
per racing instance. *Alternative rejected:* a dedicated count operation on
the port — a second query for a number the listing already carries.

**D6 — Batch `--takeover` is whole-run.** (FR3, Q5) In batch mode the
headless takeover flag authorizes takeover for every explicitly listed
`Working` ref; without it, `Working` refs are skipped with the holder named.
*Rationale:* a batch ref list is already an explicit per-task operator
mandate; per-ref flag syntax buys precision nobody asked for at real CLI
cost. *Alternative rejected:* `--takeover=42,44` per-ref syntax — duplicates
the ref list and invites drift between the two lists.

**D7 — Exit codes: batch aggregate = smallest non-zero per-ref code; serve
exits 0/1/2.** (FR3, FR10, Q3) A batch run exits 0 only when every ref
exited 0; otherwise the smallest non-zero code among per-ref results — which
makes the below-10 "tool could not operate" family dominate the ≥ 10
legitimate-outcome family arithmetically, with no second code table. `serve`
exits 0 on a clean stop (drain complete or graceful SIGTERM), 1 on startup
failure (binding smoke test, config), 2 on usage errors; per-task outcomes
are the tracker's story, not the daemon's exit code. *Alternative rejected:*
a bitmask or "worst outcome" ranking — new semantics for scripts to learn,
against D16 of tracker-port which already ordered severity by families.

**D8 — Per-clone mutation lock for git physics.** (NFR-R2) All repo-level
mutating git operations of one instance (fetch, worktree add/remove, push)
serialize on one in-process lock per target clone; in-worktree operations
run unlocked in parallel. *Rationale:* git's own locking is fail-fast
(`index.lock` errors), not queueing — serializing in-process turns spurious
contention failures into waiting, and these operations are seconds against
hour-long rounds. *Alternative rejected:* relying on git's internal locks
with retry — retry loops around nondeterministic failures, harder to test
than a lock.

**D9 — Shutdown: stop-claim flag + boundary latch + `ProcessHandle` tree
kill.** (FR11, Q6) SIGTERM (via shutdown hook) sets the stop flag (feed
claims nothing more), signals every slot to stop at its next round boundary,
waits up to grace, releases the claims of slots that reached a boundary, then
destroys the process tree via `ProcessHandle.descendants()` (forcibly after
a short wait) so no gnome outlives the daemon. *Rationale:* round boundaries
are the only safe stop points (state committed); the tree kill is the
cross-platform approximation of a process-group kill — and a gnome that
escapes it is exactly the zombie the git fence already keeps harmless.
*Alternative rejected:* POSIX `setpgid`/`kill(-pgid)` — platform-specific
native calls for a guarantee the fence makes non-critical.

**D10 — Worktree cleaner: one janitor component, startup + hourly tick, age
threshold 14 days.** (FR14, Q4) A single component owns "dispose of a task's
environment by age": it runs at daemon startup and on an hourly timer,
disposes environments of ended tasks older than the threshold (factory
config, default 14 days), and never touches tasks this instance currently
holds. Worktrees are instance-local (each instance owns its clone), so no
cross-instance coordination exists or is needed; a disposed worktree
rematerializes from the branch on resume. *Rationale:* the sandbox notes ask
for exactly this seam — callers say "dispose", only the janitor knows the
environment is a host worktree, so the future sandbox change swaps the
inside. *Alternative rejected:* piggybacking disposal on feed idle ticks — a
busy daemon (always Filling/Full) would never clean.

## Risks / Trade-offs

- [A 30 s grace rarely catches an hour-long round's boundary] → most SIGTERM
  stops send mid-round tasks down the TTL path by design; planned stops
  should use `--drain`; the guide says so.
- [Head-zone randomization breaks strict FIFO] → bounded by K = 5 and
  accepted as a soft preference; starvation is impossible since the
  zone always includes the head.
- [W overshoot grows with the number of racing instances] → bounded to one
  per instance by D1/D5; W is policy, not a safety invariant.
- [Cleaner races a concurrent resume of an aged task] → disposal targets
  only ended tasks past a long threshold; the resume path rematerializes
  from the branch, so the race costs a re-clone, not correctness.
- [`ProcessHandle` tree kill is best-effort (double-forking gnome escapes)]
  → the escaped process cannot push (fence) or write the tracker (claim
  gone); it is waste, not danger.
- [Batch whole-run `--takeover` is blunt] → scope is the explicit ref list
  the operator typed; per-ref precision available by running singles.

## Migration Plan

No breaking changes; `serve` and batch are additive. Behavioral deltas to
bare `take`, called out in the guide: the strict queue head becomes the head
zone, returned tasks take priority, and the WIP limit can turn a formerly
claiming run into a clean no-op naming the limit. Rollout order inside the
change: returned fact (port + adapters + contract) → feed policy in core
(bare take switches to it) → scheduler + serve + lifecycle → batch form →
worktree janitor → operator guide.

## Open Questions

- None blocking implementation; the wire shape of the returned fact and the
  serve log line wording are fixed in code review.

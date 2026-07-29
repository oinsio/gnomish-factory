# Design: add-claim-heartbeat

## Context

A claim in `add-tracker-port` is written once and never maintained: an instance
death strands its task in `Working` until an operator flips labels by hand.
This change turns the claim into a lease (FR1–FR10) consumed by the existing
`take`, and extends the credential scrub to command checks (FR11). Decisions
below are grounded in the 2026-07-20 explore session and its best-practice
research (Kleppmann's fencing argument, DynamoDB Lock Client, Kubernetes
Leases, SQS visibility timeout, Temporal heartbeats); `explore-notes-factory-loop.md`
carries the full reasoning. Multi-instance behavior is designed in from the
start — the follow-up `add-factory-serve` adds concurrency within an instance,
not a new protocol.

## Goals / Non-Goals

- **Goals**: lease maintenance for every claim-holding run; automatic return
  of orphaned tasks; safe explicit takeover; zombie fencing; outage tolerance;
  terminal-write reconcile; command-check env scrub.
- **Non-Goals**: scheduler/slots/daemon (Change 2), execution sandbox beyond
  the scrub, mid-round cancellation, gnome-liveness monitoring (per-stage
  `roundTimeout` in `executor.settings` already covers it — no config delta
  needed here).

## Decisions

**D1 — Beat = in-place edit of the claim comment.** (FR1, NFR-O1, NFR-P1)
`heartbeat` PATCHes the existing claim comment; the comment id — the lease
anchor of the earliest-id claim protocol — never changes; one write per beat
carries a human-readable progress line (stage, attempt, alive-at) sourced from
`EngineEventListener` events. *Rationale:* the thread stays readable (UX4 of
tracker-port), the anchor stays stable, and live progress in the issue is
free. *Alternative rejected:* appending heartbeat comments — spams the thread
and grows unbounded; a heartbeat label — labels carry no facts by decision
FR7 of tracker-port.

**D2 — Staleness by local observation of versions.** (FR2, NFR-R1) Claim
version = `(commentId, updatedAt)`; core remembers, per observed claim, the
version and the first-seen instant on the observer's monotonic clock
(`System.nanoTime`-based, injected `Clock` for tests); stale = same version
for TTL since first sight. *Rationale:* the DynamoDB Lock Client pattern —
no cross-host clock comparison, and a fresh observer gets a grace period by
construction. *Alternative rejected:* `now − updated_at` arithmetic — one
skewed clock steals live claims.

**D3 — Heartbeat is an instance-level thread, not a gnome concern.** (FR1)
One thread per process beats all held claims on schedule while slot threads
block on executors; the gnome never learns the tracker exists (tracker-port
security model). *Rationale:* claim liveness answers "is the holder process
alive"; it must not depend on what the gnome is (script, CLI, external
service). *Alternative rejected:* Temporal-style activity heartbeats (the
worker itself calls heartbeat) — couples the protocol to gnome cooperation
and breaks the "gnome is anything" stance.

**D4 — Reaper rides the heartbeat thread.** (FR4) Each tick, after beating,
the thread calls `listOpen`, feeds the staleness memory, and removes stale
claims. *Rationale:* the thread lives exactly as long as the instance holds
any claim — the only window in which a single `take` process can observe
longer than TTL; it also keeps reaping alive when a future daemon's feed goes
quiet (the Full-state gap found in the final review). *Alternatives rejected:*
reaping on `listReady` polls — `take` has no continuous feed, and a saturated
daemon stops polling; a separate `reap` command — one more thing for
operators to schedule, covering no additional window.

**D5 — `removeStaleClaim` is one port operation guarded by the observed
version.** (FR4, FR5, NFR-R2) Signature sketch: `removeStaleClaim(taskId,
observedVersion)` → `Removed | Mismatch(currentFacts)`; the adapter re-checks
`(commentId, updatedAt)` immediately before acting, posts the
"stale claim removed" boundary marker, deletes the dead claim comment, flips
the label. *Rationale:* the version guard makes concurrent reapers and a
racing live beat converge without coordination; the marker is a claim
boundary, so the next lease round anchors on it like on release/park/abort.
*Alternative rejected:* composing from `release` + `postNote` in core —
`release` means "my claim", overloading it for foreign claims blurs the
contract, and the compose widens the race window the guard exists to close.

**D6 — Fence = git non-fast-forward push; tracker writes get a cheap
pre-check.** (FR7) The task branch is never force-pushed by anyone; two
non-force writers are equivalent to monotonic fencing tokens (Kleppmann), so
no separate pre-push claim check is needed. Unfenced tracker writes (park,
finish, release) are preceded by a conditional "claim still ours" read; the
residual TOCTOU costs at most a stray label/comment that the new holder's
next write overwrites. *Alternative rejected:* fencing tokens stored in the
tracker — GitHub cannot atomically reject a stale writer, while git, the
resource that matters, already can.

**D7 — Beat failures are classified, not counted.** (FR8) Network/5xx → WARN
and continue (the round boundary already re-checks the claim and will pass,
reveal the takeover, or abort); "comment gone" → claim lost, react at the
nearest boundary exactly like a revocation. *Alternative rejected:* an
"M consecutive failures" fuse — an arbitrary constant duplicating what the
boundary check decides for free.

**D8 — Defaults: 5-minute interval, multiplier 3.** (FR3, NFR-P1) 12
writes/hour per working task; death-to-Ready latency ≈ TTL (15 min) + one
reaper tick; network-blip tolerance = (multiplier−1) × interval. The industry
beat/TTL ratio (~1/3: k8s 10s/40s, DynamoDB 3s/10s) holds at our timescale.
The interval is the operator's throughput knob: the shared-token write budget
(~500/h secondary limit) bounds total concurrent tasks across all instances —
called out in the operator guide. *Alternative rejected:* sub-minute beats —
burn the shared budget for recovery latency nobody needs at hour-long rounds.

**D9 — Takeover is a pre-claim confirmation: TTY dialog or `--takeover`
flag.** (FR6) `take <ref>` on `Working` prints holder and last-beat age, then
asks; headless runs require `--takeover`. The confirmed path reuses
`removeStaleClaim` (with the just-observed version), then claims by the
ordinary lease. *Rationale:* the human is the authority — no TTL wait for an
operator staring at a dead instance; mistakes are insured by D6. This is a
deliberate, named deviation from tracker-port's "identical with and without a
TTY": a pre-claim gate, never an in-run wait, so it does not conflict with
"escalation always parks and exits". *Alternative rejected:* TTL-gated
automatic steal on explicit take — makes the operator wait out a timer they
can already judge better than the factory.

**D10 — Reconcile-on-resume sits at the head of the claim path.** (FR10,
NFR-C1) After any successful claim of a task with an existing branch, before
decision collection: load the branch state; if it records a terminal outcome
(`Completed`/`Escalated`) whose tracker counterpart never landed, perform the
deferred `finish`/`park` and exit — zero engine rounds. Terminal-write
retries use the existing Resilience4j policies with backoff, bounded at ~10
minutes of holding the slot; on giving up, exit with an ERROR naming the
unreconciled state — the branch keeps the truth and reconcile closes it
later. *Alternative rejected:* replaying the last stage to regenerate the
report — burns tokens to recompute what the branch already stores.

**D11 — Scrub reuse for command checks.** (FR11) `ShellCommandCheckRunner`'s
process construction receives the same adapter-declared credential list the
agent launcher gets (tracker-port D17 seam) and excludes those variables; no
tracker configured → env inherited unchanged. *Alternative rejected:* a full
env allowlist — that is sandbox Tier 0 (`explore-notes-sandbox.md`),
deliberately out of scope here.

**D12 — Structural formats follow the existing marker shape.** (FR1, FR4,
NFR-O1) The "stale claim removed" marker and the beat payload reuse the
adapter's hidden-HTML-comment + one-line-JSON convention: kinds
`stale-claim-removed` (fields: dead holder, removed comment id, observed
version, time) and the claim comment's refreshed body (stage, attempt,
alive-at, format version). *Alternative rejected:* a new format family — two
conventions to parse with no gain.

## Risks / Trade-offs

- [Unfenced tracker writes keep a TOCTOU window] → pre-write check narrows
  it; the damage class is a stray label/comment; the new holder's writes
  converge the state.
- [One-shot cron runs cannot reap (observer dies before TTL)] → documented
  honestly in the operator guide; manual escape hatch remains until `serve`;
  explicit `--takeover` works today.
- [Beats spend the shared write budget] → default economy (12/h/task), the
  coupling named in the operator guide; interval is the knob.
- [Heartbeat thread dies while the run continues] → beats stop, the claim
  goes stale, a reaper returns the task; the fence keeps the still-running
  zombie harmless — degradation is the designed death path.
- [Confirmed takeover of a genuinely live holder] → the old holder's next
  beat hits 404 (claim lost), it stops at its boundary writing nothing; git
  fence arbitrates any race on the branch.

## Migration Plan

No breaking changes. New config keys default sensibly (absent keys = current
behavior plus heartbeat at defaults); the `take` disposition change replaces a
flat refusal with a gated takeover — refusal remains the no-confirmation
outcome. Command-check env loses only tracker credentials; pipelines relying
on that leak were depending on a bug (NFR-S1 violation). Rollout order inside
the change: port + adapters + contract suite → core lease/staleness/reaper →
take integration → scrub → docs.

## Open Questions

- None blocking implementation; exact wire shapes of `listOpen` entries and
  the beat payload fields are fixed in code review against D5/D12 sketches.

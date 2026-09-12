# Design: add-claim-return

## Context

See proposal.md — Why. What shapes the approach:

- `Tracker.release` means "drop the claim, move no label" (FR15, D2 of
  add-tracker-port). The GitHub adapter implements it as an explicit no-op;
  the in-memory reference clears the claim marker and leaves the working
  state. `TrackerReleaseContract` (landed by `add-base-ref-resolution`) pins
  that on both adapters, so this change cannot and does not bend it.
- The tracker already has one fenced, version-guarded "retire a claim and
  restore the queue" routine: `removeStaleClaim` — re-read, compare, post a
  boundary marker, delete the claim comment, flip the label; `Mismatch` on
  any difference (`GithubStaleClaimRemoval`, `ClaimLeases.removeIfMatches`).
  The return is the same physics with a different marker kind and a
  different comparison key.
- Every claim carries a monotonic `ClaimEpoch` (`ClaimResult.Acquired`,
  `ClaimVersion.epoch`), and the holding instance records it in
  `ClaimEpochBook`. The holder therefore knows its own claim identity
  without threading heartbeat versions through the slot.
- SIGTERM today reuses the revocation round-boundary path verbatim
  (`ServeShutdown` → `ClaimLossFlag.claimLost(ref, SHUTDOWN_REASON)` →
  `RevocationCheckingAttemptPersistence` → `RevocationDetectedException` →
  `RevocationHandler`): shutdown and genuine loss are one code path
  distinguished only by a reason string.
- Active changes touching the same regions: `add-base-ref-resolution`
  (owns `FreshClaimBaseBinding`/`ResumeLawBinding` and the `tracker-take`
  release requirement this change layers on) and
  `signal-outage-gate-on-origin-contact` (modifies the outage-gate
  requirement of `factory-serve`, not the SIGTERM one). Neither touches
  `RevocationHandler` or the `Tracker` port.

Driven by FR1–FR9, NFR-R1–R3, NFR-O1, NFR-S1 of the proposal.

## Goals / Non-Goals

**Goals:** one fenced verb; one retirement routine per adapter; the three
deliberate callers switched; revocation untouched; the typed shutdown cause.

**Non-Goals (design-level):** no change to the heartbeat, the staleness TTL,
or the reaper's schedule; no new tracker shape; no new thread or lock.

## Decisions

**D1 — A second verb, not a widened `release`.** `returnToReady(ref,
ownClaim, reason)` joins the port beside `release`. *Rationale:* `release`'s
no-op-on-state is the intended behavior for revocation (a human may already
have moved the label), and it is pinned by contract; giving it a second
meaning would either break the revocation case or fork its behavior on a
flag. Every surveyed lease system keeps the two apart (Service Bus
`Abandon` vs lock expiry, AMQP `nack requeue`, Kubernetes `ReleaseOnCancel`
vs `leaseDuration`). *Alternative rejected:* make `release` flip the label
when the caller still holds the claim — one verb whose effect depends on a
race, and the revocation path would start moving labels a human may have
set. *Alternative rejected:* reaper-only (document the TTL and stop) — the
proposal's "Why" records the cost; Kubernetes filed the same gap as a bug.

**D2 — The fence is the caller's own claim identity, checked inside the
adapter.** The verb takes `ClaimIdentity(holder, epoch)`; the adapter
re-reads the live claim and proceeds only on an exact match, else
`ReturnResult.Mismatch(currentFacts)`. The caller obtains its identity from
`ClaimEpochBook.epochFor(taskId)` plus its `InstanceId` — both already in
hand at every call site. *Rationale:* this is Kleppmann's fencing token and
client-go's `holderIdentity` + `resourceVersion` check; the epoch is
monotonic per task, so a re-claim by the same instance after a reap is a
different identity and the stale return still no-ops. *Alternative
rejected:* fence by `ClaimVersion` (marker id + `updated_at`) as
`removeStaleClaim` does — the slot would have to capture every heartbeat's
refreshed version, threading `HeartbeatBeater` state into the bindings;
the epoch is the identity the protocol already issues for exactly this
purpose (claim-heartbeat, "Every (re)claim issues a monotonically
increasing epoch"). *Alternative rejected:* no fence, trust the caller — the
zombie-returns-a-reassigned-task double-scheduling case in proposal U3.

**D3 — One claim-retirement routine per adapter; two entries.** GitHub:
`GithubStaleClaimRemoval` is generalized into `GithubClaimRetirement`
taking a `Retirement` parameter object — marker kind (`STALE_CLAIM_REMOVED`
| `CLAIM_RETURNED`), comparison (observed footprint | own identity), marker
text; `removeStaleClaim` and `returnToReady` each build one and call it.
In-memory: `ClaimLeases.removeIfMatches` gains the same parameter and
`CorrespondenceEntry.Kind.CLAIM_RETURNED`. The marker reader on both
adapters treats the new kind as a boundary (the `IndexLagging` rule
applies unchanged). *Rationale:* the flip-to-ready write order is the
crash-consistency-critical part (D5); two copies would be the
manual-sync-pair this project's rules exist to prevent. *Alternative
rejected:* implement the return as `postNote` + `release` + a label call
from the application layer — three port calls, no fence, and the kill
windows would fall between port operations no adapter owns.

**D4 — A typed claim-loss cause decides the verb at the round boundary.**
`ClaimLossFlag` records `ClaimLoss(cause, reason)` with `cause ∈
{SHUTDOWN, LOST}`; `RevocationDetectedException` carries it;
`RevocationHandler.handle` runs salvage and best-effort push for both, then
branches: `SHUTDOWN` → `returnToReady` with the shutdown reason and no
"work stopped" note (UX3); `LOST` → the existing `postNote` + `release`,
byte-for-byte. The `TakeResult` for a shutdown stop stays `Revoked` (the
exit-code mapping and the slot outcome line are unchanged; the line's
reason text already says "daemon shutting down"). *Rationale:* the
decision must not rest on string equality with `SHUTDOWN_REASON`, and the
round-boundary machinery is reused rather than duplicated. *Alternative
rejected:* a separate shutdown handler beside `RevocationHandler` — a
second salvage/push sequence to keep in sync. *Alternative rejected:*
return from `ServeShutdown` directly after the drain — the slot thread owns
the claim and the round boundary; a second thread writing the tracker for
the same ref is a new race.

**D5 — Write order and kill windows.** Per adapter the return lands three
writes in order: boundary marker → claim marker removal → label flip.
Windows and shapes (crash-consistency checklist): after the marker —
`IndexLagging` (marker newer than the working label), owner reaper,
completes the flip; after the removal — still `IndexLagging` (the marker,
not the absent comment, carries the truth), same owner; after the flip —
`Ready`, settled. Constructive before destructive: the marker precedes the
deletion. Mutually-implied facts: none — each write is self-describing. The
in-memory adapter is atomic (one lock); the GitHub adapter covers the three
windows by fault injection in its kill-window suite, and the tracker-side
row joins `TrackerKillWindows`. Recovery idempotence: the reaper's
`IndexLagging` repair is already idempotent. *Alternative rejected:* flip
the label first for a faster `Ready` — a kill after the flip and before the
marker leaves a ready-labeled task carrying a live claim marker, the
`ClaimAbandoned` suspension leftover, which costs a grace period instead of
a plain completion.

**D6 — Best-effort at the call site, bounded on shutdown.** A return that
throws (network, exhausted 5xx) is caught where the old release was, logged
under its own operator-event code, and the run continues to its result;
the task stays on the TTL path (NFR-R1). On shutdown the return runs on the
slot thread inside the grace window like the round boundary it follows;
`ServeShutdown`'s kill proceeds when grace ends regardless (NFR-R3).
*Alternative rejected:* retry the return after the adapter's own retries —
it would lengthen exactly the exit the operator is waiting for, and the
reaper already guarantees convergence.

**D7 — Observability.** Three new codes in `OperatorEvent`: return landed
(INFO, task, holder, reason), return fenced (DEBUG, what the adapter found),
return failed (ERROR). The reason text is factory-authored: a fixed phrase
for outage and shutdown, `LogText.forLog` of the usage error's summary for
the bail-out (NFR-S1); tracker-sourced text never enters the marker.

**D8 — Durable principle.** `docs/adr/0008-two-paths-back-to-the-queue.md`:
every claim has two ways back to `Ready` — the holder's fenced immediate
return and lease expiry swept by the reaper; the explicit path is an
optimization of latency, expiry is the safety invariant, and neither verb
ever borrows the other's meaning. Glossary: *return* beside *release*; the
reaper entry gains "or returned by its holder".

**Sync surfaces.** The in-memory and GitHub realizations of the retirement
routine are two adapters of one port operation, held equal by the contract
suite (`TrackerReturnContract`), not a hand-synced pair; the `Retirement`
parameter object is the shared shape on each side. This change touches no
pair declared in `manual-sync-pairs.md`. Host and container fresh-claim
paths already share `FreshClaimBaseBinding`, so switching the verb there
switches both.

**Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| Adapter claim-retirement routine (`GithubClaimRetirement`, `ClaimLeases`) | `ReturnResult` for a `ClaimIdentity` | `FreshClaimBaseBinding.releaseBestEffort`, `ResumeLawBinding.releaseBestEffort`, `TakeClaimAndWork.releaseBestEffort`, `RevocationHandler.handle` (SHUTDOWN arm) | `tracker.release(ref)` at those four sites — deleted; the surviving exemption is `RevocationHandler`'s LOST arm (revocation keeps `release` by decision, NG1) | `ReleaseCallSiteBoundarySpec` in `:bootstrap`: scans `application/src/main` and fails on any `.release(` outside `RevocationHandler` (M3); the port type `ClaimIdentity` (no `String` holder overload) |
| `ClaimLossFlag` | `ClaimLoss(cause, reason)` | `RevocationCheckingAttemptPersistence`, `RevocationHandler`, `ServeShutdown`, `TakeHeartbeat` (LOST) | `claimLost(ref, String reason)` and `reason(ref)` as the discriminator — replaced by the typed record; `SHUTDOWN_REASON` stays as the reason text only | The `ClaimLoss` type; no string-compare of the reason survives (grep gate in the same spec) |

## Risks / Trade-offs

- [A half-landed return on GitHub shows a "returned" marker while the issue
  still wears the working label] → classified `IndexLagging`, completed by
  the next reaper pass; covered by fault injection (D5).
- [The shutdown return adds one to three tracker writes per drained slot
  inside the grace window] → bounded by grace; a slow tracker costs at most
  the grace the operator configured, never the exit.
- [A caller passes a stale epoch from `ClaimEpochBook` after a same-process
  re-claim] → the book is updated on every `issued`; the identity is read
  at return time, not cached at claim time.
- [Widening the contract chain again lengthens every adapter's suite] → one
  file appended at the chain end, five properties; in-memory runs in
  milliseconds, GitHub under WireMock in seconds.
- [Lock scope] → no new lock; the in-memory write runs under the tracker's
  existing store lock with no I/O inside it (state-guarding lock, the rule,
  satisfied).

## Migration Plan

Apply after `add-base-ref-resolution` archives (it owns the two binding
classes and the layered `tracker-take` requirement). Port first (plugin API,
both adapters, sample, contract), then callers, then docs. Rollback is a
revert: the marker kind is additive and a reader that does not know it
falls into `Foreign` with a diagnosis, never a silent misclassification.

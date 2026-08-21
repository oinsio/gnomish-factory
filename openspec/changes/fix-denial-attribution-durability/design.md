# Design: fix-denial-attribution-durability

## Context

See proposal.md — Why. Two constraints shape everything below.

First, the executor port reports infrastructure failure by *throwing*: the
agent adapter throws (round timeout, missing result event, process that would
not start), and the engine's round mechanics catch any `RuntimeException` and
shape it into `RoundOutcome.CannotExecute` — no attempt record is built, which
is why `fix-denial-report-attachment`'s attachment point cannot serve this
path (its design D1a). The adapter already drains the denials on that path
today; they have nowhere to go but the log.

Second, the read position is already durable — but only along the attempt
path. The predecessor's FR5 commits the position in `state.json` with the
attempt it delimits, stamped with the guard container's runtime id, and
offers it back on resume, applying it only when the stamp names the live
container (`GuardDenialReads`, `EnvironmentAttemptPersistence`,
`ContainerRunTermination`). The failure-path drain, however, advances the
position with no attempt commit to ride: the advance dies with the process,
and a cross-process resume restores the last *attempt's* position and
re-reads the drained denials into the first round after the resume.

## Goals / Non-Goals

**Goals:** a design-level answer to two questions — how denials travel out of
a round that throws (FR1), and how the position advanced by a failed round's
drain becomes durable without ever leading the record it delimits (FR3,
NFR-R2).

**Non-Goals:** changing how infrastructure failures are classified or how the
executor port signals them; denial sources and surfaces excluded by the
proposal's NG1–NG5.

## Decisions

**D1 — Denials ride a dedicated executor-failure exception.**
The domain gains an `ExecutorFailure extends RuntimeException` carrying the
original failure as its cause plus `List<Finding> denials()`. The agent
adapter's existing failure path drains the denials and rethrows the original
wrapped in it; `RoundExecution` renders the *cause* (so the escalation text is
unchanged) and copies the denials onto `EscalationReport.CannotExecute`. Any
other `RuntimeException` still maps to a `CannotExecute` with an empty list, so
adapters that have no environment need no change.
*Rationale:* the smallest blast radius that keeps the port's "throws on
infrastructure failure" contract intact — one new domain type, one adapter
touch point, every other executor adapter untouched.
*Alternative rejected:* making the failure a value on the sealed
`ExecutionResult` (`CannotExecute(cause, denials)`) — cleaner in the abstract
and worth doing if the port is ever reworked, but it rewrites every executor
adapter and every engine branch for a purely observational field; carrying the
denials on the existing exception types by giving each one a field — spreads
the same concern across three unrelated exceptions and still misses the fourth.

**D2 — The drained position rides the escalation write, not a side file.**
`task.json` gains the same cursor field `state.json` already carries — the
opaque position paired with the guard container identity it was read from —
written by the escalation park alongside `lastEscalation`, read best-effort
from the environment after the drain exactly as the attempt commit reads it.
The resume restore offers the newest source-matching position across the two
documents at the branch tip; positions of one source are that daemon's
timestamps and totally ordered, and the existing stamp check still discards a
position naming a recreated or foreign container, falling back to a full read.
*Rationale:* this is the position-with-the-record pattern stream processors
use for the same problem — a consumer's offset is committed atomically with
the output it delimits (Kafka's commit-after-processing, Flink's
offset-in-the-checkpoint) precisely so the position can only *lag* the
record, never lead it: every failure mode then degrades to a re-read
(duplicates, D3), never to a skipped read (silence). It also adds no new
host-side state: the position lives where the denials it delimits live, with
the same lifetime, writers, and readers.
*Alternative rejected:* a factory-private position file beside the guard's
rendered config, advanced after each read — the position and the record then
have two writers with no shared commit, so a crash between them leaves the
position *ahead* of the record and silences the gap: the exact failure D3
exists to rule out; it is also new host state needing disposal wiring and an
orphan sweep. A docker label on the guard — fixed at creation, cannot
advance. Deriving the position from the timestamps of already-recorded
denials — needs a timestamp on `Finding`, a domain type shared with check
findings, for a guard-only concern.

**D3 — Losing the position means duplicates, never silence.**
An unreadable, absent, or mismatched position reads the whole (tail-capped)
log and logs that it did so (FR4, NFR-O2). This is the one place the design
deliberately prefers repeating itself: denials gate nothing (proposal NG1), so
noise costs a reviewer a second look while silence costs the signal entirely.

**D4 — Additive v1 fields in both mappers, one render path.**
`task.json`'s `lastEscalation` and `status.json`'s escalation gain `denials`
using the existing finding DTO shape; absent reads as empty, no version bump —
the same evolution path the predecessor took for attempt denials. The text
render reuses the funnel-fenced finding line built by that change, so
gnome-chosen hosts and paths cannot rewrite an operator's terminal (NFR-S1).
The cursor of D2 stays branch-internal: `task.json` only, never
`status.json` — it is environment bookkeeping, not report content, the same
stance `state.json` already takes.

## Risks / Trade-offs

- [A guard recreated mid-lease restarts its log; a position pointing past it
  would silence every later denial] → the container-identity stamp of D2 makes
  the mismatch detectable, and D3 turns it into a fresh full read.
- [`EscalationReport` is re-exposed by `gnomish-plugin-api`, so a new component
  breaks the api-compat gate] → accept it the documented way, as the
  predecessor did for `AttemptRecord`: pre-1.0 breaking = MINOR api version
  bump plus a regenerated `compat-baseline/` in this change's diff.
- [Two committed positions at the tip can disagree after a resume] → they
  never contradict: positions of one source are that daemon's timestamps and
  totally ordered, so newest-source-matching-wins is deterministic; a
  position of another source is dropped by the existing stamp check either
  way.
- [The park path may run when the environment is unhealthy, so the cursor
  read can fail exactly when it matters] → the write is best-effort by
  requirement (NFR-R1): a park without a cursor costs at most a duplicated
  denial on resume (D3), never the park itself.
- [New branchy code — position choice, fallback paths — must clear the PIT
  100% gate] → the branches are all observable through the guard's docker
  seam and the state DTOs, which the existing guard and persistence specs
  already drive daemon-free.
- [`add-serve-sandbox-lifecycle` (active) disposes unowned guard containers
  immediately, so a parked task's guard may be gone before the resume] → the
  NFR-R2 case, not a hole: the position shares the log's lifetime, and a
  resume over a missing or recreated guard starts a fresh full read —
  correct rather than a fallback. M2's cross-process scenario deliberately
  keeps the guard alive to exercise the cursor path.

## Migration Plan

None: both document fields — the escalation's denials and `task.json`'s
cursor — are additive under contract v1, and a tip with no escalation-side
cursor restores exactly what today's resume restores.

## Open Questions

None.

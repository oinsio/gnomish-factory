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

Second, the read position is *designed* to be durable along the attempt path
(predecessor FR5: committed in `state.json` with the attempt, stamped with
the guard container's runtime id, offered back on resume through
`GuardDenialReads`, `EnvironmentAttemptPersistence`,
`ContainerRunTermination`) — but the 2026-08-28 audit found the design
disconnected in production: `LeasedEnvironment`, the view both writers are
built over, forwards none of the port's three denial default methods, so the
constant defaults answer instead of the guard, no cursor is ever committed,
and the restore branch never runs. Both spec halves are green over doubles
that implement the methods themselves. The failure-path drain, additionally,
advances the position with no attempt commit to ride: the advance dies with
the process, and a cross-process resume restores the last *attempt's*
position and re-reads the drained denials into the first round after the
resume. The audit's root cause: the invariant "the position becomes durable
only with the record it delimits" has no owner type — it is prose spread
over five classes in three modules, so a forgotten forward or a wrong mapper
overload deletes it silently.

## Goals / Non-Goals

**Goals:** a design-level answer to four questions — how denials travel out
of a round that throws (FR1); how the position advanced by a failed round's
drain becomes durable without ever leading the record it delimits (FR3,
NFR-R2); how the cursor mechanism becomes *actually wired* in production and
stays wired (FR6, FR9); and how re-reads become idempotent and loss becomes
visible (FR7, FR8).

**Non-Goals:** changing how infrastructure failures are classified or how the
executor port signals them; denial sources and surfaces excluded by the
proposal's NG1–NG5; deduplication across tasks or across sources (identity
is scoped to one denial source's log).

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
Authority: because pushes are best-effort, two copies of a position can
transiently exist. The authoritative one is the branch tip on origin after a
successful push (the ADR 0003 durability point); a local-only position is
advisory. Git makes the pair safe by construction — position and record ride
one commit on one branch, so a push delivers both or neither — and a stale
origin degrades to the D3 re-read, which D5's identity merge turns into a
no-op.
*Alternative rejected:* a factory-private position file beside the guard's
rendered config, advanced after each read — the position and the record then
have two writers with no shared commit, so a crash between them leaves the
position *ahead* of the record and silences the gap: the exact failure D3
exists to rule out; it is also new host state needing disposal wiring and an
orphan sweep. A docker label on the guard — fixed at creation, cannot
advance. Deriving the *cursor* from the timestamps of already-recorded
denials — rejected as a position mechanism (not unique, not monotonic across
sources), but see D5: the same timestamp serves as per-event *identity*,
which is a different use the original rejection conflated with this one.

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

**D5 — Every recorded denial carries the daemon timestamp as its identity;
attachment merges idempotently.**
The guard log line already carries the docker daemon's nanosecond timestamp
(`--timestamps`); today `GuardLogCursor` consumes it for the cursor and
throws it away before the finding is parsed. This change keeps it: a parsed
denial pairs the finding with `(source id, event timestamp)` — unique and
totally ordered within one source — and the persisted denial entries in
`state.json` / `task.json` carry it additively. Attaching denials to a
record merges by identity against the denials already recorded at the
branch tip, so a D3 fallback re-read recovers exactly the unrecorded tail
("re-read: N already present, M recovered") instead of duplicating.
Identity is branch-internal environment bookkeeping like the cursor:
`status.json` and the text render do not show it. The shared domain
`Finding` type is untouched — identity rides a guard-side wrapper and the
persistence DTOs.
*Rationale:* the mature stance from the offset literature — offset as
optimization, identity as correctness. It converts every "position lost"
failure mode (erased field, unpushed commit, mismatched stamp over a
surviving log) from duplicates into a clean merge, and it costs nothing:
the identity is already in every log line.
*Alternative rejected:* content hash as identity — two denials to the same
host/path/method are byte-identical, and *repeats are the signal* a reviewer
wants counted, so a content hash silently collapses real events. No
identity at all — leaves D3 producing visible duplicates, acceptable but
strictly worse for the same read.

**D6 — Loss is reported in-band, through the findings channel.**
When the factory can *see* that denials were lost — `GuardLogCursor`
saturation (the tail cap swallowed lines older than the read window) or a
committed cursor naming a source that no longer holds its log — it emits a
synthetic loss finding ("egress denial log truncated: up to N events lost")
into the same denials list the real findings travel, funnel-fenced like any
finding (NFR-S1). No new report field, no new contract surface: the marker
reaches `state.json` / `task.json` / `status.json` / the text render through
the machinery this change already builds.
*Rationale:* every surviving audit system makes its own loss a first-class
event on the same channel as the data (auditd's `lost` counter, Falco's
drop alerts, Kafka's `OffsetOutOfRangeException`); a WARN in the factory log
is exactly the invisibility this change exists to remove. In-band beats a
side counter because the reader of the report is the person who must know.
*Alternative rejected:* a dedicated `denialsLost` report field — a v1
contract addition and a second render path for strictly less information
than a finding already carries; a metrics counter — nobody reading a task
report sees it.

**D7 — The pair `(findings, position)` gets one owner; the wiring gets a
production spec and a mechanical gate.**
The port-side read returns findings and the advanced position as one value,
and the position becomes durable only through the same call that persists
the record — the invariant lives in one type instead of five classes.
`LeasedEnvironment` gains the three denial forwards; the sibling
`ObservedSandboxLifecyclePass` overrides the three-arg `run` so the
caller's extra sink joins the fanout instead of vanishing through the
inherited default. Two guards keep it fixed: a production-wiring spec that
drives the cursor round-trip through the real `LeasedEnvironment` (M4), and
a named ArchUnit rule in the bootstrap architecture package — a delegating
implementer (same-type delegate field, `Supplier<I>` included) must override
every default method of its interface, with a named allowlist for justified
exemptions (M5). Leaves are untouched: for a leaf the constant default is a
truthful "I don't have this"; only for a delegator is it a lie about
someone else's capability.
*Rationale:* three independent bugs (forgotten forwards, cursorless mapper
overload on RESUMED, drain advancing without a commit) share this one root;
fixing instances without owning the invariant invites the fourth. ArchUnit
1.5.0 is already in the build with eight architecture specs to stand beside.
*Alternative rejected:* splitting the optional denial capability into a
separate `Optional<DenialSource>` accessor interface — it turns a wrong
*answer* into a wrong *object* (the same silent-empty one level up) and
rewrites every implementer for no stronger guarantee than the gate gives;
javadoc discipline alone — that is what just failed.

## Risks / Trade-offs

- [A guard recreated mid-lease restarts its log; a position pointing past it
  would silence every later denial] → the container-identity stamp of D2 makes
  the mismatch detectable, and D3 turns it into a fresh full read.
- [`EscalationReport` is re-exposed by `gnomish-plugin-api`, so a new component
  breaks the api-compat gate] → accept it the documented way, as the
  predecessor did for `AttemptRecord`: pre-1.0 breaking = MINOR api version
  bump (0.3.0 → 0.4.0 as of this writing) plus both `compat-baseline/` jars
  regenerated in this change's diff.
- [`harden-task-branch-contract`'s `putTaskAndState` rewrites `state.json`
  cursorless, so a RESUMED commit erases the committed cursor] → the
  cursor-preservation rule is specified in this change's
  `git-task-persistence` delta; whichever change is still open when the fix
  lands carries the code (see the proposal's coordination notes). Until
  fixed, D5's identity merge caps the damage at a clean re-merge.
- [The tail cap can drop denials before any read, cursor or no cursor] → not
  fully preventable at this layer (the log is the daemon's); D6 makes it
  *visible* in the report, which is the guarantee this change can honestly
  give (G6). Raising retention or moving the log to a volume is future work
  for the sandbox capability.
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
- [`add-serve-sandbox-lifecycle` (archived 2026-08-22) disposes unowned guard
  containers immediately, so a parked task's guard may be gone before the resume] → the
  NFR-R2 case, not a hole: the position shares the log's lifetime, and a
  resume over a missing or recreated guard starts a fresh full read —
  correct rather than a fallback. M2's cross-process scenario deliberately
  keeps the guard alive to exercise the cursor path.

## Migration Plan

None: all document fields — the escalation's denials, `task.json`'s cursor,
and the per-denial identity of D5 — are additive under contract v1. A tip
with no escalation-side cursor restores exactly what today's resume
restores; a denial entry with no identity merges as "unknown, keep" (the
pre-change behavior). The durable principle — a consumed position becomes
durable only with the record it delimits, and known loss is reported
in-band — is added to `docs/adr/0003-crash-consistency.md` in this change,
so later transitions cite the ADR, not this file.

## Open Questions

None.

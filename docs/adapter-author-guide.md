# Adapter author guide: building a `Tracker` port adapter

<!-- implements FR19 of add-tracker-port -->

This guide teaches you how to implement a new `Tracker` port adapter for
Gnomish Factory — for example a Jira or Redmine binding. It is self-sufficient:
every obligation the port contract suite checks is stated here, so you should
not need to read factory core source to build a conforming adapter. Where the
guide names real classes it means them literally — you can navigate to them in
`src/main/java/com/github/oinsio/gnomish/` for the exact code, but you do not
need to in order to build your own adapter.

Two adapters ship today: `adapter/tracker/inmemory` (the executable reference,
`InMemoryTracker`) and `adapter/tracker/github` (the production GitHub
binding). Both live in `src/main/java/com/github/oinsio/gnomish/` alongside the
port itself in `app/port/tracker`. A third adapter — Redmine — is sketched
later in this guide purely as a worked design exercise; it is **not**
implemented.

## 1. The state dictionary and the three-level distinction

The factory reasons about a task at three levels that must never be confused.
Conflating them is the most common adapter-author mistake, so read this
section before touching any code.

```mermaid
flowchart TB
    subgraph L1["Level 1 — Tracker state (your adapter reports this)"]
        Ready
        Working
        AwaitingHuman
        Finished
        Gone
    end
    subgraph L2["Level 2 — Run outcome (the engine produces this; you translate it)"]
        Completed
        Escalated
        Paused
        Aborted
    end
    subgraph L3["Level 3 — Scheduler slot (never touches the tracker)"]
        Idle
        Busy
    end
    L2 -->|"factory core maps outcome to a port call"| L1
    L3 -.->|"never written to the tracker — your adapter has no API for this"| L1
```

- **Tracker state** (`TrackerTaskState`) — the logical state your adapter
  persists and reports: `Ready`, `Working(holder)`, `AwaitingHuman(reason)`,
  `Finished`, `Gone`. This is the only level your adapter is responsible for.
- **Run outcome** — the engine's per-round result (`Completed`, `Escalated`,
  `Paused`, `Aborted`). Core, not your adapter, maps an outcome to a specific
  port call (`finish`, `park`, `recordAbort`). Your adapter never sees an
  outcome type; it only ever receives calls like `park(ref, ParkReason.INFRA,
  report)`.
- **Scheduler slot** — whether a factory instance is currently idle or busy.
  This is process-local bookkeeping and **must never** be written to the
  tracker. There is no port operation for it, and your adapter must not invent
  one (e.g. do not encode "instance X is currently running" as a label or
  custom field — that is a leak of level 3 into level 1).

### Transition matrix

Transitions are initiated only by the factory (via a port call) or by a human
acting directly in the tracker UI — **never** by the gnome process itself.

```mermaid
stateDiagram-v2
    Ready --> Working: factory claims
    Working --> Finished: Completed
    Working --> AwaitingHuman: Escalated / Paused / K-th abort
    Working --> Ready: Aborted (below K)
    AwaitingHuman --> Ready: human returns task
    Working --> [*]: human closes (revocation)
    AwaitingHuman --> [*]: human closes
```

Rules your adapter must uphold:

- The factory claims tasks only from `Ready`. Your `claim` implementation must
  never let a caller acquire a task that is not `Ready` (see the "already
  working" contract row in §3).
- The *only* exits from `AwaitingHuman` are human actions: returning the task
  to `Ready`, or closing it (which surfaces later as `Gone`). Your adapter must
  never auto-transition an `AwaitingHuman` task back to `Ready` on its own.
- `Paused` (a `manual` pipeline checkpoint) appears in the tracker as
  `AwaitingHuman(CHECKPOINT)` — there is no separate "paused" tracker state.
- `ParkReason` has three values: `ESCALATION` (a human decision is needed),
  `CHECKPOINT` (a debug pause), `INFRA` (an environment/pipeline problem needs
  a fix, no decision text required to resume). Your adapter stores and reports
  back exactly the reason it was given — it does not need to interpret it.
- A task outside the factory's world (closed, deleted, or never existed) is
  `Gone`, not an error. `Gone` and "never seeded/never existed" are
  indistinguishable by contract — your `fetchTask` must return `Gone` for both,
  never throw.

## 2. Per-operation port semantics

The `Tracker` interface (`app/port/tracker/Tracker.java`) has exactly fourteen
operations. Implement all fourteen; there is no partial adapter. Ten are the
core feed/coordination/correspondence operations; `recordProgress` persists a
durable-progress marker; and the three lease-maintenance operations `listOpen`,
`heartbeat`, and `removeStaleClaim` keep and reap the claim lease.

| Operation                                                    | Contract                                                                                                                                                                                                                                                                                                                                                                                                                           |
|--------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `List<ReadyTask> listReady(int limit)`                       | Returns unclaimed tasks in **your adapter's own queue order**, each paired with `AbortFacts`. This is the raw feed only — it must **never** filter by abort backoff. Deciding whether an entry with unexpired backoff should be skipped is core policy applied over the facts you report; filtering it yourself breaks the contract.                                                                                               |
| `TrackerTask fetchTask(TaskRef ref)`                         | Returns the full fact set: frozen `TaskSnapshot`, current `TrackerTaskState` (with holder for `Working`, reason for `AwaitingHuman`), and `AbortFacts`. A closed/nonexistent task returns `TrackerTaskState.Gone` — never an exception.                                                                                                                                                                                            |
| `List<HumanReply> collectDecisions(TaskRef ref)`             | Returns human reply comments/replies posted **after the last `acknowledgeDecision` call**, in posting order. Empty means either nobody replied yet, or the most recent reply was already consumed — these two cases are indistinguishable by design.                                                                                                                                                                               |
| `ClaimResult claim(TaskRef ref, String instanceId)`          | Must be **observably atomic**: under a concurrent race, exactly one caller receives `Acquired`, every other caller receives `Held(winner)` naming the actual winner. This is the hardest operation to implement correctly — see §3.                                                                                                                                                                                                |
| `void release(TaskRef ref)`                                  | Drops the caller's claim **without changing the logical state otherwise** — used on a revoked/abandoned task, leaving it for a human to inspect as-is. Do not transition state here.                                                                                                                                                                                                                                               |
| `void park(TaskRef ref, ParkReason reason, String report)`   | Transitions to `AwaitingHuman(reason)`, publishing `report` as finished text. `report` is already-rendered prose — your adapter never receives an engine domain object.                                                                                                                                                                                                                                                            |
| `void finish(TaskRef ref, String summary)`                   | Transitions to `Finished`, publishing `summary` as the final report. Once finished, a task is never touched again by the factory.                                                                                                                                                                                                                                                                                                  |
| `void recordAbort(TaskRef ref, AbortRecord record)`          | **One operation**: persist a structural abort marker (cause, instance, time) **and** return the task to `Ready`. The facts must be reconstructable by any instance from the tracker alone — a fresh `fetchTask`/`listReady` call from a different instance must observe the updated count and last-abort time. Your adapter reports facts; it must **never** apply backoff or the K-abort fuse policy itself — that is core's job. |
| `void recordProgress(TaskRef ref)`                           | Persists a structural durable-progress marker **without changing the logical state or claim holder** — it is the anchor from which a fresh `fetchTask`/`listReady` reconstructs `AbortFacts` as "aborts since this marker". Emission is idempotent within a claim: a second marker is harmless, since reconstruction always anchors to the latest one. Your adapter persists the marker only; core decides when to emit it and how the reset arithmetic works.                                                                                                             |
| `void acknowledgeDecision(TaskRef ref, String decisionText)` | Posts an "acting on decision" marker such that a subsequent `collectDecisions` is empty until a new reply arrives. This is the single mechanism that both records what was acted on and anchors future collection.                                                                                                                                                                                                                 |
| `void postNote(TaskRef ref, String text)`                    | Posts free text without changing logical state — the catch-all for anything that is neither a park report, a finish summary, nor a decision ack.                                                                                                                                                                                                                                                                                   |
| `List<OpenTask> listOpen()`                                  | Returns the **open** tasks — `Working` and `AwaitingHuman` only — each with its logical state; a `Working` entry additionally carries its holder (read from `Working(holder)` inside the state, not duplicated on `OpenTask`) and an opaque **claim version** `ClaimVersion(markerId, updatedAt)`. Never returns `Ready`/`Finished`/`Gone`, and never non-task artifacts (e.g. GitHub pull requests). A `Working` task whose claim marker is missing is reported with an **absent (null)** claim version — core decides what that means. Report version **facts only**; the TTL/staleness policy and observation memory live in core. There is **no `limit`** parameter — the reaper needs the full open set. |
| `HeartbeatResult heartbeat(TaskRef ref, String progressPayload)` | Updates the caller's **existing** claim marker in place — refreshing its version and the human-readable `progressPayload` — with **no new coordination artifact**, one write. Returns `Beaten(newVersion)` on success or `ClaimGone()` when the claim was reaped or taken over — a protocol **signal**, a distinct result, **not** an exception. A task the tracker **no longer holds at all** is the strongest form of this and also returns `ClaimGone()`, never an exception. An infrastructure failure (network, 5xx) is retryable and is signalled by **throwing**; do **not** model it as `ClaimGone`, so a transient outage is never confused with a lost claim.                                                                                                          |
| `RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimVersion observedVersion)` | As **one** operation, given the observed stale version: record a structural holder-transition marker ("stale claim removed") naming the dead holder, remove the dead claim marker, and return the task to `Ready`. It **never** claims the task for the caller. On version mismatch (the claim was beaten, already removed, or replaced) it is a **safe no-op** returning `Mismatch(currentVersion)` — this is what makes concurrent removals converge; when the claim is already gone (or the task itself no longer exists) the current version is **absent (`null`)**. Used by **both** the reaper and a confirmed operator takeover.                                                                                    |

Two cross-cutting rules apply to every operation:

- **Report rendering happens in core.** Every method that carries a report or
  note (`park`, `finish`, `acknowledgeDecision`, `postNote`) receives
  **finished text plus structural fields** — never an engine domain model
  (e.g. never a `StatusReport` object). Your adapter's only job is to persist
  and post that text; it must not attempt to re-render or reinterpret it.
- **`instanceId`/`holder`/`otherInstance` are plain `String`s** in the current
  port shape (task 1.2 of this change); treat them as opaque identifiers.

### Claim-version obligations

The claim version (`ClaimVersion`) is an **opaque pair** `(markerId,
updatedAt)`: a stable marker identity that survives a beat, plus a last-update
fact that advances on every beat. Core treats the whole pair as an opaque token
compared only by content — it never parses either field. Your adapter must:

- **(a) keep the marker identity stable across beats.** A beat is an *in-place
  refresh* of the one claim marker, never a fresh marker — `markerId` must be
  the same before and after a `heartbeat`.
- **(b) advance the version on every beat**, so that another instance's
  `listOpen` observes a different version after the beat. (If nothing observably
  changes, core cannot tell a live lease from a stalled one.)
- **(c) report version facts only — never compute staleness or compare
  clocks.** Core does all timing on its own monotonic clock; it never does
  `now − updatedAt`, and neither may your adapter. `updatedAt` is a fact you
  report, not a deadline you enforce.

### Removal-marker boundary semantics

The "stale claim removed" marker `removeStaleClaim` writes is a **claim
boundary**: it anchors subsequent claim verify-reads exactly like the
release/park/abort/finish boundary markers. After a removal, the next claim
round must consider **only claim markers posted after the removal marker** — so
a dead holder's original claim marker, if a best-effort delete of it failed and
it is still physically present, can **never** win the next earliest-id race. An
adapter's claim/verify logic must therefore treat the removal marker as a
boundary; getting this wrong lets a reaped claim resurrect itself on the next
lease round.

## 3. The contract suite is law

The abstract Spock base classes under
`src/test/groovy/com/github/oinsio/gnomish/app/port/tracker/contract/` form one
chain — `TrackerContract → TrackerMarkerContract → TrackerFetchContract →
TrackerLeaseContract → TrackerHeartbeatContract → TrackerReapContract` — that is
the **single, authoritative** specification of adapter behavior. Every shipped
adapter is required to pass the exact same suite with zero adapter-specific
exemptions. If your adapter passes this suite, it is a conforming `Tracker`
adapter; if it does not, no amount of prose documentation makes it one.

To bind your adapter to the suite, extend the **most-derived**
`TrackerReapContract` (which transitively pulls in the whole chain — not
`TrackerFetchContract`, which stops short of the lease properties) and implement
its four seams:

```groovy
abstract class TrackerContract extends Specification implements PortContractSupport {
    protected abstract Optional<Tracker> arrange()
    protected abstract void seedTask(Tracker adapter, TaskRef ref, TrackerTaskState state, AbortFacts abortFacts)
    protected abstract void seedReply(Tracker adapter, TaskRef ref, HumanReply reply)
}

abstract class TrackerLeaseContract extends TrackerFetchContract {
    protected abstract void seedWorkingWithClaim(Tracker adapter, TaskRef ref, String holder)
}
```

- `arrange()` builds your adapter under test with no fixture tasks loaded yet.
  Return `Optional.empty()` if it cannot be produced in the current test
  environment (e.g. missing WireMock setup) — the suite skips producibility
  gracefully via `assumeProducible`.
- `seedTask(...)` loads one fixture task at a given state and abort-facts
  combination into your adapter's storage, however that storage works
  (in-memory map, pre-stubbed HTTP responses, ...).
- `seedReply(...)` posts one pending human reply on a task, as if a human had
  just replied in the tracker UI.
- `seedWorkingWithClaim(...)` seeds a `Working(holder)` task **with a
  resolvable claim marker** — distinct from `seedTask`, which guarantees only
  that the logical `Working` state round-trips, not that a claim marker with a
  resolvable version exists (a `Working` task whose claim marker is missing is a
  legitimate state the port reports with an absent version). The lease
  properties need an actual marker so `listOpen` reports a **non-null** claim
  version read back through the port.

The properties the suite checks, verbatim:

1. **Feed filtering** — `listReady` returns only `Ready` tasks, in your
   adapter's queue order, and does **not** filter a `Ready` task by unexpired
   abort backoff (that unexpired-backoff task must still come back, carrying
   its real `AbortFacts`).
2. **Claim atomicity** — a race of many concurrent `claim` calls on one
   `Ready` task yields exactly one `Acquired` and every loser receives
   `Held(actualWinner)`. The suite repeats this multiple times to rule out a
   race that merely appeared to pass once.
3. **Claim against non-Ready never yields Acquired** — a task already
   `Working(existingHolder)` refuses every new claimant with
   `Held(existingHolder)`, never `Acquired`.
4. **Abort round-trip** — after `recordAbort`, a fresh `fetchTask` call (the
   contract suite's stand-in for "a different instance") observes the
   incremented count and the exact `lastAbortAt`, and the task is back in
   `Ready`.
5. **Decision ack round-trip** — a seeded reply is returned by
   `collectDecisions` before any ack; after `acknowledgeDecision`, the next
   collection is empty; a later reply posted after the ack surfaces again, but
   an already-acknowledged (stale) reply never resurfaces.
6. **Full fact set on `fetchTask`** — the exact snapshot id, `Working(holder)`
   or `AwaitingHuman(reason)` state, and abort facts you seeded round-trip
   unchanged.
7. **Gone is universal** — both an explicitly-seeded closed task and a
   `TaskRef` your adapter has never heard of report `Gone`, never an
   exception.
8. **`listOpen` filtering** — `listOpen` returns only `Working` and
   `AwaitingHuman` tasks; the `Working` entry carries its holder and a non-null
   claim version, the `AwaitingHuman` entry a null version; `Ready`, `Finished`,
   and `Gone` tasks are excluded, and `listOpen`/`listReady` partition the feed
   (a `Ready` task shows in `listReady` only, a `Working` task in `listOpen`
   only).
9. **Heartbeat versioning** — a beat changes the version another instance reads
   via `listOpen` while the marker identity (`markerId`) stays stable, and the
   version the `Beaten` result reports is exactly the one a later `listOpen`
   shows; a beat after the claim marker was removed reports `ClaimGone` rather
   than throwing.
10. **`removeStaleClaim`** — the round-trip (task back to `Ready`, the dead
    claim gone from `listOpen`, the holder transition recoverable, proved
    port-agnostically by a fresh claim from another instance succeeding with a
    version distinct from the removed one); the version-mismatch no-op (a beaten
    version yields `Mismatch` carrying the live version, task stays `Working`);
    and concurrent-removal convergence (two reapers racing the same observed
    version both return without error, at least one `Removed`, the task ends
    `Ready` exactly once).

`InMemoryTracker` (`adapter/tracker/inmemory/InMemoryTracker.java`) is the
**worked reference implementation** — read it as the model of "the smallest
correct adapter." It holds tasks in a single `LinkedHashMap` keyed by
`TaskRef` (insertion order = queue order for `listReady`) and guards every
operation with one coarse `ReentrantLock`, because `claim`'s
check-decide-mutate sequence must be observably atomic and its critical
sections are microseconds long — per-task locking would add complexity for no
benefit at this scale. It requires no configuration subsection at all, which
is the minimal case of the config seam described in §6.
`InMemoryTrackerContractSpec` shows how a concrete adapter subclass wires
`arrange`/`seedTask`/`seedReply` against it.

## 4. Physical state mapping by example

### 4.1 GitHub labels (as-built)

This section describes the real, shipped `adapter/tracker/github` adapter.

**Label mapping.** Logical states map to mutually exclusive GitHub issue
labels, one label per state, defaults:

| Logical state                | Default label         | Default color                                                  |
|------------------------------|-----------------------|----------------------------------------------------------------|
| `Ready`                      | `gnomish:ready`       | `2ea44f` (green)                                               |
| `Working`                    | `gnomish:working`     | `0366d6` (blue, code default in `GithubTrackerAdapterFactory`) |
| `AwaitingHuman` (any reason) | `gnomish:needs-human` | `d73a4a` (red)                                                 |
| `Finished`                   | `gnomish:delivered`   | `6f42c1` (purple, code default)                                |

All `AwaitingHuman` reasons (`ESCALATION`/`CHECKPOINT`/`INFRA`) share the
single `needs-human` label — the specific reason lives in the report comment
text, not in a separate label, because labels model coarse tracker state and
the reason is finer detail the adapter can recover from its own structural
markers.

State transitions use **point label add/remove calls**, never a whole-set
replace — e.g. moving `Ready` → `Working` removes `gnomish:ready` and adds
`gnomish:working` as two separate calls. This means a human editing unrelated
labels concurrently never loses their edit. Coordination facts (claim holder,
abort count, acks) are never encoded in labels — labels carry only the coarse
logical state; everything else lives in structural comments (see "Structural
markers" below).

A human moving `needs-human` back to `ready` is recognized as "returned to
work"; issue closure is recognized as revocation.

**Labels are provisioned idempotently at startup** as a smoke test
(`GithubLabelProvisioner`, wired from `GithubTrackerAdapterFactory`): missing
configured labels are created with their configured color and a fixed
operator-hint description; an existing label is never recolored; provisioning
failure (e.g. no write access after a fork with a stale binding) fails the
run at startup, before any task is claimed — never mid-task.

**Canonical task id.** `github:owner/repo#42`, built and parsed by
`GithubTaskId`. The host segment is included only when the configured
`api-url` differs from the normalized default `https://api.github.com`
(normalization: trim, lowercase scheme/host, drop one trailing slash) — e.g.
`github:ghe.example.com/owner/repo#42`. The `github:` prefix is a fixed code
constant, never configuration. Core treats this string as fully opaque; only
the GitHub adapter parses or builds it. It flows unchanged into branch names
via the shared `TaskIdSanitizer.branchName(taskId)` (see §7).

**Structural markers.** Coordination facts ride inside GitHub issue comments
as a hidden HTML comment carrying one-line JSON, followed by human-readable
text — rendered and parsed by `GithubMarker`:

```
<!-- gnomish {"kind":"claim","instance":"gnomish-factory-x7k2q1","at":"2026-07-20T12:00:00Z","version":1} -->
🤖 gnomish: claimed by gnomish-factory-x7k2q1
```

GitHub renders HTML comments invisibly, so a human sees only the prose line
while a fresh adapter instance parses `kind`/`instance`/`at`/`version` back out of
the raw comment body. The marker-kind vocabulary (`GithubMarkerKind`) is
`claim`, `abort`, `ack`, `note`, `park`, `finish`, `progress` (a
durable-progress anchor that is not a claim boundary), and
`stale_claim_removed` (a reaper's removal boundary marker) — the wire value is
always the lowercase enum name, decoupled from Java constant naming so the JSON
is stable across refactors. `park` and `finish` are dedicated, structurally
distinct kinds (the `enforce-finish-terminality` change split the retired
dual-use `report` kind into them): a `park` marker additionally carries a
`reason` field (the wire value of `ParkReason`), so a fresh instance's
`fetchTask` recovers the park reason from the marker kind alone — never by
inferring park-vs-finish from whether a `reason` field happens to be present. This exact wire shape is a **recommendation**, not a
contract-spec mandate — the contract spec only requires that abort facts and
decision-ack semantics round-trip; a Jira/Redmine renderer that strips or
mangles HTML comments is free to use a different structural encoding (see the
Redmine sketch below).

**Claim lease.** `GithubClaimLease` implements `claim` as a lease sequence; the
atomicity properties and race edge cases it must satisfy are the contract law
in §3 (claim atomicity, claim-against-non-Ready). The live claim comment for a
task is resolved by `GithubClaimComment`: the **earliest-comment-id `claim`
marker posted after the latest boundary marker** — the same "earliest id since
the newest boundary" rule the lease race uses, carrying the comment's `id` and
`updated_at` so a `ClaimVersion` can be reported. `listOpen`, `heartbeat`, and
`removeStaleClaim` all reuse this one resolver rather than each re-deriving the
winner.

**Lease maintenance.** The three lease operations map onto GitHub as follows,
all built on the claim comment resolved above:

- **`heartbeat`** (`GithubHeartbeat`) = a **PATCH (edit) of the existing claim
  comment**. The comment id — the earliest-id lease anchor — never changes; the
  body keeps the structural `claim` marker and refreshes the human-readable
  progress line. It is **one write**, and the reported claim version is
  `(comment id, updated_at)` read from the PATCH response. A **404** on the edit
  (the comment was deleted by a reaper or takeover), an unresolvable claim
  comment, or a **404** on the comment listing (the issue itself is gone) all map
  to `ClaimGone`; a non-404 non-2xx, 5xx, and network failures are retried and,
  once exhausted, propagate as a **thrown** infrastructure failure — never a
  lost-claim result.
- **`listOpen`** (`GithubOpenQuery`) = **List Issues by the working label and by
  the needs-human label** — two conditional GETs with `If-None-Match`/ETag, so
  an unchanged poll is a free `304` that consumes no rate limit, with PR entries
  excluded. For each `Working` issue it resolves the claim comment → holder plus
  `(comment id, updated_at)`; a working-labeled issue with **no** claim comment
  is reported with an **absent** version.
- **`removeStaleClaim`** (`GithubStaleClaimRemoval`) = **re-read the claim
  comment fresh, compare `(id, updated_at)` to the observed version** (mismatch
  → no-op `Mismatch`); on a match: **POST** the `stale_claim_removed` boundary
  marker (hidden-JSON convention, naming the dead holder), **DELETE** the dead
  claim comment (all instances operate under one token, so deletion is
  physically possible), and **flip the working label back to ready** — all point
  calls. Racing removals converge: a second remover finds the comment already
  gone and the label already flipped, both harmless — as does a **404** on the
  re-read (the issue itself is gone), a no-op `Mismatch(null)`, never an
  infrastructure failure. The `stale_claim_removed`
  marker kind joins the **claim-boundary set** (`abort`, `park`, `finish`,
  `stale_claim_removed`) the re-claim verify-read anchors on, so a dead claim
  comment whose delete did not land can never win the next lease round.

### 4.2 Redmine statuses — a thought-through sketch (NOT implemented)

Everything in this subsection is a design exercise to show how the same
model would map onto a different tracker's primitives. No Redmine adapter
exists in this codebase; do not treat any class name below as real.

Redmine issues have a configurable status workflow (e.g. `New`, `In
Progress`, `Feedback`, `Closed`) plus a `Notes` journal on every update, but
lacks GitHub's point label add/remove primitive — a status is a single field,
not a set. A sketch mapping:

| Logical state                | Redmine status (sketch)                                                                                                            |
|------------------------------|------------------------------------------------------------------------------------------------------------------------------------|
| `Ready`                      | `New`                                                                                                                              |
| `Working`                    | `In Progress`                                                                                                                      |
| `AwaitingHuman` (any reason) | `Feedback`                                                                                                                         |
| `Finished`                   | `Closed` (with a specific `done_ratio`/resolution)                                                                                 |
| `Gone`                       | issue `status.is_closed == true` for a reason other than the factory's own `Finished` resolution, or the issue id does not resolve |

Because Redmine status is a single scalar (not point add/remove), a
hypothetical `RedmineTracker.claim` could not rely on a label-transition race
the way GitHub does — instead it would need Redmine's optimistic-locking
`lock_version` field on issue update: read the issue, attempt a conditional
PUT incrementing `lock_version`, and treat a 409 conflict as "someone else
moved first," then re-read to discover the actual winner from the journal. The
coordination-fact markers (claim holder, aborts, acks) would ride as
structured text inside journal `notes` entries — Redmine does not render HTML
comments invisibly the way GitHub does, so a sketch encoding might instead use
a fenced, clearly-machine-labelled block (e.g. a line starting with a fixed
`[gnomish:claim]` prefix followed by inline JSON) rather than GitHub's hidden
comment trick, accepting that this metadata is visible to a human reading the
journal. `Redmine`'s `custom_fields` are a further sketch option for a
dedicated `gnomish_holder` field, but that requires the field to be
provisioned per-project ahead of time, unlike GitHub's issue comments which
need no upfront schema.

This sketch is meant to illustrate that the *port's* three-level model and its
fourteen operations transfer cleanly to a tracker with very different physical
primitives (scalar status vs. label set, optimistic-lock PUT vs. comment-order
race, visible journal notes vs. invisible HTML comments) — the adapter's job
is exactly this translation, and the contract suite is what proves the
translation preserves the required semantics.

## 5. Snapshot, decision, and abort-fact obligations

- **Snapshot (`TaskSnapshot`).** Captured once, at first claim: `id`, `title`,
  `body`, frozen from that point on. Once captured into the task's persisted
  state, later edits to the live tracker issue must **never** retroactively
  change the running or parked task's view of itself — resume only ever
  collects new human decisions via `collectDecisions`, it never re-reads the
  live tracker task's title/body. Your `fetchTask` still returns the *current*
  snapshot for a fresh `fetchTask` call (there is no port operation that
  freezes the snapshot on your side); the freezing happens in core's own
  persisted state after the first successful claim. Your obligation is just to
  return an accurate, non-blank `id`/`title` and a possibly-empty `body`.
- **Decisions (`HumanReply`).** `collectDecisions` must return replies
  strictly in posting order, and strictly those posted after the last
  `acknowledgeDecision` call on that task. Your adapter decides its own
  pairing heuristic for "which comments/updates count as a human reply" (e.g.
  GitHub: any comment that is not itself a `gnomish` structural marker) — this
  heuristic is explicitly adapter freedom under the round-trip law; the
  contract suite does not prescribe how you recognize a reply, only that once
  recognized, ordering and the ack-anchor rule hold.
- **Abort facts (`AbortFacts`/`AbortRecord`).** `count` is "aborts since last
  durable progress" — it is **not** a lifetime counter; something else (core)
  resets it, your adapter just needs to make its history reconstructable so
  that a correct count is derivable at any time. `recordAbort` must, in one
  operation, both persist the marker (`cause`, `instance`, `at`) and return
  the task to `Ready`. The facts must be recoverable by **any instance** — not
  just the one that recorded them — purely from what you persisted in the
  tracker; do not cache abort counts in adapter process memory as the source
  of truth. Your adapter reports facts only: it must never itself decide to
  apply exponential backoff or trip the K-abort fuse. That policy is computed
  by core from the facts you report (`delay = base × 2^(count−1)`, capped) —
  baking any of that arithmetic into your adapter duplicates policy that can
  drift from core's.

## 6. Config-subsection ownership

Your adapter owns exactly one subsection of `.gnomish/config.yaml`:
`tracker.<your-type>`. The rules:

- **Never read or validate core keys.** `tracker.type` and
  `tracker.abort-threshold` belong to core; your adapter's validator receives
  only its own subsection map and must not reach outside it.
- **Declare and validate your subsection yourself**, by implementing a
  `TrackerSubsectionValidator` (see `GithubTrackerSubsectionValidator` for the
  worked example) that aggregates every problem it finds and reports them as
  `ConfigError`s — fail fast and all-at-once at load time, consistent with the
  rest of pipeline-config error reporting. Do not throw on the first error.
- **No credential-shaped key may appear in yaml, ever.** The GitHub validator
  rejects any subsection key that normalizes (hyphens/underscores stripped,
  lowercased) to something containing `token`. Apply the equivalent check for
  whatever your tracker's credential concept is named (API key, PAT, app
  password, ...).
- **Read credentials from environment variables only**, at adapter
  construction time — never from yaml, never persisted, never logged. The
  GitHub adapter reads `GNOMISH_GITHUB_TOKEN` via `System.getenv(...)` inside
  `GithubTrackerAdapterFactory`, and fails construction with a named exception
  (`GithubTrackerConfigException`) if it is missing or blank.

### 6.1 Mandatory: declare your credential env vars for the scrub

This is not optional. `TrackerAdapterFactory` (`app/TrackerAdapterFactory.java`)
declares a `credentialEnvVars()` method:

```java
default List<String> credentialEnvVars() {
    return List.of();
}
```

The default is an empty list, correct for a credential-free adapter (the
in-memory reference overrides nothing). **Any adapter that reads a credential
from the environment MUST override this method** and return every variable
name it reads, e.g.:

```java
@Override
public List<String> credentialEnvVars() {
    return List.of(TOKEN_ENV_VAR); // "GNOMISH_GITHUB_TOKEN"
}
```

Why this matters: the wiring hands the *active* adapter's declared list to the
agent process launcher, which strips every named variable from the gnome's CLI
subprocess environment — regardless of the
`factory.agent-cli-env-passthrough` setting. The **same declared-scrub list also
applies to command checks**: a command-check subprocess (the `command` verify
type) runs with the factory environment minus the active adapter's declared
credential variables, so your declared vars are excluded from **both** the agent
process **and** command-check processes — neither untrusted subprocess ever sees
tracker credentials. This is a hard security boundary:
the gnome (the untrusted agent-CLI subprocess doing the actual task work) must
never see tracker credentials, because it could otherwise exfiltrate them or
use them to manipulate the tracker directly, bypassing the factory's
coordination invariants entirely. If you forget to declare your credential
variable name here, it leaks straight into the gnome's environment with no
other safety net — there is no hardcoded fallback list in the launcher. This
is by design (design decision D17): a hardcoded list in the launcher would
mean every new adapter has to edit executor code; declaring the names on your
own adapter's registration seam keeps the `Tracker` runtime port at exactly
its fourteen operations while staying extensible.

## 7. Known limitations

- **Branch-name sanitize collisions.** Task branch names and worktree
  directory names are both derived from the canonical task id via the shared
  `TaskIdSanitizer.sanitize`/`branchName` (in `adapter/git`, reused unchanged
  by every tracker adapter): every character outside `[A-Za-z0-9._-]` becomes
  `-`, repeated `-` collapse, and leading/trailing `.`/`-` are stripped. This
  is **lossy and unguarded against collisions** — two different canonical ids
  that sanitize to the same string will collide on the same branch. It is
  deliberately not your adapter's job to prevent this: the authoritative task
  id always lives in the task's persisted state file, never parsed back from
  a branch or directory name, so a collision is a git-workflow-layer concern,
  not a tracker-port one. If your tracker's native ids are exotic (e.g.
  contain many non-ASCII or punctuation-heavy characters), be aware that
  distinct ids can still sanitize identically; this is accepted risk for v1,
  not something an adapter can independently fix.
- **Polling economy — the GitHub analysis as the model.** Any tracker
  reachable only via periodic polling (no inbound webhooks — the factory has
  no inbound HTTP by architecture) should budget its request volume the way
  the GitHub adapter does: use conditional requests wherever the backend
  supports them (GitHub: `If-None-Match`/ETag, treating `304 Not Modified` as
  "no change" without consuming rate limit), and design steady-state,
  single-task operation to stay comfortably inside the tracker's request
  budget (GitHub's primary limit is 5000 req/h; a state transition there costs
  2-3 point-write calls). If your target tracker has no conditional-request
  primitive, the fallback is coarser polling intervals plus caching the last
  successful read — but any such adapter should document its own rate-limit
  math the same way this section documents GitHub's, so an operator can reason
  about polling load before deploying it.
- **The considered-and-rejected surrogate-id approach.** When a GitHub
  repository is renamed, an issue's `owner/repo` in a previously-minted
  canonical id can go stale. The adapter tolerates this by resolving the id's
  repo with one `GET /repos/{owner}/{repo}` call and following GitHub's own
  rename redirect: if the redirect target equals the configured binding, the
  operation proceeds with a WARN log; otherwise it refuses with an error
  naming both repos. An alternative was considered and explicitly rejected:
  using GitHub's immutable internal `node_id` as a surrogate key instead of
  the human-readable `owner/repo#number` form. It was rejected because it
  would make canonical ids and branch names unreadable to a human skimming
  branch lists, would require an extra GraphQL round-trip to resolve
  (`node_id` is not returned by the REST issue-number-based endpoints this
  adapter otherwise uses), and has no analogue in most other trackers (a
  Jira/Redmine adapter would need to invent its own concept of a rename-stable
  surrogate id from scratch). If you are building an adapter for a tracker
  that also supports renaming a project/repo/space, follow the redirect
  -verification pattern above rather than reaching for a surrogate key — it
  keeps ids readable and keeps your adapter's canonical-id logic a pure
  string operation the rest of the time.

# Design — add-tracker-task-hierarchy

## Context

See proposal.md — Why. Current state (verified by audit 2026-08-29): the
`Tracker` port has sixteen operations, none of which create tasks; the task
model is `(id, title, body)` plus coordination facts; the GitHub adapter is
REST-only with a strict no-fan-out feed (List Issues + conditional requests);
claim selection is core-owned in `FeedPolicy` over adapter-reported facts
(design rule "the adapter never filters"). GitHub's sub-issues API and issue
dependencies (blocked-by) are both GA and REST-complete. This change is the
first slice of the epic-decomposition roadmap; the decompose verdict and the
decision roll-up protocol are follow-up changes and constrain this one only
through the recovery affordances they will need (child listing with stable
keys). Context: driven by FR1–FR5, NFR-R1, NFR-P1 from proposal.

## Goals / Non-Goals

**Goals:**
- Additive port growth: existing adapters keep compiling and passing the
  existing contract suite untouched by default facts (no parent, no children,
  not blocked).
- Every new fact follows the established fact-vs-policy split: adapters
  report, `FeedPolicy` decides.
- Creation is convergent under crash/retry with a caller-supplied stable key,
  because the next change's decomposition recovery depends on it.

**Non-Goals:**
- No consumer of hierarchy facts beyond the feed filter ships here (no
  decompose verdict, no context assembly changes, no briefing changes).
- No hierarchy rendering in dashboards/status surfaces.
- No Projects/milestone metadata; no GraphQL.

## Decisions

### D1. Hierarchy as facts on existing types, not a new port

Hierarchy facts ride the existing fact records: `ReadyTask` gains
`dependencyBlocked` (like `returned`/`finished`), and `fetchTask`'s fact set
gains parent ref and children. Alternative — a separate `TaskHierarchy` port —
rejected: it would split one tracker's truth across two ports and force
double-fetching; the project's precedent (abort facts, claim facts) is facts
on the single `Tracker` port.

### D2. Create-subtask on the Tracker port with caller-supplied stable keys

`createSubtask(parentRef, childKey, title, body, blockedBy)` returns the new
ref or `AlreadyExists(existingRef)`. The stable key is caller-supplied (the
future decomposer commits its plan with keys before creating anything —
intent → effect → receipt per `crash-consistency.md`), and the adapter must
make keys discoverable via child listing. Alternative — adapter-generated ids
with a caller-side ledger — rejected: recovery would then depend on the
caller's ledger surviving the same crash that interrupted creation; a key
discoverable from the tracker itself makes the tracker the single recovery
source. GitHub mechanism: the key is recorded durably on the child issue in
adapter-owned form (marker in body on creation, same family as existing
structural markers); the in-memory adapter stores it directly.

### D3. Creation write order: identity before edges

Within `createSubtask` the durable steps are ordered: create child issue
carrying the key → link under parent → add blocked-by edges. Kill windows:
(w1) issue exists with key but no parent link — recovered because the key
marker is self-contained and a retry finds the issue by key scan of the
parent's candidate children and completes the link; (w2) linked but edges
missing — retry returns `AlreadyExists` and the caller completes edges. One
recovery owner: the caller's retry of the same `createSubtask` call (the port
operation is the convergence point; adapters implement lookup-before-create).
Both windows freeze states the next change's decomposition sweep can
enumerate (child sets are listable). Constructive-before-destructive is
trivially satisfied — nothing destructive exists in this transition.

### D4. Blocked fact piggybacks the conditional-request economy

The feed must not add per-task calls per poll (NFR-P1, and spec'd polling
economy). Dependency state is read only when a task's feed representation
changes (ETag/updated_at already tracked by the adapter); unchanged tasks
reuse the cached fact. Trade-off accepted: a blocker closing does not bump
the blocked task's feed representation, so unblocking is observed lazily — on
the blocked task's next representation change or cache expiry (bounded
staleness, tunable TTL). Alternative — always-fresh per-poll dependency reads
— rejected as a direct violation of the feed's no-fan-out rule; alternative —
deriving blockedness core-side from children states — rejected: blocked-by is
an edge distinct from parent/child and only the tracker knows it.

### D5. Filter placement in FeedPolicy beside backoff

`FeedPolicy.selectClaimCandidates` drops `dependencyBlocked` entries in the
same pass that applies backoff filtering, before the returned/fresh split, so
WIP and head-zone math see only claimable tasks. No decline, no write — a
blocked task is simply invisible to selection, unlike finished tasks (which
carry a decline protocol). Serve's `FeedCycle` inherits the behavior through
the shared policy — one owner, no scatter.

### D6. Sync surfaces

Scout results (2026-08-29): `grep -rn "Kept in sync with" */src/main` yields
only the `WorktreeSalvage`/`EnvironmentSalvage` pair; the registry in
`.claude/rules/manual-sync-pairs.md` lists no pair touching the tracker port,
adapters, or the take feed path. This change touches no declared pair. It
does extend the one *governed* parallel-implementation surface it meets — the
GitHub and in-memory adapters implementing the same port — whose
synchronization mechanism is the shared abstraction already in place: the
port interface plus the port-level contract suite that both adapters must
pass (`tracker-port` spec, "Port contract spec suite binds every adapter").
Extending that suite for the new operations is therefore a task, not a new
pair; no `Kept in sync with` markers are needed. `FeedPolicy` remains the
single owner of selection policy for both take and serve, adding no twin.
Sync surfaces: no declared pair touched; no new undeclared pair created.

## Risks / Trade-offs

- [Lazy unblocking (D4) delays claimable tasks] → bounded by cache TTL; the
  follow-up decompose change can force-refresh children of a just-finished
  task, the natural unblocking moment.
- [GitHub sub-issue/dependency endpoints are newer API surface; WireMock
  fixtures may drift from live behavior] → contract fixtures recorded from
  documented payload shapes; E2E Gitea layer does not cover these (Gitea has
  no sub-issues API) — GitHub-specific scenarios stay at the WireMock layer
  and the limitation is recorded in the adapter guide.
- [Key marker in issue body is adapter-visible to humans] → same trade-off
  already accepted for structural comments; marker is compact and documented.
- [Additive record growth touches many constructors] → parameter-object
  precedent (`TrackerFacts`) applies; stay under the 7-parameter rule.

## Open Questions

- Cache TTL default for the blocked fact (operational tuning; any value
  preserves correctness, only claim latency varies) — settle during
  implementation with the existing polling-economy constants.

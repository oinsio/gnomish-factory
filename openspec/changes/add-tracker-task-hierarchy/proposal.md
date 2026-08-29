# Proposal: add-tracker-task-hierarchy

## Why

Epic decomposition (a planning stage verdicts "this is an epic" and materializes
subtasks) and context inheritance (a subtask's gnome sees decisions already made
in its parent and earlier siblings) both presuppose that the factory can *see*
and *create* task relationships in the tracker. Today it cannot: the tracker
port models a task as exactly `(id, title, body)`, has no task-creation
operation, no notion of parent/child or blocked-by, and the claim feed will
happily hand an instance a task whose prerequisites are still open. This change
lays that foundation — hierarchy facts, a creation operation, and a
dependency-aware claim filter — so the follow-up changes (decompose verdict,
decision roll-up) build on a tracker that already tells the truth about task
structure. GitHub's native primitives are GA (sub-issues since 2025-04, issue
dependencies since 2025-08), so no bespoke medium is needed.

## What Changes

- ADDED: hierarchy facts on the tracker port's task model — a task knows its
  parent (if any), its children with their terminal/open states, and whether it
  is **dependency-blocked** (has unresolved blocked-by edges). (FR1)
- ADDED: a task-creation operation on the tracker port: create a subtask under
  a parent task, with title, body, and blocked-by edges to sibling subtasks;
  returns the new task's ref. Designed for idempotent recovery: children of a
  parent are listable, and each created subtask carries a caller-supplied
  stable key discoverable on re-listing. (FR2)
- ADDED: the ready feed reports a dependency-blocked fact per task without
  per-task request fan-out, following the existing `ReadyTask` fact pattern
  (adapter reports facts, core filters). (FR3)
- MODIFIED: claim selection (`FeedPolicy`, shared by take and serve) excludes
  dependency-blocked tasks from claim candidates, alongside the existing
  backoff and finished exclusions. (FR4)
- MODIFIED: the GitHub adapter implements the above via the REST sub-issues
  and issue-dependencies endpoints, walking hierarchy per level (no deep
  GraphQL); the in-memory reference adapter mirrors the behavior; the
  port-level contract suite grows to cover the new surface so both adapters
  pass the same specs. (FR5)
- Non-goals: the decompose stage verdict and child creation protocol (next
  change), decision roll-up / context inheritance (change after), pipeline
  routing by task type, hierarchy depth beyond parent→child (factory uses
  depth ≤ 2), milestones/Projects metadata. (NG1–NG5)

## Capabilities

### New Capabilities

_None — all changes extend existing capabilities._

### Modified Capabilities

- `tracker-port`: task model gains hierarchy facts (parent, children,
  dependency-blocked); new create-subtask operation with stable-key
  idempotency; contract suite covers both adapters.
- `github-tracker`: adapter maps hierarchy facts and creation onto GitHub
  sub-issues + issue-dependencies REST endpoints; feed enrichment carries the
  blocked fact within the existing no-fan-out budget.
- `tracker-take`: claim selection excludes dependency-blocked tasks; the
  feed-policy spec's exclusion list grows by one clause.

## Impact

- `gnomish-plugin-api` tracker port types (`Tracker`, `ReadyTask`,
  `TaskSnapshot`/`TrackerTask` fact records) — additive API growth.
- `adapters/github` — new endpoint calls (sub-issues list/create, dependencies),
  feed parser enrichment.
- `adapters` in-memory tracker — mirrored hierarchy/creation support plus test
  seeding hooks.
- `application` take/serve feed path — one new exclusion step in `FeedPolicy`;
  `TakeBareAuto` and serve's `FeedCycle` pick it up through the shared policy.
- Requirement IDs: FR1–FR5, NFR-R1 (idempotent creation/recovery),
  NFR-P1 (no feed fan-out), NG1–NG5. Uniqueness scoped to
  `add-tracker-task-hierarchy` per `traceability.md`.

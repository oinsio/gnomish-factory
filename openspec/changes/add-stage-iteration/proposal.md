# Proposal: add-stage-iteration

## Why

A stage that implements a plan produced by a previous stage (a tasks.md
checklist) today runs as one monolithic agent context: item 12 is executed
by a process dragging the transcript of items 1–11, quality degrades with
context length, a failure anywhere burns a whole-stage attempt, and the
engine cannot say which item is done. Industry practice (fresh context per
work item, externalized plan, engine-owned progress) is the documented
answer; the factory needs it as a first-class, crash-consistent stage
construct.

## What Changes

- ADDED: an `iterate:` section in the stage manifest — the engine loops
  over the checklist items of an input artifact, one fresh agent round set
  per item, with an engine-owned cursor and a frozen item-identity
  snapshot.
- ADDED: item lifecycle (pending / in-progress / completed / partial),
  per-item attempt limits, per-item cheap verification with full stage-end
  verification.
- ADDED: adoption of newly discovered items at item boundaries under a
  discovery budget; plan-invalidation raised as a decision request.
- MODIFIED: task state persists the cursor, item snapshot, and per-item
  progress records additively; status renders per-item progress.

## Capabilities

### New Capabilities

- `stage-iteration`: the iteration construct — manifest section, item
  snapshot and cursor semantics, item lifecycle, adoption protocol,
  budgets, per-item vs stage-end verification, escalation and resume.

### Modified Capabilities

- `pipeline-config`: `stage.yaml` gains the optional `iterate:` section
  with validation; the plan-producing stage's conventions.
- `stage-engine`: the attempt loop gains an intra-stage item loop; a
  per-item pass does not end the stage; per-item attempt accounting;
  plan-invalidated verdict arm.
- `git-task-persistence`: `state.json` gains cursor, item snapshot, and
  per-item progress records additively; per-item transitions are
  single-commit.
- `task-inspection`: `status` renders item progress (k of N, discovered
  count) for iterating stages.

## Goals

- G1: each item executes in a fresh agent context that receives the plan,
  the frozen law, and a structured summary of completed items — never the
  transcript of previous items.
- G2: the engine, not the gnome, owns progress truth: an item is complete
  only when its verification passed, regardless of checkbox glyphs.
- G3: the plan cannot shrink: no gnome edit can remove or reorder frozen
  items; growth is bounded and attributed.
- G4: any factory instance resumes mid-list from the durable cursor with
  no lost or repeated paid work.

## Non-Goals

- NG1: no dynamic pipeline expansion — an item is not a stage; the
  pipeline stays a static ordered list from the law.
- NG2: no parallel item execution — items are sequentially dependent by
  the plan format's own construction; the loop is strictly ordered.
- NG3: no freeze of item body text — gnome edits and annotations of item
  bodies are legitimate re-planning records; verification law gates
  quality, not text (rejected alternative recorded in design).
- NG4: no new plan format — the item syntax is the markdown checklist the
  planning practice already produces.

## Users & Scenarios

- U1: A plan stage commits a 30-item tasks.md; the implement stage
  declares `iterate:` and works item by item; the operator watches
  "item 14/30" in status and the tracker heartbeat.
- U2: Item 7 exhausts its attempts; the task escalates naming item 7 with
  its findings history; the human adjusts, resumes, and any instance
  continues from item 7.
- U3: While on item 9 the gnome discovers a missing migration step; it
  appends a new item, the engine adopts it at the boundary within the
  discovery budget, and status shows "+1 discovered".
- U4: On item 5 the gnome recognizes the remaining plan is built on a
  wrong assumption; it raises plan-invalidated; the decision tier (arbiter
  if configured, else human) rules on how to proceed.

## Requirements

### Functional

- FR1: `stage.yaml` MAY declare `iterate:` naming the checklist input
  artifact, `maxItems`, `maxDiscoveredItems`, and `attemptLimitPerItem`;
  absent section = today's whole-stage behavior unchanged.
- FR2: On stage entry the engine SHALL parse the checklist once and
  freeze an ordered item-identity snapshot in the task state; iteration
  order and completion truth derive from the snapshot and cursor only,
  never from checkbox glyphs.
- FR3: Each item SHALL have a lifecycle pending → in-progress →
  completed | partial, with at most one item in-progress; `partial` is a
  verified pass whose record carries the gnome's declared scope
  reduction.
- FR4: An item SHALL execute as one or more fresh agent rounds whose
  prompt carries the frozen law, the current plan document, the item focus
  ("your task is exactly item X"), in-scope decisions, and the structured
  progress records of completed items — never prior transcripts.
- FR5: Per-item verification SHALL run the manifest's designated cheap
  checks each item; the remaining verify chain SHALL run once at stage
  end. A stage-end quality failure SHALL re-enter the loop as a repair
  item carrying the findings, not as a whole-stage restart.
- FR6: An item pass SHALL be recorded without ending the stage: cursor
  advance, item completion record, and the round's artifacts land in one
  commit; the stage completes only when the snapshot is exhausted and
  stage-end verification passes.
- FR7: Disappearance of a frozen item id from the plan document SHALL be
  a quality failure of the round; item body edits and annotations SHALL be
  legal and SHALL leave an observability trace when a not-yet-completed
  item's body changed.
- FR8: New item ids SHALL be adopted only at item boundaries: append-only
  relative to frozen items, deduplicated by content hash against every
  seen item, provenance-marked as gnome-discovered in later prompts, and
  counted against `maxDiscoveredItems`; human-era additions (between park
  and resume) count only against `maxItems`.
- FR9: Discovery-budget overflow SHALL raise a decision request whose
  payload is the pending adopted items; plan-invalidated and item-obsolete
  verdicts from an item round SHALL likewise be decision requests — routed
  to the decision tier (arbiter when configured, else human park).
- FR10: Exhausting `attemptLimitPerItem` SHALL escalate the whole task,
  naming the item and its findings history across attempts; resume SHALL
  continue from the cursor on any instance.
- FR11: An item round producing no parseable structured result SHALL be
  an infrastructure failure of the round — no attempt burned, retried per
  policy.
- FR12: Each completed item SHALL leave a structured progress record
  (what was done, deviations, what the next item must know), durable on
  the task branch, injected into subsequent item prompts.

### Non-Functional Reliability

- NFR-R1: Every multi-step item transition satisfies the crash-consistency
  checklist: an in-progress item at pickup is a named shape whose recovery
  re-runs the item's round on the working copy as it stands (rework, not
  restart); kill-point specs cover each new window and assert a no-op
  second recovery.
- NFR-R2: The cursor survives every lifecycle rewrite of the state file
  (the cursor-preservation rule owned by
  `fix-denial-attribution-durability` extends to it).

### Non-Functional Security

- NFR-S1: Item content is data, never law: item text reaches prompts as
  delimited task data; adopted items additionally carry unreviewed
  provenance; no item can alter instructions, criteria, checks, or
  budgets.

### Non-Functional Observability

- NFR-O1: Item boundaries leave a structured log line (item id, verdict,
  adopted count); heartbeat progress and `status` carry "item k/N
  (+d discovered)".

### Non-Functional Cost

- NFR-C1: `maxItems` and `maxDiscoveredItems` are engine walls enforced
  at parse and adoption time; a plan exceeding `maxItems` at stage entry
  is a quality failure of the planning stage's output. The planning
  stage's instructions state the budget so the wall rarely triggers.

## Operator Experience Criteria

- UX1: `status` on an iterating stage answers "which item, how many left,
  how many discovered" in one line.
- UX2: An escalation names the stuck item and shows only that item's
  findings history first, with the rest available below.
- UX3: The plan document on the task branch remains a readable, honest
  history: engine truth (cursor) may disagree with checkboxes, and the
  disagreement is logged, never silently corrected.

## Success Metrics

- M1: Kill-point matrix: a kill after every durable step of the item loop
  (item commit, adoption commit, repair-item entry) converges to a named
  shape and a no-op second recovery.
- M2: A mid-list resume by a second instance repeats zero completed items
  and re-runs at most the in-progress item (paid-work bound).
- M3: A plan-shrink attempt (deleting a frozen item id) never yields a
  completed stage; the round records a quality failure.

## Open Questions

- Q1: Which check types are eligible for the per-item designation —
  proposal: `builtin` and `command` only (`external` and `judge` stay
  stage-end); confirm when the manifest schema lands.
- Q2: Default `maxDiscoveredItems` — proposal: 10; confirm against real
  plan sizes (observed 30–60 items).

# Design: add-stage-iteration

## Context

Driven by FR2–FR10, NFR-R1/R2, NFR-S1 of this change's proposal. Shaped by
the architect session (2026-08-29): external research confirmed the core
(fresh context per item, engine-owned cursor, verify-decides-completion,
boundary-only adoption) and supplied the corrections below; the code audit
located the engine invariants the implementation must respect. The sharpest:
`TaskState.startOfStage()` treats any trailing `PASSED` round as "stage
finished" and discards the attempt list on resume — a per-item pass
recorded as `PASSED` would silently erase item history.

## Decisions

**D1 — The cursor is an additive state field, not a Position variant.**
The iteration state (cursor, item snapshot, per-item attempt count,
progress records) lands in `state.json` as a sibling of `position`,
following the `egressCursor` precedent (additive under v1, absent = not
iterating; old builds ignore it). `Position` stays `AtStage | PipelineEnd`.
*Rationale:* the audit showed the sealed `Position` is mirrored in two wire
DTO trees and gated by a version check — a new variant is a wire-breaking
change; the additive-field precedent is established and tested.
*Alternative rejected:* `Position.AtItem(stage, item)` — cleaner domain
reading, but breaks every position switch and both wire vocabularies for
no behavioral gain.

**D2 — A per-item pass is a new TaskState transition, never `PASSED`.**
Item completion is recorded by a dedicated transition
(record-item-pass-and-hold: append the round, advance the cursor, keep the
stage position and the attempt history). The existing
`recordPassAndAdvance` fires only when the snapshot is exhausted and
stage-end verification passes. *Rationale:* the audit's sharpest finding —
`startOfStage()` and the resume path interpret a trailing `PASSED` as
stage-done and would discard item history. *Alternative rejected:*
reusing `PASSED` plus a cursor check at every consumer — inverts a
load-bearing invariant asserted in multiple places; guaranteed divergence.

**D3 — Freeze identity and order, not bodies.** The stage-entry snapshot
records ordered item ids (and a content hash per item for dedup and
change observability). Item body edits, annotations, and `[~]` partial
marks are legal gnome re-planning records; a changed body of a
not-yet-completed item leaves a log line, never a failure. Id
disappearance is a quality failure. *Rationale:* verification law
(frozen acceptance criteria + stage-end judge) is what gates quality —
freezing plan text would guard a door the law already guards, at the cost
of banning legitimate practice (observed in this repo's own tasks.md
files: deviation notes, partial marks, reworded items). *Alternative
rejected (steelman):* hash-freezing unpassed item bodies — it does close
the "weaken the work order mid-attempt" path and time-partitions trust
cleanly (human edits between park and resume re-blessed on resume), but
the closed path only reaches the prompt, not the verdict; the cost
(forbidding honest re-planning) buys defense the law already provides.

**D4 — Item lifecycle with at-most-one in-progress.** Items are pending /
in-progress / completed / partial; the engine marks in-progress in the
same commit that starts the item's first round. *Rationale:* an
in-progress item at pickup **is** the kill-window shape — resume
re-enters that item's round on the working copy as it stands (rework
semantics, identical to today's attempt retries), which answers the
at-least-once concern without per-item receipts beyond the cursor commit:
cursor advance and item artifacts are one commit, so "effect landed but
cursor didn't" cannot freeze. `partial` is a verified pass carrying the
gnome's declared scope reduction — the stage-end judge rules on whether
the reduction is acceptable. *Alternative rejected:* deriving progress
from checkbox glyphs — gnome-owned representation as engine truth is the
reward-hacking front door.

**D5 — Prompt = whole plan + focus + progress records, no transcripts.**
Each item round receives the full plan document (completed items with
their annotations included), the item focus, in-scope decisions, and the
structured progress records (FR12). *Rationale:* real plans are
cross-referential (sequencing notes, "coordinate with block 1"); excising
the item loses meaning, while transcripts are the thing fresh context
exists to shed. The progress record replaces transcript memory
(externalized-plan pattern; condensation is itself a durable artifact).
*Alternative rejected:* item-text-only prompts — cheaper tokens, broken
cross-references, observed oscillation risk (item 5 undoing item 2).

**D6 — Adoption at boundaries: append-only, deduplicated, provenance-
marked.** The boundary diff accepts new ids after the cursor, appends
them in document order, refuses insertions into the completed region,
dedups by content hash against every seen item (kills replan-the-same-
thing loops), stamps gnome-discovered provenance rendered as untrusted
data in later prompts, and lands the snapshot update in the same commit
as the boundary's cursor advance. *Rationale:* the adoption mechanic is
needed regardless of gnome self-addition — human re-planning during a
park lands through the same diff at resume; gnome additions are the same
code path with a budget. Provenance + append-only is the injection
hardening: adopted text can add work, never replace or reorder reviewed
work. *Alternative rejected:* decision-gating every adoption — routine
discovered subtasks would park the pipeline; the budget wall with a
decision only on overflow keeps autonomy inside limits (same philosophy
as attempt limits).

**D7 — Plan-invalidation is a decision request.** `plan-invalidated` and
`item-obsolete` verdicts from an item round (structured result channel)
become decision requests routed to the decision tier — the arbiter when
`add-decision-arbiter` is configured, else the human park. Whole-task
escalation on per-item attempt exhaustion stays (deliberate: plan items
are sequentially dependent by construction; skipping is not routable).
*Rationale:* without an invalidation arm the engine marches a dead plan
to exhaustion and escalates with a wrong diagnosis. *Alternative
rejected:* park-item-and-continue on exhaustion — the literature default
for independent items, but these items are ordered and dependent; recorded
here so the deviation from the default is deliberate.

**D8 — Stage-end failure re-enters as a repair item.** A stage-end
quality failure adopts a repair item (findings attached, engine
provenance, exempt from `maxDiscoveredItems`, bounded by
`attemptLimitPerItem` and a repair-round cap) instead of burning an
undefined whole-stage attempt. *Rationale:* gives the failure an
addressable owner and reuses the loop's own machinery. *Alternative
rejected:* whole-stage attempt burn — loses attribution and restarts
paid work.

**D9 — Per-item verification designation in the manifest.** The
`iterate:` section designates which verify checks run per item
(cheap deterministic: `builtin`, `command`) versus at stage end
(`external`, `judge`, and anything undesignated). *Rationale:* per-item
external CI would multiply wall-clock and tracker writes by N; the
cheap/expensive split follows the existing fail-fast ordering rule.
*Alternative rejected:* full chain per item — economically dead for
30–60-item plans.

## Sync surfaces (mandatory)

**D10 — Sync surfaces.** This change touches these declared pairs from
`manual-sync-pairs.md`; mirrored edits are in scope:

- `GitAttemptPersistence` ↔ `EnvironmentAttemptPersistence` — both media
  serialize the new iteration state; both ends change together and gain
  `Kept in sync with` markers in this change.
- `GitTaskRepository` ↔ `GitObjectsTaskRepository` — the STARTED initial
  state and the RESUMED rewrite must carry/preserve the iteration state in
  both media (see sequencing: the cursor-preservation rule).
- `TakeResumeRunner` ↔ `TakeContainerResumeRunner` — both resume paths
  reconstruct the cursor and route the in-progress-item shape.
- `RoundBoundaryCheck` ↔ `HarvestedBoundaryCheck` — only if the item
  result file lands under `.gnomish-task/` (planned: yes, beside the
  decision file); the allowed-path rule changes at both ends.
- New mirrored-validation pair: the `iterate:` manifest mapper and the
  pipeline validator must accept exactly the same shapes — handled inside
  the single loader pipeline (mapper + validation rule in one module), so
  no cross-module pair is created.

New parallel implementations: none — the item loop lives in the domain
engine (one owner), and persistence rides the existing per-medium
writers, which are already declared pairs. No third implementation of any
rule is added.

## Risks / Trade-offs

- The intra-stage loop complicates the engine's most load-bearing class
  (attempt loop) → mitigated by D2's dedicated transition (the
  non-iterating path is untouched when `iterate:` is absent) and the
  kill-point matrix.
- Round-number ≡ history-size assumptions (two sites) and the
  attempt-limit check duplication (two sites) predate this change and
  become three-way traps → unify both as implementation tasks before the
  loop lands.
- Full-plan prompts grow with plan size → bounded by `maxItems` and the
  progress records replacing transcripts; measure token cost in paid
  smoke before tuning.
- Adopted repair items could loop (fail → repair → fail) → repair rounds
  are capped (D8) and exhaustion escalates naming the repair item.

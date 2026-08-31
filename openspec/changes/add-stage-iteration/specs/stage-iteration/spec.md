# stage-iteration

## ADDED Requirements

### Requirement: Iteration is declared per stage and optional
A stage manifest MAY declare `iterate:` naming the checklist input
artifact, `maxItems`, `maxDiscoveredItems`, `attemptLimitPerItem`, and the
per-item check designation; a stage without the section SHALL behave
exactly as before this change.
<!-- implements FR1 of add-stage-iteration -->

#### Scenario: Non-iterating stage unchanged
- **WHEN** a stage has no `iterate:` section
- **THEN** its rounds, attempts, and advancement follow the pre-change
  contract byte for byte

### Requirement: The snapshot and cursor are engine truth
On stage entry the engine SHALL parse the checklist once, freeze an
ordered snapshot of item identities (id, content hash) in the task state,
and drive iteration from the snapshot and cursor only; checkbox glyphs in
the plan document SHALL never determine completion. A plan exceeding
`maxItems` at entry SHALL be a quality failure attributed to the plan
input.
<!-- implements FR2, NFR-C1 of add-stage-iteration -->

#### Scenario: Checkbox flip does not complete an item
- **WHEN** the gnome marks an item `[x]` but its per-item verification has
  not passed
- **THEN** the cursor does not advance and the disagreement is logged

#### Scenario: Oversized plan refused at entry
- **WHEN** the checklist parses to more than `maxItems` items
- **THEN** the stage records a quality failure naming the count and the cap

### Requirement: Item lifecycle with a single in-progress item
Each snapshot item SHALL be pending, in-progress, completed, or partial;
at most one item SHALL be in-progress; the in-progress mark SHALL land in
the same commit that starts the item's first round. `partial` SHALL be
recordable only by a verified pass whose record carries the gnome's
declared scope reduction.
<!-- implements FR3 of add-stage-iteration -->

#### Scenario: In-progress at pickup names the kill window
- **WHEN** a pickup finds an in-progress item
- **THEN** recovery re-enters that item's round on the working copy as it
  stands, and no completed item re-runs

### Requirement: Fresh context per item
Each item round SHALL receive the frozen law, the full current plan
document, the item focus, in-scope decision records, and the structured
progress records of completed items; it SHALL never receive prior rounds'
transcripts.
<!-- implements FR4, FR12 of add-stage-iteration -->

#### Scenario: Second item sees records, not transcripts
- **WHEN** item 2's round starts after item 1 completed
- **THEN** its prompt carries item 1's progress record and the plan
  document, and no transcript content

### Requirement: Item completion is a single-commit transition
An item pass SHALL land cursor advance, the item's completion record, its
progress record, and the round's artifacts in one commit; the stage SHALL
complete only when the snapshot is exhausted and stage-end verification
passes.
<!-- implements FR6 of add-stage-iteration -->

#### Scenario: Kill between items freezes a named shape
- **WHEN** the process dies after item k's commit and before item k+1
  starts
- **THEN** the tip classifies to a named shape and pickup starts item k+1
  with no repeated paid work

### Requirement: The plan cannot shrink
Disappearance of a frozen or adopted item id from the plan document SHALL
be a quality failure of the round; body edits and annotations SHALL be
legal, and a body change of a not-yet-completed item SHALL leave an
observability trace.
<!-- implements FR7, M3 of add-stage-iteration -->

#### Scenario: Deleted item id fails the round
- **WHEN** the boundary diff finds a frozen id missing
- **THEN** the round records a quality failure naming the id, and the
  cursor does not advance

### Requirement: Adoption is boundary-only, append-only, budgeted
New item ids SHALL be adopted only at item boundaries: appended in
document order after the cursor, never into the completed region,
deduplicated by content hash against every seen item, provenance-marked
as gnome-discovered, counted against `maxDiscoveredItems`, and landed in
the same commit as the boundary transition. Additions made between park
and resume SHALL count only against `maxItems`. Overflow SHALL raise a
decision request whose payload is the pending items.
<!-- implements FR8, FR9 of add-stage-iteration -->

#### Scenario: Discovered item adopted within budget
- **WHEN** the gnome appended one new item and the budget has room
- **THEN** the boundary commit carries the extended snapshot and the log
  and status show the discovery

#### Scenario: Duplicate discovery dropped
- **WHEN** a new item's content hash matches a seen item
- **THEN** it is not adopted and the drop is logged

#### Scenario: Budget overflow asks, with the items as payload
- **WHEN** adoption would exceed `maxDiscoveredItems`
- **THEN** a decision request carries the pending items for approval

### Requirement: Plan invalidation is a decision, not a march
An item round MAY return `plan-invalidated` or `item-obsolete` verdicts
through its structured result; the engine SHALL convert them into
decision requests routed to the decision tier (arbiter when configured,
else human park), never into quality failures of the item.
<!-- implements FR9 of add-stage-iteration -->

#### Scenario: Dead plan surfaces before exhaustion
- **WHEN** an item round returns plan-invalidated with rationale
- **THEN** the task raises a decision request carrying the rationale, and
  no further items run until it is answered

### Requirement: Per-item exhaustion escalates the whole task
Exhausting `attemptLimitPerItem` SHALL escalate the task naming the item,
with that item's findings history first in the report; after a human
answer, resume SHALL continue from the cursor on any instance.
<!-- implements FR10, UX2 of add-stage-iteration -->

#### Scenario: Escalation names the item
- **WHEN** item 7 fails its last per-item attempt
- **THEN** the escalation names item 7 and lists its attempts' findings

#### Scenario: Cross-instance mid-list resume
- **WHEN** a second instance resumes the returned task
- **THEN** it continues from the cursor, repeating zero completed items
<!-- implements M2 of add-stage-iteration -->

### Requirement: Structureless output is an infrastructure failure
An item round producing no parseable structured result SHALL be recorded
as an infrastructure failure of the round: retried per policy, no
per-item attempt burned.
<!-- implements FR11 of add-stage-iteration -->

#### Scenario: Malformed result burns nothing
- **WHEN** the item round's result file is absent or unparseable
- **THEN** the round is recorded unburned and retried

### Requirement: Stage-end failure re-enters as a repair item
A stage-end verification quality failure SHALL adopt a repair item
carrying the findings (engine provenance, exempt from the discovery
budget, bounded by `attemptLimitPerItem`); repair exhaustion SHALL
escalate naming the repair item.
<!-- implements FR5 of add-stage-iteration -->

#### Scenario: Findings become an addressable item
- **WHEN** the stage-end chain fails after the last item
- **THEN** the loop re-enters with a repair item holding the findings,
  and status shows it

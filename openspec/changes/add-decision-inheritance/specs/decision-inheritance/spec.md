# decision-inheritance — new capability (add-decision-inheritance)

## Purpose

Owns the contract by which decisions made in one task of a hierarchy bind and
inform its relatives: the mini-ADR decision-record fields, the epic decisions
file as the single assembly point, the roll-up protocol with its recovery,
the downward injection and selection policy for a subtask's context, and the
no-override conflict rule between child and parent scopes.

## ADDED Requirements

### Requirement: Mini-ADR decision record fields
Decision records SHALL carry, additively to their existing content and
authorship: a scope (`task` or `subtree`), a status (`accepted` or
`superseded`, with the superseding record's id), rejected alternatives
(mandatory for `subtree` scope, with the reason each was rejected), and
optional premises. An accepted record SHALL be immutable; a change of course
SHALL be a new record superseding the old one, never an edit. Records
lacking the new fields SHALL read as task-scoped and accepted.
<!-- implements FR1 of add-decision-inheritance -->

#### Scenario: Supersession leaves history intact
- **WHEN** a subtree-scoped record is superseded by a new record
- **THEN** the old record remains stored with status superseded and a link
  to its successor, and the default inherited view contains only the new one

#### Scenario: Subtree scope requires rejected alternatives
- **WHEN** a subtree-scoped record is appended without rejected alternatives
- **THEN** the append is rejected as invalid before anything lands

#### Scenario: Legacy record reads as task-scoped
- **WHEN** a record written before this change is read
- **THEN** it is treated as task-scoped and accepted, and is never inherited

### Requirement: Epic decisions file is the single assembly point
The epic's task branch SHALL hold one decisions file — seeded from the
decomposition plan's epic-level decisions, appended by children's roll-ups —
owned by a single mapper for both read and write. Readers SHALL receive the
binding view: accepted subtree-scoped records only, in decision order, each
with provenance (which task decided it). Siblings later in the dependency
order see earlier siblings' exports through this file and never read sibling
branches directly.
<!-- implements FR2 of add-decision-inheritance -->

#### Scenario: Later sibling sees earlier sibling's export
- **WHEN** child C1 finished with an exported subtree decision and C2 is
  claimed afterwards
- **THEN** C2's inherited context contains C1's decision with C1 named as
  provenance

#### Scenario: Superseded records leave the binding view
- **WHEN** the file holds a superseded record and its successor
- **THEN** the binding view returned to readers contains only the successor

### Requirement: Roll-up precedes finish and is recoverable
A finishing subtask SHALL land its exported decisions on the epic branch as
one pushed commit *before* its tracker finish. The window between roll-up
and finish SHALL be a named shape recovered by the finish retry under the
same claim. The integration child's claim SHALL verify that every finished
sibling has rolled up, and SHALL re-derive a missing roll-up from that
sibling's own branch — the epic file is a rebuildable cache, and
re-derivation SHALL be idempotent and converge to the same file content.
<!-- implements FR3, NFR-R1 of add-decision-inheritance -->

#### Scenario: Roll-up lands before finish
- **WHEN** a child with subtree-scoped decisions finishes
- **THEN** the epic branch holds its exports before the tracker shows the
  child finished

#### Scenario: Integration child repairs a missing roll-up
- **WHEN** a sibling is finished but its exports are absent from the epic
  file
- **THEN** the integration child's claim re-derives them from the sibling's
  branch, and a second repair pass changes nothing

### Requirement: Downward injection with a bounded binding set
Claiming a subtask SHALL materialize its inherited context — the child's
brief from the decomposition plan plus the epic file's binding view — frozen
for the invocation like the pipeline law, and rendered verbatim in every
executor and judge briefing under the existing context-never-commands
contract. The injected set SHALL be bounded: binding records verbatim,
superseded and out-of-scope records omitted; when the bound is exceeded,
claim SHALL fail toward escalation rather than silently truncating a binding
record.
<!-- implements FR4, NFR-C1 of add-decision-inheritance -->

#### Scenario: Subtask briefing carries brief and binding decisions
- **WHEN** a subtask of an epic is claimed and a round runs
- **THEN** the briefing contains the child's brief and every binding
  inherited decision verbatim, and nothing superseded

#### Scenario: Oversized binding set escalates instead of truncating
- **WHEN** the binding view exceeds the configured bound at claim
- **THEN** the claim path escalates the epic naming the oversize, and no
  round runs with a truncated context

### Requirement: Children escalate, never override
A subtask SHALL NOT append a decision record contradicting an inherited
binding record. The legal move SHALL be an escalation carrying a proposed
supersede — the inherited record's id, the proposed replacement, and the
reason — resolved at the parent's scope; the child stays parked until the
proposal is resolved. The briefing SHALL state this rule alongside the
inherited decisions.
<!-- implements FR5 of add-decision-inheritance -->

#### Scenario: Contradiction becomes a proposed supersede
- **WHEN** a child's work concludes an inherited binding decision is wrong
- **THEN** the child escalates with the proposed supersede referencing the
  inherited record, and no contradicting record lands on any branch

#### Scenario: Resolution unblocks the child
- **WHEN** the proposal is accepted at the parent scope (new superseding
  record on the epic file)
- **THEN** the returned child's next invocation inherits the new record and
  continues

# tracker/task-decomposition — new capability (add-epic-decomposition)

## Purpose

Owns the epic-decomposition protocol: the decomposition plan schema and child
briefs, the semantics of the `decompose` terminal verdict, the crash-consistent
intent → effect → receipt transition with its closed set of kill-window shapes
and their recovery owner, the parent epic's lifecycle after decomposition, and
the orphan policy for children of a cancelled epic.

## ADDED Requirements

### Requirement: Decomposition plan is a machine-verifiable artifact
The decomposition plan SHALL be a structured stage output declaring: an
epic-or-single verdict; for an epic, an ordered child list where every child
carries a stable key (unique within the plan), a title, and a brief;
blocked-by edges referencing sibling
keys only; and exactly one integration child blocked by every other child.
Plan validation SHALL reject a plan that breaks any of these rules, has an
edge cycle, or exceeds the configured child-count limit.
**Implements**: FR2 of add-epic-decomposition
**Notes**: Rabbit holes are pre-decided traps; no-gos are excluded scope.

#### Scenario: Valid epic plan accepted
- **WHEN** a plan declares three children with unique keys, briefs, a
  sibling edge, and an integration child blocked by the other two
- **THEN** validation passes and the plan drives decomposition

#### Scenario: A child brief carries its five parts
- **WHEN** an epic plan's child brief lacks one of objective, expected output form, owned paths, rabbit holes and no-gos
- **THEN** validation fails naming the child and the missing part

#### Scenario: Plan without integration child rejected
- **WHEN** an epic plan lists workers but no integration child
- **THEN** validation fails naming the missing integration child, and the
  failure is quality feedback to the stage attempt, not a crash

#### Scenario: Edge cycle rejected
- **WHEN** two children declare blocked-by edges on each other
- **THEN** validation fails naming the cycle

#### Scenario: Duplicate keys rejected
- **WHEN** two children of an epic plan carry the same stable key
- **THEN** validation fails naming the duplicated key

#### Scenario: Edge to an unknown key rejected
- **WHEN** a child declares a blocked-by edge to a key no sibling carries
- **THEN** validation fails naming the unknown key

#### Scenario: Child count over the limit rejected
- **WHEN** an epic plan lists more children than the configured child-count limit
- **THEN** validation fails naming the count and the limit

### Requirement: Intent, effect, receipt ordering
The decomposition transition SHALL order its durable steps: (1) the plan
lands on the task branch and is pushed — intent; (2) children are created in
the tracker via create-subtask with the plan's stable keys, in plan order
with the integration child last — effect; (3) created child refs land on the
task branch — receipt; (4) the parent transitions out of circulation. No
child SHALL be created before the pushed plan exists, and the parent SHALL
NOT transition before every receipt is durable.
**Implements**: FR3 of add-epic-decomposition

#### Scenario: Plan push precedes first child
- **WHEN** decomposition runs
- **THEN** the tracker sees no child until the plan commit is pushed on the
  task branch

#### Scenario: Parent transition is last
- **WHEN** any child creation or receipt write has not completed
- **THEN** the parent remains in its working state, claim held

### Requirement: Kill windows are named shapes with one recovery owner
Every gap between its durable steps SHALL classify to a named
shape: plan-pushed-no-children, children-partial, children-complete-receipt-
missing, receipt-complete-parent-untransitioned. The recovery owner for all
four SHALL be the resume path of the epic task itself, which reconciles
tracker children against the plan by stable key, creates only missing
children, completes missing receipts, and finishes the parent transition.
Recovery SHALL be idempotent: a second pass changes nothing.
**Implements**: FR4, NFR-R1 of add-epic-decomposition
**Notes**: Each shape's scenario below is its kill-point spec in the kill-point matrix: shape, convergence, no-op second pass.

#### Scenario: Kill after partial creation converges
- **WHEN** an instance dies after creating two of four planned children and
  another instance resumes the epic
- **THEN** the resume creates exactly the two missing children (matching
  existing ones by stable key), records receipts, and transitions the parent

#### Scenario: Recovery is a no-op when complete
- **WHEN** recovery runs on an epic whose decomposition already completed
- **THEN** no tracker write and no branch write occurs

#### Scenario: Kill after the plan push converges
- **WHEN** an instance dies after the plan push and before any child is created, and another instance resumes the epic
- **THEN** classification names the plan-pushed-no-children shape, the resume creates every planned child, records receipts and transitions the parent
- **AND** a second recovery pass makes no tracker write and no branch write

#### Scenario: Kill before the receipt converges
- **WHEN** an instance dies after every child was created and before their receipts landed, and another instance resumes the epic
- **THEN** classification names the children-complete-receipt-missing shape, the resume creates no child, records the missing receipts and transitions the parent
- **AND** a second recovery pass makes no tracker write and no branch write

#### Scenario: Kill before the parent transition converges
- **WHEN** an instance dies after every receipt landed and before the parent transitioned, and another instance resumes the epic
- **THEN** classification names the receipt-complete-parent-untransitioned shape, and the resume only transitions the parent
- **AND** a second recovery pass makes no tracker write and no branch write

### Requirement: Parent epic lifecycle and orphan policy
After decomposition the parent SHALL leave the ready feed into a waiting
state that names its children, holding no claim. The integration child's
delivery SHALL finish the parent. When the parent is cancelled or escalated
while children are open, the sweeper SHALL apply the declared orphan policy:
open children are parked with a report naming the cancelled epic — never
silently deleted, never left claimable.
**Implements**: FR5 of add-epic-decomposition

#### Scenario: Integration child delivery finishes the parent
- **WHEN** the integration child reaches its terminal delivered state
- **THEN** the parent epic is finished with a summary linking the children

#### Scenario: Cancelled epic parks open children
- **WHEN** the epic is cancelled while two children are open and unclaimed
- **THEN** the sweep parks both children naming the cancelled epic as the
  reason

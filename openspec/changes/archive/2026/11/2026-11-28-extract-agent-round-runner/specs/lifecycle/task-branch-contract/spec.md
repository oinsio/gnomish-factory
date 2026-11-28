# lifecycle/task-branch-contract — delta for extract-agent-round-runner

## MODIFIED Requirements

### Requirement: An interrupted verification is InProgress with one recovery owner
A registered snapshot commit, or a registered pending commit above one, SHALL classify by its
envelope as any registered commit does and SHALL add no shape. Its one recovery owner SHALL be the
interrupted-verification resume, on both media: it re-verifies the snapshot without running the
stage executor, or re-raises the decision request the snapshot carries, and continues the round's
pending entries; no other component SHALL resume it.
**Implements**: FR19, FR20, NFR-R1 of migrate-verify-to-executor-contract; FR11, NFR-R3 of extract-agent-round-runner
**Notes**: The commits themselves are stated in git-task-persistence, "Pending commits of an open round" and "Sandboxed round protocol: snapshot and state commits".
**Notes**: A pending-round snapshot is a snapshot commit of this requirement; the same owner recognises it, and the round's pending entry it continues is a poll of the recorded key (git-task-persistence, "Pending-round snapshot").

#### Scenario: A registered pending commit classifies by its envelope
- **WHEN** the register names a pending commit of a round at a stage
- **THEN** the shape is `InProgress`, the shape the snapshot below it has

#### Scenario: An interrupted verification is resumed by its one owner
- **WHEN** any instance picks up a task whose register names a round's snapshot or a pending commit above one
- **THEN** it salvages nothing, runs no stage executor, re-verifies the snapshot (or re-raises the decision request the snapshot carries), and a second pickup after the round's closing commit changes nothing

#### Scenario: A pending round is resumed by the same owner
- **WHEN** any instance picks up a task whose register names a pending commit above a pending-round snapshot
- **THEN** the shape is `InProgress`, the interrupted-verification resume recognises it, the round's pending entry is continued by a poll with no `start`, and no verify check runs before the round closes

#### Scenario: A closing snapshot above pending round commits is an interrupted verification
- **WHEN** the register names a round's snapshot whose first parents hold pending round commits of the same token and whose state still carries the round's pending entry
- **THEN** the snapshot is re-verified, the entry is polled by no one, and the round's closing commit carries no pending entry

#### Scenario: The round close drops the pending entries
- **WHEN** the closing commit of a round that went `pending` lands
- **THEN** its state carries no pending entry

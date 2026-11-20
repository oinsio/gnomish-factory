# lifecycle/task-branch-contract — delta for migrate-verify-to-executor-contract

## ADDED Requirements

### Requirement: An interrupted verification is InProgress with one recovery owner
A registered snapshot commit, or a registered pending commit above one, SHALL classify by its
envelope as any registered commit does and SHALL add no shape. Its one recovery owner SHALL be the
interrupted-verification resume, on both media: it re-verifies the snapshot without running the
stage executor, or re-raises the decision request the snapshot carries, and continues the round's
pending entries; no other component SHALL resume it.
**Implements**: FR19, FR20, NFR-R1 of migrate-verify-to-executor-contract
**Notes**: The commits themselves are stated in git-task-persistence, "Pending commits of an open round" and "Sandboxed round protocol: snapshot and state commits".

#### Scenario: A registered pending commit classifies by its envelope
- **WHEN** the register names a pending commit of a round at a stage
- **THEN** the shape is `InProgress`, the shape the snapshot below it has

#### Scenario: An interrupted verification is resumed by its one owner
- **WHEN** any instance picks up a task whose register names a snapshot or a pending commit
- **THEN** it salvages nothing, runs no stage executor, re-verifies the snapshot (or re-raises the decision request the snapshot carries), and a second pickup after the round's closing commit changes nothing

#### Scenario: The round close drops the pending entries
- **WHEN** the closing commit of a round that went `pending` lands
- **THEN** its state carries no pending entry

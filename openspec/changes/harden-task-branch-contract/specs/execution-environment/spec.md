# execution-environment — delta for harden-task-branch-contract

## ADDED Requirements

### Requirement: Container park records its outcome on the branch
A park (Escalated or Paused) of a container-mode task SHALL record its outcome on the task branch through the same intent→effect→receipt protocol host mode uses: the factory-side outcome commit carrying the pending-write marker is the durable intent, the terminal tracker write is the effect, and the receipt is recorded after it. Recovery of a park intent without a receipt SHALL verify the effect at the tracker before re-driving it. A container-mode park therefore never leaves the branch silent: the human's escalation answer, on return, is read against a recorded park.
<!-- implements FR10 of harden-task-branch-contract -->

#### Scenario: Container park round-trips a human decision
- **WHEN** a container-mode task parks with a question, the operator answers, and the task is returned
- **THEN** resume finds the recorded park outcome on the branch, reads the answer, and continues — it does not re-park with the same question

#### Scenario: Kill between intent and receipt converges
- **WHEN** an instance dies after the container park's outcome commit but before its receipt is recorded
- **THEN** the next pickup verifies whether the tracker write landed, re-drives it only if absent, and records the receipt — with no duplicate tracker artifact

### Requirement: Kept box excludes factory-side branch commits
Between a park's outcome commit and the disposal of its kept box, the factory SHALL NOT create further factory-side commits on the task branch — the kept box's clone cannot learn of them, and a later harvest would diverge. Resuming an escalated container task SHALL dispose the kept box before the decision commit is created, so the next round's box is materialized from a tip that already contains the decision from its start.
<!-- implements FR17 of harden-task-branch-contract -->

#### Scenario: Escalated-container resume disposes before deciding
- **WHEN** an escalated container task with a kept box is resumed with a human decision
- **THEN** the kept box is disposed first, the decision commit is created on the harvested tip after disposal, and the freshly materialized box contains the decision from its first round

#### Scenario: No factory commit lands behind a kept box's back
- **WHEN** a container-mode task is parked with its box kept
- **THEN** no factory-side commit advances the task branch until that box is disposed, so no future harvest can diverge from a tip the box never saw

### Requirement: Shared factory-owned-paths salvage policy
Host and container salvage SHALL consume one shared factory-owned-paths policy — a single definition of which working-copy paths are factory-owned, not two adapter-local lists. Under it, gnome-owned work files in a dirty working copy are salvageable; factory-owned files under `.gnomish-task/` are restored from the branch tip during recovery and never salvaged from the dirty working copy.
<!-- implements FR5 of harden-task-branch-contract -->

#### Scenario: Both adapters classify a path identically
- **WHEN** the same working-copy path is evaluated by host salvage and by container salvage
- **THEN** both classify it identically — factory-owned or gnome-owned — through the one shared policy

#### Scenario: A tampered state file cannot ride a salvage commit
- **WHEN** an interrupted round's dirty working copy holds gnome source edits and a modified `.gnomish-task/state.json`
- **THEN** salvage commits the source edits, excludes the state file, and recovery proceeds from the `state.json` at the branch tip

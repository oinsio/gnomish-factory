## MODIFIED Requirements

### Requirement: Total branch-shape classification
A classifier SHALL map any task branch to exactly one shape of a closed set of twelve: `Bare`, `Created`, `InProgress`, `AwaitingApproval`, `Parked`, `Answered`, `CompletedUncleaned`, `Delivered`, `Unregistered`, `UnsupportedVersion`, `Corrupt(reason)` and `Unknown`. The set SHALL be a sealed hierarchy so readers switch without a default branch.

The happy path and the group quarantined on first classification:

```mermaid
stateDiagram-v2
    [*] --> Bare: branch ref created
    Bare --> Created: STARTED commit
    Created --> InProgress: round recorded
    InProgress --> AwaitingApproval: manual stage passed
    AwaitingApproval --> InProgress: approval write
    InProgress --> Parked: outcome, human awaited
    Parked --> Answered: decision appended
    Parked --> InProgress: resumed write
    Answered --> InProgress: resume
    InProgress --> CompletedUncleaned: Completed outcome
    CompletedUncleaned --> Delivered: cleanup + finish
    Delivered --> [*]

    state "Quarantine on first classification" as Q {
        Corrupt
        UnsupportedVersion
        Unknown
    }
    state "Not yet fetched" as U {
        Unregistered
    }
    U --> InProgress: touchpoint fetch lands the register (any shape, by its content)
    note right of U: still no register after the read: quarantined on a claimed take
```
**Implements**: FR10 of fix-terminal-receipt-convergence; FR11 of make-checkpoint-gate-durable; FR1, FR3, FR15, NFR-R2 of harden-task-branch-contract; FR1, FR2 of fix-claim-epoch-fence; FR14, NFR-R4 of fix-operator-blockers; FR1, FR2 of split-long-spec-requirements
**Notes**: Where the registered commit comes from is the requirement "Classification reads the factory register".
**Notes**: The twelve names are the canonical vocabulary every other artifact of this contract refers to rather than restates.
**Notes**: The distinct `UnsupportedVersion` shape is what lets a version diagnosis name the version rather than a parse failure.
**Notes**: The classifier reads the registered commit's envelope, file set, versions and position, and the tip's relation to it.

#### Scenario: Every generated tip classifies to exactly one shape
- **WHEN** property-generated branch tips (arbitrary file subsets, envelope versions and positions) are classified
- **THEN** each input yields exactly one named shape and no input throws

#### Scenario: A gate classifies by its position, not by the park
- **WHEN** a tip's `state.json` position is `AwaitingApproval(stage)` and its `task.json` outcome is null, `paused`, or a stale earlier outcome
- **THEN** the shape is `AwaitingApproval` in all three cases, and never `InProgress` or `Parked`

#### Scenario: Pre-contract tip is a legal initial shape
- **WHEN** the tip carries `task.json` but no `state.json`
- **THEN** the shape is `Created` and resume starts the first stage from scratch

#### Scenario: Unsupported envelope version is its own shape, not a parse failure
- **WHEN** a state file at the tip declares an envelope version the factory does not support
- **THEN** the tip classifies as `UnsupportedVersion` with a diagnosis naming the file, the observed version, and the supported range — on every reading path including take and serve — and never as `Corrupt` or `Unknown`

#### Scenario: A tip stamped by an earlier tenure classifies by its content
- **WHEN** a task is reclaimed and its tip commit carries a claim epoch older than the new claim's
- **THEN** the shape is the one the tip's files and envelopes describe — `Parked`, `AwaitingApproval`, `Answered`, `InProgress`, `CompletedUncleaned`, `Created`, or `Delivered` — and the reclaim routes on that shape

#### Scenario: A cleanup commit inherited from the base does not deliver a live task
- **WHEN** an earlier task's delivered branch was merged into the base with its history, and a new task forked from that base is parked, in progress or freshly created
- **THEN** the new task classifies by its own content — `Parked`, `InProgress` or `Created` — and never as `Delivered`, on every reading path including `status`, take and serve

#### Scenario: A base merged into a live task after it started does not deliver it
- **WHEN** a base holding an earlier task's cleanup commit is merged into a live task branch after the task started
- **THEN** the live task does not classify as `Delivered`

#### Scenario: A branch ref with no STARTED commit is Bare
- **WHEN** the task branch ref exists but carries no STARTED commit
- **THEN** the shape is `Bare`, and the branch has no delivery whatever its history holds

#### Scenario: A started branch with no completed round is Created
- **WHEN** the STARTED commit is present, no round has completed, and no decision is recorded
- **THEN** the shape is `Created`

#### Scenario: A run underway is InProgress
- **WHEN** rounds are recorded, no outcome is recorded, and the position is at a stage or the pipeline end
- **THEN** the shape is `InProgress`

#### Scenario: An outcome awaiting a human at a non-gate position is Parked
- **WHEN** an outcome is recorded at a non-gate position and a human is awaited, with or without the pending-write marker
- **THEN** the shape is `Parked` in both cases — the marker is a sub-state, not a separate shape

#### Scenario: An appended decision with the outcome cleared is Answered
- **WHEN** the human's decision is appended and the outcome cleared, with no round run since
- **THEN** the shape is `Answered`, and the branch is resumable

#### Scenario: A recorded completion awaiting cleanup is CompletedUncleaned
- **WHEN** a `Completed` outcome is recorded and the cleanup is still pending
- **THEN** the shape is `CompletedUncleaned`

#### Scenario: A cleanup commit in the task's own history is Delivered
- **WHEN** the cleanup commit is found in the task's own history and further commits follow it
- **THEN** the shape is `Delivered` — the post-cleanup commits are tolerated

#### Scenario: Unreadable or self-contradictory content is Corrupt
- **WHEN** a tip's content is unreadable or self-contradictory
- **THEN** the shape is `Corrupt(reason)`, the reason naming the offending file and the observed versus expected content

#### Scenario: A legal but unrecognized combination is Unknown
- **WHEN** a tip's files form a legal combination this contract does not recognize
- **THEN** the shape is `Unknown`

#### Scenario: A tip without its register is Unregistered, not Unknown
- **WHEN** the tip carries a factory envelope and the clone holds no `refs/gnomish-factory/<task>` for the task — as an operator's or an IDE's `git fetch`, or a `git clone`, leaves a task branch
- **THEN** the shape is `Unregistered`, its diagnosis names the register and the command that fetches it, its recovery owner is the touchpoint fetch, and it is never `Unknown`

#### Scenario: A fetched register reclassifies an Unregistered branch by its content
- **WHEN** the touchpoint fetch lands origin's register for an `Unregistered` branch
- **THEN** the branch classifies by that register as any other branch does, and a second fetch changes nothing

#### Scenario: An unsupported version never reads as its closest legal shape
- **WHEN** an envelope at the tip declares an unsupported version while the rest of the tip resembles a legal shape
- **THEN** the shape is `UnsupportedVersion`, never the closest legal match

#### Scenario: No shape name collides with a domain type
- **WHEN** the shape names are compared with the existing domain types
- **THEN** the shape for "awaiting a human" is `Parked` (never `Escalated`, a `TaskOutcome` variant), the shape for a closed gate is `AwaitingApproval` (the word the position carries, since the shape is the position), and the shape for "decision landed" is `Answered` (never `Decision`, the human's answer record)

#### Scenario: An unreachable environment is the classifier's only error
- **WHEN** git or the daemon is unreachable while a tip is read for classification
- **THEN** the failure surfaces as an infrastructure error that retries under the existing policy without burning a quality attempt; no content ever makes the classifier throw

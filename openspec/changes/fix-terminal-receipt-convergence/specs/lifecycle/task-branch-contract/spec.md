# Spec Delta: lifecycle/task-branch-contract

The closed shape set grows by one: `Unregistered` (design D13 of fix-terminal-receipt-convergence)
names a tip that carries a factory envelope while the clone holds no factory register for the
task — the state an operator's or an IDE's `git fetch` leaves every task branch in. It was
`Unknown`, one label over two states with two recoveries (a diverged register: quarantine; a
register not yet fetched: the touchpoint fetch). Both requirements are repeated whole; every
existing scenario keeps its title, so its markers stay valid.

## MODIFIED Requirements

### Requirement: Total branch-shape classification
A classifier SHALL map any task branch — its registered commit's envelope, file set, versions and position, and the tip's relation to it — to exactly one shape of a closed set of twelve: `Bare`, `Created`, `InProgress`, `AwaitingApproval`, `Parked`, `Answered`, `CompletedUncleaned`, `Delivered`, `Unregistered`, `UnsupportedVersion`, `Corrupt(reason)` and `Unknown`. The set SHALL be a sealed hierarchy so readers switch without a default branch.

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
**Implements**: FR10 of fix-terminal-receipt-convergence; FR11 of make-checkpoint-gate-durable; FR1, FR3, FR15, NFR-R2 of harden-task-branch-contract; FR1, FR2 of fix-claim-epoch-fence; FR14, NFR-R4 of fix-operator-blockers
**Notes**: Where the registered commit comes from is the requirement "Classification reads the factory register".
**Notes**: The twelve names are the canonical vocabulary every other artifact of this contract refers to rather than restates.
**Notes**: The distinct `UnsupportedVersion` shape is what lets a version diagnosis name the version rather than a parse failure.

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

### Requirement: Classification reads the factory register
Every reader of task-branch state SHALL take the envelope, the position, the outcome, the base pin and the delivery fact from the task's registered commit — the commit `refs/gnomish-factory/<task>` names — never from the branch tip. The tip SHALL contribute one fact: equal to the registered commit, a descendant of it, or neither. `Delivered` SHALL mean the registered commit is the cleanup commit. A record the gnome writes above the register SHALL change no shape and open no gate.
**Implements**: FR10 of fix-terminal-receipt-convergence; FR13, NFR-S2 of tighten-spec-contract
**Notes**: The register is the one ref only a factory closing push moves (git-task-persistence, "Factory register ref"); a commit id is content-addressed, so the gnome cannot forge one.
**Notes**: Host mode carries no integrity claim against the gnome (design D19 of tighten-spec-contract); there the register is a detection, not a guarantee.

#### Scenario: The tip equals the register
- **WHEN** the branch tip and the registered commit are the same commit
- **THEN** the shape is the one that commit's envelope describes, exactly as a tip read would have described it before the register existed

#### Scenario: A tip above the register is an open gnome range
- **WHEN** the branch tip descends from the registered commit and is not equal to it
- **THEN** the shape is the registered commit's, and the commits above the register are the open round's gnome range — salvaged or admitted by the round-close owner, never classified

#### Scenario: A register that is not an ancestor of the tip quarantines
- **WHEN** the registered commit is neither the tip nor an ancestor of it
- **THEN** the shape is `Unknown` with a diagnosis naming the register and the tip, and the existing budgeted recovery owner handles it

#### Scenario: A missing register is a state of the clone, not of the branch
- **WHEN** the register ref is missing in the clone for a branch that has a STARTED commit
- **THEN** the shape is `Unregistered`, nothing is read at a register that is not there, and the budgeted recovery owner takes it only after a touchpoint fetch still landed no register — or at once for a local head, which the locate does not fetch

#### Scenario: A gnome commit carrying the cleanup subject does not deliver the task
- **WHEN** the gnome commits on its own branch with a message carrying `gnomish: cleanup`, as the subject or anywhere in the message, and the register names a commit below it
- **THEN** the branch does not classify as `Delivered`, on every reading path including `status`, take and serve

#### Scenario: A gnome-written Completed outcome is not a pending cleanup
- **WHEN** a gnome commit above the register rewrites `task.json` with `outcome: COMPLETED`, alone or with a matching `state.json`
- **THEN** the branch does not classify as `CompletedUncleaned`, no cleanup is written, and no tracker finish is posted

#### Scenario: A gnome-written gate position opens no gate
- **WHEN** a gnome commit above the register writes an `AwaitingApproval(stage)` position with a `paused` outcome
- **THEN** the branch does not classify as `AwaitingApproval`, and the pickup performs no approval write

#### Scenario: A gnome-written base pin binds no law
- **WHEN** a gnome commit above the register rewrites the base pin in `task.json`
- **THEN** resume binds the law from the pin the registered commit carries, and the rewritten pin is never read

#### Scenario: No reader takes an envelope fact from the tip
- **WHEN** a production class in the git adapter or the application layer reads a factory-owned envelope file by the branch tip or `HEAD` outside the register owner
- **THEN** the architecture gate fails the build naming the file

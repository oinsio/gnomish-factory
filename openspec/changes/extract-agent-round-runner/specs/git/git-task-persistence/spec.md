# git/git-task-persistence — delta for extract-agent-round-runner

## ADDED Requirements

### Requirement: A decision records the question it answers
The decision commit that consumes an `escalated` outcome whose report is a decision request
SHALL record that report's question and options on the decision it appends, in the same commit
as the reply; a decision that consumes any other outcome SHALL record none. A `task.json` whose
decisions carry no question SHALL read as before.
**Implements**: FR9, NFR-R2 of extract-agent-round-runner

#### Scenario: The reply and its question land in one commit
- **WHEN** a task parked on a decision request is answered, on the host or in container mode
- **THEN** the decision the decision commit appends to `task.json` carries the reply, the question and the options of the consumed report

#### Scenario: A reply to another escalation records no question
- **WHEN** a task parked as `escalated(AttemptsExhausted)` is returned with a reply
- **THEN** the appended decision carries the reply and no question

#### Scenario: Decisions written before the question read as before
- **WHEN** a `task.json` whose decisions carry only body, stage, author and time is read
- **THEN** each decision reads with no question and is written back unchanged

### Requirement: Pending-round snapshot
The snapshot that registers a `transform` round's working copy when its `start` answers `pending`
SHALL carry the subject `gnomish: pending-snapshot <stage>#<round> <token>`, on both media. A
pickup SHALL read a registered pending commit above it as a pending round, never as an
interrupted verification, and SHALL read it with no pending commit above it as a round to
re-drive.
**Implements**: FR10, FR11, NFR-R3 of extract-agent-round-runner
**Notes**: What the pickup then does is stated in pipeline/stage-engine, "Any instance resumes a pending transform round".

#### Scenario: Only the start of a round registers a pending-round snapshot
- **WHEN** a round's `start` answers `pending` and two polls answer `pending` after it
- **THEN** the branch carries one pending-round snapshot of the round, below its pending round commit

#### Scenario: A pending commit above a pending-round snapshot is a pending round
- **WHEN** the register names a pending commit whose first parents reach a pending-round snapshot of the same stage, round and token
- **THEN** the pickup reads a pending round with that token and runs no verify check

#### Scenario: A pending-round snapshot alone is a round to re-drive
- **WHEN** the register names a pending-round snapshot with no pending commit above it
- **THEN** the pickup re-verifies nothing and the round is re-driven with a new `start`

### Requirement: The closing snapshot of a pending round
The snapshot that closes a round after its terminal `poll` SHALL have the round's last registered
commit as its only parent and SHALL carry the round's token, on both media. The gnome range it is
judged over SHALL start at that parent, and the factory's own `state.json` of a registered commit
of the round SHALL count as no edit.
**Implements**: FR10, NFR-R4 of extract-agent-round-runner

#### Scenario: The closing snapshot sits on the last pending round commit
- **WHEN** a pending round's terminal poll returns `completed`
- **THEN** the round's snapshot has the last pending round commit as its only parent and its subject names the token of the round's pending-round snapshot

#### Scenario: The factory's own commits of a pending round are no gnome commits
- **WHEN** a pending round closes after its executor committed in the working copy during a poll
- **THEN** the range judged holds the executor's commits and neither the pending-round snapshot nor a pending round commit, and no boundary violation is reported for the worktree's `state.json`

## MODIFIED Requirements

### Requirement: Pending commits of an open round
A pending commit SHALL carry exactly `state.json`, SHALL have the round's last registered commit
as its only parent, SHALL carry the subject `gnomish: pending <stage>#<round> <token>`, and SHALL
land with its register in one ref transaction on both media. A pickup SHALL recognise a
registered pending commit through its first parents down to the snapshot of the same stage, round
and token.
**Implements**: FR2, FR10, FR20 of migrate-verify-to-executor-contract; FR10, FR11 of extract-agent-round-runner
**Notes**: The entries a pending commit's `state.json` carries are stated in verification/pending-poll.
**Notes**: The snapshot a chain ends on names its kind: a round's snapshot for a check's pending commits, a pending-round snapshot for a pending round ("Pending-round snapshot").

#### Scenario: A pending commit sits on the round's last registered commit
- **WHEN** the first check of a round to answer `pending` does so
- **THEN** the pending commit's only parent is the round's registered snapshot and the pending commit changes no path besides `state.json`

#### Scenario: A second pending check stacks on the first pending commit
- **WHEN** another check of the same round answers `pending` after a pending commit of the round was registered
- **THEN** its pending commit's only parent is the round's last pending commit, it changes no path besides `state.json`, and its state carries both entries

#### Scenario: A pending commit is built from the registered commit alone
- **WHEN** a pending check's run has staged or committed a change in the round's working copy before the pending commit is written
- **THEN** the pending commit's tree is the registered commit's tree with only `state.json` replaced, its parent is the registered commit, and on the host its ref transaction refuses a branch the run moved

#### Scenario: The factory's own pending commit is no envelope edit at the round close
- **WHEN** a host round with a pending commit closes and the worktree's `state.json` is the one a registered commit of the round carries
- **THEN** no boundary violation is reported for it and the round commit carries the factory's own `state.json`

#### Scenario: A key rotation stacks a pending commit
- **WHEN** a `poll` of a recorded key returns `pending` with another key
- **THEN** the next pending commit's only parent is the previous pending commit and its register moves in the same transaction

#### Scenario: Interrupted verification resumes through a pending commit
- **WHEN** a factory dies after a check's pending commit was registered above a round's snapshot and before the round's closing commit
- **THEN** a resuming instance re-runs verification against the snapshot below it, runs no stage executor, and the attempt counter is unchanged

#### Scenario: A harvested pending commit that changes another path is refused
- **WHEN** in sandboxed mode the harvested pending commit changes any path besides `state.json` or sits on another parent
- **THEN** the task aborts as a boundary violation and the register does not move

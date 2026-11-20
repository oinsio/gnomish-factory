# git/git-task-persistence — delta for migrate-verify-to-executor-contract

## ADDED Requirements

### Requirement: Pending commits of an open round
A pending commit SHALL carry exactly `state.json`, SHALL have the round's last registered commit
as its only parent, SHALL carry the subject `gnomish: pending <stage>#<round> <token>`, and SHALL
land with its register in one ref transaction on both media. A pickup SHALL recognise a
registered pending commit through its first parents down to the snapshot of the same stage, round
and token.
**Implements**: FR2, FR10, FR20 of migrate-verify-to-executor-contract
**Notes**: The entries a pending commit's `state.json` carries are stated in verification/pending-poll.

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
- **WHEN** a factory dies after a pending commit was registered and before the round's closing commit
- **THEN** a resuming instance re-runs verification against the snapshot below it, runs no stage executor, and the attempt counter is unchanged

#### Scenario: A harvested pending commit that changes another path is refused
- **WHEN** in sandboxed mode the harvested pending commit changes any path besides `state.json` or sits on another parent
- **THEN** the task aborts as a boundary violation and the register does not move

### Requirement: No salvage over an interrupted verification
When the registered commit is a snapshot or a pending commit, neither a resume nor a revocation
stop SHALL salvage the working copy's leftovers, on either medium, with or without
`--discard-work`: the host SHALL discard them, a resume before it re-verifies, and the container
SHALL keep them out of every commit.
**Implements**: FR19 of migrate-verify-to-executor-contract
**Notes**: Leftovers above a registered snapshot are what the killed verification wrote; the gnome's work is in the snapshot.

#### Scenario: Host residue above a snapshot is discarded
- **WHEN** a host task is resumed in the same clone, its register names a snapshot, and the killed verification left uncommitted files in the worktree
- **THEN** the files are discarded, no salvage commit lands, and verification runs on the snapshot's tree

#### Scenario: A resumed host verification runs no agent
- **WHEN** a git-mode host task is picked up and its register names a snapshot or pending commit
- **THEN** the resuming instance re-runs the round's verification, runs no stage executor, and the attempt counter is unchanged

#### Scenario: A revocation during a verification lands no salvage commit
- **WHEN** a claim is revoked while a round's verification runs above its registered snapshot or pending commit
- **THEN** no salvage commit lands, the register still names that commit, and the next pickup re-verifies the round without running its stage executor

### Requirement: An unverifiable round close is not an interrupted verification
A sandboxed round whose stage executor did not answer `completed` SHALL close under the subject
`gnomish: unverified-snapshot <stage>#<round> <token>`, and no pickup SHALL resume a registered
commit carrying it as an interrupted verification; it SHALL be resumed as a round killed during
its stage executor, with no attempt burned.
**Implements**: FR19, NFR-R1 of migrate-verify-to-executor-contract
**Notes**: The round close names its outcome because the container snapshot precedes the mapping of the executor's result (design D22).

#### Scenario: A failed program round is not resumed as completed
- **WHEN** a factory dies after the round close of a sandboxed round whose program answered `failed`, before the round's state commit or escalation lands
- **THEN** the registered commit carries the unverified-snapshot subject, the resuming instance re-runs the round's stage executor without burning an attempt, and no verification of the failed round runs

## MODIFIED Requirements

### Requirement: Round commit of the whole working tree
In host mode a round SHALL be persisted as one commit of the entire working tree — gnome changes,
`state.json` and the round trace — except a completed git-mode round, which SHALL first close the
gnome's changes in a registered snapshot commit before verification, its round commit then
carrying `state.json` and the trace on top. In sandboxed mode a round SHALL close snapshot first:
a snapshot commit inside the environment, then, after verification, a state commit with
`state.json` and the trace.
**Implements**: FR2, NFR-R1 of add-git-workflow; FR21 of add-sandbox-core; FR19 of migrate-verify-to-executor-contract
**Notes**: A completed round is one whose stage executor answered `completed`; a failed round and one that asked a decision get no snapshot.

#### Scenario: Host round atomicity
- **WHEN** a host-mode round finishes and persist runs
- **THEN** a round that was not snapshotted lands exactly one new commit containing the gnome's file changes, `state.json`, and `attempts/<stage>/<round>/trace.jsonl`; a snapshotted round lands exactly one round commit with `state.json` and the trace, on top of the one snapshot commit with the gnome's file changes and the round's pending commits, if any, and no `state.json` of the round lands without its snapshot below it

#### Scenario: Sandboxed round closes as snapshot plus state
- **WHEN** a sandboxed round finishes and verification completes
- **THEN** the branch gains a snapshot commit with the gnome's file changes and a separate state commit with `state.json` and the round trace

#### Scenario: A persist failure aborts the task
- **WHEN** persisting a round fails, in host or sandboxed mode
- **THEN** the task aborts through the strict port instead of continuing past an unrecorded round

#### Scenario: A completed host round is verified at its snapshot
- **WHEN** the checks of a completed git-mode host round run
- **THEN** the task's register names the round's snapshot commit, whose tree holds the gnome's changes and the factory-owned paths of the commit registered before it

#### Scenario: A boundary violation of a completed host round takes no snapshot
- **WHEN** a completed git-mode host round's gnome range or uncommitted envelope edit is a violation, or cannot be judged
- **THEN** no snapshot commit lands, the register does not move, and the round's persist reports the violation and the task aborts, as for a round with no snapshot

### Requirement: Sandboxed round protocol: snapshot and state commits
In sandboxed mode a round SHALL close in two steps: a snapshot commit of the whole working tree,
made inside the environment with hooks disabled at argv level and harvested, which verification
judges as the attempt commit; then, after verification, a state commit of `state.json` and the
trace written in via `putFile`, whose parent SHALL be the round's last registered commit — the
snapshot or its last pending commit — and whose integrity the factory SHALL verify at its harvest.
**Implements**: FR21, FR22 of add-sandbox-core; FR20 of migrate-verify-to-executor-contract

#### Scenario: Verification judges the attempt commit
- **WHEN** a sandboxed round closes and verification runs
- **THEN** builtin, fresh-box, and external checks all observe exactly the harvested snapshot commit, and uncommitted box residue influences none of them

#### Scenario: Interrupted verification resumes without burning an attempt
- **WHEN** a factory dies after the snapshot commit but before the state commit
- **THEN** a resuming instance finds the snapshot unrecorded in `state.json`, re-runs verification against it, and the attempt counter is unchanged

#### Scenario: The snapshot subject is read at the register only
- **WHEN** a resuming instance looks for a pending verification
- **THEN** it reads the stage, round and token from the subject of the registered commit, and a snapshot-shaped subject on a commit above the register is not a snapshot

#### Scenario: Daemon-inserted commit is caught by the parent-check
- **WHEN** a background process in the box commits to the branch between the snapshot and state commits
- **THEN** the harvested state commit's parent is not the round's last registered commit and the task aborts as a boundary violation

#### Scenario: Tampered state file is caught by read-back
- **WHEN** in-box tampering alters `state.json` content between `putFile` and the state commit
- **THEN** the harvested content differs from what the factory wrote and the task aborts as a boundary violation

#### Scenario: Every check reads the attempt commit its own way
- **WHEN** verification of a sandboxed round runs
- **THEN** builtin checks read the snapshot commit as bare git objects in the factory clone, fresh-box checks and judge votes materialize from it, and external checks poll CI runs of exactly that pushed commit

#### Scenario: A tampered round trace is caught by read-back
- **WHEN** in-box tampering alters the round trace between `putFile` and the state commit
- **THEN** the harvested trace is not byte-identical to what the factory wrote and the task aborts as a boundary violation

#### Scenario: task.json never crosses the environment channel
- **WHEN** a sandboxed task's `task.json` changes
- **THEN** it is committed factory-side as a bare-object commit (see "Task lifecycle port"), never written into the environment, and no read-back runs for it

#### Scenario: The state commit sits on the round's last pending commit
- **WHEN** a sandboxed round whose check went `pending` closes
- **THEN** the harvested state commit's only parent is the round's last pending commit and its state carries no pending entry

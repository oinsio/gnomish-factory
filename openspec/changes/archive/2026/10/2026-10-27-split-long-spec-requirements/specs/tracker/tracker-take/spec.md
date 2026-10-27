## ADDED Requirements

### Requirement: Delivered state is read from the Completed commit
The delivered state a recovery reports SHALL be read from the `Completed` commit itself: at the tip while its envelope is still there, otherwise at the parent of the cleanup commit found in the branch's history.
**Implements**: FR3, FR4, FR8, NFR-R1 of fix-terminal-receipt-convergence; FR9 of harden-task-branch-contract; FR6 of fix-envelope-medium; FR2 of split-long-spec-requirements

#### Scenario: The delivered state never comes from a commit after the cleanup
- **WHEN** a delivered branch gained commits after its cleanup commit
- **THEN** the recovery reads the `Completed` envelope at the cleanup commit's parent and reports that state, whatever the later commits hold

## MODIFIED Requirements

### Requirement: Exit codes by take result
`gnomish take` SHALL exit with: 0 — Delivered, or a clean bare-mode no-op;
1 — failure outside a claimed run; 2 — usage error; 3 — pipeline load
failure; 8 — an operator precondition refused after the claim, the claim
released; 10 — parked as escalation; 11 — parked as checkpoint; 12 —
infrastructure abort below the fuse; 13 — parked as infra; 14 — revoked;
15 — refused or skipped; 16 — claim released on a base-refresh infrastructure
failure before any work started.
**Implements**: FR9 of add-tracker-port; FR10 of add-tracker-port; FR15 of add-tracker-port; FR9 of add-base-ref-resolution; FR14 of add-sandbox-base-image; FR1, FR2 of split-long-spec-requirements
**Notes**: 8 is the below-10 family's configuration code, in the spirit of `sysexits` `EX_CONFIG`: the operator fixes it, a retry will not (design D18); the preconditions are the clone's ref format, the container runtime, the box's git floor and the image contract, named in "Operator precondition refused after the claim exits 8".

#### Scenario: Empty queue exits clean
- **WHEN** bare `take` finds no eligible ready task
- **THEN** the process exits 0 reporting an empty queue

#### Scenario: Escalation park exit
- **WHEN** a take run parks its task as an escalation
- **THEN** the process exits 10

#### Scenario: Refusal exit
- **WHEN** `take <ref>` refuses a task held by another instance
- **THEN** the process exits 15 naming the holder

#### Scenario: Released-on-outage exit
- **WHEN** a single-shot `take` releases its claim because the base refresh
  hit a dead remote
- **THEN** the process exits 16 naming the remote and the cause, and the
  task is Ready with no new marker

#### Scenario: Failure before a claim exits 1
- **WHEN** the tracker is unreachable at startup, or label provisioning fails
- **THEN** the process exits 1

#### Scenario: Infra park exit
- **WHEN** a take run parks its task as infra, by a fuse trip or an infrastructure escalation
- **THEN** the process exits 13

#### Scenario: Every refusal and skip exits 15
- **WHEN** `take <ref>` finds its task already done, closed or nonexistent, or names a foreign
  repo
- **THEN** the process exits 15

#### Scenario: Shared codes mean the same in run
- **WHEN** `gnomish run` and `gnomish take` end with the same result family (usage error,
  pipeline load failure)
- **THEN** both exit with the same code

#### Scenario: An uncaught exception never exits a bare 1
- **WHEN** a claimed take run dies with an uncaught exception
- **THEN** the abort protocol runs and the process exits 12, or 13 at the fuse — never 1

#### Scenario: Operator precondition refused after the claim exits 8
- **WHEN** a single-shot `take` claims its task and the clone's ref format, the box's git floor or the image contract refuses
- **THEN** the process exits 8 printing the refusal and its fix, the claim is released, the task carries no abort marker, and the outcome log recorded the typed precondition result

### Requirement: A Completed-without-cleanup tip is finished, never re-executed
When the branch tip records the `Completed` outcome but the cleanup commit is
absent, the factory SHALL finish the delivery — deliver the tracker finish when
it is still owed, then commit the cleanup and push it — and SHALL NOT re-enter
the engine: zero rounds, no executor or judge invocation. Once the tracker
reports the task finished, the cleanup SHALL converge without a claim, from any
clone of origin, whether or not it holds the branch locally.
**Implements**: FR3, FR4, FR8, NFR-R1 of fix-terminal-receipt-convergence; FR9 of harden-task-branch-contract; FR6 of fix-envelope-medium; FR1, FR2 of split-long-spec-requirements
**Notes**: The order is FR10's: the cleanup is the destructive step, so it runs behind the confirmed finish.
**Notes**: Where the delivered state of the finish comes from is the requirement "Delivered state is read from the Completed commit".

#### Scenario: Kill between Completed and cleanup costs no re-run
- **WHEN** a previous run died after committing `Completed` but before its
  cleanup commit, and the task is taken again
- **THEN** the run posts the finish, commits the cleanup, pushes, and exits
  with the delivery exit code without executing any stage

#### Scenario: A finish that already landed is not posted twice
- **WHEN** the recovering run probes the tracker and finds the task already
  finished
- **THEN** no second finish is written, the cleanup commit still lands, and the
  run exits with the delivery exit code

#### Scenario: Delivered state survives commits after cleanup
- **WHEN** a delivered branch gained one or more commits after its cleanup
  commit and its tracker finish is still owed
- **THEN** the recovery locates the cleanup commit in history, reads the
  `Completed` envelope at its parent, and posts a finish carrying the state
  that was delivered

#### Scenario: Delivered state is read at the tip while the envelope is still there
- **WHEN** the tip still carries the `Completed` envelope
- **THEN** the delivered state is read at the tip, not at any parent

#### Scenario: A kill during the finish leaves the recoverable shape
- **WHEN** the recovering run is killed after the tracker finish but before the cleanup commit
- **THEN** the tip still records `Completed` with its envelope intact, and the next take
  recovers it by this same requirement

#### Scenario: A finished task's cleanup converges without a claim
- **WHEN** the tracker reports a task finished, its branch tip still carries the
  `Completed` envelope, and `take <ref>` names it, `run --resume` names it, or
  the `serve` janitor passes over it
- **THEN** the cleanup commit lands and is pushed, the worktree or environment
  is disposed, no claim is written, and the task is reported as already done

#### Scenario: Two instances converge on one cleanup commit
- **WHEN** two instances finish the cleanup of the same finished task's branch at the same time,
  through `take`, `run --resume` or the `serve` janitor
- **THEN** both go through one owner, one cleanup commit lands, the loser's ref update fails
  without force, and neither instance fails: the loser reports the task as already done

#### Scenario: A finished task's branch held only as remote-tracking converges
- **WHEN** the tracker reports a task finished, its branch is `CompletedUncleaned` at origin, and
  a clone that holds it only as a remote-tracking ref — brought by the factory's own touchpoint
  or by an operator's or an IDE's plain `git fetch`, so with or without the register — runs
  `take <ref>`, `run --resume` or the `serve` janitor's branch pass
- **THEN** the branch and its register are fetched first, the branch is adopted as a local
  branch, the cleanup commit lands and is pushed, no claim is written, and the task is reported
  as already done

# Spec Delta: tracker/tracker-take

## MODIFIED Requirements

Layered on "A Completed-without-cleanup tip is finished, never re-executed" as modified by
`tighten-spec-contract` (sequenced before this change). That delta kept the finish-then-cleanup
rule in the body, moved the kill window into its note and the scenario "A kill during the finish
leaves the recoverable shape", and gave the tip-or-parent read its two scenarios; this text
widens the subject from the take to the factory, delivers the finish only when it is still owed,
adds the claimless convergence of the cleanup to the body, and adds the scenarios "A finished
task's cleanup converges without a claim", "Two instances converge on one cleanup commit" and
"A finished task's branch held only as remote-tracking converges".

### Requirement: A Completed-without-cleanup tip is finished, never re-executed
When the branch tip records the `Completed` outcome but the cleanup commit is
absent, the factory SHALL finish the delivery — deliver the tracker finish when
it is still owed, then commit the cleanup and push it — and SHALL NOT re-enter
the engine: zero rounds, no executor or judge invocation. Once the tracker
reports the task finished, the cleanup SHALL converge without a claim, from any
clone of origin, whether or not it holds the branch locally. The
delivered state a recovery reports SHALL be read from the `Completed` commit itself.
**Implements**: FR3, FR4, FR8, NFR-R1 of fix-terminal-receipt-convergence; FR9 of harden-task-branch-contract; FR6 of fix-envelope-medium
**Notes**: The order is FR10's: the cleanup is the destructive step, so it runs behind the confirmed finish.

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

## ADDED Requirements

### Requirement: Receipts are confirmed from tenure history, not from the current state
A recovery that must decide whether a terminal tracker effect — a park or a finish — already
landed before re-driving it SHALL decide from the task's latest tenure boundary as `fetchTask`
reports it, never from the task's current logical state. A park has landed only when that
boundary is `PARK`; a finish has landed when it is `FINISH` or the `finished` fact is set.
**Implements**: FR2, FR3, UX1 of fix-terminal-receipt-convergence
**Notes**: By the time any pickup runs, the pickup's own claim has already made the state `Working`.

#### Scenario: A landed park with a lost receipt is not re-driven
- **WHEN** a park landed on the tracker, the process died before clearing the pending marker, a
  human returned the task, and another instance claimed it
- **THEN** the pickup confirms the park from the `PARK` boundary, clears the marker, consumes
  the human's reply or approval, and the tracker shows one park comment and no second park

#### Scenario: A lost park is re-driven
- **WHEN** the park write never reached the tracker, the reaper returned the stale claim, and
  another instance claimed the task
- **THEN** the pickup finds the latest boundary `STALE_CLAIM_REMOVED`, re-drives the park, and
  exits without running a stage

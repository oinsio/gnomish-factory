# Spec Delta: tracker-take

## MODIFIED Requirements

### Requirement: A Completed-without-cleanup tip is finished, never re-executed
When the branch tip records the `Completed` outcome but the cleanup commit is
absent, the factory SHALL finish the delivery — deliver the tracker finish when
it is still owed, then commit the cleanup and push it — and SHALL NOT re-enter
the engine: zero rounds, no executor or judge invocation. The order is FR10's:
the cleanup is the destructive step and runs behind the confirmed finish, so a
kill anywhere in the sequence leaves the tip recording `Completed` with its
envelope intact — the same shape this requirement recovers. The cleanup half
SHALL converge **without a claim** once the tracker reports the task finished:
`take <ref>` on a finished task performs it instead of refusing with nothing
done, `run --resume` on a completed task performs it before reporting, and the
`serve` janitor performs it on its pass over the clone's task branches — all
through one owner, so two instances meeting on the same branch land one cleanup
commit and the loser's ref update fails without force. The delivered state a
recovery reports SHALL be read from the `Completed` commit itself: at the tip
when the tip still carries the envelope, otherwise at the parent of the cleanup
commit located in the tip's history — never from the tip's parent by
assumption, so a branch that gained commits after its cleanup still reports the
state it delivered.
<!-- implements FR3, FR4, NFR-R1 of fix-terminal-receipt-convergence -->
<!-- implements FR9 of harden-task-branch-contract; FR6 of fix-envelope-medium -->

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

#### Scenario: A finished task's cleanup converges without a claim
- **WHEN** the tracker reports a task finished, its branch tip still carries the
  `Completed` envelope, and `take <ref>` names it, `run --resume` names it, or
  the `serve` janitor passes over it
- **THEN** the cleanup commit lands and is pushed, the worktree or environment
  is disposed, no claim is written, and the task is reported as already done

#### Scenario: Delivered state survives commits after cleanup
- **WHEN** a delivered branch gained one or more commits after its cleanup
  commit and its tracker finish is still owed
- **THEN** the recovery locates the cleanup commit in history, reads the
  `Completed` envelope at its parent, and posts a finish carrying the state
  that was delivered

#### Scenario: Delivered state is read at the tip while the envelope is still there
- **WHEN** the tip still carries the `Completed` envelope
- **THEN** the delivered state is read at the tip, not at any parent

## ADDED Requirements

### Requirement: Receipts are confirmed from tenure history, not from the current state
A recovery that must decide whether a terminal tracker effect — a park or a finish — already
landed before re-driving it SHALL decide from the task's latest tenure boundary as `fetchTask`
reports it, never from the task's current logical state: by the time any pickup runs, the
pickup's own claim has already made the state `Working`. A park whose latest boundary is
`PARK` has landed, and the pickup SHALL clear the branch's pending marker and continue with the
human's return rather than park again; a park whose latest boundary is `STALE_CLAIM_REMOVED` or
none never landed and SHALL be re-driven. A finish whose latest boundary is `FINISH`, or whose
`finished` fact is set, has landed.
<!-- implements FR2, FR3, UX1 of fix-terminal-receipt-convergence -->

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

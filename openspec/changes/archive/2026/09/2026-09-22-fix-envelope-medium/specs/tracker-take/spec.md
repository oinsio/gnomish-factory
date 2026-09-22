## MODIFIED Requirements

### Requirement: A Completed-without-cleanup tip is finished, never re-executed
When the branch tip records the `Completed` outcome but the cleanup commit is
absent, the take SHALL finish the delivery — deliver the tracker finish, then
commit the cleanup and push it — and SHALL NOT re-enter the engine: zero
rounds, no executor or judge invocation. The order is FR10's: the cleanup is
the destructive step and runs behind the confirmed finish, so a kill anywhere
in the sequence leaves the tip recording `Completed` with its envelope intact —
the same shape this requirement recovers. The delivered state a recovery
reports SHALL be read from the `Completed` commit itself: at the tip when the
tip still carries the envelope, otherwise at the parent of the cleanup commit
located in the tip's history — never from the tip's parent by assumption, so a
branch that gained commits after its cleanup still reports the state it
delivered.
<!-- implements FR9 of harden-task-branch-contract -->
<!-- implements FR6 of fix-envelope-medium -->

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

## ADDED Requirements

### Requirement: Host envelope reads resolve at the branch tip
<!-- implements FR1, FR2, FR5, FR9, NFR-R2 of fix-envelope-medium -->
In host mode, every read of a factory-owned `.gnomish-task/` file that a decision rests on —
the resume bootstrap's `task.json`, the recorded `state.json` a resume or a completion reads
back, and the current content a lifecycle rewrite carries forward (the pending-write marker
clear, the decision append, the denial-cursor carry-forward) — SHALL resolve at the branch tip
the task worktree has checked out, never at the working-tree file. The working tree is where the
next envelope version is staged for its commit; it is not a medium any reader consults for the
current one. An envelope absent at the tip SHALL be reported as a typed absence the caller
routes on, and an envelope read that did not run to its own exit SHALL be reported as
unavailability, never as absence. The envelope's path names — the state directory, its two
files and the decisions subdirectory — SHALL be spelled once, in the lowest module every reader
depends on, and every medium's reader SHALL address the files through that one spelling; a
literal spelling of those names anywhere else in production code SHALL fail the build.

#### Scenario: A stale worktree does not change what resume reads
- **WHEN** a previous process left the worktree without `.gnomish-task/` (a staged removal it
  never committed) while the branch tip still carries a `Completed` envelope
- **THEN** resume reads the `Completed` envelope from the tip, classifies the branch as a
  finished task awaiting cleanup, and routes to the completion finish — not to the delivered
  path

#### Scenario: A half-written worktree file is never carried forward
- **WHEN** a lifecycle rewrite runs over a worktree whose `state.json` differs from the tip's
- **THEN** the field it carries forward (the denial cursor) is the tip's value, and the
  commit that lands is what the tip plus the rewrite imply

#### Scenario: Absence and unavailability are distinct answers
- **WHEN** the tip carries no `task.json`
- **THEN** the reader reports absence and the caller routes to the delivered recovery
- **WHEN** the read is cut off before git exits
- **THEN** the reader reports unavailability and no route is taken on that answer

#### Scenario: Every medium addresses the envelope by one spelling
- **WHEN** the host tip reader, the container tip reader and the shape classifier name the
  envelope's files
- **THEN** all three resolve the same path constants, so a renamed envelope file changes one
  declaration and every reader follows

#### Scenario: A hand-typed envelope path fails the build
- **WHEN** a production source outside the owner spells `.gnomish-task`, `task.json` or
  `state.json` as a literal
- **THEN** the build fails naming the file and the owner to use instead

## MODIFIED Requirements

### Requirement: Cleanup on completion
On `Completed` the outcome commit SHALL carry a pending-cleanup marker; the cleanup commit removing `.gnomish-task/` from the branch tip is the destructive last step of the completion sequence, created only after the constructive steps have their receipts, and the terminal tracker finish is delivered only after the constructive receipts as well. A tip recording `Completed` with the marker but without the cleanup commit is a finished task awaiting cleanup, never one to re-execute. All state files remain reachable in branch history as the audit trail. In sandboxed mode the cleanup commit SHALL be built factory-side from bare tree objects — no checkout of the branch ever occurs in factory-owned filesystem — and SHALL NOT require a live environment: the last in-box commit is the state commit, and the environment MAY be disposed before the outcome and cleanup commits are created. Host mode keeps the worktree cleanup commit, and its guard SHALL test the branch tip — the same test the sandboxed medium makes — never the working tree: a tip without `.gnomish-task/` is a no-op in either medium. The host step SHALL converge from every state its own two-command sequence can freeze: a removal already staged by a killed predecessor lands in the cleanup commit rather than failing or being skipped, and the second call on a cleaned tip changes nothing.
<!-- implements FR15 of add-git-workflow -->
<!-- implements FR25 of add-sandbox-core -->
<!-- implements FR10 of harden-task-branch-contract -->
<!-- implements FR3, FR4, FR7, NFR-O1 of fix-envelope-medium -->

#### Scenario: Clean tip, full history
- **WHEN** a task completes
- **THEN** the branch tip contains no `.gnomish-task/` while every round commit remains in history

#### Scenario: Kill between outcome and cleanup does not re-run the final stage
- **WHEN** an instance dies after the `Completed` outcome commit (marker present) but before the cleanup commit
- **THEN** the next pickup finishes the cleanup, pushes, and delivers the tracker finish — without re-entering the engine or re-running any stage

#### Scenario: Cleanup works after dispose
- **WHEN** a sandboxed task completes and its environment is already disposed
- **THEN** the outcome and cleanup commits are still created factory-side and pushed, with no environment required

#### Scenario: Kill between the staged removal and its commit converges on the same worktree
- **WHEN** a host instance dies after `.gnomish-task/` was removed from the worktree and its
  index but before the cleanup commit, and the next pickup reuses that same worktree
- **THEN** the pickup classifies the tip as a finished task awaiting cleanup, the cleanup
  commit lands the removal that was already staged, the tip ends without `.gnomish-task/`, the
  worktree is removed, and a second pickup changes nothing

#### Scenario: A converged staged removal is visible in the log
- **WHEN** the cleanup commit lands a removal a predecessor already staged
- **THEN** one INFO line names the task and says the staged removal was committed

# Spec Delta: git/git-task-persistence

The cleanup commit now has one writer on both media, the bare-object one (design D8): the host
no longer removes `.gnomish-task/` in the task worktree and commits the staged removal. Two
scenarios of "Cleanup on completion" described only that worktree path ("Kill between the staged
removal and its commit converges on the same worktree", "A converged staged removal is visible
in the log"), and a MODIFIED block cannot drop a scenario (`delta-specs.md`), so the requirement
is removed and replaced, under a new header, by "Cleanup commit on completion". Every other
scenario is carried over with its title unchanged, so its test markers stay valid.

The ADDED requirement "Task branch adoption" (design D9–D12) is the one owner of making a branch
a clone holds only as a remote-tracking ref local, which every write path — host and container,
claimed and claimless — runs before it writes.

The `Unregistered` shape (design D13) replaces `Unknown` wherever a scenario of this capability
described a branch whose register the clone does not hold: "Resume from the recorded branch" and
"Factory register ref" are repeated whole with that one word changed and one scenario added for
the fetch a third party makes; every existing scenario keeps its title.

## REMOVED Requirements

### Requirement: Cleanup on completion
**Reason**: two of its scenarios describe the host's worktree cleanup writer (a staged removal in the task worktree, then its commit), which this change deletes (design D8, FR4, NFR-R1); a MODIFIED block cannot drop them, so the requirement is replaced by "Cleanup commit on completion".
**Migration**: none for operators; the cleanup commit, its subject and its register are unchanged. The two dropped scenarios are retired by this delta (design D7): the spec-contract gate stops requiring their link at once, so the marker and the exemption line that named them are removed in this change (task 0.3), not at the archive; the carried-over scenarios resolve through "Cleanup commit on completion".

## ADDED Requirements

### Requirement: Cleanup commit on completion
On `Completed` the outcome commit SHALL carry a pending-cleanup marker, and a cleanup commit removing `.gnomish-task/` from the branch tip SHALL follow as the destructive last step of the completion sequence, while every state file stays reachable in branch history as the audit trail. On both media the cleanup commit SHALL be built factory-side from the tip's tree objects, never from a working copy or its index.
**Implements**: FR15 of add-git-workflow; FR25 of add-sandbox-core; FR10 of harden-task-branch-contract; FR3, FR4, FR7, NFR-O1 of fix-envelope-medium; FR4, NFR-R1 of fix-terminal-receipt-convergence
**Notes**: The scenario title "The cleanup commit is built per mode" is kept from the replaced requirement so its markers stay valid; it now states one writer for every mode.

#### Scenario: Clean tip, full history
- **WHEN** a task completes
- **THEN** the branch tip contains no `.gnomish-task/` while every round commit remains in history

#### Scenario: Kill between outcome and cleanup does not re-run the final stage
- **WHEN** an instance dies after the `Completed` outcome commit (marker present) but before the cleanup commit
- **THEN** the next pickup finishes the cleanup, pushes, and delivers the tracker finish — without re-entering the engine or re-running any stage

#### Scenario: Cleanup works after dispose
- **WHEN** a sandboxed task completes and its environment is already disposed
- **THEN** the outcome and cleanup commits are still created factory-side and pushed, with no environment required

#### Scenario: Cleanup follows every constructive receipt
- **WHEN** a task completes
- **THEN** the cleanup commit is created only after the constructive steps have their receipts, and the terminal tracker finish is delivered only after the constructive receipts as well

#### Scenario: The cleanup commit is built per mode
- **WHEN** a task's cleanup commit is created, in host or sandboxed mode
- **THEN** it is built factory-side from the tip's bare tree objects in the clone's `.git`, with a private index, and lands with the factory register in one ref transaction under the tip's compare-and-swap
- **AND** no checkout of the branch occurs in factory-owned filesystem and no worktree index is touched — the host's task worktree included

#### Scenario: A tip without the state directory is a cleanup no-op
- **WHEN** the cleanup step runs, in host or sandboxed mode, on a branch tip that carries no `.gnomish-task/`
- **THEN** no commit is created, whatever the host working tree holds — the guard tests the branch tip, never the working tree

#### Scenario: A host worktree already missing .gnomish-task/ does not affect the cleanup
- **WHEN** a host task's tip is `CompletedUncleaned` while its worktree no longer holds `.gnomish-task/` (a killed predecessor removed it there), and the next pickup reuses that worktree
- **THEN** the cleanup commit is built from the tip, the tip ends without `.gnomish-task/`, the worktree is removed, and a second pickup changes nothing

### Requirement: Task branch adoption
A task branch a clone holds only as a remote-tracking ref SHALL become local through one owner, by one ref transaction that creates `refs/heads/gnomish/<task>` at the tracking tip under a compare-and-swap on absence — no checkout, no `git branch`, no name git resolves itself. Every path that writes the branch SHALL adopt before it writes; a head that adoption created whose closing push origin rejects SHALL stay as the clone holds it.
**Implements**: FR7, FR8, FR9, NFR-R1 of fix-terminal-receipt-convergence
**Notes**: The register is not moved by an adoption: the touchpoint fetch that refreshed the tracking ref already landed origin's register in the clone's register (design D9).
**Notes**: No adoption is undone, force-pushed or pushed again by a claimless path (design D12): a clone keeps every finished local branch, so the loser's line costs one ref, no more than a cleanup the clone had won; it has no recovery owner: the shape is terminal and inert, every reader judges it by its registered tip, and a claimless path fails closed on it (proposal NG4).

#### Scenario: A remote-tracking-only branch is adopted at its tracking tip
- **WHEN** a clone holds the branch only as `refs/remotes/origin/gnomish/<task>` and a write path runs
- **THEN** `refs/heads/gnomish/<task>` exists at that tip, the clone's register is unchanged, and no checkout occurred

#### Scenario: Two adopters land one head
- **WHEN** two processes adopt one branch of one clone at once
- **THEN** one create lands, the other reads the branch back as local at the same tip and proceeds, and neither fails

#### Scenario: The host worktree is added on an existing local branch
- **WHEN** a host bootstrap materializes a task worktree
- **THEN** the branch was adopted first and the worktree is added on that existing local branch, never on a name git had to resolve

#### Scenario: A lost cleanup race leaves the adopted head in place
- **WHEN** a head adoption created carries a cleanup commit whose push origin rejected because another clone delivered first
- **THEN** the head and the register stay at that cleanup commit, origin is untouched and holds the other clone's one cleanup commit, and the clone's next claimless pass reads the tip as `Delivered`, writes nothing and pushes nothing

#### Scenario: A diverged adopted head is left standing by every reader
- **WHEN** the clone holding that line later reconciles the task at a touchpoint, takes it under a claim after the tracker lost the finish, or runs a manual `run --resume` on it
- **THEN** the reconciliation declines the catch-up push and reports that origin's tip is not an ancestor of the local tip, the claimed take reads `Delivered` and reports the task done, and the manual resume fails closed on the divergence — no path deletes, moves or force-pushes the head

#### Scenario: A cleanup push that does not reach origin keeps the adopted head
- **WHEN** the cleanup commit's push on an adopted head does not run to its own exit
- **THEN** the head stays and the next touchpoint pushes it

#### Scenario: A branch that exists nowhere is not adopted
- **WHEN** neither the local branch nor a remote-tracking ref exists after the touchpoint fetch
- **THEN** the answer is "nowhere" and no ref is written

## MODIFIED Requirements

### Requirement: Resume from the recorded branch
`--resume <task>` SHALL locate the branch — the local branch, else the touchpoint fetch of exactly `gnomish/<task>` and its register, else, only when origin cannot deliver, a tracking ref already present — then continue by `task.json` outcome: escalated → decision dialog; paused → confirmation; null → continue from the recorded position; completed → report "task done" and exit. A missing task working copy SHALL be materialized through the bound task environment from the branch state alone.
**Implements**: FR10 of fix-terminal-receipt-convergence; FR8 of add-git-workflow; FR6 of add-sandbox-core; FR7, FR12 of add-base-ref-resolution; FR13 of tighten-spec-contract
**Notes**: `git clone` brings a task branch as a remote-tracking ref without its register, so a present tracking ref does not end the locate (design D13 of tighten-spec-contract, amended 2026-10-13).

#### Scenario: Another instance resumes from origin
- **WHEN** the branch exists only on origin
- **THEN** resume fetches that single ref and materializes an environment that continues from the recorded position

#### Scenario: A tracking ref without a local branch still fetches the register
- **WHEN** no local task branch exists and a remote-tracking ref of it is already present, as a `git clone` leaves it
- **THEN** the locate runs the touchpoint fetch, and origin's register lands in the clone's register beside the refreshed tracking ref

#### Scenario: An unreachable origin falls back to the tracking ref
- **WHEN** no local task branch exists, a remote-tracking ref of it is present, and origin cannot deliver
- **THEN** the locate answers with that tracking ref, the clone's register stays as an earlier touchpoint fetch left it, and a branch with no register in the clone classifies `Unregistered` — the honest answer offline, never `Unknown`

#### Scenario: Autonomous resume refreshes the pinned base for the law
- **WHEN** a tracker-driven resume finds the task branch and a pinned base ref name
- **THEN** it narrow-fetches exactly that ref name as well, binds the law from its tip (pipeline-config), and treats an unreachable remote as an infrastructure failure like the base refresh; manual resume without a reachable remote binds from the local ref tip

### Requirement: Factory register ref
The factory SHALL keep, per task, the ref `refs/gnomish-factory/<task>` in origin and in its clone, naming its latest closing commit: STARTED, a round's state commit, a snapshot, an outcome, an approval, an appended decision, a salvage, the cleanup. Every closing push SHALL push the task branch and the register in one atomic push; a mid-round push or harvest SHALL move the branch alone. Every touchpoint fetch of the branch SHALL fetch the register with it, in one atomic transfer.
**Implements**: FR10, FR11 of fix-terminal-receipt-convergence; FR13, NFR-S2 of tighten-spec-contract
**Notes**: The register is the factory's trust anchor on the branch: a commit id is content-addressed, and only a factory closing push moves the ref (design D13 of tighten-spec-contract).
**Notes**: In host mode the gnome shares the clone and can move the ref; the host tier carries no integrity claim (design D19), and the register there is a detection.

#### Scenario: Branch and register land together
- **WHEN** a closing commit is pushed
- **THEN** origin's task branch and register both name the new commit, or neither moved — a refusal of either ref lands nothing

#### Scenario: A mid-round push leaves the register
- **WHEN** a mid-round push or an admitted harvest pushes the branch
- **THEN** the register at origin and in the clone still names the previous closing commit

#### Scenario: The register follows every closing commit
- **WHEN** any factory closing commit lands — STARTED, state, snapshot, outcome, approval, decision, salvage or cleanup
- **THEN** after its push the register and the branch tip are the same commit, on a bare origin, for every closing path of both media

#### Scenario: Killed between admission and push
- **WHEN** an instance dies after the admission moved the branch locally and before its push
- **THEN** the next pickup fetches the register and the branch from origin, the register names the last closing commit, and the next harvest re-admits the range as an ordinary open round

#### Scenario: The touchpoint fetch carries the register
- **WHEN** a pickup, a status read or a reconcile fetches the task branch from origin
- **THEN** the same transfer fetches the register, and the branch is never guessed: a non-ancestor register classifies it `Unknown`, a register origin does not carry leaves it `Unregistered`

#### Scenario: A third party's fetch brings the branch without its register
- **WHEN** an operator's `git fetch`, a `git pull` or an IDE's periodic fetch lands a task branch as a remote-tracking ref in the factory clone
- **THEN** the clone holds no register for it, the branch classifies `Unregistered` on a local read, and the next factory touchpoint of that task — a locate, a `status <task>`, or the `serve` janitor's branch pass — fetches the register in place and classifies the branch by its content

#### Scenario: A rejected branch update leaves the register unmoved
- **WHEN** a touchpoint fetch's branch refspec is rejected by the clone (origin's branch is not a descendant of the clone's tracking ref)
- **THEN** neither the tracking ref nor the register moves, and the fetch reports the rejection

#### Scenario: The reconcile brings the register forward
- **WHEN** the clone's task branch equals or is ahead of origin's, and origin's register is on that line while the clone holds no register or an ancestor of origin's
- **THEN** the reconcile moves the clone's register to origin's in one ref transaction that verifies the branch unmoved

#### Scenario: A register ahead of origin's is never rolled back
- **WHEN** a closing push was lost and a later branch-only push made origin's branch equal to the clone's, so the clone's register is ahead of origin's
- **THEN** the reconcile leaves the clone's register naming its own closing commit

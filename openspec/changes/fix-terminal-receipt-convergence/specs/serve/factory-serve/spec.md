# Spec Delta: serve/factory-serve

## MODIFIED Requirements

Keeps the age policy of "Worktree cleaner disposes aged task environments" as it stands in
`openspec/specs/` and adds the branch pass of FR4: the cleaner converges the clone's
`CompletedUncleaned` branches whose tracker reports the task finished. The scenario "The
cleaner consults no tracker status" stays true for the age pass; the tracker is read by the
branch pass only, for those branches only. Amended 2026-10-15 (design D13, FR11, FR12): the pass
forwards by the domain's table — `CompletedUncleaned` and `Unregistered` — and the owner reads a
forwarded branch through the fetching locate before it decides, so a branch a third party's
`git fetch` brought without its register converges too; nothing is skipped silently.

### Requirement: Worktree cleaner disposes aged task environments
The daemon SHALL dispose of host worktree environments whose last file activity is at least as old as a configured age and which do not currently occupy a slot of this instance, through the bound task environment port. On every tick the cleaner SHALL also forward each `CompletedUncleaned` or `Unregistered` task branch of the clone to the one claimless owner, which fetches before it classifies and claims nothing, and SHALL log every branch it does not forward.
**Implements**: FR4, FR11, FR12, UX2, NFR-C2, NFR-O2 of fix-terminal-receipt-convergence; FR14 of add-factory-serve; FR5, FR6 of add-serve-sandbox-lifecycle
**Notes**: Ended tasks (delivered, escalated, revoked) stop touching their worktrees and are the population the age policy targets; worktrees are instance-local, so one disposed too early costs only a re-materialize on resume, never correctness.
**Notes**: Container environments live in a host-global namespace; the ownership-based sweep and aged reaper of `sandbox-lifecycle` govern them.

#### Scenario: Aged environment removed
- **WHEN** an escalated task's worktree exceeds the age threshold
- **THEN** the cleaner removes it, and a later resume rematerializes the
  worktree from the branch

#### Scenario: Aged container environment removed by runtime age
- **WHEN** an escalated task's stopped container exceeds the age threshold per its runtime metadata
- **THEN** this cleaner leaves it untouched — it is not a host worktree — and the `sandbox-lifecycle` aged reaper disposes container, volume, and network by the same runtime-metadata age, so a later resume still materializes a fresh environment from the branch

#### Scenario: Working task untouched
- **WHEN** the cleaner runs while a task is `Working` in a slot of this
  instance
- **THEN** that task's environment is not considered for disposal

#### Scenario: The cleaner consults no tracker status
- **WHEN** the cleaner evaluates an aged host worktree that occupies no slot
  of this instance
- **THEN** it disposes the worktree without reading the task's tracker
  status; only the branch pass reads the tracker, and only for a `CompletedUncleaned` tip

#### Scenario: Resume reuses a still-present worktree
- **WHEN** a task resumes on the same instance while its worktree is still
  present
- **THEN** the slot reuses that worktree rather than materializing a new one

#### Scenario: A finished task's branch is cleaned on the branch pass
- **WHEN** the clone holds a `gnomish/*` branch whose tip records `Completed` with its envelope
  still there and the tracker reports the task finished
- **THEN** the tick lands the cleanup commit, pushes it, disposes the environment, writes no
  claim, and the next tick finds the branch `Delivered` and writes nothing

#### Scenario: A branch fetched without its register is read through the fetch before it is judged
- **WHEN** the clone holds a `gnomish/*` branch only as a remote-tracking ref with no register
  for it — as an operator's or an IDE's `git fetch` leaves it — while origin's register names a
  `CompletedUncleaned` tip and the tracker reports the task finished
- **THEN** the pass forwards the branch, the owner's locate fetches the branch and its register
  in place, the tip classifies `CompletedUncleaned`, one tracker read confirms the finish, the
  branch is adopted, the cleanup lands and is pushed, and the next tick finds it `Delivered`

#### Scenario: A branch the pass does not forward costs nothing
- **WHEN** the clone holds a `Delivered`, an `InProgress` and a `Parked` branch
- **THEN** the pass runs no fetch and no tracker read for them and logs one DEBUG line per
  branch naming the shape it left alone

#### Scenario: A branch still unregistered after the owner's read is named, not skipped
- **WHEN** a forwarded `Unregistered` branch is still `Unregistered` after the owner's read —
  origin delivered no register for the task, origin could not be reached, or the clone holds it
  as a local head, which the locate does not fetch
- **THEN** the pass logs one aggregate line per pass naming such branches and the location kind
  of each, without asserting which cause holds, writes nothing to them, and the next tick names them again rather than skipping them silently

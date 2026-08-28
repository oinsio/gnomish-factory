# git-task-persistence — delta for harden-logging-observability

Layered on the capability as modified by `harden-task-branch-contract`
(sequenced before this change); the requirement modified here is not touched
by that change's delta.

## MODIFIED Requirements

### Requirement: Gnome commits within a round
Gnome commits inside a round SHALL be allowed (encouraged via stage instructions, using plain git); the adapter's commit closes the round. Boundary verification SHALL run factory-side against harvested refs in sandboxed mode: history rewrite is refused by the fast-forward-only harvest itself; `.gnomish-task/` SHALL be untouched by the gnome between tips — with exactly one carve-out, `decisions/<stage>-a<attempt>.json` (FR23); the in-box HEAD check before the snapshot commit is advisory only. In host mode the existing worktree checks (HEAD on the task branch, previous tip an ancestor, `.gnomish-task/` untouched) remain. A violation breaks durability: persist SHALL throw, aborting the task, with the evidence kept on the branch and in the kept environment.

Boundary verification SHALL distinguish three outcomes, never two: clean,
violated, and **cannot-verify**. A git invocation that fails while producing
the evidence (non-zero exit, unreadable revs) SHALL classify as
cannot-verify — an infrastructure failure that aborts the round without
burning a stage attempt and without attributing a violation to the gnome —
and SHALL never be read as clean. Both boundary-check media (worktree diff
and harvested-ref check) SHALL implement the same three-outcome rule.
<!-- implements FR12 of add-git-workflow -->
<!-- implements FR21, FR23 of add-sandbox-core -->
<!-- implements FR13 of harden-logging-observability -->

#### Scenario: Fine-grained gnome history is preserved
- **WHEN** the gnome makes three commits during a round
- **THEN** the round-closing commit builds on them and all four commits reach the branch

#### Scenario: History rewrite aborts
- **WHEN** at the round boundary the previous tip is no longer an ancestor of the branch
- **THEN** persist throws (host) or the ff-only harvest refuses (sandboxed) and the task ends Aborted

#### Scenario: Decision request is the one permitted state-directory write
- **WHEN** a gnome commit adds `.gnomish-task/decisions/<stage>-a<attempt>.json` and touches nothing else under `.gnomish-task/`
- **THEN** boundary verification passes; any other `.gnomish-task/` change still aborts

#### Scenario: A failed boundary probe never passes as clean
- **WHEN** the git invocation backing the boundary check exits non-zero
  (damaged repo, bad rev) while its output stream is empty
- **THEN** the check reports cannot-verify, the round aborts as an
  infrastructure failure with the git failure as evidence, no stage attempt
  is burned, and no boundary violation is attributed to the gnome

## ADDED Requirements

### Requirement: Verified tip resolution on durable paths
Any branch-tip resolution whose result is recorded durably (an attempt
commit's tip, a snapshot record) or gates a recovery decision SHALL verify
the resolving invocation succeeded and produced a non-blank ref; on failure
the operation SHALL fail with the git evidence rather than record or compare
a blank value. Read-only polling probes MAY skip their observation on a
failed resolution, but SHALL never treat it as an observed change. Both
attempt-persistence media follow the same rule.
<!-- implements FR13 of harden-logging-observability -->

#### Scenario: A blank tip is never recorded
- **WHEN** the tip resolution behind an attempt or snapshot record fails
- **THEN** the persist operation fails with the git failure as evidence and
  no record carrying a blank tip is created

#### Scenario: A failed poll observation is not a change
- **WHEN** a mid-round polling probe cannot resolve the branch tip
- **THEN** the probe skips that observation without reporting the tip as
  moved or lost

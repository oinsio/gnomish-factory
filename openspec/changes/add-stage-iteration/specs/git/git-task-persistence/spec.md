# git/git-task-persistence

## ADDED Requirements

### Requirement: Iteration state persists additively and survives rewrites
`state.json` SHALL gain, additively under contract v1: the item cursor,
the frozen item snapshot (ordered ids with content hashes, per-item state
and attempt count, provenance for adopted items), and per-item progress
records. Documents written before this change SHALL read as not
iterating, and no lifecycle rewrite of the state file (STARTED, RESUMED,
park, finish) SHALL drop committed iteration state — the
cursor-preservation rule extends to it.
**Implements**: FR2, FR6, NFR-R2 of add-stage-iteration
**Notes**: Every item transition is a single commit, as `stage-iteration`'s `Item completion is a single-commit transition` states.

#### Scenario: Pre-change document reads as not iterating
- **WHEN** a state.json without iteration fields is read
- **THEN** the state reports no iteration and engine behavior is
  pre-change

#### Scenario: RESUMED rewrite preserves the cursor
- **WHEN** a resume rewrites the state file on a task mid-list
- **THEN** the tip still carries the cursor, snapshot, and progress
  records

#### Scenario: Both media serialize iteration state identically
- **WHEN** the same item transition is persisted in host mode and in container mode
- **THEN** the iteration fields of the two `state.json` documents are field-for-field identical

# verification/sealed-assets — delta for harden-verification-integrity

## Purpose

What keeps verification independent of the tree it verifies: the frozen law tree inside the
box, the declared protected paths, and the deterministic check that an attempt stayed within
them.

## ADDED Requirements

### Requirement: Law tree is materialized read-only into the environment
For every round and check the factory SHALL materialize the frozen law tree from the law
commit into the environment at a factory-owned path outside the working copy, exposed as
`GNOMISH_LAW_DIR`, read-only for the task user; content SHALL never be taken from the working
copy.
**Implements**: FR1, NFR-S1, NFR-R1 of harden-verification-integrity

#### Scenario: Gnome edit to a script is irrelevant
- **WHEN** the gnome rewrites `.gnomish/verify/run-tests.sh` in the working copy and a check runs
  `$GNOMISH_LAW_DIR/verify/run-tests.sh`
- **THEN** the law commit's script runs and the working-copy edit has no effect

#### Scenario: Write to the law dir fails
- **WHEN** a command in the box writes under `GNOMISH_LAW_DIR`
- **THEN** the write fails and the check continues against unchanged content

### Requirement: Protected paths are declared in the law
The law MAY declare `protected:` globs; the loader SHALL validate them lexically (no traversal,
no absolute paths) and SHALL reject a glob matching the law tree itself as redundant.
**Implements**: FR2 of harden-verification-integrity

#### Scenario: Traversal in a glob
- **WHEN** `protected:` contains `../secrets/**`
- **THEN** loading reports a located error naming the glob

### Requirement: diff-scope verdict executor
The built-in `diff-scope` SHALL compare the attempt commit with the task base from the factory
clone's objects and SHALL fail with one finding per protected path changed unless the stage
declares `allows-protected`, SHALL fail on deletion of a protected file regardless, and SHALL
fail when the diff exceeds the declared size cap. It needs no box.
**Implements**: FR3, NFR-R2, NFR-O1, UX1, M1 of harden-verification-integrity

#### Scenario: Relaxed test is caught
- **WHEN** the attempt edits `src/test/FooSpec.groovy` and `src/test/**` is protected
- **THEN** the check fails with a finding "protected path changed: src/test/FooSpec.groovy (by
  src/test/**)"

#### Scenario: Stage may touch protected paths
- **WHEN** the stage declares `allows-protected: true` and the attempt edits a protected file
- **THEN** the check passes and the change is recorded as a finding of level `note`

#### Scenario: Deletion is never allowed
- **WHEN** the attempt deletes a protected file in a stage with `allows-protected: true`
- **THEN** the check fails naming the deletion

#### Scenario: Oversized diff
- **WHEN** the attempt diff exceeds the declared cap
- **THEN** the check fails naming the size and the cap

### Requirement: Judges know what protected paths changed
Every `verdict` request SHALL carry, as delimited data, the list of protected paths the attempt
changed (possibly empty), so a judge grades with that knowledge and never from the gnome's
narrative alone.
**Implements**: FR4 of harden-verification-integrity

#### Scenario: Judge sees the protected change
- **WHEN** an attempt edits a protected threshold file and a judge runs
- **THEN** the judge's request carries that path inside the data delimiters

# git/git-task-persistence — delta for own-git-invocation-policy

## MODIFIED Requirements

Layered on `Factory git executes no untrusted content` as modified by `tighten-spec-contract`
(sequenced before this change). That delta gives the block its `**Implements**:` line and changes
nothing else; this text replaces the persisted `core.hooksPath` sentence with the per-invocation
hooks neutralization and adds the scenarios "A hook path set in the clone after startup runs
nothing" and "The clone's configuration is not written".

### Requirement: Factory git executes no untrusted content
The factory SHALL never check out gnome-branch content into its own filesystem namespace: reading gnome branches happens via bare git object access, and materialization of untrusted content happens only inside task environments. Every factory git invocation SHALL carry the invocation policy's hooks neutralization, so no hook runs for any factory git operation whatever the clone's own configuration names; the factory SHALL persist no setting into a clone to achieve this.
**Implements**: FR17 of add-sandbox-core; FR1, FR2, FR6, NFR-S1 of own-git-invocation-policy

#### Scenario: Reading a gnome branch runs nothing
- **WHEN** the factory reads state files or artifacts from a gnome branch
- **THEN** it reads git objects directly, no checkout occurs in factory-owned paths, and no hook executes

#### Scenario: A hook path set in the clone after startup runs nothing
- **WHEN** the clone's local configuration points `core.hooksPath` at a directory holding executable hooks — set by the operator's tooling or by anyone else, before or after the factory started — and the factory commits, updates a ref, or adds a worktree in that clone
- **THEN** no hook in that directory runs

#### Scenario: The clone's configuration is not written
- **WHEN** the factory claims, resumes, or runs a task in a clone
- **THEN** the clone's `.git/config` is byte-identical before and after

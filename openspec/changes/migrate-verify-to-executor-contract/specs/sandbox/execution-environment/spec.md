## MODIFIED Requirements

### Requirement: Host adapter mechanics
A host adapter SHALL implement the port over worktree and `ProcessBuilder` mechanics: materialize = git worktree of the factory clone, exec = local subprocess with the working copy as working directory, harvest = no-op (the branch is already in the factory clone), scratch = a factory-private temp directory outside the worktree, decision file = a factory-private per-round temp file rather than an in-branch decision file.
**Implements**: FR2 of add-sandbox-core; FR19 of migrate-verify-to-executor-contract
**Notes**: The two decision-file media are a declared manual-sync pair (`DecisionFileTransport` in host mode, `BranchDecisionFile` in sandboxed modes): they share the variable name, the size cap and the tolerant read, and each owns its own staleness rule.
**Notes**: The snapshot of a completed git-mode host round is design D22 of migrate-verify-to-executor-contract; the commit shape of each round is the `git/git-task-persistence` capability's "Host round atomicity".

#### Scenario: Host adapter keeps worktree mechanics
- **WHEN** a pipeline runs through the host adapter
- **THEN** the working copy is a git worktree of the factory clone, processes run as local subprocesses, and harvest performs no fetch; a round with no snapshot closes with the single round commit, and a completed git-mode round closes with its snapshot commit and exactly one round commit above it, the round's pending commits, if any, between them

#### Scenario: Host passport declares no isolation
- **WHEN** the host adapter's capability passport is read
- **THEN** it declares no isolation, stating that the env allowlist bounds environment variables only, not filesystem access

#### Scenario: Mode-independent discipline applies in host mode
- **WHEN** a pipeline runs through the host adapter
- **THEN** the layered env allowlist, stdin prompts, law binding, the findings funnel, the pin-check and factory git hardening apply as in sandboxed modes; only the isolation mechanics and the decision file's medium differ — the gnome's decision request travels through a factory-private per-round temp file, not an in-branch decision file

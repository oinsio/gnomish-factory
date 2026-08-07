# manual-run (delta)

## MODIFIED Requirements

### Requirement: Run modes
`gnomish run` SHALL accept `--mode git|in-place`, default `git`. Git mode: the factory creates the task branch and the task working copy through the bound task environment, closes rounds per the bound adapter's round protocol, and pushes; the branch name is printed upfront — together with the worktree path in host mode, or the task-environment identifier in sandboxed mode, whose working-copy location is a private adapter detail and is never printed as a host path. In-place mode: the preserved legacy behavior — no git, in-memory state, no resume — with an honest reminder at start that exiting kills the task. Git-only flags (`--base`, `--resume`, `--discard-work`) combined with `--mode in-place` SHALL be a usage error (exit code 2).
<!-- implements FR7, UX1, UX4 of add-git-workflow -->
<!-- implements FR1, FR21 of add-sandbox-core -->

#### Scenario: Git mode is the default
- **WHEN** `gnomish run --task="t"` runs without `--mode`
- **THEN** the run operates in git mode and prints the task branch before the first stage — with the worktree path when the binding is host

#### Scenario: Sandboxed run prints the environment, not a host path
- **WHEN** a git-mode run starts with a container binding
- **THEN** the upfront output names the task branch and the task environment, and no host filesystem path of the working copy is printed

#### Scenario: Git flag rejected in in-place mode
- **WHEN** `gnomish run --mode=in-place --resume T-1` is invoked
- **THEN** the process exits with code 2 naming the incompatible flags

#### Scenario: In-place reminder
- **WHEN** an in-place run starts
- **THEN** the dialog states that state is in memory only and the task dies with the process

### Requirement: Read-only workspace with a definition snapshot
In git mode the runner SHALL NOT mutate the `--dir` clone: all work — gnome changes and `.gnomish-task/` state — happens in the task working copy owned by the bound task environment. Findings files SHALL live in the environment's scratch area — outside the working copy in every binding; host mode: a factory-private directory outside the worktree, as today; sandboxed mode: inside the task environment, never in factory-owned filesystem. Decision requests live in-branch under `.gnomish-task/decisions/` in git modes (FR23); only the in-place mode keeps the temp-file transport. In in-place mode the runner process SHALL write nothing inside the workspace: findings temp files and logs live outside it; the workspace changes only through the operator and the manifest's own commands, and the runner SHALL NOT require or inspect git. In both modes the pipeline definition SHALL be loaded once at startup; mid-dialog edits of `.gnomish/` take effect on the next invocation.
<!-- implements FR1, NFR-S1 of add-manual-run -->
<!-- implements FR10, NFR-S1, NFR-S2 of add-agent-executor -->
<!-- implements FR7, NFR-S2 of add-git-workflow -->
<!-- implements FR1, FR4 of add-sandbox-core -->

#### Scenario: No runner artifacts in the workspace
- **WHEN** an in-place run completes after executing command checks with findings files
- **THEN** every file the runner itself created resides outside the workspace

#### Scenario: Clone untouched in git mode
- **WHEN** a git-mode run executes stages and commits rounds
- **THEN** the clone's working copy, index, and current branch are exactly as before the run

#### Scenario: Sandboxed runner files stay in the environment
- **WHEN** a command check with findings runs in container mode
- **THEN** every runner-created temp file lives inside the task environment and none appears in factory-owned filesystem

### Requirement: files_exist builtin runner
The `files_exist` runner SHALL check existence of the literal workspace-relative paths in its `files` param, producing one finding (message + path as location) per missing path. In sandboxed mode existence SHALL be evaluated against the harvested attempt commit via bare git object reads in the factory clone — no environment access; in host modes it SHALL check the workspace filesystem as today. Malformed params or a path resolving outside the workspace SHALL yield `CannotVerify`.
<!-- implements FR6 of add-manual-run -->
<!-- implements FR21 of add-sandbox-core -->

#### Scenario: Missing files enumerated
- **WHEN** two of three configured paths do not exist
- **THEN** the verdict is Fail with exactly two findings naming the missing paths

#### Scenario: Sandboxed check reads the commit, not the box
- **WHEN** `files_exist` runs in sandboxed mode
- **THEN** existence is answered from the attempt commit's tree in the factory clone, and content present only as uncommitted box residue does not count

#### Scenario: Workspace escape refused
- **WHEN** a configured path resolves outside the workspace root
- **THEN** the verdict is CannotVerify naming the offending path

### Requirement: Command check runner
The command runner SHALL execute the manifest command via `sh -c` through `exec()` of the bound task environment, with the working copy as working directory, merging stderr into stdout and retaining a bounded output tail (~200 lines / 10 KB). The child environment SHALL be the layered allowlist of the execution-environment capability (adapter base set + operator passthrough + factory-set variables) — no factory-process variable is inherited implicitly, so tracker credentials cannot reach a check by construction. Exit 0 → Pass; exit 126/127 → CannotVerify (shell convention for not-executable / not-found); any other non-zero exit → Fail.
<!-- implements FR7 of add-manual-run -->
<!-- implements FR11, NFR-S1 of add-claim-heartbeat -->
<!-- implements FR4, FR9 of add-sandbox-core -->

#### Scenario: Red check carries feedback
- **WHEN** the command exits 1 without a findings file
- **THEN** the verdict is Fail with one synthetic finding whose details contain the output tail

#### Scenario: Missing binary is infrastructure
- **WHEN** the command exits 127
- **THEN** the verdict is CannotVerify, honoring the engine's classification table

#### Scenario: No factory variable leaks into a check
- **WHEN** a command check runs while `GNOMISH_GITHUB_TOKEN` and unrelated host variables are set in the factory environment
- **THEN** the check's process environment contains only the allowlisted variables

### Requirement: Findings-JSON wire format
Before starting the command, the runner SHALL allocate a findings-file path in the environment's scratch area — outside the working copy but inside the bound task environment — and pass it as `GNOMISH_FINDINGS_FILE`; after process exit the file SHALL be read back through the environment. After a non-zero exit (other than 126/127), a valid `{"findings":[{message, location?, details?}]}` file SHALL replace the synthetic finding; a malformed file SHALL degrade to the synthetic finding plus a logged warning — the exit-code verdict always stands; a findings file on exit 0 SHALL be ignored with a warning.
<!-- implements FR8, NFR-R2 of add-manual-run -->
<!-- implements FR1, FR4 of add-sandbox-core -->

#### Scenario: Structured findings win
- **WHEN** the command exits 1 and wrote two valid findings
- **THEN** the verdict is Fail with exactly those two findings

#### Scenario: Broken reporter cannot mask a red check
- **WHEN** the command exits 1 and the findings file is unparseable
- **THEN** the verdict is Fail (not CannotVerify) with the synthetic tail finding

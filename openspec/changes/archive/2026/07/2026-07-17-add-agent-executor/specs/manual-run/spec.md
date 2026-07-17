# Delta spec: manual-run

Wiring becomes manifest-driven: real CLI adapters are the default mechanism, interactive adapters an explicit override; `api` stages fail fast at startup. Two add-manual-run requirements are reworded to stop presuming an all-interactive session: the invocation is a console session (not "one interactive dialog"), and the workspace-mutation list admits the stage executor's agent process.

## ADDED Requirements

### Requirement: Manifest-driven mechanism with interactive override
By default `gnomish run` SHALL bind mechanisms from the manifest: `agent-cli` stages to the CLI stage executor and judge checks to the CLI judge voter (models and settings from the manifest); external checks remain interactive regardless of flags. A pipeline containing an `api` stage SHALL fail fast in the startup validation chain — pipeline-load exit code, before any dialog, naming the unsupported stage. `--interactive` SHALL restore the interactive executor and judge entirely; `--interactive=executor` and `--interactive=judge` SHALL swap only that role. No confirmation gate precedes the first paid round: the manifest-driven run is the tool's purpose and the operator is present.
<!-- implements FR10, UX2, D6 of add-agent-executor -->

#### Scenario: Flagless run uses real adapters
- **WHEN** `gnomish run` executes an `agent-cli` manifest with a judge check and no flags
- **THEN** the stage round and the judge vote both run through the CLI adapters with the manifest's pinned models

#### Scenario: Api stage rejected before any dialog
- **WHEN** the loaded pipeline contains a stage with `executor.type: api`
- **THEN** the process exits with the pipeline-load exit code naming the stage, without prompting

#### Scenario: Mixed run for judge calibration
- **WHEN** `gnomish run --interactive=judge` executes an `agent-cli` manifest
- **THEN** stage rounds run through the CLI executor while judge votes are prompted interactively

#### Scenario: Full interactive override
- **WHEN** `gnomish run --interactive` executes the same manifest
- **THEN** executor and judge behave exactly as specified by the interactive-adapter requirements

### Requirement: Agent-raised decisions reach the operator unchanged
A `DecisionNeeded` raised by the CLI executor through the decision-file protocol SHALL surface as the standard escalation dialog — question and options rendered like any engine escalation, the operator's answer recorded as a decision and fed back on resume.
<!-- implements FR3, UX3, D1 of add-agent-executor -->

#### Scenario: Agent question round-trips
- **WHEN** the agent writes a decision file with a question and two options and the operator answers with one of them
- **THEN** the resumed run's executor prompt contains that decision verbatim

## MODIFIED Requirements

### Requirement: Single-task dialog invocation
`gnomish run` SHALL run exactly one ad-hoc task as one console session per process: `--project` (default: current directory) names the workspace, exactly one of `--task` | `--task-file` supplies the description, `--task-id` (optional, filesystem/git-ref-safe charset) overrides the generated `manual-<yyyyMMdd-HHmmss>-<2 chars>` id, `--from-stage` (optional) starts at a named stage. Dialogs occur where a human holds a role: escalations and checkpoints always; executor, judge, and external-check prompts per the wiring in effect. Validation SHALL run in fixed order: arguments, then pipeline load, then the run. Task title = first non-empty description line (markdown heading markers stripped), body = remainder; decisions start empty.
<!-- implements FR10 of add-agent-executor -->

#### Scenario: Dry-run from the project directory
- **WHEN** `gnomish run --task="fix the flaky spec"` is invoked in a directory with a valid `.gnomish/`
- **THEN** the run starts at the first stage with a generated task id and title "fix the flaky spec"

#### Scenario: Unknown stage is a usage error
- **WHEN** `--from-stage=missing` names a stage absent from the loaded definition
- **THEN** the process exits with the usage exit code and the message lists the known stage names

#### Scenario: Broken pipeline reported before any dialog
- **WHEN** `.gnomish/` fails to load
- **THEN** the loader errors are printed as-is and the process exits with the pipeline-load exit code without prompting

### Requirement: Read-only workspace with a definition snapshot
The runner process SHALL write nothing inside the workspace: findings and decision temp files and logs live outside it; the workspace changes only through the operator, the manifest's own commands, and the stage executor's agent process (a judge round is read-only by adapter policy). The pipeline definition SHALL be loaded once at startup; mid-dialog edits of `.gnomish/` take effect on the next invocation. The runner SHALL NOT require or inspect git.
<!-- implements FR10, NFR-S1, NFR-S2 of add-agent-executor -->

#### Scenario: No runner artifacts in the workspace
- **WHEN** a run completes after executing command checks with findings files
- **THEN** every file the runner itself created resides outside the workspace

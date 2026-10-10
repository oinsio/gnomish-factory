# Spec Delta

## MODIFIED Requirements

### Requirement: Pure orchestration run
The engine SHALL run one task from its recorded state to a terminal outcome via `run(definition, context, state, workspace, ports)`, touching no tracker, filesystem, or git — the engine executes nothing itself; all effects happen behind the injected `EnginePorts` members: the seven behavioral ports (`StageExecutor`, `BuiltinCheckRunner`, `CommandCheckRunner`, `ExternalCheckClient`, `JudgeVoter`, `EngineEventListener`, `AttemptPersistence`) plus the one environment port: the time equipment (`TimeEquipment`), carrying the JDK instant source (`java.time.InstantSource`, the one type for the current instant across the codebase) and the `Sleeper` as one value.
<!-- implements FR1, NFR-S1 of add-stage-engine -->
<!-- implements FR15 of add-manual-run -->
<!-- implements FR17 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Reference run with fakes only
- **WHEN** a pipeline exercising all four check types runs with fake ports, including a quality-failure retry, a decision escalation with a decision-carrying re-run, and a manual pause
- **THEN** the run reaches the expected terminal outcomes with no I/O outside the ports

#### Scenario: The engine's time source is the shared one
- **WHEN** the engine is assembled with the process's instant source
- **THEN** every timestamp and deadline the engine computes reads that same source, and no project-owned clock type stands between the engine and it

### Requirement: Two-level attempt telemetry
Every executed round SHALL be recorded in `TaskState.attempts` with an explicit result classification (Passed, QualityFailure, CannotVerify, DecisionNeeded), a `startedAt` timestamp read from the engine's instant source when the round begins, and optional metrics — wall time, per-tool aggregates (name, call count, total duration), and per-model token maps keyed by resolved model id, each entry carrying input, output, cache-creation, and cache-read counts (judge tokens as per-model maps per vote) — including rounds ending in CannotVerify, which are recorded but not counted. An empty token map means unreported, preserving the unknown ≠ zero distinction; merging token maps SHALL union keys and sum the four counts per key; any display total is derived, never stored alongside the map. `TaskState` SHALL carry cumulative usage totals for the whole task, updated on every recorded round and preserved across stage advancement and resume. The raw chronological ToolTrace SHALL stay out of TaskState, correlated by the (taskId, stage, attempt) key. The history SHALL cover only the current stage, resetting on advancement.
<!-- implements FR13, FR14, NFR-C1 of add-stage-engine -->
<!-- implements FR15 of add-manual-run -->
<!-- implements FR5, FR9, NFR-C1 of add-agent-executor -->
<!-- implements FR17 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Unburned round is still recorded
- **WHEN** a round's verification ends in CannotVerify
- **THEN** the round appears in `attempts` with its token usage while `attemptsUsed` is unchanged

#### Scenario: History resets on advancement
- **WHEN** a stage passes and the pipeline advances
- **THEN** the new state's attempt history is empty and the previous rounds were persisted

#### Scenario: Round result is explicit
- **WHEN** a round ends in DecisionNeeded before any check runs
- **THEN** its record carries the DecisionNeeded result rather than leaving consumers to infer it from an empty check list

#### Scenario: Round start is timestamped
- **WHEN** the engine records any round — passed, failed, unverifiable, or decision-needed
- **THEN** the record carries `startedAt` equal to the instant-source reading taken when the round began

#### Scenario: Cumulative totals survive advancement
- **WHEN** a stage passes and the pipeline advances
- **THEN** the new state's attempt history is empty while the task totals still include the passed stage's usage

#### Scenario: Per-model grain survives merging
- **WHEN** two rounds report usage for overlapping model ids and the task totals fold them in
- **THEN** the totals carry the union of model ids with the four token counts summed per model

#### Scenario: Unreported tokens stay distinguishable from zero
- **WHEN** a round reports an empty token map
- **THEN** the cumulative totals are unchanged by that round rather than gaining zero-valued entries

# command-executor

## Purpose

Run a stage whose Mechanism is a declared shell command: execution through the task
environment's sole process seam, the exit-code contract, timeout, allowlisted
environment, bounded sanitized output, and zero-token telemetry — no agent process and
no decision protocol.

## ADDED Requirements

### Requirement: Command rounds run through the task environment
A `command` stage's round SHALL execute the manifest-declared command via `exec()` of
the bound task execution environment — the sole process seam — as `sh -c <command>`
over the task working copy. The observed protocol (exit code, captured output) SHALL be
identical in host and container modes, with no mode-specific executor implementation.
The command's working-copy modifications are the stage product: round boundary checks,
attempt persistence, and harvest SHALL apply to command rounds unchanged.
<!-- implements FR4, FR9, G2 of add-command-executor -->

#### Scenario: Container round speaks the same protocol
- **WHEN** the same command stage executes once in host mode and once in container mode
- **THEN** the executor observes the exit code and output through the environment
  identically, and the round classification is the same in both modes

#### Scenario: Command effects are the round product
- **WHEN** a command modifies files in the working copy and exits 0
- **THEN** those modifications survive to harvest and the attempt commit exactly as an
  agent round's edits would

### Requirement: Command child environment is allowlisted and decision-free
The environment passed to the command SHALL be composed exclusively by the layered
child-env allowlist of the execution-environment capability — base set, operator
passthrough by exact name, factory-set variables; nothing inherited implicitly, and
variables declared as credentials refused by construction. The command round SHALL NOT
set `GNOMISH_DECISION_FILE` and the executor SHALL never return `DecisionNeeded` — the
decision protocol is an agent concept.
<!-- implements FR4, NFR-S1, NG1 of add-command-executor -->

#### Scenario: Tracker token is absent from the command
- **WHEN** a command round starts while the factory holds the tracker token in its own
  environment
- **THEN** the command's environment contains no tracker token and no variable outside
  the allowlist

#### Scenario: A stray decision file changes nothing
- **WHEN** a command writes a decision-request file into the working copy and exits 0
- **THEN** the round completes as `Completed` — classification follows the exit code
  only, and no decision escalation occurs

### Requirement: Exit-code contract
The command executor SHALL classify the finished process by exit code: `0` SHALL
complete the round (`Completed` — the stage's verify chain then runs as usual); any
other exit code from a started, finished process SHALL be an executor-reported quality
failure carrying exactly one finding that names the exit code, with the bounded tail of
the command's captured output — stripped of control sequences by the findings
sanitizer — as its details. A command that cannot start (spawn failure) or whose exit
code signals an unrunnable command (`126` not executable, `127` not found) SHALL be an
infrastructure failure of the round — no attempt burned, escalated as "cannot execute".
<!-- implements FR5, FR6, FR7, NFR-O1, UX1 of add-command-executor -->

#### Scenario: Exit zero completes the round
- **WHEN** the command exits with status 0
- **THEN** the executor returns `Completed` and verification proceeds in manifest order

#### Scenario: Non-zero exit is a quality failure with the output tail
- **WHEN** the command exits with status 1 after printing build errors
- **THEN** the round is a quality failure whose single finding names exit code 1 and
  carries the bounded tail of the output as details

#### Scenario: Captured output is sanitized
- **WHEN** the failing command's output contains ANSI color sequences
- **THEN** the finding's details contain the text with control sequences stripped and
  the tail bounded, never raw terminal noise

#### Scenario: Unrunnable command is infrastructure
- **WHEN** the declared command exits 127 (not found) or cannot be spawned at all
- **THEN** the round is an infrastructure failure, no attempt is burned, and the task
  escalates as "cannot execute" naming the command

### Requirement: Round timeout kills the process tree as infrastructure
The command round SHALL be bounded by the resolved `roundTimeout` — same accepted
shapes (number of seconds, ISO-8601 duration string) and same 30-minute default as
agent-cli rounds. Expiry SHALL kill the whole process tree and classify the round as an
infrastructure failure — no verdict exists, no attempt burned.
<!-- implements FR3, FR7 of add-command-executor -->

#### Scenario: Hung command cannot hang the engine
- **WHEN** the command outlives the resolved `roundTimeout`
- **THEN** its process tree is killed and the round escalates as infrastructure with
  `attemptsUsed` unchanged

#### Scenario: Default timeout applies
- **WHEN** a command stage declares no `roundTimeout`
- **THEN** the round is bounded by the 30-minute default

### Requirement: Command settings with strict validation
The `settings` map of a `command` executor SHALL accept exactly `roundTimeout`; any
unknown key SHALL be a located error raised at load, before any stage runs, naming the
stage manifest and the offending key. A malformed `roundTimeout` (neither a number of
seconds nor an ISO-8601-parseable string) SHALL be a located error under the same
contract as the agent-cli shapes.
<!-- implements FR3, UX2 of add-command-executor -->

#### Scenario: Unknown settings key fails at load
- **WHEN** a command stage's settings contain `allowedTools`
- **THEN** loading reports a located error naming the stage manifest and the key

#### Scenario: Well-formed roundTimeout is accepted
- **WHEN** a command stage declares `roundTimeout: 600` or `roundTimeout: "PT10M"`
- **THEN** validation passes and the round uses the declared bound

### Requirement: Zero-token telemetry
A command round SHALL report no token usage — an empty per-model map (unreported, never
fabricated zeros) and an empty tool trace — while its wall time SHALL be measured and
recorded like any round's. Cumulative task usage totals SHALL be unchanged by command
rounds.
<!-- implements NFR-C1 of add-command-executor -->

#### Scenario: Command round spends no tokens
- **WHEN** a command round completes and is recorded
- **THEN** its record carries an empty token map and the task's cumulative usage totals
  are unchanged, while the round's wall time is recorded

### Requirement: Per-stage executor dispatch by declared type
The executor serving a stage SHALL be selected by the stage's declared executor type:
`command` stages run the command executor, `agent-cli` stages keep today's executor
selection unchanged. Interactive-mode executor substitution SHALL apply to agent-typed
stages only — a `command` stage always runs its declared command for real, in every run
mode.
<!-- implements FR8 of add-command-executor -->

#### Scenario: Mixed pipeline dispatches per stage
- **WHEN** a pipeline interleaves an `agent-cli` stage and a `command` stage
- **THEN** each stage's round runs through the executor matching its declared type

#### Scenario: Interactive mode does not simulate commands
- **WHEN** a run starts with interactive executor substitution enabled
- **THEN** agent-typed stages are substituted as today while the `command` stage still
  executes its declared command

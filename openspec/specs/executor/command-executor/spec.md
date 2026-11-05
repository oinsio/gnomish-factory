# executor/command-executor Specification

## Purpose

Run a stage whose Mechanism is a declared shell command: execution through the task
environment's sole process seam, the exit-code contract, timeout, allowlisted
environment, bounded sanitized output, and zero-token telemetry — no agent process and
no decision protocol.

## Requirements

### Requirement: Command rounds run through the task environment
A `command` stage's round SHALL execute the manifest-declared command via `exec()` of
the bound task execution environment — the sole process seam — as `sh -c <command>`
over the task working copy. The observed protocol (exit code, captured output) SHALL be
identical in host and container modes, with no mode-specific executor implementation.
The command's working-copy modifications SHALL be the stage product, under the round
boundary checks, attempt persistence and harvest unchanged.
**Implements**: FR4, FR9, NFR-R2, G2 of add-command-executor

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
**Implements**: FR4, NFR-S1, NG1 of add-command-executor

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
The command executor SHALL report the finished process through the executor contract:
exit `0` is `completed` (the stage's verify chain then runs as usual); any exit code other
than `0`, `126` and `127` from a started, finished process is `failed/quality`; `126` and
`127` are `failed/infrastructure`. The executor SHALL decide no class itself beyond this
status.
**Implements**: FR5, FR6, FR7 of add-command-executor

#### Scenario: Exit zero completes the round
- **WHEN** the command exits with status 0
- **THEN** the executor returns `completed` and verification proceeds in manifest order

#### Scenario: Non-zero exit is a quality failure with the output tail
- **WHEN** the command exits with status 1 after printing build errors
- **THEN** the round is a quality failure whose single finding names exit code 1 and
  carries the bounded tail of the output

#### Scenario: Unrunnable command is infrastructure
- **WHEN** the declared command exits 127 (not found) or cannot be spawned at all
- **THEN** the round is an infrastructure failure, no attempt is burned, and the task
  escalates as "cannot execute" naming the command

#### Scenario: Non-executable command is infrastructure
- **WHEN** the declared command exits 126 (not executable)
- **THEN** the round is an infrastructure failure, no attempt is burned, and the task
  escalates as "cannot execute" naming the command

### Requirement: Quality reason is the round's one finding
A `failed/quality` result of the command executor SHALL carry its `reason` as the round's
one finding: a text that names the exit code and carries the bounded, sanitized tail of
the command's captured output. The contract's one status mapping SHALL turn that reason
into the engine's finding exactly as for a program executor, so the next attempt receives
it as prior feedback.
**Implements**: FR6, NFR-R1, NFR-O1, UX1 of add-command-executor

#### Scenario: Flaky command retries with its finding in feedback
- **WHEN** a command stage's command exits 1 on the first round and 0 on the second
- **THEN** the second round's request carries the first round's finding as prior feedback,
  the stage completes with two attempts used, and the verify chain runs after the second
  round only

#### Scenario: Captured output is sanitized
- **WHEN** the failing command's output contains ANSI color sequences
- **THEN** the finding's text contains the output with control sequences stripped and
  the tail bounded, never raw terminal noise

### Requirement: Round timeout kills the process tree as infrastructure
The command round SHALL be bounded by the resolved `roundTimeout` — same accepted
shapes (number of seconds, ISO-8601 duration string) and same 30-minute default as
agent-cli rounds. Expiry SHALL kill the whole process tree and classify the round as an
infrastructure failure — no verdict exists, no attempt burned.
**Implements**: FR3, FR7 of add-command-executor

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
**Implements**: FR3, UX2 of add-command-executor

#### Scenario: Unknown settings key fails at load
- **WHEN** a command stage's settings contain `allowedTools`
- **THEN** loading reports a located error naming the stage manifest and the key

#### Scenario: Well-formed roundTimeout is accepted
- **WHEN** a command stage declares `roundTimeout: 600` or `roundTimeout: "PT10M"`
- **THEN** validation passes and the round uses the declared bound

### Requirement: Zero-token telemetry
A command round SHALL report a known zero of model use — the contract's usage with
provenance `REPORTED`, an empty per-model map and an empty tool trace, never `ABSENT`, which
means the executor could not tell — while its wall time SHALL be measured and recorded like
any round's. Cumulative task usage totals, cost completeness included, SHALL be unchanged by
command rounds.
**Implements**: NFR-C1 of add-command-executor

#### Scenario: Command round spends no tokens
- **WHEN** a command round completes and is recorded
- **THEN** its record carries usage of provenance `REPORTED` with an empty token map and an
  empty tool trace, the task's cumulative usage totals are unchanged with cost completeness
  included, and the round's wall time is recorded

### Requirement: Command executor is a built-in of the executor registry
The command executor SHALL be registered in the executor registry under the built-in name
`command`, which no law declaration may take, SHALL implement the executor contract's interface
(`describe`, `start`, `poll`; a poll answers `failed/infrastructure`, a command round is never
pending), and SHALL pass the contract's conformance kit driven in process.
**Implements**: FR8 of add-command-executor

#### Scenario: Built-in passes the conformance kit
- **WHEN** the conformance kit runs against the `command` built-in in process
- **THEN** every applicable case passes and the wire-only cases are reported not applicable

#### Scenario: Poll is never pending
- **WHEN** the `command` built-in is polled
- **THEN** it answers `failed/infrastructure`, never `pending`

#### Scenario: A law declaration named after the built-in is refused
- **WHEN** the law declares an executor named `command`
- **THEN** the run's registry is not built, and the error names the declaration and asks for a
  rename

### Requirement: Dispatch maps the command stage type to the built-in
The run's dispatch SHALL map the stage type `command` to the built-in's registry name, so a
`command` stage resolves to the built-in through the registry and never reaches the agent
arm; a `command` stage SHALL run its declared command for real in every run mode.
**Implements**: FR8 of add-command-executor

#### Scenario: Mixed pipeline dispatches per stage
- **WHEN** a pipeline interleaves an `agent-cli` stage and a `command` stage
- **THEN** each stage's round runs through the executor the registry resolves for it, and
  the `command` stage's round never reaches the agent arm

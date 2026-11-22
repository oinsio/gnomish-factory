# executor/agent-executor — delta for extract-agent-round-runner

## ADDED Requirements

### Requirement: Agent CLI is a named executor of the contract
The agent CLI SHALL be one executor in the executor registry implementing the contract's
interface in roles `transform`, `verdict`, `decision`, built from the agent round runner and a
dialect; role-specific round classes SHALL NOT exist.
**Implements**: FR1, FR2, G2 of extract-agent-round-runner

#### Scenario: Round through the registry
- **WHEN** a stage names the Claude executor
- **THEN** its round runs through the registry-resolved executor and the one runner

### Requirement: Decision request is the answer file
The gnome's decision request SHALL be written to the request's answer file path and read through
the shared result-file reader as a `decision-request` answer; the decision file SHALL have no
transport of its own. The branch-side reader of a pending request SHALL read the same path.
**Implements**: FR9 of extract-agent-round-runner

#### Scenario: Pending decision survives factory death
- **WHEN** a factory dies after the snapshot commit carrying the answer file
- **THEN** a resuming instance finds the decision request in the branch and escalates it

#### Scenario: Answer file read is capped
- **WHEN** the gnome writes an answer file larger than the result cap
- **THEN** at most the cap is read, truncation is recorded, and the round still resolves as a
  decision request

### Requirement: A decision is rendered with the question it answers
The briefing's decisions section SHALL render a decision that answers a decision request as the
question, its options and the reply, through the one shared renderer, in the stage round prompt
and the judge prompt alike; a decision that answers no request SHALL render as author and body.
**Implements**: FR9, NFR-R2 of extract-agent-round-runner

#### Scenario: Question, options and reply reach the next round
- **WHEN** a task parked on a decision request is answered and the next round starts
- **THEN** the round's prompt carries the question, each option and the reply in its decisions section

#### Scenario: A decision without a question renders as author and body
- **WHEN** the briefing renders a decision that records no question
- **THEN** its line carries the author and the body only

#### Scenario: Any instance starts the round after an answer alike
- **WHEN** a task parked on a decision request is answered and a second instance with a fresh clone, or a fresh box, starts the next round
- **THEN** the round's prompt equals the one the parking instance would have built, and no argv carries a session flag

## REMOVED Requirements

### Requirement: Decision file is read through the shared result reader
**Reason**: Superseded by "Decision request is the answer file": the read through the shared
result reader and its cap are kept there (scenario "Answer file read is capped"), and the runner
returns the contract's status, which the engine classifies; the decision-file path and its
protocol are what this change replaces.
**Migration**: none for manifests; `GNOMISH_DECISION_FILE` is replaced by `GNOMISH_ANSWER_FILE`.

### Requirement: Decision-file protocol
**Reason**: The decision request becomes the contract's answer file read by the single result
reader; naming, fallbacks and survival on the branch are restated in "Decision request is the
answer file".
**Migration**: the path stays under `.gnomish-task/`; `GNOMISH_DECISION_FILE` is replaced by
`GNOMISH_ANSWER_FILE`.

### Requirement: Hard-wired adapter policy
**Reason**: Policy is the contract's abstract policy compiled by the dialect
(`executor/claude-dialect`, "Policy compilation"), with the same hard-wired flags.
**Migration**: none for manifests.

### Requirement: CLI judge vote
**Reason**: A `judge` check is one `verdict` request to the executor its block declares
(`stage-engine`, "A judge check is one verdict request"); it runs in a fresh box of its own
(`verification-hardening`, "Sandboxed judge votes run in a fresh environment").
**Migration**: a `judge` check declares the stage's `executor:` block in place of `model` and
`settings`; the criteria file keeps its meaning.

### Requirement: Manifest settings with strict validation
**Reason**: Settings are validated against the executor's describe schema
(`executor/claude-dialect`, "Claude describe").
**Migration**: the same keys are accepted by the Claude schema.

### Requirement: Live progress listener SPI
**Reason**: Progress events become vendor-neutral (`executor/agent-round-runner`,
"Vendor-neutral progress").
**Migration**: listeners drop the session id and result subtype fields.

### Requirement: Contract compliance via fake agent
**Reason**: The fake agent becomes a fake dialect plus the conformance kit; the
`StageExecutorContract`/`JudgeVoterContract` suites are replaced by the kit cases.
**Migration**: run `gnomish executor verify` against the agent executor with the fake binary.

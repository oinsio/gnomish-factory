# agent-executor — delta for extract-agent-round-runner

## ADDED Requirements

### Requirement: Agent CLI is a named executor of the contract
The agent CLI SHALL be one executor in the executor registry implementing the contract's
interface in roles `transform`, `verdict`, `decision`, built from the agent round runner and a
dialect; role-specific round classes SHALL NOT exist.
<!-- implements FR1, FR2, G2 of extract-agent-round-runner -->

#### Scenario: Round through the registry
- **WHEN** a stage names the Claude executor
- **THEN** its round runs through the registry-resolved executor and the one runner

### Requirement: Decision request is the answer file
The gnome's decision request SHALL be written to the request's answer file path and read through
the shared result-file reader as a `decision-request` answer; the decision file SHALL have no
transport of its own. The branch-side reader of a pending request SHALL read the same path.
<!-- implements FR9 of extract-agent-round-runner -->

#### Scenario: Pending decision survives factory death
- **WHEN** a factory dies after the snapshot commit carrying the answer file
- **THEN** a resuming instance finds the decision request in the branch and escalates it

## REMOVED Requirements

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
**Reason**: Judge votes are `verdict` requests to the named executor (`stage-engine`, "Judge
votes are verdict requests"); the fresh-environment rule is unchanged.
**Migration**: `judge` checks name an executor; criteria and model keep their meaning.

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

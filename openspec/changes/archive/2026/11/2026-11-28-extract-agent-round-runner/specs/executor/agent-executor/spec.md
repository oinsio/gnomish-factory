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
The gnome's decision request SHALL be the `decision-request` answer at the round's answer file
path, read by the one answer read on the live path and at recovery alike; the decision file
SHALL have no transport of its own, and no reader SHALL turn content that is not a valid answer
into a decision request.
**Implements**: FR9, FR21, NFR-R5 of extract-agent-round-runner

#### Scenario: Pending decision survives factory death
- **WHEN** a factory dies after the snapshot commit carrying the answer file
- **THEN** a resuming instance finds the decision request in the branch and escalates it

#### Scenario: Answer file read is capped
- **WHEN** an answer file larger than the result cap is read
- **THEN** at most the cap is read and the answer is refused as over the cap, never read as a
  decision request

#### Scenario: Recovery reads a snapshot's answer as the live round does
- **WHEN** an interrupted verification resumes from a snapshot commit whose answer path holds
  the bytes a live round would have refused
- **THEN** the resumed round has the outcome the live round would have had, and no decision
  request is raised from them

#### Scenario: A salvage commit carries the request
- **WHEN** a round whose answer is a decision request ends in a salvage commit instead of a
  snapshot commit
- **THEN** the request is on the branch under its token, as it would be after a snapshot commit

#### Scenario: A snapshot without a request resumes as Completed
- **WHEN** an interrupted verification resumes from a snapshot commit whose tree holds nothing
  at the round's token path
- **THEN** the resumed round maps to `Completed`

### Requirement: The answer file is named by the round that wrote it
In branch modes the answer file SHALL live at `.gnomish-task/decisions/<stage>-a<attempt>-<token>.json`,
`<token>` being the round token, and a round SHALL read that path only; in the in-place mode it
SHALL be a per-round file outside the workspace, removed with the round.
**Implements**: FR9 of extract-agent-round-runner

#### Scenario: An answered request is not asked again
- **WHEN** a container-mode task parked on a decision request is answered, the stage restarts at
  attempt 0, and the next round's box is cloned from a tip that still carries the earlier file
- **THEN** the next round is given a path under its own token, reads only that path, and maps to
  `Completed` unless a new request is at that path

#### Scenario: Stale request from another round is ignored
- **WHEN** an answer file named for a previous round — another stage, another attempt number,
  or the same stage and attempt number under an earlier round's token — is in the working copy
- **THEN** the current round maps to `Completed` and the stale file changes nothing

#### Scenario: A decision file beside the real one changes nothing
- **WHEN** a file under `.gnomish-task/decisions/` with any name other than the round's own path
  is present, and the round's own path holds nothing
- **THEN** the round maps to `Completed`, whether or not the other file was removed

#### Scenario: The in-place answer file leaves with the round
- **WHEN** a round runs in the git-less in-place mode
- **THEN** its answer path is a per-round file outside the workspace, gone after the round's
  close or discard

### Requirement: The answer instruction is the dialect's
The round prompt of a `transform` request SHALL tell the agent how to ask for a decision in the
words of the dialect's declared answer form: its final structured reply for a schema-enforced
answer, the answer file path for a file answer. No vendor-neutral prompt builder SHALL spell
either.
**Implements**: FR19 of extract-agent-round-runner

#### Scenario: A schema-enforced dialect is not told to write a file
- **WHEN** the prompt of a `transform` round of a dialect declaring a schema-enforced answer is
  built
- **THEN** it tells the agent to ask through its final reply and names no answer file path

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

## MODIFIED Requirements

### Requirement: Round prompt composition
The round prompt SHALL be built from the shared briefing sections (task goal, input artifacts, prior-attempt feedback, decisions, control-file content) followed by the executor epilogue: the stage's full verify plan including judge acceptance-criteria content, the dialect's answer instruction, and — on attempts after the first — a rework preamble stating that the working copy already contains the prior attempt's result and must be reworked, not restarted.
**Implements**: FR2, D8, D9 of add-agent-executor; FR19 of extract-agent-round-runner

#### Scenario: Verify plan is visible to the agent
- **WHEN** a stage with a command check and a judge check executes
- **THEN** the prompt lists both checks, including the judge's acceptance criteria text

#### Scenario: Rework preamble on retry only
- **WHEN** attempt 1 and attempt 2 prompts of the same stage are compared
- **THEN** only the attempt 2 prompt contains the rework preamble

## REMOVED Requirements

### Requirement: Decision file is read through the shared result reader
**Reason**: Superseded by "Decision request is the answer file". The fate of each scenario:
- "Decision file read is capped" — replaced by "Answer file read is capped" there, with a
  different outcome: an over-cap answer is refused (design D15), where the old read kept the cut
  text as a question.
- "Adapter constructs no failure class" — replaced by `executor/agent-round-runner`, "An invalid
  transform answer is a quality failure", scenario "A round with no result event stays an
  infrastructure failure": a kill on the round's bound is still reported with the contract's
  status and classified by the engine; the runner now names `quality` for one case, an invalid
  answer (design D15).
**Migration**: none for manifests; `GNOMISH_DECISION_FILE` is replaced by `GNOMISH_ANSWER_FILE`.

### Requirement: Decision-file protocol
**Reason**: The decision request becomes the contract's answer file read by the one answer read.
The fate of each scenario:
- "Agent asks for a decision" — moved to `executor/agent-round-runner`, "Answers are obtained as
  the dialect declares", scenario "A schema-enforced decision request reaches the answer file".
- "No signal means Completed" — moved there, scenario "A round with no answer completes".
- "Pending decision survives factory death", "A salvage commit carries the request", "A snapshot
  without a request resumes as Completed" — moved, titles kept, to "Decision request is the
  answer file".
- "An answered request is not asked again", "Stale request from another round is ignored", "A
  decision file beside the real one changes nothing" — moved, titles kept, to "The answer file
  is named by the round that wrote it".
- "In-place mode uses a temp file outside the workspace" — moved there as "The in-place answer
  file leaves with the round".
- "A decision round keeps its telemetry" — moved to `executor/agent-round-runner`, "Answers are
  obtained as the dialect declares", scenario "A decision request keeps the round's usage".
- "Garbage decision file is not lost", "A garbage request in a snapshot is re-raised as raw
  text", "Empty decision file asks a fallback question" — dropped by design D15: content that is
  not a valid answer is the round's quality failure, live and at recovery
  (`executor/agent-round-runner`, "An invalid transform answer is a quality failure"); the
  raw-text question and the stand-in question no longer exist.
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

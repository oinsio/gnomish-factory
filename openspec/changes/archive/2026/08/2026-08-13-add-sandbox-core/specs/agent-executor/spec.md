# agent-executor (delta)

## MODIFIED Requirements

### Requirement: Fresh CLI round per attempt
The CLI `StageExecutor` adapter SHALL run one `execute()` call as one fresh CLI process spawned via `exec()` of the bound task environment over the task working copy, with stream-json output, never reusing a CLI session between attempts; work carries across retries through the task working copy, and prior findings reach the agent only through the rendered `Request.feedback`.
<!-- implements FR1, D2 of add-agent-executor -->
<!-- implements FR4 of add-sandbox-core -->

#### Scenario: Retry is a fresh process
- **WHEN** attempt 2 of a stage executes after a quality failure
- **THEN** a new CLI process starts via the task environment with no session-resume flag and its prompt contains the rendered findings of attempt 1

### Requirement: Decision-file protocol
In git modes the decision request SHALL live in the working copy at `.gnomish-task/decisions/<stage>-a<attempt>.json` — the single gnome-writable path under `.gnomish-task/`. The adapter SHALL pass that path via `$GNOMISH_DECISION_FILE`, read it through the environment after process exit, and map: file matching the current stage and attempt present → `DecisionNeeded`, absent → `Completed`; files not matching the current stage and attempt SHALL be ignored. The request rides the snapshot (or salvage) commit, so a pending escalation survives factory death and is resumable by any instance from the branch alone. Reading SHALL be tolerant: invalid JSON → the entire file content becomes the question with empty options; empty file → a fallback question text; the raw content SHALL be logged at WARN on any parse trouble. A `DecisionNeeded` result SHALL carry the same telemetry (usage and trace) as `Completed`. The git-less in-place mode SHALL keep the per-round temp-file transport outside the workspace.
<!-- implements FR3, NFR-R3, NFR-O2, D1 of add-agent-executor -->
<!-- implements FR23 of add-sandbox-core -->

#### Scenario: Agent asks for a decision
- **WHEN** the agent writes `{"question": "Refactor or patch?", "options": ["refactor", "patch"]}` to the decision file for the current stage and attempt and exits
- **THEN** the adapter returns `DecisionNeeded` with that question and both options

#### Scenario: No signal means Completed
- **WHEN** the process exits without creating a decision file for the current stage and attempt
- **THEN** the adapter returns `Completed` and verification proceeds

#### Scenario: Garbage decision file is not lost
- **WHEN** the decision file contains unparseable text
- **THEN** the adapter returns `DecisionNeeded` with the raw text as the question and logs the content at WARN

#### Scenario: Pending decision survives factory death
- **WHEN** a factory dies after the snapshot commit carrying a decision request but before the escalation is recorded
- **THEN** a resuming instance finds the request in the branch and escalates with that question, without replaying the round

#### Scenario: Stale request from another round is ignored
- **WHEN** a decision file named for a previous stage or attempt is still present in the working copy
- **THEN** the current round maps to `Completed` and the stale file changes nothing

### Requirement: CLI judge vote
The CLI `JudgeVoter` adapter SHALL run one `vote()` as one CLI round via `exec()` of a task environment with the check's pinned model — in sandboxed mode a fresh environment materialized from the attempt commit, never the gnome-touched round environment (votes of one attempt may share it); in host mode the stage workspace, as today. The prompt SHALL contain the acceptance-criteria content read from the pipeline law bound at invocation start — never from the gnome-writable working copy — the task context (goal and human decisions), and a structured-verdict instruction — and SHALL NOT contain prior-attempt feedback. The verdict SHALL be extracted tolerantly from the final message (fences stripped, first JSON object taken); when no verdict is obtainable — unparseable message, dead process, missing result event — the vote SHALL be `CannotVerify`, never a silent pass, with the raw final message logged at WARN.
<!-- implements FR8, NFR-R1, NFR-O2, D5, D8 of add-agent-executor -->
<!-- implements FR4, FR15, FR19 of add-sandbox-core -->

#### Scenario: Fenced verdict accepted
- **WHEN** the judge's final message wraps `{"passed": false, "findings": [...]}` in a markdown code fence
- **THEN** the vote is Fail with the parsed findings

#### Scenario: No verdict is never a pass
- **WHEN** the judge process exits without a parseable verdict in its final message
- **THEN** the vote is `CannotVerify` and the raw message is logged at WARN

#### Scenario: Feedback is withheld from the judge
- **WHEN** a judge vote runs on attempt 3 after two quality failures
- **THEN** its prompt contains no prior-attempt findings

### Requirement: Round timeout and control-file preflight
`roundTimeout` expiry SHALL kill the CLI process and classify the round as an infrastructure failure (no verdict exists, no attempt burned). The adapter SHALL obtain the stage's control file from the pipeline law bound at invocation start — never from the gnome-writable working copy at use time; an unreadable control file SHALL be an infrastructure failure before any process starts (a "cannot execute" escalation), never a silently control-less prompt. The same preflight SHALL cover the judge acceptance-criteria files embedded into the round prompt's verify plan. The judge adapter SHALL apply the same preflight to the check's acceptance-criteria file, read from the same law source: an unreadable criteria file SHALL yield `CannotVerify` before any process starts, never a criteria-less vote.
<!-- implements FR13, NFR-R1, D8 of add-agent-executor -->
<!-- implements FR19, NFR-S2 of add-sandbox-core -->

#### Scenario: Hung CLI cannot hang the engine
- **WHEN** the process outlives `roundTimeout`
- **THEN** it is killed and the round escalates as infrastructure, with `attemptsUsed` unchanged

#### Scenario: Unreadable control file stops before spawn
- **WHEN** the stage's instructions file cannot be read from the law source
- **THEN** no process starts and the failure is infrastructure, not a quality failure

#### Scenario: Unreadable criteria stop the vote before spawn
- **WHEN** a judge check's acceptance-criteria file cannot be read from the law source
- **THEN** no process starts and the vote is `CannotVerify`

### Requirement: Manifest settings with strict validation
The manifest `settings` map of an `agent-cli` executor and of a judge check SHALL accept exactly `allowedTools`, `disallowedTools`, `maxTurns`, and `roundTimeout`; any unknown key SHALL be a startup error raised before any dialog, naming the stage/check and the offending key. Installation-level configuration — the CLI binary path (default: `claude` from PATH) and the child-environment allowlist — SHALL live in application properties, never in the manifest. The child environment SHALL follow the layered allowlist of the execution-environment capability — adapter base set, operator passthrough by exact name, factory-set variables; no variable is inherited implicitly. Variables declared as credentials by the active tracker adapter SHALL be refused in the passthrough as a startup error, so tracker credentials can never reach the gnome by construction.
<!-- implements FR11, UX2, D7 of add-agent-executor -->
<!-- implements NFR-S1 of add-tracker-port -->
<!-- implements FR9 of add-sandbox-core -->

#### Scenario: Typo fails fast
- **WHEN** a stage's settings contain `allowedTols`
- **THEN** startup fails before any dialog with a message naming the stage and the unknown key

#### Scenario: Binary path is installation config
- **WHEN** application properties point the CLI binary at a fake agent script
- **THEN** rounds execute that binary with no manifest change

#### Scenario: Credential variable cannot be allowlisted
- **WHEN** application properties allowlist a variable the active tracker adapter declares as a credential
- **THEN** startup fails before any dialog with an error naming the variable

## ADDED Requirements

### Requirement: CLI processes run through the task environment
Agent-CLI rounds and CLI judge votes SHALL be spawned via `exec()` of the bound `TaskExecutionEnvironment`, never as direct host subprocesses. The stream-json contract, decision-file semantics, timeouts, and telemetry SHALL be unchanged — the adapter consumes the same stdout stream and exit code regardless of where the process runs.
<!-- implements FR4 of add-sandbox-core -->

#### Scenario: Round in a container speaks the same protocol
- **WHEN** an agent round executes in container mode
- **THEN** stream-json events, the decision file, and the exit code are observed by the executor adapter exactly as in host mode

### Requirement: CLI child environment is allowlisted
The environment passed to CLI processes SHALL be the layered allowlist of the execution-environment capability: the adapter's base set, operator passthrough names, the AI base-url/auth-token seam variables, and required tool variables — nothing else. No factory-process variable SHALL be inherited implicitly.
<!-- implements FR9 of add-sandbox-core -->

#### Scenario: Tracker token is absent from the round
- **WHEN** an agent round starts while the factory holds the tracker token in its own environment
- **THEN** the CLI process environment contains no tracker token and no variable outside the allowlist

### Requirement: Prompts are delivered via stdin
Round and judge prompts SHALL be passed to the agent CLI via stdin, never as an argv argument, in all modes and both adapters — argv caps a single argument (Linux `MAX_ARG_STRLEN`) while prompts accumulate the findings history of all prior attempts, and argv content is visible to any host user via process listings.
<!-- implements FR24 of add-sandbox-core -->

#### Scenario: Late attempt with a large feedback history starts
- **WHEN** a round prompt exceeds the platform's single-argument size limit
- **THEN** the process starts and receives the full prompt on stdin, with no truncation

#### Scenario: Prompt content is absent from the command line
- **WHEN** a round or judge process is inspected while running
- **THEN** its command line contains transport flags only — no prompt text, no findings

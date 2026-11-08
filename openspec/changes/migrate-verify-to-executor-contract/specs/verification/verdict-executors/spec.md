# verification/verdict-executors — delta for migrate-verify-to-executor-contract

## Purpose

The built-in executors that answer in role `verdict` — file existence, shell command, GitHub
Actions runs, HTTP probe, SCM status reader — and the rules every verdict executor shares:
findings as SARIF, no verdict constructed by the executor, credentials only in a fresh box.

## ADDED Requirements

### Requirement: Checks resolve to verdict executors
A verify check SHALL resolve to a named executor supporting role `verdict`. The manifest tokens
`builtin`, `command` and `external` SHALL map to the built-in names `files-exist`, `shell` and
the declared provider name; an explicit `executor:` form SHALL be accepted beside them.
**Implements**: FR1 of migrate-verify-to-executor-contract

#### Scenario: Legacy manifest loads unchanged
- **WHEN** a stage declares `- type: command` and `- type: external, provider: github`
- **THEN** the loaded model names executors `shell` and `github-actions` and no load error occurs

#### Scenario: Executor without the verdict role is refused
- **WHEN** a check names an executor whose describe lists only `transform`
- **THEN** loading reports a located error naming the check, the executor and the role

### Requirement: Executors report status, the engine derives the verdict
A verdict executor SHALL report status and a SARIF answer only; pass or fail SHALL be derived
by the engine from the answer's result levels against the check's threshold (default `error`).
A shell exit code SHALL carry no verdict meaning.
**Implements**: FR5, FR7 of migrate-verify-to-executor-contract

#### Scenario: Warnings pass under the default threshold
- **WHEN** the `shell` executor exits 1 and its answer carries only `warning` results
- **THEN** the check passes with the warnings recorded as findings

#### Scenario: Legacy findings file is normalized
- **WHEN** the command writes `{"findings":[{"message":"x"}]}` to the findings path
- **THEN** the answer is stored as SARIF with one `error` result and the check fails

### Requirement: A pass keeps its below-threshold findings
A passing verdict SHALL carry every result below the check's threshold as a finding, in the order
written, and every writer of a check result — the state file, `status --json`, the usage report —
SHALL record a pass's findings exactly as it records a failure's. A pass's findings SHALL feed
no later attempt: retry feedback stays the failed checks of prior attempts.
**Implements**: FR7 of migrate-verify-to-executor-contract
**Notes**: Design D5 as amended; the findings-less pass of `add-stage-engine` FR4 and `add-manual-run` FR8 rested on the exit code being the verdict, which D5 removed.

#### Scenario: A pass keeps its findings across the state file
- **WHEN** a check passes with two `warning` results and the attempt record is written to the task branch and read back
- **THEN** the read verdict is a pass carrying the same two findings in the same order, and the next attempt's feedback does not mention them

#### Scenario: A judge pass keeps its findings
- **WHEN** the judge's final message carries `{"passed": true, "findings": ["x"]}`
- **THEN** the vote is a pass carrying the finding `x`, and a malformed `findings` array still makes the vote `CannotVerify`

### Requirement: The shell executor runs the command in the task environment
The `shell` executor SHALL run the manifest command via `sh -c` through `exec()` of the bound
task environment, with the working copy as working directory, stderr merged into stdout and a
bounded output tail (~200 lines / 10 KB) retained. The exit code SHALL be reported as status,
never as a verdict: 126 and 127 are `failed`/`infrastructure`; any other non-zero exit with no
valid result written SHALL yield one `error` result carrying the output tail.
**Implements**: FR5 of migrate-verify-to-executor-contract
**Notes**: Former "Command check runner" of `cli/manual-run` (FR7 of add-manual-run; FR11, NFR-S1 of add-claim-heartbeat; FR4, FR9 of add-sandbox-core), restated in the derived form by D5 as amended 2026-11-08.
**Notes**: The check's allowlist is the execution-environment capability's layered one: adapter base set, operator passthrough, factory-set variables.

#### Scenario: A red command without a findings file carries its output tail
- **WHEN** the command exits 1 and wrote no findings file
- **THEN** the answer holds one `error` result whose details contain the output tail, and the check fails

#### Scenario: A missing binary is an infrastructure failure
- **WHEN** the command exits 127
- **THEN** the status is `failed`/`infrastructure` and the derived verdict is `CannotVerify`, honoring the engine's classification table

#### Scenario: No factory variable leaks into a shell check
- **WHEN** a shell check runs while `GNOMISH_GITHUB_TOKEN` and unrelated host variables are set in the factory environment
- **THEN** the check's process environment contains only the allowlisted variables

#### Scenario: A shell check inherits no factory variable implicitly
- **WHEN** a variable is set in the factory process and is in none of the allowlist's layers
- **THEN** it is absent from the check's environment, so a tracker credential cannot reach a check by construction

### Requirement: The shell executor reads its findings path whatever the exit code
Before starting the command, the `shell` executor SHALL allocate a findings-file path in the
environment's scratch area — outside the working copy, inside the bound task environment — and
pass it as `GNOMISH_FINDINGS_FILE`. After exit it SHALL read the file through `ResultFileReader`
on every exit code but 126 and 127: a SARIF file is the answer, a valid legacy
`{"findings":[...]}` file is normalized to `error` results, and either replaces the synthetic
result.
**Implements**: FR5 of migrate-verify-to-executor-contract
**Notes**: Former "Findings-JSON wire format" of `cli/manual-run` (FR8, NFR-R2 of add-manual-run; FR1, FR4 of add-sandbox-core); its drop of the file on exit 0 contradicted "A pass keeps its below-threshold findings" and is gone.

#### Scenario: Structured findings replace the synthetic result
- **WHEN** the command exits 1 and wrote two valid legacy findings
- **THEN** the check fails with exactly those two findings and no synthetic one

#### Scenario: A broken reporter cannot mask a red command
- **WHEN** the command exits 1 and the findings file is unparseable
- **THEN** the check fails (not `CannotVerify`) with the synthetic tail result

#### Scenario: A malformed findings file is logged
- **WHEN** the command exits 1 and the findings file is malformed
- **THEN** a warning is logged and the synthetic tail result stands

#### Scenario: A findings file on a green command is read
- **WHEN** the command exits 0 and wrote a file with one `warning` result
- **THEN** the check passes carrying that finding
- **AND** the same file with one `error` result fails the check

### Requirement: Built-in verdict executors report a known zero usage
A built-in verdict executor that mediates no model SHALL report usage with provenance
`REPORTED` and an empty per-model map, and its describe SHALL declare `reportsUsage = true`,
so a task whose checks are built-ins keeps a complete usage fold and no per-model zero is
written.
**Implements**: NFR-C1 of migrate-verify-to-executor-contract
**Notes**: Adopts design D8 of `add-command-executor` for the verdict built-ins (design D13).

#### Scenario: Built-in verdict usage keeps the task cost complete
- **WHEN** a built-in verdict executor (`files-exist`, `shell`, `scm-status`, `github-actions` or `http`) completes a check
- **THEN** its usage entry is `REPORTED` with no per-model figure, and the task's usage fold stays complete

### Requirement: A credentialed built-in verdict executor is connected by operator configuration
A built-in verdict executor that declares credentials SHALL take its endpoint and credential
names from the operator's `factory.check.<executor-name>` subsection, inline or by a `connection:`
reference to a `factory.connections` profile graded at load like any port subsection's; a check
naming such an executor with no connection configured SHALL be a located load error.
**Implements**: NFR-S2 of migrate-verify-to-executor-contract
**Notes**: The tracker's subsection is never read for a verdict executor (design D3).

#### Scenario: Operator connects scm-status with configuration alone
- **WHEN** the operator declares `factory.check.scm-status.connection: github-main` and `factory.connections.github-main` names the endpoint and token name
- **THEN** an `scm-status` check reads statuses from that endpoint with the token resolved through the secrets port, and no tracker subsection is read

#### Scenario: Unconnected credentialed built-in is a located error
- **WHEN** a check names `github-actions` and no `factory.check.github-actions` subsection or profile supplies its connection
- **THEN** loading reports a located error naming the check and the executor

### Requirement: SCM status reader
The `scm-status` built-in SHALL read named verdicts on the attempt SHA from the configured SCM —
GitHub Checks and Commit Statuses, Gitea statuses — SHALL require an `expected` contexts list,
SHALL return `pending` while any expected context is absent or running, SHALL take the latest
verdict per context, and SHALL fail with findings naming each failed context.
**Implements**: FR4, FR9, NFR-R2 of migrate-verify-to-executor-contract

#### Scenario: Absent context stays pending
- **WHEN** two contexts are expected and only one has reported success
- **THEN** the executor returns `pending` and the engine keeps polling

#### Scenario: Same-named duplicate uses the latest
- **WHEN** a context reports failure then success on the same SHA
- **THEN** the context counts as passed

#### Scenario: Status on another SHA is ignored
- **WHEN** the only report for an expected context is on the previous attempt's SHA
- **THEN** the executor returns `pending`

#### Scenario: Rate limit backs off
- **WHEN** the SCM answers with a rate-limit response
- **THEN** the executor returns `pending` with a wait hint and the engine respects it

### Requirement: Existing providers keep their behavior as built-ins
The `github-actions` and `http` built-ins SHALL preserve the observable behavior of the former
check providers — workflow-run lookup by commit with job-log findings, pin contribution of the
workflow file; status-code probe with conditions — expressed as `start`/`poll` over the contract.
**Implements**: FR6 of migrate-verify-to-executor-contract

#### Scenario: GitHub Actions findings unchanged
- **WHEN** a workflow run on the attempt commit fails with two red jobs
- **THEN** the check fails with the same job-log findings the provider produced before

#### Scenario: Pin contribution survives
- **WHEN** the gnome modified the workflow file of a `github-actions` check, whose describe declares `checkIdIsPinnedPath`
- **THEN** the pin-check fails before the first `start`, with no pin path declared in the manifest

### Requirement: SCM verdict built-ins are core's own, outside the vendor bundle
The `github-actions` and `scm-status` built-ins SHALL be compiled into the factory and
registered by core like every built-in, outside the github vendor bundle; the bundle SHALL
carry the github tracker only. Both built-ins and the bundle's tracker SHALL use the one
first-party GitHub HTTP core, and every in-process HTTP call of a built-in verdict executor
SHALL pass the one HTTP egress guard, admitted by its operator connection and the redirect
hosts it declares.
**Implements**: FR13, FR14, FR15, FR17 of migrate-verify-to-executor-contract
**Notes**: Human decision of 2026-11-06 on the escalation of task #121 (design D4, D15, D16); the `plugin/github-plugin` capability is restated for the tracker.

#### Scenario: Removing the vendor bundle keeps the SCM built-ins
- **WHEN** the github vendor bundle is absent and a stage declares a `github-actions` check and an `scm-status` check
- **THEN** both checks resolve from the executor registry and run, while the github tracker is unavailable

#### Scenario: A built-in's HTTP call outside its declared hosts is refused
- **WHEN** the platform answers a `github-actions` call with a redirect to a host that neither the built-in's declared redirect hosts nor its connection's `redirect-hosts` name
- **THEN** the hop is refused by the egress guard before any connection and the check is `failed/infrastructure` naming the host and the connection's key

### Requirement: Credentialed verdict executors run in a fresh box
A `program` verdict executor whose describe declares credentials SHALL run in `FRESH_BOX` with
an egress allowlist derived from its declared hosts; a `SAME_BOX` declaration on such a check
SHALL be a located load error. A credentialed built-in runs in the factory process under the
in-process egress guard (its operator connection and declared redirect hosts); its token SHALL
enter no box, and its `verify-in` value SHALL not move its HTTP call.
**Implements**: NFR-S1, FR15, FR17 of migrate-verify-to-executor-contract

#### Scenario: Secret never enters the gnome's box
- **WHEN** a check names an executor declaring `SONAR_TOKEN`
- **THEN** the executor runs in a fresh box, the token is present there and absent from the
  round box, and the fresh box may reach only the declared hosts

#### Scenario: A built-in's token enters no box
- **WHEN** a check names the `github-actions` built-in in container mode
- **THEN** the token is resolved in the factory process, is absent from the round box and from
  any fresh box of the attempt, and the built-in's calls pass the in-process allowlist

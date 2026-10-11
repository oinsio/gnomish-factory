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
<!-- implements FR1 of migrate-verify-to-executor-contract -->

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
<!-- implements FR5, FR7 of migrate-verify-to-executor-contract -->

#### Scenario: Warnings pass under the default threshold
- **WHEN** the `shell` executor exits 1 and its answer carries only `warning` results
- **THEN** the check passes with the warnings recorded as findings

#### Scenario: Legacy findings file is normalized
- **WHEN** the command writes `{"findings":[{"message":"x"}]}` to the findings path
- **THEN** the answer is stored as SARIF with one `error` result and the check fails

### Requirement: SCM status reader
The `scm-status` built-in SHALL read named verdicts on the attempt SHA from the configured SCM —
GitHub Checks and Commit Statuses, Gitea statuses — SHALL require an `expected` contexts list,
SHALL return `pending` while any expected context is absent or running, SHALL take the latest
verdict per context, and SHALL fail with findings naming each failed context.
<!-- implements FR4, FR9, NFR-R2 of migrate-verify-to-executor-contract -->

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
<!-- implements FR6 of migrate-verify-to-executor-contract -->

#### Scenario: GitHub Actions findings unchanged
- **WHEN** a workflow run on the attempt commit fails with two red jobs
- **THEN** the check fails with the same job-log findings the provider produced before

#### Scenario: Pin contribution survives
- **WHEN** the gnome modified the workflow file of a `github-actions` check
- **THEN** the pin-check fails before the first `start`

### Requirement: Credentialed verdict executors run in a fresh box
A verdict executor whose describe declares credentials SHALL run in `FRESH_BOX` with an egress
allowlist derived from its declared hosts; a `SAME_BOX` declaration on such a check SHALL be a
located load error.
<!-- implements NFR-S1 of migrate-verify-to-executor-contract -->

#### Scenario: Secret never enters the gnome's box
- **WHEN** a check names an executor declaring `SONAR_TOKEN`
- **THEN** the executor runs in a fresh box, the token is present there and absent from the
  round box, and the fresh box may reach only the declared hosts

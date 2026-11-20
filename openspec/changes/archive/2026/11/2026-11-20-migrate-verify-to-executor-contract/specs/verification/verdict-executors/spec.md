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
- **AND** the result carries `pendingState: unobserved` and a `pollAfterMillis` taken from the response's `retry-after`, or else its rate-limit reset, and the engine makes no poll of the key sooner

### Requirement: The SCM status reader names what it awaits
While an expected context is absent on the attempt SHA, `scm-status` SHALL answer `pending` with
`pendingState: unreported`, and while every expected context is reported and one is running, with
`pendingState: running`; its `reason` SHALL name every expected context still absent or running.
**Implements**: FR4, FR9, UX1 of migrate-verify-to-executor-contract
**Notes**: The engine decides the timeout's class from the state and shows the reason (design D19).

#### Scenario: Absent context is reported as unreported
- **WHEN** two contexts are expected, one has reported success and the other has not reported on the attempt SHA
- **THEN** the result is `pending` with `pendingState: unreported` and a reason naming the absent context

#### Scenario: Running context is reported as running
- **WHEN** every expected context has reported and one of them is still running
- **THEN** the result is `pending` with `pendingState: running` and a reason naming the running context

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
first-party GitHub HTTP core.
**Implements**: FR13, FR14 of migrate-verify-to-executor-contract
**Notes**: Human decision of 2026-11-06 on the escalation of task #121 (design D4, D15, D16); the `plugin/github-plugin` capability is restated for the tracker.

#### Scenario: Removing the vendor bundle keeps the SCM built-ins
- **WHEN** the github vendor bundle is absent and a stage declares a `github-actions` check and an `scm-status` check
- **THEN** both checks resolve from the executor registry and run, while the github tracker is unavailable


### Requirement: A built-in verdict executor's HTTP passes the egress guard
Every in-process HTTP call of a built-in verdict executor SHALL pass the one HTTP egress guard,
admitted by its operator connection, the connection's `egress` list, the hosts its describe
`egress` declares and the `in-process` scope.
**Implements**: FR15, FR17 of migrate-verify-to-executor-contract
**Notes**: Split out of "SCM verdict built-ins are core's own, outside the vendor bundle" to keep both within OpenSpec's 500-character limit (design D16 as amended 2026-11-09).

#### Scenario: A built-in's HTTP call outside its declared hosts is refused
- **WHEN** the platform answers a `github-actions` call with a redirect to a host that neither the built-in's describe `egress`, nor its connection's `egress`, nor `factory.egress.in-process` names
- **THEN** the hop is refused by the egress guard before any connection and the check is `failed/infrastructure` naming the host and the connection's key

### Requirement: Credentialed verdict executors run in a fresh box
A `program` verdict executor whose describe declares credentials SHALL run in `FRESH_BOX` with
the egress the law's `executors.<name>.egress` grants, bounded by its describe; a `SAME_BOX` declaration on such a check
SHALL be a located load error. A credentialed built-in runs in the factory process under the
in-process egress guard (its operator connection, the connection's `egress` and its describe `egress`); its token SHALL
enter no box, and its `verify-in` value SHALL not move its HTTP call.
**Implements**: NFR-S1, FR15, FR17 of migrate-verify-to-executor-contract

#### Scenario: Secret never enters the gnome's box
- **WHEN** a check names an executor declaring `SONAR_TOKEN`
- **THEN** the executor runs in a fresh box, the token is present there and absent from the
  round box, and the fresh box may reach only the hosts the law grants the executor

#### Scenario: A built-in's token enters no box
- **WHEN** a check names the `github-actions` built-in in container mode
- **THEN** the token is resolved in the factory process, is absent from the round box and from
  any fresh box of the attempt, and the built-in's calls pass the in-process allowlist

### Requirement: Each request of a fresh-box program check gets its own box at the attempt commit
Each `start` and each `poll` of a `program` verdict check bound through the fresh box SHALL run in
a box materialized for that request at the attempt commit of the round under verification and
disposed after it, so the working copy's `HEAD` is that commit on every request of the check, a
resumed instance's included.
**Implements**: FR11, FR3 of migrate-verify-to-executor-contract
**Notes**: Design D11 as amended 2026-11-14; reusing a box across requests is proposal NG16, with the measurement of task 5.3 as its pickup.
**Notes**: A pollable verdict program keeps what a later `poll` of its key needs outside its box — in the service it drives, or in the key itself; the conformance kit's pending cases enforce it ("Pending cases poll from a new working copy"). A key whose run it kept only in a box is answered by the program as a key it does not hold: a refusal is the visible `failed/infrastructure`, and a program that starts the run anew breaks FR11 and fails the kit's pending cases.

#### Scenario: Two requests of one check see one commit in two boxes
- **WHEN** a fresh-box `program` verdict check returns `pending` from `start` and its key is polled
- **THEN** the poll runs in a box other than the one `start` ran in, and `HEAD` of both working copies is the attempt commit

#### Scenario: A resumed poll runs in a box at the same commit
- **WHEN** an instance resumes a pending fresh-box `program` check from its recorded key
- **THEN** the poll runs in a new box whose `HEAD` is the attempt commit the recorded round names, and no `start` is sent

### Requirement: A program verdict check outside a fresh box is refused at run assembly
A law whose check names a `program` executor that would run outside a fresh box SHALL be refused
at run assembly with a located error naming the check and the executor, taking the path of a
describe refusal; the refusal SHALL NOT surface as a failure of the check's round.
**Implements**: FR1, FR3 of migrate-verify-to-executor-contract
**Notes**: Outside a fresh box means host mode, or `same-box` verification in container mode, the default for a check without credentials. The located error names the check by its manifest and `verify[<i>]`.
**Notes**: As for a describe refusal, `take` and `serve` park the task with the located report and no attempt burned, and `run` exits as for an invalid law.
**Notes**: Proposal NG14: no task binds a `program` check on the host or in the round's box; the first change that does lifts this refusal for its medium (design D14, D18).
**Notes**: The refusal follows the run-assembly refusal of a built-in's connection (design D18, "Where a refusal at run assembly goes"); the resolution's `IllegalStateException` stays a wiring defect no law can reach.

#### Scenario: A program verdict check outside a fresh box is a located error
- **WHEN** a law's check names a `program` executor and the run would verify it on the host or in the round's box
- **THEN** run assembly refuses with a located error naming the check and the executor, no process of the executor is spawned, `take` and `serve` park the task with no attempt burned, and `run` exits as for an invalid law

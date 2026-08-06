# stage-engine

## MODIFIED Requirements

### Requirement: External check poll loop
The engine SHALL poll an `external` check via single-poll port calls
returning `PollStatus` (Pass | Fail with findings | Running | CannotVerify
with reason and details) at the manifest interval until a verdict or the
manifest timeout, using an injected sleeper/clock. A timeout SHALL classify
per the check's declared timeout class: `quality` (the default) fails the
check as a quality failure with a timeout finding; `infrastructure`
resolves the check as CannotVerify naming the elapsed timeout — no stage
attempt is burned. The engine SHALL NOT submit external checks: they are
assumed to be triggered by the task-branch push (submission is deferred —
add-stage-engine NG8).
<!-- implements FR3, NFR-R3 of add-stage-engine -->
<!-- implements FR9 of add-external-check-github-actions -->

#### Scenario: Verdict within timeout
- **WHEN** the poll port returns Running twice and then Pass
- **THEN** the engine sleeps the manifest interval between polls and the
  check passes

#### Scenario: Timeout burns the attempt by default
- **WHEN** the poll port still returns Running when the timeout elapses
  (per the injected clock) and the check declares no timeout class
- **THEN** the check fails as a quality failure with a timeout finding

#### Scenario: Infrastructure-classed timeout burns no attempt
- **WHEN** the poll port still returns Running when the timeout elapses
  and the check declares its timeout class `infrastructure`
- **THEN** the check resolves as CannotVerify naming the elapsed timeout
- **AND** no stage attempt is burned and the task escalates with a
  "cannot verify" report

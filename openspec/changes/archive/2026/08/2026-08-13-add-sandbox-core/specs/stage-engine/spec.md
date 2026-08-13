# stage-engine (delta)

## MODIFIED Requirements

### Requirement: External check poll loop
The engine SHALL poll an `external` check via single-poll port calls
returning `PollStatus` (Pass with an optional platform run URL | Fail
with findings | Running | CannotVerify with reason and details) at the
manifest interval until a verdict or the manifest timeout, using an
injected sleeper/clock. A run URL carried by Pass SHALL be preserved
into the recorded check result, so reporting publishes it through the
same channel a failing check's findings travel. A timeout SHALL classify
per the check's declared timeout class: `quality` (the default) fails the
check as a quality failure with a timeout finding; `infrastructure`
resolves the check as CannotVerify naming the elapsed timeout — no stage
attempt is burned. The engine SHALL NOT submit external checks: they are
assumed to be triggered by the task-branch push (submission is deferred —
add-stage-engine NG8). Because of that, delivery of the attempt commit
to the remote SHALL be a verified precondition of the poll loop: before
the first poll the engine SHALL confirm the attempt commit is on the
remote, re-attempting the push if it is not; a commit that cannot be
delivered SHALL resolve the check as CannotVerify (infrastructure
failure, no stage attempt burned) — never left to expire as a
poll-timeout quality failure.
<!-- implements FR3, NFR-R3 of add-stage-engine -->
<!-- implements FR9 of add-external-check-github-actions -->
<!-- implements FR21, NFR-O2 of add-sandbox-core -->

#### Scenario: Verdict within timeout
- **WHEN** the poll port returns Running twice and then Pass
- **THEN** the engine sleeps the manifest interval between polls and the
  check passes

#### Scenario: A passing poll's run link is recorded
- **WHEN** the poll returns Pass carrying a platform run URL
- **THEN** the recorded check result carries that URL for the task report

#### Scenario: Undelivered attempt commit never burns an attempt
- **WHEN** the attempt commit cannot be pushed to the remote and the
  re-attempted push also fails
- **THEN** the external check resolves as CannotVerify naming the
  undelivered commit, no poll is issued, and no stage attempt is burned

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

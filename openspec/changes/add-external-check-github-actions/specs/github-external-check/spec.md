# github-external-check

## ADDED Requirements

### Requirement: Verdicts originate from workflow-run conclusions only
The adapter SHALL derive Pass/Fail exclusively from the conclusion of
workflow runs authored by the platform; check-run and commit statuses —
creatable with a repo-scoped token — SHALL never be consulted. A
non-`success` or unknown conclusion SHALL map to Fail.
<!-- implements FR2, FR3 of add-external-check-github-actions -->

#### Scenario: Forged status is ignored
- **WHEN** the attempt commit carries a token-created "success" status while
  the matching workflow run concluded `failure`
- **THEN** the poll returns Fail, derived from the run conclusion alone

#### Scenario: Unknown conclusion fails closed
- **WHEN** the matching run reports a conclusion the adapter does not
  recognize
- **THEN** the poll returns Fail

### Requirement: Runs are matched by attempt commit and declared workflow
A poll SHALL consider only workflow runs whose head commit equals the
attempt commit under verification and whose workflow is the check's
`checkId`; among several matching runs the latest run attempt SHALL be
authoritative. Runs of other workflows or other commits SHALL not influence
the verdict.
<!-- implements FR1, FR5 of add-external-check-github-actions -->

#### Scenario: Unrelated workflows do not gate the stage
- **WHEN** the push triggers three workflows and the check declares only
  `ci.yml`
- **THEN** the verdict comes from the `ci.yml` run alone, whatever the other
  runs conclude

#### Scenario: Re-run supersedes the first attempt
- **WHEN** the matching workflow ran twice for the attempt commit and only
  the newest attempt concluded `success`
- **THEN** the poll returns Pass

### Requirement: Absent verdict reads as still running
When no matching run exists yet, or the matching run has no conclusion, the
poll SHALL return Running; timeout classification stays with the engine,
per the check's declared timeout class (add-stage-engine FR3, FR9 of this
change).
<!-- implements FR2 of add-external-check-github-actions -->

#### Scenario: CI has not picked up the push yet
- **WHEN** the platform lists no run for the attempt commit
- **THEN** the poll returns Running and the engine keeps polling until its
  timeout

### Requirement: Infrastructure failures burn no attempt
Network errors, 5xx responses and rate-limit rejections SHALL classify as
CannotVerify with the cause named — infrastructure failures of the check,
retried without consuming a stage attempt.
<!-- implements NFR-R1 of add-external-check-github-actions -->

#### Scenario: Platform outage is not a red build
- **WHEN** the runs query returns 503
- **THEN** the poll returns CannotVerify naming the outage, and no quality
  failure is recorded

### Requirement: Failure findings carry jobs and capped log tails
On Fail the adapter SHALL emit findings naming each failed job and step plus
the tail of each failed job's log, within the funnel's size caps; the
findings travel through the unified funnel like every other check's.
<!-- implements FR6, NFR-C1, UX1 of add-external-check-github-actions -->

#### Scenario: The gnome sees why CI failed
- **WHEN** a matching run concludes `failure` with two failed jobs
- **THEN** findings name both jobs and their failed steps and include each
  job's log tail, truncated to the cap with truncation noted

### Requirement: The adapter contributes its workflow file to the pin set
The pin set checked by the pin-check guard SHALL be the union of the
user-declared paths from the stage law and the `checkId` workflow file
contributed by the adapter.
<!-- implements FR4 of add-external-check-github-actions -->

#### Scenario: Early substitution is caught at the point of use
- **WHEN** the gnome modified the declared workflow file during an earlier
  stage and a later stage reaches this check
- **THEN** the pin-check guard fails the check against the base branch
  before any platform contact

### Requirement: Polling is stateless and takeover-safe
A poll SHALL depend only on the check declaration and the attempt commit; no
poll state SHALL be persisted, so any factory instance can resume polling
after a crash or takeover and observe the same runs.
<!-- implements NFR-R2 of add-external-check-github-actions -->

#### Scenario: Another instance resumes mid-poll
- **WHEN** the polling instance dies and another instance resumes the task
- **THEN** the new instance polls the same attempt commit and reaches the
  same verdict with no state handed over

### Requirement: Token resolution and hygiene
The adapter SHALL obtain its token through `SecretsProvider`, require read
scope only, and never include token material in logs or findings.
<!-- implements FR8, NFR-S1 of add-external-check-github-actions -->

#### Scenario: Token never leaks into findings
- **WHEN** a poll fails and CannotVerify details preserve the HTTP exception
- **THEN** the recorded details contain no token material

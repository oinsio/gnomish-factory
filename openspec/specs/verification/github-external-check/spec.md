# github-external-check

## Purpose

Adapt the `external` check port to GitHub Actions: derive Pass/Fail verdicts solely from workflow-run conclusions (never from forgeable check-run or commit statuses), match runs to the attempt commit and declared workflow, classify infrastructure and misconfiguration failures without burning stage attempts, surface failed jobs and capped log tails as findings, contribute the check's workflow file to the pin set, and poll statelessly so any factory instance can resume.

## Requirements

### Requirement: Verdicts originate from workflow-run conclusions only
The adapter SHALL derive Pass/Fail exclusively from the conclusion of
workflow runs authored by the platform; check-run and commit statuses —
creatable with a repo-scoped token — SHALL never be consulted. A
non-`success` or unknown conclusion SHALL map to Fail.
**Implements**: FR2, FR3 of add-external-check-github-actions

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
**Implements**: FR1, FR5 of add-external-check-github-actions

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
per the check's declared timeout class (stage-engine's external check poll
loop requirement).
**Implements**: FR2 of add-external-check-github-actions

#### Scenario: CI has not picked up the push yet
- **WHEN** the platform lists no run for the attempt commit
- **THEN** the poll returns Running and the engine keeps polling until its
  timeout

### Requirement: Infrastructure failures burn no attempt
Network errors, 5xx responses and rate-limit rejections SHALL classify as
CannotVerify with the cause named — infrastructure failures of the check,
retried without consuming a stage attempt.
**Implements**: NFR-R1 of add-external-check-github-actions

#### Scenario: Platform outage is not a red build
- **WHEN** the runs query returns 503
- **THEN** the poll returns CannotVerify naming the outage, and no quality
  failure is recorded

### Requirement: Misconfiguration fails fast without burning an attempt
A client-side rejection the retry policy cannot resolve — a 401 (invalid or
expired token), a non-rate-limited 403 (token lacks Actions read scope), a
404 (checkId names no existing workflow), or any other non-2xx that is not a
transient infrastructure failure — SHALL classify as CannotVerify
immediately, without polling to the check's timeout, naming the likely
misconfiguration; no stage attempt is burned.
**Implements**: NFR-R3 of add-external-check-github-actions
**Notes**: Read as a runs listing, an error body's missing `workflow_runs` array would look like an empty, still-Running list.

#### Scenario: A mistyped checkId is diagnosed, not silently polled
- **WHEN** the runs query for the declared checkId returns 404
- **THEN** the poll returns CannotVerify at once, its reason naming the check
  and that no workflow by that file name exists, and no quality failure is
  recorded

#### Scenario: An expired token escalates immediately
- **WHEN** the runs query returns 401
- **THEN** the poll returns CannotVerify naming the invalid or expired token,
  without waiting for the check's timeout, and burns no stage attempt

#### Scenario: An error body is never read as a runs listing
- **WHEN** the runs query returns a client-side rejection whose body carries no
  `workflow_runs` array
- **THEN** the poll returns CannotVerify, never Running, and the error body is
  never parsed as a runs listing

### Requirement: Failure findings carry jobs and capped log tails
On Fail the adapter SHALL emit findings naming each failed job and step plus
the tail of each failed job's log, within the funnel's size caps; the
findings travel through the unified funnel like every other check's.
**Implements**: FR6, NFR-C1, UX1 of add-external-check-github-actions

#### Scenario: The gnome sees why CI failed
- **WHEN** a matching run concludes `failure` with two failed jobs
- **THEN** findings name both jobs and their failed steps and include each
  job's log tail, truncated to the cap with truncation noted

### Requirement: The adapter contributes its workflow file to the pin set
The pin set checked by the pin-check guard SHALL be the union of the
user-declared paths from the stage law and the `checkId` workflow file
contributed by the adapter.
**Implements**: FR4 of add-external-check-github-actions

#### Scenario: Early substitution is caught at the point of use
- **WHEN** the gnome modified the declared workflow file during an earlier
  stage and a later stage reaches this check
- **THEN** the pin-check guard fails the check against the base branch
  before any platform contact

### Requirement: Polling is stateless and takeover-safe
A poll SHALL depend only on the check declaration and the attempt commit;
no poll state SHALL be persisted, so any factory instance can resume
polling after a crash or takeover and observe the same runs. The adapter
SHALL read the attempt commit from this change's concrete workspace type
— the one the engine hands to check runners — replacing the adapter-local
`GithubCheckWorkspace` stand-in and its internal downcast.
**Implements**: NFR-R2 of add-external-check-github-actions; FR26 of add-sandbox-core

#### Scenario: Another instance resumes mid-poll
- **WHEN** the polling instance dies and another instance resumes the task
- **THEN** the new instance polls the same attempt commit and reaches the
  same verdict with no state handed over

#### Scenario: The adapter consumes the engine workspace type
- **WHEN** the stage engine invokes the check with the workspace carrying
  the attempt commit of the round under verification
- **THEN** the adapter reads the attempt commit from that workspace and
  polls runs of exactly that commit

### Requirement: Token resolution and hygiene
The adapter SHALL obtain its token through `SecretsProvider`, require read
scope only, and never include token material in logs or findings.
**Implements**: FR8, NFR-S1 of add-external-check-github-actions

#### Scenario: Token never leaks into findings
- **WHEN** a poll fails and CannotVerify details preserve the HTTP exception
- **THEN** the recorded details contain no token material

### Requirement: Adapter is constructed from factory configuration
The factory SHALL construct `GithubCheckExternalClient` from factory
config — the platform base URL under a dedicated config key and a token
resolved by name through `SecretsProvider` — and inject it into the stage
engine wrapped by the pin-check guard, so an operator enables the GitHub
Actions adapter with configuration alone, no code changes.
**Implements**: FR26 of add-sandbox-core; FR16 of add-plugin-architecture; FR17 of add-plugin-architecture; UX3 of add-plugin-architecture
**Notes**: The `connection:` profile is the same one the github tracker may reference, so one vendor keeps a single connection and credentials block (the vendor-connection-profile capability); the `SecretsProvider`-backed token name replaced the provisional direct environment read.

#### Scenario: Operator enables the adapter with config alone
- **WHEN** factory config declares the external-check base URL and the
  token secret resolves
- **THEN** stages declaring external checks poll GitHub Actions through
  the constructed adapter, behind the pin-check guard

#### Scenario: Adapter is constructed from a shared connection profile
- **WHEN** the check subsection declares `connection: <name>` and the operator
  config defines that profile
- **THEN** the adapter resolves the base URL and the token's credential name
  from the profile, sharing them with any other port referencing the same
  profile

#### Scenario: Missing token fails closed at wiring time
- **WHEN** the token secret does not resolve
- **THEN** construction fails as a configuration error naming the missing
  secret; no stage runs with an unauthenticated adapter

#### Scenario: External-check token cannot be allowlisted
- **WHEN** operator config lists `GNOMISH_GITHUB_ACTIONS_TOKEN` as a
  child-environment passthrough variable
- **THEN** startup fails with a configuration error naming the variable,
  same as for the tracker token

#### Scenario: An inline key the profile lacks overlays it
- **WHEN** the check subsection declares `connection: <name>` and, inline, a
  key the referenced profile does not define
- **THEN** the adapter is constructed from the profile's values with that
  inline key overlaid

#### Scenario: An inline key the profile also defines is a load error
- **WHEN** a check subsection referencing a profile declares, inline, keys the
  referenced profile also defines
- **THEN** the configuration is refused before any adapter is built, with one
  located error per overlapping key naming it and the two forms declared; in
  operator config the refusal stops startup

#### Scenario: The default token name is the env/file secret
- **WHEN** the check subsection references no connection profile
- **THEN** the token resolves through `SecretsProvider` under the name
  `GNOMISH_GITHUB_ACTIONS_TOKEN`, which the env/file adapter backs

#### Scenario: A profile-supplied token name cannot be allowlisted
- **WHEN** the referenced connection profile names the token under another
  variable and operator config lists that variable as a child-environment
  passthrough variable
- **THEN** the adapter declares the profile-supplied name through the check
  SPI's credential declaration, and startup fails with a configuration error
  naming the variable

### Requirement: Pass verdicts carry the platform run URL
On Pass the adapter SHALL include the URL of the authoritative workflow
run in the poll result, so the link reaches the tracker report through
the same check-result channel a failing check's findings use.
**Implements**: NFR-O2 of add-sandbox-core

#### Scenario: A green check is auditable from the tracker
- **WHEN** the matching workflow run concludes `success`
- **THEN** the poll returns Pass carrying that run's URL and the task
  report's entry for the check surfaces it

# github-external-check (delta)

## ADDED Requirements

### Requirement: Adapter is constructed from factory configuration
The factory SHALL construct `GithubCheckExternalClient` from factory
config — the platform base URL under a dedicated config key and a token
resolved by name through `SecretsProvider` — and inject it into the stage
engine wrapped by the pin-check guard, so an operator enables the GitHub
Actions adapter with configuration alone, no code changes. The env/file
adapter backs the token name with `GNOMISH_GITHUB_ACTIONS_TOKEN`,
replacing the provisional direct env read; the adapter SHALL declare
that name as a credential so it can never be admitted into a
child-environment allowlist, matching the tracker token's treatment.
<!-- implements FR26 of add-sandbox-core -->

#### Scenario: Operator enables the adapter with config alone
- **WHEN** factory config declares the external-check base URL and the
  token secret resolves
- **THEN** stages declaring external checks poll GitHub Actions through
  the constructed adapter, behind the pin-check guard

#### Scenario: Missing token fails closed at wiring time
- **WHEN** the token secret does not resolve
- **THEN** construction fails as a configuration error naming the missing
  secret; no stage runs with an unauthenticated adapter

#### Scenario: External-check token cannot be allowlisted
- **WHEN** operator config lists `GNOMISH_GITHUB_ACTIONS_TOKEN` as a
  child-environment passthrough variable
- **THEN** startup fails with a configuration error naming the variable,
  same as for the tracker token

### Requirement: Pass verdicts carry the platform run URL
On Pass the adapter SHALL include the URL of the authoritative workflow
run in the poll result, so the link reaches the tracker report through
the same check-result channel a failing check's findings use.
<!-- implements NFR-O2 of add-sandbox-core -->

#### Scenario: A green check is auditable from the tracker
- **WHEN** the matching workflow run concludes `success`
- **THEN** the poll returns Pass carrying that run's URL and the task
  report's entry for the check surfaces it

## MODIFIED Requirements

### Requirement: Polling is stateless and takeover-safe
A poll SHALL depend only on the check declaration and the attempt commit;
no poll state SHALL be persisted, so any factory instance can resume
polling after a crash or takeover and observe the same runs. The adapter
SHALL read the attempt commit from this change's concrete workspace type
— the one the engine hands to check runners — replacing the adapter-local
`GithubCheckWorkspace` stand-in and its internal downcast.
<!-- implements NFR-R2 of add-external-check-github-actions -->
<!-- implements FR26 of add-sandbox-core -->

#### Scenario: Another instance resumes mid-poll
- **WHEN** the polling instance dies and another instance resumes the task
- **THEN** the new instance polls the same attempt commit and reaches the
  same verdict with no state handed over

#### Scenario: The adapter consumes the engine workspace type
- **WHEN** the stage engine invokes the check with the workspace carrying
  the attempt commit of the round under verification
- **THEN** the adapter reads the attempt commit from that workspace and
  polls runs of exactly that commit

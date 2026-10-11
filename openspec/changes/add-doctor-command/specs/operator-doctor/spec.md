## ADDED Requirements

### Requirement: Doctor reports every readiness check in one run
`gnomish doctor [--dir=<clone>] [--online] [--json]` SHALL run every readiness check and report each as `ok`, `warn` or `fail`: the factory version and Java runtime (informational); the git version floor; the operator configuration (one line per violation); the registration of `--dir`; the pipeline law of the clone loading and validating; the presence of every secret the tracker and the configured checks declare; the presence of an agent credential when any stage is container-bound; Docker reachable when any stage is container-bound; the sandbox image present locally with a matching contract version; the guard image present locally (`warn` when absent); the agent CLI binary locatable when any stage is host-bound; and leftovers of the pre-registry folder layout (`warn`). Without `--dir` and outside a registered clone it SHALL run the host-level checks only and state that project checks were skipped and why. It SHALL exit 0 when no check failed and 1 otherwise; a `warn` never fails.
<!-- implements FR1, FR2, FR6, UX2 of add-doctor-command -->

#### Scenario: Several problems in one run
- **WHEN** `gnomish doctor --dir=~/src/widgets` runs with the sandbox image missing and the tracker token absent
- **THEN** both are reported as `fail` with their fixes in the same run, every other check is reported, and the exit code is 1

#### Scenario: Healthy setup
- **WHEN** every check passes
- **THEN** only `ok` lines and a one-line summary are printed, and the exit code is 0

#### Scenario: Outside any project
- **WHEN** `gnomish doctor` runs in a directory that is no registered clone
- **THEN** host-level checks run and the output says project checks were skipped because the directory is not registered, with the `project add` line

### Requirement: Doctor shares every decision with the startup path
Each doctor check SHALL obtain its verdict from the component the real startup path uses for the same decision, through a non-refusing assessment on which that component's refusing form is itself built. Doctor SHALL NOT start a subprocess, read an environment variable, or parse a configuration file itself, and SHALL have no side effects: no claim, branch, worktree, box, image pull or file write beyond its log. Without `--online` it SHALL make no network call; `--online` adds a read-only tracker probe with the configured credential.
<!-- implements FR3, FR4, NFR-R1, NFR-P1 of add-doctor-command -->

#### Scenario: Same verdict as startup
- **WHEN** a fixture makes `gnomish take` refuse on a check
- **THEN** `gnomish doctor` reports that check as `fail` with the same reason

#### Scenario: Offline by default
- **WHEN** `gnomish doctor` runs without `--online`
- **THEN** no network connection is opened

### Requirement: Doctor output is readable and machine-parseable
Each result SHALL be one line with status, check name, detail and — for `warn` and `fail` — the fix. Text captured from tools SHALL be rendered through the console exit. No secret value SHALL appear; a secret check names the secret and the places searched. `--json` SHALL print the same results as one JSON document whose checks carry stable ids.
<!-- implements FR5, NFR-S1, NFR-O1, UX1 of add-doctor-command -->

#### Scenario: Secret named, never shown
- **WHEN** the tracker token is present
- **THEN** the line reports it found and where, and the token's value appears nowhere in the output

#### Scenario: JSON for automation
- **WHEN** `gnomish doctor --json` runs
- **THEN** stdout is one JSON document listing every check with its id, status, detail and fix

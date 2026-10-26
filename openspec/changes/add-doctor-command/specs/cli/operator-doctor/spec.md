# cli/operator-doctor

## Purpose

The `gnomish doctor` command: every readiness check of a machine and a registered clone in one
run, each verdict obtained from the component the startup path uses for the same decision, with
no side effects, reported one line per check or as one JSON document.

## ADDED Requirements

### Requirement: Doctor reports every readiness check in one run
`gnomish doctor [--dir=<clone>] [--online] [--json]` SHALL run every readiness check in one run and report each as `ok`, `warn` or `fail`, never stopping at the first failure. It SHALL exit 0 when no check failed and 1 otherwise; a `warn` never fails.
**Implements**: FR1, FR2, FR6, UX2 of add-doctor-command

#### Scenario: Several problems in one run
- **WHEN** `gnomish doctor --dir=~/src/widgets` runs with the sandbox image missing and the tracker token absent
- **THEN** both are reported as `fail` with their fixes in the same run, every other check is reported, and the exit code is 1

#### Scenario: Every readiness check is reported
- **WHEN** `gnomish doctor --dir=<clone>` runs on a registered clone
- **THEN** it reports the factory version and Java runtime (informational), the git version
  floor, the operator configuration (one line per violation), the registration of `--dir`, the
  clone's ref backend, the pipeline law of the clone loading and validating, and the presence of
  every secret the tracker and the configured checks declare
- **AND** when any stage is container-bound it also reports the presence of an agent
  credential, Docker reachable, the sandbox image present locally with a matching contract
  version (naming the box image's git floor as not probed), and the guard image present locally
- **AND** when any stage is host-bound it also reports the agent CLI binary locatable and the
  host process-group launcher helper and the group sweep's `/bin/kill` present
- **AND** it reports leftovers of the pre-registry folder layout

#### Scenario: Absent guard image and old layout leftovers only warn
- **WHEN** the guard image is absent locally, or leftovers of the pre-registry folder layout
  exist
- **THEN** each is reported as `warn`, and neither alone makes the exit code 1

#### Scenario: Missing launcher helper
- **WHEN** a stage is host-bound and the platform's process-group launcher helper is absent
  (`setsid` on Linux, `/usr/bin/perl` on macOS)
- **THEN** the check is reported as `fail`, naming the helper and the package that provides it,
  and the exit code is 1

#### Scenario: Missing group sweep signaller
- **WHEN** a stage is host-bound and `/bin/kill` is absent
- **THEN** the check is reported as `fail`, naming `/bin/kill` and the package that provides it
  (procps or util-linux on Linux), and the exit code is 1

#### Scenario: Healthy setup
- **WHEN** every check passes
- **THEN** only `ok` lines and a one-line summary are printed, and the exit code is 0

#### Scenario: Outside any project
- **WHEN** `gnomish doctor` runs in a directory that is no registered clone
- **THEN** host-level checks run and the output says project checks were skipped because the directory is not registered, with the `project add` line

### Requirement: Doctor names the hooks-path line earlier versions left in the clone
On a registered clone, `gnomish doctor` SHALL read `core.hooksPath` from the clone's local configuration (`--local`), never through an unscoped read, which under the factory's git invocation policy reports the command-scope value `/dev/null`. When the local value ends in `/gnomish-empty-hooks` it SHALL report `warn` with the fix `git config --local --unset core.hooksPath`; otherwise it SHALL report `ok` and print no such line. It SHALL remove nothing.
**Implements**: FR8 of add-doctor-command; UX2 of own-git-invocation-policy

#### Scenario: Leftover hooks path of an earlier version
- **WHEN** `gnomish doctor --dir=<clone>` runs on a clone whose local configuration holds `core.hooksPath=<clone>/.git/gnomish-empty-hooks`
- **THEN** the check is reported as `warn` with the fix `git config --local --unset core.hooksPath`, the exit code is not made 1 by it, and the clone's configuration is unchanged

#### Scenario: An operator's own hooks path is not reported
- **WHEN** the clone's local `core.hooksPath` is unset, or holds a value that does not end in `/gnomish-empty-hooks`
- **THEN** the check is reported as `ok` and no `git config --local --unset core.hooksPath` line is printed

### Requirement: Doctor shares every decision with the startup path
Each doctor check SHALL obtain its verdict from the component the real startup path uses for the same decision, through a non-refusing assessment on which that component's refusing form is itself built. Doctor SHALL NOT start a subprocess, read an environment variable, or parse a configuration file itself, and SHALL have no side effects: no claim, branch, worktree, box, image pull or file write beyond its log.
**Implements**: FR3, FR4, NFR-R1, NFR-P1 of add-doctor-command

#### Scenario: Same verdict as startup
- **WHEN** a fixture makes `gnomish take` refuse on a check
- **THEN** `gnomish doctor` reports that check as `fail` with the same reason

#### Scenario: Offline by default
- **WHEN** `gnomish doctor` runs without `--online`
- **THEN** no network connection is opened

#### Scenario: Online adds a read-only tracker probe
- **WHEN** `gnomish doctor --online` runs against a repository that lacks the factory's labels
- **THEN** it adds one read-only tracker probe made with the configured credential — the
  adapter's own probe, for GitHub the label listing the startup provisioning makes first — and
  makes no other network call and no tracker write
- **AND** the line reports reading confirmed, the labels startup will create, and that write
  access is not verified

#### Scenario: Online probe refused
- **WHEN** the tracker answers the probe with a refusal (a 404 or a 403)
- **THEN** the line is `fail` with the cause the startup provisioning would name for the same
  status, a 404 worded as not found or not visible to this token, and a 403 quoting the
  permissions the tracker reports as accepted
- **AND** the probe is not retried on a bad credential

### Requirement: Doctor output is readable and machine-parseable
Each result SHALL be one line with status, check name, detail and — for `warn` and `fail` — the fix. Text captured from tools SHALL be rendered through the console exit. No secret value SHALL appear; a secret check names the secret and the places searched. `--json` SHALL print the same results as one JSON document whose checks carry stable ids.
**Implements**: FR5, NFR-S1, NFR-O1, UX1 of add-doctor-command

#### Scenario: Secret named, never shown
- **WHEN** the tracker token is present
- **THEN** the line reports it found and where, and the token's value appears nowhere in the output

#### Scenario: JSON for automation
- **WHEN** `gnomish doctor --json` runs
- **THEN** stdout is one JSON document listing every check with its id, status, detail and fix

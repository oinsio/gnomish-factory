# Spec Delta

## MODIFIED Requirements

### Requirement: serve command surface
`gnomish serve` SHALL continuously serve the ready queue with the scheduler
until stopped, always in git mode, supporting `--dir` and the drain flag —
and SHALL be unconditionally non-interactive: no serve path prompts on a TTY;
escalation always parks with a tracker report. Startup SHALL run label
provisioning as a binding smoke test: an unreachable repository is death on
startup with a clear error, before anything is claimed. `serve` SHALL also
accept `--dashboard` and `--dashboard-out=<path>` and read
`factory.serve.dashboard` (default `false`); the dashboard is enabled by the
flag or by the property, and `--dashboard-out` without it enabled is a usage
error.
<!-- implements FR2, FR4, FR12 of add-factory-serve -->
<!-- implements FR8 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Escalation parks without a prompt
- **WHEN** a slot's task escalates while `serve` runs with a TTY attached
- **THEN** the task parks with its report and no dialog is shown

#### Scenario: Startup smoke test
- **WHEN** `serve` starts against an unreachable repository binding
- **THEN** the process exits on startup with an error naming the binding,
  having claimed nothing

#### Scenario: Property enables the dashboard
- **WHEN** the host configuration sets `factory.serve.dashboard: true` and
  `serve` runs without `--dashboard`
- **THEN** the daemon renders the dashboard page

#### Scenario: Output path without the dashboard
- **WHEN** `serve --dashboard-out=page.html` runs with the dashboard disabled
- **THEN** the command fails with a usage error before anything is claimed

## ADDED Requirements

### Requirement: Serve renders the dashboard in-process when enabled
With the dashboard enabled, `serve` SHALL render the same page `gnomish
dashboard --watch` renders for the same instance: same blocks, cadences and
default output path, `--dashboard-out` overriding the path. It SHALL print the
page's absolute path before the first claim and record the setting in the
start anchor. A standalone `gnomish dashboard` SHALL keep working unchanged.
<!-- implements FR9, UX1, NFR-O2 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Default path
- **WHEN** `gnomish serve --dashboard` starts for project `widgets` with the
  default instance name
- **THEN** it prints the path `GNOMISH_HOME/projects/widgets/serve/default/dashboard.html`
  and the page appears there within one render cadence

#### Scenario: Standalone dashboard unaffected
- **WHEN** a daemon runs without the dashboard and an operator runs
  `gnomish dashboard --watch` in another process
- **THEN** the page renders exactly as before this change

### Requirement: The embedded dashboard never harms the daemon
A failure of the embedded dashboard of any kind, including render failures,
file write failures, tracker failures of its board reads, and its loop being
disabled, SHALL NOT stop, delay or fail task work, the feed, or the daemon's
other loops. Its board reads SHALL NOT count as failures of the daemon's
tracker.
<!-- implements NFR-R1, FR10 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Board outage is not a daemon tracker failure
- **WHEN** every board read of the embedded dashboard fails for ten minutes
  while the daemon's own tracker calls succeed
- **THEN** the snapshot's tracker `consecutiveFailures` stays 0

#### Scenario: Disabled dashboard, working daemon
- **WHEN** the embedded dashboard's loop dies often enough to be disabled
- **THEN** one ERROR says so and the daemon keeps claiming and completing tasks

### Requirement: The embedded dashboard reads the configuration serve bound
The embedded dashboard SHALL read the tracker binding and the WIP limit only
from the configuration `serve` bound at startup from origin's default branch,
never from the checkout, and SHALL add no tracker reads beyond one ready
listing and one open listing per board interval.
<!-- implements FR10, NFR-S1, NFR-P1 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Checkout differs from origin
- **WHEN** the clone's checked-out `.gnomish/config.yaml` sets `wip-limit: 3`
  while origin's default branch sets `wip-limit: 10`, and `serve --dashboard` runs
- **THEN** the page's WIP stat shows the limit 10

#### Scenario: Tracker reads bounded
- **WHEN** `serve --dashboard` runs for an hour with default cadences
- **THEN** the dashboard performs at most one `listReady` and one `listOpen`
  per board interval

### Requirement: Shutdown stops every supervised loop and renders a final page
The shutdown sequence SHALL stop the worktree cleaner, the sandbox sweep tick
and the standing reaper before it waits out the grace window, and the snapshot
writer at its final `stopped` write. With the dashboard enabled, it SHALL then
render the page once more, so the page shows the stopped state. A second pass
SHALL change nothing.
<!-- implements FR6, FR11, UX2 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Page after Ctrl-C
- **WHEN** `serve --dashboard` receives SIGINT and finishes its shutdown
- **THEN** the page file's last render shows the stopped state and reason

#### Scenario: No cleaner run during drain
- **WHEN** SIGTERM arrives and slots are still releasing within the grace window
- **THEN** no worktree cleaner or sandbox sweep run starts after the stop

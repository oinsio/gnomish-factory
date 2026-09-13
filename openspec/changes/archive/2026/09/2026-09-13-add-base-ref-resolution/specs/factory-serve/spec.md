# factory-serve — delta for add-base-ref-resolution

## ADDED Requirements

### Requirement: Serve survives base-refresh outages without charging tasks
A base-resolution infrastructure failure in a serve slot SHALL NOT kill the
slot or the daemon: the claim is released per tracker-take, the slot returns
to the feed, and running slots continue. The failure SHALL NOT be recorded as
an abort: no abort marker, no comment, no change to the task's backoff or to
the K-fuse accounting — the outage belongs to the daemon (the remote outage
gate below), not to any task. No automatic tracker escalation is posted on
these failures. The slot's outcome SHALL reach serve's outcome log as its
own typed kind, never as a free-text skip; ledger treatment is per
serve-observability (one `remoteOutage` line per closed outage, no
`taskOutcome` line for a released task).
<!-- implements FR9, G5 of add-base-ref-resolution -->

#### Scenario: Released tasks carry no abort history
- **WHEN** a slot's base refresh fails against a dead remote and the task is
  claimed again after the remote returns
- **THEN** the task's abort facts read zero, its backoff is unexpired, and
  the tracker thread holds no abort marker and no comment from the outage

#### Scenario: Recovery needs no operator action
- **WHEN** the remote returns after an outage
- **THEN** the next feed cycle claims a task, resolves and refreshes its
  base normally, and no residue of the outage (claims, statuses, comments)
  needs manual cleanup

### Requirement: Remote outage gate holds the feed off the tracker
`serve` SHALL keep one remote outage gate per remote target. A slot's
base-refresh infrastructure failure SHALL open it; the feed SHALL consult the
gate before every claim and SHALL claim nothing while it is open. While
open, the daemon SHALL probe the remote with a tracker-free reachability
check on a jittered interval that grows from the idle interval to a
configured cap; the first successful probe closes the gate and a failed
probe re-arms the next interval. Recovery SHALL be established by a probe,
never by claiming a task; the interval SHALL reset to the idle interval only
after the first successful base refresh following the close, never on the
closing probe, so a remote that answers probes but fails fetches meets a
growing pause rather than a claim-and-release cycle. Slots already working
SHALL continue under an open gate. The gate is process-local: a restart forgets it and re-learns on the
next failure.
<!-- implements FR14, NFR-R3, M4 of add-base-ref-resolution -->

#### Scenario: At most one claim per slot for the whole outage
- **WHEN** the remote is unreachable for an hour under a three-slot serve
- **THEN** the tracker sees at most three claims and three releases from the
  outage, none after the gate opened, and the daemon is still running

#### Scenario: A probe, not a claim, ends the outage
- **WHEN** the remote returns while the gate is open
- **THEN** the next probe succeeds, the gate closes, and only then does the
  feed claim a task — the first successful `ls-remote` precedes the first
  claim

#### Scenario: A flapping remote does not restart the interval
- **WHEN** the gate closes on a successful probe and the very next base
  refresh fails again before any refresh succeeded
- **THEN** the gate reopens with an interval longer than the one before the
  close, and the interval returns to idle only after a base refresh
  succeeds

#### Scenario: Probe intervals grow with jitter and stop at the cap
- **WHEN** eight consecutive probes fail on virtual time
- **THEN** the intervals between them grow from the idle interval toward the
  cap, no two are identical, and none exceeds the cap

### Requirement: Gate transitions are the outage's log and snapshot signal
Opening the gate SHALL log one WARN with an operator-event code naming the
remote and the cause; closing SHALL log one INFO recovery line with the
outage duration and probe count; failures and probes in between SHALL be
DEBUG with a periodic roll-up. A gate open longer than a configured duration
SHALL log ERROR once with its own event code. The serve snapshot SHALL carry
a `remote` section (state, open since, last error, next probe time,
consecutive failures) beside the existing `tracker` section.
<!-- implements NFR-O1, NFR-O3, UX6 of add-base-ref-resolution -->

#### Scenario: One WARN, one recovery line, roll-ups between
- **WHEN** the remote is unreachable for an hour and then returns
- **THEN** the log carries exactly one WARN on open and one INFO on close,
  every probe in between is DEBUG or a roll-up, and the ERROR line appears
  once only if the outage exceeded the sustained-open duration

#### Scenario: Blocked on the remote is readable without logs
- **WHEN** an operator opens the dashboard during an outage
- **THEN** the `remote` section shows the gate open, since when, the last
  error, and the next probe time

### Requirement: Serve validates the default branch at startup and binds law per task
At startup the daemon SHALL load and validate the full definition from the
refreshed repository default branch and exit on a load error, exactly as
before; that definition supplies the trusted configuration tier and the
`board`/`dashboard` view. Each slot SHALL bind its task's law from the task's
law commit after base resolution. A task whose base fails to load SHALL park
with a configuration report and SHALL NOT be released back to Ready — the
failure is deterministic and a retry loop would only repeat it; the slot
returns to the feed and other slots are unaffected.
<!-- implements FR13, UX5 of add-base-ref-resolution -->

#### Scenario: Two bases, one broken
- **WHEN** serve holds tasks resolving to `develop` (valid `.gnomish/`) and
  `release/1.18` (invalid `.gnomish/`)
- **THEN** the `develop` task runs, the `release/1.18` task parks with the
  located errors and the base ref in its report, and the daemon keeps running

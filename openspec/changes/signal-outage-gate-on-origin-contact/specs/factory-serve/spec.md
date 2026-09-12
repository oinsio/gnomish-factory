# factory-serve — delta for signal-outage-gate-on-origin-contact

Layered on "Remote outage gate holds the feed off the tracker" as added by
`add-base-ref-resolution` (sequenced before this change): the requirement
below is written over that delta's text, not over `openspec/specs/`, so
syncing in that order merges cleanly. If this change syncs first, the later
sync must merge by hand — the "that contacted origin" qualification and the
last scenario are what must survive.

## MODIFIED Requirements

### Requirement: Remote outage gate holds the feed off the tracker
`serve` SHALL keep one remote outage gate per remote target. A slot's
base-refresh infrastructure failure SHALL open it; the feed SHALL consult the
gate before every claim and SHALL claim nothing while it is open. While
open, the daemon SHALL probe the remote with a tracker-free reachability
check on a jittered interval that grows from the idle interval to a
configured cap; the first successful probe closes the gate and a failed
probe re-arms the next interval. Recovery SHALL be established by a probe,
never by claiming a task; the interval SHALL reset to the idle interval only
after the first successful base refresh following the close that contacted
origin, never on the closing probe and never on a success the clone served
from its own object store, so a remote that answers probes but fails fetches
meets a growing pause rather than a claim-and-release cycle. The remote's
last successful contact, as the gate reports it, SHALL advance only on such
a refresh or on a successful probe. Both slot-side signals are taken from
the base read at the instant it returns, never inferred from the slot's
terminal result. Slots already working SHALL continue under an open gate.
The gate is process-local: a restart forgets it and re-learns on the next
failure.
<!-- implements FR14, NFR-R3, M4 of add-base-ref-resolution -->
<!-- implements FR2, FR3, NFR-O1 of signal-outage-gate-on-origin-contact -->

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

#### Scenario: A clone-served success after a close keeps the grown interval
- **WHEN** the gate closes on a successful probe, the next claim's base is a
  commit SHA the clone already holds, and the base refresh after that fails
  against the remote
- **THEN** the gate reopens with an interval longer than the one before the
  close, the remote's last successful contact still names the closing probe,
  and only a later refresh that contacted origin returns the interval to
  idle

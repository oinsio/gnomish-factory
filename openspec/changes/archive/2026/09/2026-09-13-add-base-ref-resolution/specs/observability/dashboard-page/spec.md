# observability/dashboard-page — delta for add-base-ref-resolution

## ADDED Requirements

### Requirement: Open remote outage gate is an alert condition in the status card
The status card SHALL flag an open remote outage gate as an alarm line
naming the target, how long the gate has been open, the last error, and the
next probe time — the same alarm-palette line the tracker-failure and
hygiene conditions use, rendered in the status card and not in a block of
its own. A gate open longer than the sustained-open duration SHALL escalate
the line's wording to "blocked on the remote" from "remote outage, probing".
A snapshot without a `remote` section SHALL raise no line.
<!-- implements NFR-O3, UX6 of add-base-ref-resolution -->

#### Scenario: Blocked on the remote reads from one place
- **WHEN** the dashboard renders a snapshot whose `remote[origin].state` is
  `open`
- **THEN** the status card carries one alarm line stating the target, the
  open duration, the last error, and the next probe time

#### Scenario: Closed gate is quiet
- **WHEN** the snapshot's `remote` section shows every target `closed`
- **THEN** no remote alarm line renders and the card shows the last
  successful remote contact time

### Requirement: History section counts closed outages per day
The history section SHALL aggregate `remoteOutage` ledger lines into an
outages-per-day count with the day's total blocked duration, rendered as a
compact line under the outcomes bars — not as a bar of its own, since an
outage is not an outcome. Days with no outage SHALL render nothing for it.
<!-- implements NFR-O3 of add-base-ref-resolution -->

#### Scenario: Overnight outage in the morning view
- **WHEN** one `remoteOutage` line of three hours landed last night
- **THEN** that day's history shows one outage totalling three hours beside
  its outcome bar, and no other day mentions outages

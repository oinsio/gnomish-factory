# dashboard-page — delta

## ADDED Requirements

### Requirement: Sandbox hygiene section
The dashboard SHALL render a sandbox hygiene section composed from the snapshot's sweeper vitals and the ledger's sweep lines: the last tick's breakdown in four groups over the verdict categories — cleaned (disposed-aged + disposed-reconstructible), stopped (stopped-orphan), checked-and-untouched (checked-alive + kept-under-threshold), skipped-without-verdict (skipped-no-verdict) — the kept-environment inventory with ages and time-to-reap, and a table of recent stop/dispose actions with task, reason, and age. The section SHALL degrade independently like every other section when its inputs are missing or stale.
<!-- implements NFR-O3, UX1 of add-serve-sandbox-lifecycle -->

#### Scenario: One glance answers the four questions
- **WHEN** the operator opens the dashboard after a daemon has run overnight
- **THEN** the hygiene section shows what was disposed, what was stopped, how many objects were verified alive or kept, and whether any tick skipped without a verdict

### Requirement: Sandbox hygiene alerts
The dashboard's alert conditions SHALL include: the sweep has not completed a tick for longer than a threshold; consecutive skipped-no-verdict ticks (cleanup silently stalled); and any stopped-orphan event with ownership mode `tracked` in the rendered window — surfaced as a symptom of a dead or hung instance, naming the object and task, not as routine cleanup. A stopped-orphan event with mode `manual` is a routine age-policy stop: it appears in the breakdown and the actions table but SHALL NOT raise the dead-instance alert.
<!-- implements NFR-O3, UX2 of add-serve-sandbox-lifecycle -->

#### Scenario: Silent stall becomes loud
- **WHEN** three consecutive ticks report skipped-no-verdict
- **THEN** the dashboard raises an alert stating cleanup is not actually running, with the failing verdict source named

#### Scenario: Zombie stop reads as an incident
- **WHEN** a tick stopped an abandoned running box
- **THEN** the alert names the box and its task and reads as "an instance died or hung", distinct in presentation from aged disposals

# Delta spec: tracker-port (fix-abort-progress-reset)

## MODIFIED Requirements

### Requirement: Single Tracker port speaking the factory's language
The application layer SHALL expose one `Tracker` port with exactly the v1
operations `listReady`, `fetchTask`, `collectDecisions`, `claim`, `release`,
`park`, `finish`, `recordAbort`, `acknowledgeDecision`, `postNote`,
`recordProgress`. The port vocabulary SHALL be the factory's (tasks, states,
decisions, abort facts, durable progress); all tracker-specific mapping SHALL be
confined to adapters. Report rendering (domain report → text) SHALL happen in
core: the port accepts finished text plus structural fields, never engine domain
models.
<!-- implements FR1 of add-tracker-port -->
<!-- implements FR1 of fix-abort-progress-reset -->

#### Scenario: Core compiles against the port alone
- **WHEN** the take runner drives a full task lifecycle
- **THEN** every tracker interaction goes through the `Tracker` port, and no core
  class references an adapter type or a tracker-specific concept (label, issue,
  transition id)

#### Scenario: Adapter receives rendered text
- **WHEN** the factory parks a task with an escalation report
- **THEN** the adapter receives the report as finished text plus structural fields,
  not an engine report object

#### Scenario: recordProgress leaves logical state untouched
- **WHEN** the factory calls `recordProgress` on a task it holds as `Working`
- **THEN** the task stays `Working` with the same claim holder, and only its
  abort facts are affected (the durable-progress marker is recorded)

### Requirement: Abort facts round-trip across instances
`recordAbort` SHALL, as one operation, persist a structural abort marker (cause,
instance, time) and return the task to `Ready`. `recordProgress` SHALL persist a
structural durable-progress marker without changing logical state. Abort facts
SHALL be reconstructable by any instance from the tracker alone: after
`recordAbort`, a `fetchTask` or `listReady` from a different instance SHALL
observe the updated count and last-abort time; after `recordProgress`, a
different instance SHALL observe the count reset. The count semantics is "aborts
recorded strictly after the last durable-progress marker on the task";
adapters report facts and SHALL NOT apply backoff or fuse policy.
<!-- implements FR14 of add-tracker-port -->
<!-- implements FR1 of fix-abort-progress-reset -->
<!-- implements FR3 of fix-abort-progress-reset -->
<!-- implements FR4 of fix-abort-progress-reset -->

#### Scenario: Fresh instance sees abort history
- **WHEN** instance A records an abort and instance B calls `fetchTask`
- **THEN** B observes abort count incremented and the last abort time from A's
  marker

#### Scenario: Progress reset round-trips across instances
- **WHEN** instance A records two aborts, then a durable progress, and instance
  B calls `fetchTask`
- **THEN** B observes an abort count of zero (the aborts precede the progress)

## ADDED Requirements

### Requirement: Abort count resets on durable progress
Every adapter SHALL reconstruct `AbortFacts.count` as the number of abort
markers recorded strictly after the latest durable-progress marker; abort
markers at or before that marker SHALL NOT be counted. When no durable-progress
marker exists on the task, the count SHALL fall back to the existing
claim-streak reconstruction. This holds identically for the `listReady` feed and
for `fetchTask`.
<!-- implements FR3 of fix-abort-progress-reset -->

#### Scenario: Progress before an abort resets the count to one
- **WHEN** a claim is followed by a durable progress and then a single abort
- **THEN** the reconstructed abort count is one

#### Scenario: Aborts before progress are excluded
- **WHEN** two aborts are followed by a durable progress and then one abort
- **THEN** the reconstructed abort count is one, counting only the abort after
  the progress

#### Scenario: No progress marker preserves the historical count
- **WHEN** a claim aborts twice with no durable progress recorded
- **THEN** the reconstructed abort count is two (the pre-progress fallback is
  unchanged)

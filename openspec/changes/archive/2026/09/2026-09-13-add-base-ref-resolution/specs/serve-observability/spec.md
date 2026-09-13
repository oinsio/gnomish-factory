# serve-observability — delta for add-base-ref-resolution

## ADDED Requirements

### Requirement: Remote section exposes the outage gate at a glance
The snapshot SHALL carry a `remote` section beside `tracker`, one entry per
remote target, reporting the remote outage gate: `state` ∈ `closed | open`,
`openSince` (null while closed), `lastError` (the scrubbed cause of the last
failed fetch or probe, null while closed), `nextProbeAt` (null while
closed), `consecutiveFailures` (failed fetches and probes since the gate
opened; zero while closed), and `lastSuccessAt` (the last successful fetch
or probe). The section SHALL be updated on every gate transition and every
probe, with a transition triggering the immediate snapshot write the
lifecycle transitions already use. A reader SHALL treat an absent `remote`
section as "no gate known" — the section is additive, and `version` stays
`1`.
<!-- implements NFR-O3, UX6 of add-base-ref-resolution -->

#### Scenario: Blocked on the remote is a field
- **WHEN** a slot's base refresh fails against an unreachable remote and the
  gate opens
- **THEN** the next snapshot shows `remote[target].state: open`, `openSince`
  set, `lastError` naming the cause without credentials, and `nextProbeAt`
  in the future

#### Scenario: Probes advance the section without claims
- **WHEN** three probes fail while the gate is open
- **THEN** `consecutiveFailures` reads three, `nextProbeAt` moves out on
  each failure, `lastSuccessAt` does not advance, and the tracker section is
  unaffected because no tracker call was made

#### Scenario: Recovery clears the section
- **WHEN** a probe succeeds
- **THEN** the immediately written snapshot shows `state: closed`,
  `lastSuccessAt` at the probe time, and `openSince`, `lastError`,
  `nextProbeAt` null

#### Scenario: Old snapshots stay readable
- **WHEN** a dashboard reads a snapshot written before this change, with no
  `remote` section
- **THEN** it renders without error and shows no remote gate

### Requirement: remoteOutage lines record each closed outage in the ledger
A `remoteOutage` line SHALL be appended when the gate closes — one line per
outage, never per failure or per probe — with `target`, `openedAt`,
`closedAt`, `durationMillis`, `probeCount`, `releasedClaims` (claims the
outage released before the gate opened), and `lastError`. A gate still open
at daemon stop SHALL NOT produce a line (the snapshot's final stopped record
carries the open state); the next daemon starts with a closed gate and
knows nothing of it. Released-on-outage slot results SHALL NOT produce
`taskOutcome` lines: like `Skipped`, no engine ran and nothing was spent.
<!-- implements NFR-O1, NFR-O3 of add-base-ref-resolution -->

#### Scenario: One line per outage
- **WHEN** the remote is dead for an hour with forty failed probes and then
  returns
- **THEN** exactly one `remoteOutage` line is appended, with `probeCount`
  forty and a `durationMillis` near an hour

#### Scenario: Released task leaves no outcome line
- **WHEN** a slot releases its claim because the base refresh hit the dead
  remote
- **THEN** no `taskOutcome` line is written for that task, and the slot's
  outcome log carries the typed released-on-outage line

## MODIFIED Requirements

### Requirement: Snapshot content is limited to instance, lifecycle, feed, slots, vitals, tracker
The snapshot SHALL contain the top-level scalars `version`, `writtenAt`,
`intervalSeconds` and exactly the sections `instance` (full instance id,
host, factory version), `lifecycle`, `feed`, `slots`, `vitals`, `tracker`,
and `remote`. It SHALL NOT contain ready-queue depth, per-task detail, or
configuration beyond `wipLimit`.
<!-- implements FR3 of add-serve-observability -->
<!-- implements NFR-O3 of add-base-ref-resolution -->

#### Scenario: Pointers instead of payloads
- **WHEN** an operator needs per-task detail for a slot entry
- **THEN** the snapshot offers only the task id (canon: `gnomish status <id>`
  / the task branch), not stage artifacts or logs

#### Scenario: Remote section names targets, not tasks
- **WHEN** the gate is open and four tasks were released by the outage
- **THEN** the `remote` section names the target and the count of released
  claims, never the task ids — those are in the tracker, Ready

## MODIFIED Requirements

### Requirement: Snapshot content is limited to instance, lifecycle, feed, slots, vitals, tracker
The snapshot SHALL contain the top-level scalars `version`, `writtenAt`,
`intervalSeconds` and exactly the sections `instance` (full instance id,
host, factory version), `lifecycle`, `feed`, `slots`, `vitals`, `tracker`,
and `conditions`. It SHALL NOT contain ready-queue depth, per-task detail, or
configuration beyond `wipLimit`.
**Implements**: FR3 of add-serve-observability; NFR-O3 of add-base-ref-resolution; NFR-O2 of add-sandbox-base-image
**Notes**: `conditions` is the former `remote` section generalized: one entry per daemon condition, the remote's entry first; a reader accepts a snapshot with either spelling (design D19, Sync surfaces).

#### Scenario: Pointers instead of payloads
- **WHEN** an operator needs per-task detail for a slot entry
- **THEN** the snapshot offers only the task id (canon: `gnomish status <id>`
  / the task branch), not stage artifacts or logs

#### Scenario: Remote section names targets, not tasks
- **WHEN** the gate is open and four tasks were released by the outage
- **THEN** the `remote` section shows the target and its gate state, never a
  task id — the released count is on the `remoteOutage` ledger line

#### Scenario: A conditions entry names the precondition, not a task
- **WHEN** the clone ref-format condition is open and two tasks were released by it
- **THEN** the `conditions` section names the condition and its state, never a task id — the released count is on the `daemonCondition` ledger line

### Requirement: taskOutcome lines for terminal results carrying a final state
A `taskOutcome` line SHALL be appended for each terminal slot result with a
final state — `outcome` ∈ `delivered | awaitingHuman | aborted | revoked`,
`parkReason` only with `awaitingHuman` — with `taskId`, `stage` (null at
pipeline end), `attemptsUsed`, `startedAt`, `finishedAt`, `wallMillis`,
`tokensByModel`. A slot whose run ends by an unclassified throwable SHALL
append its line with `outcome: aborted` from the slot boundary itself.
`EmptyQueue`, `Skipped` and precondition-released results SHALL NOT produce lines.
**Implements**: FR11 of add-serve-observability; FR18 of add-sandbox-base-image
**Notes**: A released task writes no line for the reason a `Skipped` one writes none: no engine ran and nothing was spent; its episode is the `daemonCondition` line's (design D20).

#### Scenario: Delivered task leaves one line
- **WHEN** a slot delivers a task
- **THEN** one `taskOutcome` line with `outcome: delivered` and its token
  totals is appended

#### Scenario: Lost claim race leaves no line
- **WHEN** a slot ends with `Skipped`
- **THEN** no ledger line is written (no engine run — no spend)

#### Scenario: A crashed slot leaves one line
- **WHEN** a slot's run dies with a throwable no arm classifies
- **THEN** one `taskOutcome` line with `outcome: aborted`, a null `stage`, zero tokens and the slot's wall time is appended before the uncaught-crash event is logged, and the dashboard counts the slot among the aborted

### Requirement: Remote section exposes the outage gate at a glance
The snapshot SHALL carry a `conditions` section beside `tracker`, one entry per
daemon condition — the remote (per remote target), the container runtime, the
clone's ref format, the image contract, the box's git floor — reporting
`state` ∈ `closed | open`, `openSince`, `lastError` (the scrubbed cause of the
last failed check or probe), `nextProbeAt`, `consecutiveFailures` (failed
checks and probes since the condition opened), and `lastSuccessAt`.
**Implements**: NFR-O3, UX6 of add-base-ref-resolution; NFR-O2, UX4 of add-sandbox-base-image
**Notes**: The remote's entry carries exactly the fields the `remote` section carried; the scenarios below that name `remote` read the remote's entry of `conditions`.

#### Scenario: Blocked on the remote is a field
- **WHEN** a slot's base refresh fails against an unreachable remote and the
  gate opens
- **THEN** the next snapshot shows `remote[target].state: open`, `openSince`
  set, `lastError` naming the cause without credentials, and `nextProbeAt`
  in the future

#### Scenario: Probes advance the section without claims
- **WHEN** three probes fail while the gate is open
- **THEN** `consecutiveFailures` reads four (the failure that opened the
  gate and the three probes), `nextProbeAt` moves out on
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

#### Scenario: A closed gate counts no failures
- **WHEN** the gate closes on a successful probe
- **THEN** the snapshot's `consecutiveFailures` for that target reads zero

#### Scenario: A gate transition writes the snapshot at once
- **WHEN** the gate opens, or closes
- **THEN** a snapshot is written immediately, through the same trigger the
  lifecycle transitions use, without waiting for the write timer

#### Scenario: The remote section keeps version 1
- **WHEN** a snapshot carries a `remote` section
- **THEN** its `version` is still `1`

#### Scenario: A snapshot with only the remote section is read as one condition
- **WHEN** a dashboard reads a snapshot written before the conditions section, carrying `remote` alone
- **THEN** it renders the remote's entry as the one condition and shows the others closed

#### Scenario: Every condition kind round-trips the snapshot
- **WHEN** a snapshot is written with every condition kind in every state
- **THEN** the reader returns each kind and state as written, and an unknown kind token is kept as unknown rather than failing the read

### Requirement: remoteOutage lines record each closed outage in the ledger
A `daemonCondition` line SHALL be appended when a condition closes — one line
per episode, never per failure or per probe — with `condition` (the kind;
`remote` carries `target`), `openedAt`, `closedAt`, `durationMillis`,
`probeCount`, `releasedClaims` (slot permits the open condition abandoned
before any claim was attempted) and `lastError`. The remote's line is the
former `remoteOutage` line; a reader SHALL accept both spellings.
**Implements**: NFR-O1, NFR-O3 of add-base-ref-resolution; NFR-O2 of add-sandbox-base-image
**Notes**: A released slot result writes no `taskOutcome` line for the reason a `Skipped` one writes none: no engine ran and nothing was spent.

#### Scenario: One line per outage
- **WHEN** the remote is dead for an hour with forty failed probes and then
  returns
- **THEN** exactly one `daemonCondition` line with `condition: remote` is appended, with `probeCount`
  forty and a `durationMillis` near an hour

#### Scenario: Released task leaves no outcome line
- **WHEN** a slot releases its claim because the base refresh hit the dead
  remote
- **THEN** no `taskOutcome` line is written for that task, and the slot's
  outcome log carries the typed released-on-outage line

#### Scenario: An outage open at stop writes no line
- **WHEN** the daemon stops while the gate is still open
- **THEN** no `daemonCondition` line is appended, the final stopped snapshot
  shows the gate open, and the next daemon starts with a closed gate that
  knows nothing of that outage

#### Scenario: A precondition episode is one line naming its condition
- **WHEN** the box-git-floor condition opens, two slots release on it, and it closes after six probes
- **THEN** exactly one `daemonCondition` line with `condition: boxGitFloor`, `releasedClaims` two and `probeCount` six is appended, and no `taskOutcome` line for the released tasks

#### Scenario: Old remoteOutage lines still aggregate
- **WHEN** the dashboard aggregates a ledger written before this change, carrying `remoteOutage` lines
- **THEN** each is counted as a closed remote episode, exactly as a `daemonCondition` line with `condition: remote` is

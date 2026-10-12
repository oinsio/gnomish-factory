# git/git-task-persistence — delta for define-executor-contract

## ADDED Requirements

### Requirement: Round commit subject is rendered from the record
The subject of a round commit (`gnomish: round <stage>#<round>`) SHALL be rendered from the
recorded round — its `stage` and `round` — by the one service-message owner; no writer SHALL
spell the stage into the subject from a second source.
**Implements**: FR14 of define-executor-contract

#### Scenario: Subject and record agree by construction
- **WHEN** a round is persisted
- **THEN** the commit subject's stage and round equal the recorded round's `stage` and `round`

### Requirement: Host rounds mint a round token
A host-mode round SHALL open with a round token minted from the task branch's tip at that
moment through the same parse container-mode rounds use; the run's `CurrentRound` SHALL hold it
and the round's record SHALL take it from there. No later round of the task repeats it: every
round lands at least its record commit and every reset rides a lifecycle commit of its own.
**Implements**: FR21 of define-executor-contract

#### Scenario: Two host rounds carry distinct tokens
- **WHEN** a host-mode task runs two rounds of one stage
- **THEN** each recorded round carries the tip it opened on and the two tokens differ

### Requirement: Attempt records carry identity and participant usage
Each recorded attempt in `state.json` SHALL carry its identity — `taskId`, `stage`,
`roundToken` — and one `usage` list of participant entries (`role`, `executor`, `vote` for
`verdict`, `provenance`, optional `wallMillis` and `byTool`, `byModel` keyed by resolved model
id with `input`, `output`, `cacheCreation`, `cacheRead` and optional `costUsd`). `totals` SHALL
be the usage snapshot (`byRole` → model → the four counts, `cost {priced, unpricedTokens,
complete}`, `rounds`, `through`).
**Implements**: FR14, FR15, FR16, FR17 of define-executor-contract; FR4 of tighten-spec-contract
**Notes**: The participant list and the snapshot are pre-release amendments under version 1, like the gate position and the stop; these are the `attempts[]` and `totals` inner forms the field list of "State directory with one writer per file" refers to.

#### Scenario: An attempt record carries its identity
- **WHEN** a round is recorded in `state.json`
- **THEN** its attempt carries the task's `taskId`, the round's `stage` and the `roundToken` the round opened on

#### Scenario: No per-kind usage field is written
- **WHEN** a round with an executor participant and judge votes is recorded
- **THEN** the attempt carries one `usage` list holding every participant, and no `executorUsage` or `judgeUsage` field

#### Scenario: State files and status share one usage vocabulary
- **WHEN** a usage entry is written to `state.json` and rendered in the status report
- **THEN** both spell it through the one shared usage wire vocabulary, and the git adapter uses no status-report DTO

## MODIFIED Requirements

Layered on `State-file JSON contract v1` as modified by `tighten-spec-contract` (sequenced
before this change): that delta cut the body and added the notes and the scenarios "An attempt
without a stop reads as none" and "Unknown version refuses the inspection commands"; this text
adds the participant-usage round-trips (the record's fields are the ADDED requirement "Attempt
records carry identity and participant usage"), and both must survive the later sync.

### Requirement: State-file JSON contract v1
`task.json` and `state.json` SHALL carry `"version": 1` and follow status-report v1 conventions (camelCase, ISO-8601 UTC, millisecond durations, sealed types via `"type"`). Readers SHALL ignore unknown fields; an unknown version SHALL refuse resume and the inspection commands (`status`/`usage`) alike, with a clear error naming the file and the unsupported version. Status-report DTOs SHALL NOT be reused.
**Implements**: FR10 of make-checkpoint-gate-durable; FR4 of add-git-workflow; FR14, FR15, FR16, FR17 of define-executor-contract
**Notes**: `awaitingApproval` and the attempt `stop` are pre-release amendments under version 1: no released branch carries either token, and an older build reading a newer tip fails closed as an unreadable envelope.
**Notes**: The equivalence with the live report is anchored by `status-report-v1.reference.json`.

#### Scenario: Unknown version refuses resume
- **WHEN** `state.json` carries `"version": 2`
- **THEN** resume stops with an error naming the file and the unsupported version

#### Scenario: Every position and stop variant round-trips
- **WHEN** each `Position` variant and each attempt stop variant is written to `state.json` and read back
- **THEN** the value read equals the value written, for every variant the sealed hierarchies permit, with no hand-listed subset

#### Scenario: Equivalence with the live report
- **WHEN** the same task history is rendered from events and from the persisted files
- **THEN** the two StatusReports are equivalent per the reference contract

#### Scenario: An attempt without a stop reads as none
- **WHEN** an attempt record in `state.json` has no `stop` field
- **THEN** it reads with stop `none`

#### Scenario: Unknown version refuses the inspection commands
- **WHEN** `status` or `usage` reads a `task.json` or `state.json` carrying an unsupported version
- **THEN** the command refuses with an error naming the file and the unsupported version

#### Scenario: Every participant entry round-trips
- **WHEN** a round with a `transform` entry carrying cost, a `verdict` entry without cost and an `ABSENT` entry is written and read back
- **THEN** the read record equals the written one, the absent cost stays absent and the `ABSENT` entry's model map stays empty

#### Scenario: The snapshot round-trips with its position
- **WHEN** a state whose `totals` carries `rounds`, `through` and an incomplete cost is written and read back
- **THEN** the read snapshot equals the written one

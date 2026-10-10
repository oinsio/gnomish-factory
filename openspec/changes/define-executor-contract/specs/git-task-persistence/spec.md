# git-task-persistence — delta for define-executor-contract

## ADDED Requirements

### Requirement: Round commit subject is rendered from the record
The subject of a round commit (`gnomish: round <stage>#<round>`) SHALL be rendered from the
recorded round — its `stage` and `round` — by the one service-message owner; no writer SHALL
spell the stage into the subject from a second source.
<!-- implements FR14 of define-executor-contract -->

#### Scenario: Subject and record agree by construction
- **WHEN** a round is persisted
- **THEN** the commit subject's stage and round equal the recorded round's `stage` and `round`

### Requirement: Host rounds mint a round token
A host-mode round SHALL open with a round token minted from the task branch's tip at that
moment through the same parse container-mode rounds use; the run's `CurrentRound` SHALL hold it
and the round's record SHALL take it from there. No later round of the task repeats it: every
round lands at least its record commit and every reset rides a lifecycle commit of its own.
<!-- implements FR21 of define-executor-contract -->

#### Scenario: Two host rounds carry distinct tokens
- **WHEN** a host-mode task runs two rounds of one stage
- **THEN** each recorded round carries the tip it opened on and the two tokens differ

## MODIFIED Requirements

### Requirement: State-file JSON contract v1
`task.json` and `state.json` SHALL carry `"version": 1` and follow status-report v1 conventions (camelCase, ISO-8601 UTC, millisecond durations, sealed types via `"type"`). `state.json`'s position SHALL be `atStage(stage)` | `awaitingApproval(stage)` | `pipelineEnd`; each recorded attempt SHALL carry its identity — `taskId`, `stage`, `roundToken` — and a `stop` object — `none` | `decisionNeeded(question, options)` | `cannotVerify(check, reason, details)` — with the field absent read as `none`; each recorded attempt SHALL carry one `usage` list of participant entries (`role`, `executor`, `vote` for `verdict`, `provenance`, optional `wallMillis` and `byTool`, `byModel` keyed by resolved model id with `input`, `output`, `cacheCreation`, `cacheRead` and optional `costUsd`) and no `executorUsage` or `judgeUsage` field; `totals` SHALL be the usage snapshot (`byRole` → model → the four counts, `cost {priced, unpricedTokens, complete}`, `rounds`, `through`). The participant list and the snapshot are pre-release amendments under version 1, like the gate position and the stop: no released branch carries the former shape, and a build older than this amendment reading a newer tip fails closed as an unreadable envelope. Readers SHALL ignore unknown fields; an unknown version SHALL refuse resume and the inspection commands (`status`/`usage`) alike, with a clear error naming the file and the unsupported version. Status-report DTOs SHALL NOT be reused in the git adapter; both documents SHALL be produced from one shared usage wire vocabulary, and a contract test SHALL hold the StatusReport rendered from state files equivalent to one rendered from live events, anchored by `status-report-v1.reference.json`. The field list of "State directory with one writer per file" reads its `attempts[]` and `totals` inner forms from this requirement.
<!-- implements FR10 of make-checkpoint-gate-durable -->
<!-- implements FR4 of add-git-workflow -->
<!-- implements FR14, FR15, FR16, FR17 of define-executor-contract -->

#### Scenario: Unknown version refuses resume
- **WHEN** `state.json` carries `"version": 2`
- **THEN** resume stops with an error naming the file and the unsupported version

#### Scenario: Every position and stop variant round-trips
- **WHEN** a state with each position variant and each stop variant is written and read back
- **THEN** the read state equals the written one

#### Scenario: Equivalence with the live report
- **WHEN** the same task history is rendered from events and from the persisted files
- **THEN** the two StatusReports are equivalent per the reference contract

#### Scenario: Every participant entry round-trips
- **WHEN** a round with a `transform` entry carrying cost, a `verdict` entry without cost and an `ABSENT` entry is written and read back
- **THEN** the read record equals the written one, the absent cost stays absent and the `ABSENT` entry's model map stays empty

#### Scenario: The snapshot round-trips with its position
- **WHEN** a state whose `totals` carries `rounds`, `through` and an incomplete cost is written and read back
- **THEN** the read snapshot equals the written one

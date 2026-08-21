# git-task-persistence — delta

## MODIFIED Requirements

### Requirement: State directory with one writer per file
`.gnomish-task/` at the working-copy root SHALL hold exactly: `task.json` (written only by `TaskRepository`: version, taskId, title, body, createdAt, baseCommit, decisions[] {text, author?, stage?, at?}, outcome — null | completed | paused{passedStage} | escalated{report} | aborted{failedAt, cause} — and lastEscalation, whose `cannotExecute` kind additionally carries the denials of the round that could not execute), `state.json` (written only by the git `AttemptPersistence`: version, position, attemptsUsed, attempts[] {round, result, startedAt, checks[], denials[], executorUsage, judgeUsage}, totals — inner forms as in status-report v1), `attempts/<stage>/<round>/trace.jsonl` (one JSON line per tool call; the round is identified by the file path), and — in git modes — `decisions/<stage>-a<attempt>.json` (written only by the gnome; the single gnome-writable path under `.gnomish-task/`, per the decision-file protocol).
<!-- implements FR3 of add-git-workflow -->
<!-- implements FR23 of add-sandbox-core -->
<!-- implements FR4 of fix-denial-report-attachment -->
<!-- implements FR2 of fix-denial-attribution-durability -->

#### Scenario: History of past stages lives in git
- **WHEN** the task advances to the next stage
- **THEN** `state.json` contains only the current stage's attempts; earlier rounds remain in the file's git history

#### Scenario: Decision file keeps the one-writer rule
- **WHEN** a round leaves a decision request in `.gnomish-task/decisions/`
- **THEN** the gnome is that file's only writer and every other `.gnomish-task/` path keeps its single factory-side writer

### Requirement: Denial cursor in the state file
`state.json` SHALL carry the environment's denial read position at commit time — the opaque position paired with the identity of the denial source it was read from — so an instance resuming the task continues the denial delta from the newest source-matching committed position at the branch tip (`state.json`'s attempt-side cursor or `task.json`'s escalation-side cursor, whichever is newer), instead of re-reading everything the source still holds. The field is environment bookkeeping, not task state: it SHALL NOT affect the position, attempts, or usage a reader reconstructs. It is additive under contract v1: an absent field means "no cursor to resume from", and a writer with no denial source records none.
<!-- implements FR5 of fix-denial-report-attachment -->
<!-- implements FR3, FR5 of fix-denial-attribution-durability -->

#### Scenario: The cursor is committed with the attempt it delimits
- **WHEN** a sandboxed round is committed to the task branch
- **THEN** `state.json` records the environment's denial read position and the identity of the source it was read from

#### Scenario: Resume continues the delta instead of replaying it
- **WHEN** an instance resumes a task whose recorded cursor names the denial source it reattaches to
- **THEN** the first round after resume reports only its own denials, and earlier rounds' denials — already recorded in their own attempts — are not attached to it again

#### Scenario: A state file written before the cursor existed stays readable
- **WHEN** a state file with no cursor field is read
- **THEN** it parses under contract v1 and the run reads its denial source from the beginning

## ADDED Requirements

### Requirement: Escalation denials in task.json
A `cannotExecute` escalation recorded in `task.json` SHALL carry the denials read from the environment of the round that could not execute, using the same finding shape as check findings and attempt denials. The field is additive under contract v1: it is present as an empty array when the failed round recorded no denial, and documents written before this addition SHALL read it as empty. The denials SHALL NOT appear in the attempt history — the round they belong to was never recorded as an attempt — and SHALL influence no derived field.
<!-- implements FR2, NFR-S1 of fix-denial-attribution-durability -->

#### Scenario: A parked task keeps the hung round's denial
- **WHEN** a task is parked after a round was killed on its round timeout having attempted a denied egress request
- **THEN** `task.json` carries that denial under `lastEscalation`, while `state.json`'s attempt history and `attemptsUsed` are unchanged

#### Scenario: Pre-existing task files stay readable
- **WHEN** a `task.json` written before this contract addition is read
- **THEN** it parses under contract v1 with the escalation's denials read as empty

#### Scenario: Escalation denials survive the resume
- **WHEN** the parked task is resumed and the escalation is superseded by later work
- **THEN** the recorded escalation and its denials remain readable in the file's git history

### Requirement: Denial cursor rides the escalation write
`task.json` SHALL carry the environment's denial read position as it stood after an escalation's denials were drained — the same opaque-position-plus-source-identity shape `state.json` records with an attempt — written in the same write as the escalation it belongs to, so the durable position can lag the record carrying its denials but never lead it. Like `state.json`'s cursor, the field is environment bookkeeping, not task state: it SHALL influence no reconstructed field and never appear in `status.json`. It is additive under contract v1: absent means "no cursor to resume from". Writing it is best-effort: an environment that cannot answer its cursor at park time writes none and never fails the park. An instance resuming the task SHALL be offered the newest source-matching committed position across `state.json` and `task.json` at the branch tip.
<!-- implements FR3, FR5, NFR-R1 of fix-denial-attribution-durability -->

#### Scenario: The drained position is committed with the escalation
- **WHEN** a round dies before its close, its denials are drained onto a `cannotExecute` escalation, and the task is parked
- **THEN** `task.json` carries the position advanced by that drain, written together with the escalation and its denials

#### Scenario: Resume prefers the newest committed position
- **WHEN** a resumed task's branch tip carries an attempt cursor in `state.json` and a newer escalation cursor in `task.json`, both naming the surviving guard container
- **THEN** the restore offers the escalation's position, and the first read after the resume starts past the drained denials

#### Scenario: An unanswerable cursor never fails the park
- **WHEN** the environment cannot answer its denial cursor while a `cannotExecute` escalation is being recorded
- **THEN** `task.json` records the escalation and its denials without a cursor and the park succeeds

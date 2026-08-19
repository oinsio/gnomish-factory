# git-task-persistence — delta

## MODIFIED Requirements

### Requirement: State directory with one writer per file
`.gnomish-task/` at the working-copy root SHALL hold exactly: `task.json` (written only by `TaskRepository`: version, taskId, title, body, createdAt, baseCommit, decisions[] {text, author?, stage?, at?}, outcome — null | completed | paused{passedStage} | escalated{report} | aborted{failedAt, cause} — and lastEscalation), `state.json` (written only by the git `AttemptPersistence`: version, position, attemptsUsed, attempts[] {round, result, startedAt, checks[], denials[], executorUsage, judgeUsage}, totals — inner forms as in status-report v1), `attempts/<stage>/<round>/trace.jsonl` (one JSON line per tool call; the round is identified by the file path), and — in git modes — `decisions/<stage>-a<attempt>.json` (written only by the gnome; the single gnome-writable path under `.gnomish-task/`, per the decision-file protocol).
<!-- implements FR3 of add-git-workflow -->
<!-- implements FR23 of add-sandbox-core -->
<!-- implements FR4 of fix-denial-report-attachment -->

#### Scenario: History of past stages lives in git
- **WHEN** the task advances to the next stage
- **THEN** `state.json` contains only the current stage's attempts; earlier rounds remain in the file's git history

#### Scenario: Decision file keeps the one-writer rule
- **WHEN** a round leaves a decision request in `.gnomish-task/decisions/`
- **THEN** the gnome is that file's only writer and every other `.gnomish-task/` path keeps its single factory-side writer

## ADDED Requirements

### Requirement: Attempt denials in the state file
Each attempt record in `state.json` SHALL carry a denials list — structured findings recorded by the environment's egress guard during that attempt's round — separate from check results. The list SHALL NOT participate in the attempt's result, the stage's overall verdict, or the prior-failure feedback of a retry. The field is additive under contract v1: readers SHALL treat an absent field as an empty list, and existing documents without it remain readable.
<!-- implements FR2, FR4 of fix-denial-report-attachment -->

#### Scenario: Denials persist with the attempt
- **WHEN** a round with a guard denial is committed to the task branch
- **THEN** the attempt's entry in `state.json` carries the denial finding, and the attempt's result is unchanged by its presence

#### Scenario: Pre-existing state files stay readable
- **WHEN** a state file written before this contract addition is read
- **THEN** it parses under contract v1 with every attempt's denials read as empty

#### Scenario: Denials never feed retries
- **WHEN** an attempt with denials fails on a check and the stage retries
- **THEN** the feedback context of the retry contains only the check findings, not the denials

### Requirement: Denial cursor in the state file
`state.json` SHALL carry the environment's denial read position at commit time — the opaque position paired with the identity of the denial source it was read from — so an instance resuming the task continues the denial delta where the committed attempt left it, instead of re-reading everything the source still holds. The field is environment bookkeeping, not task state: it SHALL NOT affect the position, attempts, or usage a reader reconstructs. It is additive under contract v1: an absent field means "no cursor to resume from", and a writer with no denial source records none.
<!-- implements FR5 of fix-denial-report-attachment -->

#### Scenario: The cursor is committed with the attempt it delimits
- **WHEN** a sandboxed round is committed to the task branch
- **THEN** `state.json` records the environment's denial read position and the identity of the source it was read from

#### Scenario: Resume continues the delta instead of replaying it
- **WHEN** an instance resumes a task whose recorded cursor names the denial source it reattaches to
- **THEN** the first round after resume reports only its own denials, and earlier rounds' denials — already recorded in their own attempts — are not attached to it again

#### Scenario: A state file written before the cursor existed stays readable
- **WHEN** a state file with no cursor field is read
- **THEN** it parses under contract v1 and the run reads its denial source from the beginning

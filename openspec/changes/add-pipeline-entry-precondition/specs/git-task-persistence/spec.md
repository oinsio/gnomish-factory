# git-task-persistence — delta for add-pipeline-entry-precondition

## ADDED Requirements

### Requirement: Entry-precondition verdict in the state file
`state.json` SHALL carry the entry-precondition verdict when one has been obtained:
the verdict (green | red), the baseline SHA and environment image identity that key
it, the probe's bounded output tail for a red verdict, and the verdict's timestamp.
The field SHALL be written factory-side by the entry-precondition step in its own
commit on the task branch, between the task-creation commit and the first round —
never by the gnome, never inside a round — and every later state write SHALL
preserve it unchanged, including lifecycle rewrites (resume, park, finish). The
field is additive under contract v1: readers SHALL treat an absent field as "no
verdict obtained", and state files written before this contract addition remain
readable. The verdict SHALL NOT affect the position, attempts, or usage a reader
reconstructs.
<!-- implements FR6, NFR-R1 of add-pipeline-entry-precondition -->

#### Scenario: Verdict commit lands before the first round
- **WHEN** a probe completes for a task with no recorded rounds
- **THEN** the task branch gains a factory-side commit updating only the verdict
  field of `state.json`, keyed by baseline SHA and image identity, and no attempt
  entry is created

#### Scenario: Round writes preserve the verdict
- **WHEN** rounds are later recorded and the task parks and resumes
- **THEN** every rewritten `state.json` still carries the recorded verdict under
  its original key

#### Scenario: Pre-existing state files stay readable
- **WHEN** a state file written before this contract addition is read
- **THEN** it parses under contract v1 with the verdict read as absent, and the
  entry-precondition step treats the task as unprobed

## MODIFIED Requirements

### Requirement: State directory with one writer per file
`.gnomish-task/` at the working-copy root SHALL hold exactly: `task.json` (written only by `TaskRepository`: version, taskId, title, body, createdAt, baseCommit, decisions[] {text, author?, stage?, at?}, outcome — null | completed | paused{passedStage} | escalated{report} | aborted{failedAt, cause} — and lastEscalation), `state.json` (version, position, attemptsUsed, attempts[] {round, result, startedAt, checks[], denials[], executorUsage, judgeUsage}, totals — inner forms as in status-report v1 — and the optional entryPrecondition verdict), `attempts/<stage>/<round>/trace.jsonl` (one JSON line per tool call; the round is identified by the file path), and — in git modes — `decisions/<stage>-a<attempt>.json` (written only by the gnome; the single gnome-writable path under `.gnomish-task/`, per the decision-file protocol). `state.json` SHALL have exactly two factory-side writers with disjoint windows: the entry-precondition step, which writes only the entryPrecondition field in its own commit before any round exists, and the git `AttemptPersistence`, which owns every round write and preserves the entryPrecondition field; the gnome writes neither.
<!-- implements FR3 of add-git-workflow -->
<!-- implements FR23 of add-sandbox-core -->
<!-- implements FR4 of fix-denial-report-attachment -->
<!-- implements FR6 of add-pipeline-entry-precondition -->

#### Scenario: History of past stages lives in git
- **WHEN** the task advances to the next stage
- **THEN** `state.json` contains only the current stage's attempts; earlier rounds remain in the file's git history

#### Scenario: Decision file keeps the one-writer rule
- **WHEN** a round leaves a decision request in `.gnomish-task/decisions/`
- **THEN** the gnome is that file's only writer and every other `.gnomish-task/` path keeps its single factory-side writer

#### Scenario: Writer windows are disjoint
- **WHEN** the entry-precondition step records a verdict
- **THEN** the write happens before any round is recorded, and once rounds exist,
  only the git `AttemptPersistence` writes `state.json`

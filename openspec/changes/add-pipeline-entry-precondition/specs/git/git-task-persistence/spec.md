# git/git-task-persistence — delta for add-pipeline-entry-precondition

## ADDED Requirements

### Requirement: Entry-precondition verdict in the state file
`state.json` SHALL carry the entry-precondition verdict once one has been obtained: the
verdict (green | red), the baseline SHA and environment image identity that key it, the
bounded output tail of a red verdict, and the verdict's timestamp. The entry-precondition
step SHALL write it factory-side, in its own commit between the task-creation commit and the
first round — never the gnome, never inside a round — and every later state write SHALL
preserve it unchanged.
**Implements**: FR6, NFR-R1 of add-pipeline-entry-precondition
**Notes**: The field is additive under contract v1; state files written before it stay readable.

#### Scenario: Verdict commit lands before the first round
- **WHEN** a probe completes for a task with no recorded rounds
- **THEN** the task branch gains a factory-side commit updating only the verdict
  field of `state.json`, keyed by baseline SHA and image identity, and no attempt
  entry is created

#### Scenario: Round writes preserve the verdict
- **WHEN** rounds are later recorded and the task parks and resumes
- **THEN** every rewritten `state.json` still carries the recorded verdict under
  its original key

#### Scenario: A finish write preserves the verdict
- **WHEN** a task with a recorded verdict finishes and its lifecycle write rewrites `state.json`
- **THEN** the rewritten file still carries the recorded verdict under its original key

#### Scenario: Pre-existing state files stay readable
- **WHEN** a state file written before this contract addition is read
- **THEN** it parses under contract v1 with the verdict read as absent, and the
  entry-precondition step treats the task as unprobed

#### Scenario: The verdict does not change the reconstructed run
- **WHEN** a reader reconstructs the position, the attempts and the usage from a `state.json` that carries a verdict
- **THEN** it reconstructs the same position, attempts and usage as from the same file without the verdict

## MODIFIED Requirements

Layered on `State directory with one writer per file` as modified by
`tighten-spec-contract` (sequenced before this change): that delta cuts the body to the
directory's four paths and their writers and moves the files' contents and the `state.json`
writer windows into scenarios ("task.json carries the task context and the outcome",
"state.json has two writers in disjoint windows", "state.json carries the position and the
current attempts", "A repeated stage visit overwrites the round trace at the tip"); it already
carries the round-token decision path and the attempt's `stop` field of the archived
`make-checkpoint-gate-durable`. This text adds the base pin wording, the entryPrecondition
verdict and its writer window (the third writer, scenario "Writer windows are disjoint"), and
all of these must survive the later sync.

### Requirement: State directory with one writer per file
`.gnomish-task/` at the working-copy root SHALL hold exactly `task.json`, `state.json`, the round traces `attempts/<stage>/<round>/trace.jsonl` and — in git modes — the decision requests `decisions/<stage>-a<attempt>-<token>.json`. `task.json` SHALL be written only by `TaskRepository` and `state.json` only by factory-side components; a decision request, named by the round token per the decision-file protocol, is the single gnome-writable path. The gnome writes `state.json` never.
**Implements**: FR13 of make-checkpoint-gate-durable; FR3 of add-git-workflow; FR23 of add-sandbox-core; FR4 of fix-denial-report-attachment; FR3 of harden-task-branch-contract; FR2 of fix-denial-attribution-durability; FR6 of add-pipeline-entry-precondition

#### Scenario: History of past stages lives in git
- **WHEN** the task advances to the next stage
- **THEN** `state.json` contains only the current stage's attempts; earlier rounds remain in the file's git history

#### Scenario: Decision file keeps the one-writer rule
- **WHEN** a round leaves a decision request in `.gnomish-task/decisions/`
- **THEN** the gnome is that file's only writer and every other `.gnomish-task/` path keeps its single factory-side writer

#### Scenario: The request is named by its round
- **WHEN** two rounds of the same stage and attempt number run in one task — an answered question restarts the stage, or a killed round is retried
- **THEN** their decision paths differ by the round token, and the later round's path is not present on the tip it opens on

#### Scenario: task.json carries the task context and the outcome
- **WHEN** `TaskRepository` writes `task.json`
- **THEN** the file carries version, taskId, title, body, createdAt, the base pin (the `baseCommit` slot, structured per base-ref-resolution), decisions[] {text, author?, stage?, at?}, outcome — null | completed | paused{passedStage} | escalated{report} | aborted{failedAt, cause} — and lastEscalation, whose `cannotExecute` kind additionally carries the denials of the round that could not execute

#### Scenario: state.json has two writers in disjoint windows
- **WHEN** `state.json` is written after the STARTED commit by anything other than the entry-precondition step's verdict write
- **THEN** the writer is either the git `AttemptPersistence`, for a round write, or `TaskRepository`, for a lifecycle transition (the decision commit replacing the state with the reset one beside the appended decision, the approval write moving the position past the gate, the resumed write replacing the state with the reset one) in its own commit outside any round
- **AND** both preserve the entryPrecondition field unchanged

#### Scenario: state.json carries the position and the current attempts
- **WHEN** `state.json` is written
- **THEN** it carries version, position, attemptsUsed, attempts[] {round, result, startedAt, stop, checks[], denials[], executorUsage, judgeUsage} and totals, the inner forms as in status-report v1, and the optional entryPrecondition verdict

#### Scenario: A repeated stage visit overwrites the round trace at the tip
- **WHEN** a stage is visited again and a round of the new visit writes `attempts/<stage>/<round>/trace.jsonl`
- **THEN** the file holds one JSON line per tool call of that round, the tip carries the latest visit's version, and git history keeps every earlier version

#### Scenario: Writer windows are disjoint
- **WHEN** the entry-precondition step records a verdict
- **THEN** the write happens before any round is recorded, and once rounds exist,
  only the git `AttemptPersistence` writes `state.json`

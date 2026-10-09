# Spec Delta: manual-run

## MODIFIED Requirements

### Requirement: Resume invocation
`gnomish run --dir=<dir> --resume=<task>` SHALL resume the named task from its branch; `--resume` is mutually exclusive with `--task`, `--task-file`, `--task-id`, and `--from-stage` (usage error). `--decision="<text>"` SHALL be accepted only together with `--resume`, never with `--mode=in-place`, and only for a task whose recorded outcome is `escalated`; any other combination is a usage error naming the conflict. Resume SHALL behave by recorded branch state without any prompt, and every continuation SHALL land its one lifecycle commit before the engine runs: escalated with `--decision` → the decision commit (decision appended, attempts reset, outcome cleared), then continue; escalated without `--decision` → the resumed commit (attempts reset, outcome cleared), then continue — unless the recorded report is `DecisionNeeded`, in which case the question is restated, nothing is written, and the process exits 10; at a gate (`AwaitingApproval(stage)`, whether or not the `paused` park was recorded) → the approval commit (position past the gate, outcome cleared), then continue; outcome null at a stage → continue silently from the recorded position; completed → report the task is done and exit with the success code.
<!-- implements FR4, FR7 of make-checkpoint-gate-durable -->
<!-- implements FR3, FR4, FR5, FR9 of make-run-headless -->

#### Scenario: Resume of an escalated task
- **WHEN** `--resume --decision="patch in place"` opens a task parked as Escalated with an `AttemptsExhausted` report
- **THEN** the branch gains one commit carrying the decision and the attempts reset, and the engine continues at the same stage

#### Scenario: Resume without a decision writes the reset
- **WHEN** `--resume` without `--decision` opens a task parked as Escalated with an `AttemptsExhausted` or `CannotVerify` report
- **THEN** the branch gains one commit with `outcome` null and the reset attempt counter before the first round, and a kill after it does not grant a second reset

#### Scenario: Resume of a checkpoint approves it
- **WHEN** `--resume` opens a task whose branch is at `AwaitingApproval(stage)`
- **THEN** the branch gains the approval commit — position past the gate, `outcome` null — and the engine starts the following stage; no prompt, no decision

#### Scenario: Resume of a gate whose park was lost
- **WHEN** `--resume` opens a task at `AwaitingApproval(stage)` whose `task.json` outcome is null
- **THEN** the run behaves exactly as for a recorded `paused` park: it approves and continues

#### Scenario: Completed task resumes to a no-op
- **WHEN** `--resume` names a task whose outcome is completed
- **THEN** the process reports the task is done and exits 0

#### Scenario: Unanswered question is restated
- **WHEN** `--resume` without `--decision` opens a task whose recorded report is `DecisionNeeded`
- **THEN** the question and options are printed again with the return-path line, no round runs, nothing is written, and the process exits 10

#### Scenario: Decision without a question is a usage error
- **WHEN** `--resume --decision="x"` opens a task whose branch is at a gate or whose outcome is completed, or `--decision` is given without `--resume`
- **THEN** the process exits 2 naming the conflict and writes nothing to the branch

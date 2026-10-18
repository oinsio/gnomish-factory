# Spec Delta: cli/manual-run

## MODIFIED Requirements

Layered on "Resume invocation" as modified by `tighten-spec-contract` (sequenced before this
change); the earlier layering on `make-checkpoint-gate-durable` and `make-run-headless` is
archived and is part of that text. That delta cut the body to the resume rule and the
`--decision` constraint and moved the per-state continuations into scenarios, adding "Resume
excludes the fresh-start flags" and "An interrupted task resumes silently". This text keeps
that body, narrows the scenario "Completed task resumes to a no-op" to a branch whose cleanup
already landed, and states the lost-cleanup convergence (the body's completed continuation) as
the scenario "Completed task with a lost cleanup converges".

### Requirement: Resume invocation
`gnomish run --dir=<dir> --resume=<task>` SHALL resume the named task from its branch, behaving by the recorded branch state without any prompt, and every continuation SHALL land its one lifecycle commit before the engine runs. `--decision="<text>"` SHALL be accepted only together with `--resume`, never with `--mode=in-place`, and only for a task whose recorded outcome is `escalated`; any other combination is a usage error naming the conflict.
**Implements**: FR4 of fix-terminal-receipt-convergence; FR4, FR7 of make-checkpoint-gate-durable; FR3, FR4, FR5, FR9 of make-run-headless

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
- **WHEN** `--resume` names a task whose outcome is completed and whose cleanup commit is already on the branch
- **THEN** the process reports the task is done, writes nothing, and exits 0

#### Scenario: Completed task with a lost cleanup converges
- **WHEN** `--resume` names a task whose tip records `Completed` and still carries `.gnomish-task/`
- **THEN** the cleanup commit lands and is pushed through the same cleanup owner `take` uses, the worktree is removed, the process reports the task is done and exits 0, and a second `--resume` writes nothing

#### Scenario: Unanswered question is restated
- **WHEN** `--resume` without `--decision` opens a task whose recorded report is `DecisionNeeded`
- **THEN** the question and options are printed again with the return-path line, no round runs, nothing is written, and the process exits 10

#### Scenario: Decision without a question is a usage error
- **WHEN** `--resume --decision="x"` opens a task whose branch is at a gate or whose outcome is completed, or `--decision` is given without `--resume`
- **THEN** the process exits 2 naming the conflict and writes nothing to the branch

#### Scenario: Resume excludes the fresh-start flags
- **WHEN** `--resume` is combined with `--task`, `--task-file`, `--task-id` or `--from-stage`
- **THEN** the process exits 2 with a usage error naming the conflict

#### Scenario: An interrupted task resumes silently
- **WHEN** `--resume` opens a task whose recorded outcome is null at a stage
- **THEN** the engine continues silently from the recorded position, with no prompt

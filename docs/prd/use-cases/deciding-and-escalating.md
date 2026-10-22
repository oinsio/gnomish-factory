# Deciding and escalating

Group 6 of [`../use-cases.md`](../use-cases.md): the events between "the factory cannot go on
alone" and "a human or an arbiter has settled it": a fork the gnome raised stays unresolved, an
arbiter decides, a human answers, a stage's attempts are exhausted, a manual checkpoint is
reached. The voices are the escalation handler, the pipeline author, the factory operator, the
security owner, the reviewer and the arbiter; the stories follow the form and rules of the
index. The gnome's own question is `UC-ASK-A-DECISION` in group 4; a task parked because the
factory could not verify or could not run is group 8; the board of parked tasks is group 9.

## Escalation handler (`SH-ESCALATION-HANDLER`)

### UC-DECIDE-FROM-THE-REPORT — Decide from the thread alone
**When** a task is parked for me,
**I want** one report in the tracker thread I can decide from without the branch or the logs:
the question with the options the gnome saw, or the stage with every attempt's findings, or the
checkpoint's stage, with the way back spelled out, and never a request that is not a question,
**so that** a parked task costs me one read and one reply.

### UC-ANSWER-IN-THE-THREAD — Have my answer acted on, once, by whoever picks the task up
**When** I reply in the thread and return the task,
**I want** any instance to claim it, say which reply it acts on before it acts, and carry my
answer verbatim into every later round and judge of the task,
**so that** one reply settles the question for good, whichever instance continues.

### UC-RETURN-WITHOUT-A-WORD — Have a bare return read as my answer
**When** I return a task without replying (I fixed the cause, or the attempts were spent),
**I want** the return itself to grant one fresh attempt budget, recorded before any round so a
crash cannot grant it twice, and an open question restated rather than guessed at,
**so that** continuing never needs a ritual reply and never needs me to answer twice.

### UC-APPROVE-A-CHECKPOINT — Approve a passed stage by returning the task
**When** a stage marked for my approval has passed,
**I want** the task stopped at that stage with its work on the branch at origin, nothing run
past it until I return the task, and the return taken as the approval exactly once,
**so that** I review finished work at my own pace and am never asked for the same approval twice.

### UC-VETO-AN-ARBITER-DECISION — Overrule a decision the arbiter made
**When** the arbiter settled a fork in a way I disagree with,
**I want** the decision visible in the thread with its author, its rules and its scope before
the task completes, and a normal tracker action of mine to supersede it,
**so that** automation decides the routine and I keep the last word.

## Pipeline author (`SH-PIPELINE-AUTHOR`)

### UC-BOUND-THE-ATTEMPTS — Say how many tries a stage gets
**When** I set a stage's attempt limit in the law,
**I want** a red stage retried up to that limit with the findings fed back, then parked with
the whole attempt history,
**so that** a task that cannot pass never burns models all night and the human sees every try.

### UC-PUT-A-CHECKPOINT-IN-THE-LAW — Make a stage wait for a human
**When** I mark a stage as manual in the law,
**I want** every task to stop at that stage after it passes, parked for a human, with the
position recorded by the passing round itself,
**so that** a human sees the work before the next stage spends on it, whatever instance runs.

### UC-SETTLE-ROUTINE-FORKS-BY-RULE — Let written rules answer the routine forks
**When** I write decision rules for a stage,
**I want** a fork the gnome raises answered by an arbiter under those rules, sealed with the
law, choosing only among the gnome's options, a bounded number of times per stage, and handed
to a human with its analysis when the rules do not cover it,
**so that** a routine fork costs no human context switch and no decision can loosen a check.

## Factory operator (`SH-FACTORY-OPERATOR`)

### UC-PARK-INSTEAD-OF-WAIT — Never hold a slot for an answer
**When** a run must stop for a human,
**I want** it to park the task and exit the same way with a terminal and under cron, the exit
code naming the kind of stop, and the trackerless run answered by a resume flag,
**so that** a question never holds a slot overnight and a script can drive the loop.

## Security owner (`SH-SECURITY-OWNER`)

### UC-DECISIONS-NEVER-COMMAND — Keep answers from rewriting the law
**When** an answer or an arbiter's verdict reaches the gnome or the judge,
**I want** it carried as data that changes no instruction, criterion or check, and a parked
task moved only by a human's tracker action, never by the gnome,
**so that** the decision channel can steer the work but cannot weaken a gate.

## Reviewer (`SH-REVIEWER`)

### UC-READ-EVERY-DECISION — Read every decision that shaped the work
**When** I read a finished task,
**I want** every decision with who gave it (the gnome's request, the arbiter, a human), when,
and for what scope, on the branch and in the thread,
**so that** I can tell why the work went the way it did.

## Arbiter (`AC-ARBITER`)

### UC-CHOOSE-AMONG-GIVEN-OPTIONS — Receive a fork I can only choose on
**When** I am consulted on a fork,
**I want** the question, its enumerated options, the stage's rules and read-only access to the
work on a fresh environment built from the attempt commit, all delimited as data, and one
closed form for my verdict,
**so that** I choose among what the gnome saw and can never rewrite what the law says.

## Coverage

The second column is the dry run of the `**Serves**:` lines: the capability and the
requirement headings that would carry the key. The third names entries of
[`../gaps.md`](../gaps.md).

| Story | Carried by today | Gaps |
|---|---|---|
| `UC-DECIDE-FROM-THE-REPORT` | `tracker/tracker-take`: Escalation parks and exits; `pipeline/stage-engine`: Outcome and report model, Attempt limit and exhaustion; `operator-guide.md` (the escalation flow) | `GP-MALFORMED-REQUEST-PARKS` |
| `UC-ANSWER-IN-THE-THREAD` | `tracker/tracker-take`: Decision consumption always leaves an ack, Any instance can pick up a returned task; `tracker/tracker-port`: Decision collection anchored to the last ack; `pipeline/stage-engine`: Human decisions as pass-through context | none |
| `UC-RETURN-WITHOUT-A-WORD` | `tracker/tracker-take`: Escalation parks and exits; `git/git-task-persistence`: Outcome protocol in task.json; `lifecycle/task-branch-contract`: A recorded position never implies an authorisation not yet recorded | none |
| `UC-APPROVE-A-CHECKPOINT` | `pipeline/stage-engine`: Advancement modes; `tracker/tracker-take`: Escalation parks and exits, Any instance can pick up a returned task; `git/git-task-persistence`: Delivery fence before a park's tracker write | none |
| `UC-VETO-AN-ARBITER-DECISION` | nothing | `GP-NO-ARBITER-TIER` |
| `UC-BOUND-THE-ATTEMPTS` | `pipeline/pipeline-config`: Autonomy limit resolution; `pipeline/stage-engine`: Attempt limit and exhaustion | none |
| `UC-PUT-A-CHECKPOINT-IN-THE-LAW` | `pipeline/stage-engine`: Advancement modes; `lifecycle/task-branch-contract`: A recorded position never implies an authorisation not yet recorded | none |
| `UC-SETTLE-ROUTINE-FORKS-BY-RULE` | nothing | `GP-NO-ARBITER-TIER` |
| `UC-PARK-INSTEAD-OF-WAIT` | `tracker/tracker-take`: Escalation parks and exits, Exit codes by take result; `cli/manual-run`: Agent-raised decisions reach the operator unchanged, Resume invocation | none |
| `UC-DECISIONS-NEVER-COMMAND` | `pipeline/stage-engine`: Human decisions as pass-through context; `tracker/tracker-port`: Logical task-state dictionary and transition matrix; `verification/verification-hardening`: Judge input is delimited data | none |
| `UC-READ-EVERY-DECISION` | `git/git-task-persistence`: State directory with one writer per file (the decision record); `tracker/github-tracker`: Structural comments carry coordination facts (the ack) | `GP-DECISION-AUTHOR-OPTIONAL` |
| `UC-CHOOSE-AMONG-GIVEN-OPTIONS` | nothing | `GP-NO-ARBITER-TIER` |

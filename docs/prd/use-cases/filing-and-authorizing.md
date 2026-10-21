# Filing and authorizing work

Group 2 of [`../use-cases.md`](../use-cases.md): the events between "someone wants work done"
and "the factory may start". The actors are the task author, the factory operator and the
security owner; the stories follow the form and rules of the index.

## Task author (`SH-TASK-AUTHOR`)

### UC-FILE-TASK — Write a task the factory reads as I meant it
**When** I write an issue for the factory to work,
**I want** the factory to work from exactly the text I authorized, read by the project's own
conventions, and to ask in the thread before any paid round if the text is ambiguous,
**so that** a wording slip costs me a comment, not a whole round and an escalation.

### UC-AUTHORIZE-TASK — Hand a task to the factory
**When** I have put `gnomish:ready` on an issue,
**I want** a running factory to claim it within one poll, or the board to name the one reason
it did not (in backoff, finished, held by the work-in-progress limit),
**so that** I never dig through logs to learn whether work has started.

### UC-STEER-TASK — Tell the factory how to treat this task
**When** a task must start from another base or go through another pipeline,
**I want** to say so with a label the law allows, and the factory to refuse with the allowed
set when it does not, and to escalate rather than guess when two labels conflict,
**so that** I steer the run without writing instructions to the gnome into the task text.

### UC-FOLLOW-UP-DELIVERED-TASK — Ask for more work on a delivered task
**When** I move a delivered task back to `gnomish:ready` or reopen its issue,
**I want** the factory to decline within one poll, restore the delivered status, and ask me
for a new task that references the old one,
**so that** nothing ever runs a second time over code that is already merged.

## Factory operator (`SH-FACTORY-OPERATOR`)

### UC-MANDATE-TASK — Make the factory work this task now
**When** I name one task on the command line instead of waiting for the queue,
**I want** it worked regardless of readiness and backoff, a takeover of a held task only on my
explicit confirmation, and a refusal that names the state and the way back when the task is
parked, finished or gone,
**so that** I can unstick or prioritize a task without waiting for cron and without breaking
another instance's claim.

### UC-RUN-ADHOC-TASK — Try one task without a tracker
**When** I have a task text and no tracker, or I am testing a pipeline law,
**I want** to run it through the whole quality-control cycle with the real executors, never
waiting for my input, with the outcome readable from the exit code and the branch alone,
**so that** a script or an agent can drive the escalate, decide and resume loop.

## Security owner (`SH-SECURITY-OWNER`)

### UC-KEEP-READY-GRANT-TRUSTED — Keep the ready label in trusted hands
**When** a fork's workflow, a bot, a stranger's form or a text field tries to apply
`gnomish:ready`,
**I want** the label reachable only through tracker write access and automation I reviewed,
and every claim to record who applied it,
**so that** the label never becomes someone else's right to run code on the host.

Tension with `UC-AUTHORIZE-TASK`: the author wants the hand-off to be one gesture; the owner
wants every gesture attributable.

## Coverage

The third column names entries of [`../gaps.md`](../gaps.md).

| Story | Carried by today | Gaps |
|---|---|---|
| `UC-FILE-TASK` | `tracker/tracker-take`: Snapshot at first claim; `.gnomish/README.md` (the `Change:` line) | `GP-NO-PRECLAIM-TEXT-CHECK` |
| `UC-AUTHORIZE-TASK` | `tracker/tracker-take`: Bare auto mode takes the head of the queue; `operator-guide.md` (label dictionary, ready eligibility) | none |
| `UC-STEER-TASK` | `git/base-ref-resolution`; `tracker/tracker-port` (designators) | `GP-NO-TASK-TYPE` |
| `UC-FOLLOW-UP-DELIVERED-TASK` | `tracker/tracker-take`: Explicit-mode disposition by task state, Bare auto mode takes the head of the queue | none |
| `UC-MANDATE-TASK` | `tracker/tracker-take`: Explicit-mode disposition by task state, Batch take works the list with a summary and one exit code | none |
| `UC-RUN-ADHOC-TASK` | `cli/manual-run`; `operator-guide-run.md` | none |
| `UC-KEEP-READY-GRANT-TRUSTED` | `operator-guide-autonomy-gate.md`, guidance only | `GP-READY-APPLIER-UNRECORDED` |

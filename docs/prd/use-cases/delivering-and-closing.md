# Delivering and closing

Group 7 of [`../use-cases.md`](../use-cases.md): the events between "the last stage has passed"
and "nothing of the task is left to do": the task is completed, its pull request is opened,
the branch is merged, the machine is freed. The voices are the repository owner, the task
author, the reviewer, the security owner, the factory operator and the factory instance; the
stories follow the form and rules of the index. A delivered task moved back to ready is
`UC-FOLLOW-UP-DELIVERED-TASK` in group 2; what a task cost is group 9.

## Repository owner (`SH-REPOSITORY-OWNER`)

### UC-RECEIVE-AN-ORDINARY-PULL-REQUEST — Get finished work as a pull request like any other
**When** a task has passed its last stage,
**I want** its work to reach me as a pull request from an ordinary branch against the base I
allowed, with a substantive title and body that reference the task, opened once and updated
rather than duplicated on a retry,
**so that** I review and merge it as I would any contributor's work.

### UC-KEEP-MAIN-FREE-OF-BOOKKEEPING — Merge the work, not the factory's journal
**When** I merge a task branch,
**I want** the diff to hold only the work, the factory's state files gone from the tip, and the
round-by-round journal to stay behind on the task branch whichever merge method I use,
**so that** my main branch's history records changes to my project, never the factory's steps.

### UC-LEAVE-NOTHING-BEHIND-AFTER-MERGE — Be done with a task when its branch is gone
**When** the pull request is merged and its branch deleted,
**I want** the factory to treat the task as done forever, its inspection to say plainly that
the task is gone, and no ref, label or comment of the factory's to outlive the branch,
**so that** a finished task leaves my repository exactly as a human contributor's would.

## Task author (`SH-TASK-AUTHOR`)

### UC-HEAR-THAT-IT-IS-DONE — Learn in the thread that the work is ready
**When** my task is delivered,
**I want** the issue moved to the delivered status with one final report naming the stages,
their attempts, the branch and the time it took, and the issue closed by the merge rather
than by the factory,
**so that** the thread tells me the work is ready for review and when it has landed.

## Reviewer (`SH-REVIEWER`)

### UC-REVIEW-THE-WORK-NOT-THE-JOURNAL — Read the diff as the work, the journal beside it
**When** I review a delivered task,
**I want** the pull request's diff to be the work alone, and every attempt, verdict, decision
and denial reachable from the branch history and the issue thread,
**so that** I judge the change on its own and can still ask how it came to be.

## Security owner (`SH-SECURITY-OWNER`)

### UC-DELIVER-WITHOUT-ARMING-THE-GNOME — Open the pull request with a credential the gnome never holds
**When** a task's pull request is opened,
**I want** it opened by the factory, with a credential scoped to that one write and never
present in the gnome's environment,
**so that** a deceived gnome can neither open, edit nor merge pull requests, nor push past its
own branch.

Tension with `UC-WORK-IN-OWN-COPY`: the gnome wants the project's own tools, the forge's
command line among them; the owner wants no forge credential in the box.

## Factory operator (`SH-FACTORY-OPERATOR`)

### UC-FREE-THE-MACHINE-AFTER-DELIVERY — Have a delivered task cost no disk
**When** a task is delivered,
**I want** its worktree, or its box with its volume and network, disposed once it has aged
past a bound I set, by any instance, without touching a task still in work,
**so that** a month of delivered tasks leaves my machine as it was.

## Another factory instance (`AC-FACTORY-INSTANCE`)

### UC-CONVERGE-ON-ONE-FINISH — Finish a completion that was recorded but not delivered
**When** I touch a task whose completion is recorded on its branch but whose finish is still
owed (the process died, or the tracker was down at the finish line),
**I want** to deliver the finish from origin alone, with or without a claim, and converge with
any sibling doing the same on one report, one cleanup commit and one push,
**so that** a completed task is finished exactly once and never pays for a stage again.

## Coverage

The second column is the dry run of the `**Serves**:` lines: the capability and the
requirement headings that would carry the key. The third names entries of
[`../gaps.md`](../gaps.md).

| Story | Carried by today | Gaps |
|---|---|---|
| `UC-RECEIVE-AN-ORDINARY-PULL-REQUEST` | `git/git-task-persistence`: Task branch naming and base; `git/base-ref-resolution`: Allowed bases govern per-task selection; `reference-pipelines.md` (the `deliver` stage of the reference pipelines) | `GP-DELIVERY-IS-A-GNOME-STAGE` |
| `UC-KEEP-MAIN-FREE-OF-BOOKKEEPING` | `git/git-task-persistence`: Cleanup commit on completion; `operator-guide-run.md` (squash-merge guidance) | `GP-SQUASH-MERGE-IS-ADVICE` |
| `UC-LEAVE-NOTHING-BEHIND-AFTER-MERGE` | `tracker/tracker-take`: Delivery posts a final report and ends the factory's involvement; `cli/task-inspection`: Missing task reported plainly; `tracker/github-tracker`: Finished fact from the finish marker | `GP-REGISTER-REF-OUTLIVES-THE-BRANCH` |
| `UC-HEAR-THAT-IT-IS-DONE` | `tracker/tracker-take`: Delivery posts a final report and ends the factory's involvement; `observability/status-report`: Single report model behind every render | none |
| `UC-REVIEW-THE-WORK-NOT-THE-JOURNAL` | `git/git-task-persistence`: Cleanup commit on completion, Round commit of the whole working tree; `cli/task-inspection`: External status reader; `tracker/github-tracker`: Structural comments carry coordination facts | none |
| `UC-DELIVER-WITHOUT-ARMING-THE-GNOME` | nothing | `GP-DELIVERY-IS-A-GNOME-STAGE` |
| `UC-FREE-THE-MACHINE-AFTER-DELIVERY` | `serve/factory-serve`: Worktree cleaner disposes aged task environments; `sandbox/sandbox-lifecycle`: Sweep decision matrix, Aged reaper for kept environments | none |
| `UC-CONVERGE-ON-ONE-FINISH` | `tracker/tracker-take`: A Completed-without-cleanup tip is finished, never re-executed, Terminal tracker writes retry against an outage; `lifecycle/claim-heartbeat`: Terminal outcomes reconcile through the branch; `git/git-task-persistence`: Task branch adoption, Cleanup commit on completion | none |

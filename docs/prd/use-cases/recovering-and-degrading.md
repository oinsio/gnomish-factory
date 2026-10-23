# Recovering and degrading

Group 8 of [`../use-cases.md`](../use-cases.md): the events that happen to the factory rather
than to one task's work: a process is killed between two durable steps, origin, the tracker
or the container runtime is unreachable, a clone or a machine is lost. The voices are the
factory instance, the factory operator, the task author, the security owner and the incident
analyst; the stories follow the form and rules of the index. Resume is a first-order promise
(G1 and G2 of `add-git-workflow`), so these events are a group of their own. Reaping a dead
sibling's claim is `UC-TAKE-OVER-A-DEAD-CLAIM` and continuing its task `UC-RESUME-ANY-INSTANCE`,
both in group 3; a completion whose finish was lost is `UC-CONVERGE-ON-ONE-FINISH` in group 7.

## Another factory instance (`AC-FACTORY-INSTANCE`)

### UC-RESUME-FROM-THE-RECORD — Continue from what the media say, not from a journal
**When** I pick up a task whose process died between two durable steps,
**I want** to read the branch at its registered commit and the tracker, classify what I see
into one named state, and run the one recovery that state has, finishing what was begun or
discarding to the last good point, never re-running a paid round, and running it twice
changing nothing,
**so that** a crash costs the task a pickup, never a stage.

### UC-SALVAGE-THE-UNCOMMITTED-TAIL — Keep what the gnome had not yet committed
**When** a round was cut short with uncommitted work,
**I want** the gnome's edits committed as a salvage commit while the box or worktree still
exists, the factory's own files restored from the record and never from the dirty copy, and a
lost box to cost at most the uncommitted tail,
**so that** a crash loses minutes of work, not a round, and lets no tampered file in.

### UC-DELIVER-THE-LOST-PUSH — Carry to origin what an earlier instance could not
**When** a push was lost to a crash or an outage,
**I want** the next touch of the task, on any machine, to compare with origin and push what
origin lacks, never with force, and a pair diverged under my claim resolved for origin with
no human asked,
**so that** origin is the one truth between instances and nobody does git surgery.

### UC-QUARANTINE-WHAT-I-CANNOT-MEND — Park once what no recovery can converge
**When** a branch is unreadable, written by a future version, in a state no shape names, or
its recovery has failed past its budget,
**I want** the task parked once with a diagnosis naming the file, the observed and expected
shape and the attempts spent, counted on one budget with the crash fuse,
**so that** a broken record costs one park and one read, never a crash loop.

## Factory operator (`SH-FACTORY-OPERATOR`)

### UC-RIDE-OUT-AN-OUTAGE — Let an outage pass without a human
**When** the tracker, origin or the container runtime is unreachable,
**I want** the daemon to keep running, hold its outcomes on the branch and retry terminal
writes with backoff, stop claiming while origin is down and resume on a probe, charge no task
an attempt, an abort or a manual return for the outage, and tell me once at warning level,
**so that** an outage in the night costs me nothing in the morning.

### UC-NEVER-HANG-ON-A-DEAD-REMOTE — Bound every command that waits on something else
**When** git or a child process talks to a remote that never answers,
**I want** every such command ended at a deadline I can raise, with its whole process tree,
and a timeout told apart from an interrupt and from a refusal,
**so that** no run holds a slot overnight on a connection that went quiet.

### UC-REBUILD-FROM-ORIGIN — Lose a machine and lose no task
**When** the factory's clone, its worktrees or the whole machine are lost,
**I want** a fresh clone, registered, to continue every task from origin and the tracker,
with nothing of a task living only on the lost machine,
**so that** a disk is replaceable and a task is not.

## Task author (`SH-TASK-AUTHOR`)

### UC-KEEP-MY-ANSWER-THROUGH-A-CRASH — Have my answer survive the factory's death
**When** the factory crashed after I replied or approved,
**I want** my answer acted on exactly once by whichever instance continues, never lost and
never asked for again,
**so that** a crash on the factory's side is never a question on mine.

## Security owner (`SH-SECURITY-OWNER`)

### UC-RECOVERY-TRUSTS-ONLY-THE-RECORD — Recover from the factory's record, not the gnome's tip
**When** recovery reads the world after a crash,
**I want** it to read the branch at the factory's register and the tracker, never the working
copy and never a tip the gnome wrote,
**so that** a crash is not a moment the gnome can use to get a forged state accepted.

## Incident analyst (`SH-INCIDENT-ANALYST`)

### UC-READ-WHY-RECOVERY-ACTED — Reconstruct a repair from one line
**When** a task was repaired, released or quarantined,
**I want** one log line per repair naming the shape, the task, the epoch and the action, a
warning when the same task is repaired again, the quarantine report readable without the
logs, and every degraded result logged with its cause,
**so that** one search leads from the event to the rule that fired.

## Coverage

The second column is the dry run of the `**Serves**:` lines: the capability and the
requirement headings that would carry the key. The third names entries of
[`../gaps.md`](../gaps.md).

| Story | Carried by today | Gaps |
|---|---|---|
| `UC-RESUME-FROM-THE-RECORD` | `lifecycle/task-branch-contract`: Total branch-shape classification, One recovery owner per shape; `lifecycle/claim-heartbeat`: Total tracker-shape classification with one recovery owner; `tracker/tracker-take`: Reconcile precedes resume; `pipeline/stage-engine`: Resume from any valid state | none |
| `UC-SALVAGE-THE-UNCOMMITTED-TAIL` | `git/git-task-persistence`: Salvage of interrupted rounds; `sandbox/execution-environment`: Shared factory-owned-paths salvage policy | none |
| `UC-DELIVER-THE-LOST-PUSH` | `git/git-task-persistence`: Origin reconciliation at task touchpoints, Automatic origin divergence resolution; `lifecycle/task-branch-contract`: Replica-pair reconciliation; `tracker/tracker-take`: Divergence resolves automatically under the lease | none |
| `UC-QUARANTINE-WHAT-I-CANNOT-MEND` | `tracker/tracker-take`: Non-recoverable shapes quarantine on first classification, Recovery attempts share one budgeted accounting with the crash fuse; `lifecycle/task-branch-contract`: Budgeted recovery with quarantine | none |
| `UC-RIDE-OUT-AN-OUTAGE` | `serve/factory-serve`: Daemon tolerates tracker outages, Serve survives base-refresh outages without charging tasks, Remote outage gate holds the feed off the tracker, Gate transitions are the outage's log and snapshot signal; `tracker/tracker-take`: Terminal tracker writes retry against an outage, Base infrastructure failure releases the claim outside the abort accounting; `sandbox/execution-environment`: Runtime outage is an infrastructure failure; `sandbox/sandbox-egress`: Guard outage is an infrastructure failure | `GP-RUNTIME-OUTAGE-PARKS`, `GP-NO-IMMEDIATE-RETURN` |
| `UC-NEVER-HANG-ON-A-DEAD-REMOTE` | `git/git-task-persistence`: Bounded git network invocations, Stall detection governs a progressing transfer, Interruption and timeout are named outcomes; `lifecycle/subprocess-supervision`: Supervised subprocess invocations, Launched processes form their own process group | none |
| `UC-REBUILD-FROM-ORIGIN` | `tracker/tracker-take`: Any instance can pick up a returned task; `git/git-task-persistence`: Resume from the recorded branch, Factory register ref; `operator-guide.md` (the reftable clone and its remedy) | `GP-FACTORY-IN-OPERATOR-CLONE` |
| `UC-KEEP-MY-ANSWER-THROUGH-A-CRASH` | `lifecycle/task-branch-contract`: A recorded position never implies an authorisation not yet recorded, One recovery owner per shape; `git/git-task-persistence`: Outcome protocol in task.json; `pipeline/stage-engine`: Human decisions as pass-through context | none |
| `UC-RECOVERY-TRUSTS-ONLY-THE-RECORD` | `lifecycle/task-branch-contract`: Classification reads the factory register, One recovery owner per shape; `lifecycle/claim-heartbeat`: Terminal outcomes reconcile through the branch | `GP-HOST-MODE-BEST-EFFORT` |
| `UC-READ-WHY-RECOVERY-ACTED` | `lifecycle/task-branch-contract`: Budgeted recovery with quarantine; `tracker/tracker-take`: Non-recoverable shapes quarantine on first classification; `observability/factory-logging`: No silent degradation | none |

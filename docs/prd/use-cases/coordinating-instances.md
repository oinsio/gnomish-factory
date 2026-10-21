# Coordinating instances

Group 3 of [`../use-cases.md`](../use-cases.md): the events between instances of one project,
from "the queue has work" to "a sibling continues what another started". The voices are the
factory operator and the factory instance itself; the stories follow the form and rules of the
index. Taking one task by hand is `UC-MANDATE-TASK` in group 2; what a crash does to one task
is group 8.

## Factory operator (`SH-FACTORY-OPERATOR`)

### UC-RUN-SLOTS-UNATTENDED — Let one daemon work the queue
**When** I start the daemon with a slot count,
**I want** it to claim from the queue while the project's open fronts are below the limit,
keep every claim alive while its task runs, and keep working through tracker and origin
outages without a restart,
**so that** a cron line and a slot count are all the operation I do.

### UC-STOP-AND-RESTART-WITHOUT-DEBT — Stop the daemon and start it again at no cost
**When** I stop the daemon (a signal, a drain, an upgrade) and start it again,
**I want** claiming to stop at once, running slots to end at their next round boundary within
the grace, their tasks back in the queue right away, and the new process to inherit nothing
from the old one,
**so that** a restart costs no lease wait and no cleanup by hand.

### UC-SHARE-A-PROJECT-ACROSS-MACHINES — Run one project from several machines
**When** I run instances of one project on several machines,
**I want** the numbers every instance must agree on (the beat, the lease, the open-front
limit) to come from the project's law, per-machine knobs only for slots and cadence, and the
shared tracker write budget stated so I can size the fleet,
**so that** two machines never disagree on when a claim is dead or how much work is open.

Tension with `UC-TAKE-OVER-A-DEAD-CLAIM`: a longer beat buys write budget for more slots and
lengthens the time a dead instance holds a task.

## Another factory instance (`AC-FACTORY-INSTANCE`)

### UC-TAKE-OVER-A-DEAD-CLAIM — Return a dead sibling's task to the queue, and only then
**When** a sibling's claim has gone stale by my own observation,
**I want** to return the task to the queue only after a grace window in which the holder could
have fenced itself, never on the strength of clocks compared across machines, and never while
the tracker is unreachable,
**so that** a slow sibling is never robbed and a dead one never holds a task past its lease.

### UC-STAND-DOWN-AS-A-ZOMBIE — Stop writing once my own lease is gone
**When** my own lease has lapsed (I was paused, partitioned or reaped),
**I want** to notice it before any reaper could reassign the task and stop writing, with every
later push of mine refused by the branch itself,
**so that** two holders never both write one task's records.

### UC-RESUME-ANY-INSTANCE — Continue what a sibling started
**When** I claim a task a sibling started (returned, parked or reaped),
**I want** everything I need in the tracker and on the task branch at origin, nothing in the
sibling's memory or disk, and the branch reconciled with origin before I resume,
**so that** any instance continues from the recorded position.

### UC-CLEAN-UP-AFTER-A-DEAD-SIBLING — Leave nothing of a crashed instance behind
**When** a sibling died leaving boxes, worktrees or a finish cut short,
**I want** to judge what is still alive by the claim lease alone, dispose the rest in steps that
grow less reversible with age, and complete the cut-short finish,
**so that** a crash leaks nothing and leaves no task stuck in a finished state it never reached.

## Coverage

The second column is the dry run of the `**Serves**:` lines: the capability and the
requirement headings that would carry the key. The third names entries of
[`../gaps.md`](../gaps.md).

| Story | Carried by today | Gaps |
|---|---|---|
| `UC-RUN-SLOTS-UNATTENDED` | `serve/factory-serve`: Scheduler runs N slots over the existing take cycle, WIP limit bounds fresh starts, Daemon tolerates tracker outages, Concurrent slots share one clone safely; `lifecycle/claim-heartbeat`: Heartbeat maintains the claim in place | `GP-SLOTS-RACE-ON-CLONE-CONFIG` |
| `UC-STOP-AND-RESTART-WITHOUT-DEBT` | `serve/factory-serve`: SIGTERM stops cleanly within grace, Drain mode exits when the work runs out, Restart is a clean start | `GP-NO-IMMEDIATE-RETURN` |
| `UC-SHARE-A-PROJECT-ACROSS-MACHINES` | `lifecycle/claim-heartbeat`: Protocol constants come from the factory's clone; `serve/factory-serve`: WIP limit bounds fresh starts; `operator-guide-serve.md` (instance knobs vs protocol constants, the write-budget coupling) | none |
| `UC-TAKE-OVER-A-DEAD-CLAIM` | `lifecycle/claim-heartbeat`: Staleness by local observation of claim versions, Reaper returns stale claims to circulation, Lost-detection strictly precedes reassignment, Tracker outage causes no false reaping; `tracker/tracker-port`: Stale-claim removal returns the task to circulation | none |
| `UC-STAND-DOWN-AS-A-ZOMBIE` | `lifecycle/claim-heartbeat`: Zombie fencing, Every (re)claim issues a monotonically increasing epoch; `lifecycle/task-branch-contract`: Claim-epoch fencing | none |
| `UC-RESUME-ANY-INSTANCE` | `tracker/tracker-take`: Any instance can pick up a returned task, Reconcile precedes resume, Divergence resolves automatically under the lease; `lifecycle/task-branch-contract`: Replica-pair reconciliation | none |
| `UC-CLEAN-UP-AFTER-A-DEAD-SIBLING` | `sandbox/sandbox-lifecycle`: Ownership labels at creation, Liveness oracle over claim heartbeat, Sweep decision matrix; `serve/factory-serve`: Worktree cleaner disposes aged task environments; `tracker/tracker-take`: Reconcile precedes resume | none |

# Working a task

Group 4 of [`../use-cases.md`](../use-cases.md): the events between "a stage's round opens" and
"the round's result goes to verification". The voices are the gnome, the pipeline author, the
security owner, the factory operator and the reviewer; the stories follow the form and rules
of the index. Verification itself is group 5, the answer to a decision is group 6.

## Gnome (`AC-GNOME`)

### UC-START-ROUND-BRIEFED — Start a round from one briefing
**When** a round of a stage opens for me,
**I want** one briefing holding the task goal, the stage's instructions and verify plan, the
input artifacts, the findings of the previous attempt and the decisions already given,
**so that** I spend the round on the work and never on reconstructing where the task stands.

### UC-WORK-IN-OWN-COPY — Work as a developer would
**When** I edit files and run the project's commands,
**I want** a full working copy with the project's toolchain, a reachable package source and
plain git,
**so that** the project's own build and tests run for me as they would for a developer.

Tension with `UC-ONE-EXIT-FROM-THE-BOX`: the gnome wants package sources reachable; the
security owner wants every destination denied unless listed.

### UC-COMMIT-AS-I-GO — Have my commits kept
**When** I commit during a round,
**I want** every commit kept on the task branch in the order I made it, also when the round
is cut short,
**so that** nothing I finished is lost and the next round starts from my last commit.

Tension with `UC-GNOME-CANNOT-FORGE-RECORDS`: the gnome wants plain git on its branch; the
security owner wants records on that branch the gnome cannot write.

### UC-ASK-A-DECISION — Ask when the task forks
**When** I reach a choice the instructions and the decisions given do not settle,
**I want** to state the fork with the options I see and stop, and to find the answer in my
next briefing,
**so that** I never guess on the author's behalf and a question costs no attempt.

## Pipeline author (`SH-PIPELINE-AUTHOR`)

### UC-SEAL-THE-LAW — Have the law applied as sealed
**When** a task starts from its base commit,
**I want** every round, check and judge of that task to run under the law as it stood at that
commit, whatever the gnome writes in its copy,
**so that** a stage's criteria mean the same for every attempt and cannot be loosened from
inside.

### UC-BRIEF-THE-GNOME-FROM-THE-LAW — Say what the gnome sees
**When** I write a stage,
**I want** to name in the law the instructions, rules and artifacts the gnome is briefed
with, and the executor, model and settings that run it,
**so that** a stage's behaviour lives in the law I review, not in the engine.

## Security owner (`SH-SECURITY-OWNER`)

### UC-KEEP-SECRETS-ON-THE-HOST — Keep every secret out of the gnome's hands
**When** a round needs the tracker, the model vendor or a registry,
**I want** the credentials to stay on the host side of the boundary, with the gnome's
processes holding none of them,
**so that** a deceived gnome can disclose nothing it was never given.

### UC-ONE-EXIT-FROM-THE-BOX — Make the guard the only way out
**When** a process of the gnome opens a network connection,
**I want** the guard to be its only route out, default-deny, with every denial recorded by
destination,
**so that** I can read what a task tried to reach and nothing left unobserved.

### UC-FAIL-CLOSED-BOX — Never run unprotected
**When** the box's protection cannot be established or verified before a round,
**I want** the round not to start and the task to stay claimable with an infrastructure
report,
**so that** "run without the box" is never a fallback anyone can reach.

### UC-GNOME-CANNOT-FORGE-RECORDS — Keep the factory's records out of the gnome's reach
**When** the gnome writes anything to its copy or its branch,
**I want** the factory's own records, the position, verdicts, decisions and approvals, told
from the gnome's writes by something the gnome cannot write,
**so that** a deceived gnome can neither advance a stage nor approve its own work.

## Factory operator (`SH-FACTORY-OPERATOR`)

### UC-BOUND-A-ROUND — Bound what one round can cost
**When** a round runs past its time or spend bound,
**I want** it ended, its commits kept, the reason recorded and the slot freed,
**so that** one runaway round never holds a slot or a budget for the night.

## Reviewer (`SH-REVIEWER`)

### UC-READ-THE-ATTEMPTS — Read every attempt from the branch
**When** I open the task branch, during the work or after it,
**I want** every attempt as a commit naming its stage, attempt number and outcome, with the
gnome's own commits inside it,
**so that** I can tell what was tried and in which order without the factory's logs.

## Coverage

The second column is the dry run of the `**Serves**:` lines: the capability and the
requirement headings that would carry the key. The third names entries of
[`../gaps.md`](../gaps.md).

| Story | Carried by today | Gaps |
|---|---|---|
| `UC-START-ROUND-BRIEFED` | `executor/agent-executor`: Round prompt composition, Shared briefing renderer; `pipeline/stage-engine`: Human decisions as pass-through context | none |
| `UC-WORK-IN-OWN-COPY` | `git/git-task-persistence`: Sandboxed working copy is an independent full clone; `sandbox/execution-environment`: Container adapter | `GP-NO-ARTIFACT-DEPOT` |
| `UC-COMMIT-AS-I-GO` | `git/git-task-persistence`: Gnome commits within a round, Salvage of interrupted rounds, Harvest protocol | none |
| `UC-ASK-A-DECISION` | `executor/agent-executor`: Decision-file protocol; `pipeline/stage-engine`: Gnome-initiated decision escalation | none |
| `UC-SEAL-THE-LAW` | `pipeline/pipeline-config`: Pipeline law binds per invocation; `executor/agent-executor`: Round timeout and control-file preflight; `sandbox/execution-environment`: Read-only control surfaces inside the box | `GP-LAW-UNSEALED-AGENT-FILES`, `GP-VERIFY-RUNS-GNOME-TREE` |
| `UC-BRIEF-THE-GNOME-FROM-THE-LAW` | `pipeline/pipeline-config`: Stage model mirrors the stage contract and all verify-check types, Artifact references by identifier; `executor/agent-executor`: Manifest settings with strict validation | none |
| `UC-KEEP-SECRETS-ON-THE-HOST` | `sandbox/execution-environment`: Layered positive environment allowlist; `executor/agent-executor`: CLI child environment is allowlisted; `operator/vendor-connection-profile`: Profile-resolved credential names join the scrub set | `GP-VENDOR-KEY-IN-BOX`, `GP-HOST-MODE-BEST-EFFORT` |
| `UC-ONE-EXIT-FROM-THE-BOX` | `sandbox/sandbox-egress`: The guard is the only route out, DNS resolves only through the guard, Denials are captured as structured findings | `GP-HOST-MODE-BEST-EFFORT` |
| `UC-FAIL-CLOSED-BOX` | `sandbox/sandbox-egress`: Mandatory fail-closed self-check; `sandbox/execution-environment`: Adapter passports and fail-closed reconciliation, Runtime outage is an infrastructure failure | none |
| `UC-GNOME-CANNOT-FORGE-RECORDS` | `lifecycle/task-branch-contract`: Classification reads the factory register, A recorded position never implies an authorisation not yet recorded; `git/git-task-persistence`: Factory register ref, Harvest protocol | `GP-HOST-MODE-BEST-EFFORT` |
| `UC-BOUND-A-ROUND` | `executor/agent-executor`: Round timeout and control-file preflight | `GP-NO-SPEND-BOUND` |
| `UC-READ-THE-ATTEMPTS` | `git/git-task-persistence`: Round commit of the whole working tree, State-file JSON contract v1; `pipeline/stage-engine`: Strict attempt persistence | none |

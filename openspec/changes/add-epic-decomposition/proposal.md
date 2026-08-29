# Proposal: add-epic-decomposition

## Why

A planning stage that discovers its task is really an epic has no legal exit
today: it can only pass, retry, or escalate to a human. The design session of
2026-08-29 settled the shape — decomposition is a *terminal stage verdict*
that materializes subtasks in the tracker and ends the parent's pipeline run,
keeping every pipeline linear (no DAG edges). `add-tracker-task-hierarchy`
delivers the tracker primitives (create-subtask with stable keys, hierarchy
facts, dependency-aware claim); this change delivers the verdict itself and
the crash-consistent creation protocol, so an epic claimed by any instance
converges to a complete, well-briefed child set no matter where a kill lands.

## What Changes

- ADDED: a **decomposition plan** artifact — a machine-verifiable stage
  output declaring the child set: per child a stable key, title, brief
  (objective, output format, owned paths, rabbit holes, no-gos), blocked-by
  edges among siblings, and a mandatory final **integration child** blocked
  by every other child, whose job is verifying the epic's own acceptance
  criteria after all siblings finish. (FR1, FR2)
- ADDED: a `decompose` terminal outcome in the stage engine: a stage declared
  decomposition-capable may produce the plan artifact; when verification
  passes and the plan declares an epic, the engine ends the run with
  `Decomposed` instead of advancing. A plan declaring "single task" advances
  normally — decomposition is conditional, never a mandatory step. (FR1)
- ADDED: the decomposition transition, ordered intent → effect → receipt:
  plan committed and pushed on the task branch first (intent), children
  created via `createSubtask` under the epic (effect), child refs recorded
  back on the branch (receipt), parent transitioned last. Recovery on any
  instance reconciles tracker children against the plan by stable key and
  converges idempotently; every kill window is a named shape with kill-point
  specs. (FR3, FR4, NFR-R1)
- ADDED: parent-epic lifecycle after decomposition: the parent leaves the
  ready feed into a waiting state that names its children; it is finished by
  the integration child's delivery, and an orphan policy defines what the
  sweeper does with open children when the epic is cancelled or escalated.
  (FR5)
- MODIFIED: `stage.yaml` gains an optional `decompose:` section (plan
  artifact reference and limits — max children, factory depth ≤ 2); absent
  section keeps today's behavior exactly. (FR6)
- MODIFIED: the observability ledger records the `decomposed` task outcome;
  wire tokens grow at both declared ends with the round-trip spec. (NFR-O1)
- Non-goals: context/decision inheritance between parent and children (next
  change, `add-decision-inheritance`); pipeline routing by task type;
  decomposing a subtask further (depth > 2); arbiter/human approval flavor of
  the decompose verdict (layered on later via `add-decision-arbiter`'s
  advisory mechanism once both changes exist). (NG1–NG4)

## Capabilities

### New Capabilities

- `task-decomposition`: the decomposition protocol — plan schema and child
  briefs, the decompose verdict semantics, the intent→effect→receipt
  transition with its kill-window shape set and recovery owner, parent-epic
  lifecycle, and the orphan policy.

### Modified Capabilities

- `stage-engine`: a decomposition-capable stage may end the run with the
  `Decomposed` terminal outcome after passing verification.
- `pipeline-config`: `stage.yaml` optional `decompose:` section; validation
  (plan artifact declared as a stage output, limits, only one
  decomposition-capable stage per pipeline).
- `tracker-take`: new terminal disposition — on `Decomposed`, take drives
  child creation and the parent transition; a resumed epic frozen
  mid-decomposition completes it before anything else.
- `git-task-persistence`: the plan and its receipt are durable branch writes
  in the task-branch contract's shape set.
- `serve-observability`: ledger `decomposed` outcome token and dashboard
  aggregation.

## Impact

- `domain` engine: new terminal outcome variant beside `Completed`/`Paused`;
  stage-result plumbing.
- `application` take path: new outcome dispatch arm (both host and container
  mode flows), decomposition driver, recovery-on-resume; ledger/dashboard
  token growth at both ends of the declared wire pair.
- `adapters/git`: plan + receipt persistence on the task branch.
- `.gnomish/` config surface: `decompose:` in `stage.yaml`; loader
  validation.
- Depends on: `add-tracker-task-hierarchy` (createSubtask, hierarchy facts,
  blocked-aware claim). Requirement IDs FR1–FR6, NFR-R1, NFR-O1, NG1–NG4,
  scoped to `add-epic-decomposition`.

# Design: add-pipeline-entry-precondition

## Context

Driven by FR1–FR7, NFR-R1, and NFR-S1 of the proposal. Canon: merge queues
attribute a red baseline to the base, not the candidate; Codex/Jules validate the
environment in a setup phase before agent work; Tekton rejects invalid runs before
start. Constraints that shape the approach:

- The stage-engine classification table hard-codes `command` exit ≠ 0 → Fail
  (quality) for verify checks — a check-shaped precondition would burn attempts.
- The claim→first-round region is dense with declared host/container pairs
  (`.claude/rules/manual-sync-pairs.md`): `TakeFreshClaim` ↔
  `TakeContainerFreshClaim` (recipe "harden → synthesize → createTask → run"),
  `TakeEngineExecution` ↔ `TakeContainerEngineExecution`, `TakeResumeRunner` ↔
  `TakeContainerResumeRunner`.
- `TaskExecutionEnvironment.materialize(branch, commitPin)` already supports a
  factory-chosen pin (`sandbox/core/.../TaskExecutionEnvironment.java:53`); the
  fresh-box verify model (materialize → exec → exit code → dispose without
  harvest) exists in `SandboxCheckEnvironmentSource` and `FreshJudgeEnvironments`.
- `harden-task-branch-contract` (implemented, unarchived) makes the task-creation
  commit carry both `task.json` and an initial `state.json`; the baseline SHA is
  `task.json`'s `baseCommit`.
- `add-pipeline-routing` (active) pins the pipeline definition (name + content
  hash) at first claim; `add-stage-iteration` also adds additive `state.json`
  fields.

## Goals / Non-Goals

**Goals:** wire the step with one shared implementation invoked from both mode
recipes; keep the escalation vocabulary, wire formats, and stage-engine contract
untouched; land the verdict crash-consistently per the checklist.

**Non-Goals:** see proposal NG1–NG5. Additionally out of scope: any change to
`engine.run` internals or `EnginePorts`; cross-task caching infrastructure.

## Decisions

**D1 — Engine-owned pre-run step, not a verify check.** (FR2, FR4) The
precondition runs in the application layer between task creation and the engine
run — it is not a stage, not a check, and never enters `engine.run`. *Rationale:*
the stage-engine spec's classification table hard-codes "command exit ≠ 0 → Fail
(quality)" for verify checks; a check-shaped precondition would burn a stage
attempt and feed the red baseline into gnome feedback — precisely the
mis-attribution this change removes. A pre-run step also needs no engine or port
changes. *Alternative rejected:* a synthetic "stage zero" with one command check —
inherits attempt accounting, feedback context, and stage lifecycle that are all
wrong for a probe the gnome never acts on; also pollutes every pipeline's stage
list.

**D2 — Declaration is a pipeline-level section of `pipeline.yaml`.** (FR1)
Optional `entry-precondition` block (command + timeout) at pipeline level; absent
→ silent skip. *Rationale:* the precondition is a property of the pipeline's
entry, not of any stage and not of the whole tree — fix-the-build and TDD-red
pipelines in the same `.gnomish/` legitimately differ. Under `add-pipeline-routing`
the block sits inside the named pipeline's own section, so the pinned content hash
covers it for free — the declaration a task runs under is frozen at first claim.
*Alternative rejected:* tree-wide `config.yaml` key — cannot vary per pipeline;
first-stage manifest key — smuggles a pipeline property into a stage contract.

**D3 — Reuse `CannotVerify`, do not extend the escalation set.** (FR4, NG3) A red
baseline escalates as `CannotVerify(check, reason, details)` with a synthesized
check reference naming the entry precondition, reason `baseline red at <SHA>`, and
the bounded output tail as details. *Rationale:* the sealed five-kind set
(`EscalationReport.java`) flows through state DTOs, status JSON, tracker rendering,
and declared wire pairs; a sixth kind would touch every one of them for a single
message. Semantically the fit is honest: the pipeline cannot verify anything
meaningful about gnome work while the baseline itself is red, and `CannotVerify`
already means "no verdict obtainable, no attempt burned, human attention needed".
The distinguishable fixed reason prefix keeps the report owner-attributable
(UX1). *Alternative rejected:* new `BaselineRed` kind — cost across sealed
permits, DTO mappers, and sync pairs is unjustified; extending the set remains
possible later if a consumer needs machine-level discrimination beyond the reason
prefix.

**D4 — Fresh-box probe at `baseCommit`, generalized from the existing model.**
(FR3, NFR-S1) The probe materializes a fresh environment pinned at `task.json`'s
`baseCommit`, execs the command with the declared timeout, reads the exit code and
bounded tail, disposes without harvest — the exact fresh-box verify shape.
`SandboxCheckEnvironmentSource.freshBox` is keyed to an attempt-commit workspace
and cannot be reused unchanged; the probe gets its own small environment source
following the same pattern (same `-v`-style environment keying rules from the
lifecycle decision matrix). No-mutation holds by construction: nothing is ever
harvested from the probe box. *Alternative rejected:* probing in the main task
environment — a red baseline's build residue would poison the box the first round
then runs in, and "no mutation" would rest on discipline instead of construction.

**D5 — Exit-code and timeout classification.** (FR4, FR5) Exit 0 → green; exit
126/127 → infrastructure (mirrors `ShellCommandCheckRunner`'s existing
convention); any other exit → red baseline; probe timeout → red baseline (the
owner's project cannot go green within its own declared bound — retrying won't
help, and attribution belongs to the owner; Q2 keeps a future `timeoutClass`
open); materialize failure, runtime outage, interruption → infrastructure via
existing channels. *Alternative rejected:* timeout as infrastructure — would
retry forever against a hanging baseline and never tell the owner.

**D6 — Verdict cached in `state.json`, keyed by baseline SHA + image identity.**
(FR6, NFR-C1) A new optional `entryPrecondition` component on the state DTO,
following the `egressCursor` precedent: additive under contract v1, no domain
`TaskState` counterpart, read off the DTO by the entry step directly. Key = the
task's `baseCommit` + an environment image identity token supplied by the bound
adapter (container: the configured image reference; host: a fixed host token — no
image-digest concept exists in the codebase today, and inventing one is not this
change's job; the `add-sandbox-hardening` fingerprint can strengthen the token
later). The write is a factory-side commit of its own between the creation commit
and the first round, epoch-stamped like every branch write. *Alternative
rejected:* verdict in `task.json` — that file is task identity written by
`TaskRepository` at lifecycle points; the verdict is execution bookkeeping and
belongs with the state file, whose round writers already preserve unknown-to-them
fields.

**D7 — Crash consistency (checklist of `.claude/rules/crash-consistency.md`).**
(NFR-R1) Durable steps in order: (1) task-creation commit (pre-existing), (2)
verdict commit + best-effort push, then green → engine run (existing protocol) or
red → outcome commit + tracker park (existing park protocol, delivery fence
included). Kill windows:

- **W1** — after creation, before the probe ran. **W2** — probe executed, verdict
  commit not durable. W1 and W2 freeze *identical* branch states: a created task
  with an initial state file, zero rounds, no verdict — the probe box is
  disposable garbage the existing orphan sweep already owns. Shape: the
  `task-branch-contract` capability's existing shape for a created-but-unstarted
  task (`Created`); no new shape is needed because the verdict field does not
  participate in classification. Recovery owner: the entry-precondition step
  itself, invoked by whichever take/resume runner picks the task up — it rolls
  forward by re-running the probe. Idempotent and convergent: the probe mutates
  nothing (D4), so re-run twice equals once; recovery on a recorded verdict is a
  no-op (the keyed cache short-circuits).
- **W3** — verdict committed, next step (engine run or park) not taken. The
  branch shows a verdict and zero rounds; pickup reads the verdict and continues
  forward — green proceeds to the first round, red re-drives the park through the
  existing intent → effect → receipt park protocol, whose recovery owner is
  unchanged.

Mutually-implied fields (verdict + key + tail) land in one commit. Constructive
before destructive: the verdict commit precedes the probe box dispose. Ordering
admits the sweeper: the creation commit (which admits the task) is first, the
verdict commit sits between, nothing destructive precedes a receipt. Atomicity:
git commit + atomic ref advance per the ADR 0003 durability table; durability
point is the successful push, best-effort as for all state commits. Kill-point
specs: task 6.3.

**D8 — Sync surfaces.** This change touches declared pairs and MUST change both
ends. The step itself is **one shared component** (a single
entry-precondition runner class in the application layer, taking the environment
source and state writer as ports) — no second implementation of the probe logic
exists, so the rule's preference order is satisfied by shared abstraction at the
step level. What remains mode-specific is the call site inside each recipe:

- `TakeFreshClaim` ↔ `TakeContainerFreshClaim` — the declared recipe invariant
  changes at both ends to "harden → synthesize → createTask → **entry
  precondition** → run"; the registry row's invariant wording in
  `.claude/rules/manual-sync-pairs.md` is updated in the same change.
- `TakeResumeRunner` ↔ `TakeContainerResumeRunner` (and the manual-mode
  `GitResumeRunner` ↔ `ContainerResumeRunner`, `GitModeRunner` ↔
  `ContainerGitModeRunner` if their pre-first-round paths bypass the take
  recipes) — the resume-before-first-round path gains the same guarded call at
  both ends.

Tasks.md carries every mirrored edit explicitly. *Alternative rejected:*
duplicating the probe logic per mode — a new undeclared pair, banned outright.

## Risks / Trade-offs

- [Three active changes plus this one add additive `state.json`/`task.json`
  fields; apply order decides version-gate sequencing] → all fields are additive
  under contract v1 with absent-tolerant readers; tasks note the coordination and
  the apply-order owner bumps the reference JSON once.
- [`add-pipeline-routing` not yet applied: the per-pipeline block and content
  hash do not exist yet] → the declaration lands in the current single-pipeline
  `pipeline.yaml` shape; hash coverage arrives automatically when routing's
  loader change subsumes the section into the pipeline block. Whichever change
  applies second reconciles the loader delta.
- [Reason-prefix discrimination ("baseline red at ...") is stringly for any
  future machine consumer] → accepted for now (D3); Q2/NG3 record the extension
  path.
- [Host-mode image identity token is weak (host toolchains drift without the
  token changing)] → accepted: host mode already offers no hermeticity; a stale
  green verdict there costs at most one mis-started task, and the owner can
  re-trigger by returning the task.
- [A long baseline build makes every affected task pay the probe once] → bounded
  by the declared timeout (NFR-P1); Q1 records cross-task caching as future work.

## Migration Plan

Purely additive: no declaration → no behavior change (M3); old state files read
with the verdict absent. Rollback = removing the declaration from `.gnomish/`;
recorded verdict fields are ignored by readers that do not know them under
contract v1.

## Open Questions

See proposal Q1 (cross-task verdict cache) and Q2 (configurable timeout class) —
both deferrable without changing specs, approach, or tasks.

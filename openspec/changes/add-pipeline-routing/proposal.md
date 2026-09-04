# Proposal: add-pipeline-routing

## Why

Every task today runs the same single pipeline: the definition is loaded once
per process before the tracker is even consulted, `PipelineDefinition` has no
name, and neither `task.json` nor `state.json` records which pipeline a task
runs — so a research task, a feature, a bugfix, and a task that fixes the
project's own factory configuration (its `.gnomish/` tree) are forced through
one conveyor built for one of them. The design session of 2026-08-29 settled the
shape from CI/tracker practice (Jira workflow schemes, Argo pin-at-submission,
Temporal pinned versioning; no shipped issue-driven agent routes by LLM):
pipelines stay linear, and variation is **routing** — several named linear
pipelines, one selected per task at claim by the task's declared type. The
absence of pipeline identity on the task branch is also a latent correctness
bug the moment a second pipeline exists: a resume with a colliding stage name
would silently continue on the wrong pipeline.

## What Changes

- ADDED: **task type as a core entity**: an operator-defined type designator
  carried as a task fact — kind `type` of the label-derived designator
  mechanism that `add-base-ref-resolution` introduces (this change is
  sequenced after it, per the 2026-09-04 session). Label-backed adapters
  (GitHub, in-memory) report raw labels; the kind-generic extraction applies
  a configured rule defaulting to the `type:` prefix; future adapters (or a
  GitHub extension to native issue types) may fulfill the same
  absent | single | conflict contract from native fields. Multiple
  conflicting type designators on one task are a routing error, not a pick.
  (FR2)
- ADDED: **named pipelines and a routing table** in `.gnomish/`: multiple
  linear pipelines sharing the same `stages/` pool, plus a table mapping type
  → pipeline with an explicitly declared default. A legacy single-pipeline
  `.gnomish/` loads unchanged as one pipeline routed for everything. Loader
  validation: every routed pipeline exists, the default exists, artifact-id
  uniqueness is scoped per pipeline, and the dangling-stage check becomes
  "referenced by at least one pipeline". (FR1, FR5)
- ADDED: **claim-time selection**: after claiming, the factory resolves the
  task's type through the table — typeless tasks take the default; a type
  absent from the table escalates loudly without burning attempts and
  releases nothing silently. Selection is deterministic and declarative —
  no model involvement. (FR3)
- ADDED: **pipeline pinning**: the resolved pipeline name and the definition
  content hash land in `task.json` in the same commit that creates the task
  on the branch. Every resume, on any instance, loads the pinned pipeline by
  name and verifies the hash; a missing name or changed hash is a
  pipeline-mismatch escalation, never a silent run on a different
  definition. The pin is immutable for the task's lifetime — retyping a
  task in the tracker affects only tasks not yet pinned; re-routing a
  pinned task is out of scope. (FR4, NFR-R1)
- MODIFIED: take/serve assembly inverts load-before-claim: definitions for
  all pipelines load and validate at startup, the per-task choice happens
  after claim; serve freezes one law per pipeline. `gnomish run` gains a
  `--pipeline` flag (default: the routing default). (FR3, FR6)
- Non-goals: LLM/triage auto-typing (a future triage pipeline can set the
  label; claim-time routing stays declarative); native GitHub issue types as
  the type source (future adapter extension); pipeline name in
  observability/dashboard surfaces (task branch and inspect carry it);
  mid-flight re-route or stage-mapping migration of a pinned task; any
  non-linear pipeline construct. (NG1–NG5)

## Capabilities

### New Capabilities

- `pipeline-routing`: the routing contract — task-type entity as designator
  kind `type` with its configurable selection rule, routing table semantics
  (explicit default, loud no-match), claim-time selection, pipeline pinning
  and resume verification, and the retype policy.

### Modified Capabilities

- `pipeline-config`: named pipelines sharing the stage pool; routing table
  loading and validation; per-pipeline artifact-id scope; union dangling
  check; legacy shape compatibility.
- `tracker-port`: designator kind `type` joins the label-derived designator
  mechanism (introduced by `add-base-ref-resolution`); the contract suite
  covers its three shapes for both adapters. No adapter-side mapping code:
  the GitHub and in-memory adapters already report raw labels after that
  change, and the `type:` extraction rule is core-side configuration.
- `tracker-take`: post-claim selection, typeless→default, unknown-type
  escalation without attempt burn; resume runs the pinned pipeline.
- `git-task-persistence`: pipeline pin (name + definition hash) in
  `task.json`, written in the task-creation commit; wire version bump.
- `manual-run`: `--pipeline` flag; `--from-stage` resolves against the
  selected pipeline.

## Impact

- `domain`: `PipelineDefinition` gains a name; task-type value object;
  per-pipeline validation scopes.
- `application`: selection point after claim in take/serve/run flows (both
  host and container mode twins — mirrored edits in scope), law-per-pipeline
  assembly, tracker config decoupled from the pipeline object.
- `adapters`: loader (multi-pipeline + routing table + selection rule),
  `task.json`/`state.json` mapper growth behind the version gate; no
  adapter-side label mapping (labels are reported raw per
  `add-base-ref-resolution`).
- Requirement IDs FR1–FR6, NFR-R1, NG1–NG5, scoped to
  `add-pipeline-routing`. Independent of the task-hierarchy trio; combines
  naturally with it later (an epic's children may route to different
  pipelines).
- Sequencing (revised 2026-09-04): this change lands **after**
  `add-base-ref-resolution` and consumes two of its products — the
  kind-generic label-derived designator mechanism (this change adds kind
  `type`) and the `task.json` pin-extension precedent (version gate +
  wire round-trip; that change bumps first, this one follows the pattern).
  The type-derived tier of the base-ref priority chain (a type→base-default
  column atop both mechanisms) is explicitly deferred there (its NG2) and
  lands in this change or a small follow-up — see design Open Questions.

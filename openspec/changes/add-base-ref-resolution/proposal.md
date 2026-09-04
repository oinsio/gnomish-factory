# Proposal: add-base-ref-resolution

## Why

Every task branch today starts from the local clone's HEAD: `TaskBranchCreator`
branches from whatever the factory clone happens to hold, `--base` overrides,
and the runner never fetches or pulls the base (design decision D7 of
`add-git-workflow`, archived 2026-07-19). D7 itself scheduled this revision:
"updating the clone is the human's job *(later the factory loop's)*". With
`gnomish take` and `gnomish serve` there is no human at the clone, so staleness
accumulates without bound — an autonomous factory quietly builds every task on
an ever-older base. Separately, "where to branch from" is hardwired to one
implicit answer, while real projects branch features from `develop`, hotfixes
from `release/1.18`-style series, and the like; the design session of
2026-09-04 (canonical branching models, backport/cherry-pick automation, coding
agents, CI systems) settled the shape: a **project-configured menu of allowed
bases** (patterns), a **per-task selection** (task metadata), and a
**fail-closed refresh fetch** of the chosen base before the branch is created.

D7's rejected alternative conflated fetch and pull: `git fetch` touches neither
the working tree, HEAD, nor local branches, so the FR7 invariant "the clone
itself is untouched" survives fetching. And the prohibition is already not
absolute — resume narrow-fetches exactly `gnomish/<task>` (D9). This change
supersedes the D7 wording in `git-task-persistence` while preserving the manual
`gnomish run` behavior it protected.

## What Changes

- ADDED: **base-ref resolution as a first-class capability**: a resolver at the
  single fresh-start funnel decides the base for every new task branch by a
  fixed priority — explicit `--base` > per-task designator from tracker
  metadata > repository default branch (queried from the remote, never a
  hardcoded `main`) > (manual run only) local HEAD. The two duplicated
  defaults in the adapters collapse; below the funnel everything speaks
  resolved refs. (FR4, FR10)
- ADDED: **`base:` block in `.gnomish/config.yaml`**: a menu of allowed bases
  as patterns with roles (`development` | `release`), an optional configured
  default, a `select.label` mapping rule, and a `type: patterns` discriminator
  for forward compatibility. Zero configuration stays valid and resolves to
  the repository default branch. The block is read **only from the refreshed
  default branch** — a gnome or task branch can never alter it. (FR1, FR2)
- ADDED: **generalized label-derived designator mechanism** on the tracker
  port: task facts yield a designator of a kind — absent, single, or conflict
  — with kind `base` as the first user; `add-pipeline-routing` later adds kind
  `type` on the same mechanism (dependency direction reversed versus the
  earlier plan: this change lands first). (FR3)
- ADDED: **fail-closed refresh fetch** of the resolved base (branch, tag, or
  SHA) between claim hardening and task creation in the autonomous paths; an
  unreachable remote is an infrastructure failure — bounded retries, no stage
  attempt burned, the claim is released and the task returns to Ready. (FR6,
  FR9)
- ADDED: **base pin in `task.json`**: `(resolved ref, SHA, source rule)`
  recorded in the task-creation commit, extending the existing write-only
  `baseCommit` slot behind the wire version gate; resume reads the pin and
  never re-resolves. (FR7)
- MODIFIED: `git-task-persistence` "Task branch naming and base" — the
  "SHALL NOT fetch or pull the base" and "never fetching anything else"
  wording is superseded; pull remains forbidden everywhere. (FR6, FR8)
- Preserved: manual `gnomish run` without `--base` still branches from the
  local HEAD with no fetch; `--base <sha>` and clones without an origin work
  exactly as before. (FR8)

## Capabilities

### New Capabilities

- `base-ref-resolution`: the resolution contract — base menu grammar and
  roles, per-task selection via the designator mechanism, source priority,
  fail-closed validation and escalation on underdetermined input, default
  branch discovery, refresh-fetch policy, and the base pin lifecycle.

### Modified Capabilities

- `git-task-persistence`: the branch-base requirement is rewritten (supersedes
  D7 wording); the base pin extends `task.json` behind the version gate; the
  refresh fetch joins the bounded-network rules.
- `pipeline-config`: the optional `base:` section loads and validates into the
  typed definition (patterns compiled at load, unknown keys are located
  errors); its law source is the refreshed default branch.
- `tracker-port`: task facts carry label-derived designators per configured
  kind (absent | single | conflict), covered by the contract suite for all
  adapters.
- `tracker-take`: auto and explicit take resolve the base after claim; `--base`
  keeps its explicit-single-start-only surface and becomes the top priority
  tier; fetch/resolution failure releases the claim as an infrastructure
  failure.
- `factory-serve`: the serve loop treats a base-resolution infrastructure
  failure as a released claim with suppressed repeat logging, not an abort and
  not an escalation.
- `module-layering`: a new dependency-free leaf module hosts the pure
  resolution policy (values in, decision out; no subprocess, no ports).

## Goals

- G1: every autonomously started task branches from a base that is explicitly
  chosen and freshly fetched at claim time — staleness no longer accumulates
  with serve uptime.
- G2: zero configuration keeps working and means "the repository's default
  branch as the remote reports it".
- G3: the allowed bases are project configuration (patterns), the choice is
  per-task (tracker metadata), and an out-of-menu or ambiguous choice
  escalates instead of guessing.
- G4: the designator mechanism and the `task.json` pin precedent land in a
  form `add-pipeline-routing` consumes without reshaping.

## Non-Goals

- NG1: propagation obligations (merge-back / cherry-pick-down bookkeeping) —
  change 2 of the 2026-09-04 session; this change only records the chosen
  rule in the pin.
- NG2: the "rule by task type" priority tier — depends on
  `add-pipeline-routing`; the priority chain leaves a named slot for it.
- NG3: state-dependent resolution predicates (git-flow "open release branch if
  one exists", OneFlow "latest version tag") — a second iteration of the
  declarative language, when a real project asks.
- NG4: an eligibility gate (task class × branch role); the `role` field is the
  hook, the gate is future work.
- NG5: version-to-branch resolution (Jira `fixVersion` → `release-{v}`) —
  deferred to the Jira adapter; the label capture group already permits it.
- NG6: a custom base-selection plugin (`BaseRefPolicy` port via
  `gnomish-plugin-api`) — only the `type:` discriminator forward-compatibility
  lands now.
- NG7: base freshness at the merge end (speculative merges, merge queues) —
  the host's merge queue's job; recorded as a design note.
- NG8: executing repo-provided code to choose a base — rejected permanently
  for security, recorded as a rejected alternative in design.
- NG9: one task targeting several bases — fan-out to per-base tasks via the
  task-hierarchy design, never retargeting.

## Users & Scenarios

- U1: an operator runs `gnomish serve` on a project that develops on
  `develop`; the project sets `base.default: develop` once, and every task
  branches from the freshly fetched `develop`.
- U2: a triager routes a hotfix by putting the `base:release-1.18` label on
  the task; the factory validates it against the menu, fetches the branch,
  and pins the choice. A label naming a branch outside the menu parks the
  task with a report instead of branching.
- U3: an operator uses manual `gnomish run` offline exactly as today — local
  HEAD, no network, no new failure modes.
- U4: external automation (Jira automation, a GitHub Action, a cron job)
  computes the base and sets the label — the documented customization path;
  the factory only validates and executes the choice.

## Requirements

### Functional

- FR1: `.gnomish/config.yaml` SHALL support an optional `base:` section: a
  `type` discriminator (only `patterns` supported now), an optional `default`
  ref, a `menu` of patterns each with an optional role (`development` |
  `release`; default `development`), and a `select.label` regex with exactly
  one capture group mapping a task label to a ref name. Patterns compile at
  load; unknown keys, an invalid regex, or a `default` matching no menu entry
  are located `ConfigError`s. An absent section is valid and means an empty
  menu with no configured default.
- FR2: the `base:` section SHALL be read only from the repository's default
  branch, refreshed by fetch, on the factory side — never from a task branch
  or a gnome-writable working copy. The rest of pipeline law continues to be
  bound from the already-chosen base (existing `PipelineLaw` semantics).
- FR3: task facts SHALL yield label-derived designators per configured kind:
  absent, a single value, or a conflict listing all values found; the adapter
  never resolves conflicts. Kind `base` is introduced by this change; the
  mechanism is kind-generic so `type` (routing) plugs in later. The tracker
  contract suite SHALL cover all three shapes for every adapter.
- FR4: base resolution SHALL follow one priority order — explicit `--base`,
  else the task's `base` designator validated against the menu, else the
  configured `default`, else the repository default branch; manual `run`
  without `--base` alone falls through to the local HEAD. A designator conflict
  or a designator naming a ref outside the menu SHALL escalate (park with a
  report) without burning a stage attempt and without silently substituting
  another base.
- FR5: the repository default branch SHALL be discovered from the remote at
  resolution time, never hardcoded; a clone with no origin remote falls back
  to the local HEAD only in manual run and refuses in autonomous modes.
- FR6: before task creation, the autonomous paths SHALL refresh the resolved
  base with a narrow fetch of exactly that ref (branches, tags, or a SHA
  reachable check), fail-closed: no branch is created from a base whose
  freshness could not be established. The fetch inherits the bounded-network
  rules (deadline, stall detection, credential scrubbing) and MUST NOT touch
  the working tree, HEAD, or local branches of the operator clone.
- FR7: the task-creation commit SHALL pin `(resolved ref, SHA, source rule)`
  in `task.json` behind the wire version gate; legacy files carrying only
  `baseCommit` stay readable. Resume — any instance, any mode — SHALL read
  the pin and never re-resolve the base from tracker data or configuration.
- FR8: manual `gnomish run` behavior is preserved: without `--base` it
  branches from the local HEAD with no fetch and no remote query; with
  `--base` the given ref wins everywhere it is accepted today; a clone
  without an origin remote keeps working offline. `git pull` remains
  forbidden on every path.
- FR9: a failure to discover, fetch, or resolve the base in take/serve SHALL
  classify as an infrastructure failure: bounded retries via the existing git
  retry policy, no stage attempt burned, the claim released so the task
  returns to Ready and any instance can retry later; no tracker escalation is
  posted for it.
- FR10: the resolution policy SHALL be a pure component — inputs are values
  (menu, designators, default-branch name, mode), output is a decision
  `(ref, rule, reason)`; it executes no subprocess and holds no port. All
  four fresh-start paths (host/container × run/take) consume it through the
  single existing funnel.

### Non-Functional — Reliability

- NFR-R1: base-refresh failures never corrupt state: resolution and fetch
  happen before any durable task-branch write; a crash between fetch and pin
  leaves a claim without a branch — the existing shape whose recovery
  re-resolves from scratch. The kill windows of claim → fetch → resolve →
  create join the crash-consistency checklist in design.
- NFR-R2: re-resolution before the pin exists is idempotent in effect (the
  menu and metadata are re-read; a different answer simply wins before
  anything durable references the old one); after the pin exists it never
  happens.

### Non-Functional — Observability

- NFR-O1: the first base-refresh failure per target logs WARN with an
  operator-event code; repeats are suppressed to DEBUG with a roll-up
  (existing `RepeatSuppressor`), so a dead remote does not flood serve logs.
- NFR-O2: the pinned `(ref, sha, rule)` is readable from `task.json` by the
  existing inspection surfaces — an operator can always answer "why did this
  task branch from there".

### Non-Functional — Security

- NFR-S1: the gnome cannot influence any future task's base: the `base:`
  block is read from the default branch only, and working-copy or task-branch
  copies are project content, never law.
- NFR-S2: the base choice is pinned at claim, before any agent runs; resume
  reads the pin and never re-resolves from data a gnome can write.
- NFR-S3: no repository-provided code executes to choose a base — selection
  is declarative pattern matching in the factory.

### Non-Functional — Performance

- NFR-P1: the claim path grows by at most one remote refs read (default
  branch discovery) plus one narrow single-ref fetch, both bounded by the
  existing git network deadline; manual run gains zero network calls.

### Non-Functional — Cost

- NFR-C1: resolution is deterministic and declarative — no model calls, no
  token cost.

## Operator Experience Criteria

- UX1: the `base:` block reads as the settled YAML shape — `type`, `default`,
  `menu` with `pattern`/`role`, `select.label` — consistent with the
  `tracker:` block precedent; config mistakes surface as located load errors,
  not runtime surprises.
- UX2: an escalation for an out-of-menu or conflicting designator names the
  offending label value(s) and the configured menu, so the human fixes the
  label or the menu, not a stack trace.
- UX3: `gnomish run` users notice nothing: identical commands, identical
  offline behavior.
- UX4: the operator guide documents the external-automation escape hatch
  (compute the base outside, set the label) as the supported customization
  path.

## Success Metrics

- M1: on a zero-config project under serve, a newly created task branch's
  recorded base SHA equals the remote default-branch tip observed at claim —
  staleness does not grow with serve uptime (asserted by integration spec
  against a local bare remote).
- M2: exactly one production code path decides the base: the two duplicated
  defaults (`GitFreshTaskSupport` null→HEAD, `TaskBranchCreator.startPoint`)
  are gone — verifiable by grep and covered by specs for all four fresh-start
  paths.
- M3: 100% of newly created `task.json` files carry the pin triple, and the
  rule vocabulary has a round-trip spec over every constant.
- M4: a simulated dead remote in serve produces one WARN plus suppressed
  repeats, zero burned attempts, zero tracker escalations, and the task is
  claimable again afterwards (spec-asserted).

## Open Questions

- Q1: where the `select.label` regex is applied — adapter-side (like
  routing's planned `type:*` prefix rule) or core-side over raw label facts.
  To be settled in design; the port contract shape (absent | single |
  conflict) is fixed either way.
- Q2: whether the refresh fetch materializes a remote-tracking ref or
  resolves via `FETCH_HEAD` — design detail with no contract impact beyond
  "the operator clone's local branches are untouched".

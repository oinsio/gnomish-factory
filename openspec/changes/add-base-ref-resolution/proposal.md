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
agents, CI systems) settled the shape: a **project-configured list of allowed
bases** (patterns), a **per-task selection** (task metadata), and a
**fail-closed refresh fetch** of the chosen base before the branch is created.

D7's rejected alternative conflated fetch and pull: `git fetch` touches neither
the working tree, HEAD, nor local branches, so the FR7 invariant "the clone
itself is untouched" survives fetching. And the prohibition is already not
absolute — resume narrow-fetches exactly `gnomish/<task>` (D9). This change
supersedes the D7 wording in `git-task-persistence` while preserving the manual
`gnomish run` behavior it protected.

A review of 2026-09-05 found a second gap the allowed-bases list opens: pipeline law is
read from the factory clone's **working tree** (`PipelineLawReader` over a
directory, the definition loaded once per process, the external-check pin
guard comparing against the literal `HEAD`). That equivalence — "clone working
tree = base" — holds only while the base *is* the clone's HEAD. With a task
based on `release/1.18` the law would come from whatever the clone has checked
out. The industry answer (GitLab via Gitaly, Jenkins `SCMFileSystem`, GitHub
Actions, Renovate) is uniform: read configuration by ref from the object
database, never from a working copy, with a trusted tier on the default branch
and the rest from the ref being built. This change adopts that mechanism.

## What Changes

- ADDED: **base-ref resolution as a first-class capability**: a resolver at the
  single fresh-start funnel decides the base for every new task branch by a
  fixed priority — explicit `--base` > per-task designator from tracker
  metadata > repository default branch (queried from the remote, never a
  hardcoded `main`) > (manual run only) local HEAD. The two duplicated
  defaults in the adapters collapse; below the funnel everything speaks
  resolved refs. (FR4, FR10)
- ADDED: **`task-branch.base` section in `.gnomish/config.yaml`**: the
  allowed bases as patterns with roles (`development` | `release`), an
  optional configured default, and a `type: patterns` discriminator for
  forward compatibility. The section sits under `task-branch:` — the glossary
  term for the branch it configures — so the key reads as "the base of the
  task branch", and later settings of the same branch land beside it instead
  of as new root keys (NG12). The section is domain policy only; how a
  tracker encodes a per-task selection is the tracker adapter's configuration
  (`tracker.<type>.designators`). Zero configuration stays valid and resolves
  to the repository default branch. The section is read **only from the
  refreshed default branch, once at startup** — a gnome or task branch can
  never alter it, and a claim never re-reads it. (FR1, FR2)
- ADDED: **generalized designator mechanism** on the tracker port: the
  `fetchTask` facts carry, per kind, a designator in one of three shapes —
  absent, single, or conflict — classified by one shared function and
  derived by each adapter from its own representation (GitHub: a configured
  label rule; a future Jira adapter: a native field). Kind `base` is the
  first user; `add-pipeline-routing` later adds kind `type` on the same
  mechanism (dependency direction reversed versus the earlier plan: this
  change lands first). A designator rule for kind `base` with no allowed
  bases to match against is a located load error. (FR3)
- ADDED: **fail-closed refresh fetch** of the resolved base (branch, tag, or
  SHA) between claim hardening and task creation in the autonomous paths; an
  unreachable remote is an infrastructure failure — bounded retries, no stage
  attempt burned, the claim is released and the task returns to Ready, and
  the outage is never charged to the task's abort accounting. (FR6, FR9)
- ADDED: **remote outage gate** in `serve`: one daemon-level state per remote
  target, opened by a slot's base-refresh infrastructure failure and consulted
  *before* every claim. While open, the feed claims nothing and the daemon
  probes the remote with a tracker-free reachability check on a jittered,
  growing, capped interval; the first successful probe closes it. Transitions
  are operator events, the state is a snapshot section, and a gate open past a
  configured duration logs ERROR once. (FR14, NFR-O1, NFR-O3)
- ADDED: **base pin in `task.json`**: `(resolved ref, SHA, source rule)`
  recorded in the task-creation commit, extending the existing `baseCommit`
  slot (written and carried through today, but no decision depends on it)
  behind the wire version gate; resume reads the pin and never re-resolves.
  (FR7)
- MODIFIED: `git-task-persistence` "Task branch naming and base" — the
  "SHALL NOT fetch or pull the base" and "never fetching anything else"
  wording is superseded; pull remains forbidden everywhere. (FR6, FR8)
- MODIFIED: `pipeline-config` "Pipeline law binds per invocation" — the law
  source in git modes becomes **git objects at a resolved commit** (the law
  commit), never the clone's working tree; two configuration tiers: the
  **trusted tier** (`tracker:`, `task-branch:`, later `routing:`) binds from the
  refreshed default branch, the **task tier** (stages, instructions,
  criteria, the rest of `config.yaml`) binds from the chosen base. (FR2, FR11,
  NFR-S1)
- ADDED: **law source abstraction** with two realizations — working tree
  (in-place mode, manual `run` without `--base`) and git objects at a commit
  (every path that resolved a ref); the external-check pin guard compares
  against the law commit instead of `HEAD`. (FR11, M5)
- ADDED: **resume binds the task tier from the tip of the pinned ref name**,
  so a human fix on the base reaches a returned task; a pinned ref that no
  longer resolves parks the task. Structural drift under a parked task is the
  pipeline pin's concern (`add-pipeline-routing`). (FR12)
- ADDED: **startup validation from the refreshed default branch, per-task
  binding from the base**: `serve`/`take` keep fail-fast on the default-branch
  definition; each task loads its task tier from its base's law commit, and a
  load error there parks the task with a configuration report. (FR13)
- Preserved: manual `gnomish run` without `--base` still branches from the
  local HEAD with no fetch and still reads law from the working tree, so
  uncommitted `.gnomish/` edits keep driving the pipeline author's loop;
  `--base <ref>` and clones without an origin still need no network, and
  with `--base` the law comes from git objects at that ref (FR8, FR11)

## Capabilities

### New Capabilities

- `base-ref-resolution`: the resolution contract — allowed-bases pattern
  grammar and roles, per-task selection via the designator mechanism, source priority,
  fail-closed validation and escalation on underdetermined input, default
  branch discovery, refresh-fetch policy, and the base pin lifecycle.

### Modified Capabilities

- `git-task-persistence`: the branch-base requirement is rewritten (supersedes
  D7 wording); the base pin extends `task.json` behind the version gate; the
  refresh fetch joins the bounded-network rules.
- `pipeline-config`: the optional `task-branch.base` section loads and validates into the
  typed definition (patterns compiled at load, unknown keys are located
  errors); the law-source requirement is rewritten around the law commit and
  the two configuration tiers; resume law source and per-task binding join it.
- `tracker-port`: the `fetchTask` fact set gains designators per kind
  (absent | single | conflict), derived adapter-side and classified by one
  shared function, covered by the contract suite for all adapters; the
  adapter factory seam reports the kinds an adapter is configured to
  extract.
- `github-tracker`: the `tracker.github` subsection gains the optional
  `designators` map (kind → one-capture-group regex, validated with the
  other keys); the adapter extracts candidates from issue labels by rule and
  reports its declared kinds.
- `tracker-take`: auto and explicit take resolve the base after claim; `--base`
  keeps its explicit-single-start-only surface and becomes the top priority
  tier; fetch/resolution failure releases the claim as an infrastructure
  failure with a typed take result and its own exit code, outside the abort
  accounting.
- `factory-serve`: a base-resolution infrastructure failure in a slot releases
  the claim and opens the remote outage gate — neither an abort nor an
  escalation; the gate holds the feed off the tracker until a probe succeeds;
  startup validates the default-branch definition, each slot binds its task's
  law from the task's base.
- `serve-observability`: the snapshot gains a `remote` section beside the
  existing `tracker` section — gate state, open since, last error, next probe
  — so "up but blocked on the remote" is answerable without reading logs;
  the ledger gains a `remoteOutage` line per closed outage.
- `observability/dashboard-page`: an open gate is an alarm line in the
  status card; the history section counts closed outages per day.
- `module-layering`: a new dependency-free leaf module hosts the pure
  resolution policy (values in, decision out; no subprocess, no ports). The
  same two requirements are MODIFIED by the active
  `add-subprocess-access-log`; this change is sequenced after it and its
  delta is layered on access-log's text (see design, Sync surfaces).

## Goals

- G1: every autonomously started task branches from a base that is explicitly
  chosen and freshly fetched at claim time — staleness no longer accumulates
  with serve uptime.
- G2: zero configuration keeps working and means "the repository's default
  branch as the remote reports it".
- G3: the allowed bases are project configuration (patterns), the choice is
  per-task (tracker metadata), and a disallowed or ambiguous choice
  escalates instead of guessing.
- G4: the designator mechanism and the `task.json` pin precedent land in a
  form `add-pipeline-routing` consumes without reshaping.
- G5: a dead remote costs the tracker nothing beyond the claims already in
  flight when it died, and advances no task toward backoff or quarantine.

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
  deferred to the Jira adapter, which derives kind `base` from its native
  field under the same three-shape contract.
- NG6: a custom base-selection plugin (`BaseRefPolicy` port via
  `gnomish-plugin-api`) — only the `type:` discriminator forward-compatibility
  lands now.
- NG7: base freshness at the merge end (speculative merges, merge queues) —
  the host's merge queue's job; recorded as a design note.
- NG8: executing repo-provided code to choose a base — rejected permanently
  for security, recorded as a rejected alternative in design.
- NG9: one task targeting several bases — fan-out to per-base tasks via the
  task-hierarchy design, never retargeting.
- NG10: a manual override of the remote outage gate (force open for planned
  maintenance, force close after a fix) and a per-task tally of outage
  releases that could one day become an operator signal — follow-up work
  once the gate has run in production.
- NG11: sharing gate state across factory instances — instances are
  stateless, probes are tracker-free, so N instances cost N cheap probes and
  nothing else.
- NG12: a configurable task-branch name prefix. The `gnomish/` prefix stays
  fixed by `git-task-persistence`; when a project needs an override, the
  `task-branch:` section introduced here is its home (`task-branch.prefix`,
  beside `task-branch.base`) — a follow-up change, not this one.

## Users & Scenarios

- U1: an operator runs `gnomish serve` on a project that develops on
  `develop`; the project sets `task-branch.base.default: develop` once, and every task
  branches from the freshly fetched `develop`.
- U2: a triager routes a hotfix by putting the `base:release-1.18` label on
  the task; the factory validates it against the allowed bases, fetches the
  branch, and pins the choice. A label naming a branch outside the allowed
  bases parks the task with a report instead of branching.
- U3: an operator uses manual `gnomish run` offline exactly as today — local
  HEAD, no network, no new failure modes.
- U4: external automation (Jira automation, a GitHub Action, a cron job)
  computes the base and sets the label — the documented customization path;
  the factory only validates and executes the choice.

## Requirements

### Functional

- FR1: `.gnomish/config.yaml` SHALL support an optional `task-branch`
  section holding a `base` subsection: a `type` discriminator (only
  `patterns` supported now), an optional `default` ref, and `allowed` — a
  list of patterns each with an optional role (`development` | `release`;
  default `development`). Patterns compile at load; unknown keys (a
  root-level `base:` included — there is no alias), an invalid pattern, or a
  `default` matching no allowed pattern are located `ConfigError`s. An
  absent section is valid and means no allowed bases and no configured
  default. The subsection holds no tracker-specific selection rule: how a
  task names its base is adapter configuration (FR3).
- FR2: the `task-branch.base` section SHALL be read only from the repository's default
  branch, refreshed by fetch, on the factory side — never from a task branch
  or a gnome-writable working copy. It belongs to the trusted tier of
  configuration (FR11), which binds **once at startup** (FR13) and is not
  re-read per claim: a change to the allowed bases merged to the default branch takes effect
  on the next start of `serve`/`take`, exactly as a `tracker:` change does.
  The task tier binds from the chosen base's law commit.
- FR3: the `fetchTask` facts SHALL carry designators per kind: absent, a
  single value, or a conflict listing all values found; the adapter never
  resolves conflicts. Each adapter derives the candidate values from its own
  representation — the GitHub adapter from labels through a configured
  `tracker.github.designators.<kind>` regex with exactly one capture group,
  validated on the adapter's own config seam — and classifies them with one
  shared function published by the port module, so no regex or shape logic is
  duplicated per adapter and no tracker concept (label) reaches core. The
  adapter factory seam SHALL report the kinds an adapter is configured to
  extract; a `base` rule configured while no base is allowed SHALL be a
  located load error at startup, because such a rule can only ever reject.
  Kind `base` is introduced by this change; the mechanism is kind-generic so
  `type` (routing) plugs in later. The tracker contract suite SHALL cover all
  three shapes for every adapter.
- FR4: base resolution SHALL follow one priority order — explicit `--base`,
  else the task's `base` designator validated against the allowed bases, else the
  configured `default`, else the repository default branch; manual `run`
  without `--base` alone falls through to the local HEAD. A designator conflict
  or a designator naming a ref outside the allowed bases SHALL escalate (park with a
  report) without burning a stage attempt and without silently substituting
  another base.
- FR5: the repository default branch SHALL be discovered from the remote at
  resolution time, never hardcoded; a clone with no origin remote falls back
  to the local HEAD only in manual run and refuses in autonomous modes.
- FR6: before task creation, the autonomous paths SHALL refresh the resolved
  base with a narrow fetch of exactly that ref, fail-closed: no branch is
  created from a base whose freshness could not be established. Where the
  fetched ref lands is fixed per kind: a branch updates its remote-tracking
  ref `refs/remotes/origin/<name>` (forced — it is the clone's cache of
  origin); a tag is written to `refs/tags/<name>` without force, exactly as
  git's own tag auto-following would, so a local tag of the same name
  pointing elsewhere refuses the fetch and parks the task with a report
  naming both commits; a bare SHA is checked locally first, fetched by SHA
  only when absent, and verified as a commit object afterwards — it needs
  no ref at all. The fetch never auto-follows unrelated tags and never
  relies on `FETCH_HEAD`: the SHA is always read back from the destination
  ref (or the object itself; `docs/adr/0006-base-refresh-fetch.md`). It
  inherits the bounded-network rules
  (deadline, stall detection, credential scrubbing) and MUST NOT touch the
  working tree, index, HEAD, `refs/heads/*`, or any pre-existing
  `refs/tags/*` entry of the operator clone.
- FR7: the task-creation commit SHALL pin `(resolved ref, kind, SHA, source
  rule)` in `task.json` behind the wire version gate — the kind (branch, tag,
  or commit) being the fact origin stated when the refresh classified the
  ref, never a configured value; legacy files carrying only `baseCommit`
  stay readable. Resume — any instance, any mode — SHALL read the pin and
  never re-resolve the base from tracker data or configuration; the
  re-fetch of the pinned ref name on resume SHALL use the pinned kind, so a
  same-named ref appearing later in the other namespace cannot redirect or
  park the task.
- FR8: manual `gnomish run` behavior is preserved: without `--base` it
  branches from the local HEAD with no fetch and no remote query, and its
  law source stays the working tree, so uncommitted `.gnomish/` edits apply;
  with `--base` the given ref wins everywhere it is accepted today, is
  resolved locally with no fetch, and — because a ref was resolved — its
  law is read from git objects at that ref (FR11). A clone without an origin
  remote keeps working offline in both forms. `git pull` remains forbidden
  on every path.
- FR9: a failure to discover, fetch, or resolve the base in take/serve SHALL
  classify as an infrastructure failure, distinguished **by cause, never by
  the step that observed it**, from task-level failures (a ref that does not
  exist, a per-ref refusal by a remote that still answers, a diverging local
  tag, a remote that refuses fetch-by-SHA, and an underdetermined designator
  are NOT this class; a credential the remote refuses before any ref is
  confirmed IS this class, per `docs/adr/0005-dependency-outage-accounting.md`). The same classification SHALL apply to every claim-time fetch of
  any ref, so a later change fetching another branch at claim (for example
  an epic branch for inherited context) inherits this rule rather than
  choosing its own: bounded retries via
  the existing git retry policy, no stage attempt burned, the claim released
  through the plain claim-release path so the task returns to Ready for any
  instance. The release SHALL write no abort marker and no comment: the
  outage is charged to the daemon, never to the task's abort accounting, so
  neither the task's backoff nor the K fuse moves. The failure is caught and
  classified at the fresh-claim step and never reaches the abort protocol as
  an uncaught exception. The take run ends with a typed result naming the
  infrastructure cause and carrying its own exit code.
- FR10: the resolution policy SHALL be a pure component — inputs are values
  (allowed bases, designators, default-branch name, mode), output is a decision
  `(ref, rule, reason)`; it executes no subprocess and holds no port. All
  four fresh-start paths (host/container × run/take) consume it through the
  single existing funnel.
- FR11: in every path that resolved a ref, pipeline law SHALL be read from
  git objects at one commit — the **law commit** — through a law source
  abstraction with exactly two realizations: working tree (in-place mode,
  manual `run` without `--base`) and git objects at a commit. The clone's
  working tree, index, and `HEAD` SHALL play no part in such a path. Both
  realizations SHALL resolve stage file references (`instructions`,
  `criteriaFile`) against one **law root** — the `.gnomish/` directory of
  the law commit or of the working tree — so a reference that validates at
  load reads at run; a symlink entry under the law root SHALL be refused by
  both realizations, never followed. Two
  tiers: the trusted tier (`tracker:`, `task-branch:`, and future selector blocks)
  binds from the refreshed default branch; the task tier (stages, stage
  instructions, judge criteria, the remainder of `config.yaml`) binds from
  the base's law commit — the pinned SHA on a fresh start. Manual `run` with
  `--base` is such a path: it resolves the ref locally, fetches nothing, and
  reads its law from git objects at that ref, offline. The external-check
  pin guard SHALL compare against the law commit, never against `HEAD`.
- FR12: on resume the task tier SHALL bind from the current tip of the pinned
  ref **name** (for a tag or SHA base this equals the pin), so a human fix to
  instructions or criteria on the base reaches a returned task; the base
  itself is never re-resolved (FR7). Autonomous resume narrow-fetches the
  pinned ref name first, fail-closed like the base refresh; manual resume
  without a reachable remote binds from the local ref tip. A pinned ref that
  no longer resolves SHALL park the task with a report, never fall back to
  the pinned SHA silently. Detecting a *structural* change (stage list, order, checks) under
  a resumed task is the pipeline pin's job (`add-pipeline-routing`): its hash
  SHALL cover structure only, not instruction or criteria content.
- FR13: `serve` and `take` SHALL load and validate the full definition from
  the refreshed default branch at startup (fail-fast, and the source for
  `board`/`dashboard` and the trusted tier), then load the task tier from the
  task's law commit after resolution, per task. A definition that fails to
  load from a base SHALL park that task with a configuration report — no stage
  attempt burned, no claim-release retry loop, since the failure is
  deterministic.
- FR14: `serve` SHALL keep one **remote outage gate** per remote target. A
  slot's FR9 failure opens it; the feed SHALL consult it before every claim
  and claim nothing while it is open. While open, the daemon SHALL probe the
  remote with a tracker-free reachability check on a jittered interval that
  grows from the idle interval to a configured cap; the first successful
  probe closes the gate, a failed probe re-arms the next interval
  (`docs/adr/0005-dependency-outage-accounting.md`). Recovery
  SHALL be confirmed by a probe, never by claiming a task; the probe
  interval SHALL reset to the idle interval only after the first successful
  base refresh that follows the close, never on the probe itself, so a
  flapping remote (probe passes, fetch fails) faces a growing pause instead
  of a claim-and-release cycle at feed speed. In-flight slots
  keep working under an open gate; single-shot `take` has no gate and ends
  per FR9. The gate is process-local: a restart forgets it and re-learns on
  the next failure.
- FR15: on every path that creates a task branch, the branch SHALL start
  from the same peeled commit the task's law was bound from, handed to the
  repository port as a typed object id — never as a ref name the adapter
  resolves again. No adapter SHALL run a name-to-commit resolution of the
  base: the one resolution happens where the law is bound, and the pin's
  `SHA`, the law commit, and the branch's first parent are one value by
  construction. This closes git's own refname disambiguation (a bare name
  prefers a local tag, then a local branch, over `refs/remotes/origin/`),
  which otherwise lets a stale local branch, a planted local tag, or an
  origin-only branch that no local ref names redirect or fail the start
  point after a successful refresh.

### Non-Functional — Reliability

- NFR-R1: base-refresh failures never corrupt state: resolution and fetch
  happen before any durable task-branch write; a crash between fetch and pin
  freezes the tracker in the existing `claim-heartbeat` shape `Claimed`
  with a dead holder, which the reaper alone restores to `Ready` on TTL
  expiry, and leaves no branch ref at all; the next claimant then
  re-resolves from scratch. The kill windows of claim → fetch → resolve →
  create join the crash-consistency checklist in design.
- NFR-R2: re-resolution before the pin exists is idempotent in effect (the
  allowed bases and metadata are re-read; a different answer simply wins before
  anything durable references the old one); after the pin exists it never
  happens.
- NFR-R3: the kill window between a failed base refresh and the claim release
  freezes the same `claim-heartbeat` shape `Claimed` with a dead holder,
  recovered by the reaper on TTL expiry alone; the next claimant re-resolves
  after that recovery. No new shape and no new recovery owner. The gate is
  in-memory and is not a durable step.

### Non-Functional — Observability

- NFR-O1: gate transitions, not individual failures, are the log signal:
  opening logs one WARN with an operator-event code naming the remote and
  the cause; closing logs one INFO recovery line with the outage duration and
  probe count; failures and probes in between are DEBUG with a periodic
  roll-up (existing `RepeatSuppressor`), so a dead remote does not flood
  serve logs. A gate open longer than a configured duration logs ERROR once
  with its own event code — a sustained outage is never silent.
- NFR-O3: the serve snapshot exposes the gate as a `remote` section (state,
  open since, last error, next probe time, consecutive failures) beside the
  existing `tracker` section; the ledger records one `remoteOutage` line per
  closed outage and no `taskOutcome` for a released task; the dashboard
  raises an alert condition while the gate is open and counts closed
  outages per day in its history.
- NFR-O2: the pinned `(ref, sha, rule)` is readable from `task.json` by the
  existing inspection surfaces — an operator can always answer "why did this
  task branch from there".

### Non-Functional — Security

- NFR-S1: the gnome cannot influence any future task's base: the
  `task-branch.base` section is read from the default branch only, and working-copy or task-branch
  copies are project content, never law. Reading law by ref also closes the
  serve drift where a `git pull` in the clone silently changed the law between
  tasks.
  A local branch or tag in the shared clone that carries a base's name
  SHALL NOT redirect any task's start point: the branch is created from the
  refreshed commit itself (FR15), so a gnome sharing the clone's refs in
  host mode gains no lever over a future task's base.
- NFR-S2: the base choice is pinned at claim, before any agent runs; resume
  reads the pin and never re-resolves from data a gnome can write.
- NFR-S3: no repository-provided code executes to choose a base — selection
  is declarative pattern matching in the factory.

### Non-Functional — Performance

- NFR-P1: the claim path grows by at most one remote refs read (default
  branch discovery) plus one narrow single-ref fetch, both bounded by the
  existing git network deadline; the trusted-tier fetch of the default
  branch happens once at startup, outside the claim path; autonomous resume
  grows by one narrow fetch of the pinned ref name; manual run gains zero
  network calls.

### Non-Functional — Cost

- NFR-C1: resolution is deterministic and declarative — no model calls, no
  token cost.

## Operator Experience Criteria

- UX1: the `task-branch.base` section reads as the settled YAML shape —
  `type`, `default`, `allowed` with `pattern`/`role` — under a root key that
  names the branch it configures; the selection rule sits beside the
  tracker's other label configuration (`tracker.github.designators`),
  consistent with the `tracker.github.labels` precedent; config mistakes,
  including a selection rule with no allowed base to match, surface as
  located load errors, not runtime surprises.
- UX2: an escalation for a disallowed or conflicting designator names the
  offending label value(s) and the configured allowed bases, so the human
  fixes the label or the list, not a stack trace.
- UX3: `gnomish run` users notice nothing: identical commands, identical
  offline behavior, uncommitted `.gnomish/` edits still apply without
  `--base`.
- UX5: a task parked because its base's `.gnomish/` fails to load names the
  base ref, the law commit, and the located errors — the same report shape as
  a startup load failure.
- UX4: the operator guide documents the external-automation escape hatch
  (compute the base outside, set the label) as the supported customization
  path.
- UX6: during a remote outage, `gnomish dashboard` and the snapshot answer
  "blocked on remote X since T, next probe at T'" from one place, and the
  operator guide names the gate, its log lines, and its exit code.

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
- M4: a simulated dead remote in serve produces one WARN on gate open and
  one recovery line on close, zero burned attempts, zero abort markers, zero
  tracker escalations, at most one claim per slot for the whole outage (none
  while the gate is open), and the task is claimable again after the first
  successful probe (spec-asserted on virtual time).
- M5: with the clone checked out at a ref other than the task's base, a take
  binds law and pin from the base — asserted by the law-binding contract
  spec; no `"HEAD"` literal remains in law-source or pin wiring (grep gate).

## Open Questions

- Q1 (resolved 2026-09-06, design D5): the selection regex is applied
  adapter-side; the port carries the classified designator, never raw labels.
  The first draft chose core-side extraction over raw label facts; the
  review of 2026-09-06 reversed it against the port's own vocabulary rule and
  the ports-and-adapters literature.
- Q2 (resolved 2026-09-06, design D11): the refresh fetch never reads
  `FETCH_HEAD` — one unlocked file per clone, overwritten by any concurrent
  fetch. Branches land in their remote-tracking ref, tags in `refs/tags/`
  without force, bare SHAs as objects only; the SHA is read back from the
  destination.
- Q3 (resolved 2026-09-06, design D16): the section was first specified as a
  root-level `base:` key with a `menu` list, and tasks 1–3 landed under that
  vocabulary. The review of 2026-09-06 found the root key ambiguous outside a
  git context and the list name a metaphor; the section is now
  `task-branch.base` with `allowed`, the concept is renamed in code and
  glossary alike (tasks 9.x), and the designator kind keeps the word `base`.

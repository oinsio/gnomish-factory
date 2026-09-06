# Design — add-pipeline-routing

## Context

See proposal.md — Why. Audit anchors (2026-08-29): the definition loads once
per process before any claim (`TakeCommand`, `ServeCommand`,
`PipelineStartup`); `PipelineDefinition` is unnamed and carries tracker
config; the law freezes per definition; artifact-id uniqueness and the
dangling-stage check assume the one `pipeline.yaml`; neither `task.json` nor
`state.json` records pipeline identity, and resume trusts the process-wide
definition (`Engine`'s `PipelineMismatch` fires only on unknown stage
names). Research grounding: Jira workflow schemes (type→workflow table with
declared default), Argo pin-at-submission / Temporal pinned versioning,
label-declarative routing in every shipped issue-driven agent, no-silent-
fallback lesson from k8s classes. User decision: task type is a core entity;
adapters map it; GitHub + in-memory use labels under an operator-configured
rule; native issue types are a possible future adapter extension. Revised
2026-09-04: `add-base-ref-resolution` lands first and introduces the
kind-generic designator mechanism and the `task.json` pin-extension
precedent (version gate + wire round-trip). Revised 2026-09-06 with that
change's D5: the port carries the classified designator (absent | single |
conflict per kind), each adapter derives it from its own representation
through one shared classification function, and the GitHub rule lives in
`tracker.github.designators` — never raw labels in core. This change
consumes both: kind `type` rides the mechanism, and the pipeline pin follows
the pin pattern. Context: driven by FR1–FR6, NFR-R1 from proposal.

## Goals / Non-Goals

**Goals:**
- One deterministic resolver owns routing; no scatter, no model involvement.
- Legacy `.gnomish/` trees and pre-routing task branches behave bit-for-bit
  as today.
- Pipeline identity becomes a durable branch fact with no unpinned window.

**Non-Goals:**
- No routing rules richer than exact designator match plus default (ordered
  rule engines can come later behind the same resolver seam).
- No taxonomy in code: the 3-type starter (`feature`/`bugfix`/`research`)
  is documentation and examples, never an enum — types are operator-defined
  strings validated only against the table.
- No observability field for pipeline name (task branch + inspect carry it;
  adding it later touches the snapshot/ledger pairs and gets its own pass).

## Decisions

### D1. TaskType is designator kind `type` on the shared mechanism

A small domain value (designator string, plus the conflict shape) rides the
`fetchTask` facts as kind `type` of the designator mechanism that
`add-base-ref-resolution` builds (its D5, revised 2026-09-06): the port
carries the classified designator; the GitHub adapter extracts candidates
from labels through the operator's `tracker.github.designators.type` rule
(one capture group) and classifies them with the port's one shared function;
the in-memory adapter sets the designator directly; a future Jira adapter
fulfills the same three-shape contract from a native field. Core never sees
a label. The rule has **no built-in default**: an adapter default would make
kind `type` "configured" on every project and defeat the dead-configuration
checks below; the operator writes `type: "type:(.+)"` beside the routing
table, and the starter documentation shows both together. Two startup
checks over the factory seam's configured-kinds query mirror the base
change's "rule without a menu" error: kind `type` extracted while no routing
table exists, and a routing table with type entries while no adapter
extracts kind `type`, are located `ConfigError`s — each configuration can
only ever miss. Rejected — the first draft (raw labels in the port facts, a
core-side rule with a `type:` default in the routing block): leaks the label
concept into core against the tracker-port vocabulary rule, and cannot
carry a native-field adapter. Rejected — closed enum in core: the taxonomy
belongs to the operator's repo (routing table), not the factory binary; an
enum would force a factory release per new type.

### D2. Routing lives in `.gnomish/` beside the pipelines

`pipeline.yaml` grows a shape with named pipelines and a `routing:` block
(type → name, `default:` mandatory when routing is present); the legacy
single-list shape loads as one pipeline named `default`, routed for
everything. Rejected — a separate routing file: one more cross-file
consistency surface for zero gain; the table and the pipelines it names
validate together in one loader pass. Rejected — binding on the tracker
side (label names a pipeline directly): couples tracker data to repo config
structure and breaks on pipeline rename; the k8s explicit-class escape
hatch can be added later without moving the table.

### D3. Selection is a post-claim step with one owner

All pipelines load and validate at startup from the refreshed default
branch (fail-fast stays — it is the trusted-tier load of
`add-base-ref-resolution` D14); the `routing:` table and the selection
rule are trusted-tier configuration and are read only from there. A single
resolver class (type facts + table → pipeline name, or a routing
escalation) is called at exactly two seams: fresh-claim synthesis (resolve
+ pin) and resume bootstrap (read pin + verify hash). The *definition* the
name denotes is then loaded from the task's law commit — the base SHA on a
fresh start, the pinned ref tip on resume — because tasks on different
bases legitimately see different task-tier definitions; a name the base's
tree does not define parks the task like any base load error. Revised
(2026-09-06): the earlier rejection of load-per-task ("same tree") assumed
one base for every task, which `add-base-ref-resolution` falsifies; the
fail-fast property survives through the startup load. Rejected — selection
before claim (feed-side): the feed would need type facts for filtering it
doesn't do; routing errors belong to the claimed task's escalation path,
not to feed skipping.

### D4. Pin = name + structural hash, in the task-creation commit

The pin lands in `task.json` at creation (mutually-implied facts in one
commit — no unpinned durable task exists). The hash covers the pipeline's
**structure** — stage names and order, each stage's verify-check list and
executor kind, artifact declarations — and deliberately excludes the
content of stage instructions and judge criteria: `add-base-ref-resolution`
D13 binds the task tier on resume from the tip of the pinned ref so that a
human fix to instructions or criteria reaches a parked task, and a content
hash would park the task the fix was meant to unblock. Resume loads by
pinned name from the task's law commit and verifies the structural hash;
mismatch escalates (extends the existing `PipelineMismatch` family). Legacy files read as
default-pinned with absent hash → verification skipped, matching today's
behavior. The wire growth follows the pin precedent set by
`add-base-ref-resolution` (its base pin bumps the version gate first; this
pin lands as the next additive field with its own round-trip coverage).
Rejected — pin by name only: silent drift when the definition
changes under a parked task is exactly the bug class the audit flagged.
Rejected — snapshotting the whole definition onto the branch (full Argo
copy): heavier, duplicates the law source, and the hash gives the same
guarantee with an explicit escalation instead of a silent stale copy.

### D5. Law per pipeline; tracker config decoupled from the definition

The law freezes per selected pipeline at the task's law commit (freeze
already takes a definition; each slot freezes the law of its task's
pipeline from its task's base — one law per (pipeline, law commit), cached
per pair only if measured to matter; the startup load is validation and
display, never a slot's law). Tracker config moves off `PipelineDefinition`
onto the load outcome's tree-wide config, since board/dashboard reach
tracker config through the pipeline object today only by historical
accident — and it is trusted-tier configuration, so it belongs with the
default-branch load, not with a per-task definition. Rejected —
one union law: coherent (refs are path-keyed) but makes "which files bind
this task" fuzzy and couples unrelated pipelines' staleness.

### D6. Sync surfaces

Scout results (2026-08-29): no `Kept in sync with` markers exist outside
the salvage pair, but the registry lists mode-twin rows this change
touches, and mirrored edits are in scope:

- **`TakeFreshClaim`/`TakeContainerFreshClaim`** (fresh-claim recipe): the
  recipe gains "resolve type → pin" — implemented once in the shared
  resolver/pin step and called from both twins; the mirrored edit per twin
  is the call site, with a parity spec asserting both produce an identical
  pin. `add-base-ref-resolution` edits the same pair first (its
  fetch+resolve step between `harden()` and `createTask()`); this change's
  step layers after it in both twins, and the pair's `Kept in sync with`
  invariant line grows once more.
- **`TakeResumeRunner`/`TakeContainerResumeRunner`** and
  **`GitResumeRunner`/`ContainerResumeRunner`** (resume control flow): both
  gain the read-pin + hash-verify bootstrap via the shared resolver; call
  sites mirrored, parity spec required.
- **`GitModeRunner`/`ContainerGitModeRunner`** (manual run): `--pipeline`
  selection resolves before mode dispatch in shared argument handling, so
  the twins receive an already-selected definition — no divergent logic is
  added to either.
- **Wire mappers**: `task.json` growth stays inside the single
  `TaskJsonMapper` behind the version gate with its round-trip spec —
  writer and reader are one class, no new pair. Snapshot/ledger pairs are
  untouched (observability field is a non-goal).
- **No new parallel implementation**: routing logic exists exactly once
  (the resolver), and type extraction reuses the one designator mechanism
  from `add-base-ref-resolution` — one shared classification function, one
  GitHub rule map entry, governed by the port contract suite (shared
  abstraction), same as `add-tracker-task-hierarchy` D6.

### D7. Spike pipeline content is examples, not engine features

The research pipeline's specifics (timebox, one-page report contract, judge
criteria, follow-up spawning) are `.gnomish/` authoring shipped as a
documented example; the engine gains nothing research-specific. This keeps
the routing change mechanism-only and the taxonomy operator-owned.

The same holds for a `factory-config` type — tasks whose work edits the
target repo's own `.gnomish/` tree (fixing a stage prompt, adjusting the
routing table itself). It is plain routing-table authoring: the task routes
to its own pipeline, runs under the law frozen from the *pre-edit* config,
and its edits become law only for later tasks once a human merges them (the
D14 law-freeze of add-sandbox-core). No engine special-casing exists or is
needed; the example belongs in the operator guide beside the research one.

## Risks / Trade-offs

- [Hash-mismatch escalations after every structural `.gnomish/` edit annoy
  operators with parked tasks] → deliberate: a changed structure under a
  parked task *should* be a human decision (Jira publish forces the same);
  instruction and criteria edits do not trip it (D4); the escalation report
  names the pinned and current hashes; an explicit re-pin/migration
  operation is future work if the friction proves real.
- [Serve holding N frozen laws grows memory] → laws are file maps of small
  markdown; N is the number of live (pipeline, law commit) pairs, bounded by
  the slot count if not cached (sprawl anti-pattern documented for
  operators).
- [Type conflict shape adds a third arm to every consumer of the type
  fact] → confined to the resolver; the port type makes the three shapes
  exhaustive (sealed), so the compiler enforces handling.
- [Version bump of `task.json` while three other proposed changes also
  touch it] → additive fields each; the apply order is now fixed for the
  first two — `add-base-ref-resolution` bumps first (base pin), this change
  follows — and the round-trip spec grows with each; the remaining changes
  stay flagged for the roadmap's apply sequencing.

## Open Questions

- Example starter table shipped in docs (`feature` default, `bugfix`,
  `research`, `factory-config`) — exact example stage lists to be settled
  when writing the operator guide during implementation.
- Type-derived base defaults (the "rule by task type" tier that
  `add-base-ref-resolution` defers as its NG2): one column mapping type →
  base default atop both mechanisms. Lands either in this change's routing
  table or as a small follow-up — decide when this change is applied,
  without reshaping either mechanism.

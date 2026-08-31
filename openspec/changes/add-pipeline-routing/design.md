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
adapters map it; GitHub + in-memory use labels with the `type:*` default,
operator-configurable; native issue types are a possible future adapter
extension. Context: driven by FR1–FR6, NFR-R1 from proposal.

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

### D1. TaskType is a core value; adapters report it as a fact

A small domain value (designator string, plus the conflict shape) rides the
task facts like abort/claim facts do. Rejected — label parsing in core: the
core would then know GitHub label conventions, breaking the ports rule that
adapters speak tracker dialects. Rejected — closed enum in core: the
taxonomy belongs to the operator's repo (routing table), not the factory
binary; an enum would force a factory release per new type.

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

All pipelines load and validate at startup (fail-fast stays); a single
resolver class (type facts + table → named definition, or a routing
escalation) is called at exactly two seams: fresh-claim synthesis (resolve
+ pin) and resume bootstrap (read pin + verify hash). Rejected — load-per-
task: re-reading `.gnomish/` per claim buys nothing (same tree) and costs
the fail-fast property. Rejected — selection before claim (feed-side): the
feed would need type facts for filtering it doesn't do; routing errors
belong to the claimed task's escalation path, not to feed skipping.

### D4. Pin = name + content hash, in the task-creation commit

The pin lands in `task.json` at creation (mutually-implied facts in one
commit — no unpinned durable task exists). Resume loads by pinned name and
verifies the hash against the freshly frozen law source; mismatch escalates
(extends the existing `PipelineMismatch` family). Legacy files read as
default-pinned with absent hash → verification skipped, matching today's
behavior. Rejected — pin by name only: silent drift when the definition
changes under a parked task is exactly the bug class the audit flagged.
Rejected — snapshotting the whole definition onto the branch (full Argo
copy): heavier, duplicates the law source, and the hash gives the same
guarantee with an explicit escalation instead of a silent stale copy.

### D5. Law per pipeline; tracker config decoupled from the definition

The law freezes per selected pipeline (freeze already takes a definition;
serve freezes one law per pipeline at startup and hands each slot its
task's law). Tracker config moves off `PipelineDefinition` onto the load
outcome's tree-wide config, since board/dashboard reach tracker config
through the pipeline object today only by historical accident. Rejected —
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
  pin.
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
  (the resolver); the GitHub and in-memory adapters implementing the type
  fact are governed by the port contract suite (shared abstraction), same
  as `add-tracker-task-hierarchy` D6.

### D7. Spike pipeline content is examples, not engine features

The research pipeline's specifics (timebox, one-page report contract, judge
criteria, follow-up spawning) are `.gnomish/` authoring shipped as a
documented example; the engine gains nothing research-specific. This keeps
the routing change mechanism-only and the taxonomy operator-owned.

## Risks / Trade-offs

- [Hash-mismatch escalations after every `.gnomish/` edit annoy operators
  with parked tasks] → deliberate: a changed law under a parked task
  *should* be a human decision (Jira publish forces the same); the
  escalation report names the pinned and current hashes; an explicit
  re-pin/migration operation is future work if the friction proves real.
- [Serve holding N frozen laws grows memory] → laws are file maps of small
  markdown; N is governed small (sprawl anti-pattern documented for
  operators).
- [Type conflict shape adds a third arm to every consumer of the type
  fact] → confined to the resolver; the port type makes the three shapes
  exhaustive (sealed), so the compiler enforces handling.
- [Version bump of `task.json` while three other proposed changes also
  touch it] → additive fields each; apply order decides who bumps first;
  the round-trip spec grows with each — flagged for the roadmap's apply
  sequencing.

## Open Questions

- Example starter table shipped in docs (`feature` default, `bugfix`,
  `research`) — exact example stage lists to be settled when writing the
  operator guide during implementation.

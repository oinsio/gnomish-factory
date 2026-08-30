# Design: enforce-artifact-contracts

## Context

Driven by FR1–FR7, NFR-R1, NFR-S1 and UX1/UX2 of the proposal. Today `ArtifactOutput` is
`record(String id)`; `ArtifactGraphRule` lints the DAG at load time, `BriefingSections`
renders inputs symbolically, and nothing at runtime consumes `outputs:`. The engine sees
the working copy only through the opaque `Workspace` marker; in container mode the gnome's
files live inside the box and reach the factory only as harvested commits on the task
branch (cf. `RoundBoundaryCheck` / `HarvestedBoundaryCheck`, `GitAttemptPersistence` /
`EnvironmentAttemptPersistence`). A passing round's persisted state already carries the
advanced position (FR4 of harden-task-branch-contract). Canon: Tekton validates declared
params/workspaces before a run starts, as a class distinct from task failure.

## Decisions

**D1 — `path` lives on outputs only, validated lexically at load (FR1, FR2, NFR-S1).**
`ArtifactOutput` gains an optional `path` (nullable in the DTO, `Optional`-shaped in the
domain model): one file path or glob relative to the working copy root. Inputs never
declare paths — an `internal` input's path is its producer's; `source` inputs stay
symbolic. Load-time validation is the pin-paths class of check: relative, normalized (no
`.`/`..` segments), not absolute, and accepted by `FileSystem#getPathMatcher("glob:…")` as
syntactically valid — reported as located `ConfigError`s, with no existence check (no
working copy exists at load time, and the `.gnomish/`-root confinement of file references
deliberately does not apply). *Rationale:* one declaration point keeps the DAG the single
source of the producer–consumer link; lexical validation keeps the loader read-only and
traversal-safe. *Alternative rejected:* per-input path declarations — they would duplicate
the path at every consumer and invite divergence the DAG exists to prevent.

**D2 — the gates read the persisted task-branch tip, not a live filesystem (FR3, FR4, FR7).**
This is the host/container asymmetry question resolved. In container mode the engine can
never see the box's filesystem; what it *can* always see — in both modes — is the task
branch tip the round persistence just wrote (host: `GitAttemptPersistence` commit in the
worktree repo; container: the harvested snapshot commit in the factory clone). The gates
therefore validate the **durable** state: the producer gate runs after the passing round's
persist (the tip exists and, per harden-task-branch-contract FR4, already carries the
advanced position), the consumer gate at stage entry before the first round (the tip is
what the workspace was materialized from). *Rationale:* the contract protects
*resumability* — a file present on a host disk but absent from the commit would be lost to
the next resuming instance anyway, so the tip is the truth that matters, and one read
target serves both modes symmetrically. *Alternative rejected:* probing the live working
copy per mode (host FS walk + container `exec` into the box, the `RoundBoundaryCheck` /
`HarvestedBoundaryCheck` shape) — it needs a second, box-side implementation, validates a
state that may differ from what persists, and reintroduces exactly the divergence-prone
twin pair this design avoids.

**D3 — a miss escalates through the existing `CannotExecute` report (FR5, UX1).** The
closed escalation set stays closed. `CannotVerify` is wrong: it carries a `CheckRef` into
the stage's verify list, and the gate is not a verify check — verify already *passed*.
`CannotExecute` is right by meaning — "the factory cannot run/advance this stage for
non-quality reasons, no attempt burned" — and only its documented wording generalizes: the
"no round recorded" clause remains true for the executor-throw and consumer-gate paths,
while a producer-gate miss preserves the already-recorded passing round. The cause names
stage, artifact id, path, and gate, and attributes the miss to the factory/pipeline.
*Alternative rejected:* a new `ArtifactMissing` report kind — every consumer of the sealed
vocabulary (tracker rendering, ledger/wire tokens, dashboards) would grow a variant for a
distinction the operator does not act on differently; the closed set expresses it.

**D4 — engine-side gate, matching rule in the domain, enumeration behind one port (FR7,
NFR-P1).** The gate logic (which stages to gate, when, claim resolution, escalation
shaping) lives once in the domain engine. File visibility crosses the purity boundary
through a small new engine port — `ArtifactFileSource` with a single "enumerate the
relative paths of the current persisted working-copy state" operation; the domain matches
globs against that one listing (one enumeration per gate, NFR-P1; symlinks are entries in
the listing, never followed out of the root, NFR-S1). The production adapter is a single
git implementation (`git ls-tree -r --name-only <tip>`), wired with the worktree repo in
host mode and the factory clone in container mode — same class, different constructor
argument. *Alternative rejected:* a `boolean exists(glob)` port — it pushes the matching
rule into every adapter, multiplying the surface D5 must then declare.

**D5 — Sync surfaces.** None new: this change adds no parallel implementation and touches
no pair declared in `manual-sync-pairs.md`. The gate rule has exactly one domain
implementation, and the git enumeration adapter is one class wired twice (D2/D4), so no
host/container twin arises — deliberately, in contrast to the `RoundBoundaryCheck` /
`HarvestedBoundaryCheck` pair, which needs pre-persist views the gates do not. Should a
future git-less in-place mode need a filesystem-walk enumerator, that second `ArtifactFileSource`
implementation must be declared a pair (both-end `Kept in sync with` markers, invariant:
same enumeration universe) or folded into an abstraction per the rule's preference order.
`BriefingSections` (FR6) is not an end of any declared pair.

**D6 — no `schemaVersion` bump (proposal "What Changes").** `path` is additive and
optional; the pipeline-config versioning stance rejects unknown *versions*, not additive
fields within the supported one. An older factory reading a path-declaring manifest fails
closed with a located structural error at worst — acceptable, since factory and loader
ship together. *Alternative rejected:* bumping the version — it would force every existing
`.gnomish/` tree to change for a feature it may never opt into.

**D7 — crash consistency: the gates add no durable step (NFR-R1).** Both gates are
read-only, so the checklist of `.claude/rules/crash-consistency.md` closes trivially: no
new kill windows, no new branch or tracker shapes, ordering unchanged (gate reads happen
between existing durable steps). The recovery owner for "producer passed but artifact
missing" is the **consumer gate**: whatever a crash, kill, or dirty resume froze, the next
run re-derives the same verdict from the same tip — idempotent and convergent. The
producer gate is early detection of the same defect, not a second owner: both emit the
same escalation for the same broken state, and escalation itself rides the existing
tracker machinery unchanged.

## Risks / Trade-offs

- Validating the tip instead of the live FS means a producer-gate miss is detected after
  the passing round persisted (position already advanced) → harmless by D7: the state is a
  legal, named shape and the consumer gate converges it on any resume.
- `git ls-tree` on a large repo per gate → single listing per gate (NFR-P1), plain
  name-only plumbing; negligible next to a round's agent run.
- Glob semantics ("at least one match") may under-constrain exact-arity artifacts → Q1 of
  the proposal; revisit on real need.
- An old factory rejects a new manifest with a located error rather than ignoring `path`
  (D6) → fail-closed is the intended direction; message names the unknown field.

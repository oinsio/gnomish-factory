# Design — add-decision-inheritance

## Context

See proposal.md — Why. Existing machinery this builds on: decisions round-trip
`task.json` and render into every briefing (`BriefingSections`, single
assembly point) under "context never commands"; the arbiter change gives
records authorship/scope and one append owner; the decomposition change gives
the plan (briefs), hierarchy facts, and the epic's waiting lifecycle; the
pipeline law shows the freeze-at-invocation pattern. Research grounding
(session 2026-08-29): binding-verbatim/summarize/retrieve selection tiers,
supersession-not-editing (ADR practice), no-override conflict rule,
roll-up-at-completion as the cross-branch visibility mechanism. Context:
driven by FR1–FR5, NFR-R1, NFR-C1 from proposal.

## Goals / Non-Goals

**Goals:**
- Inheritance reuses the proven single-task decision flow end to end: same
  append owner, same briefing contract, same verbatim rendering.
- The epic file is derived truth: every record on it is re-derivable from
  some child's (or the plan's) own branch, so repair never guesses.
- Zero behavior change for non-hierarchical tasks, byte-for-byte briefings.

**Non-Goals:**
- No summarization tier in this change: the bound plus escalate-on-oversize
  replaces it until real epics show the need (YAGNI; the record schema does
  not block adding a summary view later).
- No retrieval tooling: the epic file rides the workspace checkout, and the
  gnome can Read it like any file.
- No automatic staleness detection beyond premises being recorded; flagging
  a decision whose premise died is briefing instruction, not engine logic.

## Decisions

### D1. Inherited context frozen at invocation, like the law

Fetch epic-branch file + plan brief at claim/resume bootstrap, freeze into
an inherited-context value object carried beside `TaskContext`. Alternative
— live reads each round — rejected: mid-invocation context drift is exactly
what the law freeze exists to prevent (anti-reward-hacking, reproducible
rounds); a decision accepted mid-flight arrives at the next invocation, and
decision resume already creates one.

### D2. Roll-up at finish, integration-child repair, file-as-cache

Exports land on the epic branch under the finishing child's claim (before
tracker finish); the only cross-branch *read* fan-out lives in the
integration child's completeness check, which re-derives missing exports
from sibling branches. Alternative — assemble from N child branches at every
subtask claim — rejected: N fetches per claim, racing live siblings, and no
single file a human can audit; it survives demoted to the repair path where
it runs once, under one claim, against finished (immutable) siblings.

### D3. Conflict rule enforced at the append gate, not by prompt alone

The single append owner rejects a subtree-scoped record whose question
matches an inherited binding record (append-time check against the frozen
inherited set), converting it into the proposed-supersede escalation. The
briefing states the rule (cheap first line of defense), but the gate is
code. Alternative — prompt-only discipline — rejected: MAST-class failures
show agents ignore inherited inputs under pressure; a gate makes the
override structurally impossible, satisfying "no verification gate weakens".

### D4. Oversize binding set fails toward escalation

A bound (configured, generous) on the injected verbatim set; exceeding it
escalates the epic ("binding set too large — split or supersede") instead of
truncating or summarizing silently. Alternative — auto-summarize overflow —
rejected for this change: a summarized *binding* record is no longer the
record (lossy law), and the correct fix is fewer, better-scoped decisions —
a human/arbiter call at the epic level.

### D5. Sync surfaces

Scout results (2026-08-29): no declared pair is touched — decision-file
transports (`DecisionFileTransport`/`BranchDecisionFile`), salvage,
attempt-persistence, and ledger pairs are all outside this change's write
paths (the epic-file write is a new factory-side path on the epic branch,
not the gnome-side decision channel and not the attempt sequence). The
change is governed by two one-owner rules instead: (1) the epic decisions
file gets exactly one mapper class owning read and write, shared by roll-up
writer, claim-time reader, and integration-child repair — the rejected
alternative (a reader in the take path mirroring the git adapter's writer
tokens) would create a new undeclared wire pair, the registry's leading bug
shape; a values()-iterating round-trip spec pins it. (2) All decision
appends — task-local and epic-file — flow through the arbiter change's
single append owner, so no second serializer of decision records appears.
Mode twins are untouched by construction: bootstrap and finish ordering
live in the mode-neutral classes; if implementation must touch a twin
runner (registry rows `TakeResumeRunner`/`TakeContainerResumeRunner`), the
mirrored edit and its parity assertion are in scope of the touching task.
Sync surfaces: no declared pair touched; new parallel implementations
prevented by single-mapper and single-append-owner construction.

## Risks / Trade-offs

- [Epic-branch fetch at every subtask claim adds latency and a failure
  mode] → one fetch of one branch, conditional (remembered tip); failure is
  a loud infrastructure escalation, never silent bare rounds (spec'd).
- [Append-gate question matching is fuzzy (same question, different words)]
  → the gate matches on the inherited record ids the gnome must cite when
  touching a decided area; the briefing instructs citing; an uncited
  contradiction still lands but the judge's criteria (authored in
  `.gnomish/`) and the integration child remain the semantic net — the gate
  is a strong filter, not the sole defense.
- [Cross-change coupling: three active changes touch decision records] →
  strict sequencing (arbiter → decomposition → this); this change's specs
  only add fields and flows on top of the arbiter's owner; if the arbiter
  change re-scopes, revisit FR1 before apply.
- [Escalated supersede parks a child on the human's clock] → that is the
  designed cost of "children never override"; the arbiter tier (where
  configured) absorbs the routine share.

## Open Questions

- Default value of the binding-set bound (operational; correctness
  independent) — settle with the first real epic during implementation.

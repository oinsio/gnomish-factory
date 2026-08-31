# Design — add-epic-decomposition

## Context

See proposal.md — Why. Engine terminal outcomes are a sealed `TaskOutcome`
(`Completed`/`Paused`/escalation family) dispatched once in
`TakeOutcomeDispatch`; escalation exits through a single `TakeEscalationExit`.
Host and container modes share control-flow twins declared in the
manual-sync-pairs registry (fresh claim, engine execution, resume runners).
The observability ledger's task-outcome tokens are a declared wire pair
(`LedgerJsonMapper` ↔ `LedgerAggregator`) with a mandatory round-trip spec.
`add-tracker-task-hierarchy` supplies `createSubtask` (stable keys,
`AlreadyExists`), child listing, and blocked-aware claim. Context: driven by
FR1–FR6, NFR-R1, NFR-O1 from proposal.

## Goals / Non-Goals

**Goals:**
- Decomposition rides existing machinery: plan = ordinary stage output;
  verdict = engine terminal outcome; transition = take-owned exit like
  escalation; recovery = resume classification on the branch.
- One recovery owner (the epic's own resume path) for all four kill-window
  shapes; the pushed plan is the point of no return.
- The parent's children become claimable only when the tracker holds their
  complete edge set — a child is created blocked and unblocks naturally.

**Non-Goals:**
- No decision/context inheritance (next change reads the plan's briefs; this
  change only guarantees they exist and survive).
- No arbiter/approval gate on the verdict (layered later; the plan artifact
  and park report already give the human veto surface via the tracker).
- No re-decomposition of a child (depth ≤ 2 enforced in validation).

## Decisions

### D1. Plan is a stage output, verdict is derived — no new gnome channel

The gnome writes the plan as a declared stage output artifact on the branch;
verification validates it (builtin check); the engine derives
advance-vs-`Decomposed` from the validated plan. Alternative — a decision-
file-style side channel for the verdict — rejected: it would add a third
implementation of the file-handoff rule (`DecisionFileTransport` /
`BranchDecisionFile` pair) and put a routing verdict outside verification;
outputs are already machine-verifiable, resumable, and mode-agnostic.

### D2. `Decomposed` is a domain terminal outcome, transition is take-owned

The engine ends the run; everything durable-external (child creation, parent
park) happens in the application layer under the claim, exactly like the
escalation exit. Alternative — engine drives tracker writes — rejected: the
engine is tracker-free by layering; all tracker effects flow through take's
outcome dispatch (single dispatch point, `TakeOutcomeDispatch`).

### D3. Children are born blocked; integration child last

Creation order follows plan order with the integration child last, and every
child's blocked-by edges are written at creation (change (a)'s
`createSubtask` orders identity before edges). A worker child with no
sibling edges is immediately claimable even while later siblings are still
being created — acceptable: its brief is complete and self-contained by plan
validation, and earlier-sibling context arrives only with the next change.
The integration child is structurally last and blocked by all, so the epic
can never "finish" before the workers exist.

### D4. Point of no return is the pushed plan

Once the plan commit is pushed, resume never re-runs the engine on the epic —
it only converges the transition. Alternative — allow re-planning until the
first child exists — rejected: two instances observing different phases could
then disagree on whether the plan binds, and a re-plan after a partial
creation orphans created children; a single durable point ordered before all
effects is the project's intent→effect→receipt rule verbatim.

### D5. Parent finish is driven by the integration child's delivery

The integration child's pipeline delivers the epic's verification; its
delivery report finishes the parent (single writer: the instance delivering
the integration child, using existing finish machinery). Alternative — a
sweeper duty polling children states — rejected as a second owner for the
same transition; the sweeper owns only the orphan policy (cancelled epic →
park open children), which is a different shape with no competing owner.

### D6. Sync surfaces

Scout results (2026-08-29): this change touches declared pairs and must
mirror them.

- **Ledger wire pair** (`serveobservability/json/LedgerJsonMapper` ↔
  `dashboard/LedgerAggregator`, registry row "ledger wire tokens
  (`TaskOutcome`, ...)"): the `decomposed` token is added at both ends in
  this change, and the round-trip spec iterating `values()` covers it —
  tasked explicitly.
- **Mode twins** (registry rows `TakeEngineExecution`/
  `TakeContainerEngineExecution`, `TakeResumeRunner`/
  `TakeContainerResumeRunner`, `TakeFreshClaim`/`TakeContainerFreshClaim`):
  the `Decomposed` outcome surfaces in both modes' outcome flows. Mitigation
  by construction: the decomposition driver and the recovery convergence are
  each implemented once in mode-neutral application classes (like
  `TakeEscalationExit`/`TakeOutcomeDispatch`), so the twins gain only the
  arm that calls the shared driver — the mirrored edit is one dispatch arm
  per twin, tasked per registry row touched, with the twin-parity spec
  asserting both modes reach the same driver.
- **No new pair is created**: plan/receipt serialization gets exactly one
  mapper class owning both write and read (round-trip spec per the wire
  rule), used by the writer (take driver) and the reader (resume
  classifier) alike — a shared abstraction, not a writer/reader pair split
  across modules; rejected alternative (separate reader in the classifier
  module mirroring the writer's tokens) is precisely the divergence shape
  the registry exists to prevent.

## Risks / Trade-offs

- [A bad decomposition fans out wasted work across N children] → plan
  validation is structural, not semantic; the semantic gate is the stage's
  own verify (judge on the plan) authored in `.gnomish/`, plus the human
  veto window while children sit blocked; the arbiter flavor lands later.
- [Immediately claimable first worker child races the human veto] →
  operators wanting a veto pause configure the planning stage's advancement
  `manual` (existing mechanism) — documented in the operator guide.
- [Recovery needs the plan schema stable across factory versions] → the plan
  file carries a schema version; unknown future fields are ignored, unknown
  version is an infrastructure escalation, never a silent misparse.
- [Two instances could both attempt recovery of the same frozen epic] → the
  claim serializes recovery exactly as it serializes normal work; recovery
  runs only under the epic's claim.

## Open Questions

- Whether the park report for the decomposed parent should embed the full
  child table or link the branch plan (cosmetic; decide at implementation
  with operator-guide review).

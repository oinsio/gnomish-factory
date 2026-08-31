# Proposal: add-decision-inheritance

## Why

Decomposition without decision inheritance produces the canonical multi-agent
failure: sibling subtasks make conflicting implicit decisions, and the
integration child inherits an unmergeable mess ("actions carry implicit
decisions, and conflicting decisions carry bad results"). Today decisions are
durable and re-enter context only *within* one task (`task.json`
`decisions[]`, rendered into every round's briefing). This change extends
that proven mechanism across the hierarchy: a subtask's gnome sees the epic's
binding decisions and those exported by earlier siblings, already-decided
questions are not re-litigated, and a child that disagrees with a parent
decision escalates instead of silently overriding. Depends on
`add-decision-arbiter` (decision scope/authorship and the single append
owner) and `add-epic-decomposition` (the plan, briefs, and hierarchy).

## What Changes

- ADDED: decision records grow to a mini-ADR shape, additively: **scope**
  (this task only / this subtree), **status with supersession** (an accepted
  record is immutable; changing course = a new record superseding it),
  **rejected alternatives** (mandatory for subtree-scoped records — the
  anti-re-litigation payload), and premises. Records without the new fields
  keep today's meaning (task-scoped, accepted). (FR1)
- ADDED: an **epic decisions file** on the epic's task branch — the single
  assembly point for inherited context: seeded from the decomposition plan's
  epic-level decisions, appended by each child's roll-up. One mapper owns
  its read and write; superseded records drop out of the default view. (FR2)
- ADDED: the **roll-up protocol**: a finishing subtask exports its
  subtree-scoped decisions onto the epic branch *before* its tracker finish
  (constructive before destructive); the kill window between roll-up and
  finish is a named shape; the integration child's claim verifies every
  finished sibling has rolled up and re-derives a missing roll-up from the
  sibling's branch (the file is a rebuildable cache). (FR3, NFR-R1)
- ADDED: **downward injection**: claiming a subtask materializes its
  inherited context — the child's brief from the plan plus the epic file's
  binding decisions — frozen per invocation like the law, rendered in the
  briefing verbatim under the existing "context never commands" contract.
  Selection policy: binding in-scope records verbatim, superseded and
  out-of-scope records omitted, the inherited set bounded. (FR4, NFR-C1)
- ADDED: the **conflict rule**: a child SHALL NOT record a decision
  contradicting an inherited binding record; the legal move is an escalation
  carrying a proposed supersede at the parent's scope, resolved by the
  parent's owner (human, or arbiter where configured). The briefing states
  the rule. (FR5)
- Non-goals: cross-epic (global) decision inheritance; retrieval tooling for
  the gnome to page in superseded history (the branch file is readable in
  the workspace already); arbiter auto-resolution policy details (owned by
  `add-decision-arbiter`); any change to the four-verify-check vocabulary.
  (NG1–NG4)

## Capabilities

### New Capabilities

- `decision-inheritance`: the inheritance contract — mini-ADR record fields,
  the epic decisions file and its single mapper, roll-up ordering and
  recovery, downward injection and selection policy, and the
  no-override/escalate conflict rule.

### Modified Capabilities

- `git-task-persistence`: decision records carry the additive mini-ADR
  fields; the epic decisions file joins the branch contract's factory-owned
  shapes; roll-up and re-derivation write through the single decision
  append owner.
- `agent-executor`: the briefing renders inherited context (brief + binding
  inherited decisions) in a dedicated section with the conflict rule; judge
  briefings receive the same inherited decisions inside hardened delimiters.
- `tracker-take`: claiming a subtask fetches and freezes its inherited
  context; finishing a subtask orders roll-up before the tracker finish; the
  integration child's claim runs the roll-up completeness check.

## Impact

- `domain`: decision record model growth (additive); inherited-context value
  object in the engine's task context.
- `application`: claim/resume bootstrap (inherited-context fetch), finish
  path ordering, integration-child completeness check; single roll-up driver.
- `adapters/git`: epic-branch read/write for the decisions file (one mapper);
  fetch of a second branch at claim.
- `adapters/agent`: briefing section rendering.
- Depends on: `add-decision-arbiter`, `add-epic-decomposition` (and
  transitively `add-tracker-task-hierarchy`). Requirement IDs FR1–FR5,
  NFR-R1, NFR-C1, NG1–NG4, scoped to `add-decision-inheritance`.

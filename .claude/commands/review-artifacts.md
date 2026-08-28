---
description: Read-only review of an OpenSpec change's artifacts — freshness against the current codebase, completeness, and internal + external consistency; changes nothing
argument-hint: "[change-name]"
---

# Artifacts Review (read-only)

Review the **artifacts** of an OpenSpec change — `proposal.md`, `design.md`, delta specs,
`tasks.md` — for three properties:

1. **Freshness** — every assumption the change makes about the project (existing files,
   classes, modules, specs, behavior) is still true in the current codebase.
2. **Completeness** — all required artifacts and sections exist and every requirement is
   carried through the artifact layers (proposal → specs → tasks).
3. **Consistency** — the artifacts do not contradict each other, the stable specs, the
   already-implemented functionality, other active changes, or the project rules.

Differs from `/audit-implementation` and `/opsx:verify`: those judge the *implementation* against the
change; this command judges the *change itself* — typically before or during implementation.
**Strictly read-only**: no file edits, no git state changes. The only artifact is the report.

## Input

- `$1` — change name under `openspec/changes/` (not `archive/`). If omitted: when exactly one
  active change exists, take it; otherwise list active changes with AskUserQuestion.

## Steps

### 1. Load context

- All files of `openspec/changes/<name>/`: `proposal.md`, `design.md` (if present),
  `tasks.md`, everything under `specs/`.
- Stable specs under `openspec/specs/` that the delta specs touch (same capability names).
- Names of other active changes under `openspec/changes/` and of recently archived ones
  (`openspec/changes/archive/`, last ~3 months) for overlap checks.
- The relevant rules: `proposal-format.md`, `delta-specs.md`, `design-decisions.md`,
  `traceability.md`, `process-invariants.md`.

For a large change, fan out read-only Explore subagents per dimension (freshness /
completeness / consistency) and merge their findings; run them in parallel.

### 2. Freshness against the current project

For every concrete claim the artifacts make about the existing project, verify it against
reality (grep/read; cite file:line):

- Files, classes, methods, modules, Gradle projects, config keys mentioned as *existing* —
  do they exist under those names and locations now?
- Descriptions of current behavior ("today the engine does X") — does the code still do X?
- `MODIFIED`/`REMOVED` requirements in delta specs — does the referenced requirement exist
  verbatim in `openspec/specs/` (delta-specs rule: MODIFIED must restate the current text)?
- Is any part of the change **already implemented** — by an archived change, by another
  merged branch, or directly in code? Partially-implemented scope must be flagged.
- References to other changes (`Supersedes:`, "after <change> lands") — do those changes
  exist and are they in the expected state (active vs archived)?

Each stale claim → ❌ or ⚠️ with what reality looks like now and how to update the artifact.

### 3. Completeness

- **proposal.md** satisfies `proposal-format.md`: all required sections; requirement IDs
  follow `traceability.md` formats; each FR/NFR/UX is verifiable as written; goals/non-goals
  present; open questions (Q-IDs) either answered in `design.md` or explicitly left open.
- **Delta specs** satisfy `delta-specs.md`: ADDED/MODIFIED/REMOVED structure; every
  requirement has at least one `#### Scenario:`; scenarios are concrete enough to test.
- **design.md** (when the change alters architecture, ports, or cross-module contracts):
  exists per `design-decisions.md`, decisions have IDs and "Context: driven by FR-X" links.
- **Sync surfaces** (`design-decisions.md`, mandatory category): the design answers
  "shared abstraction or declared pair", or states the explicit
  `Sync surfaces: none` line. Verify the answer against reality: grep
  `Kept in sync with` markers and the `manual-sync-pairs.md` registry for pairs the change
  touches, and search for an existing same-rule implementation in another mode/layer that
  the artifacts fail to mention. A missing answer, or a "none" contradicted by an existing
  counterpart, is ❌ CRITICAL.
- **tasks.md**: every FR/NFR/UX maps to at least one task; every task traces back to a
  requirement or decision; verification steps are automated (specs, gates), not manual.
- **Coverage matrix**: for each requirement ID report proposal → delta spec → task presence;
  a gap in any column is a finding.

### 4. Internal consistency

- No two artifacts assert conflicting things (proposal says A, design decides not-A, a task
  implements a third variant); scenario text matches its requirement's SHALL statement.
- Requirement IDs are unique within the change; every ID referenced anywhere (design, tasks,
  scenarios) is defined in `proposal.md`; no orphan or dangling IDs.
- Terminology is stable: one name per concept across all artifacts (flag drift like
  "stage manifest" vs "stage descriptor" for the same thing; check the "reference, never
  golden" convention).
- `tasks.md` ordering is implementable: no task depends on an artifact produced by a later
  task; checkbox state (if any) is not contradicted by the change's own narrative.

### 5. External consistency

- Delta specs vs stable specs: an ADDED requirement must not duplicate or contradict an
  existing requirement in `openspec/specs/`; MODIFIED/REMOVED must target real ones.
- Vs implemented functionality: proposed behavior must not silently break invariants the
  code already enforces (search for the touched entities in code and tests); when the change
  intends to break one, that must be stated explicitly as MODIFIED/REMOVED, never implied.
- Vs other active changes: overlapping files/specs/capabilities → flag the collision and the
  needed ordering.
- Vs project rules and ADRs: tech choices match ADR 0001 and `.claude/rules/`; scope fits
  the 1–4 week rule (`process-invariants.md`), else recommend a split; naming is
  kebab-case-descriptive; artifacts reference only durable `docs/` files, never scratch
  locations.

### 6. Report

```
## Artifacts Review: <name>

### Summary
| Dimension            | Result                           |
|----------------------|----------------------------------|
| Freshness            | N stale claims / OK              |
| Completeness         | X/Y requirements fully covered   |
| Internal consistency | N contradictions                 |
| External consistency | N conflicts (specs/code/changes) |

### Freshness            — each stale claim: artifact:line, current reality, suggested edit
### Coverage matrix      — requirement ID → proposal / delta spec / task (✅/❌ per column)
### Completeness         — missing sections/scenarios/tasks with the rule they violate
### Internal consistency — each contradiction with both citations
### External consistency — conflicts with stable specs, code, active changes, rules
### Recommendations, ordered — CRITICAL first, then WARNING, then SUGGESTION
### Verdict              — ready to implement / needs revision (blockers listed)
```

Each Recommendations item must be self-contained, with the same format as `/audit-implementation`:

```
N. **SEVERITY — <short title>** (`artifact-or-file:line`)
   Problem: what is wrong and why it matters, restated here.
   Fix: the concrete edit to the artifact (or code reality to acknowledge).
```

Severity: CRITICAL — implementing as written would build the wrong thing or break existing
behavior undeclared; WARNING — gap or drift that will surface during implementation;
SUGGESTION — clarity/traceability polish. When uncertain, verify against code before
reporting; downgrade rather than guess. End with a reminder that nothing was modified and
the human decides what to apply.

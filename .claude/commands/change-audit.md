---
description: Read-only audit of an OpenSpec change — per-item completeness, code/test quality and security report with recommendations; changes nothing
argument-hint: "[change-name] [quick]"
---

# Change Audit (read-only)

Audit how completely and how well an OpenSpec change is implemented. Produce a report with a
verdict and a recommendation **for every item** — every task in `tasks.md`, every requirement,
every quality rule. **Strictly read-only**: no file edits, no `git` state changes, no commits,
no checkbox updates. The only artifact of this command is the report in the reply.

Differs from `/opsx:verify`: that command trusts checkboxes and samples requirements; this one
re-verifies each item against the actual code, applies the project's own quality rules
(`.claude/rules/`) including build/test/mutation gates, and reviews code quality, test quality
and security readiness.

## Input

- `$1` — change name under `openspec/changes/` (not `archive/`). If omitted: when exactly one
  active change exists, take it; otherwise list active changes with AskUserQuestion.
- `quick` anywhere in the arguments — skip the Gradle gate (step 4).

## Steps

### 1. Load artifacts

Read from `openspec/changes/<name>/`: `proposal.md`, `design.md` (if present), `tasks.md`,
every file under `specs/`. Collect:

- the task list with checkbox states,
- all requirement IDs (FR/NFR-*/UX per `traceability.md`) from the proposal,
- delta-spec requirements (`### Requirement:`) and scenarios (`#### Scenario:`),
- design decisions (D-numbers / DEC-numbers) from `design.md`.

Also read `git diff main...HEAD --stat` plus `git status --short` to scope what the
implementation actually touched (include uncommitted work).

### 2. Verify every task item

For **each** task in `tasks.md`, checked or not, find concrete evidence in code/tests
(file:line). Do not trust the checkbox in either direction:

- `[x]` with evidence → ✅, cite the evidence.
- `[x]` without evidence, or evidence contradicts the task text → ❌ CRITICAL,
  recommendation: what to implement or how to reconcile.
- `[ ]` but evidence exists → ⚠️, recommendation: mark done (the human does it).
- `[ ]` and not implemented → ❌ CRITICAL, recommendation: concrete next step.

For a change with many sections, fan out one Explore subagent per `##` section of `tasks.md`
(read-only), each returning per-item verdicts with file:line evidence; run them in parallel.

### 3. Verify every requirement and scenario

Per `traceability.md`: for each FR/NFR/UX from the proposal, grep for an implementing entity
(code doc comment or spec referencing the ID). For each delta-spec requirement/scenario, find
the covering test. Report per ID: ✅ implemented+tested (evidence) / ⚠️ implemented, no
traceability link or no test / ❌ not found. Every ⚠️/❌ gets a specific recommendation.

### 4. Quality gate (skip if `quick`)

Determine affected modules from the diff; run `./gradlew <module>:check` for them (includes
Spotless, Error Prone/NullAway, Spock, JaCoCo, PIT). Report failures verbatim as ❌ with the
failing task/requirement they belong to. A long PIT run is normal — do not abort it; if the
build cannot run at all, mark the gate SKIPPED with the reason, never as passed.

### 5. Project-rule conformance

Check the new/modified files (from the diff) against `.claude/rules/`:

- `process-invariants.md`: file size (target 100–120 lines, hard cap 200), module boundaries
  (no imports from sibling-module internals), English-only docs/comments.
- `testing.md`: every new `@DoNotMutate` / `excludedClasses` / `excludedTestClasses` entry has
  a written rationale meeting the rule's bar; specs are Spock, one capability per spec file.
- `design-decisions.md` adherence: implementation matches each D/DEC decision in `design.md`;
  contradictions are ⚠️ with "fix code or revise design.md" recommendations.
- `diagrams.md`/docs: if the change altered behavior described in `docs/` or `README.md`,
  check the prose and Mermaid diagrams still match.

### 6. Code quality review

Review every production file the change added or modified (from the diff), as a code reviewer
would — beyond what static analysis already gates:

- **Correctness risks**: unhandled edge cases (null/empty/concurrent/interrupt), swallowed
  exceptions, resource leaks (unclosed processes, streams, executors), race conditions around
  shared state — this project runs virtual threads and subprocesses.
- **Design**: duplication introduced by the change, dead code, leaky abstractions across
  port boundaries, needless mutability, misplaced responsibility (logic in a config/factory
  class), overly clever code where a plain version exists.
- **Error reporting**: failures must name the problem and the fix (the project's fail-fast
  convention); flag messages that would leave an operator guessing.

Report each finding with file:line, severity, and a concrete fix suggestion — not applied.

### 7. Test quality review

For every new/modified spec, judge whether it would actually catch a regression:

- Assertions test observable behavior, not implementation wiring ("method calls method").
- Failure paths are tested, not only happy paths; error messages are asserted where the
  contract is "name the problem and the fix".
- No shared mutable state between features; no order dependence; temp dirs/processes are
  cleaned up; no real network or wall-clock sleeps in unit specs.
- Data tables used where a scenario matrix exists; spec names describe behavior.
- Coverage exists at the right level per `testing.md` (port contract specs for adapters,
  in-process unit specs feeding the PIT gate, E2E only where declared).

### 8. Security & production readiness

Review the diff with `security-review`-style scrutiny scoped to this change:

- **Secrets & credentials**: no tokens/keys/passwords in code, tests, fixtures or logs; env
  and config values holding credentials never logged or echoed into error messages.
- **Injection & untrusted input**: subprocess arguments built from tracker/task data are
  passed as argv lists (never shell-interpolated); paths from task state are validated
  against traversal; YAML/JSON from the task repo parsed with safe settings.
- **Sandbox boundaries** (NFR-S IDs): container/egress guarantees the change claims are
  actually enforced, not just configured; trust-table/passport checks fail closed.
- **Dependencies**: new/updated dependencies noted, with known-CVE check left to OSV in CI
  but flagged here if a dependency looks unmaintained or overly broad.
- **Operational readiness**: failure modes are observable (logged at the right level, no
  silent catch), retries/timeouts bounded, no unbounded queues or memory growth.

Findings here use the same severity scale; anything exploitable or fail-open is CRITICAL.

### 9. Report

```
## Change Audit: <name>

### Summary
| Dimension        | Result                          |
|------------------|---------------------------------|
| Tasks            | X/Y verified done (Z mismatches)|
| Requirements     | A/B traced and tested           |
| Quality gate     | pass / fail / skipped (quick)   |
| Project rules    | N findings                      |
| Code quality     | N findings                      |
| Test quality     | N findings                      |
| Security/prod    | N findings                      |

### Tasks (per item)          — verdict + evidence/recommendation for every task
### Requirements (per ID)     — same, for every FR/NFR/UX and scenario
### Quality gate              — failing checks with output excerpts
### Project rules             — findings with file:line
### Code quality              — findings with file:line and suggested fix
### Test quality              — findings per spec file
### Security & prod readiness — findings, fail-open/exploitable first
### Recommendations, ordered  — CRITICAL first, then WARNING, then SUGGESTION
### Verdict                   — ready to archive / not ready (blockers listed)
```

Every finding must carry a `file:line` reference and an actionable recommendation — no vague
"consider reviewing". When uncertain, downgrade severity rather than guessing. End with a
reminder that nothing was modified and the human decides what to apply.

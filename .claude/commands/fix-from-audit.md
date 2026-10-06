---
description: Fix an OpenSpec change's implementation from an /audit-implementation report — triage every recommendation (apply with TDD, or reject with evidence), leave ./gradlew check green, record the resolution. No commits.
argument-hint: "<change-name> [audit-report-path]"
---

# Fix From Audit

Work through the recommendations of an `/audit-implementation` report on a change's
implementation. An audit is a second opinion, not an order: some of its recommendations will
be wrong, useless, or harmful. This command is the **second filter** — every recommendation is
re-verified against the code and then either fixed or rejected with evidence. Nothing is
changed because the report says so. **No commits** (project invariant: the agent never
commits).

The counterpart for a change's *artifacts* is `/update-from-review`; both follow
`.claude/rules/review-recommendations.md`, which defines when a recommendation is worth fixing
and how a resolution is recorded.

## Input

- `$1` — the change name under `openspec/changes/` (not `archive/`).
- `$2` — the audit report. If omitted, the newest `temporary-docs/audit-<change>-*.md` (the
  file `/audit-implementation` step 10 writes); if none exists, stop and say so.

## Steps

### 1. Load

Read the whole report; the change's `proposal.md`, `design.md`, `tasks.md` and delta specs;
`CLAUDE.md`; and the `.claude/rules/` files the recommendations cite or the touched code falls
under (`testing.md`, `implementation.md`, `process-invariants.md`, `logging.md`,
`lock-scope.md`, `crash-consistency.md`, `manual-sync-pairs.md`, as they apply).

### 2. Triage every recommendation before changing anything

Resolve each numbered recommendation in order by "Resolving recommendations" in
`.claude/rules/review-recommendations.md`: re-verify it against the code, apply only what is
worth fixing, otherwise reject it with evidence. For code:

- A **SUGGESTION** is trivially safe when it is local and changes no behaviour and no public
  surface: a doc or traceability comment, a name inside one method, a missing test assertion
  for behaviour the code already has.
- A recommendation that the **plan** is wrong — the proposal, design or delta specs must
  change for the fix to be right — is not fixed here: code that departs from the plan is the
  defect `/opsx:apply` exists to prevent. Reject it with that reason; the summary says the
  plan question needs a human (`/opsx:update`).
- "Task `[x]` without evidence" and "task `[ ]` not implemented" are fixed by implementing what
  the task says, under the same rules as `/opsx:apply`. "Task `[ ]` but evidence exists" is
  fixed by ticking it, once you have verified the evidence yourself.

When a human runs this command, show the triage and apply only after they confirm it.

### 3. Fix with TDD, one recommendation at a time

For each accepted recommendation, in order:

1. **Red.** Where it is a behaviour defect — wrong, missing or unsafe behaviour — write or
   extend the Spock spec that shows it first, and run it to see it fail for the reason the
   recommendation states (`testing.md`). A spec that passes before the fix proves the
   recommendation wrong: reject it after all, with that spec as evidence, and delete the spec.
2. **Green.** Make the smallest change that passes it, within the project's rules — never by
   weakening a gate (`testing.md` lists the only accepted exemptions, each with its bar).
3. **Run** the owning module's specs (`./gradlew :<module>:test`), and the single-owner sweep
   of `implementation.md` where the fix touches such a mechanism.

Recommendations that are not behaviour — a missing traceability link, a test-quality defect,
a rule violation in structure — skip the red step but not the run.

For a long list, give each accepted recommendation to a foreground `general-purpose`
subagent, strictly one at a time — never in parallel: two fixes editing overlapping files
clobber each other. Its prompt carries the recommendation verbatim, the rule files it
touches, "Do NOT make any git commits", and steps 3.1–3.3; its report ends with the specs
run and the sweep, and you verify both before moving on.

### 4. Finish green

Run `./gradlew spotlessApply`, then `./gradlew check`. Fix what fails — by the same rules,
listed under Other edits in the record — and re-run until it passes. The last run before
reporting is the full root `check`: edits in one module routinely break another.

### 5. Record the resolution

In the resolution-record format of `.claude/rules/review-recommendations.md`, under this
frame:

```
## Audit Resolution: <change-name>

Source: <audit report path>

<one `### <N> — applied|rejected` entry per recommendation, per the rule; an applied
behaviour fix names its red spec in the Edit line>

### Other edits
<per the rule; here typically a fix for a check failure of step 4>

### Verification
- `./gradlew check`: <result>
```

A report with no recommendations gets `None.` under the heading, then the Other edits and
Verification sections.

### 6. Persist

Write the record to `temporary-docs/audit-<change-name>-<YYYY-MM-DD>-resolution.md` (the same
date and language suffix as the report it resolves, per `/audit-implementation` step 10), and
show it in the reply. `openspec/**` artifacts never reference this file.

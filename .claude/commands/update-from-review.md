---
name: "Update From Review"
description: Revise an OpenSpec change from a /review-artifacts report via /opsx:update — triage every recommendation (apply, or reject with evidence), keep the change coherent and valid, record the resolution
argument-hint: "<change-name> [review-report-path]"
category: Workflow
tags: [workflow, artifacts]
---

Wrapper around `/opsx:update` that works through the recommendations of a
`/review-artifacts` report. Do not modify `/opsx:update` itself; it is managed by OpenSpec
and an update would overwrite the change.

A review is a second opinion, not an order. Its recommendations are the reviewer's
judgement, and some of them will be wrong, useless, or harmful to the change. This command is
the **second filter**: every recommendation is re-verified against the source and then
either applied or rejected with evidence. Nothing is applied because the report says so.

## Input

- `$1` — the change name under `openspec/changes/` (not `archive/`).
- `$2` — the review report. If omitted, the newest `temporary-docs/review-<change>-*.md`
  (the file `/review-artifacts` step 7 writes); if none exists, stop and say so.

**How it works**: invoke `/opsx:update $1` via the Skill tool, with the request "apply the
accepted recommendations of the review report", and with the constraints below injected
into its execution.

## Execution constraints

1. **Triage before any edit.** Read the whole report, then take every numbered
   recommendation in order and decide **apply** or **reject**:
   - Re-verify it yourself: open the location it cites and the code, spec, ADR or rule it
     relies on. The report's wording is a claim to check, not a fact.
   - Apply it only if it is **worth fixing** by the definition in step 6 of
     `.claude/commands/review-artifacts.md` — the location shows the problem, the Impact is
     a concrete failure, the fix costs less than the gap, the fix harms nothing — and it is
     not on that step's drop list (widens the scope, reopens a settled decision without new
     grounds, reverts what landed, advice instead of an edit, a duplicate).
   - A **SUGGESTION** is applied only when the edit is trivially safe: a local wording,
     reference or traceability fix that changes no behaviour, scope, requirement or task.
     Otherwise reject it, saying so.
   - You may apply a recommendation with a smaller or different edit than its Fix proposes
     when that closes the same gap with less risk; say what you did instead and why.
   - A recommendation whose fix would change the change's **intent** rather than refine it
     is not applied here: `/opsx:update` itself sends such a change to a new change. Reject
     it with that reason, and say in the summary that the intent question needs a human.

2. **Apply through `/opsx:update`'s reconcile step.** After each accepted edit, check every
   other artifact of the change against it, in any direction, as `/opsx:update` step 4
   requires. Edit only the change's own existing artifacts: never `openspec/specs/`,
   `openspec/changes/archive/`, another change, the code, or the review report itself.

3. **Leave the change valid.** `openspec validate <name> --strict` must report the change
   valid when you finish. An error that no recommendation named is still yours to fix if
   the fix is a faithful restatement (a `MODIFIED` block that must copy the current
   scenarios, say); if it needs a decision, report it instead of guessing.

4. **Record the resolution.** One entry per recommendation of the report, in the report's
   numbering, nothing skipped:

   ```
   ## Review Resolution: <change-name>

   Source: <review report path>

   ### <N> — applied
   - Recommendation: <SEVERITY — title, as in the report>
   - Re-verified: `<file:line>` — what you found there
   - Edit: `<artifact:line>` — what changed (and, if it differs from the proposed Fix, why);
     list every location, the reconciling edits in other artifacts included

   ### <N> — rejected
   - Recommendation: <SEVERITY — title>
   - Re-verified: `<file:line>` — what you found there
   - Evidence: `<file:line>` — why it fails: which test of the worth-fixing definition or
     which drop-list entry, quoting what the source actually says

   ### Other edits
   - `<artifact:line>` — an edit no recommendation asked for (a validation fix under
     constraint 3), and why; `None.` when there are none

   ### Validation
   - `openspec validate <name> --strict`: <result>
   ```

   Every edit to the change appears in the record — under the recommendation it serves or
   under Other edits. An edit the record does not list is an edit nobody reviews.

   A report with no recommendations gets `None.` under the heading, then the Other edits
   and Validation sections.

5. **Persist.** Write the resolution to `temporary-docs/review-<change-name>-<YYYY-MM-DD>-resolution.md`
   (the same date and language suffix as the report it resolves, per `/review-artifacts`
   step 7), and show it in the reply. `openspec/**` artifacts never reference this file.

6. **Confirmation stays with the human.** `/opsx:update` step 5 — show each revision and
   write it only after the user confirms — applies unchanged when a human runs this
   command; the triage of constraint 1 is what you propose to them.

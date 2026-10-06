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

1. **Triage before any edit.** Read the whole report, then resolve every numbered
   recommendation in order by "Resolving recommendations" in
   `.claude/rules/review-recommendations.md`: re-verify, apply only what is worth fixing,
   SUGGESTION only when trivially safe, otherwise reject with evidence. A SUGGESTION here is
   trivially safe when it is a local wording, reference or traceability fix. A fix that
   would change the change's **intent** is rejected with that reason — `/opsx:update` itself
   sends such a change to a new change — and the summary says the intent question needs a
   human.

2. **Apply through `/opsx:update`'s reconcile step.** After each accepted edit, check every
   other artifact of the change against it, in any direction, as `/opsx:update` step 4
   requires. Edit only the change's own existing artifacts: never `openspec/specs/`,
   `openspec/changes/archive/`, another change, the code, or the review report itself.

3. **Leave the change valid.** `openspec validate <name> --strict` must report the change
   valid when you finish. An error that no recommendation named is still yours to fix if
   the fix is a faithful restatement (a `MODIFIED` block that must copy the current
   scenarios, say); if it needs a decision, report it instead of guessing.

4. **Record the resolution** in the rule's resolution-record format, under this frame:

   ```
   ## Review Resolution: <change-name>

   Source: <review report path>

   <one `### <N> — applied|rejected` entry per recommendation, per the rule>

   ### Other edits
   <per the rule; here typically a validation fix under constraint 3>

   ### Validation
   - `openspec validate <name> --strict`: <result>
   ```

   A report with no recommendations gets `None.` under the heading, then the Other edits
   and Validation sections.

5. **Persist.** Write the resolution to `temporary-docs/review-<change-name>-<YYYY-MM-DD>-resolution.md`
   (the same date and language suffix as the report it resolves, per `/review-artifacts`
   step 7), and show it in the reply. `openspec/**` artifacts never reference this file.

6. **Confirmation stays with the human.** `/opsx:update` step 5 — show each revision and
   write it only after the user confirms — applies unchanged when a human runs this
   command; the triage of constraint 1 is what you propose to them.

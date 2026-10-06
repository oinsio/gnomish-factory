# Acceptance criteria for the archive stage

`<task>` in the paths below is the name of this task's branch without its
`gnomish/` prefix — or `local` when the branch does not start with `gnomish/`.

This round archived one OpenSpec change: it merged the change's delta specs into
`openspec/specs/` and moved the change under `openspec/changes/archive/`. The record
`temporary-docs/gnomish/<task>/archive.md` names the target directory and every
capability merged. How a change is archived and its deltas merged is defined by
`.claude/commands/opsx/archive.md`, `.claude/commands/opsx/sync.md` and
`.claude/rules/delta-specs.md`; read them first and judge against them.

A check before you has already confirmed that the change moved to exactly one correctly
dated archive directory with every task ticked, that `openspec validate --specs --strict`
passes, and that only the main specs of capabilities with a delta were touched. Do not
recount them, and do not run anything.

For every delta spec under the archived change's `specs/`, read the main spec of the same
capability path under `openspec/specs/` and check:

1. **ADDED** — every added requirement is present in the main spec with the delta's text
   and every scenario of it, word for word.
2. **MODIFIED** — every modified requirement in the main spec now reads as the delta's
   full text: description and scenarios. A delta's MODIFIED block carries the whole
   requirement, so a scenario of the main spec that the delta's block does not hold is
   one the merge should have dropped, and one the delta adds must be there.
3. **REMOVED** — every removed requirement is gone from the main spec; a capability whose
   last requirement was removed has no main spec left, unless the record says why it was
   kept.
4. **RENAMED** — present under the new name, absent under the old one.
5. **Nothing else moved.** Requirements the delta does not name are untouched: same text,
   same order, same scenarios. A merge that "tidies" a neighbouring requirement fails this
   criterion even when the edit looks harmless.
6. **The record is true.** Each `Specs:` line names a capability that has a delta, with the
   counts the delta actually carries; no capability with a delta is missing from it.

## Your verdict

Name every capability and requirement that fails, the criterion, and what the main spec
says against what the delta says — "quality-gates / Duplicated code gate: fails 1, the
scenario 'Repeated traceability comments do not count' is missing from the main spec".
The next attempt acts on your list item by item. Report every violation, not the first
few.

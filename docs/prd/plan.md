# Plan: building the requirements layer

The order of work for this directory and for wiring it into the OpenSpec specs. One step per
session chunk, each reviewed by the human before the next; the AI agent never commits and
recommends a commit message after every step. Started 2026-10-21; a step is struck through
here when it lands, so a new session can read this file and continue.

## Why this order

The factory has the lower half of the requirements pyramid (proposal → delta spec → code →
gated tests) and had no upper half. The git usage review of 2026-10-21 found mechanisms with no
need behind them, so needs are written first and every further change is judged against them.
Method: ISO/IEC/IEEE 29148 order, Cockburn actor-goal lists, Volere traceability, ATAM quality
scenarios; the agent1st rules of the sibling repository `agent1st` for how artifacts are
written.

## A. Finish the actor-goal list (one group per step)

Form and rules: [`use-cases.md`](use-cases.md). Each group lands with its coverage table and
its [`gaps.md`](gaps.md) entries in one step.

1. ~~Group 2, filing and authorizing~~ · ~~group 4, working a task~~ · ~~group 1, setting
   up~~ · ~~group 3, coordinating instances~~
2. Group 5, verifying: round ends; check red; CI silent past its timeout; judge verdict.
3. Group 6, deciding and escalating: fork unresolved; arbiter decides; human answers;
   attempts exhausted; manual checkpoint.
4. Group 7, delivering and closing: task completed; PR opened; branch merged; task reopened.
5. Group 8, recovering and degrading: process killed mid-step; origin, tracker, runtime
   unreachable; clone lost. The first-order promise of resume lives here.
6. Group 9, observing and accounting: morning check; wall display; incident review; cost.
7. Group 10, developing the factory: release tagged; plugin written; the factory on itself.

Per group, the checks run before the report: every `UC-` heading unique across the
directory; every `GP-` key in a table defined in `gaps.md` and every entry referenced by some
story; every key in a `**Breaks:**` line resolves to a story; every `planned:` change exists
unarchived; every requirement heading in a coverage cell exists in its main spec (strip only
the `(...)` note at the end of a cell, never inside a heading). The checks are an ad hoc
shell script until the gate of step D.2 replaces them.

## B. The operational concept

`concept.md`: one task from filing to merge, told from every stakeholder's seat, with the
media (tracker, task branch, factory register, box) and the decision chain gnome → arbiter →
human. It carries the execution-mode stance of `GP-HOST-MODE-BEST-EFFORT`: host mode stays for
work a box cannot serve, best effort, with the list of differences from container mode. No
keys; Mermaid for the flow.

## C. Quality requirements

`quality.md`, `QR-*` keys, six-part form (source, stimulus, environment, artifact, response,
response measure); the measure is the fit criterion. Sources: the needs of
[`stakeholders.md`](stakeholders.md) no story carries; the "Tension with" lines of the
groups; the stories the index refused for lack of a human voice. Includes the host-mode QR
whose measure is "the list of differences is kept and complete". A gap that breaks a QR
names it in `**Breaks:**`.

## D. Wire the layer into OpenSpec

1. **Rules and vocabulary.** `traceability.md`: the `**Serves**:` line beside
   `**Implements**:` on every main-spec requirement, keys `UC-`/`QR-`, many-to-many; the dead
   "Use case format" and "Gherkin scenario" rows deleted. `process-invariants.md`: this
   directory as layer 0 of artifact layering. `delta-specs.md`: the second metadata line.
   `proposal-format.md`: "Users & Scenarios" becomes "Actors & Use Cases", naming keys.
   Glossary entries: use case, quality requirement, known gap, fake-agent role (and the rename
   `GNOMISH_FAKE_SCENARIO` → `GNOMISH_FAKE_ROLE`, "scenario" being OpenSpec's word).
2. **The gate.** `checkSpecContract` resolves every PRD key to exactly one heading under this
   directory, fenced blocks masked (the format examples contain `UC-AUTHORIZE-TASK` and
   `GP-KEY`); a `**Serves**:` line per requirement with a per-capability ratchet; coverage
   tables' headings resolve; a stale gap is a finding (a `planned:` change archived, a
   `decided:` stance landed). The script `scripts/check-spec-contract.sh` runs it.
3. **The migration.** One change writes the `**Serves**:` lines onto the 559 main-spec
   requirements from the coverage tables, and the "Actors & Use Cases" section onto the four
   active proposals that lack one. A requirement that serves no story is reported, not
   deleted: it is a later group's, a QR's, or a deletion candidate for the human.

## E. Use the layer

- Judge the pending git work against the stories: the operator-clone premise
  (`GP-FACTORY-IN-OPERATOR-CLONE`), the scope of `own-git-invocation-policy`, directions A/B/C
  of the git usage review.
- Decide every `open` entry of `gaps.md` or state why it stays open.
- Only then, Cockburn's next rung for the stories whose risk earns it: main course, failure
  conditions and their handling.

## Standing constraints

Documentation in English, conversation in Russian; 200 lines hard cap per file, 100–120 the
target; one subagent at a time; OpenSpec-managed commands are never edited; `openspec/**`
never references `temporary-docs/`; a key is the first token of a heading and is never reused.

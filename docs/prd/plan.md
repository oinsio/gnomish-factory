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
2. ~~Group 5, verifying: round ends; check red; CI silent past its timeout; judge verdict.~~
3. ~~Group 6, deciding and escalating: fork unresolved; arbiter decides; human answers;
   attempts exhausted; manual checkpoint.~~
4. ~~Group 7, delivering and closing: task completed; PR opened; branch merged; task reopened.~~
5. ~~Group 8, recovering and degrading: process killed mid-step; origin, tracker, runtime
   unreachable; clone lost. The first-order promise of resume lives here.~~
6. ~~Group 9, observing and accounting: morning check; wall display; incident review; cost.~~
7. ~~Group 10, developing the factory: release tagged; plugin written; the factory on itself.~~

Per group, the checks run before the report: every `UC-` heading unique across the
directory; every `GP-` key in a table defined under `gaps/` and every entry referenced by some
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
   `proposal-format.md`: "Users & Scenarios" becomes "Actors & Use Cases", naming keys; every
   FR cites the story step or extension it realizes, every NFR cites the `QR-` whose measure is
   its criterion. `design-decisions.md` and the ADR template: a decision names the `UC-`/`QR-`
   it serves and its premises, one checkable sentence each (section G).
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

- Review the decisions behind the `wrong-turn` entries by the procedure of section G, the
  operator-clone premise first (`GP-FACTORY-IN-OPERATOR-CLONE`, the scope of
  `own-git-invocation-policy`, directions A/B/C of the git usage review).
- Decide every `open` entry of `gaps.md` or state why it stays open.

## F. From stories to requirements

No separate list of functional and non-functional requirements is written: the system
requirements are the main-spec requirements with their scenarios, and FR/NFR are numbered per
change in its proposal (`traceability.md`). The stories and the `QR-` entries are their
source, and the path from one to the other is:

1. **Three outcomes per story**, read off the coverage table: a requirement carries the story
   whole (it gets the `**Serves**:` line, nothing new is written); it carries it in part or
   not at all (a `gaps.md` entry); a requirement carries no story at all (a deletion
   candidate, or a missing story or `QR-`; reported by the ratchet of D.2, decided by the human).
2. **Cockburn's next rung, only for the stories a gap being closed breaks**: the main course
   (the actor's steps and the system's) and the extensions (what goes wrong, how the system
   answers), written under the story in its group file before the change is proposed. Every
   system step is a candidate FR; every extension an FR for handling it; a condition on a step
   ("within one poll", "before any paid round") is the measure of a `QR-`.
3. **One change per group of gaps with one closing mechanism.** Its proposal names the keys
   in "Actors & Use Cases"; each FR cites its step or extension; each NFR cites its `QR-` and
   takes the measure as its criterion. Then design, delta requirements with `Implements` and
   `Serves`, scenarios, tests, as today. The archive closes the gap entry; the gate reports it
   if it stays.
4. **Completeness both ways** (Volere: every event has a response): a story with no
   requirement and no gap is a hole; a `QR-` with no requirement and no gap is a hole; a
   requirement with no `Serves` is a mechanism without a need. The "Tension with" lines are
   settled in `quality.md` by saying which `QR-` wins, and that order is the order of closing
   gaps.

## G. Reviewing a decision that leads the wrong way

Every wrong turn found so far rested on a premise nobody had written down, so none could be
checked ("the factory's clone is the operator's clone"; "a check may run commands from the
gnome's tree"). The layer makes a decision reviewable by its source and its premises:

1. **Source and premises are written.** An ADR or a `D-n` of a design names the `UC-`/`QR-`
   it serves and its premises, one checkable sentence each (D.1). A decision serving no story
   is as suspect as a requirement without `Serves`.
2. **Stories test premises.** A premise is refuted when the **When** of a story is
   incompatible with it: "I run instances on several machines", "two slots claim at once",
   "my IDE fetches" each refute the operator-clone premise. A `wrong-turn` entry therefore
   names the decision, the premise and the refuting story.
3. **The ratchet finds mechanisms without a need** (D.2, F.1): what the git usage review
   found by hand, every later group or `QR-` surfaces by a run of the gate.
4. **The review is a decision review, never a narrowed requirement** (`spec-mismatch.md`;
   `/architect` mode C). Inputs the layer adds: the alternatives compared by the `QR-` each
   meets and breaks, the "Tension with" lines as the trade-off points, alternative zero (delete
   the mechanism; who carries the duty) checked against the same stories. One of three results:
   the decision stands and gains its missing source line; it is superseded by a new ADR or a
   change with `Supersedes`, the old requirement leaves by REMOVED in the delta, the gap entry
   moves to `planned:`; it is kept knowingly, as `decided:` with the stance in `concept.md`
   (host mode is the precedent).
5. **A refuted premise becomes an explicit stance**, a `QR-` with a measure or a sentence in
   `concept.md`, so the same turn is not taken twice ("the factory writes only to a clone no
   other writer touches").

## Standing constraints

Documentation in English, conversation in Russian; 200 lines hard cap per file, 100–120 the
target; one subagent at a time; OpenSpec-managed commands are never edited; `openspec/**`
never references `temporary-docs/`; a key is the first token of a heading and is never reused.

# Use cases

What each stakeholder or actor wants from the factory, written as **job stories**: a situation,
a want, and the reason behind it. The partition follows Volere's event partitioning: the
factory exists to respond to events at its boundary, so the groups are stretches of one
task's life from filing to merge, plus the events that happen to the factory itself. The
precision follows Cockburn's ladder: the stories are the actor-and-goal list, reviewed for
accuracy and completeness before anything below them is written; the main course, the
failure conditions and their handling are added later, only where the risk earns them.

## Groups

One file per group under [`use-cases/`](use-cases/). The order is the order of a task's life;
groups 8 to 10 hold the events that are not one task's.

| # | Group | File | Events of the kind |
|---|---|---|---|
| 1 | Setting up the installation and the project | [`setting-up.md`](use-cases/setting-up.md) | factory installed; clone registered; law written; sandbox boundary set |
| 2 | Filing and authorizing work | [`filing-and-authorizing.md`](use-cases/filing-and-authorizing.md) | task written; ready applied; ready applied by an untrusted source; task mandated by ref |
| 3 | Coordinating instances | [`coordinating-instances.md`](use-cases/coordinating-instances.md) | queue non-empty; lease stale; instance died; sibling picks up |
| 4 | Working a task | [`working-a-task.md`](use-cases/working-a-task.md) | round begins; gnome commits; gnome asks a decision; box fails to start |
| 5 | Verifying | [`verifying.md`](use-cases/verifying.md) | round ends; check red; CI silent past its timeout; judge verdict |
| 6 | Deciding and escalating | [`deciding-and-escalating.md`](use-cases/deciding-and-escalating.md) | fork unresolved; arbiter decides; human answers; attempts exhausted; manual checkpoint |
| 7 | Delivering and closing | [`delivering-and-closing.md`](use-cases/delivering-and-closing.md) | task completed; PR opened; branch merged; task reopened |
| 8 | Recovering and degrading | *(next)* | process killed mid-step; origin unreachable; tracker unreachable; clone lost |
| 9 | Observing and accounting | *(next)* | morning check; wall display; incident review; cost report |
| 10 | Developing the factory | *(next)* | release tagged; plugin written; the factory works on itself |

Recovery is its own group, against Volere's habit of folding a failure into the use case it
interrupts: for the factory, resuming after a crash is a first-order promise (G1 and G2 of
`add-git-workflow`), not a footnote to each step.

## The story form

Inside a group file, a second-level heading names the actor by its register entry, and each
story under it is a third-level heading carrying the PRD key, followed by three clauses:

```markdown
## Task author (`SH-TASK-AUTHOR`)

### UC-AUTHORIZE-TASK — Hand a task to the factory
**When** I have put `gnomish:ready` on an issue,
**I want** a running factory to claim it within one poll, or the board to name the reason it did not,
**so that** I never dig through logs to learn whether work has started.
```

Rules:

- **When** names an event, never a state: "I have put the label", not "the label is on".
- **I want** says what the factory must do, in the glossary's words (`gnomish:ready`, the
  board, a claim). It does not say how the factory does it inside.
- **so that** names the gain or the cost avoided; it never restates the want.
- **One story, one actor.** The actor is the heading above; one event may yield several
  stories under different actors, each with its own want. A person who acts in another
  entry's role (a maintainer testing a pipeline is a pipeline author) uses that entry's story.
- **No actor, no story.** A story that has no human voice looks for one among the actors
  (`AC-*`): a temporal or internal event is usually the factory instance's own story. If no
  voice exists, the content is a quality requirement in `quality.md`, not a story.
- **Tension with `UC-…`**, one optional line after the story, records a conflict between two
  actors' wants; it is material for prioritizing the quality requirements later.
- Mechanism names appear only where the glossary owns them; a file path, a ref name or a
  class name never does.
- Each group file ends with a **Coverage** table: the story, the capability and requirement
  headings that carry it today (the dry run of the `**Serves**:` lines), and the `GP-*` keys
  of [`gaps.md`](gaps.md) it is left short by. A gap is written once there, never in the cell.

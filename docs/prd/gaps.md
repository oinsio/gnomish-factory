# Known gaps

Where the factory as built does not meet a story or a quality requirement of this directory.
The register is Volere's "waiting room": one entry per gap, written once and referenced by key
from the coverage tables of the use-case groups, so a gap that breaks four stories is one
entry, not four cells. An entry is deleted when it closes; git history keeps it.

## The files

The entries live under [`gaps/`](gaps/), one file per subject; a new entry goes into the file
whose subject it is about, and a new file is added here when none fits. Keys are unique
across the directory, so a reference never names the file.

| File | Subject |
|---|---|
| [`installation-and-clone.md`](gaps/installation-and-clone.md) | the factory on the machine: readiness, the registered clone, the slots that share it |
| [`filing-and-claiming.md`](gaps/filing-and-claiming.md) | a task in the tracker before and between its claims |
| [`sandbox-and-secrets.md`](gaps/sandbox-and-secrets.md) | the box, its boundary, what the gnome may reach and hold, host mode |
| [`law-and-checks.md`](gaps/law-and-checks.md) | the law as sealed and the checks that grade an attempt |
| [`decisions.md`](gaps/decisions.md) | forks, arbiters, answers and their record |
| [`delivery.md`](gaps/delivery.md) | the pull request, the merge and what the factory leaves behind |
| [`observing.md`](gaps/observing.md) | what the factory shows of itself and what it counts |
| [`developing.md`](gaps/developing.md) | the factory's own making: build, release, plugin contract, its work on itself |

## The entry form

```markdown
### GP-KEY — one line naming the gap
**Breaks:** the `UC-*` and `QR-*` keys the gap leaves unmet
**Where today:** the capability and requirement that carry the story as far as it goes, or "nothing"
**Kind:** one of the four kinds below
**Resolution:** one of the four resolutions below
```

**Kind** says what sort of work closes the gap:

| Kind | Meaning |
|---|---|
| `missing` | the want was never built; nothing contradicts it, nothing serves it |
| `contradiction` | a requirement promises one thing and the story wants another; one of the two must change |
| `one-medium-only` | served on one execution medium and not on the other; the story holds in the box and fails in host mode, or the reverse |
| `wrong-turn` | built, but on a premise a story refutes; closing it means a decision review, not a patch. The entry names the decision, the premise and the refuting story (`plan.md`, section G) |

**Resolution** says who closes it and when the entry leaves:

| Resolution | Meaning | The entry leaves when |
|---|---|---|
| `open` | nobody has decided yet | a decision moves it to another value |
| `planned: <change>` | an unarchived change under `openspec/changes/` closes it | that change archives |
| `decided: <stance>, lands in <file>` | the gap stays by decision; the stance is a non-goal or a weaker quality requirement | the stance is written in the named file |
| `rejected: <reason>` | the story asked for the wrong thing; the story is rewritten | the story changes |

A `planned:` change that is archived, or a `decided:` stance that has landed, leaves a stale
entry; the gate, once written, reports both.

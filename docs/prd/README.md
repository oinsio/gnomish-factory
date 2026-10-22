# Gnomish Factory — product requirements

The system-level layer above the OpenSpec changes: who the factory is for, what they need from
it, and which qualities it must hold. A change's `proposal.md` states the why of one change;
this directory states the why of the factory, so a requirement is judged against a need, not
against the mechanism that implements it. The order follows ISO/IEC/IEEE 29148: stakeholders
and needs, the operational concept, use cases and quality requirements; system requirements
live in `openspec/specs/`.

**Status: in progress.** The stakeholder register and five of the ten use-case groups are
written; the order of the remaining work, and how it is wired into the specs, is in
[`plan.md`](plan.md).

| File | Holds | Keys |
|---|---|---|
| [`stakeholders.md`](stakeholders.md) | who has an interest in the factory, who interacts with it, what each needs | `SH-*`, `AC-*` |
| `concept.md` (next) | the operational concept: one task from filing to merge, from every stakeholder's seat | none |
| [`use-cases.md`](use-cases.md) | the event partition into ten groups and the entry format; one file per group under `use-cases/` | `UC-*` |
| `quality.md` (next) | quality requirements in the six-part form with a fit criterion each | `QR-*` |
| [`gaps.md`](gaps.md) | where the factory as built does not meet a story or a quality requirement: the kind of each gap and who closes it | `GP-*` |

## PRD keys

Every entry that may be referenced is a heading whose first token is its **PRD key**. The text
after the key may change; the key never does, and a retired key is never reused. Keys are
unique across this directory, mnemonic rather than numbered (`SH-FACTORY-OPERATOR`, not
`SH-3`), and spelled in UPPER-KEBAB.

| Prefix | Entry kind | Meaning |
|---|---|---|
| `SH-` | stakeholder | a person or group with an interest in what the factory does; their needs are stated inside the entry, not as separate keys |
| `AC-` | actor | an automated party that interacts with the factory but has no interest of its own; listed because use cases name it |
| `UC-` | use case | one actor's goal with its trigger, success condition and extensions |
| `QR-` | quality requirement | a system-wide quality stated as source, stimulus, environment, artifact, response and response measure; the measure is its fit criterion |
| `GP-` | known gap | a story or quality requirement the factory as built does not meet; referenced from coverage tables, deleted when closed |

External systems and adversarial sources carry no key: they are depended on or defended
against, never served. A use case or quality requirement names them in its text.

## Referencing from specs and proposals

A spec requirement names the entries it serves on a `**Serves**:` metadata line beside its
`**Implements**:` line; a proposal's *Actors & Use Cases* section names them too.
`checkSpecContract` resolves every referenced key to exactly one heading under this directory
(the rule is in `.claude/rules/traceability.md`, once the gate lands).

```markdown
### Requirement: Resume from the recorded position
A factory instance SHALL ...
**Implements**: FR8 of add-git-workflow
**Serves**: UC-RESUME-ANY-INSTANCE, QR-NO-LOST-ROUND
```

Sources for the register: the glossary, the README, the operator and developer guides, the
sandbox threat registry, the self-hosting pipeline under `.gnomish/`, and the *Users &
Scenarios* sections of all 97 proposals (325 items, read on 2026-10-21).

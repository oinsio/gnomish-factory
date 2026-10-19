---
paths:
  - "openspec/changes/**/design.md"
---

# Rule: design.md — local technical decisions

`design.md` inside a change folder is for non-trivial technical decisions scoped to THIS change. Not every change needs one.

## When to create design.md

- There is a real architectural choice (e.g., "polling vs webhooks for tracker updates in this feature")
- Multiple implementation approaches exist and the reasoning should be recorded
- The decision has trade-offs worth documenting

## When NOT to create design.md

- The implementation is straightforward — no alternatives to consider
- The decision is global and not tied to this change — use `docs/adr/` instead

## Format

Two accepted formats — pick by the number of decisions.

### Single decision

```markdown
# Design: <Change name>

## Context
What drove this decision. Reference FR/NFR from proposal.md.

## Decision
What exactly was decided. Be specific.

## Consequences
Positive:
- ...

Negative:
- ...

## Alternatives Considered
**<Alternative 1>**: why considered and why rejected
```

### Multiple decisions

When a change settles several related decisions, use numbered decisions instead of one giant Decision section:

```markdown
# Design: <Change name>

## Context
What drove these decisions. Reference FR/NFR from proposal.md.

## Decisions
**D1 — <short title>.** What was decided. *Rationale:* why.
*Alternative rejected:* what and why not.

**D2 — ...**

## Risks / Trade-offs
- [risk or negative consequence] → mitigation
```

Each Dn must still carry its rationale and at least one rejected alternative; the shared Risks / Trade-offs section covers the negative consequences. Other artifacts reference decisions as `D1`, `D2`, ...

## Mandatory decision: sync surfaces

One decision category is required, not optional. If the change adds a second implementation
of a rule/capability that already exists for another execution mode, layer, or medium — or
touches either end of a pair declared in `manual-sync-pairs.md` — `design.md` MUST record
the decision: **shared abstraction or declared pair**, chosen by that rule's preference
order (a third implementation always extracts the abstraction), with rationale and the
rejected alternative. A change with no such surface states it explicitly:
`Sync surfaces: none — this change adds no parallel implementation and touches no declared
pair.` Silence is a format violation — the explicit "none" is what makes the question
checkable by `/review-artifacts` and `/audit-implementation`.

## Mandatory decision: single-owner mechanisms

Sync surfaces answer "where is one rule implemented twice". This decision answers the
reverse question: "who must use the one implementation, and what stops them from not using
it". Whenever a decision says that one component is the only source of a value or the only
place a step happens ("resolve once", "one funnel", "the adapter peels once", "the only
sanitizer"), `design.md` MUST carry a table for it:

| Owner | Value (type) | Requires | Consumers | Old way removed | Enforced by |
|-------|--------------|----------|-----------|-----------------|-------------|

- **Value (type)** names the type the result travels as. If it is a primitive a consumer could
  obtain elsewhere (`String`, `Path`, `boolean`), the owner is not enforced — introduce the
  typed value or explain why the primitive cannot be replaced.
- **Requires** names what the owner needs in order to run: a worktree index, a working copy,
  a live box, the network, a claim — or none of those. It is what tells a mechanism named by
  where it was first wired from one bound to that medium: `GitObjects` was "the container
  medium's writer" through three designs and requires only a `.git`, which the host clone has.
  It is also what a consumer's conditions are checked against (`manual-sync-pairs.md`, "A new
  consumer of a declared pair"): an owner that requires a worktree index cannot serve a consumer
  that may run from two processes at once. The failure this exists for: task 3.3 of
  `fix-terminal-receipt-convergence` (2026-10-14) gave the host delivered cleanup the host end,
  `CleanupCommit`, because the bare-object writer was taken to belong to the container; the
  host end required the task worktree's index, two host cleanups met on its `index.lock`, and
  the loser failed. Design D8 of that change moved the host onto `GitObjectsTerminalCommits.
  cleanUp`, opened over the repository's own clone, and its row says "Requires: a `.git`".
- **Consumers** lists every call site that must take the value from the owner, by file. "All
  paths" is not an entry; the implementer's sub-agent (see `implementation.md`) can only check
  a list.
- **Old way removed** names the raw call, default, or signature the owner replaces and states
  that the change deletes it — or lists each surviving exemption (a manual tier, an offline
  path) with its reason.
- **Enforced by** names the mechanism that keeps it true after the change: the parameter type,
  an architecture spec in `:bootstrap`, or a grep gate. Convention is not an entry.
- **The row names the owner and its invariant, never the construction site.** "Opened over the
  repository's own clone, so no other clone can be named" is a design fact; "`GitTaskStore.
  taskRepository` opens it" is an implementation choice the row must leave to the code. A
  construction site written into the row turns a correct placement into a deviation the
  implementer has to ask about, and it is checked against nothing: the enforcement column is
  where the invariant becomes checkable. The failure this exists for: D8 of
  `fix-terminal-receipt-convergence` (2026-10-14) placed the `GitObjects` opening in the store
  by analogy with `ContainerRunSupport`, without reading the constructor it was changing, which
  already held the clone; the sub-agent opened it there, correctly, and stopped to ask.

Where the decision claims two values "are one by construction" or "cannot diverge", the table
row is paired with an identity spec on the real medium (`testing.md`, "Invariant specs across
a flow"). A change with no single-owner mechanism states it: `Single-owner mechanisms: none.`
`/review-artifacts` checks the table exists; `/audit-implementation` checks the code matches
every row.

## A decision added during implementation is reviewed before its task runs

A `Dn` that `/opsx:update` adds while the change is being applied — after a task found what the
plan missed — has skipped the `/review-artifacts` pass the rest of the design went through, and
it is written under the pressure of the finding, usually by analogy with a neighbouring class.
So, before the task that implements it starts:

- the update quotes, in the decision or its task, the current signature of every constructor,
  method or port the decision changes (`GitTaskRepository(GitRunner, RegisteredClone,
  ClaimEpochSource, InstantSource)`), so a wiring claim is checked against the code it names
  rather than against the class it was modelled on;
- `/review-artifacts <change> D<n>` runs scoped to that decision: freshness of its claims
  against the code, its single-owner row against this file, and the tasks it adds.

The failure this exists for: D8 and task 3.5 of `fix-terminal-receipt-convergence`
(2026-10-14) were added after task 3.3 raced two host cleanups; no review ran, the decision
named a construction site that the constructor's own signature contradicted, and the
implementer stopped to ask.

## Alternative zero: remove the mechanism

The first alternative every decision steelmans is **not having the mechanism at all**: what is
left per run, per test, per call once the thing being designed is deleted? Only what survives that
question is designed; everything else is prepared once and reused — a committed preset, a fixture
in the repository, a value computed at build time.

The failure this exists for (2026-10-10): the PIT measurement found that every spec wrote its own
stand-in `git` script per test and macOS assessed each new file at 1–4 s. The first design kept
the per-test generation and made the generated file cheap to run. Two rounds of "why generate at
all?" from the user reduced it to committed scripts and committed presets with, at most, one
symbolic link per test (ADR 0015). The mechanism that was being optimized should have been the
first alternative rejected. The second pass asked the same question of what was left: the
committed links were a registry that alternative zero removes too, since the owner can make one
on first use for what a committed one cost (ADR 0015, as amended).

So, before a `Dn` describes how a per-X artefact is produced, the decision lists what in that
artefact actually varies per X; an alternative that removes the production step is written first,
and rejected only with the varying part named.

## A platform fact is measured on every platform CI runs

A JDK or OS fact a design rests on — what a `close()` wakes, what a lock blocks, what a signal
reaches — is measured on Linux and macOS before it is written into a design, and the design
records the JDK build and OS of each measurement. A fact measured on one platform is an
unverified premise on the other (`spec-mismatch.md`, item 1). The failure this exists for:
design D15 of `fix-terminal-receipt-convergence` measured "a close from another thread ends a
read parked on a child's pipe" on macOS only and wrote it into a requirement, a rule and the
docs; on Linux the close wakes nothing, and the first Linux CI run after that change (run
39117545428) was red (`docs/adr/0018-non-parking-pipe-reads.md`, "The method rule").

## Rules

- Always reference the FR/NFR/UX from proposal.md that drove the decision
- Keep it concise — this is not a research paper
- If the decision applies beyond this change, it belongs in `docs/adr/NNNN-*.md`
- Local decisions in design.md get archived with the change and are not meant to be updated later

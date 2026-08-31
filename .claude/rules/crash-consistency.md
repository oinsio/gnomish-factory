# Rule: crash consistency of multi-step transitions

Applies to any change that adds or modifies a transition with more than one
durable step — a commit plus a push, a push plus a tracker write, an effect
plus its confirmation, a create plus a delete. The principle, the three
mechanisms, and the rejected alternatives live in
`docs/adr/0003-crash-consistency.md`; this rule is the checklist that keeps
new work inside them.

## The question

For every step boundary: **what does the next pickup see?** A frozen
intermediate state that no reader can name is the defect, not the crash.

## Checklist

A transition is not designed until every item has a written answer in the
change's `design.md` or spec:

1. **Kill windows enumerated.** List the durable steps in order; each gap
   between two of them is a kill window with a name.
2. **Every window is a named shape.** Each frozen state classifies to exactly
   one shape of the closed set its medium owns — the `task-branch-contract`
   capability for the branch, the `claim-heartbeat` capability for the
   tracker. A window that maps to no shape means the transition is wrong or
   the shape set is incomplete; say which.
3. **One recovery owner per shape.** Name the component that converges it, and
   whether it rolls forward or discards. Two owners for one shape is a bug.
4. **Mutually-implied fields land together.** If two facts are only true
   together, they land in one commit, not two.
5. **Constructive before destructive.** The step that removes something
   (cleanup, label removal, box disposal) runs after every constructive
   receipt.
6. **Ordering admits the sweeper.** The write that admits a task into the
   sweep universe comes first in its sequence, the write that removes it comes
   last, truth markers in between — so every window freezes a state the
   sweeper's own query enumerates.
7. **External effects follow intent → effect → receipt.** Durable intent
   first, receipt after; recovery of an intent without a receipt probes the
   target before re-driving it.
8. **Recovery is idempotent and convergent.** Running it on an
   already-recovered state changes nothing; running it twice equals running it
   once; a kill during recovery lands in a shape whose recovery finishes the
   work.
9. **Atomicity named per medium.** Say which mechanism from the ADR's
   durability table carries each write, and keep the durability point at the
   successful push.
10. **Kill-point specs exist.** The transition joins the kill-point matrix:
    kill after each durable step, run the pickup, assert the shape and the
    convergence, and assert the second recovery pass is a no-op.

## Referencing

- **Policy ownership is cited by capability, never by change name.**
  Capabilities outlive archives; a change folder is archived and its path
  moves. Write "the `task-branch-contract` capability owns the shape set",
  not a path into `openspec/changes/`.
- **Provenance is cited by change name.** "Introduced by
  `harden-task-branch-contract`" is the right way to record where a rule came
  from — it is history, and history does not move.
- A durable statement of policy belongs in `docs/adr/` or this rules
  directory; a change's `design.md` archives with the change and governs
  nothing afterwards.

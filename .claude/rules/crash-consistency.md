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
11. **Readers name their medium.** Every decision on a recovery path reads the
    durable medium (tip, tracker); a local working copy is a write staging
    area, and a read of a factory-owned file from it is a defect the
    `EnvelopeMediumBoundarySpec` gate rejects.
12. **No write implies an authorisation a later write grants.** For every
    durable step, ask what a pickup that sees it and nothing after it is
    allowed to do; if the answer is "continue past a gate", "deliver", or
    "run the next stage" while a later step was meant to decide that, the
    gate belongs in this step's record (the position is the gate; the stop
    rides the round record), never in the step that follows. Introduced by
    `make-checkpoint-gate-durable`.
13. **Liveness is judged by identity, never by presence.** A reader of a
    medium that keeps the past (a branch tip, a cloned working copy) cannot
    tell "written for this pickup" from "carried over" by looking for a file,
    a marker or a record. The writer stamps the message with an identity the
    orchestrator minted and no later step repeats (the round token: the tip a
    round opened on; the claim epoch; a source identity on a cursor), the
    receiver accepts only the current identity, and deletion of consumed
    messages is hygiene in the consuming commit — never the judge. Two
    independent liveness judgements for one message are two recovery owners
    (item 3). A key that is reset by design — the attempt number restarts on
    an answer, an advancement, a retry — is not an identity. Introduced by
    `make-checkpoint-gate-durable` (design D10).
14. **Recovery rebuilds context from the record through the live path's parse.**
    An identity a run holds in memory (the round token, the attempt commit, the
    claim epoch) has a named producer on the live path and a named producer on
    every recovery path, and both go through the same parse function — recovery
    never derives the value by a second route (a fresh `rev-parse`, a listing,
    a presence check). A per-run holder for such a value is justified by the
    port that forces it, holds whole identities only (never a half the other
    half can drift from), and is named in the design's single-owner table with
    its writers. A guard that compares a recorded value with its own re-read
    of the same medium is a defect, not a check: it cannot fire while the
    record is right, and when the record is wrong it has nothing to compare
    against. Introduced by `make-checkpoint-gate-durable` (D10 as amended).

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

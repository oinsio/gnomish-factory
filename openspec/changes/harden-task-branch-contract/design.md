# Design: harden-task-branch-contract

## Context

See proposal.md — Why. Constraints shaping the decisions:

- Statelessness is load-bearing: the only durable media are the task branch and the tracker.
  Any mechanism needing its own durable store contradicts the architecture.
- The claim lease already exists (claim-heartbeat) and is the mutual-exclusion baseline; all
  divergence scenarios are sequential under a healthy lease, concurrent only inside the
  heartbeat partition window.
- Container mode replicates the branch across up to five repositories (origin, clone,
  round/judge/verification boxes) with one-way movement only; there is no clone→live-box
  channel.
- Implementation follows `bound-subprocess-commands`: its named invocation outcomes
  (exited / timed-out / interrupted) are inputs here. `fix-denial-attribution-durability`
  follows this change and routes its restore through the classifier.
- Everything here must clear the PIT gate per `.claude/rules/testing.md`.

## Goals / Non-Goals

**Goals:** one principle (every pose has exactly one recovery owner), three mechanisms
(single-commit transitions; intent→receipt for external effects; reaper-owned tracker
shapes), each mechanism owned by exactly one class; discipline outlives the change via ADR +
process rule + kill-point gate.

**Non-Goals:** a saga/workflow journal (D1); WAL or multi-ref transactions; block-allocated
sequence counters; changing report content.

## Decisions

**D1 — Reconciliation, not a journaled saga: the media are the journal.** Recovery reads the
world (branch tip + tracker state) and classifies it; no step journal exists. *Rationale:* a
journal needs a durable home — a third source of truth that statelessness forbids and that
can itself diverge (the defect class being fixed). Precedents: Kubernetes controllers
(level-triggered reconcile), crash-only software (recovery path = startup path), and the
project's own denial-cursor decision (position rides the record). *Alternative rejected:*
saga/workflow engine — journal placement is unsolvable here, and cross-instance saga handoff
under a lease is a distributed-workflow engine. Recorded durably as a new ADR in `docs/adr/`
(FR2, G5); this design references, not restates, it.

**D2 — Initial `state.json` is synthesized at branch creation from the frozen law** (Q1):
version 1, position `atStage(<first stage of the frozen pipeline>)`, zero attempts, empty
totals, null cursor — committed in the STARTED commit beside `task.json` (FR3). The
pre-contract shape (task.json alone) stays legal and resumes identically. *Rationale:* the
branch contract holds from the first commit; old branches heal without surgery.
*Alternative rejected:* fabricating state at read time only — leaves every future tip reader
with a special case.

**D3 — One classifier over a tip-reader seam.** Shape classification is one domain-side
component; media access is a narrow "read file at tip" port with three adapters (worktree
tip, `git show`, bare objects). *Rationale:* three access paths must not become three
classifiers (FR1, FR2). *Alternative rejected:* per-reader classification — the scatter that
produced the audit findings.

**D4 — One canonical shape vocabulary, owned by the spec.** The closed set of eleven shapes
and their meanings live in exactly one place — the "Total branch-shape classification"
requirement of the `task-branch-contract` spec. This design and the task list refer to that
table; neither restates it, because three copies of a "closed" set is how the sets drift
apart. Two naming constraints the table encodes: a shape name must not collide with an
existing domain type, so "awaiting a human" is `Parked` rather than `Escalated` (a
`TaskOutcome` variant) and "decision landed" is `Answered` rather than `Decision` (the human's
answer record); and an unsupported envelope version is its own shape rather than a flavour of
`Corrupt`, so its diagnosis can name the version instead of a parse failure. Sealed hierarchy;
readers switch without default. Each shape declares roll-forward or discard recovery in one
table (the saga "pivot" idea without the journal). *Alternative rejected:* hard-coded `tip^`
delivered detection — breaks on any post-cleanup human commit.

**D5 — One intent→effect→receipt component for the five external-effect flows** (host park,
container park, completion finish, decision acknowledge, abort mark; FR10). Flows supply the
intent record, the effect, and an effect-observed probe; the component owns ordering,
receipt, and check-target-before-redrive. Destructive steps run after all constructive
receipts (prune-last). *Rationale:* five hand-rolled marker dances are five divergence
opportunities; precedent: Kubernetes Job tracking (intent list + finalizer receipt) and the
in-repo push primitive consolidation. *Alternative rejected:* per-flow markers — the
container park proved a flow can silently opt out.

**D6 — The claim epoch is the tracker-assigned claim comment id** (Q2): GitHub comment ids
increase monotonically, so each (re)claim's comment id is a ready-made epoch — no counter
storage, no CAS. Stamped as a commit trailer and inside comment markers; readers compare
against the live claim's id (FR13). Self-fencing: an unconfirmed heartbeat freezes writes at
the next boundary (extends the existing claim-loss flag). *Rationale:* true server-side
fencing is unavailable (NG2); detectability is the achievable half, and the tracker already
allocates the monotonic number. *Alternative rejected:* an epoch counter in `state.json` —
a second writer-owned counter that resume must reconcile.

**D7 — One marked-comment primitive in the GitHub adapter** (FR11): hidden content-identity
marker (`task + intent kind`), find-then-upsert; the five existing marker kinds migrate onto
it. Keyed never on the bot account (a documented Renovate failure). *Alternative rejected:*
idempotency for new writes only — mixed guarantees are none.

**D8 — One replica-pair reconciler.** The EQUAL/AHEAD/BEHIND/DIVERGED relation and its
policies (keep / fast-forward / discard-under-lease) live in one component instantiated for
clone↔origin in both execution modes, replacing the host and container twins; the box↔clone
harvest refusal maps to the same DIVERGED verdict (FR8). The discard reset is a local-ref CAS
against the decided tip; origin history is never rewritten (NFR-R3). *Rationale:* arbitration
became decidable when the claim protocol landed — origin advances only through legitimate
lease holders, so unpushed local work is already "nonexistent" by NFR-R3 of the git-workflow
change. *Alternative rejected:* an operator `--discard-local` flag — automation is safe under
the lease, and the flag re-introduces manual surgery.

**D9 — One automatic-retry accounting** (FR14): the crash fuse and the recovery budget merge
into a single persisted counter model with categorized causes (instance crash / recovery
failure) and one quarantine outcome carrying the history. The three non-recoverable shapes
named in D4's table bypass the counter — first classification quarantines (FR15). *Rationale:* two near-identical
counters with two thresholds invite the drift the audit already found in the abort marker.
*Alternative rejected:* a separate recovery counter beside the fuse.

**D10 — The atomic file writer moves to a shared dependency-free leaf module**, and both
persisters plus the existing dashboard writer use it (FR5). Durability point remains the
successful push: local fsync discipline is deliberately NOT added — a lost local write is
expendable by D8's arbitration; this acceptance is recorded in the ADR. *Alternative
rejected:* a second copy in the git adapter (module boundaries forbid the sideways import;
copies drift).

**D11 — One factory-owned-paths policy for salvage** (FR5): the `.gnomish-task/` ownership
list is a single constant consumed by host and container salvage; factory files restore from
tip, gnome files salvage.

**D12 — Container parks record through the existing bare-objects repository; escalated
resume disposes the kept box before the decision commit** (FR10, FR17). *Rationale:* the
pending-marker write already exists on the bare-objects path (host parity); for the decision,
no clone→live-box channel exists, so the invariant "no factory-side commit while a kept box
survives" is enforced by disposal — the next round's fresh box seeds from the decided tip;
cost is one re-seed. *Alternative rejected:* pushing the decision into the live box — builds
the missing reverse channel for one consumer and doubles the reconciliation surface.

**D13 — The kill-point harness is a table-driven spec over (transition × kill point ×
pickup)** (M1): each multi-step transition enumerates its durable steps; the harness kills
after each, runs the pickup against a local bare origin (Gitea only where a real remote
protocol matters), asserts the classified shape and convergence, and runs every recovery
twice (idempotence). Gate placement per Q4: `check` if measured runtime allows, else a
dedicated CI lane — decided by measurement, recorded in tasks.

**D14 — Fetch-failure classification builds on the subprocess outcomes** (FR6): a locate
fetch is "branch absent" only on a confirmed missing-remote-ref result; timed-out,
interrupted, and every other failure classify as infrastructure (retry, then abort the
take). The same split applies to the remote-tip probe used by reconciliation.

**D15 — Documentation layering** (G5): the ADR carries the principle and the three
mechanisms plus rejected alternatives (saga journal, WAL, block counters, fsync); a new
`.claude/rules/` crash-consistency rule carries the future-work checklist (every multi-step
transition names its kill windows and recovery owner); `docs/glossary.md` gains the terms
branch shape, tracker shape, sweep universe, recovery owner, claim epoch,
intent/receipt. Design decisions here stay scoped
to this change; the durable principle lives in the ADR.

**D16 — The tracker medium gets the same total classification as the branch medium** (FR19,
FR12). One core classifier — the mirror of D3 — maps adapter-reported tracker facts (state
labels, claim footprint, boundary markers) to the closed shape set owned by the
`claim-heartbeat` delta; consumers switch exhaustively; adapters report facts and never
judge (the audit found the staleness judgment split between the `listOpen` omission rule and
the reaper's eligibility filter — two half-brains no sweep could reconcile). The reaper
sweeps the union of both listings, because a sweeper filtering on the very label a kill
window may not have written yet is structurally blind (precedents: Kubernetes
ownerReference-at-creation, Prow sinker's complement query). Time judgments (staleness TTL,
window grace) stay with the observation memory. Write order follows the sweep-universe
rule: the label admitting the task into the universe first, the label removing it last,
truth markers between — markers are the truth, labels the index. *Alternatives rejected:*
claim comment before the label with a lease deadline in the comment body — reintroduces
holder-written time the staleness model deliberately avoids, and its kill window freezes on
a ready-labeled issue outside every sweep while still winning the earliest-id race; the
task-branch ref as the claim CAS — collapses the two writes into one atomic primitive but
moves mutual exclusion into a medium a non-git tracker adapter lacks (kept as an open ADR
question for the future).

## Risks / Trade-offs

- [Scope at the top of the 1–4 week invariant] → cut line, in order: D13 harness breadth
  (keep transitions touched by fixed defects, backfill the rest in a follow-up), D7
  migration of pre-existing marker kinds. Both trail cleanly without reopening interfaces.
- [Classifier becomes a bottleneck for every reader] → it is one read of a tip already being
  read; adapters add no extra subprocess calls beyond today's.
- [Automatic discard destroys work] → only unpushed work under a lost lease — already
  "nonexistent" for the fleet; the repair log (NFR-O1) names every discard.
- [Epoch-as-comment-id couples fencing to GitHub] → the tracker port exposes "monotonic
  claim token"; Jira's adapter chooses its own monotonic source.
- [Merging the fuse and the recovery budget changes park semantics] → the quarantine report
  carries the cause history, so operators see crash-vs-recovery causes distinctly (NFR-O2).
- [Kept-box disposal on decision loses warm state] → the box was parked for a human
  round-trip (hours); a re-seed costs seconds and buys the invariant.
- [Active sandbox changes touch the same regions] → deltas here are ADDED-heavy; the two
  known active-change couplings are sequenced in Impact (subprocess before, denial-durability
  after); `add-sandbox-*` introduce no new refs (verified against their proposals).

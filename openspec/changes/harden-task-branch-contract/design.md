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

**Goals:** one principle (every shape has exactly one recovery owner), three mechanisms
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
table; neither restates it. *Rationale:* three copies of a "closed" set is how the sets
drift apart — one owner keeps the set actually closed. Two naming constraints the table encodes: a shape name must not collide with an
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
marker (`task + intent kind`), find-then-upsert; the eight existing marker kinds migrate onto
it. Keyed never on the bot account (a documented Renovate failure). *Alternative rejected:*
idempotency for new writes only — mixed guarantees are none.

**D8 — One replica-pair reconciler.** The EQUAL/AHEAD/BEHIND/DIVERGED relation and its
policies (keep / fast-forward / discard-under-lease) live in one component instantiated for
clone↔origin in both execution modes, replacing the host and container twins (`WorktreeDivergenceCheck`,
`ContainerResumeBranch`) — and `OriginReconciliation`, the third computer of the same
ancestry relation at task touchpoints, consumes the reconciler's verdict instead of keeping
its own; the box↔clone harvest refusal maps to the same DIVERGED verdict (FR8). The discard reset is a local-ref CAS
against the decided tip; origin history is never rewritten (NFR-R3). *Rationale:* arbitration
became decidable when the claim protocol landed — origin advances only through legitimate
lease holders, so unpushed local work is already "nonexistent" by NFR-R3 of the git-workflow
change. The discard is gated on that lease being held for the task (the instance's own tenure record,
`ClaimEpochBook`): the claimless `run --resume` paths keep the pre-FR8 stop-and-report, because
there origin was never arbitrated by any lease and the local line may be the operator's only
copy. *Alternative rejected:* an operator `--discard-local` flag — automation is safe under
the lease, and the flag re-introduces manual surgery. *Alternative rejected:* re-reading the
tracker immediately before the ref swap — a lease re-check just before a write buys no safety
(the writer can be paused between check and write), and this write moves only a local ref no
peer reads; the writes that reach shared media are fenced where they land.

**D9 — One automatic-retry accounting** (FR14): the crash fuse and the recovery budget merge
into a single persisted counter model with categorized causes (instance crash / recovery
failure) and one quarantine outcome carrying the history. The three non-recoverable shapes
named in D4's table bypass the counter — first classification quarantines (FR15). *Rationale:* two near-identical
counters with two thresholds invite the drift the audit already found in the abort marker.
*Alternative rejected:* a separate recovery counter beside the fuse.

**D10 — The atomic file writer moves to a shared dependency-free leaf module** (`atomicfile`),
and every non-atomic writer of `.gnomish-task/` files adopts it — per the code these are the
host-side writers `GitAttemptPersistence`, `GitTaskRepository` (initial files),
`TerminalWriteMarker`, and `TraceLineWriter`; the container-side persisters are atomic at
commit granularity by construction — the round-state persister
(`EnvironmentAttemptPersistence`) writes `state.json` into the box through the file
channel's `putFile` (a plain in-box write, not temp+rename) and commits in-box, using bare
objects only for read-back verification, while the lifecycle persister
(`GitObjectsTaskRepository`) builds its commits from bare objects — so a partial in-box
write never reaches a commit, and FR5's restore-from-tip rule keeps every reader away from
it; the dashboard writer already uses the writer and only moves with it (FR5). The
per-medium atomicity/durability table (host atomic writer / in-box commit granularity /
bare objects) is owned by the crash-consistency ADR (D15); the ADR also records two
accepted non-mechanisms: durability point remains the successful push — local fsync
discipline is deliberately NOT added, a lost local write is expendable by D8's
arbitration — and `putFile` is deliberately NOT made atomic: commit granularity plus the
restore-from-tip rule already carry the invariant, and a second guard on the same path
would blur which mechanism owns it. *Alternative rejected:* a second copy in the git
adapter (module boundaries forbid the sideways import; copies drift).

**D11 — One factory-owned-paths policy for salvage** (FR5): the `.gnomish-task/` ownership
list is a single constant consumed by host and container salvage; factory files restore from
tip, gnome files salvage. *Rationale:* two mode-local ownership lists are the same scatter
D3 removes from classification — they drift, and a path missed by one mode silently trusts
the dirty worktree. *Alternative rejected:* per-mode lists kept in each salvage — the
divergence opportunity this change exists to close.

**D12 — Container parks record through the existing bare-objects repository; escalated
resume disposes the kept box before the decision commit** (FR10, FR17). *Rationale:* the
bare-objects repository already carries the outcome write (`recordOutcome`), but the
container park path never invokes it today — the Why's "records nothing on the branch" and
this reuse are both true; the pending-marker protocol lands on that existing write path
(host parity); for the decision,
no clone→live-box channel exists, so the invariant "no factory-side commit while a kept box
survives" is enforced by disposal — the next round's fresh box seeds from the decided tip;
cost is one re-seed. *Alternative rejected:* pushing the decision into the live box — builds
the missing reverse channel for one consumer and doubles the reconciliation surface.

**D13 — The kill-point harness is a table-driven spec over (transition × kill point ×
pickup)** (M1): each multi-step transition enumerates its durable steps; the harness kills
after each, runs the pickup against a local bare origin (Gitea only where a real remote
protocol matters), asserts the classified shape and convergence, and runs every recovery
twice (idempotence). The idempotence assertion tolerates `CapturedExec`'s documented
conservative interrupt classification — an interrupt landing just after a clean exit
re-runs at worst a service commit, never paid executor or judge work (NFR-C1 untouched).
Gate placement per Q4: `check` if measured runtime allows, else a
dedicated CI lane — decided by measurement, recorded in tasks. *Rationale:* the audit found
the defect class by asking "what does the next pickup see?" — the table makes that question
executable for every transition, present and future (G5). *Alternative rejected:* per-defect
regression specs only — they pin the seventeen known findings and leave every new transition
unasked.

**D14 — Fetch-failure classification builds on the subprocess outcomes** (FR6): a locate
fetch is "branch absent" only on a confirmed missing-remote-ref result; timed-out,
interrupted, and every other failure classify as infrastructure (retry, then abort the
take). The same split applies to the remote-tip probe used by reconciliation. These
infrastructure retries are their own budget, distinct from the single bounded re-attempt
bound-subprocess-commands governs: that change forbids spending a re-attempt on an
interrupted or timed-out invocation, and FR6/FR7 comply — an interrupted invocation is
never re-driven within the run, and a timed-out network invocation re-enters only the
infrastructure-retry policy (Resilience4j), never a re-attempt count. For the load-bearing
first push (FR7) a timed-out push is an unknown remote outcome: the retry loop SHALL
re-check the remote tip within its own bound before re-pushing, and treats a confirmed
landed ref as success. The named-outcome taxonomy (exited / timed-out / interrupted) has
one representation per execution medium — `GitCommandResult.Termination` in the git
runner, an `InterruptedIOException` cause from `CapturedExec` on the in-box exec path —
and each medium maps its native representation onto the taxonomy in exactly one
adapter-owned seam (the D3 principle applied to invocation outcomes): classification and
retry policy consume the taxonomy only, and no call site branches on exception types ad
hoc. *Rationale:* only the subprocess layer can distinguish
"origin confirmed the ref missing" from "the invocation never got an answer" — the named
outcomes make the split decidable where a bare exit code could not. *Alternative rejected:*
treating any locate-fetch failure as "branch absent" (the status quo — the duplicate-branch
defect U5 fixes).

**D15 — Documentation layering** (G5): the ADR
(`docs/adr/0003-crash-consistency.md`) carries the principle and the three
mechanisms plus rejected alternatives (saga journal, WAL, block counters, fsync); a new
`.claude/rules/crash-consistency.md` rule carries the future-work checklist (every multi-step
transition names its kill windows and recovery owner); `docs/glossary.md` gains the
"Crash consistency" section with the terms
branch shape, tracker shape, sweep universe, recovery owner, claim epoch,
intent/receipt, quarantine, and the epoch sense of fence. Design decisions here stay scoped
to this change; the durable principle lives in the ADR. *Rationale:* each medium matches its
lifetime — the archived design cannot govern future changes, the ADR and rule can.
*Alternative rejected:* keeping the principle only in this design.md — it archives with the
change and outlives nothing (the G5 failure mode).

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
truth markers between — markers are the truth, labels the index. The repairs are port
physics, never adapter judgment: `removeStaleClaim` generalizes to dead footprints
(absent live version), and one new `repairIndex` operation restores a claimless working
task and completes a marker's lagging flip, both guarded by a re-read no-op. *Alternatives rejected:*
claim comment before the label with a lease deadline in the comment body — reintroduces
holder-written time the staleness model deliberately avoids, and its kill window freezes on
a ready-labeled issue outside every sweep while still winning the earliest-id race; the
task-branch ref as the claim CAS — collapses the two writes into one atomic primitive but
moves mutual exclusion into a medium a non-git tracker adapter lacks (kept as an open ADR
question for the future).

## Sync surfaces (mandatory)

**D17 — Sync surfaces.** This change touches eight of the pairs the initial registry in
`.claude/rules/manual-sync-pairs.md` listed when the change began — one of which, the salvage
pair, ends the change carrying `Kept in sync with` markers at both ends and so leaves that
registry — plus one undeclared pair that predates the registry entirely.
Chosen per that rule's preference order: the undeclared pair is collapsed into a shared
abstraction, the eight stay declared pairs — seven changed symmetrically, one deliberately
one-sided. No third implementation of any rule is introduced, so no further extraction is
forced.

*Collapsed into a shared abstraction (preference 1):* **`WorktreeDivergenceCheck` / the
container divergence twin → `ReplicaPairReconciler`** (D8). The EQUAL/AHEAD/BEHIND/DIVERGED
relation, its policy and its claimless gate now live in one class both modes call through
`TaskBranchGit.reconcileRemote`; the modes differ in exactly one step (host resyncs a working
tree, container moves refs alone), carried by one seam inside the class. The pair was never in
the registry and ends here rather than being declared into it.

*Kept as declared pairs (preference 2) — the duplication is deliberate medium decoupling
(a worktree with a checked-out tree versus bare objects or an in-box `sh -c` script), and the
shared part of each rule is already extracted into a helper both ends call:*

| Pair                                                      | Synchronized invariant                                                          | Extracted shared part                                                                                                                                                     |
|-----------------------------------------------------------|---------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `WorktreeSalvage` / `EnvironmentSalvage`                  | salvage commit shape: epoch trailer + factory-path restore                      | `FactoryOwnedPaths` (D11), `ClaimEpochTrailer` — the one pair whose ends now carry the `Kept in sync with` markers, so its initial-registry row is dropped in this change |
| `GitAttemptPersistence` / `EnvironmentAttemptPersistence` | round commit + `state.json` write sequence, epoch-stamped                       | `ClaimEpochTrailer` (the in-box end also reads `GnomishTaskPaths` for the state path)                                                                                     |
| `GitTaskRepository` / `GitObjectsTaskRepository`          | lifecycle write protocol: one transition = one commit (FR4), epoch stamp (FR13) | `TaskLifecycleCommitWriter`, `ClaimEpochSource`                                                                                                                           |
| `TakeFreshClaim` / `TakeContainerFreshClaim`              | fresh-claim recipe, now carrying the synthesized initial state (D2)             | `GitFreshTaskSupport.createTask`                                                                                                                                          |
| `TakeEngineExecution` / `TakeContainerEngineExecution`    | terminal-boundary wiring: intent → effect → receipt for park and finish (D5)    | `ParkTransition`, `FinishTransition`                                                                                                                                      |
| `TakeResumeRunner` / `TakeContainerResumeRunner`          | resume-with-decision recipe: commit the decision, then acknowledge (FR12)       | `TaskRepository.appendDecision(taskId, decision, resetState)`                                                                                                             |

`GitModeRunner` / `ContainerGitModeRunner` change symmetrically and minimally: both hand the
synthesized initial state to `GitFreshTaskSupport.createTask` (D2). The manual-run control
flow itself is unchanged, so the pair stays declared with nothing further to extract.

*One-sided by intent:* `GitResumeRunner` documents the new divergence policy on the
`run --resume` path; `ContainerResumeRunner` gets the mirrored javadoc sentence but no code
change, because the behavior itself moved into the shared `ReplicaPairReconciler` both modes
already call — there is no per-mode rule left to duplicate. Recorded here so the asymmetry is
a decision rather than an omission.

*Alternative rejected:* extracting a `ResumeMechanics`-style abstraction over the seven kept
pairs in this change. Each spans two media with different primitives, the extraction is a
change-sized refactoring of its own, and folding it in would push this change past the 1–4
week scope invariant. The rule permits a declared pair here; the abstraction becomes mandatory
if a third medium arrives.

## Risks / Trade-offs

- [Scope at the top of the 1–4 week invariant] → cut line, in order: D13 harness breadth
  (keep transitions touched by fixed defects, backfill the rest in a follow-up), D7
  migration of pre-existing marker kinds (eight, not the five first estimated — the larger
  volume strengthens this cut, and partial migration still trails cleanly: unmigrated kinds
  keep their current write paths). Both trail cleanly without reopening interfaces.
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

# Harden task branch contract

## Why

A field trial of autonomous `serve` surfaced a class of defects with one shape: the factory
performs a multi-step transition (git commit → tracker write → confirmation), a crash between
steps freezes an intermediate state, and the next pickup misreads it. A systematic audit found
seventeen instances. The worst are permanent: a task whose first round died infrastructurally
crash-loops on every resume until it parks unreturnable (`state.json` is written only with the
first completed round, yet resume demands it); a container-mode park records nothing on the
branch, so the human's escalation answer is never read and every return re-parks; a claim whose
label moved before its comment posted is a task no reaper will ever reclaim. Others burn money
silently: a kill between "stage passed" and the next round's snapshot re-runs the whole green
stage, judge votes included; a kill between the `Completed` commit and the cleanup commit
re-runs the final stage. Each defect was found by the same question — "what does the next
pickup see?" — which no invariant in the codebase forces anyone to ask.

## What Changes

- **ADDED** `task-branch-contract` — the branch-shape contract as a capability: a total
  classifier over the task branch tip (every combination of files, versions, and claim epochs
  yields a named shape, `Unknown` included), exactly one recovery owner per shape, a claim
  epoch stamped into every commit and tracker write, replica-pair reconciliation rules
  (origin wins on divergence, under the lease), and a recovery attempt budget with quarantine.
- **MODIFIED** `git-task-persistence` — the STARTED commit carries an initial `state.json`
  (written once by `TaskRepository`, the one exception to `AttemptPersistence` ownership);
  every logical transition lands as one commit (decision + attempt-counter reset together;
  stage advancement persisted with the passing round); state files are written atomically;
  the first push of a new branch is load-bearing; cleanup follows a pending-cleanup marker
  (destructive step last); durability point is the successful push, never the local commit.
- **MODIFIED** `tracker-take` — fresh-vs-resume routing goes through the classifier against
  origin-confirmed state: a fetch failure is an infrastructure error, never "branch absent";
  divergence resolves automatically (origin wins) instead of a terminal exit; a
  `Completed`-without-cleanup tip is finished, not re-executed; corrupt and unknown shapes
  park with a diagnosis on first classification instead of burning the crash fuse.
- **MODIFIED** `stage-engine` — a resume never re-executes a stage whose recorded last round
  at the recorded position carries a passing verdict.
- **MODIFIED** `github-tracker` — every factory comment is an upsert keyed by a hidden
  content-identity marker (never a blind post); claim, reap, finish, park,
  decision-acknowledge, and abort sequences are ordered by the sweep-universe rule so every
  kill window freezes a named tracker shape; the listings report facts only — no
  adapter-side omission or judgment; label transition failures and HTTP failures join the
  retryable tracker-unavailable hierarchy.
- **MODIFIED** `tracker-port` — the listing operations report tracker facts without
  adapter-side judgment: `listOpen` returns every open-state-labeled task with its label
  set and claim facts (never omitting an uninterpretable combination), `listReady` entries
  carry the same claim facts from the enrichment read; state judgment lives in core.
- **MODIFIED** `claim-heartbeat` — a total tracker-shape classification, the tracker-side
  mirror of the branch classifier: every combination of state labels, claim footprint, and
  boundary markers classifies to a named shape with exactly one recovery owner; the reaper's
  sweep universe is the union of both listings and it repairs every non-steady shape; a
  holder that cannot confirm its own heartbeat freezes at the next boundary before the
  reaper can act; every (re)claim issues a monotonically increasing epoch.
- **MODIFIED** `execution-environment` — a container-mode park records its outcome on the
  branch through the same pending-marker protocol as host mode; a factory-side commit while a
  kept box survives is forbidden (the box cannot learn of it), so escalation decisions
  dispose the kept box; host and container salvage share one factory-owned-paths policy that
  restores factory files from the tip instead of trusting the dirty worktree.
- **MODIFIED** `task-inspection` — `status` and `usage` tolerate every legal shape: a
  delivered branch renders as delivered, a just-created branch as pending, an unreadable
  historical commit is skipped with a warning; one bad branch never breaks the listing.

## Capabilities

### New Capabilities

- `task-branch-contract`: the total branch-shape classification, shape→owner recovery
  routing, claim-epoch fencing, replica-pair reconciliation rules, and the recovery budget.

### Modified Capabilities

- `git-task-persistence`: initial state in the STARTED commit; one transition = one commit;
  atomic file writes; load-bearing first push; pending-cleanup marker; push as the durability
  point.
- `tracker-take`: classifier-driven routing on origin-confirmed state; fetch-failure
  classification; automatic divergence resolution; deferred finishing of
  `Completed`-without-cleanup tips; first-classification quarantine for corrupt shapes.
- `stage-engine`: persisted stage advancement — a passing round and its advancement land as
  one durable transition, so a resume starts the following stage.
- `github-tracker`: marker-keyed upsert comments; sweep-universe write ordering for claim,
  reap, finish, park, acknowledge, abort; facts-only listings; widened retryable-failure
  hierarchy.
- `tracker-port`: facts-only listing surfaces (`listOpen` label and claim facts, `listReady`
  claim facts); judgment moved to core.
- `claim-heartbeat`: total tracker-shape classification with per-shape recovery owners;
  union sweep universe; holder self-fencing; claim epochs.
- `execution-environment`: container park persistence; kept-box vs factory-side commit
  exclusion; shared salvage policy for factory-owned paths.
- `task-inspection`: shape-tolerant status listing and usage history.

## Impact

- `:domain` — branch-shape and tracker-shape value types, epoch types; single-commit pass-and-advance transition; recovery budget model.
- `:adapters:git` — classifier tip-reading adapters (worktree, `git show`, bare objects);
  atomic writes; initial-state commit; pending-cleanup marker; CAS local-ref reset for discard; the
  divergence reconciler consolidating the host and container twins and
  `OriginReconciliation`'s touchpoint ancestry check.
- `:adapters:github` — marked-comment upsert primitive and migration of the eight existing
  marker kinds onto it; sweep-universe write ordering for claim/reap/finish/park/ack/abort;
  facts-only listing surfaces; exception hierarchy.
- `:application` — take routing through the classifier; recovery budget unified with the
  crash fuse; container park recording; tracker-shape classifier and the generalized reaper
  repair duty (claim guard and take consume shapes instead of throwing); shared salvage
  policy.
- `:sandbox:docker` — container salvage policy consumption; kept-box disposal on decision.
- `bootstrap` — wiring; the container-mode terminal paths (`ContainerRunSupport`,
  `ContainerRunTermination`) the container park and kept-box disposal changes land in;
  kill-point test harness over the Gitea E2E layer.
- New durable docs in this change: `docs/adr/` crash-consistency ADR (reconciliation over a
  saga journal; media are the journal), a `.claude/rules/` crash-consistency checklist for
  future multi-step transitions, and `docs/glossary.md` entries (branch shape, tracker shape,
  sweep universe, recovery owner, claim epoch, intent/receipt, quarantine, fence).
- Sequencing: implementation starts after `bound-subprocess-commands` lands (its named
  command outcomes — exited / timed-out / interrupted — are inputs to fetch-failure
  classification and load-bearing push retries). `fix-denial-attribution-durability`
  implements after this change and routes its resume restore through the classifier.

## Goals

- G1: no frozen intermediate state of any factory transition can make a task permanently
  unclaimable, silently re-execute paid work, or lose a human's decision — every kill window
  lands in a classified shape with exactly one recovery owner.
- G2: recovery is idempotent and convergent: recovering an already-recovered state is a no-op,
  and repeating any recovery twice equals running it once.
- G3: a corrupt or unclassifiable state costs one classification, one diagnosis, and one
  park — never a crash loop.
- G4: divergence between local and origin resolves automatically under the lease; no exit
  code demands manual git surgery.
- G5: the discipline outlives this change: the invariants live in an ADR, a process-rule
  checklist, and a kill-point test gate that future transitions must pass.

## Non-Goals

- NG1: making every round push load-bearing — the local-commit-then-best-effort-push
  durability boundary stays; only the first push of a new branch becomes load-bearing.
- NG2: true server-side fencing — git and GitHub cannot reject stale-epoch writes; epochs
  make zombie writes detectable and classifiable, not impossible.
- NG3: bounding the duration of a single subprocess invocation (deadlines, stall
  detection) — owned by the `subprocess-supervision` capability; attempt counts and
  backoff for retried operations are the FR6/FR7 budgets of this change.
- NG4: narrowing the heartbeat partition window below one round — the epoch plus self-fencing
  bounds its cost to one duplicate round, which is accepted.
- NG5: multi-ref or cross-repository transactions — cross-repo movement stays reconciled,
  never transactional; a WAL and block-allocated sequence refs are explicitly rejected
  (recorded in the ADR).
- NG6: tracker-side rendering changes beyond idempotency — report content is untouched.

## Users & Scenarios

- U1: an operator's host dies mid-first-round; the returned task resumes from the initial
  state instead of crash-looping to an unreturnable park.
- U2: a container-mode task parks with a question; the operator answers and returns it; the
  factory reads the answer and continues — today's container tasks never do.
- U3: a kill lands between "final stage passed" and delivery; the next pickup finishes
  cleanup and delivers without re-running the stage or re-paying the judge.
- U4: an instance dies after its work was superseded from another host; the next pickup on
  the first host discards the stale local branch automatically and continues from origin.
- U5: a network blip during claim no longer forks a duplicate branch: the take retries or
  aborts instead of treating the failed fetch as "no branch exists".
- U6: the operator lists `gnomish status` over a repository containing delivered, fresh, and
  in-flight tasks; every row renders.
- U7: a task with a genuinely corrupt branch parks once with a diagnosis naming the corrupt
  file and the expected shape; the operator fixes or abandons it; the fleet never loops on it.

## Requirements

### Functional

- FR1: a total classifier SHALL map any task branch tip — file set, envelope versions, claim
  epoch — to exactly one named shape drawn from the closed set the `task-branch-contract` spec
  defines; unrecognized combinations map to `Unknown`, never to a thrown exception or a
  closest match.
- FR2: every reader of task-branch state (take routing, resume, reconcile, status, usage,
  denial-cursor restore) SHALL obtain the shape only through the classifier; per-shape
  handling SHALL be exhaustive by construction (sealed types, no default branch).
- FR3: the STARTED commit SHALL carry both `task.json` and an initial `state.json`; a branch
  whose tip predates this contract (task.json without state.json) SHALL classify as a legal
  shape that resumes the first stage from scratch.
- FR4: every logical transition SHALL become durable as exactly one commit on the task
  branch: a human decision lands with the attempt-counter reset; a passing round lands with
  the advanced pipeline position; a container park lands as the outcome commit with the
  pending marker. No mutually-implied fields may split across commits.
- FR5: no reader of `.gnomish-task/` state files — a salvaging resume included — SHALL ever
  observe a partially written file. Each write medium realizes the invariant per the
  crash-consistency ADR's durability table: host-worktree writers use the shared atomic
  writer (temp file + atomic rename); the container-side persisters are atomic at commit
  granularity — round state is written into the box and committed in-box, lifecycle commits
  are built from bare objects — so a partial in-box write never reaches a commit. Recovery
  SHALL restore factory-owned files under `.gnomish-task/` from the branch tip and never
  salvage them from a dirty worktree (which also shields the partial in-box write), while
  gnome-owned work files remain salvageable; both salvage paths SHALL consume one shared
  factory-owned-paths policy.
- FR6: fresh-vs-resume routing SHALL rely only on origin-confirmed state: a locate fetch that
  fails for any reason other than a confirmed missing remote ref SHALL classify as an
  infrastructure failure (retried under the infrastructure-retry policy — a budget separate
  from the bounded re-attempts of bound-subprocess-commands — then abort the take) and
  SHALL NOT route to a fresh claim.
- FR7: the first push of a newly created task branch SHALL be load-bearing: bounded retries,
  and on exhaustion the take aborts without starting a round; all subsequent pushes stay
  best-effort. A timed-out first push is an unknown remote outcome: before any re-push the
  retry loop SHALL re-check the remote tip within its own bound and SHALL treat a confirmed
  landed ref as success (per bound-subprocess-commands, a timed-out invocation never spends
  a bounded re-attempt).
- FR8: when the local branch and origin have diverged and the instance holds a live claim,
  recovery SHALL discard the local branch (reset to the origin tip, drop drafts) and
  continue — automatically, without an operator flag; the reset SHALL be an explicit
  local-ref compare-and-swap against the tip the decision was made on — no push is involved. Local-ahead keeps local;
  local-behind fast-forwards; only true divergence discards. Where no live claim is held —
  the claimless `run --resume` paths — a diverged pair SHALL stop and report instead of
  discarding, since the discard's whole justification is the claim protocol.
- FR9: a tip whose recorded outcome is `Completed` but whose cleanup has not happened SHALL
  be finished — cleanup committed, pushed, tracker finish delivered — and SHALL NOT re-enter
  the engine. FR9 is the recovery path of FR4's terminal half: once FR4's single-commit
  transitions land, this split state arises only from pre-contract history and kill windows.
  Deliberately out of scope: a pre-contract tip carrying a pass at an unadvanced position
  (the pre-FR4 split) is NOT fast-forwarded — a recorded round carries no stage identity, so
  no reader can distinguish that split from a normally advanced state whose history still
  holds the finished stage's rounds; a resume re-runs the recorded stage, a bounded one-time
  re-execution accepted for pre-contract history only, since contract-era writers cannot
  produce the split at all.
- FR10: every terminal transition with an external effect (host park, container park,
  completion finish, decision acknowledge, abort mark) SHALL follow one shared
  intent→effect→receipt protocol: durable intent before the effect, receipt after it, and
  recovery of an intent-without-receipt SHALL verify the effect at the target before
  re-driving it. The destructive step of any sequence (cleanup, label removal, box disposal)
  SHALL come after all constructive receipts.
- FR11: every factory-authored tracker comment SHALL carry a hidden content-identity marker
  (task and intent, never the bot account) and SHALL be written as find-then-upsert through
  one shared primitive; the eight existing marker kinds migrate onto it.
- FR12: tracker write sequences SHALL obey the sweep-universe rule: the label write that
  admits a task into the sweep universe comes first in its sequence, the label write that
  removes it from the universe comes last, and truth markers land in between — so every kill
  window freezes a state the sweeper's own query enumerates. Concretely: the working label
  before the claim comment; the abort, finish, and park markers before their label flips; a
  human decision appended to the branch before its acknowledge. The abort ordering trades a
  possible under-count for an over-count toward parking, which fails safe.
- FR13: each (re)claim SHALL be issued a monotonically increasing epoch, recorded with the
  claim, stamped into every commit and tracker write of that tenure; readers SHALL classify
  artifacts carrying an older epoch than the current claim as a distinct stale-epoch shape;
  a holder whose heartbeat cannot be confirmed SHALL stop writing at the next boundary until
  it re-verifies its claim.
- FR14: automatic recovery SHALL be budgeted: a persisted per-task counter of recovery
  attempts with backoff, and quarantine to the needs-human status with the failure history
  once exhausted; this budget and the existing crash fuse SHALL be one accounting (one
  counter model, one quarantine outcome), and quality attempts remain separate.
- FR15: the three non-recoverable shapes — `Corrupt`, `UnsupportedVersion`, and `Unknown` —
  SHALL quarantine on first classification with a diagnosis naming the offending file and the
  observed and expected shape (for `UnsupportedVersion`, the observed and supported versions),
  without burning crash-fuse cycles, on every reading path including take and serve.
- FR16: `status` (list and single-task) SHALL render every legal shape; `usage` SHALL skip an
  unreadable historical commit with a warning instead of failing the walk.
- FR17: while a kept box survives a park, the factory SHALL NOT commit to the task branch on
  the factory side; resuming an escalated container task SHALL dispose the kept box before
  the decision commit, so the next round's box sees the decision from its start.
- FR18: label-operation failures and HTTP-transport failures of the tracker SHALL be
  retryable under the same policy as tracker-unavailable failures wherever a bounded
  terminal-write retry exists.
- FR19: the tracker-side state of a task SHALL classify totally, in core, over
  adapter-reported facts (state labels present, claim footprint, boundary markers): every
  combination yields exactly one named tracker shape with exactly one recovery owner,
  `Foreign` included for out-of-protocol combinations (surfaced with a diagnosis, never
  auto-repaired); adapters report facts and never omit or judge a combination; and the
  reaper SHALL sweep the union of both listings (`listReady` and `listOpen`) and repair
  every non-steady shape — after a grace period rolling an incomplete claim back to ready,
  and completing the label flip a newer boundary marker implies.

### Non-Functional — Performance

- NFR-P1: the kill-point harness (M1) SHALL fit a measured runtime budget compatible with
  the default `check` lane — target: no more than ~5 minutes added to `check`; exceeding
  the budget routes the harness to a dedicated CI lane per Q4. No other
  performance-sensitive path is introduced: the classifier adds one read of a tip already
  being read and no extra subprocess calls.

### Non-Functional — Reliability

- NFR-R1: recovery of every shape is idempotent: running it on an already-recovered state
  changes nothing, and a kill during recovery lands in a shape whose recovery completes the
  work.
- NFR-R2: the classifier itself never throws on content: only environment unavailability
  (git or daemon unreachable) may surface as an infrastructure error, and it retries under
  existing policy without burning quality attempts.
- NFR-R3: no automatic path force-pushes or rewrites origin history; the only non-fast-forward
  write is the FR8 CAS reset of the local ref, and it never touches origin history.

### Non-Functional — Observability

- NFR-O1: every non-trivial repair (any classified shape other than the clean expected one)
  emits one structured log line naming the shape, task, epoch, and action taken; repeated
  repair of the same task is itself surfaced as a warning — "repeated" is judged against
  the task's persisted recovery-attempt accounting (FR14), not a separate clock: a repair
  arriving while the counter already records a prior one raises the warning.
- NFR-O2: a quarantine report names the shape, the diagnosis, and the recovery attempts
  consumed — readable without factory logs.

### Non-Functional — Security

- NFR-S1: comment markers and epoch stamps carry only task identity and counters — no paths,
  no hostnames, no credential material; scrubbing rules of existing report funnels stay in
  force.

### Non-Functional — Cost

- NFR-C1: no recovery path re-invokes a paid executor or judge for work whose passing verdict
  is recorded on a contract-era tip (the FR4 single-commit guarantee stated as cost); the
  pre-FR4 split FR9 excludes may re-run one recorded stage, once, on pre-contract tips only.

## Operator Experience Criteria

- UX1: "return the task and it continues" is true after any single crash, in both execution
  modes; no failure mode requires editing `.gnomish-task/` by hand or running git surgery.
- UX2: a quarantined task's tracker report explains what was found and what to do next; the
  operator never diagnoses a crash loop from repeated INFRA parks.
- UX3: duplicate tracker comments do not appear after crashes; a re-delivered report updates
  the existing comment in place.
- UX4: `gnomish status` over any real repository shows one row per task, whatever state each
  branch is in.

## Success Metrics

- M1: a kill-point harness enumerates every multi-step transition (host and container), kills
  after each durable step, runs the pickup, and asserts convergence to the expected shape —
  and runs each recovery twice asserting the second pass is a no-op; the matrix is a gate in
  `check`.
- M2: property-generated branch tips (arbitrary file subsets, versions, epochs) always
  classify to exactly one shape; no generated input throws.
- M3: the audit's concrete scenarios each have a green spec: first-round-killed resume (host
  and container), container park decision round-trip, Completed-without-cleanup finish,
  pass-persisted-state resume, diverged-branch automatic continuation, working-label-orphan
  reap, decision-before-ack ordering, status over a delivered+fresh+in-flight repository.
- M4: build green with mutation score per `.claude/rules/testing.md` in every touched module.
- M5: the ADR, the process-rule checklist, and the glossary entries exist and are
  cross-referenced from this change's design.

## Open Questions

- Q1: initial `state.json` content for FR3 — synthesized at the first stage of the frozen
  law: settle the exact envelope in design (resolved in design D2).
- Q2: epoch storage — claim comment id vs an explicit counter in the claim body: design
  decision (resolved in design D6).
- Q3: recovery-budget threshold and backoff defaults — start with the existing crash-fuse K
  and tune from operator experience.
- Q4: does the kill-point harness run in the default `check` or a nightly lane if wall-clock
  cost proves high? Decide from runtime measured against the NFR-P1 budget during implementation
  (resolved in implementation: ~7 s measured against a ~5 min budget, so the default `check` —
  recorded in ADR 0003).

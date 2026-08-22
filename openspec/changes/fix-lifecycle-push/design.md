# Design: fix-lifecycle-push

## Context

See proposal.md — Why. Lifecycle commits are written by two repositories (`GitTaskRepository` host-side over a worktree, `GitObjectsTaskRepository` sandbox-side over bare objects), neither of which pushes. Push mechanics today are spread over three classes with duplicated primitives (`originConfigured` × 3 + the `OriginRemoteUrl` variant, the refspec push command × 3), plus two ad-hoc caller-side lifecycle pushes in the container termination path. `RemoteAttemptDelivery` already implements the exact verify-then-push-with-retry protocol the tier-3 fence needs. The `gitobjects` module is hermetic by contract (`GIT_CONFIG_GLOBAL=/dev/null`, own runner, no factory imports) and must stay network-free. Mutating git commands against one clone serialize through a shared `GitProcessRunner` (design D8 of add-git-workflow) — any push implementation must keep using it.

Driven by FR1–FR6, NFR-R1/R2, NFR-S1 of proposal.md.

## Goals / Non-Goals

**Goals:**
- One code path owns each push primitive; the three tiers reuse it rather than adding fourth and fifth copies.
- The lifecycle-push rule lives in the adapter layer as a decorator, symmetric with the existing `PushBestEffortAttemptPersistence`.

**Non-Goals:**
- Unifying `GitExec` (gitobjects) with `GitProcessRunner` — their separation is a documented design decision (D19 of add-sandbox-core).
- Extracting the push core into a standalone module (see D5).

## Decisions

### D1: Lifecycle push is a decorator over the lifecycle store, not calls inside the repositories or at call sites

A `TaskRepository`/`TaskLifecycleStore` decorator delegates each write and then pushes best-effort, wired where the bare repositories are handed out: `GitTaskStore.taskRepository()` (host) and the container support bundle's `taskRepository()` (sandbox, which already holds the `BranchPush` seam it uses for attempt persistence). The branch name derives from the `taskId` argument per call. Because the push runs inside the decorated call, FR2's replicate-before-signal ordering falls out for free — callers that proceed to a tracker write do so after the push attempt, with zero application-layer changes.

The two caller-side pushes in the container termination path are deleted in the same task that wires the decorator (FR6) — otherwise the rule has two owners and terminal boundaries push twice.

**Alternatives**: push inside `GitTaskRepository`/`GitObjectsTaskRepository` themselves — mixes recording with replication and duplicates the rule in two classes; push at application call sites — scatters the rule over ~6 sites (the exact bug being fixed) and violates the push-monopoly rule (NFR-S1: push is adapter logic).

Port-shape note: `TaskLifecycleStore extends TaskRepository`; host hands out the wider type (with `confirmTerminalWrite`), sandbox the narrower. One decorator class per port keeps each file trivial and avoids a delegate-type cast; both share the push core, so no logic is duplicated — only delegation shims.

### D2: One shared push core in a `remote` subpackage of the git adapter

The duplicated primitives collapse into `adapter.git.remote`: an origin-presence/URL reader (absorbing `OriginRemoteUrl` and the three `originConfigured` copies), a refspec push (`push origin <branch>:<branch>`, never force), and a remote-tip reader (`ls-remote` + local-ancestry answer, extracted from `RemoteAttemptDelivery.deliveredPerRemoteTip`). `BestEffortPush`, `BranchPush`, and `RemoteAttemptDelivery` become policy holders over the core — their differing preconditions and logging stay put, their git commands come from one place. All core classes take the injected `GitProcessRunner`, preserving the per-clone mutation lock.

**Alternatives**: a standalone `gitpush` module mirroring `gitobjects` — rejected, see D5. Leaving the duplication and adding tier code alongside — rejected: tiers 2 and 3 would create a fourth push and a second `ls-remote`, the exact smell this change also fixes.

### D3: Tier 2 touchpoints take the local tip as a parameter

The reconciliation check (`origin tip vs local tip`, FR3) does not read the local tip itself: host callers supply it from their worktree-side reader, container callers from `GitObjects.resolveRef` — each mode's native reader, no third "read the branch tip" implementation. Touchpoints are resume start and the terminal boundary; both already hold the branch name, a clone path, and the runner. No timer, no daemon (NG2): the next instance to touch the task is the delivery vehicle, which is exactly the cross-machine healing FR3 wants.

### D4: The tier-3 fence reuses the attempt-delivery protocol

The fence (FR4) is the same verify → push → one bounded re-attempt → structured verdict sequence as `RemoteAttemptDelivery.ensureDelivered`, differing only in the commit checked (branch tip vs attempt commit) and the failure mapping (park-report note vs CannotVerify). Both sit on the shared core's remote-tip reader and push; the fence is a thin sibling, not a second implementation. Fence exhaustion degrades to tier-1 semantics (FR5): the park proceeds, the report notes the gap. Host mode only — container parks record no lifecycle commit (NG1, task-5.2 scope note of add-serve-sandbox-lifecycle stands; the escalation report reaches origin via the round's state-commit push).

### D5: No standalone push module; boundary recorded here

`gitobjects` earned its module through requirements push inverts: it neutralizes the operator's git config for determinism, while push *requires* that config (credential helpers, URL rewrites); it needs no clone lock, while push must serialize through the shared `GitProcessRunner`. A push module would need either a third subprocess runner (new duplication, lost lock) or a runner-interface inversion that reduces the "module" to three wrapped commands with one consumer. The boundary formula: **gitobjects = local bare objects, hermetic runner, no network; `adapter.git.remote` = network operations, factory runner, operator credentials**. Revisit the module split only if a second consumer outside the git adapter appears (e.g. a plugin needing push) or push transport grows real complexity. `gitobjects` gains no method in this change.

### D6: Testing strategy for the new pieces

Decorators are pure delegation-plus-push with no decision — per testing.md they get port-fake unit specs asserting push-after-each-write and one-push-per-`recordOutcome(Completed)` ordering. The precedent is `PushBestEffortAttemptPersistence`: fully covered by a port-fake unit spec (`PushBestEffortAttemptPersistenceSpec`) with no PIT exemption of any kind — the new decorators are expected to need none either; if a mutation-scope conflict still appears, resolve it within testing.md's existing categories rather than inventing a new one. Tier 1/2/3 end-to-end behavior lands on local bare-repo origins (`git init --bare`), asserting M1 (origin tip == local tip after Completed, both modes), M2 (park commit on origin before the tracker write — observable with an in-memory tracker fake that reads the bare origin at write time), and the fence-exhaustion note (UX2) with an unreachable-origin URL.

## Risks / Trade-offs

- [Double push at terminal boundaries during the transition] → FR6 removes the caller-side termination pushes in the same task that wires the decorator; M3's grep gate catches a leftover.
- [Fence adds latency to every host park] → one `ls-remote` when origin is current, bounded attempts when not (NFR-R2); parks are rare, human-latency events.
- [Tier-2 check on a slow origin delays resume start] → the check never blocks on failure (FR3) and shares the push's non-retrying discipline; worst case is one network timeout surfaced as WARN.
- [`push.default = matching` on operator machines keeps masking verification] → M1/M2 are asserted against bare-repo origins in tests, not manual inspection; the operator guide note calls out the masking effect.
- [Active change `fix-denial-attribution-durability` also carries a `git-task-persistence` delta and edits the host park exit (escalation write), the same region task 4.2 wires the fence into] → the two deltas touch disjoint requirements, so there is no spec collision; whichever change archives second rebases its delta onto the then-current stable spec, and the fence wiring is merged against that change's park-exit edits rather than around them.

## Open Questions

- Q1 (proposal): whether fresh-claim paths need their own tier-2 touchpoint or inherit resume-start coverage — resolvable during implementation by tracing which claim paths bypass resume bootstrap; default is resume-start only.

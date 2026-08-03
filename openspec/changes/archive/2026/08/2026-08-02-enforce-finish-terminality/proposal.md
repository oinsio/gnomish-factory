# Proposal: enforce-finish-terminality

## Why

The factory's task lifecycle is meant to be one-way: a task moves from Ready through Working to Finished and never back. Today that invariant is not enforced — if a human moves a Delivered/Finished task back to Ready in the tracker UI, the factory will happily re-claim it. Worse, the two tracker adapters disagree about what they see: the GitHub adapter derives the `returned` fact from any REPORT marker (including the finishing one), so a reopened finished task is treated as "returned" and *prioritized* ahead of fresh work, while the in-memory adapter counts only PARK entries and reports `returned = false` for the same history. The contract suite covers neither the finish-reopen case nor the divergence, so both adapters pass the same suite while behaving oppositely — exactly the class of bug the contract suite exists to prevent.

## What Changes

- **ADDED**: a `finished` (terminal) fact on the tracker port's ready-feed entries, derived by each adapter from the task's recorded history (a finish report marker), alongside the existing `returned` fact.
- **ADDED**: a decline protocol — when the factory observes a Ready task whose history says it was already finished, it refuses to work on it: posts an explanatory comment ("this task is already finished; open a new task or bug for further changes") and restores the terminal status, via a new tracker-port operation.
- **MODIFIED**: the `returned` fact derivation — a finish report no longer counts as "returned" in any adapter; only a park report or a stale-claim-removal marker does. This converges the GitHub and in-memory adapters.
- **MODIFIED** (**BREAKING**, wire format): the GitHub structural-marker vocabulary — the dual-use `report` kind is split into dedicated `park` and `finish` kinds and retired, so the park/finish distinction is structural rather than inferred from the presence of the `reason` field. Threads written by pre-split builds are not migrated (pre-production; no live threads must survive).
- **MODIFIED**: feed policy and explicit take — finished tasks are never claim candidates, in both the `serve`/bare-auto feed path and the explicit `gnomish take <ref>` path.
- **MODIFIED**: the tracker-port contract suite — new properties covering the finish-reopen case, run identically against every adapter.

## Capabilities

### New Capabilities

None — this change adds requirements to existing capabilities.

### Modified Capabilities

- `tracker-port`: ready-feed entries carry a `finished` fact; new decline-reopened operation; `returned` fact explicitly excludes finish reports; contract suite covers finish-reopen.
- `github-tracker`: split the `report` marker kind into `park` and `finish`; derive `finished` from the FINISH marker and `returned` from PARK/STALE_CLAIM_REMOVED; implement the decline operation (comment + label restore).
- `tracker-take`: explicit take of a finished-reopened task refuses with the decline protocol instead of claiming.
- `factory-serve` (delta on the in-flight `add-factory-serve` change): the feed cycle excludes finished tasks from claim candidates and declines them.

## Goals

- **G1**: The lifecycle is provably one-way — a task with a finish report in its history is never claimed again by any factory instance, regardless of its current tracker status.
- **G2**: Adapter parity on history-derived facts — GitHub and in-memory derive identical `returned` and `finished` facts from equivalent histories, enforced by the shared contract suite.
- **G3**: A human who reopens a finished task gets a clear, actionable response in the tracker within one poll cycle.

## Non-Goals

- **NG1**: No "reopen for rework" workflow. Rework is a new task or bug referencing the old one; the factory never resumes a finished task.
- **NG2**: No change to the park/escalation resume protocol — parked (AwaitingHuman) tasks returned to Ready remain fully resumable; that is the intended `returned = true` path.
- **NG3**: No prevention of the human action itself — the factory cannot stop a tracker UI relabel; it only responds to it.
- **NG4**: No Jira adapter work (not yet built).

## Users & Scenarios

- **U1** — Operator reopens a Delivered issue expecting the gnome to redo it. Within one poll cycle the factory posts a comment explaining the task is finished and that a new task/bug should be opened, and restores the Delivered status. The operator is not left waiting for work that will never start.
- **U2** — Factory instance polling the feed. A reopened finished task never enters its claim-candidate list, never consumes a WIP slot, and never burns claim attempts.
- **U3** — Operator runs `gnomish take <ref>` on a finished-reopened task explicitly. The CLI refuses with the same explanation instead of claiming.

## Requirements

### Functional

- **FR1**: A task whose recorded tracker history contains a finish report is *terminal*. Adapters MUST report this as a `finished` fact on ready-feed entries, derived from history alone (no adapter-local state), so any fresh instance reconstructs it.
- **FR2**: The `returned` fact MUST be true only for a park report or a stale-claim-removal marker in the task's history — never for a finish report. All adapters MUST agree on this derivation.
- **FR3**: Feed-based claiming (`serve` and bare auto `take`) MUST exclude `finished = true` entries from claim candidates: they are neither "returned" priority entries nor fresh entries, and never count toward or against the WIP limit.
- **FR4**: On observing a `finished = true` task in the Ready feed, the factory MUST decline it via a tracker-port operation that (a) restores the task's terminal status so it leaves the Ready feed, and (b) posts a human-readable comment stating the task is already finished and directing the human to open a new task or bug.
- **FR5**: Explicit take of a finished-reopened task MUST refuse with the same decline protocol and report a clear non-success outcome to the CLI caller.
- **FR6**: The tracker-port contract suite MUST cover the finish-reopen case (finish → human returns to Ready → `finished = true`, `returned = false`, decline restores terminal status) and MUST run identically against the in-memory and GitHub adapters.

### Non-Functional — Reliability

- **NFR-R1**: The decline operation MUST be idempotent at the state level: declining a task that is already terminal is a no-op — status unchanged and no comment posted — and concurrent declines by two instances MUST NOT corrupt state (at worst a duplicate comment, when both observe the task as non-terminal before either restores it).
- **NFR-R2**: Because restoring the terminal status removes the task from the Ready feed, steady state converges: a single reopen event produces a bounded number of decline comments (target: exactly one per reopen event per NFR-R1's race caveat).
- **NFR-R3**: All facts (`finished`, `returned`) are reconstructed from the tracker on every poll — consistent with the statelessness invariant; no instance-local memory of "already declined".

### Non-Functional — Performance

- **NFR-P1**: Deriving `finished` MUST NOT add GitHub API calls: it is computed from the same per-issue comments fetch (and conditional-request cache) the feed enrichment already pays for.

### Non-Functional — Observability

- **NFR-O1**: Every decline is logged (task ref, trigger) and visible in the tracker as the posted comment — an operator can reconstruct why a reopened task went back to Delivered from the tracker alone.

### Non-Functional — Security / Cost

- Considered: no new credentials, sandbox surfaces, or token spend (no model calls involved). No dedicated requirements.

## Operator Experience Criteria

- **UX1**: The decline comment is self-explanatory to a human who knows nothing about factory internals: it states the task was already completed, when/why nothing more will happen on it, and what to do instead (open a new task or bug). It does not read as an error or a crash.
- **UX2**: After a decline, the tracker shows the task in its terminal status again (e.g. the Delivered label on GitHub) — the board is not left lying about pending work.

## Success Metrics

- **M1**: The new contract-suite finish-reopen properties pass against both adapters — zero derivation divergence (`returned`/`finished`) between in-memory and GitHub on identical histories.
- **M2**: In the WireMock-backed adapter tests, a reopened Delivered issue is never claimed: no claim marker is posted, exactly one decline comment is posted, and the Delivered label is restored.
- **M3**: PIT mutation score for the changed production classes meets the project bar (100%, justified exceptions only).

## Open Questions

- **Q1** *(resolved — design D1)*: How should the GitHub adapter distinguish the finishing report from a park report? Resolved: dedicated `park` and `finish` marker kinds replace the dual-use `report` kind; discrimination by the presence of the `reason` field was rejected as an implicit load-bearing convention at the most failure-sensitive spot (the decline protocol autonomously closes tasks based on it).
- **Q2** *(resolved — design)*: Should the decline fire only for the Delivered-label round-trip or for any path back to Ready? Resolved: any Ready task with a finish report in its history is declined, regardless of how it got to Ready.

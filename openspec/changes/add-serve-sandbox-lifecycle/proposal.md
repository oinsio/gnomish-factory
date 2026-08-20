# add-serve-sandbox-lifecycle

## Why

Container mode currently reaches only `gnomish run`: `take` and `serve` still execute every stage on the host, because `add-sandbox-core` deliberately deferred their adoption (its task 4.8 precedent) together with the daemon half of environment cleanup. That deferral left a correctness time bomb in the startup orphan sweep: it decides "alive" from a **name snapshot** taken by a single owner before anything is created. The moment a daemon holds several in-flight tasks, a second instance shares the host, or a second project shares the Docker namespace, that assumption breaks — the sweep would remove live containers of concurrently launching tasks (TOCTOU), of a sibling instance, or of another project. This change completes the deferred serve/lifecycle pass: container `take`/`serve`, and a sweep whose liveness is derived from **ownership stamped on the objects themselves** plus the existing claim-heartbeat lease, safe under concurrency by construction.

## What Changes

- **ADDED**: a `sandbox-lifecycle` capability — ownership labels stamped atomically at object creation (task key, ownership mode, project identity), a claim-heartbeat liveness oracle, a sweep decision policy (ownership × role × state), an aged-environment reaper with keep semantics, and a unified sweep verdict vocabulary shared by all entry points.
- **MODIFIED**: `execution-environment` — the snapshot-based "orphan cleanup at startup" requirement is replaced by the ownership-based policy; the label set grows.
- **MODIFIED**: `tracker-take` and `factory-serve` — container-bound stages run through `take` and `serve` slots, reusing the already-built container run assembly; the daemon schedules the periodic sweep/reap tick and stops kept environments at task end.
- **MODIFIED**: `manual-run` — manual-mode ownership labels, oracle-free age policy, sweep summary in the finish report.
- **MODIFIED**: `serve-observability` and `dashboard-page` — sweep vitals with a kept-environment inventory, sweep action ledger lines, a sandbox hygiene dashboard section with alerts.
- **REMOVED**: the name-snapshot orphan sweep contract (`sweep(liveKeys)` semantics, "empty set removes every factory object").

## Goals

- G1: container-bound pipelines are fully usable through `gnomish take` and `gnomish serve`, including resume, salvage, keep, takeover, and `--discard-work`.
- G2: environment cleanup is safe under concurrency — parallel slot launches, multiple factory instances, multiple projects on one Docker host — with no ordering assumptions.
- G3: a crashed or hung instance's environments are reclaimed by any surviving instance within lease TTL plus one sweep tick, with salvageable work preserved (stop before dispose).
- G4: the operator can see, per sweep tick, what was cleaned, stopped, checked-and-untouched, and skipped-without-verdict.

## Non-Goals

- NG1: generalizing the ownership scheme to non-Docker object types — Colima VMs, GHA runs, provisioning snapshot images. Those changes adopt the principle by reference; this change covers the Docker container/volume/network/guard namespace only.
- NG2: any liveness registry beyond the tracker's claim heartbeat — no lock files, no instance registry, no second lease mechanism.
- NG3: changes to the claim-heartbeat protocol itself (beat cadence, TTL derivation, takeover, zombie fencing stay as specified in `claim-heartbeat`).
- NG4: writing snapshot or ledger files from one-shot `run`/`take` processes — the daemon remains the only ledger writer; unification stops at the verdict vocabulary.
- NG5: inbound HTTP or webhooks for observability; the dashboard stays file-based.

## Users & Scenarios

- U1: an operator runs `gnomish serve` with container-bound stages on a shared dev host; slots launch and finish tasks while the sweep tick runs — nothing live is ever touched.
- U2: the same operator debugs one task with container `gnomish run` beside the daemon; the manual environment is invisible to the oracle yet protected, and a forgotten manual zombie is still reclaimed eventually.
- U3: an escalation handler returns to a task after several days; the kept environment (stopped box, volume, network) is still there for resume — or, past the configured age, was reaped and resume falls back to a fresh materialize from the branch.
- U4: two projects each run a factory on one host; each project's sweep sees only its own objects.
- U5: an instance dies mid-task; a sibling instance's next sweep tick stops the abandoned running box, and the aged reaper later disposes it — no "next boot of the dead instance" required.

## Requirements

### Functional

- FR1: container-bound stages SHALL execute through `gnomish take` (single and batch) and `gnomish serve` slots, reusing the container run assembly built by `add-sandbox-core` — including tracker-driven resume, salvage on takeover/revocation, keep-on-non-completed-exit, and `--discard-work` — with host-mode behavior unchanged.
- FR2: every factory-created Docker object SHALL be stamped, atomically at creation, with labels carrying: factory ownership, the task environment key, the ownership mode (`tracked` | `manual`), and the project identity. An object without these labels never existed as a factory object; there is no post-creation labelling window.
- FR3: liveness of a `tracked` object SHALL be derived from the claim-heartbeat lease: the object is alive if and only if its task currently holds a claim whose heartbeat is fresh per the existing staleness policy (including its cross-tick observation memory). A stale claim counts as dead — consistent with the claim-heartbeat protocol, under which the stale claim is removed, the task returns to circulation, and takeover is licensed.
- FR4: the sweep SHALL decide per object by ownership × role × state: alive → untouched; unowned running main box → **stopped, never disposed** (volume, network, box preserved); unowned stopped box or container-less volume/network remnant → left to the aged reaper; unowned guard/judge/verification/seed-helper object → disposed immediately (reconstructible by construction); unowned factory object matching no known role → fail-safe fallback: stopped if running, then left to the aged reaper. An object younger than a configured minimum age SHALL never be touched regardless of verdict.
- FR5: the aged reaper SHALL dispose kept environments whose age exceeds a configured threshold (default 7 days): stopped boxes by runtime finished-at metadata, container-less remnants by creation timestamp. Escalation of an object is one-way: running → stopped → (past threshold) disposed.
- FR6: `gnomish serve` SHALL run the sweep and reaper on a periodic tick for the daemon's lifetime and SHALL stop-keep each task's environment at task end; `run` and `take` SHALL run one sweep pass at startup. All entry points SHALL evaluate the same policy through one shared component.
- FR7: `manual`-mode objects SHALL be governed by age alone — no oracle: an unowned running manual box is stopped only after a configured threshold (default 24 hours) since its runtime started-at; stopped manual environments follow the same aged-reaper policy as tracked ones.
- FR8: the sweep SHALL be scoped to its own project identity: objects labelled with a different project SHALL be invisible to listing and never touched.
- FR9: sweep evaluation SHALL emit a uniform verdict event per object — category (checked-alive, kept-under-threshold, stopped-orphan, disposed-aged, disposed-reconstructible, skipped-no-verdict), object name, role, ownership mode, task key, reason, age — through a listener seam; entry points differ only in where events sink.

### Non-Functional — Reliability

- NFR-R1: "no verdict" SHALL be fail-closed and distinct from "no claims": a tracker or Docker error during sweep skips the affected decisions (nothing is removed) and never degrades to an empty live set. A skipped sweep is retried on the next tick.
- NFR-R2: every sweep action SHALL be idempotent and crash-safe: re-running the sweep after a crash mid-action converges to the same end state; partial materialize/dispose residue is reclaimed by the same policy with no special casing.
- NFR-R3: the sweep SHALL never block startup or a slot: runtime or tracker unavailability degrades to a logged skip, mirroring the existing `DockerUnavailableException` handling.
- NFR-R4: stopping an unowned running box SHALL preserve salvage: a subsequent resume of that task salvages un-harvested work from the surviving volume exactly as after a keep.

### Non-Functional — Observability

- NFR-O1: the serve snapshot SHALL carry a sweeper vitals entry: last tick time, per-category counts of the last tick, and an inventory of kept environments (task, age, time until reap).
- NFR-O2: every stop and dispose action SHALL be a ledger line (object, role, task key, reason, age); each tick SHALL add one summary line with per-category counts; untouched objects are never itemized in the ledger.
- NFR-O3: the dashboard SHALL render a sandbox hygiene section (last tick breakdown, kept inventory, recent actions) and SHALL alert on: sweep not run for longer than a threshold, consecutive skipped-no-verdict ticks, and any stopped-orphan event.
- NFR-O4: `run`/`take` SHALL log the same verdict vocabulary via SLF4J; `take`'s finish report SHALL include a one-line sweep summary.

### Non-Functional — Security

- NFR-S1: labels SHALL carry no credentials or secrets — only the sanitized environment key, mode, and project identity.
- NFR-S2: an unowned running box SHALL be stopped (not disposed), preserving evidence for abort-investigation consistent with the existing keep semantics.

### Non-Functional — Cost

- NFR-C1: stopping unowned running boxes bounds runaway agent cost: a zombie stops burning tokens no later than lease TTL plus one sweep tick.
- NFR-C2: one sweep tick SHALL cost at most one tracker open-task listing (shareable with the claim reaper's existing listing) plus Docker listings — no per-object tracker calls.

### Non-Functional — Performance

- NFR-P1: the sweep tick SHALL run off the slot path; slot launch latency is unaffected by a concurrent tick.

## Operator Experience Criteria

- UX1: the dashboard answers, without log digging: what was cleaned, what was stopped, what was checked and left untouched, and whether the sweep is actually running or silently skipping.
- UX2: a stopped-orphan alert reads as "an instance died or hung" — object, task, and reason named — not as routine cleanup noise.
- UX3: the sandbox operator guide states the new boundary honestly: container mode is supported in `run`, `take`, and `serve`; VM/GHA/provisioning cleanup follows the ownership precedent in their own changes.
- UX4: all thresholds (minimum age, kept reap age, manual running-stop age, tick cadence) are configuration keys with documented defaults; no rebuild to tune.

## Success Metrics

- M1: an E2E kill of a container-mode daemon mid-task ends with the sibling instance stopping the box within TTL + one tick and salvaging the work on resume — no restart of the dead instance involved.
- M2: a race spec launching a slot concurrently with a sweep tick never loses the launching environment (label-at-creation + minimum age hold under interleaving).
- M3: coexistence specs pass: second instance, `run` beside `serve`, and second project on one Docker namespace — zero cross-touches.
- M4: sweep verdicts for every category of the decision matrix are asserted by unit specs on the shared component (module PIT gate at the standard 100% target).
- M5: the dashboard section renders all four breakdown groups (cleaned, stopped, checked-and-untouched, skipped-without-verdict, mapped over the six verdict categories) from a real snapshot + ledger fixture.

## Open Questions

- Q1: the default manual running-stop threshold — 24 h is proposed; is a longer default safer for genuinely long manual sessions, given a dead manual zombie only idles after finishing its current agent turn?
- Q2: project identity derivation — a stable digest of the origin remote URL vs an explicit configuration key with a derived default. Leaning derived-with-override; decide in design.
- Q3: should batch `take` on a daemon host eventually write sweep actions into the daemon ledger (multi-writer)? Out of scope now (NG4); revisit if authorship of stop events proves operationally important.

## Capabilities

### New Capabilities

- `sandbox-lifecycle`: ownership labels, the claim-heartbeat liveness oracle, the sweep decision policy, the aged reaper, minimum-age protection, project scoping, and the verdict vocabulary.

### Modified Capabilities

- `execution-environment`: "Orphan cleanup at startup" is replaced — cleanup is ownership-based per `sandbox-lifecycle`; the label contract grows mode and project identity.
- `tracker-take`: container-bound stages run through take; sweep summary in the finish report.
- `factory-serve`: container-bound stages run in slots; the daemon schedules the sweep/reap tick and stop-keeps environments at task end.
- `manual-run`: manual ownership mode and its oracle-free age policy.
- `serve-observability`: sweeper vitals with kept inventory; sweep ledger lines.
- `dashboard-page`: sandbox hygiene section and its alert conditions.

## Impact

- Modules: `sandbox/docker` (labels, sweeper rewrite, reaper), `application` (`app.serve` scheduling, take/serve container wiring, `serveobservability` vitals + ledger lines, `dashboard` section + alerts), `bootstrap` (assembly of the shared sweep component per entry point).
- Removed behavior: `ContainerOrphanSweeper.sweep(liveKeys)` name-snapshot semantics, including "empty live set removes everything".
- New configuration keys: minimum object age, kept reap age (default 7 d), manual running-stop age (default 24 h), sweep tick cadence.
- Depends on existing capabilities unchanged: `claim-heartbeat` (oracle, including its staleness observation memory), `git-task-persistence` (keep/salvage semantics), `serve-observability` writer machinery.
- Docs: `docs/guides/operator-guide-sandbox.md` boundary update (UX3), including removing its premature claim that the serve daemon already disposes aged kept environments; `docs/glossary.md` entries for the new lifecycle vocabulary.
- Coordination with other active changes: `add-sandbox-hardening` (FR15) and `add-sandbox-colima-vm` (FR11) describe their cleanup as a "mirror of change A orphan cleanup" — the requirement this change removes; once this change lands, those references must be repointed at `sandbox-lifecycle` (edits belong to those changes).
- Scope note: if implementation grows past the 1–4 week bound, the container take/serve adoption (FR1, tasks section 5) is the split point — it can land as a follow-up change depending on this one; the lifecycle policy itself must not be split.

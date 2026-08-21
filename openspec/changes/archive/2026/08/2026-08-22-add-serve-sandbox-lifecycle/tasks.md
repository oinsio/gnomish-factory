# Tasks — add-serve-sandbox-lifecycle

## 1. Ownership labels and project identity

- [x] 1.1 Extend the Docker label set with ownership mode (`tracked`|`manual`) and project identity, stamped in the same create commands as the existing labels; unit specs assert atomicity-by-construction (no code path creates an object without the full set) (FR2, NFR-S1)
- [x] 1.2 Project identity derivation: stable digest of the configured origin remote URL with `factory.sandbox.project-id` override, falling back to a digest of the clone's own canonical absolute path (never a shared constant) when the clone has no `origin`; the override is rejected unless it matches `[A-Za-z0-9._-]+`, since a comma or an equals sign would forge a second label pair in the `k1=v1,k2=v2` rendering. Spec covers derivation stability, override precedence, the origin-less fallback and the rejected override (FR8, design D5)
- [x] 1.3 Compatibility classification for project-labelled objects lacking the mode label (older builds): treated as `tracked` with age-only protection; spec covers the mixed-version host. Objects predating the project label fall outside the scoped listing by design (FR8) and are removed once by hand — operator-guide procedure, not sweep behavior (design: Migration)

## 2. Liveness oracle

- [x] 2.1 Open-task listing → live environment-key set, computed forward via the sanitizer, recomputed per evaluation; staleness judged by the existing claim-heartbeat policy (`StalenessMemory`, cross-tick observation memory reused as-is); on serve the listing is shared with the claim reaper's tick — at most one tracker listing per tick (FR3, NFR-C2, design D1)
- [x] 2.2 Fail-closed verdict source: listing error → no-verdict outcome distinct from an empty result; unit specs for error≠empty, plus design D4's outage geometries (NFR-R1, design D4) — `LivenessOracleSpec` carries a named feature per geometry rather than a `where:` table, since each drives a different multi-tick sequence of `Reaper`/`StalenessMemory` interactions and shares no arguments to tabulate. Rows 1 and 3 of D4's table ("tracker unreachable from the sweeper", "tracker unreachable from everyone") are one behavior at this seam — an instance's oracle sees only its own `listOpen`, so both yield `NoVerdict` from the same code path and are covered by the row-1 feature; row 3's longer duration changes the cost, not the verdict
- [x] 2.3 Stale-claim semantics aligned with takeover: spec asserts a stale-claim task's objects classify unowned exactly when the takeover license applies (FR3)

## 3. Sweep policy component

- [x] 3.1 Decision-matrix evaluator in `sandbox/docker`: ownership × role × state per the `sandbox-lifecycle` spec table, minimum-age guard, project scoping at listing; pure component with injected clock and verdict listener (FR4, FR8)
- [x] 3.2 Verdict event model: category, object, role, ownership mode, task key, reason, age; listener seam; data-driven Spock table covering every matrix row and category (FR9, M4)
- [x] 3.3 Actions: stop for unowned running main boxes (volume/network/box preserved), immediate dispose for unowned guard/judge/verification/seed-helper objects, fail-safe fallback for unrecognized roles (stop if running, aged reap) — role derived from labels and the container adapter's naming per the `execution-environment` delta, `-j`/`-v` keys resolving to the base task key, the anonymous labelled seed helper classified by its namelessness — idempotent by name; crash-rerun convergence spec (FR4, NFR-R2, NFR-S2)
- [x] 3.4 Aged reaper integration: finished-at age for stopped boxes, created-at for container-less remnants, 7-day default threshold key; one-way escalation asserted (running→stopped→disposed, never a shortcut) (FR5)
- [x] 3.5 Manual-mode branch: age-only policy, running-stop threshold (default 24 h, config key) from runtime started-at (FR7)
- [x] 3.6 Remove the name-snapshot sweeper contract (`sweep(liveKeys)`, empty-set-removes-all); migrate its call sites to the policy component (proposal: REMOVED) — `ContainerEnvironmentReaper.reapAged`, `ContainerOrphanSweeper` and `ContainerEnvironments.sweepOrphans()` are all gone, along with the `DockerCommands.list*Names()` listings that fed them; the one real call site (container `run`'s startup sweep, wired through `bootstrap.ContainerRunSupport`/`ContainerTerminalDrive`) now runs `SandboxLifecycleSweep` via `SandboxLifecyclePass` (task 4.4). `SandboxRunSupport.sweepOrphans()` keeps its name but carries the new contract

## 4. Entry-point wiring

- [x] 4.1 Serve: periodic sweep+reap tick on a virtual thread — immediate startup tick then configurable cadence, off the slot path, tick failure logged and retried (FR6, NFR-P1, NFR-R3, design D7)
- [x] 4.2 Serve: stop-keep at slot end via the existing reaper mechanics, closing deferred `add-sandbox-core` task 4.8 (FR6) — live since section 5: a serve slot routes through `TakeSlotRunner` → `TakeWorkRouter` → `TakeContainerEngineExecution`, whose Aborted/Escalated/Paused terminal boundaries call `SandboxRunSupport.keepStopped()` → `ContainerEnvironments.stopKeeping()`. The sweep tick (4.1) remains the safety net for a box abandoned without reaching a terminal boundary at all
- [x] 4.3 Take: startup sweep pass + structured SLF4J verdict sink + logged per-invocation summary line (FR6, NFR-O4)
- [x] 4.4 Run: startup pass with trackerless degradation (tracked objects of other tasks → skipped-no-verdict; manual policy needs no tracker); `mode=manual` labelling of run-created objects (FR2, FR6, FR7, NFR-R1)
- [x] 4.5 Narrow the worktree janitor to host worktrees only (delta spec), so no object has two cleaners (design D7)

## 5. Container take/serve adoption

- [x] 5.1 Take dispatch: container-bound stages route through the container assembly (fresh claim path) with `tracked` labelling; host mode untouched (FR1)
- [x] 5.2 Take resume/takeover/revocation in container mode: reattach or recreate-over-volume, salvage, decision collection, `--discard-work` (FR1, NFR-R4) — the full host routing table now applies in container mode, including the `Completed` → deferred-finish branch (a delivered-but-unfinished branch whose `.gnomish-task/` the `Completed` cleanup commit already stripped), closed by the unification in task 5.5
- [x] 5.3 Serve slots run the container take cycle concurrently; per-slot key isolation spec (FR1)
- [x] 5.4 Batch take parity: container dispositions in batch summaries and exit codes (FR1)
- [x] 5.5 Unify the take resume routing table across host and container (FR1, design D8): `ResumedBranch` as the common loaded-branch view, `ResumeMechanics<B>` as the seam for what differs (load, final state, marker clear, resume with/without decision), `TakeDispositionResume`/`TakeDecisionResume`/`TakeReconcile` generic and shared; `TakeContainerDispositionResume`, `TakeContainerDecisionResume` and `TakeContainerReconcile` deleted. Routing predicates get one contract spec pair over both mechanics instead of mirrored ones

## 6. Observability

- [x] 6.1 Sweeper vitals in the snapshot: last tick time, per-category counts, bounded kept inventory with age and time-to-reap (NFR-O1)
- [x] 6.2 Ledger: per-action lines (stop/dispose) + per-tick summary lines; no itemized untouched objects; rotation/retention unchanged (NFR-O2)
- [x] 6.3 Dashboard: sandbox hygiene section rendering vitals + ledger sweep history; independent degradation (NFR-O3, UX1)
- [x] 6.4 Dashboard alerts: tick overdue, consecutive skipped-no-verdict, tracked stopped-orphan-as-incident (manual age-stops excluded from the alert, present in the breakdown) (NFR-O3, UX2)

## 7. E2E and coexistence proofs

- [x] 7.1 E2E (Docker+Gitea-gated): container-mode take completes a tracker task end to end — claim, rounds in box, harvest, push, outcome, dispose (FR1, M1-adjacent)
- [x] 7.2 E2E: kill a container-mode holder mid-task; sweep tick stops the zombie box (never disposes it), resume salvages from the volume (M1, NFR-R4, NFR-C1) — `SandboxLifecycleZombieE2ESpec` starts from the oracle's OUTPUT (a `Live` verdict omitting the zombie's task) and proves the Docker half from there. The claim-staleness half — a sibling judging the claim stale after TTL and seizing it — is proven at its own seam by `LivenessOracleSpec` (task 2.x) and `TakeDeathAndRecoverySpecBase`, not re-run over real Docker here
- [x] 7.3 Launch-race spec: a just-launched environment is never lost to a sweep tick that has not observed its claim yet (M2) — `SandboxLifecycleLaunchRaceE2ESpec` proves the guard that makes the race safe (the real 2-minute minimum age), by running a real tick whose verdict omits the freshly materialized task; it does not interleave the two on separate threads, since the minimum-age guard is what decides the outcome at every interleaving
- [x] 7.4 Coexistence specs: second instance (same project identity, oracle as the cross-instance guard), `run` beside `serve`, second project identity on one namespace — zero cross-touches (M3, FR7, FR8)
- [x] 7.5 Dashboard fixture test: hygiene section renders all four categories from a real snapshot + ledger fixture (M5)

## 8. Docs and gates

- [x] 8.1 Operator guide: new boundary (container `run`/`take`/`serve` supported), threshold keys and defaults, label compatibility note, ownership precedent pointer for VM/GHA/provisioning changes; drop the guide's premature "serve daemon disposes aged kept environments" sentence in favor of the now-real behavior (UX3, UX4, NG1)
- [x] 8.2 Glossary: add `docs/glossary.md` entries for the new lifecycle vocabulary — sweep, sweep verdict, environment reaper (disambiguated from the claim reaper), kept environment, salvage, ownership mode (`tracked` | `manual`, disambiguated from "Ownership asymmetry"), project identity, remnant, minimum age, liveness oracle — with *Not:*/*Never:* lines where confusion is likely (FR9, process-invariants: no-jargon rule)
- [x] 8.3 `./gradlew check` green: Spock, JaCoCo, PIT per module policy, Error Prone/NullAway, Spotless (M4)
- [x] 8.4 Traceability grep: every FR/NFR/UX of add-serve-sandbox-lifecycle has an implementing entity in code or specs

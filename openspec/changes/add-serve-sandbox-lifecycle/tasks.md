# Tasks — add-serve-sandbox-lifecycle

## 1. Ownership labels and project identity

- [ ] 1.1 Extend the Docker label set with ownership mode (`tracked`|`manual`) and project identity, stamped in the same create commands as the existing labels; unit specs assert atomicity-by-construction (no code path creates an object without the full set) (FR2, NFR-S1)
- [ ] 1.2 Project identity derivation: stable digest of the configured origin remote URL with `factory.sandbox.project-id` override; spec covers derivation stability and override precedence (FR8, design D5)
- [ ] 1.3 Compatibility classification for objects lacking the new labels (older builds): treated as `tracked` with age-only protection; spec covers the mixed-version host (design: Migration)

## 2. Liveness oracle

- [ ] 2.1 Open-task listing → live environment-key set, computed forward via the sanitizer, recomputed per evaluation; staleness judged by the existing claim-heartbeat policy (`StalenessMemory`, cross-tick observation memory reused as-is); on serve the listing is shared with the claim reaper's tick — at most one tracker listing per tick (FR3, NFR-C2, design D1)
- [ ] 2.2 Fail-closed verdict source: listing error → no-verdict outcome distinct from an empty result; unit specs for error≠empty, plus the three outage geometries as data-driven cases (NFR-R1, design D4)
- [ ] 2.3 Stale-claim semantics aligned with takeover: spec asserts a stale-claim task's objects classify unowned exactly when the takeover license applies (FR3)

## 3. Sweep policy component

- [ ] 3.1 Decision-matrix evaluator in `sandbox/docker`: ownership × role × state per the `sandbox-lifecycle` spec table, minimum-age guard, project scoping at listing; pure component with injected clock and verdict listener (FR4, FR8)
- [ ] 3.2 Verdict event model: category, object, role, ownership mode, task key, reason, age; listener seam; data-driven Spock table covering every matrix row and category (FR9, M4)
- [ ] 3.3 Actions: stop for unowned running main boxes (volume/network/box preserved), immediate dispose for unowned guard/judge/verification/seed-helper objects, fail-safe fallback for unrecognized roles (stop if running, aged reap) — role derived from labels and the container adapter's naming per the `execution-environment` delta, `-j`/`-v` keys resolving to the base task key, the anonymous labelled seed helper classified by its namelessness — idempotent by name; crash-rerun convergence spec (FR4, NFR-R2, NFR-S2)
- [ ] 3.4 Aged reaper integration: finished-at age for stopped boxes, created-at for container-less remnants, 7-day default threshold key; one-way escalation asserted (running→stopped→disposed, never a shortcut) (FR5)
- [ ] 3.5 Manual-mode branch: age-only policy, running-stop threshold (default 24 h, config key) from runtime started-at (FR7)
- [ ] 3.6 Remove the name-snapshot sweeper contract (`sweep(liveKeys)`, empty-set-removes-all); migrate its call sites to the policy component (proposal: REMOVED)

## 4. Entry-point wiring

- [ ] 4.1 Serve: periodic sweep+reap tick on a virtual thread — immediate startup tick then configurable cadence, off the slot path, tick failure logged and retried (FR6, NFR-P1, NFR-R3, design D7)
- [ ] 4.2 Serve: stop-keep at slot end via the existing reaper mechanics, closing deferred `add-sandbox-core` task 4.8 (FR6)
- [ ] 4.3 Take: startup sweep pass + structured SLF4J verdict sink + finish-report summary line (FR6, NFR-O4)
- [ ] 4.4 Run: startup pass with trackerless degradation (tracked objects of other tasks → skipped-no-verdict; manual policy needs no tracker); `mode=manual` labelling of run-created objects (FR2, FR6, FR7, NFR-R1)
- [ ] 4.5 Narrow the worktree janitor to host worktrees only (delta spec), so no object has two cleaners (design D7)

## 5. Container take/serve adoption

- [ ] 5.1 Take dispatch: container-bound stages route through the container assembly (fresh claim path) with `tracked` labelling; host mode untouched (FR1)
- [ ] 5.2 Take resume/takeover/revocation in container mode: reattach or recreate-over-volume, salvage, decision collection, `--discard-work` (FR1, NFR-R4)
- [ ] 5.3 Serve slots run the container take cycle concurrently; per-slot key isolation spec (FR1)
- [ ] 5.4 Batch take parity: container dispositions in batch summaries and exit codes (FR1)

## 6. Observability

- [ ] 6.1 Sweeper vitals in the snapshot: last tick time, per-category counts, bounded kept inventory with age and time-to-reap (NFR-O1)
- [ ] 6.2 Ledger: per-action lines (stop/dispose) + per-tick summary lines; no itemized untouched objects; rotation/retention unchanged (NFR-O2)
- [ ] 6.3 Dashboard: sandbox hygiene section rendering vitals + ledger sweep history; independent degradation (NFR-O3, UX1)
- [ ] 6.4 Dashboard alerts: tick overdue, consecutive skipped-no-verdict, tracked stopped-orphan-as-incident (manual age-stops excluded from the alert, present in the breakdown) (NFR-O3, UX2)

## 7. E2E and coexistence proofs

- [ ] 7.1 E2E (Docker+Gitea-gated): container-mode take completes a tracker task end to end — claim, rounds in box, harvest, push, outcome, dispose (FR1, M1-adjacent)
- [ ] 7.2 E2E: kill a container-mode holder mid-task; sibling seizes after TTL, sweep tick stops the zombie box, resume salvages from the volume (M1, NFR-R4, NFR-C1)
- [ ] 7.3 Race spec: slot launch concurrent with a sweep tick never loses the launching environment (M2)
- [ ] 7.4 Coexistence specs: second instance, `run` beside `serve`, second project identity on one namespace — zero cross-touches (M3, FR7, FR8)
- [ ] 7.5 Dashboard fixture test: hygiene section renders all four categories from a real snapshot + ledger fixture (M5)

## 8. Docs and gates

- [ ] 8.1 Operator guide: new boundary (container `run`/`take`/`serve` supported), threshold keys and defaults, label compatibility note, ownership precedent pointer for VM/GHA/provisioning changes; drop the guide's premature "serve daemon disposes aged kept environments" sentence in favor of the now-real behavior (UX3, UX4, NG1)
- [ ] 8.2 Glossary: add `docs/glossary.md` entries for the new lifecycle vocabulary — sweep, sweep verdict, environment reaper (disambiguated from the claim reaper), kept environment, salvage, ownership mode (`tracked` | `manual`, disambiguated from "Ownership asymmetry"), project identity, remnant, minimum age, liveness oracle — with *Not:*/*Never:* lines where confusion is likely (FR9, process-invariants: no-jargon rule)
- [ ] 8.3 `./gradlew check` green: Spock, JaCoCo, PIT per module policy, Error Prone/NullAway, Spotless (M4)
- [ ] 8.4 Traceability grep: every FR/NFR/UX of add-serve-sandbox-lifecycle has an implementing entity in code or specs

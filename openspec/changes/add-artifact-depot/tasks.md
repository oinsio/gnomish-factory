# Tasks: add-artifact-depot

Order follows the migration plan (design): deployment and the
coordinated config switch first, then serve-time policy, private
upstreams, the journal, and the image path last. Requires change A
(`add-sandbox-core`) implemented; strengthens B/C/D where present.
Depot-touching specs are gated on a local depot instance (compose),
the same pattern as Docker-gated specs.

## 1. Spikes and re-verification

- [ ] 1.1 Product re-verification against the #31 maturity criteria: Nexus edition/licensing state, cooldown/quarantine feature fit, admin-plane separation; fallback per-ecosystem set if needed (Q1, D1, NFR-S1)
- [ ] 1.2 Cooldown defaults per ecosystem and lockfile-pinned policy question; record decisions (Q2, D3)

## 2. Deployment and the config switch

- [ ] 2.1 Compose recipe: depot service with proxy repositories for all FR1 ecosystems (incl. apt and raw/generic), cache volume, resolution endpoint on the box route, admin plane outside it; admin credentials via `SecretsProvider` (FR1, FR11, D8)
- [ ] 2.2 Coordinated factory-config switch: allowlist collapse (gateway + depot) together with baked registry parameters; no half-enabled state (FR2, FR8, D2, UX1)
- [ ] 2.3 Self-check probes: depot reachable, direct registry denied; failure = infrastructure failure (FR2, D2)
- [ ] 2.4 Direct-registry denials into the findings funnel, distinguishable from outages (FR2, NFR-O1, UX3)
- [ ] 2.5 E2E: build resolves Maven/Gradle + npm through the depot with direct registries blocked; rewritten build config dies at the guard with a finding (M1)

## 3. Serve-time policy

- [ ] 3.1 Cooldown enforcement at serve time, per-ecosystem windows, distinct refusal (artifact, age, policy); operator exceptions (FR3, D3, UX2)
- [ ] 3.2 Quarantine list + vulnerability-block data aligned with the CI OSV gate; block refusals distinguishable from cooldown (FR4, Q4, D3)
- [ ] 3.3 Specs: serve-time semantics incl. policy-change-covers-cache, refusal taxonomy (M2, NFR-R2)

## 4. Private upstreams

- [ ] 4.1 Upstream-addition flow docs: repo declares in `.gnomish/`, operator adds at the depot with the same policy (FR7, D5, UX4)
- [ ] 4.2 Private upstream credentials via `SecretsProvider` into depot config only; provisioning network model identical to task phase (FR6, D5)
- [ ] 4.3 E2E assertion: no registry credential observable in box env, filesystem, or provisioning phase (M3)

## 5. Journal and anomaly signal

- [ ] 5.1 Per-task download journal derived from guard access logs, depot logs as supplement; attach to task report via the findings funnel (FR5, D4)
- [ ] 5.2 Project baseline accumulation and anomaly flagging (flag, not enforce); cold-start behavior documented (FR5, D4, Q3)
- [ ] 5.3 E2E: journal present in the report; never-seen artifact flagged (M4)

## 6. Image path and closing

- [ ] 6.1 Docker image pulls via the depot's registry proxy: container-adapter daemon mirror parameter and change-C VM mirror point at it where deployed (FR9, D6, Q5)
- [ ] 6.2 E2E: sandbox workload image pull resolves through the depot with direct registry egress blocked (M5)
- [ ] 6.3 Contract test: depot outage = infrastructure failure, no attempt burned, no direct fallback (FR10, M6, D7)
- [ ] 6.4 Cache storage bounds: cleanup policies configured and documented (NFR-C1, D7)
- [ ] 6.5 Docs: enabling/rollback (one switch), cooldown tuning, exception process, GHA-bypass note, per-site placement guidance (UX1, UX2, UX4, Q5)

# Tasks: add-sandbox-hardening

Order follows the migration plan (design): gateway first (no TLS
complexity), provisioning second (independent), interception third, E2E
and docs last. Every layer lands behind its own default-off switch.
Requires change A (`add-sandbox-core`) implemented.

## 1. Spikes and tool re-verification

- [ ] 1.1 Re-verify gateway product choice against the #31 maturity criteria (LiteLLM vs Bifrost: fail-closed, no default creds, Anthropic wire protocol, virtual-key budgets) (Q1, NFR-S3, D2)
- [ ] 1.2 Matchlock spike: could it replace self-built interception? Record verdict before starting group 5 (Q2, D4)

## 2. Gateway and virtual keys

- [ ] 2.1 Factory config surface: gateway settings + the four independent switches with dependency validation (tool policy⇒gateway, L7⇒interception), defaults off (D9, UX1)
- [ ] 2.2 Gateway client adapter: issue/revoke virtual keys with budget, expiry, model restriction; master key via `SecretsProvider` (FR1, FR2)
- [ ] 2.3 Wire key lifecycle to segment lifecycle: issue at segment start (budget = remaining task budget), revoke at segment end / completion / escalation (FR2, NFR-C1, D3)
- [ ] 2.4 Budget-failure classification: distinct "budget exceeded: spent X of Y" escalation, no stage attempt burned (FR3, UX2)
- [ ] 2.5 Per-key rate limit configuration on the gateway (FR4)
- [ ] 2.6 Environment wiring: box env gets gateway base URL + virtual key through the existing seam; AI-provider hosts leave the box allowlist when the gateway is on (FR1, D1)
- [ ] 2.7 Verdict-independent findings slot in the report model: a denial/observability findings field carried into state.json/status.json independently of any check `Verdict`, so a passing round can still surface findings without flipping its outcome (parallel to core task 8.4a's `PollStatus.Pass` change); wire change A's captured base allowlist denials (`EgressGuard.denialFindings()`) through the `TaskExecutionEnvironment` port into this slot, discharging add-sandbox-core Q6; the prerequisite for every findings-attachment task below (2.8, 3.2, 5.2, 5.3) (NFR-O1)
- [ ] 2.8 Cost ledger: read per-segment spend, attach to task report via the findings slot (2.7), flag anomalies via config multiplier baseline (FR6, NFR-O1, D10)
- [ ] 2.9 Fail-closed paths: gateway unreachable / issuance failure = infrastructure failure, never a real-key fallback (NFR-R1)
- [ ] 2.10 Specs (WireMock as gateway): issuance/revocation, budget failure surfacing, ledger read, base-denial finding reaches the report on a passing round, "no real provider key in box env" assertion (M2)

## 3. Tool policy and multi-provider

- [ ] 3.1 Stage model + loader: `Mechanism` model/provider declaration and server-side tool allowance, typed into `PipelineDefinition`; loader specs (FR5, FR7)
- [ ] 3.2 Server-side tool stripping policy at the gateway; each removal recorded and attached as a finding (FR5, NFR-O1)
- [ ] 3.3 Multi-provider routing: key restricted to the stage-declared model, protocol translation config; spike cross-provider judge quality and record recommendation (FR7, Q3, NG4)
- [ ] 3.4 Contract test: request with disallowed server tool reaches provider mock with the tool absent (M4)

## 4. Provisioning and snapshot cache

- [ ] 4.1 Recognize `.gnomish/setup.sh` as law surface: read from the factory law clone, loading executes nothing (FR12)
- [ ] 4.2 Provisioning flow: one-shot container from the base image with working copy materialized from the law clone, egress through the guard, gnome never enters (FR12, D6)
- [ ] 4.3 Snapshot commit: fingerprint naming (`sha256(setup.sh)+base digest`), working copy and secret material removed before commit (FR13, FR16, D7)
- [ ] 4.4 Image resolution in the container adapter: valid snapshot first (name + TTL `factory.sandbox.snapshot-max-age`), else operator image; rebuild triggers (fingerprint, TTL, `gnomish env rebuild` / `--rebuild-env`); provisioning failure = infrastructure failure (FR13, D8)
- [ ] 4.5 Snapshot lifecycle: factory labels, superseded-image cleanup after successful build, startup orphan sweep, per-fingerprint provisioning lock (FR15, NFR-R2)
- [ ] 4.6 Specs (Docker-gated): snapshot reuse, rebuild-exactly-once on content change, crash-safe cleanup, setup-secret hygiene incl. image history (M3, M6)

## 5. Interception, credential policy, L7

- [ ] 5.1 Guard interception mode switch on the baked CA; per-host passthrough exceptions; unbuffered streaming settings (FR8)
- [ ] 5.2 Credential policy at the guard: strip non-factory auth headers on policy hosts, optional injection with in-box sentinel; stripped headers → findings; credentials never logged (FR9, NFR-S2)
- [ ] 5.3 Per-host L7 rules (paths, methods) from operator config; violations recorded like denials (FR10)
- [ ] 5.4 Extended self-check probes per enabled layer: gateway/key valid, interception active, foreign header does not survive, disallowed tool stripped; failure = infrastructure failure (FR11, D5)
- [ ] 5.5 Specs: guard policy behavior (local guard + echo upstream), self-check verdict matrix per switch combination (M5)

## 6. E2E and docs

- [ ] 6.1 E2E: foreign key attached in box arrives at upstream mock as factory credential only, with finding (M1)
- [ ] 6.2 E2E: budget exhaustion stops the task with the budget escalation; ledger spend matches report (M2)
- [ ] 6.3 E2E: second task starts from snapshot without setup.sh re-run; changed script rebuilds once (M3)
- [ ] 6.4 E2E: with stripping artificially disabled, extended self-check refuses task start (M5)
- [ ] 6.5 E2E: provisioning secret unobservable in gnome phase (env, filesystem, image layers) (M6)
- [ ] 6.6 Docs: gateway compose recipe, certificate-pinning passthrough list, setup.sh pinning discipline, switch matrix and safe-enable order (UX1, UX3, UX4)

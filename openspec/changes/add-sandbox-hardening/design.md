# Design: add-sandbox-hardening

## Context

Driven by FR1–FR16, NFR-S1–S3 of the proposal. Change A left deliberate
seams for this change: the factory CA baked into images, mitmdump as the
guard (interception is a mode switch), the `ANTHROPIC_BASE_URL`/
`ANTHROPIC_AUTH_TOKEN` env seam, the `SecretsProvider` port, and the
snapshot-free environment port. Threat registry items to close: #11, #15
(L7), #16, #17, #31, #45. Explore sessions already
resolved the tool landscape; decisions below fix the remaining structure.

## Goals / Non-Goals

Design goals: close the "allowed host is an open door" class without
touching the environment port; make every hardening layer an independent,
fail-closed, operator-owned switch. Non-goals: artifact depot, Vault/OIDC
adapters, non-Docker adapters (changes C/D/E), content guardrails.

## Decisions

### D1. AI channel policy lives at the gateway, not in TLS interception
The gateway is the *protocol endpoint* for the box: the gnome speaks to it
in plaintext-inside-TLS terminated at the gateway, so credential policy,
model restriction, server-side tool stripping (#45), budgets, and
protocol translation all happen without MITM machinery. Once the gateway
is enabled, direct AI-provider hosts leave the box allowlist entirely —
the box can only reach the gateway, which closes the foreign-account
door (#16) for the AI channel by construction. TLS interception (D4)
remains for the *remaining* allowlisted hosts (registries, git host if
present). Alternative rejected: doing auth-header injection for the AI
channel at the intercepting guard — duplicates the gateway's job with
strictly worse observability and no budget ledger.
<!-- implements FR1, FR5, FR7 of add-sandbox-hardening -->

### D2. Gateway product: LiteLLM as default, selection re-verified at build
LiteLLM proxy is the default: virtual keys with budget/expiry/model
restriction are its native scenario, Claude CLI integration is
documented, and it accepts the Anthropic wire protocol (hard requirement
— the gnome is a Claude CLI). Selection criteria from threat #31 apply:
fail-closed on error, no default admin credentials, active maintenance.
The master key and provider keys reach gateway config via the
`SecretsProvider` port. Alternatives: Bifrost (lighter, younger — re-check
at implementation start, Q1); header-injection-only without a gateway
(no budget ledger, needs interception on the AI path — rejected as
primary); OmniRoute-class routers (fail-open, budget-less — rejected,
#31); cloud gateways (third party sees traffic — rejected on principle).
<!-- implements FR1, NFR-S3 of add-sandbox-hardening -->

### D3. Virtual key per environment segment, budget from the task budget
One virtual key per stage-segment environment (not per task): the key's
model restriction then matches the segment's stage-declared models
exactly, and key lifetime matches environment lifetime — segment switch
(harvest → dispose → materialize, change A) also rotates the credential.
The key's budget ceiling = the task budget remaining at issuance, so the
task-level cap holds across segments with no second bookkeeping
mechanism. Revocation on segment end, task completion, and escalation.
Budget exhaustion maps to a *budget failure*: surfaced like an
escalation with "spent X of Y", no stage attempt burned as quality.
Alternative rejected: one key per task — cannot express per-stage model
restriction, and a leaked key stays valid for the whole task.
<!-- implements FR2, FR3, NFR-C1 of add-sandbox-hardening -->

### D4. TLS interception is a guard mode switch with narrow scope
mitmproxy flips from SNI/CONNECT passthrough to interception using the
CA already baked by change A — no image rebuild, no tool swap.
Interception applies to allowlisted non-gateway hosts; per-host
passthrough exceptions cover certificate-pinned tools (documented, UX3).
In interception mode the guard enforces L7 rules (path prefixes +
methods per host, guard-native config — no DSL, Q4 resolved) and strips
any `Authorization`-class header not issued by the factory; optional
header injection lets a host credential live only at the guard
(sentinel in the box). Streaming responses are passed through unbuffered
(`stream_large_bodies`-class settings). Logs carry metadata only —
bodies and credentials never (NFR-S2). Alternative rejected: Matchlock
as the interception layer (Q2 spike) — one-shot-shaped, young; the port
keeps it available as a future adapter, not a guard replacement.
<!-- implements FR8, FR9, FR10 of add-sandbox-hardening -->

### D5. Self-check extensions mirror enabled modes
The change A self-check gains mode-conditional probes run from inside
the box before the first round: gateway reachable and virtual key valid;
with interception on — a foreign auth header on a probe request must not
reach an upstream echo, and interception must actually be active (probe
sees the factory CA in the chain); with tool policy on — a probe request
declaring a server-side web tool arrives at the gateway mock with the
tool stripped. Any probe failure = infrastructure failure, task does not
start. This is the same fail-closed pattern as change A D5 — silent
degradation of stripping is exactly the "allowlist silently not
enforced" incident class.
<!-- implements FR11 of add-sandbox-hardening -->

### D6. setup.sh comes from the factory's law clone, runs only in provisioning
`.gnomish/setup.sh` is pipeline law: read from the factory clone of the
default branch (never from a gnome-writable copy — change A NFR-S2),
executed inside a one-shot provisioning container created from the base
image with the working copy materialized. The gnome never runs in this
container; secrets that provisioning may need (none by default) are
scoped to it and the working copy is removed before snapshotting, so the
snapshot holds toolchain state only (FR16). Alternatives rejected:
repo-Dockerfile ownership (build/cache burden, precedent: Codex/Jules/
Copilot all use setup scripts); running setup.sh on task start every
time (minutes per task vs seconds from snapshot).
<!-- implements FR12, FR16 of add-sandbox-hardening -->

### D7. Snapshot cache: fingerprint naming, TTL, labels — no bookkeeping
Snapshot image name = `<project>-<sha256(setup.sh) + base-image digest>`;
existence check is `docker image inspect` — content change means a new
name, so invalidation needs no tracking code. TTL from Docker's own
`.Created` metadata (`factory.sandbox.snapshot-max-age`, default 7d)
guards against unpinned-version drift; the manual path is
`gnomish env rebuild` (or `--rebuild-env` on a run), with `docker rmi`
of the labeled snapshot as the factory-less emergency fallback.
`docker commit` exists *only* in the provisioning flow; the environment
port stays snapshot-free (change A invariant), so a gnome-touched box
can never persist. Snapshots carry factory labels; after a successful
build, superseded snapshots of the project are removed and startup prune
reclaims orphans (mirrors change A cleanup). Concurrent provisioning of
one fingerprint is serialized by a factory-side lock; losers reuse the
winner's image.
<!-- implements FR13, FR14, FR15, NFR-R2 of add-sandbox-hardening -->

### D8. Image resolution order: snapshot, else operator image
Materialization resolves the environment image as: valid project
snapshot (name match + TTL) → operator-configured base image (change A
behavior). A repo without setup.sh keeps change A semantics untouched.
Provisioning failure is an infrastructure failure of the task that
triggered it — never a silent fallback to an image missing the
project toolchain, and never a retry storm (existing Resilience4j
policies apply).
<!-- implements FR13, NFR-R1 of add-sandbox-hardening -->

### D9. Every layer is an independent operator switch, default off
Four independent factory-config switches: gateway, interception, L7
rules, tool policy (tool policy requires gateway; L7 requires
interception; validation enforces the dependency). All default off =
exact change A behavior, so this change is adoptable incrementally and
rollback of any layer is a config flip. Fail-closed applies per enabled
layer: an enabled layer that cannot start its machinery refuses the
task rather than degrading.
<!-- implements G5, UX1 of add-sandbox-hardening -->

### D10. Spend anomaly detection is reporting, not enforcement
The gateway ledger is the authoritative per-task cost record (FR6);
the factory compares segment spend against a stage-typical baseline and
flags ≥10× outliers as findings in the task report. No automatic kill
beyond the budget ceiling itself — the ceiling is the enforcement,
anomaly flags are operator signal (volume-based exfiltration through the
AI channel cannot be distinguished from "thinking hard" mechanically;
account pinning is the real defense).
<!-- implements FR6, NFR-O1 of add-sandbox-hardening -->

## Risks / Trade-offs

- [Gateway is a new always-on stateful service (LiteLLM wants Postgres)]
  → it is optional (D9); docs ship a compose recipe; Bifrost re-check at
  build may lighten this (Q1).
- [Virtual key is still a secret while alive] → per-segment lifetime,
  budget ceiling, revocation; header-injection variant removes even that.
- [Certificate pinning breaks under interception] → per-host passthrough
  exceptions, documented list, self-check catches surprises.
- [Protocol translation quality for cross-provider judges unknown] →
  spike (Q3); transport works, grading quality gated before recommending.
- [Provisioning adds a first-task latency spike per project] → paid once
  per fingerprint; snapshot reuse afterwards (NFR-P1).
- [Unpinned setup.sh versions rot the snapshot silently] → TTL backstop +
  docs discipline (UX4).
- [Gateway sees all gnome↔model traffic] → it is factory-owned and local
  by requirement (NFR-S1); never a hosted service.

## Migration Plan

1. Gateway + virtual keys + tool policy + cost ledger (biggest win, no
   TLS complexity); AI hosts drop off the box allowlist.
2. Provisioning surface + snapshot cache (independent of 1).
3. Interception mode + header stripping/injection + L7 rules + extended
   self-check.
4. E2E hardening pass (M1–M6); docs (pinning exceptions, compose recipe,
   setup.sh discipline).
   Each step ships behind its own default-off switch; rollback = flip
   the switch back (D9).

## Open Questions

- Q1/Q2 (proposal): gateway product state and Matchlock spike — resolve
  at implementation start.
- Q3 (proposal): cross-provider judge quality spike — gates only the
  *recommendation*, not the mechanism.
- Baseline source for D10 anomaly flags (config constant vs learned from
  history) — resolve during tasks; start with a config multiplier.

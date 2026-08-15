# Design: add-artifact-depot

## Context

Driven by FR1–FR11, NFR-S1/S2, NFR-R2 of the proposal. Change A left
the seam deliberately: registry endpoints in baked configs are
parameters, the guard allowlist is operator config, and the explore
sessions (2026-07-31) settled the depot's role — the gnome talks only
to the depot, upstream traffic is the depot's own, enforcement belongs
to the guard, and the "repo asks, operator allows" ownership asymmetry
applies to upstreams exactly as it does to allowlist entries.

## Goals / Non-Goals

Design goals: make the depot a policy point, not just a cache —
cooldown, quarantine, and journaling are the reasons it exists; keep
the whole thing one config switch away from change-A/B behavior.
Non-goals: publish repositories, CVE scanning, GHA reachability
(proposal NG1–NG3).

## Decisions

### D1. One depot service, Nexus-class default, per-ecosystem fallback
Default: Sonatype Nexus — the one OSS-available product covering
Maven/Gradle, npm, PyPI, Docker, apt, and raw/generic in a single
service (Artifactory OSS lacks npm — a Pro feature). Proxying apt is
what lets provisioning need no wider network than the task phase (D5):
OS-package installs in `setup.sh` resolve through the depot like
everything else. The threat-#31 maturity
criteria apply to the depot exactly as to the gateway: it holds
upstream credentials and sits on every build's path, so no default
admin credentials in operation, fail-closed error behavior, active
maintenance; admin secrets come via `SecretsProvider`. Fallback
documented: per-ecosystem lightweights (verdaccio for npm, devpi for
PyPI, `registry:2` for Docker) if the Nexus edition/licensing picture
degrades (Q1 re-verifies at build). Alternative rejected: building
cooldown/journaling into the guard (mitmproxy scripts) — the guard is
deliberately a thin policy point; caching, ecosystem-aware metadata,
and upstream auth are a product, not a script.
<!-- implements FR1, NFR-S1 of add-artifact-depot -->

### D2. Enforcement at the guard; depot configs are convenience
The security property "the gnome cannot reach upstream" comes from the
guard's allowlist no longer containing registry hosts — a rewritten
`build.gradle` or `.npmrc` pointing upstream dies at the guard with a
recorded denial (threat #40's kill: the attacker never chooses what
the depot requests upstream and never reads its logs, NFR-S2). Baked
configs pointing at the depot (via the change-A parameters) exist for
builds to *work*, not to protect anything. The two must switch
together — a coordinated factory-config change (UX1) — and the
self-check proves the resulting state (depot reachable, direct
registry denied) before any round. Alternative rejected: relying on
baked configs as the control (any build script overrides them — that
is convention, exactly what layer 2 exists to not trust).
<!-- implements FR2, FR8 of add-artifact-depot -->

### D3. Cooldown at serve time, distinct refusal, operator exceptions
The cooldown window (per-ecosystem, operator config) is enforced when
an artifact is *served*, not when cached — so tightening the window
applies immediately, including to already-cached versions (NFR-R2).
The refusal is a distinct error carrying artifact, age, and policy, so
a build failure reads as "too new", never as a network mystery (UX2).
Quarantine and vulnerability blocks are additional serve-time gates
fed by operator lists and the CI OSV gate's data (Q4 fixes the feed
mechanics; the depot consumes decisions, it does not scan — NG3).
Exceptions are per-artifact/version operator grants. This is the
s1ngularity medicine: compromised releases are typically caught within
days, and the window buys those days for every build behind the depot.
<!-- implements FR3, FR4 of add-artifact-depot -->

### D4. Download journal derived from guard logs; depot logs supplement
Per-task attribution needs no depot feature: every box request to the
depot passes the task's own guard, and depot request paths carry the
artifact coordinates — so the factory builds the per-task journal from
guard access logs it already owns, joining depot logs only for
supplementary detail (cache hit/miss, upstream fetches). The journal
goes into the task report through the findings funnel; anomaly
flagging starts as "coordinates not seen in this project's baseline"
with the baseline accumulated from previous tasks' journals (Q3 may
refine). Alternative rejected: per-task depot tokens (a credential in
the box and a depot feature dependency, for data the guard already
has). <!-- implements FR5 of add-artifact-depot -->

### D5. Private upstreams die as a box concern
A private registry becomes an upstream of the depot with its
credentials in depot config — supplied via `SecretsProvider`, never in
any box, provisioning phase, or baked file. This closes threat #11 by
construction (stronger than scrubbing between phases, which remains as
insurance) and makes the provisioning network model identical to the
task's: the same two addresses, no "wider setup network" phase.
Upstream additions follow the ownership asymmetry: the repo declares
the need in `.gnomish/`, the operator adds the upstream — with the
same cooldown policy — and the box allowlist never grows.
<!-- implements FR6, FR7 of add-artifact-depot -->

### D6. The depot is the image path too, per deployment site
Where the depot runs, its Docker registry proxy serves sandbox image
pulls: the container adapter's daemon mirror parameter and the
change-C VM daemon mirror point at it, superseding the standalone
pull-through `registry:2` (change C carried this as an explicit seam).
Placement follows the executor (Q5): a host service for local
adapters; for k8s (change D) the depot or a cluster-local mirror plays
the cluster-registry-policy role. GHA runners remain outside — change
E's passport states direct registries and this change does not soften
it. <!-- implements FR9 of add-artifact-depot -->

### D7. Fail-closed availability, cache as disposable state
Depot down = infrastructure failure: check-level retries, no stage
attempt burned, task refusal if it stays down — never a fallback to
direct upstream (that would be a silent policy bypass exactly when
under stress). The cache is disposable state in the change-D sense:
its loss costs re-downloads, never correctness; storage is bounded by
the depot's own cleanup policies with operator-configured limits.
<!-- implements FR10, NFR-R1, NFR-C1 of add-artifact-depot -->

### D8. Admin plane isolation
Boxes reach only the resolution endpoint; the admin UI/API binds
separately (host interface/port outside the box route, enforced by the
same layer-2 mechanics that keep guard config unreachable). Depot
credentials and logs are operator-side only. This is the same
"the gnome cannot change the rules of its cage" invariant applied to
the newest rule-holder.
<!-- implements FR11, NFR-S2 of add-artifact-depot -->

## Risks / Trade-offs

- [Cooldown blocks a legitimately-needed brand-new version] → distinct
  error + per-version operator exception (UX2); lockfile-pinned builds
  rarely hit it (Q2 may differentiate policy).
- [Nexus is a heavyweight always-on service] → compose recipe;
  per-ecosystem lightweight fallback documented (D1); the depot is
  optional — without it, change-A/B behavior stands.
- [Nexus edition/licensing drift] → Q1 re-verification at build;
  fallback path keeps the change viable.
- [Baseline-based anomaly flagging cold-starts noisy] → first tasks of
  a project seed the baseline; flags are signal, not enforcement
  (findings funnel, same as spend anomalies in change B).
- [One more service on every build's critical path] → fail-closed
  semantics keep failures honest (D7); cache makes the common path
  local.
- [Raw/generic proxying invites "proxy anything" scope creep] →
  upstreams are enumerated operator grants (D5); no wildcard upstream.

## Migration Plan

1. Depot deployment recipe + factory config switch (allowlist collapse
   + baked parameters together) + self-check probes; M1.
2. Cooldown, quarantine, exceptions; M2.
3. Private upstreams via SecretsProvider; provisioning-phase
   verification; M3.
4. Download journal + baseline anomaly flagging; M4.
5. Docker image path (supersede C mirror where deployed); M5; docs
   (upstream flow, tuning, GHA note).
   Rollback at any point: flip the config switch back — allowlist and
   parameters revert together to direct-registry mode.

## Open Questions

- Q1 (proposal): product re-verification — resolve at step 1 start.
- Q2 (proposal): per-ecosystem cooldown defaults and lockfile-pinned
  policy — resolve at step 2 with real project data.
- Q3 (proposal): baseline mechanics for anomaly flags — start simple at
  step 4, refine on operator feedback.
- Q5 (proposal): deployment topology per executor site — resolve at
  step 5 together with change D's cluster registry policy.

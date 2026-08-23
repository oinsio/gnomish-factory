# Proposal: normalize-project-identity-url

## Why

The sandbox project identity — the label every factory-created container, volume, and network is stamped with, and the only thing that keeps one project's sweep away from another's boxes — is a digest of the clone's `origin` URL taken **verbatim**. So the identity names the *credential form of the URL*, not the project. Rotate a PAT embedded in `https://<token>@host/owner/repo.git` and the identity changes; every box, volume, and network created before the rotation keeps the old label and drops out of the sweep's scope for good. Nothing reclaims them: they hold disk and, while a container still runs, CPU and memory, and a resume can no longer find the box its task was running in. The same silent re-partition happens for cosmetic differences that name one and the same remote — a `.git` suffix, a trailing slash, `Github.com` vs `github.com`, or the scp-style `git@host:path` form of an otherwise identical remote.

The observation came out of `fix-lifecycle-push`, whose credential scrub (NFR-S2) closed the *disclosure* side of a token in the origin URL. This change closes the *identity* side: the digest is not a leak (it is one-way and truncated), but it is unstable in exactly the situation credential hygiene demands — rotation.

## What Changes

- **MODIFIED** — the project identity derives from a normalized `origin` URL: userinfo stripped, scheme and host case-folded, an explicitly written default port removed, a trailing `/` and a `.git` suffix removed, and the scp-style form rendered in the same shape as its URL equivalent. The override and the origin-less path fallback are untouched.
- **ADDED** — the sweep recognizes the *legacy* identity (the digest of the raw URL) as its own while it differs from the normalized one, so objects created before this change stay in scope instead of being orphaned by the very change that fixes orphaning. New objects are stamped with the normalized identity only.
- **ADDED** — operator guidance: what normalization does and does not conflate, and the one manual `docker ... --filter label=` cleanup that pre-existing orphans (objects created under an already-rotated credential) still need.
- Not a breaking change for configuration: no property is added, removed, or reinterpreted.

## Goals

- G1: One project resolves to one identity across a credential rotation and across the cosmetic URL variants that name the same remote.
- G2: No factory object leaves its sweep scope as a result of this change — every object created by an earlier version stays visible to the sweep that follows it.
- G3: The fix costs no new configuration and no new operator decision.

## Non-Goals

- NG1: Not a credential-handling change. Disclosure of a URL-embedded credential is already handled (`fix-lifecycle-push` NFR-S2); the token still lives in `.git/config`, and the operator guide keeps recommending a credential helper over a token in the URL.
- NG2: No change to the label schema, the sweep's classification rules, the reaper's thresholds, or the override's precedence and validation.
- NG3: No conflation of genuinely distinct remotes. Two different hosts, two different paths, or `http` vs `https` stay two identities — narrowing that is a scope-sharing decision, not a normalization one.
- NG4: No recovery mechanism for objects orphaned *before* this change by a rotation that already happened. Their label carries a digest of a URL nobody has any more; the guide gets a manual cleanup command instead.
- NG5: No migration tooling, relabelling pass, or schema version — the legacy-identity listing (FR3) is the whole migration.

## Users & Scenarios

- U1: An operator rotates the PAT in the clone's `origin` URL between two `gnomish serve` passes. The sweep continues to manage the boxes it created before the rotation; nothing is left running that no pass will ever collect.
- U2: An operator runs two factory instances against two checkouts of the same repository on one Docker host, one cloned over `https://…/repo.git` and one over `git@host:owner/repo`. Both resolve one identity and each sees the other's objects as its own project's — which is what the shared sweep scope is for.
- U3: An operator upgrading across this change runs a sweep and sees its existing boxes classified exactly as before, with one INFO line recording that legacy-labelled objects were included.

## Requirements

### Functional

- FR1: The project identity SHALL be a digest of the normalized `origin` URL rather than the raw one. Normalization SHALL remove URL userinfo, lower-case the scheme and host, remove an explicitly written default port of the scheme (`http` 80, `https` 443, `ssh` 22, `git` 9418), remove one trailing `/`, remove a trailing `.git`, and render the scp-style `[user@]host:path` form in the same shape as the equivalent `ssh://host/path` URL.
- FR2: Normalization SHALL be identity-preserving in the other direction: two remotes differing in host, path, non-default port, or scheme (beyond case) SHALL keep distinct identities.
- FR3: When the identity derives from the `origin` URL and the digest of the raw URL differs from the normalized one, sweep and reaper listings SHALL treat objects carrying that legacy identity as this factory's own, in addition to the normalized one. With an override set or no `origin` configured, no legacy identity exists — those resolution paths are unchanged by this change. Objects created after this change SHALL be stamped with the normalized identity only, and no object SHALL ever be relabelled.
- FR4: The override (`factory.sandbox.project-id`) and the origin-less fallback to the clone's canonical absolute path SHALL keep their current precedence and behavior.

### Non-Functional Reliability

- NFR-R1: Identity resolution SHALL NOT fail on a URL it cannot parse. A remote URL in any shape the normalizer does not recognize SHALL fall back to the raw string, so an unusual remote costs identity stability, never a failed run or a failed sweep pass.
- NFR-R2: A failure of the extra legacy listing SHALL be treated like any other listing failure under the existing fail-closed rule — the pass aborts with no verdicts rather than degrading to a partial object set.

### Non-Functional Observability

- NFR-O1: When the legacy identity differs from the normalized one and legacy-labelled objects are found, the pass SHALL log one INFO naming the count, so the transition is visible in the log rather than silent.

### Non-Functional Security

- NFR-S1: No stripped userinfo SHALL appear anywhere the normalization output travels — label, log, error message, or report. The identity remains a truncated digest; the normalized URL itself is never used as a label value.

### Non-Functional Cost

- NFR-C1: The legacy scope SHALL cost at most one extra listing per object kind per sweep pass, and only while a legacy identity exists.

## Operator Experience Criteria

- UX1: Rotating a credential in the `origin` URL has no visible effect on sweep behavior — the same boxes are found, classified, and reclaimed as before the rotation.
- UX2: The sandbox operator guide states which URL differences are normalized away and which are not, so an operator can predict whether two checkouts share a sweep scope.
- UX3: The guide carries the one-time manual cleanup for objects orphaned before this change, as a `docker` command an operator can run and verify.

## Success Metrics

- M1: A table-driven spec covers at least 8 URL variants: the credential and cosmetic variants of one remote (including an explicitly written default port) all resolve to one identity, and the distinct-remote variants (other host, other path, other non-default port, `http` vs `https`) all resolve to different ones.
- M2: An integration spec stamps an object with the legacy identity, runs a sweep after the change, and observes the object in the pass's verdicts — the G2 no-orphan guarantee.
- M3: `grep` finds exactly one production site deriving the project identity and exactly one normalizer; no call site bypasses the normalizer — only `ProjectIdentity` itself digests URLs, including the NFR-R1 raw-string fallback.

## Open Questions

- Q1: Is the legacy-identity listing permanent, or does it retire after a release cycle? Default: permanent for now — the project has no deprecation policy to hang a removal on, and the cost is one bounded listing.
- Q2: Should `http` and `https` forms of one remote be conflated? Default: no (NG3) — they are distinguishable transports and conflating them makes normalization a scope-sharing policy.

## Capabilities

### New Capabilities

<!-- none: this change modifies an existing capability only -->

### Modified Capabilities

- `sandbox-lifecycle`: the "Project identity derivation" requirement gains normalization of the `origin` URL before the digest (FR1, FR2, NFR-R1, NFR-S1); "Project scoping" gains the legacy-identity listing during transition (FR3, NFR-O1, NFR-C1).

## Impact

- `application/.../app/git/ProjectIdentity.java` — the single derivation site; gains a normalization step and the legacy digest it exposes for FR3.
- `sandbox/docker/.../SandboxLifecycleSweep.java`, `DockerLifecycleCommands`, `FactoryDockerLabels` — listing scoped to one identity today; FR3 widens the read side only, never the write side.
- `bootstrap/.../SandboxLifecyclePassFactory.java`, `ContainerRunSupportFactory.java` — the two call sites resolving the identity from `OriginRemote.url(...)`.
- `docs/guides/operator-guide-sandbox.md` — UX2, UX3.
- No new dependency; no configuration property added or changed.
- Ordering: implementation starts only after `fix-lifecycle-push` is committed — the `OriginRemote` reader these call sites use and the `CredentialScrub` sibling that design D3 references are introduced by that change.

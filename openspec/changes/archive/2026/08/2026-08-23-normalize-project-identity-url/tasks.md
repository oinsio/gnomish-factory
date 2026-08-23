# Tasks: normalize-project-identity-url

## 1. The normalizer (design D1, D2, D3)

- [x] 1.1 Spec first: a table-driven Spock spec over the normalizer covering the credential and cosmetic variants of one remote (embedded token, `user:password@`, `.git` suffix, trailing slash, mixed-case host, explicit default port such as `:443`, scp-style form) collapsing to one value, and the distinct-remote variants (other host, other path, other non-default port, `http` vs `https`) staying apart. At least 8 variants. FR1, FR2, M1
- [x] 1.2 Spec the total-function guarantee: an unrecognized shape (empty string, a bare local path, `ext::`-style transport, a URL with no authority) is returned unchanged and never throws. NFR-R1
- [x] 1.3 Implement the lexical normalizer in `app.git`, next to `ProjectIdentity`; javadoc names the sibling rule in `adapters/git`'s `CredentialScrub` and why the two are not shared (D3). FR1, FR2, NFR-R1
- [x] 1.4 Assert the security property directly: no spec input's userinfo appears in the normalizer's output, in a thrown message, or in a resolved identity. NFR-S1

## 2. Identity derivation (design D1, D4)

- [x] 2.1 Spec + implement: `ProjectIdentity` digests the normalized URL, keeping the override's precedence and validation and the origin-less canonical-path fallback exactly as they are. FR1, FR4
- [x] 2.2 Spec + implement the resolved *scope*: the stamped identity plus the legacy alias (digest of the raw URL) when the two differ, and no alias when they agree, when an override is set, or when there is no `origin`. FR3, NFR-C1
- [x] 2.3 Grep gate as a spec: exactly one production site derives the project identity and exactly one normalizer exists; no call site digests a raw URL. M3

## 3. Read-side scope in the sweep (design D4, D5)

- [x] 3.1 Spec + implement: the sweep's per-kind listing runs once per identity in the scope and merges by object name, with creation still stamping the single identity. FR3
- [x] 3.2 Spec + implement fail-closed parity: a failed legacy listing aborts the pass with no verdicts and no completed tick, like any other failed listing. NFR-R2
- [x] 3.3 Spec + implement the transition INFO: one line naming how many legacy-labelled objects the pass found, logged only when the alias is present and non-empty. NFR-O1
- [x] 3.4 Spec: with no alias (override set, no `origin`, or already-normal URL) the pass issues no additional listing. NFR-C1
- [x] 3.5 Wire the two composition-root call sites (`SandboxLifecyclePassFactory`, `ContainerRunSupportFactory`) to the resolved scope; the run path stamps the single identity. FR3, FR4

## 4. End-to-end verification

- [x] 4.1 Docker-gated integration spec: an object stamped with the legacy identity is found, classified, and acted on by a sweep running under the normalized one — the no-orphan guarantee. M2, G2
- [x] 4.2 Integration spec: rotating the credential in a bare-repo `origin` URL between two passes leaves the identity and the pass's verdicts unchanged. UX1, FR1
- [x] 4.3 Run `:application:check`, `:sandbox:docker:check`, `:bootstrap:check` and the full `./gradlew check`; PIT stays at the module gate with no new exemption. If a new exemption is unavoidable, record its rationale per `.claude/rules/testing.md`

## 5. Documentation

- [x] 5.1 Update `docs/guides/operator-guide-sandbox.md`: which URL differences are normalized away and which are not — including that the scp→ssh fold conflates a home-relative `host:path` with the absolute `ssh://host/path` (design D2) — so an operator can predict whether two checkouts share a sweep scope. UX2
- [x] 5.2 Document the one-time manual cleanup for objects orphaned before this change (a `docker` command over the factory-ownership label), plus the rollback window the legacy alias defines. UX3, NG4
- [x] 5.3 Glossary: amend the "Project identity" entry — the digest is now of the *normalized* `origin` URL — and add a "Legacy identity" entry (or fold it into the amended entry), per `process-invariants.md`
- [x] 5.4 Resolve Q1 (legacy alias permanent vs retired) or record it as deliberately deferred in this task's completion note. Q1

  **Deferred, with the proposal's default adopted: the legacy alias stays.** The project has no
  deprecation policy to hang a removal on, and the cost is bounded by NFR-C1 — one extra listing
  per object kind, and only while the two digests differ. Nothing here depends on the answer:
  retiring it later is the removal of `ProjectIdentity.resolveScope`'s alias branch and of the
  scope requirement's transition clause, with `ScopedObjectListing` collapsing to the single
  listing it already performs when no alias is present. Revisit when the project first needs a
  deprecation window for anything else.
- [x] 5.5 Recommend a commit message referencing normalize-project-identity-url and the requirement IDs

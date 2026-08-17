# Design: close-plugin-api-compilability-gap

## Context

See proposal.md — Why. Current state, from the parent change's G4 residual:

- `adapters/github/build.gradle` declares `implementation project(':application')`
  for exactly two types: `app.workspace.AttemptCommitWorkspace` (a record; the
  github check client downcasts the SPI-provided `Workspace` to it to read the
  attempt-commit sha — `GithubCheckExternalClient`) and
  `app.findings.FindingsSanitizer` (a dependency-free static utility;
  `GithubWorkflowJobsFetcher` strips/caps CI log tails with it).
- The same hidden-downcast protocol is used first-party by the coarse
  `:adapters` remainder (`HttpExternalCheckClient`,
  `PinCheckedExternalCheckClient`, `SandboxCheckEnvironmentSource`,
  `FilesExistCheckRunner`), `:adapters:git` (`RemoteAttemptDelivery`) and
  `:adapters:agent` (`FreshJudgeEnvironments`) — but several of those consume
  the *ref* (`attemptCommit()`), not just the sha, and their modules
  legitimately depend on `:application`.
- `FindingsSanitizer` lives in package `app.findings` next to `TrackerFence`
  (which calls it and stays); `gnomish-plugin-api`'s package root is the same
  `com.github.oinsio.gnomish.app`.
- `gnomish-plugin-api:sample` implements only `TrackerAdapterFactory`; the
  check SPI (`CheckClientFactory`, no-arg constructed, `create(secrets,
  subsection[, runContext])` → `ExternalCheckClient`) has no api-only consumer
  proving it authorable.
- japicmp runs as a failing gate against committed jars in
  `gnomish-plugin-api/compat-baseline/`, regenerated only by
  `updateApiCompatibilityBaseline` (parent change task 7.3 deviation).

Constraints: additive-only api change (FR5); the sanitization invariant must
survive the move (NFR-S1); `.claude/rules/process-invariants.md` module
boundaries; PIT 100% follows moved classes.

## Goals / Non-Goals

**Goals:**

- The github bundle's compile classpath is the published contract alone —
  proven by Gradle (`layering.allowedProjects` without `:application`), not
  prose (FR3, M1).
- A third-party external check can learn the commit it verifies and sanitize
  its findings with one declared dependency (G1, FR1, FR2).
- Regression is a compile failure in `gnomish-plugin-api:sample` (FR4).

**Non-Goals:**

- No `getAdapter(Class<T>)`-style capability framework on `Workspace` (NG1) —
  two consumers do not warrant Eclipse-grade generality.
- No retargeting of first-party consumers that need the full
  `AttemptCommitRef` (git/agent/coarse adapters keep the record; only the
  github bundle must lose the edge). Narrowing the other check adapters to the
  api type is optional hygiene, not scope.
- No `gnomish-plugin-support` module (NG2); no JPMS.

## Decisions

**D1 — The api publishes an interface; the record stays in `:application` and
implements it.** New `app.port.check.AttemptCommitWorkspace extends
domain.engine.port.Workspace` with one method, `String attemptCommitSha()`,
placed beside `ExternalCheckPinContributor` — it is check-SPI vocabulary. The
existing record implements it; `attemptCommitSha()` maps onto the record's
existing sha accessor including its "no snapshot recorded yet"
`IllegalStateException` (the spec's protocol-error scenario). The
`AttemptCommitRef` and its mutable `record(...)` protocol remain engine
internals — plugins get read access to the sha, never the ref. *Rationale:*
SPI modules carry contracts, not implementations; downcasting to a contract
type is the codebase's established workspace idiom (`DirectoryWorkspace` for
host runners), the defect was only that the target type was hidden.
*Alternative rejected:* move the record itself into the api — drags
`AttemptCommitRef` (engine mechanics, `app.port.git`) into the semver surface
and hands plugins a mutation protocol they must not touch. *Alternative
rejected:* capability accessor on `Workspace` — NG1.

**D2 — Naming: the interface claims the canonical name; the record is renamed
`RecordedAttemptCommitWorkspace`.** Two types with the same simple name in
`app.port.check` and `app.workspace` would compile but make every import a
trap; the plugin-facing name should be the undecorated one. The rename is
mechanical (record is `:application`-internal plus first-party adapters/tests;
none of it is api surface). *Alternative rejected:* same simple name in both
packages — import ambiguity for exactly the audience (first-party adapters)
that sees both.

**D3 — `FindingsSanitizer` moves file-and-FQN into the api module.** The class
keeps package `app.findings` (the api's root is the same `app`), so every
first-party caller (`TrackerFence`, `JudgeVerdictExtractor`,
`GithubWorkflowJobsFetcher`) keeps its import; only the owning module changes.
Its Javadoc's `TrackerFence` mention is rewritten (the fence stays in
`:application`; the api must not reference application types even in prose).
`FindingsSanitizerSpec` moves with it into the api module's test tree; PIT
scope follows. This makes `app.findings` a split package across two modules —
acceptable: classpath (not JPMS) build, and `TrackerFence` is deliberately NOT
api. *Rationale:* zero-churn move; the utility is contract-grade (the
sanitize-before-tracker invariant is a promise every plugin should keep).
*Alternative rejected:* new api package (e.g. `app.port.findings`) — touches
every caller for no behavioral gain; *Alternative rejected:* copy instead of
move — two drifting implementations of a security invariant.

**D4 — The sample grows a minimal `CheckClientFactory`.** Provider
`"sample"`: no-arg constructor, `create` returns an `ExternalCheckClient`
whose poll narrows the workspace to the api's `AttemptCommitWorkspace` and
reports a terminal result naming the sha it read, its finding text passed
through `FindingsSanitizer` — exercising both new surface pieces at compile
time. Declared in `META-INF/services` so the authored shape is complete; the
module keeps `gnomish-plugin-api` as its only declared dependency, which is the
whole enforcement mechanism: a future type leak breaks this module's compile.
The module has no test source set by design (compilation IS the assertion), so
nothing here is asserted by a spec — and the sample jar is on no assembled
classpath, so no runtime discovery is claimed. *Alternative rejected:* an
ArchUnit rule over `adapters/github` imports — weaker than an absent classpath
entry, and
the layering plugin already owns that axis.

**D5 — Order of operations for the api gate.** Land the two api additions and
the record's `implements` first, retarget `GithubCheckExternalClient` /
`GithubWorkflowJobsFetcher`, drop the `:application` edge and the
`layering.allowedProjects` entry, then regenerate `compat-baseline/` via
`updateApiCompatibilityBaseline` as the deliberate reviewable act. The gate
stays red between addition and regeneration only within this change's own
branch — same protocol the parent change established (its task 7.3
deviation). japicmp additions are compatible by definition; the regeneration
records them.

**D6 — The test classpath is cleared through `:test-fixtures`, not through a
new declared edge.** The two github check specs today import
`app.port.git.AttemptCommitRef` and construct the `:application` record
themselves; `:test-fixtures` already owns exactly that three-line assembly
(`AttemptCommitWorkspaces.at/empty`). Its factory methods are narrowed to
return the api `AttemptCommitWorkspace`, and the specs call them — so the only
types the github test source names are api types plus the fixture, and
`:application` remains a transitive of `:test-fixtures` that nothing
references. *Rationale:* the alternative — declaring
`testImplementation project(':application')` to satisfy the global
`onAny { severity 'fail' }` used-transitive rule — would re-open on the test
classpath precisely the edge FR3 removes from production, and would read to a
reviewer as the constraint being half-kept. The layering gate walks production
configurations only (by design, `layering-conventions`), so it would never
catch it; dependency-analysis is the only signal here, and this keeps its
answer honest rather than silenced.

## Risks / Trade-offs

- **Split package `app.findings` across `:gnomish-plugin-api` and
  `:application`** → classpath build tolerates it; documented in both
  `package-info.java` files; revisit only if JPMS ever arrives (it is a
  non-goal project-wide).
- **Record rename ripples through first-party adapters/tests (D2)** →
  mechanical, compiler-driven; no spec behavior changes, so `module-layering`'s
  behavior-preserving bar applies and existing scenarios pass unchanged.
- **The sample's proof is compile-time only — no runtime discovery is
  asserted** → same posture as its tracker factory today: the sample jar is on
  no assembled classpath, production or test, so the service entry documents
  the authoring shape without widening any distribution. Runtime discovery of
  SPI factories is already covered by the built-in providers' own specs.
- **The narrowed fixture return type ripples to other fixture callers** → the
  only ones today are two `:adapters` specs (`PinCheckFixture`,
  `PinCheckedExternalCheckClientSpec`), which pass the result on as a
  `Workspace`; if any future caller needs the record's `attemptCommit()` ref it
  builds it locally rather than widening the fixture back.
- **api surface grows → larger semver liability** → both additions are small
  and stable by construction (one accessor; one static utility with frozen
  behavior pinned by its moved spec); the japicmp gate now guards them.

## Migration Plan

Single-branch, no deploy concerns. Rollback = revert the branch; the
regenerated `compat-baseline/` jars revert with it. Third-party migration:
none — no released api version predates this change.

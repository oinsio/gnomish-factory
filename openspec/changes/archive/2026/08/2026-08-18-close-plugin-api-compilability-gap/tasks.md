# Tasks: close-plugin-api-compilability-gap

## 1. Publish the api surface

- [x] 1.1 Add `app.port.check.AttemptCommitWorkspace` interface
  (`extends Workspace`, single `String attemptCommitSha()`) to
  `gnomish-plugin-api`, Javadoc carrying the protocol-error contract and the
  traceability line (FR1, D1)
- [x] 1.2 Move `app.findings.FindingsSanitizer` file-and-FQN from
  `:application` to `gnomish-plugin-api`; rewrite its Javadoc's `TrackerFence`
  reference; move `FindingsSanitizerSpec` with it and confirm PIT scope
  follows (FR2, NFR-S1, D3)
- [x] 1.3 Document the deliberate `app.findings` split package in both
  modules' `package-info.java` (D3 risk note)

## 2. Retarget the engine record

- [x] 2.1 Rename the `:application` record to `RecordedAttemptCommitWorkspace`
  and make it implement the api interface, mapping `attemptCommitSha()` onto
  the existing sha accessor with its `IllegalStateException` intact (FR1, D1,
  D2)
- [x] 2.2 Compiler-driven sweep of first-party consumers of the renamed record
  (`:adapters` coarse, `:adapters:git`, `:adapters:agent`, `:bootstrap`,
  tests); behavior-preserving — no scenario or assertion changes (D2 risk)
- [x] 2.3 Spec: reading `attemptCommitSha()` through the api interface returns
  the recorded round's sha; reading before any snapshot throws the protocol
  error (FR1)

## 3. Cut the github edge

- [x] 3.1 Retarget `GithubCheckExternalClient`'s downcast and
  `GithubWorkflowJobsFetcher`'s sanitizer import to the api types (FR1, FR2)
- [x] 3.2 Clear the github *test* classpath of `:application` types: narrow
  `test-fixtures`' `AttemptCommitWorkspaces` factory methods to return the api
  `AttemptCommitWorkspace`, and rewrite the two github check specs
  (`GithubCheckExternalClientContractSpec`,
  `GithubCheckExternalClientStatelessPollSpec`) to build their workspace
  through that fixture instead of importing `app.port.git.AttemptCommitRef` and
  constructing the record — otherwise dependency-analysis' global
  `onAny { severity 'fail' }` demands a `testImplementation project(':application')`
  the delta forbids in spirit (FR3, M1, D6)
- [x] 3.3 Remove `implementation project(':application')` from
  `adapters/github/build.gradle` and prune `layering.allowedProjects` to
  `[':domain', ':gnomish-plugin-api']` — `:gitobjects` and `:sandbox:core`
  reached this module only through `:application`'s `api` edges and their
  entries (plus the "arrives transitively through `:application`" comment) die
  with it; update the dependency-rationale comments (FR3, M1)
- [x] 3.4 Spec/gate: `./gradlew :adapters:github:check` green with the edge
  absent; dependency-analysis raises no unused/undeclared findings on any
  configuration, and `verifyModuleLayering` passes on the pruned allow-list
  (M1)

## 4. Prove the authoring path in the sample

- [x] 4.1 Implement the sample `CheckClientFactory` (provider `"sample"`,
  no-arg constructor, client narrows to the api `AttemptCommitWorkspace`,
  finding text passed through `FindingsSanitizer`) + `META-INF/services`
  entry; module keeps `gnomish-plugin-api` as its only declared dependency
  (FR4, G1, D4)
- [x] 4.2 Confirm the enforcement is the build, not a spec: the sample module
  keeps no test source set (compilation IS the assertion) — verify
  `:gnomish-plugin-api:sample:check` compiles the new factory and that its
  `layering.allowedProjects` still names only `:domain` and
  `:gnomish-plugin-api` (FR4, D4)

## 5. Re-baseline and close out

- [x] 5.1 Run `updateApiCompatibilityBaseline`; review the baseline diff is
  additive-only for pre-existing types; japicmp gate green (FR5, D5)
- [x] 5.2 Full `./gradlew check` (JaCoCo, PIT per moved scope, ArchUnit,
  dependency-analysis, Spotless) green (M1)
- [x] 5.3 Verify traceability: every FR/NFR/G of this change grep-resolves to
  at least one implementing entity or spec (traceability rule)

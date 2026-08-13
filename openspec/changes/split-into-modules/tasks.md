# Tasks: split-into-modules

Order follows the design migration plan (D2 two passes; suite green at every
checkpoint). Tasks 1–9 are pass 1 (horizontal); task 10 is pass 2 (vertical).

## 1. build-logic foundation

- [x] 1.1 Create the `build-logic` included build and wire it into
  `settings.gradle` (FR6, D9)
- [x] 1.2 Author `java-conventions` (toolchain, Spotless, Error Prone + NullAway,
  JaCoCo) and `test-conventions` (Spock, PIT) convention plugins (FR6, D9)
- [x] 1.3 Author `library-conventions` and `published-api-conventions` (semver
  metadata) convention plugins (FR5, FR6, D9)
- [x] 1.4 Keep the single module compiling through the convention plugins; run
  the full suite green as the baseline (FR9, D2)
  <!-- baseline: `./gradlew check` green in 35m49s — 3867 mutations, 100% killed -->


## 2. Extract domain, gitobjects, and gnomish-plugin-api

- [ ] 2.1 Extract `:domain` (already adapter-free at the import level; rewrite
  its cross-layer javadoc `{@link}`s as plain text) and `:gitobjects`
  (git-object utilities + the `DoNotMutate` marker) applying the library
  convention (FR1, D1, D4)
- [ ] 2.2 Spike: derive the api surface from the github adapter's import closure
  minus its own packages (FR4, Q3, D4)
- [ ] 2.3 Create `:gnomish-plugin-api` with the tracker/secrets/check ports, the
  `TrackerAdapterFactory` SPI, and the relocated `TrackerSubsectionValidator`;
  expose `:domain` as a transitive `api` dependency; the check SPI factory is
  deferred to change B (FR4, D4)
- [ ] 2.4 Add a dependency-analysis assertion that `:gnomish-plugin-api` has zero
  `application` / `bootstrap` internal imports (FR4, M3)
- [ ] 2.5 Apply `published-api-conventions`: `maven-publish` + real semver on
  `:gnomish-plugin-api`; add japicmp in report-only (non-failing) mode over the
  api artifact and its transitively exposed `:domain` types (FR5, D10, Q2)
- [ ] 2.6 Document the api artifact for third parties; verify a sample adapter
  compiles with `gnomish-plugin-api` as its only declared dependency (UX3, FR4)

## 3. Extract the sandbox port layer

- [ ] 3.1 Extract `:sandbox:core` (port, capability-passport negotiation,
  reconciliation, the `AdapterBinding` / `IsolationLevel` enums, sandbox
  config-properties types) with no backend-specific dependencies; backend
  classes stay in place for now (FR8, D7, D11)

## 4. Split app into application and bootstrap

- [ ] 4.1 Create `:application` (use cases + ports) and move the port-only `app`
  files into it, including the execution-environment use-case files now
  importing the port from `:sandbox:core` (FR3, D3, D11)
- [ ] 4.2 Create `:bootstrap` with `@SpringBootApplication`, `main()`, and all
  `@Configuration`; move the adapter-implementation-importing `app` files into
  it (FR3, D3)
- [ ] 4.3 Make `bootstrap` the sole Spring scan root; adapters export explicit
  configuration / factories, no cross-module scanning (FR3, NFR-R1, D3)
- [ ] 4.4 Add an ArchUnit rule: `:application` has no adapter import; run suite
  green (FR2, FR9, UX2, D5)

## 5. Move adapters and enforce boundaries

- [ ] 5.1 Move the adapters (including the shared `adapter/check` runners, kept
  coarse) into a `:adapters` block depending on `:gnomish-plugin-api` +
  `:application` + `:sandbox:core` (FR1, FR2, D1)
- [ ] 5.2 Add dependency-analysis + ArchUnit rules for the acyclic direction and
  no sibling-adapter-internal imports (FR2, UX2, M4, D5)
- [ ] 5.3 Resolve the five github→sibling-adapter leaks per the D4 table: relocate
  `TrackerSubsectionValidator` into the api and `ExternalCheckPinContributor`
  into `:application`, inject `SecretsProvider` / depend on the `Workspace` port
  instead of impls, move `FindingsSanitizer` to shared util (FR2, M4, D4)
- [ ] 5.4 Verify a deliberate sibling-adapter-internal import fails `check` with a
  named rule, then revert it (FR2, UX2)
- [ ] 5.5 Assert adapters reach secrets only through the `SecretsProvider` port
  and no credential value appears in any module's build metadata (NFR-S1)

## 6. Sandbox backend split

- [ ] 6.1 Extract `:sandbox:docker` (docker-CLI backend) depending on
  `:sandbox:core` (FR8, D7)
- [ ] 6.2 Run the existing execution-environment specs unchanged against the split
  modules; assert the `AdapterBinding` enum constants are unchanged (FR8, FR9)

## 7. Shared test fixtures

- [ ] 7.1 Create `:test-fixtures` and move shared Spock fixtures into it (FR7, D8)
- [ ] 7.2 Consume `:test-fixtures` via `testImplementation`; assert no production
  module depends on it (FR7, D8)

## 8. Per-module mutation scoping and quality-gates re-expression

- [ ] 8.1 Bind each module's PIT `targetClasses` to its own Java production
  packages (never Groovy test bytecode) and wire `pitest` into that module's
  `check`, so root `check` aggregates all module runs (FR11, NFR-P1, D6)
- [ ] 8.2 Remove the whole-tree PIT task from `check` (keep an opt-in aggregate
  task); keep the scoped-target property working within a module, absent
  property = full module tree (FR11, NFR-P1, D6)
- [ ] 8.3 Re-point CI mutation scoping: map changed production classes to their
  owning modules and run only those modules' mutation gates; docs-only diffs
  still pass cleanly (FR11, D6)
- [ ] 8.4 Document the single-module PIT invocation; verify a single-module change
  mutates only that module (NFR-P1, UX1, M1)

## 9. Pass-1 verification

- [ ] 9.1 Run the full suite; assert all pre-existing specs pass with no spec-file
  edits (FR9, M5)
- [ ] 9.2 Assert each module build file is within the file-size cap and the
  monolithic `build.gradle` is gone (FR6, M2)
- [ ] 9.3 Measure clean-build wall-time against the pre-split baseline (NFR-P2)

## 10. Pass 2 — vertical adapter split

- [ ] 10.1 Extract `:adapters:github` bundling `github` (shared http, internal
  package) + `tracker/github` + `check/github` into one vendor module (FR10, D1)
- [ ] 10.2 Extract `:adapters:git` and `:adapters:agent`; keep `tracker/inmemory`
  in-tree and the small adapters coarse (FR10, D1)
- [ ] 10.3 Re-run boundary rules and the full suite green after the vertical split
  (FR2, FR9, FR10, M4)

# Proposal: refactor-app-spec-fixtures

## Why

Twenty-one app-layer spec files hand-build the identical six-collaborator
`ManualRunAssembly` block (`SystemConsoleIO`, `FilesExistCheckRunner`,
`ShellCommandCheckRunner`, `SystemClock`, `ThreadSleeper`,
`FactoryProperties`), and ~23 sites construct near-identical
`FactoryProperties` test values. Every change to the `ManualRunAssembly`
constructor or the `FactoryProperties` record ripples through all of these
files (the in-flight `add-tracker-port` diff touches many of them for
exactly this reason). The duplication (~150–250 lines) adds friction to
every wiring change and invites drift between specs.

## What Changes

- **ADDED**: a shared Spock fixture trait in test sources (working name:
  `AppAssemblyFixture`) that provides the standard `ManualRunAssembly`
  collaborator set and a `FactoryProperties` test builder, with override
  points for the parts that genuinely vary per spec (console input/output,
  agent binary, instance name).
- **MODIFIED**: the app-layer specs and spec bases that currently inline
  the construction block are refactored to use the trait. Pure test
  refactor — no assertions, no test names, no covered behavior change.
- **REMOVED**: nothing. No production code is touched.

## Capabilities

### New Capabilities

None — this change is test-infrastructure only.

### Modified Capabilities

- `quality-gates`: gains a test-suite hygiene requirement — the standard
  app-layer engine-assembly fixture SHALL have a single construction site
  shared by all app-layer specs (same precedent as the source-file-size-cap
  requirement added by `fix-oversized-adapters`). No product behavior
  changes.

## Goals

- G1: exactly one construction site for the standard `ManualRunAssembly`
  collaborator set in test sources.
- G2: a future `ManualRunAssembly` constructor change touches the fixture
  trait plus only the specs whose override points genuinely differ.
- G3: zero behavior change — same specs, same assertions, suite stays
  green with an unchanged test count.

## Non-Goals

- NG1: no Spring test context (`@SpringBootTest` / `@TestConfiguration`)
  in unit-style specs — the fixture stays a plain Groovy trait, matching
  the existing `BareGitRepoFixture` pattern and keeping specs fast.
- NG2: no changes to production wiring (`ManualRunAssembly`,
  `ManualRunConfiguration`, `FactoryProperties`) — test-side only.
- NG3: domain specs, port contract suites, and adapter specs outside the
  duplicated block are out of scope.
- NG4: the package-private `FakeAgentSupport` duplication noted in
  `ManualRunAssemblySpec` is out of scope (tracked as Q2).

## Users & Scenarios

- U1: a developer writing a new app-layer spec creates the standard
  assembly with a one-line trait call instead of copying an 8-line block.
- U2: a developer changing the `ManualRunAssembly` constructor (or
  `FactoryProperties` shape) updates the fixture trait once instead of
  editing 21 spec files.

## Requirements

### Functional

- FR1: a Groovy trait in app test sources provides a factory method that
  builds a `ManualRunAssembly` from the standard collaborator set, with
  parameters (defaulted) for console input, console output, and
  `FactoryProperties`.
- FR2: the trait provides a `FactoryProperties` test builder with the
  prevailing defaults (`'test-instance'`, null agent settings) and named
  override points covering the variants that exist today (instance name,
  agent binary path, credential env vars).
- FR3: every app-layer spec or spec base that currently inlines the
  standard construction block uses the trait; direct construction remains
  only where the spec's subject is the construction itself.
- FR4: no production sources change; the diff is confined to
  `src/test/groovy`.

### Non-Functional Reliability

- NFR-R1: trait factory methods return fresh instances per call — no
  shared mutable state between specs or between iterations of data-driven
  features (preserves today's isolation semantics).

### Non-Functional Performance

- NFR-P1: no measurable test-suite slowdown — the trait is plain object
  construction, no Spring context startup added.

### Non-Functional Observability / Security / Cost

Considered and not applicable: the change is test-internal, introduces no
logging, credentials, network, or token usage. (Credential-scrub
override points in FR2 only pass through values specs already use.)

## Operator Experience Criteria

- UX1: a reader of any refactored spec can see at the call site which
  fixture parts are overridden — defaults are invisible, deviations are
  explicit named arguments.

## Success Metrics

- M1: `grep -rn "new ManualRunAssembly" src/test/groovy` returns exactly
  one construction site (inside the trait), down from 21.
- M2: `./gradlew test` passes with the same number of executed tests as
  before the refactor.
- M3: PIT mutation score is unchanged (PIT mutates Java production code
  only; this change must not alter which mutants are killed).

## Open Questions

- Q1: none blocking — trait placement and exact API are design decisions
  (see design.md).
- Q2 (deferred, out of scope): fold the fake-agent wrapper duplication
  between `ManualRunAssemblySpec` and the package-private
  `adapter.agent.FakeAgentSupport` into a shared helper in a follow-up
  change.

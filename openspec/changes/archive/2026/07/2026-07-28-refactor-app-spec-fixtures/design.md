# Design: refactor-app-spec-fixtures

## Context

Twenty-one app-layer spec files (and spec bases / fixtures such as
`TakeResumeSpecBase`, `GitResumeSpecBase`, `TwoInstanceTakeFixture`)
inline the same construction block:

```groovy
new ManualRunAssembly(
        new SystemConsoleIO(input, output),
        new FilesExistCheckRunner(),
        new ShellCommandCheckRunner(),
        new SystemClock(),
        new ThreadSleeper(),
        factoryProperties)
```

The only parts that vary are the console streams and the
`FactoryProperties` value (~23 sites, dominated by
`new FactoryProperties('test-instance', null, null, null)` with a few
agent-binary and instance-name variants). Context: driven by FR1–FR4 and
NFR-R1/NFR-P1 from the proposal.

The test suite already has a house pattern for shared test plumbing:
plain Groovy traits (`BareGitRepoFixture`, `InvalidFixtureSupport`,
`PortContractSupport`) composed into specs — no Spring test context in
unit-style specs.

## Goals / Non-Goals

**Goals:**
- One construction site for the standard assembly collaborator set (FR1, G1).
- Named, defaulted override points for the parts that genuinely vary (FR2, UX1).
- Behavior-preserving, test-only diff (FR4, G3).

**Non-Goals:**
- No Spring `@TestConfiguration`/context in unit specs (proposal NG1).
- No production-code changes (NG2); no touching domain/contract/adapter
  specs outside the duplicated block (NG3).

## Decisions

### D1: Plain Groovy trait, not a base class and not Spring

A Spock/Groovy **trait** (`AppAssemblyFixture`) carries the factory
methods.

- *Why not an abstract base class:* Groovy has single inheritance and the
  app specs already spend it on `Specification` subtree bases
  (`TakeResumeSpecBase`, `GitResumeSpecBase`); traits compose freely with
  those and with `BareGitRepoFixture`.
- *Why not Spring `@TestConfiguration` + injected beans:* a context
  startup per spec class buys nothing here — every collaborator is a
  cheap stateless `new`; Spring would add latency (NFR-P1) and turn fast
  unit specs into container tests. Rejected per proposal NG1.
- *Why not static helper class:* a trait keeps call sites unqualified
  (`newAssembly(...)`) matching the existing fixture idiom.

### D2: Placement and naming

`src/test/groovy/com/github/oinsio/gnomish/app/AppAssemblyFixture.groovy`
— same package as all 21 call sites, next to the existing
`TwoInstanceTakeFixture`. Implements FR1. Adapter-layer specs that
construct `FilesExistCheckRunner`/`ShellCommandCheckRunner` directly
(their subject under test) are untouched (NG3).

### D3: API shape — defaults invisible, deviations named

```groovy
trait AppAssemblyFixture {

    /** Implements FR2 of refactor-app-spec-fixtures. */
    FactoryProperties testProperties(Map overrides = [:]) {
        new FactoryProperties(
                overrides.getOrDefault('instanceName', 'test-instance') as String,
                overrides['agentCliBinary'] as String,
                overrides['agentCliEnvPassthrough'] as List<String>,
                null)
    }

    /** Implements FR1 of refactor-app-spec-fixtures. */
    ManualRunAssembly newAssembly(
            InputStream input = new ByteArrayInputStream(new byte[0]),
            PrintStream output = System.out,
            FactoryProperties factoryProperties = testProperties()) {
        new ManualRunAssembly(
                new SystemConsoleIO(input, output),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                new SystemClock(),
                new ThreadSleeper(),
                factoryProperties)
    }
}
```

(Exact signatures may be tuned during implementation to cover every
existing variant; the shape — one assembly factory + one properties
builder, all parameters defaulted — is the decision.) Covers the
observed variants: custom console streams, `INSTANCE_NAME` constants,
fake-agent binary paths, env passthrough. Fresh instances per call, no
trait state (NFR-R1). The trait targets the 100–120-line file budget.

### D4: Migration is mechanical, all sites move

Each of the 21 sites replaces its inline block with `newAssembly(...)`
passing only its genuine deviations; local `new FactoryProperties(...)`
values in the app package collapse into `testProperties(...)` (FR3).
`ManualRunAssemblySpec` also uses the trait — its subject is assembly
*behavior*, not construction. After migration,
`grep -rn "new ManualRunAssembly" src/test/groovy` must return exactly
the one site inside the trait (M1).

## Risks / Trade-offs

- [Hidden defaults obscure what a spec exercises] → defaults mirror
  today's dominant literal values exactly; any deviation must be an
  explicit named argument at the call site (UX1), so review diffs show
  intent.
- [Trait method name clashes with existing spec-local helpers
  (`newAssembly` exists in several spec bases)] → migration deletes the
  local helpers in the same commit; Groovy compilation fails loudly on
  ambiguity, so a clash cannot slip through silently.
- [A future variant needs a collaborator the trait hardcodes (e.g. a
  scripted console)] → `ManualRunAssembly`'s public constructor stays
  available; the trait is a convenience, not a wall. FR3 permits direct
  construction where construction itself is the subject.
- [Behavior drift during mechanical edits] → suite must stay green with
  an unchanged executed-test count after every batch (M2); no assertion
  or test-name edits allowed in this change.

## Open Questions

None blocking. Q2 from the proposal (`FakeAgentSupport` wrapper
duplication) stays deferred to a follow-up change.

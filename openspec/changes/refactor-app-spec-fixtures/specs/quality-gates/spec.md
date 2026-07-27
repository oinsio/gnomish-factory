# Delta spec: quality-gates (refactor-app-spec-fixtures)

## ADDED Requirements

### Requirement: Shared app-layer assembly fixture
The test suite SHALL provide a single shared fixture (a plain Groovy
trait, no Spring test context) that constructs the standard app-layer
engine-assembly collaborator set and its `FactoryProperties` test
values; app-layer specs SHALL obtain the assembly through this fixture
instead of inlining the construction block, keeping exactly one
construction site for the standard set in test sources.
<!-- implements FR1, FR2, FR3 of refactor-app-spec-fixtures -->

#### Scenario: One construction site for the standard assembly
- **WHEN** test sources are searched for direct construction of the
  standard engine assembly (`new ManualRunAssembly`)
- **THEN** exactly one site is found — inside the shared fixture trait

#### Scenario: A spec deviates from the fixture defaults
- **WHEN** an app-layer spec needs a non-default collaborator (custom
  console streams, agent binary, instance name)
- **THEN** it passes the deviation as an explicit named argument to the
  fixture factory method
- **AND** all defaulted parts remain invisible at the call site

#### Scenario: Fixture adoption preserves behavior
- **WHEN** the app-layer specs are migrated to the shared fixture
- **THEN** `./gradlew test` passes with the same number of executed
  tests as before the migration
- **AND** no production source changes and no assertion changes are part
  of the migration

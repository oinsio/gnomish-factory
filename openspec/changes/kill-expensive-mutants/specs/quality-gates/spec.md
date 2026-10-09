# Spec Delta: quality-gates

## ADDED Requirements

### Requirement: Mutation cost report
After every PIT run the build SHALL write a mutation-cost report beside the PIT
report listing each mutant whose test-execution count exceeds a documented
threshold — class, method, line, mutator, count and killing test, sorted by
count descending — and SHALL log one lifecycle line naming the module, the
threshold, the count above it and the most expensive mutant. The report SHALL
never fail the build.
<!-- implements FR3, FR4, NFR-O1, NFR-C1, UX1 of kill-expensive-mutants -->

#### Scenario: Hotspots are listed
- **WHEN** a module's PIT run produced `mutations.xml` with mutants above the
  threshold
- **THEN** `expensive-mutants.txt` beside it lists exactly those mutants, most
  expensive first
- **AND** the build log carries one line with the count above threshold and the
  top mutant as `class.method:line (mutator) — N tests`

#### Scenario: A cheap module reports nothing to look at
- **WHEN** no mutant exceeds the threshold
- **THEN** the report file states that and the log line says zero above threshold

#### Scenario: A skipped gate writes no report
- **WHEN** a module's `pitest` task was skipped (empty scope, no production code)
- **THEN** the report task is skipped as well and the build succeeds
- **AND** a `mutations.xml` an earlier run left in that module's build directory is
  neither read nor described by the report

#### Scenario: Cost never gates
- **WHEN** a mutant needs any number of test executions to die
- **THEN** `check` and `pitestAll` still succeed as long as every mutation was
  killed

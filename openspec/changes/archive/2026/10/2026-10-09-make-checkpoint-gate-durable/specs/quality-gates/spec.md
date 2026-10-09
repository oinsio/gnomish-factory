# Spec Delta: quality-gates

## ADDED Requirements

### Requirement: Forked test JVMs carry no operator-process variables
The test build SHALL remove every `GNOMISH_*` environment variable it did not set itself from the environment of every forked test JVM — each `Test` task and the `pitest` task, whose minions inherit that JVM's environment — so a test observes only the factory variables its own spawner sets on a child, never those of the process that launched the build. The strip SHALL be subtractive: the machine's Docker, Gradle and Testcontainers variables stay, since the test JVM legitimately depends on them, and only the factory's own namespace is removed. The mechanism SHALL be one build-logic owner applied by the shared test conventions beside the pinned test git configuration, so a module gets it with the rest of its test lifecycle from one plugin id, and a functional spec in build-logic SHALL assert that a build launched with such a variable forks a test JVM that does not see it.
<!-- implements FR22 of make-checkpoint-gate-durable -->

#### Scenario: A variable of the launching process does not reach the test JVM
- **WHEN** a build is launched from a process whose environment holds `GNOMISH_DECISION_FILE` and runs a module's `test` task
- **THEN** `System.getenv()` inside the forked test JVM holds no `GNOMISH_*` key

#### Scenario: PIT minions are stripped the same way
- **WHEN** the same build runs a module's `pitest` task
- **THEN** no mutation minion observes the variable

#### Scenario: The machine's tooling variables survive
- **WHEN** the launching process holds `DOCKER_HOST` and `TESTCONTAINERS_RYUK_DISABLED`
- **THEN** the forked test JVM still observes both

# verification/http-check-provider — delta for migrate-verify-to-executor-contract

## MODIFIED Requirements

### Requirement: A built-in http check provider ships in core
Core SHALL ship a built-in `provider: http` external check, registered as the built-in verdict
executor `http` in the executor registry and resolved from it like any other verdict executor.
It is the escape-hatch for third-party CI / quality services reachable over HTTP, symmetric to
the `command` check for local verification — an operator points a stage at a REST endpoint
without writing an adapter.
**Implements**: FR9 of add-plugin-architecture; FR1, FR6 of migrate-verify-to-executor-contract

#### Scenario: http provider resolves like any other
- **WHEN** a stage declares an `external` check with `provider: http`
- **THEN** the loader maps the check to executor `http`, and the engine resolves it from the executor registry — the same resolution path used for every other verdict executor

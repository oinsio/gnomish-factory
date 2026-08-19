# execution-environment — delta

## ADDED Requirements

### Requirement: Denial findings readable through the port
The `TaskExecutionEnvironment` port SHALL expose the environment's egress denial findings as structured findings, so consumers reach denials through the contract without knowing the adapter. Environments without an egress guard SHALL return an empty list. Read-back SHALL be best-effort: an unreadable or missing denial source yields an empty list and SHALL never fail the round, the attempt, or the report.
<!-- implements FR1, NFR-R1 of fix-denial-report-attachment -->

#### Scenario: Sandboxed environment surfaces its guard denials
- **WHEN** a consumer holding the port type asks a sandboxed environment for denial findings after a round with a denied request
- **THEN** it receives the structured denial findings recorded by the guard, without downcasting to any adapter type

#### Scenario: Guard-less environment reports no denials
- **WHEN** a consumer asks a host (non-sandboxed) environment for denial findings
- **THEN** it receives an empty list

#### Scenario: Unreadable denial log degrades to empty
- **WHEN** the guard's denial log is missing or unreadable at read-back time
- **THEN** the port returns an empty list and the round completes normally

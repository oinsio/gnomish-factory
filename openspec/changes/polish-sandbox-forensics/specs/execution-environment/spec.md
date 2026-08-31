## ADDED Requirements

### Requirement: Exit 137 is annotated with the container's OOM state
When a process run through the container adapter's `exec()` exits with code 137, the adapter SHALL read the container's `OOMKilled` runtime state and, when it is `true`, annotate the reported failure with a "likely container OOM" note at the point the exit code is surfaced to the operator. The annotation SHALL be advisory only: the exit code, the failure classification, and attempt accounting are unchanged. The inspect read SHALL be best-effort — an unreadable runtime state yields the unannotated report, never a new failure.
<!-- implements FR1, NFR-R1, NFR-O1 of polish-sandbox-forensics -->

#### Scenario: OOM-killed process is reported as likely OOM
- **WHEN** an in-box process exits 137 and the container's runtime state reports `OOMKilled=true`
- **THEN** the failure reported for that exec carries the "likely container OOM" annotation alongside the exit code, and the failure's classification and the attempt counter are exactly what they would be without the annotation

#### Scenario: Plain kill is not blamed on memory
- **WHEN** an in-box process exits 137 and the container's runtime state reports `OOMKilled=false`
- **THEN** the failure is reported without an OOM annotation

#### Scenario: Unreadable runtime state degrades to no annotation
- **WHEN** an in-box process exits 137 and the container state cannot be inspected
- **THEN** the failure is reported exactly as today, with no annotation and no additional error

### Requirement: Container failures name the container ready-to-paste
Operator-facing failure messages about a task container or its egress guard — container materialization failures, guard start failures, and the notice that a box was kept — SHALL embed the concrete Docker object name the operator would pass to `docker logs` / `docker cp`, not only the environment key it is derivable from. Messages SHALL carry only object names and runtime metadata — never credentials or environment values.
<!-- implements FR2, NFR-S1, UX1 of polish-sandbox-forensics -->

#### Scenario: Materialize failure names the box
- **WHEN** a container materialization step fails against the runtime
- **THEN** the resulting error message contains the task container's concrete name

#### Scenario: Guard failure names the guard container
- **WHEN** the egress guard cannot be brought to a running state
- **THEN** the resulting error message contains the guard container's concrete name

#### Scenario: Keep notice names the kept box
- **WHEN** a run ends by keeping its box (park, abort, or failed self-check)
- **THEN** the operator-facing notice names the kept container, so inspection can start from the message alone

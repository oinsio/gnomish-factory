# subprocess-access-log — delta for add-subprocess-access-log

## Purpose

A structured JSONL access log of every subprocess the factory issues — git,
docker, agent CLI rounds, command checks — one line per execution with
sanitized argv, correlation context, exit code, duration, and termination
kind, so an operator can reconstruct exactly what an autonomous run executed.
Covers factory-issued commands only; it is an operational record, not
forensic evidence.

## ADDED Requirements

### Requirement: One record per subprocess execution
Every factory-issued subprocess execution that reaches a terminal outcome
SHALL append exactly one JSONL record carrying the executable name, the
redacted argv, the working directory, the start timestamp, the measured
duration, the process's exit code when it chose one, the termination kind,
the spawn-family identifier, and the correlation context — for successes and
failures alike, with no per-execution console output at any level.
<!-- implements FR1 of add-subprocess-access-log -->

#### Scenario: Successful git command leaves a record
- **WHEN** a factory git command runs to completion with exit code 0
- **THEN** one access record is appended naming the executable, the redacted
  argv, the working directory, the start timestamp, the duration, exit code
  0, and termination `exited`
- **AND** no console line is produced for it

#### Scenario: Timed-out command records its termination, not a fake exit code
- **WHEN** a bounded command expires on its deadline and its process tree is
  killed
- **THEN** the access record carries termination `timed_out` and the elapsed
  duration, and does not present a signal-derived number as a chosen exit code

#### Scenario: Interrupted command still leaves a record
- **WHEN** a supervised command's wait is interrupted (shutdown, revoked claim)
- **THEN** one access record with termination `interrupted` is appended before
  the seam returns

### Requirement: Coverage spans all four spawn families
Access records SHALL be produced by all four spawn families — the task
execution environment port (agent CLI rounds, command checks, and in-box git,
in host and container modes alike), the factory git runner, the docker
management-command seam, and the git-objects plumbing runner — and by no
other path. The dependency-free supervision module SHALL remain outside the
mechanism: it acquires no logging capability and no dependency.
<!-- implements FR2 of add-subprocess-access-log -->

#### Scenario: Environment exec is covered in both modes
- **WHEN** a command check or an agent round runs through the task execution
  environment on the host or in a container
- **THEN** its completion appends one access record identifying the
  environment spawn family

#### Scenario: Docker management command is covered
- **WHEN** a sandbox lifecycle operation runs a docker management command
- **THEN** its completion appends one access record identifying the docker
  spawn family

#### Scenario: Git-objects plumbing is covered without new coupling
- **WHEN** a git-objects plumbing command completes
- **THEN** one access record identifying the git-objects spawn family is
  appended
- **AND** the git-objects module's production dependency set is unchanged

#### Scenario: The supervision module stays neutral
- **WHEN** the dependency gates run after the access log lands
- **THEN** the supervision module still declares no internal module,
  framework, or logging dependency

### Requirement: One emitter owns format and redaction
All access records SHALL be composed and redacted by a single emitter
component; emission sites hand it structured execution facts and SHALL NOT
compose JSON, choose field names, or scrub argv themselves — every record has
the identical schema regardless of which family wrote it.
<!-- implements FR3 of add-subprocess-access-log -->

#### Scenario: Records from different families share one schema
- **WHEN** records written by the git, docker, environment, and git-objects
  families are compared field by field
- **THEN** they carry the same field names, timestamp format, termination
  vocabulary, and schema version

#### Scenario: A new field lands everywhere at once
- **WHEN** the record schema gains a field in the emitter
- **THEN** records from every family carry it with no per-site change beyond
  supplying the fact

### Requirement: Constructive redaction is a precondition of every record
Argv redaction SHALL be constructive first: environment-variable values
inlined into argv are never logged (variable names only, values replaced by a
placeholder); seams that inject secret values into argv (the AI auth token,
credential-bearing remote URLs) declare those exact values, and the emitter
replaces every occurrence with a placeholder; a structural URL-userinfo scrub
runs as the second net over the whole argv. Redaction SHALL run before
truncation and before any hash over the argv is computed.
<!-- implements FR4, NFR-S1 of add-subprocess-access-log -->

#### Scenario: Docker exec env values never appear
- **WHEN** a container exec passes environment entries on its argv as
  `-e NAME=value` pairs
- **THEN** the record shows each entry's name with a placeholder value, and no
  entry's value appears anywhere in the record

#### Scenario: The injected AI token is unfindable
- **WHEN** an agent round runs with a known fake auth token injected through
  the AI seam and the emitted records are scanned
- **THEN** zero occurrences of the token value are found, and the token's
  position carries the placeholder

#### Scenario: Credential-bearing remote URL is masked structurally
- **WHEN** a git command's argv carries a `scheme://userinfo@host` remote URL
  that no seam declared
- **THEN** the record shows the URL with its userinfo replaced by the mask

#### Scenario: The hash covers redacted content only
- **WHEN** a record is truncated and carries the full-argv hash
- **THEN** the hash equals the hash of the full redacted argv, so the secret
  is not recoverable or confirmable from the hash

### Requirement: OTel-aligned, versioned record vocabulary
Records SHALL use OTel `process.*` semantic-convention field names where one
exists (`process.executable.name`, `process.command_args`,
`process.exit.code`, `error.type`), RFC3339 UTC timestamps, a per-line schema
version, and a closed termination token set `exited | timed_out |
interrupted`. Every wire constant of the termination vocabulary SHALL have a
round-trip mapping spec iterating all constants, no hand-listed subset.
<!-- implements FR5 of add-subprocess-access-log -->

#### Scenario: A line is self-describing
- **WHEN** a collector parses one access line with no other context
- **THEN** the schema version, semconv field names, and UTC timestamps
  identify what it holds and when it happened

#### Scenario: Termination vocabulary cannot drift
- **WHEN** a termination constant is added or renamed on either side of the
  wire mapping
- **THEN** the round-trip spec over all constants fails until both sides agree

### Requirement: Oversized argv truncates with marker and hash
An argv whose rendered length exceeds the record's budget SHALL be truncated
with an explicit truncation marker and a hash of the full redacted argv, so
the record stays bounded while the executed command remains identifiable and
two truncated invocations remain distinguishable.
<!-- implements FR6 of add-subprocess-access-log -->

#### Scenario: A huge argv stays bounded
- **WHEN** a command's redacted argv exceeds the budget
- **THEN** the record carries the truncated prefix, the truncation marker, and
  the full-argv hash, and the line's size stays within the budget

#### Scenario: Within-budget argv is untouched
- **WHEN** a command's redacted argv fits the budget
- **THEN** the record carries it verbatim with no marker and no hash

### Requirement: Dedicated sink isolated from the human log
Access records SHALL flow through a dedicated logger name to their own JSONL
file appender — asynchronous, rotated by UTC day — and SHALL NOT appear in
the human log or on the console; test runs SHALL NOT write access records to
the operator's log directory.
<!-- implements FR7 of add-subprocess-access-log -->

#### Scenario: Access lines never reach the human log
- **WHEN** a run produces access records and the human log file is inspected
- **THEN** no access JSON line appears in it, and no access line reached the
  console

#### Scenario: Overnight history by filename
- **WHEN** an operator asks what the factory executed last night
- **THEN** the answer is one or two files identifiable by their daily UTC
  names

#### Scenario: Test runs stay out of operator files
- **WHEN** the full test suite runs
- **THEN** zero access records are written to the operator's log directory

### Requirement: Correlation context rides every record
Each record SHALL carry the correlation context — task id, stage, attempt,
and the daemon component key where set — copied from the MDC at the emission
seam; where an execution's outcome resolves on a different thread than its
launch, the context SHALL be captured at launch and applied at emission, so
the record is attributable regardless of which thread observed the outcome.
<!-- implements FR8 of add-subprocess-access-log -->

#### Scenario: Records are greppable by task
- **WHEN** an operator filters the access file by one task id after its run
- **THEN** every command the factory issued for that task appears, each with
  its stage and attempt where one was in flight

#### Scenario: Cross-thread outcome keeps its context
- **WHEN** an execution is launched on a round thread and its outcome is
  observed on another thread
- **THEN** the record carries the task id, stage, and attempt that were
  current at launch

### Requirement: Emission is best-effort and free
A failure to compose or write an access record SHALL never fail the command,
the check, the round, or the take, burn a stage attempt, or block the
launching thread beyond the appender hand-off; the emission failure itself
leaves a trace per the logging policy. Producing the log SHALL add zero
tracker API calls, zero AI tokens, and no subprocess.
<!-- implements NFR-R1, NFR-P1 of add-subprocess-access-log -->

#### Scenario: A broken sink harms nothing
- **WHEN** appending an access record fails with an I/O error
- **THEN** the subprocess's own result is returned unchanged, no attempt is
  burned, and the run continues

#### Scenario: Write economy unchanged
- **WHEN** the same task load runs with the access log enabled and disabled
- **THEN** the tracker write count and token spend are identical

### Requirement: The scope claim is honest and written
The capability's documentation SHALL state that the log covers factory-issued
commands only: processes the agent spawns inside its box do not cross a
factory seam and produce no records, with the bounding mechanisms for them
named (egress guard on the network plane; boundary checks and
fast-forward-only harvest on the git plane). The log SHALL claim operational
value only — the factory host is the trusted root, and no tamper-evidence is
claimed.
<!-- implements NFR-S2 of add-subprocess-access-log -->

#### Scenario: In-box spawns produce no records and no false promise
- **WHEN** an agent inside its environment spawns a process of its own
- **THEN** no access record is produced, and the capability's documentation
  states this boundary and the mechanisms that bound in-box effects

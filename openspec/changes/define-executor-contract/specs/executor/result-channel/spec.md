# executor/result-channel — delta for define-executor-contract

## Purpose

How an executor's result reaches the factory: the request on stdin, results in factory-named
files read by one capped reader, logs on stdout and stderr, and the posture that treats every
byte an executor writes as untrusted.

## ADDED Requirements

### Requirement: Request on stdin, results in factory-named files
An external executor SHALL receive its request as one JSON document on stdin and SHALL write
its result, and any answer payload, to paths the factory chose and handed over as `GNOMISH_*`
environment variables. The factory SHALL spawn the executor only through the task execution
environment seam, inside the box when the stage runs in one.
<!-- implements FR5, NFR-S3 of define-executor-contract -->

#### Scenario: Result path is factory-chosen
- **WHEN** an executor run starts
- **THEN** `GNOMISH_RESULT_FILE` names a path under the environment's scratch area that did not
  exist before the run, and the executor's stdin carries the full request

#### Scenario: Executor runs through the environment seam
- **WHEN** a `program` stage runs in container mode
- **THEN** the program is executed inside the task's box with the allowlisted environment and
  the egress guard, and no factory-side process is spawned for it

### Requirement: Stdout and stderr are logs, never protocol
Stdout and stderr SHALL be drained concurrently from launch, capped in bytes and lines, tagged
with the run's correlation id, and never parsed for a result. The one exception is NDJSON
progress on stdout from an executor whose describe declared `streams_progress`; a non-JSON
line there SHALL be logged as a contract violation and skipped.
<!-- implements FR5, NFR-O2 of define-executor-contract -->

#### Scenario: Verbose executor does not deadlock the factory
- **WHEN** an executor writes more than the pipe buffer to stderr before exiting
- **THEN** the run completes, the captured tail is capped, and the result file is read

#### Scenario: Stray print does not corrupt the result
- **WHEN** an executor prints free text to stdout and writes a valid result file
- **THEN** the result is read from the file and the text appears only in the capped log

### Requirement: One capped, inert result reader
Every read of a factory-named result path SHALL go through one reader that enforces a size cap
with a truncation marker, treats bytes as inert data, wraps every text field as untrusted text,
and reports a parse failure as a typed outcome. No other component SHALL read such a path.
<!-- implements FR6, NFR-R1, NFR-S2 of define-executor-contract -->

#### Scenario: Oversized result is truncated, not fatal to the factory
- **WHEN** a result file exceeds the cap
- **THEN** at most the cap is read, truncation is recorded, and the run resolves per the
  truncated content's parse outcome

#### Scenario: Re-read is idempotent
- **WHEN** recovery re-reads the same result files after a factory restart
- **THEN** the same status, answer and usage are produced and no run is repeated

#### Scenario: Second reader is a build failure
- **WHEN** a production class outside the owner opens a `GNOMISH_*` result path
- **THEN** the architecture spec in the composition-root module fails naming the class

### Requirement: Decision and findings files are result files
The agent executor's decision file and the command check's findings file SHALL be read through
the shared result reader with the same cap and untrusted-text posture; their tolerant fallbacks
(raw text as question, synthetic finding) SHALL be preserved.
<!-- implements FR6 of define-executor-contract -->

#### Scenario: Garbage decision file keeps its fallback
- **WHEN** the decision file contains unparseable text
- **THEN** the round resolves as a decision request whose question is the raw text, read through
  the shared reader

### Requirement: Credentials never travel in argv or inherited environment
Credential names an executor declared SHALL be resolved through the secrets port and delivered
only to that executor's process, by the environment seam's credential channel; they SHALL
appear in no argv, no log line, and no other process's environment.
<!-- implements NFR-S1 of define-executor-contract -->

#### Scenario: Declared credential reaches only its executor
- **WHEN** executor A declares `ACME_API_KEY` and a `command` check runs in the same stage
- **THEN** the key is present in A's process and absent from the check's process

#### Scenario: Undeclared credential is refused
- **WHEN** a law declares a credential for an executor whose describe does not list it
- **THEN** loading reports a located error naming the executor and the variable

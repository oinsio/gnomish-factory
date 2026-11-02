# executor/conformance-kit — delta for define-executor-contract

## Purpose

The executable definition of "speaks the executor contract": a command any executor author can
run against a candidate program, plus the reference executor and the misbehaving fake the
factory's own specs use.

## ADDED Requirements

### Requirement: Conformance command drives a candidate program
`gnomish executor verify <program>` SHALL run the candidate through every conformance case and
print one line per case with pass or fail and the first offending detail, exiting non-zero on
any failure. Cases SHALL cover: describe validity, settings-schema rejection, each declared
role, an undeclared role, oversized output, stderr noise, non-JSON stdout, pending then poll,
unknown request fields, malformed answer handling. The kit's core SHALL drive the Java mirror
interface.
**Implements**: FR8, FR12, UX2 of define-executor-contract

#### Scenario: Reference executor passes every case
- **WHEN** the kit runs against the reference executor
- **THEN** every case passes and the exit code is zero

#### Scenario: Misbehaving fake fails each case it is told to break
- **WHEN** the kit runs against the fake configured to break one named case
- **THEN** exactly that case fails with a detail naming the violation

#### Scenario: Role not declared is not exercised
- **WHEN** the candidate's describe lists `verdict` only
- **THEN** the kit runs the `verdict` case and reports the other roles as not applicable

#### Scenario: Undeclared role is refused by the candidate
- **WHEN** the kit requests a role the candidate's describe does not list
- **THEN** the case passes when the candidate writes no `completed` result for it, and fails naming the role when it does

#### Scenario: A built-in executor is driven through the same cases in process
- **WHEN** the kit's core runs against an in-process implementation of the mirror interface
- **THEN** every case that does not need a process or a document on the wire runs against it with the same verdict per case, the four wire-only cases (unknown request fields, oversized output, stderr noise, non-JSON stdout) are reported as not applicable, and no process is spawned

### Requirement: Pending case is gated by the poll capability
The pending-then-poll case SHALL run only against a candidate whose describe declares `poll`,
driven by the settings the command's `--pending-settings` option carries; for a candidate
without `poll` it SHALL be reported as not applicable.
**Implements**: FR12, FR23 of define-executor-contract

#### Scenario: Pending case needs the poll capability
- **WHEN** the candidate's describe declares no `poll` capability
- **THEN** the kit reports the pending-then-poll case as not applicable and sends no poll

### Requirement: Reference executor and misbehaving fake ship with the factory
A reference executor (a committed stand-in preset with no factory dependency) SHALL pass the kit
and SHALL be the executor the factory's own end-to-end specs run; a fake whose presets each break
one named case SHALL exist for negative specs. Both SHALL live outside production modules, in the
stand-in library, and no spec SHALL generate an executor program of its own.
**Implements**: FR12, M1 of define-executor-contract

#### Scenario: Factory end-to-end uses the reference executor
- **WHEN** the packaged-jar end-to-end suite runs a `program` stage
- **THEN** the stage's executor is the reference executor and the round completes

#### Scenario: Fake exercises the limit class
- **WHEN** the fake is told to return `failed/limit`
- **THEN** the engine spec observes a budget-exhausted escalation with no attempt burned

### Requirement: Schema files are the single source of truth
The request, result, answer and describe schemas SHALL live as files in the repository; Java
wire types SHALL round-trip against them in a spec, and the kit SHALL validate candidate output
against the same files, on the raw bytes the factory read, at the execution-environment port.
**Implements**: FR1, FR24, NFR-R3 of define-executor-contract

#### Scenario: Drift between DTO and schema is red
- **WHEN** a wire type gains a field the schema file does not declare
- **THEN** the round-trip spec fails naming the field

#### Scenario: Describe outside the schema fails the describe case
- **WHEN** the candidate's describe carries an enforcement level the schema's enum does not list
- **THEN** the describe case fails with the validator's message, although the factory's own reader would have read the level as `PROMPT_ONLY`

#### Scenario: Unknown request field reaches the candidate
- **WHEN** the kit runs the unknown-request-fields case
- **THEN** the request the candidate receives on standard input carries one field the request schema does not define, and the case passes only if the candidate answers as it does without it

#### Scenario: Observation leaves the factory's reads unchanged
- **WHEN** a case runs against a candidate whose documents all validate
- **THEN** the adapter reads the same result it reads without the kit's observation, and no production reader is configured differently for the kit

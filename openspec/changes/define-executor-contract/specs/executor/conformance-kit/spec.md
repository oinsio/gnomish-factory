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
role, cancel, oversized output, stderr noise, non-JSON stdout, pending then poll, unknown
request fields, malformed answer handling.
<!-- implements FR12, UX2 of define-executor-contract -->

#### Scenario: Reference executor passes every case
- **WHEN** the kit runs against the reference executor
- **THEN** every case passes and the exit code is zero

#### Scenario: Misbehaving fake fails each case it is told to break
- **WHEN** the kit runs against the fake configured to break one named case
- **THEN** exactly that case fails with a detail naming the violation

#### Scenario: Role not declared is not exercised
- **WHEN** the candidate's describe lists `verdict` only
- **THEN** the kit runs the `verdict` case and reports the other roles as not applicable

### Requirement: Reference executor and misbehaving fake ship with the factory
A reference executor (a script with no factory dependency) SHALL pass the kit and SHALL be the
executor the factory's own end-to-end specs run; a fake with scripted misbehaviours SHALL exist
for negative specs. Both SHALL live outside production modules.
<!-- implements FR12, M1 of define-executor-contract -->

#### Scenario: Factory end-to-end uses the reference executor
- **WHEN** the packaged-jar end-to-end suite runs a `program` stage
- **THEN** the stage's executor is the reference executor and the round completes

#### Scenario: Fake exercises the limit class
- **WHEN** the fake is told to return `failed/limit`
- **THEN** the engine spec observes a budget-exhausted escalation with no attempt burned

### Requirement: Schema files are the single source of truth
The request, result, answer and describe schemas SHALL live as files in the repository; Java
wire types SHALL round-trip against them in a spec, and the kit SHALL validate candidate output
against the same files.
<!-- implements FR1, NFR-R3 of define-executor-contract -->

#### Scenario: Drift between DTO and schema is red
- **WHEN** a wire type gains a field the schema file does not declare
- **THEN** the round-trip spec fails naming the field

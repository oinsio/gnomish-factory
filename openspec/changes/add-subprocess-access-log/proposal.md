# Proposal: add-subprocess-access-log

## Why

The factory's whole job is issuing subprocesses — git, docker, agent CLIs,
command checks — yet it keeps no record of what it ran. Every field an audit
record needs is already computed and then thrown away: duration is measured and
discarded on success (`GitProcessRunner`, `DockerCli`, `CommandProcessRunner`),
exit code and a named termination (`EXITED | TIMED_OUT | INTERRUPTED`) come
back from every seam, and argv is deliberately never logged because it can
carry credentials. Today the record is silence on success and a WARN naming
only the subcommand on timeout/interrupt. An operator asking "what exactly did
the factory execute for task X, when, and how did it end?" — the basic
account-of-actions question NIST SP 800-92 puts at the center of log
management, and the question an autonomous system owes its operator more than
any attended tool does — has no answer. The canonical form for the answer
exists (OTel `process.*` semantic conventions for execution events; the
`access.jsonl` pattern as prior art), and the factory already runs a
machine-readable JSON observability plane (ledger, snapshot) this file
naturally joins.

## What Changes

- **ADDED** `subprocess-access-log` capability: a structured JSONL access log
  — one line per factory-issued subprocess execution across all four spawn
  families (the `TaskExecutionEnvironment` port, `GitProcessRunner`,
  `DockerCli`, `GitExec`) — carrying sanitized argv, working directory,
  correlation context, exit code, duration, and termination kind, written
  through a single emitter that owns the format and the redaction, to a
  dedicated Logback JSONL appender.
- **MODIFIED** `module-layering`: the `:logtext` leaf (introduced by
  `harden-logging-observability`) additionally houses the access-log emitter
  and redactor; consumer grants widen from "where they log untrusted text" to
  also cover access-log emission; `:gitobjects` stays dependency-free — it
  reaches the emitter only through a JDK-only observer hook on its own public
  API.

## Capabilities

### New Capabilities

- `subprocess-access-log`: the access-log contract — coverage (which
  executions produce a line), record schema (OTel `process.*` field names,
  RFC3339 UTC timestamps, per-line schema version, termination vocabulary,
  truncation marker with full-argv hash), constructive redaction as a hard
  precondition, the single-emitter ownership rule, the sink (dedicated logger
  name, JSONL appender, rotation, test isolation), and the best-effort
  failure stance.

### Modified Capabilities

- `module-layering`: the `:logtext` consumer grant is widened to access-log
  emission, and the `:gitobjects` extraction contract is pinned against the
  new hook (no new dependency edge).

## Goals

- **G1** Every subprocess the factory issues — success and failure alike —
  leaves exactly one machine-readable record answering what ran, where, for
  which task/stage/attempt, how long, and how it ended.
- **G2** No credential material can reach the access log: redaction is
  constructive (the seams that inject secrets declare them) with a structural
  scrub as a second net, never a pattern-guess alone.
- **G3** One component owns the record format and the redaction; the four
  emission sites cannot drift into private formats or private scrubbing.
- **G4** The log is operationally free: producing it adds no tracker calls,
  no AI tokens, no new subprocess, and its failure never harms task work.

## Non-Goals

- **NG1** Observing processes the agent spawns *inside* the box: they do not
  cross any factory seam, and in-box exec-tree observation is eBPF/auditd
  territory. Their effects are already bounded by the egress guard on the
  network plane and by boundary checks plus the fast-forward-only harvest on
  the git plane. The log covers factory-issued commands only, and says so.
- **NG2** Forensic or tamper-evident logging: the factory host is the trusted
  root; this is an operational log, and no integrity-chain claims are made.
- **NG3** A reader, dashboard, or query tool over `access.jsonl` — `jq`/`grep`
  are the readers; the dashboard may grow one later, separately.
- **NG4** Capturing subprocess *output* (stdout/stderr content) in the access
  log — the record describes the execution, not its data; output stays on its
  existing paths (captures, tails, transcripts).
- **NG5** A new module: the emitter and redactor live in the `:logtext` leaf
  that `harden-logging-observability` introduces; this change adds no module.

## Users & Scenarios

- **U1 Operator auditing a run**: after a serve day, runs
  `jq 'select(.taskId=="T-42")' access-*.jsonl` and reads every command the
  factory issued for that task in order, each with duration and exit code —
  including the docker exec lines whose env values never appear.
- **U2 Post-mortem investigator**: a task aborted on a timed-out push;
  the access line shows the exact (redacted) argv, the elapsed time against
  the deadline, and `termination: timed_out` — no reproduction needed.
- **U3 Security reviewer**: greps the access log produced by an E2E run that
  injected a known fake token and finds zero occurrences of the token value;
  every secret-bearing position shows the placeholder.

## Requirements

### Functional

- **FR1** Every factory-issued subprocess execution that reaches a terminal
  outcome SHALL append one JSONL record carrying: the executable name, the
  redacted argv, the working directory, the start timestamp, the measured
  duration, the exit code (when the process chose one), the termination kind,
  the spawn-family identifier, correlation context, and a per-line schema
  version.
- **FR2** Coverage SHALL span the four spawn families with no fifth path: the
  `TaskExecutionEnvironment` port (agent CLI rounds, command checks, in-box
  git — host and container modes alike) via a decorator over the port;
  `GitProcessRunner` (factory git); `DockerCli` management commands (sandbox
  lifecycle); and `GitExec` (`:gitobjects` plumbing) via a dependency-free
  observer hook. The `:subprocess` module stays out: its neutrality contract
  forbids logging and dependencies, and `CaptureRunner` is a record with
  nothing to decorate.
- **FR3** One emitter SHALL own the record format and the redaction; emission
  sites hand it a structured record and SHALL NOT compose JSON, choose field
  names, or scrub argv themselves.
- **FR4** Redaction SHALL be constructive first: the seams that inject secret
  values into argv (the AI auth token inlined by `docker exec -e`,
  credential-bearing remote URLs) declare those exact values to the emitter,
  which substitutes a placeholder wherever they occur; environment-variable
  values passed on argv are never logged (names only); a structural URL
  userinfo scrub runs as the second net. Redaction runs before truncation and
  before the full-argv hash is taken.
- **FR5** The record vocabulary SHALL use OTel `process.*` semantic-convention
  names where one exists (`process.executable.name`, `process.command_args`,
  `process.exit.code`, `error.type`), RFC3339 UTC timestamps, and a closed
  termination token set (`exited | timed_out | interrupted`) with a round-trip
  spec over every constant.
- **FR6** An argv exceeding the length budget SHALL be truncated with an
  explicit truncation marker and a hash of the full redacted argv, so the
  record stays bounded while the executed command stays identifiable.
- **FR7** The sink SHALL be a dedicated SLF4J logger name routed by bootstrap
  Logback configuration to a JSONL file appender — asynchronous, daily
  rotation, additivity off so access lines never enter the human log, and the
  test configuration keeps them out of the operator's files.
- **FR8** Correlation context (`taskId`, `stage`, `attempt`, and the daemon
  `component` key where set) SHALL be copied from the MDC at the emission
  seam; where the outcome resolves on a different thread than the launch, the
  MDC SHALL be captured at launch and applied at emission.

### Non-Functional Reliability

- **NFR-R1** Emission is best-effort: a failure to compose or write a record
  SHALL never fail the command, the check, the round, or the take, burn a
  stage attempt, or block the launching thread beyond the appender's queue
  hand-off; the failure itself leaves a trace per the logging policy.

### Non-Functional Observability

- **NFR-O1** Each of the four emission paths is contract-tested: a spec per
  family asserts that a completed execution produces exactly one record with
  the required fields, and that timeout and interrupt outcomes carry their
  termination kind.

### Non-Functional Security

- **NFR-S1** No credential material in any access record: no environment
  values, no URL userinfo, no declared secret value — asserted by specs that
  inject known fake secrets through each secret-bearing seam and scan the
  emitted records. This is the access log's own stance, matching the
  ledger/snapshot rule of `serve-observability` NFR-S1.
- **NFR-S2** The log's scope claim is honest and written: factory-issued
  commands only; agent-spawned in-box processes are documented as out of
  scope together with the mechanisms that bound them (egress guard, boundary
  checks, ff-only harvest).

### Non-Functional Performance

- **NFR-P1** Producing the log adds zero tracker API calls, zero AI tokens,
  and no subprocess; the per-execution cost is one in-memory record and one
  async appender hand-off.

### Non-Functional Cost

Considered: no token or API spend is possible on any path (NFR-P1 covers the
only budgets this change touches); no separate cost requirement.

## Operator Experience Criteria

- **UX1** The console is unchanged: the access log adds no console output at
  any level; a healthy run looks exactly as it did.
- **UX2** The access file is self-describing: an operator with `jq` and no
  documentation can read a line and name the command, the task, the duration,
  and the outcome; `schema_version` tells a tool what it is parsing.

## Success Metrics

- **M1** 100% of the four spawn families emit — verified by one contract spec
  per family (NFR-O1) and by an E2E run whose access file contains git,
  docker, environment-exec, and gitobjects lines.
- **M2** Zero secret leaks: the E2E and per-seam specs that inject known fake
  credentials find zero occurrences of any injected value in the emitted
  records; every secret position carries the placeholder.
- **M3** The termination round-trip spec covers every constant of the wire
  vocabulary with no hand-listed subset, and the module's mutation gate stays
  at 100%.
- **M4** Zero access-log lines in the operator's human log file and zero
  written by `./gradlew check` to the operator's log directory.

## Open Questions

- **Q1** Retention for `access-*.jsonl` — reuse the human log's
  rotation/retention settings or a dedicated cap; resolved in design (D6):
  Logback time-based rotation with its own bounded history, no reader, no
  recovery.
- **Q2** Whether abandoned exec handles (started but never awaited) warrant a
  start-event line — resolved in design (D2): one line per resolved outcome;
  an unawaited handle is a factory bug the supervision contract already
  precludes on every current path.

## Impact

- **Modules touched**: `:logtext` (emitter, redactor, record value —
  introduced by `harden-logging-observability`; this change sequences after
  it or coordinates on that module's landing), `bootstrap` (Logback appender,
  logger routing, test config, gitobjects hook wiring), `:adapters:git`
  (`GitProcessRunner` emission, secret-value declaration for remote URLs),
  `:sandbox:docker` (`DockerCli` emission, `AuditedEnvironment` decorator,
  env-value redaction hand-off), `:gitobjects` (JDK-only observer hook on the
  public entry point — no dependency added), `:adapters:agent` (AI-seam
  secret-value declaration).
- **Dependencies**: no new external dependencies; `:logtext` stays
  slf4j-api-only (JSON lines are composed by hand, no Jackson below the
  application layer).
- **Sequencing**: after `harden-logging-observability` (this change's emitter
  home is that change's `:logtext` module and its Logback hardening is the
  config this change extends). References that change; duplicates nothing
  from it.
- **Not touched**: `:subprocess` (neutrality contract), `CaptureRunner`,
  `:domain`, tracker adapters, the ledger/snapshot plane.

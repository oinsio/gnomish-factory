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
- **MODIFIED** `execution-environment`: the container exec seam stops
  rendering environment values into the `docker exec` argv (secret-free
  wrapper argv at the source); the environment self-check runs its probes
  through the decorated exec seam instead of the raw adapter; the "sole
  process-launch seam" claim is narrowed to name its two disclosed bypasses
  (the container file channel, the git `ext::` harvest transport) and is
  enforced by a widened spawn-boundary gate.
- **MODIFIED** `factory-logging`: the untrusted-text rule is tightened — the
  findings sanitizer prepares no log-line text outside the findings funnel
  (the judge-verdict extraction WARN moves to the logging choke point), and
  the untrusted-log-text gate learns the local-string shape that let that
  site bypass it.
- **MODIFIED** `subprocess-supervision`: the three silently unbounded waits
  found by the systemic audit (container file channel, environment
  self-check probes, in-box service git commands) gain deadlines, and the
  docker runtime probe uses the operator-configured docker-command timeout;
  the factory's git and docker client subprocesses start from a cleared
  environment instead of inheriting every factory credential.

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
- `execution-environment`: secret-free container exec argv, probes through
  the decorated seam, honest and gate-enforced sole-seam wording.
- `subprocess-supervision`: bounded file-channel/probe/in-box-git waits,
  configured probe timeout, minimized child environments for git/docker
  clients.
- `factory-logging`: the findings sanitizer barred from log-line text outside
  the findings funnel; the untrusted-log-text gate extended to the
  local-string shape.

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
- **G5** The container exec wrapper argv carries no secret values at all
  after this change — env moves off the argv at the source, so log redaction
  becomes the second net, not the only control.
- **G6** No factory-issued subprocess wait on the covered seams is silently
  unbounded: every wait either has a deadline or carries a written
  justification for not having one.

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
- **NG6** The rest of the secrets-hardening backlog: `HOME` inheritance on
  host mode, the declaration-dependency of credential stripping, and gateway
  virtual keys stay with `add-sandbox-hardening`; host-mode in-box
  reachability of the origin credential is documented here (NFR-S3), not
  re-engineered.

## Users & Scenarios

- **U1 Operator auditing a run**: after a serve day, runs
  `jq 'select(.taskId=="T-42")' access-*.jsonl` and reads every command the
  factory issued for that task in order, each with duration and exit code.
  Gnome processes appear as environment-family lines showing the logical
  in-box command and a `mechanism` field (`host`/`container`); the physical
  `docker exec` wrapper argv — the only argv that ever carried env values —
  is not logged by any family, and env variable names appear without values.
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
- **FR2** Coverage SHALL span the four spawn families through five emission
  sites: the `TaskExecutionEnvironment` port (agent CLI rounds, command
  checks, in-box git — host and container modes alike, self-check probes
  included) via a decorator over the port; `GitProcessRunner` (factory git);
  `DockerCli` management commands (sandbox lifecycle); the container file
  channel's own `docker exec` spawns (docker family, emitted where the
  channel resolves its wait); and `GitExec` (`:gitobjects` plumbing) via a
  dependency-free observer hook. The git `ext::` harvest transport's docker
  grandchild is represented by its git-family record. The `:subprocess`
  module stays out: its neutrality contract forbids logging and
  dependencies, and `CaptureRunner` is a record with nothing to decorate.
- **FR3** One emitter SHALL own the record format and the redaction; emission
  sites hand it a structured record and SHALL NOT compose JSON, choose field
  names, or scrub argv themselves.
- **FR4** Redaction SHALL be constructive first: seams that hold secret
  values (the AI auth token, credential-bearing remote URLs) declare those
  exact values to the emitter, which substitutes a placeholder wherever they
  occur in any record; environment-variable values are never logged on any
  family (names only); a structural URL userinfo scrub and an env-span
  (`-e NAME=value`) scrub run as second nets over every rendered argv, even
  though after FR9 and FR14 no first-net path should produce such an argv.
  Redaction runs before truncation and before the full-argv hash is taken.
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
- **FR9** For gnome-product processes the environment-family record SHALL be
  the sole access record of the execution and SHALL describe the logical
  in-box command, carrying a `mechanism` token (`host | container`), the
  container identity where one exists, and a generated execution id for
  correlation with the session's other artifacts; the physical `docker exec`
  wrapper argv SHALL NOT be logged by any family.
- **FR10** The container file channel's `docker exec` spawns SHALL each emit
  one docker-family record at the channel's own wait resolution, handing
  structured facts to the shared emitter like every other site.
- **FR11** A spawn that fails to start (no process ever exists) SHALL emit
  one record whose termination is `start_failed`, with no exit code; the
  termination vocabulary and its round-trip spec extend to this
  emitter-owned token.
- **FR12** The auditing decorator SHALL be applied directly around each raw
  environment adapter (innermost, at all four wiring points), and the
  environment self-check SHALL exec through the decorated seam, so probe
  executions — the first commands a new box runs — are recorded.
- **FR13** The no-fifth-path claim SHALL be enforced mechanically: the
  process-spawn boundary gate enumerates every allowed spawn site (the two
  environment adapters, `DockerCli`, `CaptureRunner`'s consumers,
  `GitExec`, the container file channel) and fails the build on any other
  `ProcessBuilder` use; the sole-seam wording in the port javadoc and the
  capability spec SHALL name the disclosed bypasses (file channel, git
  `ext::` grandchild).
- **FR14** The container exec seam SHALL NOT render environment values into
  argv: env entries pass as value-less flags with values supplied through
  the docker client process's own environment, or an equivalent no-argv
  mechanism, so the wrapper argv is secret-free at the source.
- **FR15** The factory's git and docker client subprocesses SHALL start from
  a cleared environment carrying only the variables each client needs,
  instead of inheriting the factory's full environment with every credential
  in it.
- **FR16** The judge-verdict extraction WARN SHALL sanitize the raw model
  message through the logging choke point (strip, cap, flatten), and the
  untrusted-log-text gate SHALL be extended to catch the local-string shape
  that let this site bypass it.

### Non-Functional Reliability

- **NFR-R1** Emission is best-effort: a failure to compose or write a record
  SHALL never fail the command, the check, the round, or the take, burn a
  stage attempt, or block the launching thread beyond the appender's queue
  hand-off; the failure itself leaves a trace per the logging policy.
- **NFR-R2** No silently unbounded wait on the covered seams: the container
  file channel's waits, the environment self-check probes, and in-box
  service git commands SHALL be bounded with deadlines whose expiry follows
  each seam's standard timeout handling; the docker runtime probe SHALL use
  the operator-configured docker-command timeout; any wait that remains
  unbounded SHALL carry a written justification at the call site.

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
- **NFR-S3** Host-mode posture is documented where operators read it: the
  in-box reachability of the origin remote credential through the worktree's
  git config (absent in container mode, where the seed clone removes the
  remote) SHALL be stated alongside the capability-passport wording and the
  observability operator guide.

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
- **M5** Zero env values on the container exec argv: a spec over the
  composed `docker exec` argv finds value-less env flags only, and the E2E
  fake-token run finds zero token occurrences in any process argv the
  factory composed.
- **M6** Every newly bounded wait goes red on virtual time: a spec per seam
  (file channel, self-check probes, in-box git, runtime probe) proves the
  deadline expires instead of hanging.

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
  logger routing, test config, gitobjects hook wiring, widened
  process-spawn boundary gate, extended untrusted-log-text gate),
  `:adapters:git` (`GitProcessRunner` emission and child-env minimization,
  secret-value declaration for remote URLs), `:sandbox:docker` (`DockerCli`
  emission and child-env minimization, `AuditedEnvironment` decorator
  applied innermost, `DockerCommands.exec` env-off-argv, file-channel
  emission and deadlines, self-check probes through the decorated seam,
  runtime-probe configured timeout), `:gitobjects` (JDK-only observer hook
  on the public entry point — no dependency added), `:adapters:agent`
  (AI-seam secret-value declaration, judge-verdict WARN sanitization),
  `docs` (host-posture and scope statements).
- **Dependencies**: no new external dependencies; `:logtext` stays
  slf4j-api-only (JSON lines are composed by hand, no Jackson below the
  application layer).
- **Sequencing**: after `harden-logging-observability` (this change's emitter
  home is that change's `:logtext` module and its Logback hardening is the
  config this change extends). References that change; duplicates nothing
  from it.
- **Not touched**: `:subprocess` (neutrality contract), `CaptureRunner`,
  `:domain`, tracker adapters, the ledger/snapshot plane.

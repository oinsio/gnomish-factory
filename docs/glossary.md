# Glossary

The normative dictionary of the project's ubiquitous language. If a term is
here, code, specs, docs, and discussions use it with exactly this meaning and
name — a domain class, port, or field carrying a different name for a glossary
concept is a bug. Terms are grouped by bounded context: a term's definition
holds within its context. The usage rules ("No jargon" invariant, adding new
terms) live in `.claude/rules/process-invariants.md`.

## How to read an entry

**Term** — definition. Optional parts: *Not:* — a boundary or counter-example;
*Never:* — banned synonyms that must not appear anywhere, discussions included.

## Core

- **Factory** — the orchestrator process: takes tasks from the tracker, runs
  pipeline stages, pushes results. Instances are stateless and interchangeable.
- **Gnome** — the AI agent working on one task inside the factory's pipeline.
  *Not:* the agent CLI tool itself (that is a mechanism the gnome runs on).
- **Task** — the unit of work taken from the tracker; owns a task branch and a
  state file. *Never:* issue, ticket (those are tracker-adapter internals).
- **Tracker** — the external task coordination system behind the tracker port
  (GitHub built, in-memory reference for tests, Jira planned).

## Task coordination

- **Claim** — the tracker-visible record that a factory instance holds a task.
- **Lease** — the claim's liveness contract: it must be re-asserted by beats
  or it goes stale (ADR 0002).
- **Beat** — one heartbeat: a PATCH to the instance's own claim comment,
  carrying human-readable progress.
- **Stale claim** — a claim whose version stayed unchanged for a TTL measured
  on the observer's own clock; eligible for takeover. *Not:* judged by
  comparing timestamps across instances.
- **Reaper** — the standing duty that finds stale claims: each tick it lists
  the open tasks, checks every `Working` claim against the TTL, and returns
  stale-claimed tasks to `Ready`. It never claims a task for itself.
- **Zombie** — a former claim holder whose lease went stale (or was reaped)
  while its process may still be running; its writes are stopped by the fence,
  not by asking it to stop.
- **Fence** — the mechanism that stops a stale holder's writes: the task
  branch's non-fast-forward push. The task branch is never force-pushed.
- **Park** — set the task to a waiting tracker status (escalation or a manual
  checkpoint); **release** — give the claim up so any instance may take over.
- **Escalation** — handing a task to a human via tracker status, with the
  findings history attached.
- **Resume** — any instance continuing a task from its branch and state file;
  requires no hand-off from the previous holder.
- **Task branch** — the git branch holding the task's artifacts and state
  file; the single source of truth for task progress.
- **State file** — the machine-readable task state committed to the task
  branch after every attempt.
- **Round** — one iteration of the factory's execution loop for a task; round
  boundaries are where claim-loss and staleness decisions take effect.

## Pipeline execution

- **Pipeline** — the ordered stages a task passes through, defined
  declaratively in the target repo under `.gnomish/`.
- **Stage** — one pipeline step, described as IDEF0/ICOM + Quality Control
  (see `.claude/rules/stage-description.md`).
- **The law** — the `.gnomish/` pipeline definition and rules, taken from the
  factory's trusted clone; the gnome cannot edit its own acceptance criteria.
- **Executor** — the mechanism that runs a stage: `api` (direct model calls)
  or `agent-cli` (an agent CLI as a subprocess).
- **Attempt** — one counted try of a stage; only quality failures burn
  attempts, and every attempt is committed to the task branch. *Never:* retry
  for this counted unit (a retry is the uncounted repeat of an infrastructure-
  failed check).
- **Verify check** — one entry of a stage's ordered verification list:
  built-in declarative, `command`, `external`, or `judge`.
- **Judge** — LLM-as-judge verification via the `JudgeVoter` port: acceptance
  criteria in, structured verdict (`passed`, `findings[]`) out.
- **Findings** — structured, machine-readable observations reported to the
  tracker: message, optional location, optional details. Most come from a
  failed or noteworthy check and are fed back to the gnome on retry; a
  **denial** is a finding no check produced, so it is reported but never fed
  back.
- **Quality failure** — an explicit non-pass verdict (red tests, judge
  findings); burns an attempt. **Infrastructure failure** — a verdict could
  not be obtained (network, 5xx); never burns an attempt.
- **Advancement** — what happens after a stage verifies: `auto` (proceed) or
  `manual` (park at a checkpoint until a human returns the task).
- **Reference file** — an approved sample committed for equivalence tests
  (e.g. `status-report-v1.reference.json`). *Never:* golden.

## Sandbox

- **Box** — the disposable isolated execution environment a gnome runs in;
  destroyed after the task. *Not:* a synonym for container — a box may be a
  container or a VM.
- **Passport (capability passport)** — the capability declaration a sandbox
  backend ships with its binding (isolation level, egress control, ...). A
  stage's needs are reconciled against the bound adapter's passport,
  fail-closed; the factory core owns the trust table mapping each trusted
  binding id to the passport it is expected to declare.
- **Guard** — the egress proxy on the host (mitmproxy-based); the only
  network exit the box has, enforcing a default-deny allowlist and logging
  denials.
- **Allowlist** — the explicit list of permitted egress destinations; the
  default is deny. *Never:* whitelist.
- **Denial** — one egress attempt the guard refused, recorded as a finding
  (destination host, port, and for plain HTTP the method and query-free path —
  never a request body). A denial is observability, not a gate: it rides the
  attempt it happened in into the report and changes no verdict, no stage
  outcome, and no retry feedback. *Never:* block, violation.
- **Denial cursor** — the read position in a guard's denial log, paired with
  the identity of the guard container it was read from. It defines which
  denials belong to the round asking: each read starts where the previous one
  ended. Committed in `state.json` with the attempt it delimits, because the
  guard container outlives the factory process; a resuming instance offers it
  back, and the guard applies it only if it names the container now live.
  *Never:* offset, bookmark, watermark.
- **Artifact depot** — a host-side proxy for package registries; the box
  talks only to it, and it alone talks to the upstream registries.
- **Docker-strategy ladder** — the ordered escalation of ways to give a task
  Docker without handing over the host: from CI-hosted checks (step 0) up to
  a real Docker daemon inside a per-task VM (step 3).
- **Fail-closed** — when protection cannot be established or verified, the
  task does not start; never "run unprotected".
- **Ownership asymmetry** — the repo may only tighten its sandbox; adapter
  bindings and any loosening are operator-only; reconciliation is fail-closed.

## Abbreviations

| Abbreviation | Meaning                                                                    |
|--------------|----------------------------------------------------------------------------|
| ADR          | Architecture Decision Record (`docs/adr/`)                                 |
| AI           | artificial intelligence                                                    |
| API          | application programming interface                                          |
| cgroups      | Linux control groups — kernel mechanism for resource limits                |
| CI           | continuous integration                                                     |
| CRI          | Container Runtime Interface (how Kubernetes drives containers on a node)   |
| CVE          | Common Vulnerabilities and Exposures — public vulnerability identifier     |
| DinD         | Docker-in-Docker                                                           |
| DNS          | Domain Name System                                                         |
| FR / NFR     | functional / non-functional requirement (see `.claude/rules/traceability.md`) |
| GHA          | GitHub Actions                                                             |
| ICOM         | Input, Control, Output, Mechanism — the IDEF0 box interfaces               |
| IDEF0        | Integration Definition for Function Modeling — the stage-description model |
| k8s          | Kubernetes                                                                 |
| L7           | network layer 7, the application layer (HTTP methods, paths)               |
| MITM         | man-in-the-middle — an intermediary that decrypts and re-encrypts traffic  |
| OSS          | open-source software                                                       |
| PRD          | Product Requirements Document (a change's `proposal.md`)                   |
| QEMU         | Quick Emulator — software virtualization backend, weaker isolation than vz |
| RCE          | remote code execution                                                      |
| SSRF         | server-side request forgery                                                |
| TLS          | Transport Layer Security                                                   |
| TOCTOU       | time-of-check to time-of-use — a race between a check and the acting on it |
| TTL          | time to live — the staleness threshold of a lease                          |
| VM           | virtual machine                                                            |
| vz           | Apple Virtualization.framework backend — hardware virtualization on macOS  |

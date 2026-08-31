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
- **Fence** — a mechanism that stops a stale holder's writes. Two of them: the
  task branch's non-fast-forward push (the task branch is never force-pushed),
  and the **claim epoch** — a holder that cannot confirm its own heartbeat
  self-fences, writing nothing past the next round boundary until it
  re-verifies its claim, and any artifact carrying an older epoch than the
  live claim is classified as stale rather than obeyed.
- **Delivery fence** — the check that the task branch tip is on `origin`
  before a signal that depends on it is sent: verify the remote tip, push,
  one bounded re-attempt, then a delivered/undelivered verdict. Used before a
  park's tracker write and before an external check's poll loop. *Not:* the
  **Fence** above — that one stops a zombie's writes, this one makes sure a
  reader on another machine can see what a signal refers to. A delivery fence
  never blocks the thing it guards: an undelivered verdict is surfaced, not
  raised.
- **Touchpoint** — a point where an instance already has a task in hand and
  can therefore reconcile its replication for free: resume start and a run's
  terminal boundary. Deliberately not a timer or a daemon — the next instance
  to touch the task is the delivery vehicle for a push an earlier one lost.
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

## Crash consistency

The vocabulary of recovery from a crash inside a multi-step transition. The
principle and the mechanisms live in `docs/adr/0003-crash-consistency.md`; the
checklist for new transitions lives in `.claude/rules/crash-consistency.md`.

- **Branch shape** — the classification of a task branch tip: its file set,
  envelope versions, and claim epoch mapped to exactly one name from a closed
  set. Total by construction — every combination classifies, `Unknown`
  included, and classification never throws on content. The closed set and the
  meaning of each name are owned by the `task-branch-contract` capability, in
  its "Total branch-shape classification" requirement
  (`openspec/specs/task-branch-contract/spec.md`); it is the only place the
  table lives. Recovery owner and roll-forward/discard disposition per shape
  live in `docs/adr/0003-crash-consistency.md`. *Never:* `Escalated` for the
  `Parked` shape (that name belongs to a `TaskOutcome` variant), `Decision`
  for the `Answered` shape (that name belongs to the human's answer record).
- **Tracker shape** — the same total classification applied to the tracker
  medium: state labels, claim footprint, and boundary markers mapped to
  exactly one name, `Foreign` included for out-of-protocol combinations.
  Adapters report facts; the classification happens in core. The closed set and
  its table are owned by the `claim-heartbeat` capability, in its "Total
  tracker-shape classification with one recovery owner" requirement
  (`openspec/specs/claim-heartbeat/spec.md`).
- **Sweep universe** — the set of tasks a sweep's own listing queries
  enumerate (the union of the ready and open listings). The ordering rule that
  keeps every kill window inside it: the label write admitting a task into the
  universe comes first in its sequence, the label write removing it comes
  last, truth markers land in between. Markers are the truth; labels are the
  index. *Not:* the **Sweep** of Sandbox lifecycle below, which enumerates
  Docker objects; this universe is a set of tracker tasks.
- **Recovery owner** — the single component responsible for converging one
  shape to a clean state, and whether it rolls the transition forward or
  discards back to a known-good tip. Exactly one per shape; two owners for one
  shape is a bug.
- **Claim epoch** — the monotonically increasing token issued with every
  (re)claim (the tracker-assigned claim comment id), recorded with the claim
  and stamped into every commit and tracker write of that tenure. It carries
  only task identity and counters. It makes a zombie's writes detectable and
  classifiable, not impossible — see **Fence**.
- **Intent / receipt** — the two durable records bracketing an external
  effect: the intent is written before the effect, the receipt after it.
  Recovery finding an intent without a receipt probes the target to see
  whether the effect happened before re-driving it.
- **Quarantine** — the automatic-recovery kind of **park**: a task moved to
  the needs-human status because recovery cannot proceed — a non-recoverable
  shape on first classification, or an exhausted recovery budget. Its report
  names the shape, the diagnosis, and the attempts consumed. *Not:* an
  escalation raised by the gnome's own work, which reports findings rather
  than a shape.

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

## Sandbox lifecycle

- **Sweep** — the periodic evaluation that decides, per Docker object, whether
  it is alive, kept, or orphaned, and acts accordingly (stop, dispose, or
  leave untouched). `run`/`take` run one sweep pass at startup; `serve` runs
  it on a recurring tick.
- **Sweep verdict** — the one classification a sweep emits per evaluated
  object: `checked-alive`, `kept-under-threshold`, `stopped-orphan`,
  `disposed-aged`, `disposed-reconstructible`, or `skipped-no-verdict`.
- **Environment reaper** — the sweep's aged-disposal step: it disposes kept
  environments (stopped boxes, container-less remnants) past the configured
  reap age. *Not:* the **reaper** of Task coordination above, which reaps
  stale tracker claims, not Docker objects. The two govern disjoint
  populations and neither can act on the other's, but they are not fully
  independent: on `serve` the sweep's **liveness oracle** reads the claim
  reaper's most recent open-task listing and its staleness memory rather than
  issuing a tracker call of its own, so a claim reaper that cannot list leaves
  the sweep with no verdict (fail-closed) until it lists again.
- **Kept environment** — a task's stopped box with its volume and network
  retained, deliberately or after a sweep-initiated stop, so a later resume
  can salvage it. *Not:* a running environment (that is simply "alive"), and
  not a disposed one.
- **Salvage** — recovering un-harvested work from a kept environment's volume
  on resume, rather than starting over from the branch's last commit.
- **Ownership mode** — the `tracked` (claimed through the tracker — `take`,
  `serve`) or `manual` (`gnomish run`, no tracker) label stamped on every
  object at creation, deciding whether the sweep judges it by claim liveness
  or by age alone. *Not:* **Ownership asymmetry** of Sandbox above, which is
  about who may bind or loosen a sandbox adapter, not about Docker object
  cleanup.
- **Project identity** — the label scoping a sweep to its own project: a
  stable digest of the clone's **normalized** `origin` remote URL, or an
  explicit operator override. Normalization removes the URL's userinfo,
  lower-cases scheme and host, drops the scheme's own default port, a trailing
  `/` and a trailing `.git`, and renders the scp-style `[user@]host:path` form
  as its `ssh://host/path` equivalent — so a credential rotation or a cosmetic
  respelling of one remote does not re-partition the project. Objects labelled
  with a different project identity are invisible to listing and never touched.
- **Legacy identity** — the digest of the *raw*, un-normalized `origin` URL,
  which objects created before normalization still carry. A sweep whose
  identity derives from `origin` lists the legacy identity alongside its own
  for as long as the two differ, so those objects stay in scope instead of
  being orphaned; new objects are stamped with the project identity only, and
  no object is ever relabelled. Absent when an override is set, when there is
  no `origin`, and once the two digests agree.
- **Remnant** — a container-less Docker object (a volume or network whose
  container is gone) left by a partial materialize or dispose; governed by
  the same aged-reap policy as a kept environment, never disposed on sight.
- **Minimum age** — the grace period after creation during which an object is
  never touched by a sweep regardless of verdict, protecting a still-launching
  slot from a concurrent tick.
- **Liveness oracle** — the sweep's source of truth for whether a `tracked`
  object's task is alive: the claim-heartbeat lease (fresh heartbeat = alive,
  stale = dead), the same mechanism the claim reaper already uses. *Never:* an
  instance registry or lock file — the sweep has no liveness source beyond
  the tracker's own claim heartbeat.

## Subprocess supervision

- **Subprocess supervisor** — the factory's one wait/kill/drain discipline for
  the OS processes it launches (git, docker, agent CLIs, verify commands):
  output drained concurrently with the running process, an optional hard
  deadline on the wait, and on expiry or interruption a two-phase kill of the
  whole process tree followed by a reap. It owns mechanics only — logging,
  output caps, stdin feeds, credential scrubbing and what an exit code means
  stay with the caller. *Not:* a supervisor in the process-manager sense — it
  never restarts anything.
- **Termination** — how a supervised invocation ended, named separately from
  the exit code: `EXITED` (the process chose its own code), `TIMED_OUT` (the
  deadline expired and the tree was killed), `INTERRUPTED` (the waiting thread
  was interrupted and the tree was killed). *Never:* a sentinel exit code such
  as `-1` for interruption — collapsing the three into one number is the defect
  the term exists to remove.
- **Drain** — one output stream read on a thread of its own, concurrently with
  the running process, so neither a full OS pipe buffer nor a child that never
  closes its stdout can block the wait.
- **Kill grace** — the bounded wait between the cooperative terminate and the
  forced kill, so git and docker can remove their lock and temporary files on a
  catchable signal. A bound on waiting, not a sleep: a tree that stops early
  returns early.

## Abbreviations

| Abbreviation | Meaning                                                                       |
|--------------|-------------------------------------------------------------------------------|
| ADR          | Architecture Decision Record (`docs/adr/`)                                    |
| AI           | artificial intelligence                                                       |
| API          | application programming interface                                             |
| cgroups      | Linux control groups — kernel mechanism for resource limits                   |
| CI           | continuous integration                                                        |
| CRI          | Container Runtime Interface (how Kubernetes drives containers on a node)      |
| CVE          | Common Vulnerabilities and Exposures — public vulnerability identifier        |
| DinD         | Docker-in-Docker                                                              |
| DNS          | Domain Name System                                                            |
| FR / NFR     | functional / non-functional requirement (see `.claude/rules/traceability.md`) |
| GHA          | GitHub Actions                                                                |
| ICOM         | Input, Control, Output, Mechanism — the IDEF0 box interfaces                  |
| IDEF0        | Integration Definition for Function Modeling — the stage-description model    |
| k8s          | Kubernetes                                                                    |
| L7           | network layer 7, the application layer (HTTP methods, paths)                  |
| MITM         | man-in-the-middle — an intermediary that decrypts and re-encrypts traffic     |
| OSS          | open-source software                                                          |
| PRD          | Product Requirements Document (a change's `proposal.md`)                      |
| QEMU         | Quick Emulator — software virtualization backend, weaker isolation than vz    |
| RCE          | remote code execution                                                         |
| SSRF         | server-side request forgery                                                   |
| TLS          | Transport Layer Security                                                      |
| TOCTOU       | time-of-check to time-of-use — a race between a check and the acting on it    |
| TTL          | time to live — the staleness threshold of a lease                             |
| VM           | virtual machine                                                               |
| vz           | Apple Virtualization.framework backend — hardware virtualization on macOS     |

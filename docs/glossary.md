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
- **Fence** — a mechanism that stops a stale holder's writes. Two of them, one
  per medium: on the branch, the **fast-forward-only push** — the task branch
  is never force-pushed, so a push from a tenure a newer one has superseded is
  refused by the remote; at the tracker, the **round-boundary revocation
  check** — a holder re-verifies its claim at each round boundary and writes
  nothing past it while the claim cannot be confirmed as its own. *Not:* a
  reader comparing a **claim epoch** it finds on the branch against the one it
  was just issued — artifacts of earlier tenures are history, and that
  comparison yields false positives, never a fence.
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
  checkpoint); **release** — give the claim up without moving the task's status; the reaper
  returns it to Ready once the claim is stale, and only then may any instance take
  over.
- **Escalation** — handing a task to a human via tracker status, with the
  findings history attached.
- **Abort fuse** — the bound on infrastructure aborts a task may accumulate
  before it is quarantined: the abort protocol (the handler that releases or
  parks an aborted task) together with its threshold *K*, carried as one
  value because the protocol is never run without the threshold. *Not:* the
  stage attempt limit, which counts quality failures.
- **Resume** — any instance continuing a task from its branch and state file;
  requires no hand-off from the previous holder.
- **Task branch** — the git branch holding the task's artifacts and state
  file; the single source of truth for task progress. Its configuration lives
  under the `task-branch:` section of `.gnomish/config.yaml` — the `base`
  subsection (see **Allowed bases**, below) is its first member, and later
  settings of the same branch (a name-prefix override is the first named
  candidate) join it rather than becoming new root keys (D16 of
  `add-base-ref-resolution`).
- **State file** — the machine-readable task state committed to the task
  branch after every attempt.
- **Round** — one iteration of the factory's execution loop for a task; round
  boundaries are where claim-loss and staleness decisions take effect.

## Crash consistency

The vocabulary of recovery from a crash inside a multi-step transition. The
principle and the mechanisms live in `docs/adr/0003-crash-consistency.md`; the
checklist for new transitions lives in `.claude/rules/crash-consistency.md`.

- **Branch shape** — the classification of a task branch tip: its file set and
  envelope versions mapped to exactly one name from a closed set. Classified
  by content alone — the tip's claim epoch is provenance, not an input. Total by construction — every combination classifies, `Unknown`
  included, and classification never throws on content. The closed set and the
  meaning of each name are owned by the `task-branch-contract` capability, in
  its "Total branch-shape classification" requirement
  (`openspec/specs/lifecycle/task-branch-contract/spec.md`); it is the only place the
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
  (`openspec/specs/lifecycle/claim-heartbeat/spec.md`).
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
  only task identity and counters. On the branch it is **provenance**: it names
  the tenure that wrote a commit, and a reclaim resumes from commits of earlier
  tenures rather than treating them as suspect. At the tracker it is the
  holder's **identity**, which a fenced claim operation compares against. It is
  not itself a fence — see **Fence** for the two.
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

## Base ref resolution

The vocabulary of "which ref does this task branch from, and where does its
law come from" — introduced by `add-base-ref-resolution`. See
`docs/adr/0005-dependency-outage-accounting.md` and
`docs/adr/0006-base-refresh-fetch.md` for the mechanisms behind the remote
outage gate and the refresh fetch, and
`docs/adr/0007-pipeline-law-source.md` for the law-source abstraction, the
trusted/task tier split, and the law-root rule.

- **Base ref** — the git ref (branch, tag, or commit SHA) a task branch is
  created from, decided by one fixed priority order: an explicit `--base`
  argument, else the task's `base` **designator** validated against the
  **allowed bases**, else the configured `task-branch.base.default`, else the
  repository default branch as the remote reports it, else — manual `run`
  only — the clone's local `HEAD`. Recorded as a **base pin** at task
  creation; resume reads the pin and never re-resolves.
- **Allowed bases** — the project-configured list of ref-name patterns (each
  with an optional `development` | `release` role) a task's `base`
  designator is validated against, under `task-branch.base.allowed`. It
  bounds only the per-task selection: an explicit `--base` and the
  configured `default` are not matched against it. A `default` outside the
  list, or a selection rule declared for a kind with no allowed bases, is a
  located load error. *Never:* menu.
- **Designator** — a per-task selection of a given kind, carried in the
  `fetchTask` facts in one of three shapes: absent, a single value, or a
  conflict listing every value found — classified by one function shared
  across tracker adapters, which supply only the candidate values from their
  own representation (a GitHub label rule, a future Jira native field).
  `base` is the first kind; `type` (task routing) is the next, added by a
  later change on the same mechanism.
- **Base pin** — the `(resolved ref, ref kind, SHA, source rule)` record
  written into `task.json` at task creation. The **ref kind** is the
  namespace origin held the name in — branch, tag, or commit — as the refresh
  established it; a pin written by the manual tier, which classifies nothing,
  carries no kind. Resume reads the pin and never re-resolves the base from
  tracker data or configuration: it refreshes the pinned name in the pinned
  kind's namespace only, so a name later reused in the other namespace
  neither redirects nor parks the task. The SHA is not a second way of
  finding the base — it *is* the commit the task branch was created from,
  read once from the refresh's destination ref (`docs/adr/0006`).
- **Law commit** — the commit a task's pipeline law (`.gnomish/` stage
  manifests, stage instructions, judge criteria) is read from once a ref has
  been resolved for it: the pinned SHA on a fresh start, the current tip of
  the pinned ref name on resume (a tag or SHA base makes the two equal). Read
  through git objects; the factory clone's working tree, index, and `HEAD`
  play no part in it.
- **Law root** — the one root every stage file reference (`instructions`,
  `criteriaFile`) resolves against, in every medium: the `.gnomish/`
  directory of the law commit, or of the working tree in in-place mode and
  manual `run` without `--base`. Distinct from the **working copy root**,
  which the external-check pin guard and artifact output paths resolve
  against instead:

  | Manifest field                                  | Relative to            |
  |-------------------------------------------------|------------------------|
  | `instructions`, `criteriaFile`                  | law root (`.gnomish/`) |
  | external-check pin paths, artifact output paths | working copy root      |

  A symlink entry at any segment of a reference's path under the law root is
  refused as unreadable, never followed, regardless of its target.
- **Trusted tier** — the half of pipeline configuration (`tracker:`,
  `task-branch:`, and future selector sections) bound once at `serve`/`take`
  startup, from the refreshed repository default branch, and never re-read
  per claim: a merged change to it takes effect only on the next start.
- **Task tier** — the half of pipeline configuration (stages, stage
  instructions, judge criteria, the remainder of `config.yaml`) bound per
  task from the task's own law commit.
- **Origin contact** — the fact a successful base refresh (or resume bind)
  carries about the path it took: either the factory reached **origin** for
  the answer — a branch or tag fetch, or a fetch of a commit the clone
  lacked — or the answer came from the **clone alone**, with no network
  round trip: a commit object the clone already held, or a resume bound from
  the local tip in a clone with no `origin` remote. It is set by the adapter
  at the return the path arrives at, never derived from git's output, and it
  costs no extra call. Only the **remote outage gate** reads it.
- **Remote outage gate** — the one daemon-level state per remote target that
  a slot's base-refresh (or default-branch-discovery) reachability failure
  opens: while open, the feed claims nothing and the daemon probes the
  remote with a tracker-free reachability check on a jittered, growing,
  capped interval; the first successful probe closes it. Transitions are
  operator events and a `remote` snapshot section beside `tracker`; the probe
  interval resets to idle only after the first successful base refresh
  following a close, never on the probe itself — and only a refresh that
  contacted origin counts: a refresh served from the clone alone neither
  resets the interval nor advances the remote's last successful contact
  time, since it is no evidence that origin answered. The outage is charged
  to the daemon, never to any task's abort accounting.

## Sandbox

- **Box** — the disposable isolated execution environment a gnome runs in;
  destroyed after the task. *Not:* a synonym for container — a box may be a
  container or a VM.
- **Declared volume** — a path an image's Dockerfile marks with `VOLUME`. The
  factory makes every declared path ephemeral (a size-bounded `tmpfs`) unless
  it already mounts that path explicitly, so no anonymous volume is ever
  created; content the image ships under a declared path is therefore not
  visible in the box. *Never:* implicit volume, image volume.
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
  ended. It becomes durable only in the same commit as the record it
  delimits — `state.json` with the attempt, `task.json` with a
  `cannotExecute` escalation — because the guard container outlives the
  factory process. A resuming instance offers back the newest committed
  position across both documents, and no lifecycle rewrite of either
  document drops a committed cursor; the guard applies the offered position
  only if it names the container now live. *Never:* offset, bookmark,
  watermark.
- **Denial identity** — the source-assigned event timestamp the guard's own
  denial log carries for each denial, recorded beside the finding. It makes a
  re-read idempotent: denials already recorded at the branch tip are matched
  by identity and attached once, so a lost cursor degrades to a no-op rather
  than to duplicates. *Never:* denial id, event key.
- **Loss marker** — a synthetic finding emitted into the denials list when
  the factory can see that denials were lost: the guard log tail cap
  saturated a read, or a committed cursor names a source that no longer holds
  its log. It travels the same findings channel as denials, so a reader tells
  "this task had no denials" from "this task's denial data was lost" without
  leaving the report. *Never:* gap record, loss counter.
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

## Observability

- **Anchor line** — an INFO log line marking a lifecycle transition the
  operator plane must be able to find later: claim acquired, serve
  started/stopping, container environment created/reattached/disposed, task
  lifecycle commit, and the canonical task summary. Anchors are what make a
  `grep taskId=<id>` reconstruct a timeline rather than a pile of details. The
  application-plane vocabulary has one owner, `AnchorLog`; remote modules emit
  their own anchors at their own choke points under the same policy
  (`docs/adr/0004-logging-policy.md`). *Not:* an event — the ledgers and
  snapshots are the machine plane; an anchor is a text line for a human.
- **Canonical task summary** — the single log line every task produces when it
  leaves the factory, carrying outcome, stage, attempts used, wall time and
  token usage by model. One renderer serves all modes; the facts are assembled
  per mode (from the ledger's terminal line in serve/take, from engine events
  in a manual run). *Not:* the ledger's `runSummary` line, which records a
  whole drain run in the machine plane — the canonical task summary is one
  task, in the log plane. *Never:* run summary (that name is taken).
- **Operator event** — one production WARN or ERROR call site, named by a
  stable `[GFnnn]` code that the site renders as its message head. One code is
  one call site: two emitters of the same fault are two events, because the
  code names *where* the factory degraded. Codes are never reused and the
  catalog only grows; INFO and DEBUG lines have no codes, since the catalog's
  scope is the operator plane. Owned by `OperatorEvent` in `:operatorevent`, a
  JDK-only leaf every emitter reaches — `:domain` included. *Not:* an anchor
  line, which is an INFO timeline marker and carries no code. *Never:* error
  code, message ID.
- **Log contract** — the promise the factory makes about its operator plane:
  the *code* identifies the event, the prose does not. An alert, a grep or a
  spec keyed on `[GF042]` keeps matching however the sentence is rewritten, and
  every operator line is asserted by at least one spec — level and attribution
  key included. Two gates hold it up: a static one failing an uncoded site, a
  duplicated code or a code no test source names, and a runtime one failing any
  spec that provokes a WARN/ERROR no capture observed. *Not:* the log format,
  which is the encoder pattern in `logback.xml` and carries no promise at all.
- **Operator console** — the factory's terminal output that does not go through
  the logger: command reports, dialogs, and `--json` renderings. It has one
  owner, `ConsoleIO` (implemented by `SystemConsoleIO`), with two paths chosen
  by the reader: the *human path* renders untrusted characters **visibly**
  (`^[`, `^X`, `^?`, `\uXXXX`) while preserving line structure and length, so an
  operator sees that a hostile source tried; the *machine path* writes verbatim,
  because its reader is a parser. No other production class writes to
  `System.out`/`System.err`, and a build gate holds that. *Not:* the operator
  plane, which is the WARN+ log lines that reach the console through the logger.
  *Never:* stdout writer, printer.
- **Repeat suppression** — the edge-logging discipline for a loop that can
  fail on every tick: the first occurrence (or a changed reason) logs at the
  site's level, repeats drop to DEBUG, a periodic roll-up names the count, and
  a recovery line reports the outage. Owned by `RepeatSuppressor` in
  `:logtext`; state is in-memory per process, so a restart may repeat the
  first-occurrence line. *Not:* a local aggregate counter, which collapses a
  flood *within one operation* into one summary line — a different invariant,
  deliberately not the same mechanism. *Never:* deduplication, throttling.
- **Untrusted text** — text the factory captured from outside its own trust
  boundary and must treat as attacker-influenced. It is a *type*, not a
  convention: `UntrustedText` in the `:untrustedtext` leaf holds the bytes as
  captured together with their provenance, and a capture accessor returns the
  carrier rather than a string. It has four ways out, each with its own
  allowlist: queries that yield a boolean or an int (open to all); the exits
  `forLog()`, `forConsole()`, `forComment()` and `forCommentInline()`, each
  applying its plane's
  notation; `forParsing()`, for the `@UntrustedParser` classes that turn the text
  into a value that is no longer untrusted text; and `raw()`, for the
  `@UntrustedExit` classes that write the bytes to a machine medium. A build gate
  holds all four. *Not:* a secret value, which is never written anywhere at all.
  *Never:* raw string, tainted string.
- **Provenance** — where a piece of text in a carrier came from, fixed at the
  mint: one of *subprocess output*, *in-container command output*, *agent
  output*, *tracker text*, *target-repository manifest*, *task-branch document*,
  *operator-supplied argument*, *factory-composed text*. The last two are inside
  the trust boundary and are families all the same, because the field they land
  in is a carrier on every other code path. Provenance is evidence a report may
  name, never a policy input, and it is deliberately **outside** the carrier's
  identity: two carriers are equal when their text is equal, so a value minted at
  a subprocess, written to a task-branch document and read back as a
  branch-document carrier is still the value that was written. It names the
  source family, not the call site, and it is re-stated rather than inherited when
  a value is lifted out of a document another instance wrote. *Not:* the exit,
  which is chosen by the plane the text is leaving for, not by where it came from.
  *Never:* taint tag, text kind.
- **Log text sanitization** — the rendering untrusted text receives on its way
  into a log line: control/ANSI stripping, newline flattening so one event stays
  one line, and a length cap. The choke point is the carrier's own log exit,
  `UntrustedText.forLog()`; `LogText` in `:logtext` is the `String` facade over
  the same rendering for callers that do not hold a carrier. Both delegate to the
  `:untrustedtext` leaf, which owns the character table and the primitives.
  Behind them stands the **sink layer**: the Logback encoder neutralizes the
  rendered message, the rendered throwable and every MDC value through its own
  converters, so a hostile byte that reached a record without passing the exit
  still cannot forge a line — defense in depth, not a license to skip the exit
  (the three layers are stated in `docs/adr/0004-logging-policy.md`). *Not:*
  findings sanitization — `FindingsSanitizer` guards the plugin-findings boundary
  and deliberately *preserves* line structure; the two are distinct controls at
  distinct trust boundaries, both facades over the same `:untrustedtext` owner.
- **Shutdown phase** — the window between the moment a stop takes ownership of
  the process and the moment it exits. Marked once, first thing in the shutdown
  hook, and read by the sites that would otherwise report the stop's own
  casualties as faults: a slot dying mid-round, a heartbeat worker interrupted,
  a git or docker command whose process tree the stop killed. During the phase
  those are logged once, at WARN, without a stack; outside it nothing changes.
  Owned by `ShutdownPhase` in `:logtext`. *Not:* the drain — draining is the
  work the stop does, the phase is the fact that it is happening. *Never:*
  shutting-down flag.

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

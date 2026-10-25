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
- **Instant source** — the one source of the current instant every component
  reads "now" through: the JDK's `java.time.InstantSource`, never a
  project-owned type. Production builds exactly one, in the composition root
  (it is the clock of the **time equipment**); specs build the virtual one
  (`VirtualClock`). A component that stamps state and measures an interval
  about that state does both on the same instant source. Wall time only:
  elapsed time measured for its own sake reads the monotonic source
  (`System.nanoTime()`, `MonotonicTime`). Introduced by
  `supervise-daemon-loops-and-embed-dashboard` (design D16, D17); the
  reasoning is ADR 0014. Type: `InstantSource`. *Not:* `java.time.Clock`,
  whose zone and tick surface no component needs. *Never:* clock port,
  domain clock.
- **Time equipment** — real time's two seams as one value: the current instant
  (a `java.time.InstantSource`) and waiting (the `Sleeper` port), with the two
  computations only the pair can do — the time *remaining* until a deadline and
  a sleep *until* one. Every policy built from time (a retry, a loop's wait, a
  poll deadline, a box's exec stamp) takes it whole. The composition root builds
  the real one exactly once, and only `:bootstrap` can construct the real
  sleeper; specs build the virtual one (`VirtualTimeEquipment`), whose sleeper
  advances its clock. Introduced by `supervise-daemon-loops-and-embed-dashboard`
  (design D20); the reasoning is ADR 0014. Type: `TimeEquipment`. *Not:* a clock alone — a component that
  only reads the instant and never waits takes the `InstantSource`. *Never:*
  clock and sleeper pair, system defaults.

## Task coordination

- **Claim** — the tracker-visible record that a factory instance holds a task.
- **Lease** — the claim's liveness contract: it must be re-asserted by beats
  or it goes stale (ADR 0002).
- **Beat** — one heartbeat: a PATCH to the instance's own claim comment,
  carrying human-readable progress.
- **Beat timing** — the two durations one heartbeat runs on: the beat
  interval and the lost-detection threshold, how far the claim's last
  confirmed beat may fall behind before its holder stops writing. The
  threshold is a multiple of the interval and means nothing without it, so
  the two travel as one value that refuses a threshold shorter than the
  interval. Type: `BeatTiming`, derived from configuration in one place,
  `LeaseThresholds.beatTiming`. *Not:* the reaper's stale-claim TTL, which
  an observer measures on another instance's claim.
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
- **Park** — stop a task for a human (escalation or a manual checkpoint) and
  record the stop durably: `take` and `serve` record the outcome on the task
  branch and set a waiting tracker status; `run`, which has no tracker, parks
  on its branch only — the recorded outcome, no tracker status — and exits 10
  or 11. Either way any instance resumes from the branch. **Release** — give the claim up without moving the task's status; the reaper
  returns it to Ready once the claim is stale, and only then may any instance take
  over.
- **Escalation** — handing a task to a human via tracker status, with the
  findings history attached; in `run`, via exit code 10 and the report on
  stdout, answered by `--resume --decision`.
- **Abort fuse** — the bound on infrastructure aborts a task may accumulate
  before it is quarantined: the abort protocol (the handler that releases or
  parks an aborted task) together with its threshold *K*, carried as one
  value because the protocol is never run without the threshold. Type:
  `AbortFuse`, a member of the **slot wiring**. *Not:* the
  stage attempt limit, which counts quality failures.
- **Abort trigger** — what tripped one infrastructure abort, as one value: the
  recovery-cause category the attempt spends from, and the live exception
  when the trigger still holds one (an uncaught take-run crash) or none (an
  engine `Aborted` outcome, whose cause is a string the domain already
  rendered). Built only through its two factories, so no caller assembles
  the nullable by hand; handed whole to the **abort fuse**, the one entry into
  the abort protocol from a take run. Type: `AbortTrigger`. *Not:* the
  abort's outcome (release or park) — that is the protocol's decision.
- **Terminal transitions** — one run's park and finish steps as one value: the
  branch-side steps of a park and of a completion, built together by the
  run's engine execution from that run's closures and handed together to the
  slot's outcome dispatch, which routes each terminal outcome into one of
  them. Valid for one dispatch; the dispatch never keeps it. Introduced by
  `supervise-daemon-loops-and-embed-dashboard` (design D22). Type:
  `TerminalTransitions`. *Not:* the dispatch itself, which is fixed for the
  slot's lifetime.
- **Resume** — any instance continuing a task from its branch and state file;
  requires no hand-off from the previous holder.
- **Order** — the resolved description of the work one invocation performs,
  handed to the runners as one value. Built by each entry point from its own
  parsed arguments plus the loaded pipeline definition; two kinds, **run
  order** and **take order**. *Not:* the parsed command line (`RunArguments`,
  `TakeArguments`) — those are flags before validation, an order is the
  instruction a runner acts on.
- **Run order** — the order both manual `run` and tracker-driven `take` /
  `serve` carry: the clone directory, the `--base` override (if any), the
  pipeline definition and the discard-work flag. Type:
  `RunOrder`. Carries no tracker, task or instance identity — manual runs have
  none.
- **Take order** — the tracker-driven order: a run order plus the claimed
  task, the tracker it was claimed through and this instance's identity. The
  one place the claimed task's identity (its ref and task id) is derived.
  Built only after the claimed task is fetched. Type: `TakeOrder`.
- **Slot wiring** — the equipment one take slot works with: the run assembly,
  the task-git capability set, the registered clone, the task-id MDC key, the
  **abort fuse**, the credential variable names to scrub, the container
  support seam, the **claim tenure**, the trusted base tier and the root's
  terminal-write retry. Its one method beyond accessors,
  `outcomeDispatch()`, builds the slot's outcome dispatch (`TakeOutcomeDispatch`)
  over its own retry and abort fuse — the dispatch's only construction site,
  shared by every run of the slot, which hands it its **terminal
  transitions** per run (`supervise-daemon-loops-and-embed-dashboard`,
  design D22). Type: `SlotWiring`. Its lifetime is what separates it from the order: an order
  describes one invocation's job and is passed as a parameter, while the
  wiring is fixed for as long as the slot exists — built once per `take`
  invocation, or once per `serve` daemon and shared by all its slots — and is
  held as fields by the components that use it. *Not:* the order, and not a
  composition root's argument list — it is built only after the tracker is
  provisioned.
- **Bound tracker** — what one `take` or `serve` invocation has bound once its
  tracker is provisioned: the pipeline definition, the trusted base tier, the
  tracker configuration, the adapter factory, the live tracker (in `serve`,
  the health-wrapped one) and this instance's identity. Type: `BoundTracker`.
  Membership rule: a member exists only once the tracker is provisioned and
  stays fixed for the whole invocation; it constructs no collaborator. It sits
  between the other two values: unlike the **take order** it holds no fetched
  task, so it exists before any task is claimed; unlike the **slot wiring** it
  is not equipment — it is what the wiring and the dispatch chain are built
  over. *Not:* the order, and not the slot wiring.
- **Feed assembly** — the serve feed's assembly object: holds the feed's
  timing equipment (the **time equipment**, idle timing, WIP limit) as fields and
  builds one feed automaton per `serve` daemon from the per-daemon values
  (tracker, instance id, slot ledger, slot runner, dirty notifier, outage
  gate), constructing the feed's cycle and view tracker on the way. The one
  door into the automaton: its constructor is package-private and takes the
  cycle and view tracker built. Carries no decision, so it has no spec of its
  own and is exercised through the serve wiring. Type: `FeedAssembly`.
  *Not:* the automaton itself, which runs the feed cycle.
- **Eligibility inputs** — the values the feed evaluates one ready task
  against when deciding whether it would claim it now: the backoff shape
  (base and cap), the open-front count and the WIP limit. Backoff is measured
  at the board's own observation instant, which is not part of the inputs.
  Resolved once per invocation and handed whole to the board's
  model and the eligibility policy, so the board answers exactly as the take
  feed would. Type: `EligibilityInputs`. *Not:* the task's own facts (abort
  count, last abort) — those are what the inputs are evaluated against.
- **Claim tenure** — as a type, the liveness view of one tenure (one holding
  of a claim, identified by its **claim epoch**): the claim beat that keeps the
  claim alive and the claim-loss flag the beat sets when the claim is lost.
  Type: `ClaimTenure`, built only from the run's heartbeat, which owns both
  and requires the flag to be the one wired as the beat's sink. Lives as long
  as the slot wiring that carries it. It deliberately does not carry the
  claim epoch itself: the task-git capability set's epoch book is that
  value's single owner.
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
- **Agent round equipment** — what every agent round is launched with: the
  installation's agent settings (the CLI binary), the instant
  source that stamps the round, the live-progress subscriber and the extractor that
  shapes the round's essential result. Built once per owner (the stage
  executor, the judge voter) and taken whole by that owner's round
  execution, so the group is assembled in one place rather than re-listed at
  every launch. Type: `AgentRoundEquipment`. *Not:* the round's job (the
  prompt, the working copy, the round listener), which is passed per call.
- **GitHub marker fields** — the wire form of a GitHub structural marker: the
  hidden JSON a marker comment carries, as data alone (kind, instance,
  timestamp, format version, and the optional park reason, content identity
  and claim epoch), with the codec that renders and parses it kept apart.
  Type: `GithubMarkerFields`, a Jackson record; `GithubMarkerJson` is the
  codec. *Not:* the marker itself, which is the comment on the tracker.

## Crash consistency

The vocabulary of recovery from a crash inside a multi-step transition. The
principle and the mechanisms live in `docs/adr/0003-crash-consistency.md`; the
checklist for new transitions lives in `.claude/rules/crash-consistency.md`.

- **Envelope** — the factory-owned files under `.gnomish-task/` on a task
  branch that record a task's identity (`task.json`) and pipeline position
  (`state.json`); read at the branch's **registered commit** in every medium,
  never at a tip the gnome may have written above it, and written to a working
  copy only as the staging step of the next commit. *Never:* "state directory" for
  the concept (that names the path), "worktree state" (a working copy holds no
  envelope, only a staged copy of one).
- **Branch shape** — the classification of a task branch: the file set and
  envelope versions of its **registered commit** mapped to exactly one name
  from a closed set. Classified at the registered commit, never at the tip;
  the tip contributes one fact, its relation to the register (at it, above it,
  diverged from it, or no register at all — type `TipRelation`). A diverged
  tip classifies `Unknown` with a diagnosis; a missing register over an
  envelope classifies `Unregistered`, and over no envelope `Bare`. Classified by content alone — the claim epoch is
  provenance, not an input. Total by construction — every combination classifies, `Unknown`
  included, and classification never throws on content. The closed set and the
  meaning of each name are owned by the `task-branch-contract` capability, in
  its "Total branch-shape classification" requirement
  (`openspec/specs/lifecycle/task-branch-contract/spec.md`); it is the only place the
  table lives. Recovery owner and roll-forward/discard disposition per shape
  live in `docs/adr/0003-crash-consistency.md`. *Never:* `Escalated` for the
  `Parked` shape (that name belongs to a `TaskOutcome` variant), `Decision`
  for the `Answered` shape (that name belongs to the human's answer record),
  `Paused` for the `AwaitingApproval` shape (that name belongs to a
  `TaskOutcome` variant).
- **Unregistered** — the **branch shape** of a task branch whose tip carries a
  factory **envelope** while this clone holds no **factory register** for the
  task, as an operator's or an IDE's `git fetch`, or a `git clone`, leaves one.
  A state of the clone, not of the branch: nothing is read at a register that
  is not there, its **recovery owner** is the touchpoint fetch, which lands the
  register beside the branch, and the branch then classifies by its content. A
  branch still `Unregistered` after a reader's own read is quarantined like
  any shape no register can roll forward from. Only a branch the clone holds
  as a remote-tracking ref is fetched that way; a local head without its
  register is not, and stays `Unregistered` (a deferral of the same change,
  its proposal NG5). Type:
  `BranchShape.Unregistered(register, tip)` (introduced by
  `fix-terminal-receipt-convergence`, design D13). *Not:* `Unknown`, which is a
  present and diverged pair (or a legal but unrecognized combination); *Not:*
  `Bare`, whose tip carries no envelope at all.
- **Branch adoption** — making a task branch that a clone holds only as a
  remote-tracking ref (`refs/remotes/origin/gnomish/<task>`) a local branch,
  by one compare-and-swap ref create: one `git update-ref --stdin` transaction
  creates `refs/heads/gnomish/<task>` at the tracking tip only if no such head
  exists yet. The factory register is not touched: the touchpoint fetch that
  located the branch already landed it. Two adopters in one clone race only at
  that create; the loser finds the winner's head and continues on it. Nothing
  undoes an adoption. Owner: `TaskBranchAdoption`, behind the git port's
  `ensureLocalTaskBranch` and `adopt`; answer type `BranchAdoption` (`Adopted`, `AlreadyLocal`,
  `Nowhere`) (introduced by `fix-terminal-receipt-convergence`, design D9,
  D12). *Not:* a checkout (no working copy is touched, and git resolves no
  name); *Not:* a fresh task's branch creation, which makes a branch that
  exists nowhere yet.
- **Tracker shape** — the same total classification applied to the tracker
  medium: state labels, claim footprint, and boundary markers mapped to
  exactly one name, `Foreign` included for out-of-protocol combinations.
  Adapters report facts; the classification happens in core. The closed set and
  its table are owned by the `claim-heartbeat` capability, in its "Total
  tracker-shape classification with one recovery owner" requirement
  (`openspec/specs/lifecycle/claim-heartbeat/spec.md`).
- **Tenure boundary** — the kind of the last boundary marker (park, finish,
  abort, stale-claim removal) on a task's whole tracker thread, in posting
  order, whatever claim markers follow it; null when the thread has none.
  Reported by `fetchTask` as `TrackerTask.tenureBoundary`, and the one fact a
  receipt probe reads to confirm a park or finish after its own pickup has
  claimed the task again. *Not:* the claim-scoped boundary the shape facts
  carry for the reaper (`TrackerFacts.latestBoundary`), which is null once a
  claim follows the boundary.
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
- **Factory register** — the ref `refs/gnomish-factory/<task>`, in origin and
  in the factory clone, naming the factory's latest **closing commit** of the
  task; moved only by the factory, only together with a closing commit — one
  ref transaction in the factory clone (`git update-ref --stdin`, atomic on
  disk because the clone is on the reftable ref backend), one
  `git push --atomic` of branch and register at origin. A mid-round push or an
  admitted gnome range moves the branch alone. `git clone` does not carry it:
  a clone receives it through the touchpoint fetch, which fetches branch and
  register together. It is the trust anchor of the branch's record in the
  container tier; in host mode the gnome can move it, so it carries no
  integrity claim there, and its anomalies are signals only. Owner in the
  clone: `FactoryRegister`; ref name: `RegisterRef`, spelled from a task id by
  `FactoryRegisterRefs` (introduced by
  `tighten-spec-contract`, design D13, D19). *Not:* a **registered clone** of
  Operator configuration, which is a clone directory listed in a project
  file.
- **Registered commit** — the commit the **factory register** names, read once
  per pickup after the touchpoint fetch; every reader of the factory's record
  takes it — the **envelope**, the position, the outcome, the **base pin**,
  the snapshot subject, and the delivery fact (`Delivered` is "the registered
  commit is the cleanup commit"). It is a commit id, never a ref or `HEAD`, so
  a reader handed one cannot be pointed at the tip. Type: `RegisteredCommit`,
  produced only by `FactoryRegisterRead` (introduced by
  `tighten-spec-contract`, design D13).
- **Closing commit** — a commit by which the factory closes a step of the
  task's record: task started, a round's state commit, the round snapshot, an
  outcome, an approval, an appended decision, a resumed write, a salvage, the
  cleanup. Made as an object first, then landed with the **factory register**
  in one ref transaction and carried to origin by the **closing push** (the
  atomic push of branch and register). A host closing commit is built on the
  task branch's tip, never on the worktree's `HEAD`. *Not:* a gnome commit,
  which never moves the register.
- **Harvest quarantine** — the ref `refs/gnomish-quarantine/<task>`, in the
  factory clone only, that every harvest of a box's task branch fetches into;
  what the gnome committed exists factory-side as objects and this one scratch
  ref until the **harvest admission** moves the branch to it. Type:
  `QuarantineRef` (introduced by `tighten-spec-contract`, design D18).
  *Not:* the **quarantine** below, the park of a task whose shape recovery
  cannot converge.
- **Harvest admission** — the one way gnome content from a box enters the task
  branch: it inspects the range from the **registered commit** to the
  **harvest quarantine** with the **gnome range inspection**, requires it to
  descend from the branch tip, and only then moves the branch to it (with the
  register, in one transaction, when the top is the factory's own in-box
  closing commit). A refused or unverifiable range moves nothing, so it never
  becomes durable; a refusal is a boundary violation of the box. Type:
  `HarvestAdmission`, its result `AdmittedRange` (introduced by
  `tighten-spec-contract`, design D18).
- **Gnome range inspection** — the one rule a range of gnome commits must pass
  before it may enter the task branch: the base an ancestor of the top, no
  commit with a second parent, no commit whose subject is in the `gnomish: `
  namespace, no change to a factory-owned path the round's rule does not
  permit (the gnome's own decision requests). Two callers: the **harvest
  admission** in container mode, and the host round close over its own range,
  which also restores the factory-owned paths from the registered commit
  before staging. Type: `GnomeRangeInspection`, under an `AdmissionRule`
  (introduced by `tighten-spec-contract`, design D18; it replaced the declared
  pair of the host and container round-boundary checks).
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
  than a shape; nor the **harvest quarantine** above, a ref where a box's
  commits wait for the harvest admission.
- **Gate** — a stop that a durable write records and only a later durable
  write may open: whatever the **registered commit** of a task branch
  records, a pickup may do no more than that commit allows, so the gate lives in the record of the step
  that reaches it, never in the step that follows. The `manual` checkpoint is
  the gate this vocabulary was introduced for: its passing round commit already
  records **awaiting approval**, and only the **approval** moves past it
  (`.claude/rules/crash-consistency.md`, item 12; introduced by
  `make-checkpoint-gate-durable`). *Not:* the park — the park reports a gate
  to a human; the gate holds even when the park was never written.
- **Awaiting approval** — the pipeline position a passing `manual` stage
  leaves on the task branch, naming that stage: the round commit itself
  records it, so a pickup that finds it pauses without running anything,
  however many times it runs. Type: `Position.AwaitingApproval`; the branch
  shape of such a tip is `BranchShape.AwaitingApproval` (introduced by
  `make-checkpoint-gate-durable`). *Not:* the `Paused` task outcome, which is
  the run's report that it stopped at the gate.
- **Approval** — the pivot write that opens a gate: one commit that moves the
  position from **awaiting approval** to the position following the gate stage
  in the pinned pipeline definition and clears the recorded outcome together,
  leaving the attempt history untouched. It is the only writer past a gate and
  refuses, writing nothing, when the tip is not at the gate it names. Method:
  `TaskRepository.approveCheckpoint`; refusal:
  `CheckpointApprovalRefusedException` (introduced by
  `make-checkpoint-gate-durable`). *Not:* the human's answer to a decision
  request, which is recorded by `appendDecision`.
- **Resumed write** — the one commit by which a continuation consumes a
  recorded outcome without a human answer (a bare return after an exhausted
  attempt budget, an infrastructure-class return, `run --resume` without a
  decision): it records the reset state and clears the outcome together, so the
  budget a return grants is granted once. Refuses when the tip records no
  outcome. Method: `TaskRepository.resumeFrom`. Together with `appendDecision`
  and the **approval** it is one of the only three writes that clear a
  recorded outcome (introduced by `make-checkpoint-gate-durable`).
- **Round token** — the identity of one container-mode **round**: the commit
  the task branch's tip pointed to when the round opened. No later round of the
  task repeats it, because every round lands at least its snapshot commit and
  every reset rides a commit of its own. It names the round's decision request
  (`decisions/<stage>-a<attempt>-<token>.json`, the only path read for that
  round) and rides its snapshot commit's subject. A fresh round mints it from
  the tip it opens on; a resumed round never mints — it reuses the token its
  snapshot recorded. Type: `RoundToken`, parsed only by `RoundToken.of`
  (introduced by `make-checkpoint-gate-durable`, D10). *Never:* "attempt" for
  it — the attempt is the counted try, and its number repeats.
- **Current round** — the per-run cell that holds the identity of the round in
  flight, whole: open (its **round token**), then closed by its snapshot (the
  token and the snapshot commit together). Filled either by the live round
  opening or restored from the snapshot's record on the resume path — the same
  two transitions in both cases. It exists only because the engine's ports
  cannot carry a round identity without learning about commits. Types:
  `CurrentRound`; a closed round is `ClosedRound` (introduced by
  `make-checkpoint-gate-durable`, D10). *Never:* "ref" or "holder" for it in
  docs.

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
  attempts, and every attempt is committed to the task branch. Its number
  counts within one visit of a stage and restarts at zero on every reset (an
  answer, an advancement, a retry of a killed round), so the same stage and
  attempt number repeat across visits — it identifies no round; the **round
  token** does. *Never:* retry
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
- **Reference pipeline** — a public repository whose `.gnomish/` tree is a
  working pipeline for one technology stack, listed in
  `docs/reference-pipelines.md` with a maturity level (`sketch`, `runs`,
  `hardened`) and the factory build it was tested on. Listed as-is, under its
  own repository's license. The maintainer adds an entry from an issue opened
  with the reference-pipeline form
  (`.github/ISSUE_TEMPLATE/reference-pipeline.yml`). *Not:* a reference file —
  nothing in the factory's build compares against it.
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
  carries no kind. Resume reads the pin at the task branch's **registered
  commit**, so a pin the gnome writes above it is never read (in the
  container tier; host mode carries no such claim), and never re-resolves the
  base from tracker data or configuration: it refreshes the pinned name in
  the pinned kind's namespace only, so a name later reused in the other namespace
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
- **Daemon condition** — one precondition a running `serve` daemon holds the feed
  on: the remote (the remote outage gate below) or an operator precondition — the
  container runtime, the clone's ref format, the image contract, the image's git
  floor. While any is open the feed claims nothing and reads nothing from the
  tracker; each is probed by the check that opened it, and only a passing probe
  closes it. Owned by `DaemonConditions`; opened only by a typed refusal. ADR
  0019. *Not:* a task failure — no abort is charged.
- **Operator precondition** — something the factory needs from the operator's
  setup and cannot fix itself (a reftable clone, a reachable container runtime,
  an image with this factory's contract and git floor). Assessed by `serve`
  before its first claim; met after a claim, it is
  `TakeResult.OperatorPreconditionUnmet` (exit 8) and, under `serve`, a daemon
  condition. ADR 0019.
- **Remote outage gate** — the first daemon condition: the one daemon-level state per remote target that
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
- **Image contract** — what the factory assumes about a box image, at one
  contract version: the `gnome` user (uid 1000) owning `/gnomish/**`, the
  root-owned control surfaces (`/etc/gnomish`, the agent policy in
  `/etc/claude-code`) and home with only declared directories gnome-writable,
  the egress guard's proxy in the environment, `git` at the factory's floor,
  `curl`, and the helpers `gnomish-trust-ca`, `gnomish-home-dir` and
  `gnomish-verify-image`. The version, the guard alias and the guard port have
  one source, `sandbox-image/image-contract.properties`; an image states its
  version in the label `io.gnomish.image-contract`, and the factory refuses a
  box image whose label is missing or names another version. Introduced by
  `add-sandbox-base-image`. *Not:* the capability passport, which describes a
  sandbox backend, not an image.
- **Sandbox base image** — `ghcr.io/oinsio/gnomish-sandbox-base`, built from
  `sandbox-image/` and published with every release: Ubuntu LTS carrying the
  whole image contract and no agent CLI, toolchain or credential. An
  operator's box image builds `FROM` it and adds only a toolchain and the agent
  CLI. Introduced by `add-sandbox-base-image`. *Not:* the reference recipe
  (`docs/examples/sandbox-image/`), an operator image built on it. *Never:*
  sandbox template, parent image.
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
- **Transfer** — one factory git fetch or clone as a value: the argument
  list and the environment built by the one transfer owner from a transfer
  source and one refspec, carrying the deny-by-default set of ADR 0008 (no
  tag auto-following, no `FETCH_HEAD`, no submodule recursion, no prune,
  validated objects, a closed protocol allowlist). *Not:* a push — pushes
  are not transfers and keep their own argv. *Not:* a narrow fetch, which
  is the base refresh's branch or tag transfer from `origin` (ADR 0006), one
  kind of transfer among several. *Never:* fetch recipe, flag set.
- **Transfer source** — where a transfer reads from, one of a closed set of
  kinds each carrying its own protocol allowlist and configuration
  isolation: the trusted `origin` remote, a task container reached over the
  `ext::` transport, or the operator's clone as the seed path of a box's
  working copy. A new sandbox backend adds a kind, never a flag list.
  *Never:* remote kind, fetch target.
- **Git invocation policy** — how the factory runs every git process of its
  own: a closed set of command-scope `-c` pairs that neutralize the keys of a
  clone's local configuration that run a program or change what git reads or
  writes (hooks, commit and tag signing, the filesystem monitor, branch
  tracking writes, background maintenance and garbage collection, replace
  refs), and the inherited per-process configuration variables removed from
  the environment. The clone's configuration is untrusted input, never the
  factory's storage: the factory reads no policy from it and writes nothing
  into it. One owner, `GitInvocation` in `:gittransfer`, builds the whole
  command line for both git launchers and opens every transfer. Introduced by
  `own-git-invocation-policy`; the reasoning is ADR 0012
  (`docs/adr/0012-git-invocation-policy.md`). *Not:* the transfer set — the
  deny-by-default set of the git transfer policy (ADR 0008) that a transfer
  adds after the invocation policy's pairs. *Not:* the in-box git of a task
  environment, which is outside the policy.
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
- **Skipped-verdict source** — where a `skipped-no-verdict` failed: `tracker`
  (the liveness oracle reached no verdict, so ownership is unknown) or
  `runtime` (the container runtime refused the stop or dispose, or the object
  could not be inspected). The snapshot's sweep entry carries the last one of
  the last tick, and the dashboard's stall alarm names it.
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
- **Container supports** — the installation's container-mode equipment (the
  check providers, the factory and sandbox settings, the **execution mode**
  selector, the process's claim-epoch book and the **time equipment**) and
  the constructions made from it: the container support for `gnomish run`,
  stamping `manual`, and for `take` / `serve`, stamping `tracked` — the same
  construction differing only in the **ownership mode** — plus a manual run's
  execution-mode plan, which it asks the selector for. The binding settings,
  the discovered adapter bindings and the container-runtime probe are the
  selector's inputs, not its own (`supervise-daemon-loops-and-embed-dashboard`,
  design D22). Type: `ContainerSupports`. *Not:* one run's container
  support (`ContainerRunSupport`), which it builds.
- **Execution mode** — whether a run's stages execute on the host or in a
  container (`host` | `container`): the fail-closed decision one selector makes
  over the operator's per-stage bindings, the sandbox settings, the
  classpath-discovered binding registry and the container-runtime probe. It
  refuses a mixed-binding pipeline and a container run without its
  prerequisites (the image and a reachable runtime) rather than fall back to
  the host. Built once by the composition root and asked by both manual runs
  and `take` / `serve`, so no caller supplies its own inputs. Introduced by
  `supervise-daemon-loops-and-embed-dashboard` (design D22). Type:
  `SandboxModeSelector`, its probe `ContainerRuntimeProbe`. *Not:* the
  `gnomish run --mode git|in-place` choice (the "run modes" of the manual-run
  capability), which decides whether a run uses a task branch, not where its
  stages execute. *Never:* sandbox flag.
- **Container environment factory** — the installation's **box equipment**, held
  once per **ownership mode**: the operator sandbox settings, the **box
  timing**, the guard config root and the ownership mode itself — what the
  installation decides about every box before any task exists. Its one method
  takes a task's inputs (the environment key, the **box git link**, the child
  environment allowlist, the **project identity** and the read of the denial
  position the branch tip records) and builds that task's environment seam.
  Built by the **container supports**. Type: `ContainerEnvironmentFactory`
  (introduced by `make-checkpoint-gate-durable`, D12). *Not:* the per-task
  seam `ContainerEnvironments` it builds.
- **Live box** — the one materialized **box** of a role (a round box per
  segment, a judge box per attempt commit), kept while its key is unchanged
  and rebuilt — the previous box disposed, a fresh one materialized — when the
  key changes. Concurrent requests for the key being built join that one build;
  a reader asking for the current box sees the last recorded one and never
  waits on a build in flight. No blocking work runs under its lock
  (`.claude/rules/lock-scope.md`). Type: `LiveBox` (introduced by
  `make-checkpoint-gate-durable`, D13). *Never:* "lease" for it — **lease** is
  the claim's liveness contract; the class name `EnvironmentLease` predates
  this rule and is kept.
- **Task container settings** — the operator configuration one task container
  is created with: the image, the runtime, the resource bounds and the
  disk-quota opt-in, read off the sandbox settings once and carried whole
  into the environment and the materializer. The image requirement lives in
  its constructor, so a blank image cannot become a settings value. Type:
  `TaskContainerSettings`. *Not:* the sandbox settings as a whole, of which
  these are the per-container slice.
- **Box git link** — the git link between the factory and one **box**: the
  factory's local clone the box's working copy is seeded from, and the fetch
  that brings the box's commits back into that clone — into the task's
  **harvest quarantine**, never onto the task branch. One value because
  neither names a thing without the other — a seed source with no way back is
  a leak, a harvest into nothing has no destination. Type: `BoxGitLink`; the
  environment harvests through it rather than reading the fetch back out.
- **Box timing** — the timing equipment every box operation runs on: the **time
  equipment**, whose clock stamps exec starts and whose sleeper the self-check's
  pause waits on, and the deadline every `docker` management command is bounded
  by. All of it is
  about time; a host directory such as the guard config root is deliberately
  not in it. Type: `BoxTiming`.
- **Box equipment** — the installation's half of every **box**, as one value:
  the `docker` command seam, the operator sandbox settings, the **box
  timing**, the guard config root and the **ownership mode**. The **container
  environment factory** builds it once; every constructor down the
  round-environment chain takes it whole beside a task's own inputs (the
  environment key, the **box git link**, the child environment allowlist, the
  **project identity**), never its members as loose parameters next to them.
  It owns the projections every box shares: the **task container settings**,
  a role's guard config directory, and a task's ownership (its mode beside the
  task's project identity). Type: `BoxEquipment` (introduced by
  `tighten-spec-contract`, D12). *Not:* a task's inputs, which vary per task
  and never ride in it.
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
  output caps, the bytes given to a child as input, credential scrubbing and
  what an exit code means stay with the caller; how that input reaches the
  child is the **launch owner**'s. *Not:* a supervisor in the process-manager sense — it
  never restarts anything.
- **Termination** — how a supervised invocation ended, named separately from
  the exit code: `EXITED` (the process chose its own code), `TIMED_OUT` (the
  deadline expired and the tree was killed), `INTERRUPTED` (the waiting thread
  was interrupted and the tree was killed). *Never:* a sentinel exit code such
  as `-1` for interruption — collapsing the three into one number is the defect
  the term exists to remove.
- **Child output** — the readable side of one of a child's pipes, stdout or
  stderr, as a typed value (`ChildOutput` in `:subprocess`): minted only from a
  started `Process` (`ChildOutput.stdout(process)`, `ChildOutput.stderr(process)`),
  so it carries the process beside its stream and the exit signal is wired by
  construction. It is the only thing a drain can start from, and the
  environment port hands it across as `ExecHandle.output()`. It reads without
  parking — only the bytes the pipe reports available, otherwise one tick of
  waiting — and closes nothing: the pipe is the JDK's to close at the exit. Its
  pull view (`ChildOutput.stream()`) runs the same steps on the caller's thread,
  and its `close()` is a stop. The rule and its measurement are ADR 0018.
  *Not:* the bare `InputStream` of a `Process`, which no reader outside the owner
  obtains.
- **Drain** — one child output read concurrently with the running process, so
  neither a full OS pipe buffer nor a descendant that keeps the pipe open can
  block the wait. It reads what is available and never parks in a blocking
  read; it ends on its own at the first query after the child's exit that
  finds nothing available, or early when it is stopped. Two forms of one owner:
  the push form (`Drain`, chunks handed to a sink on the drain's own thread)
  and the pull view (`ChildOutput.stream()`). The reasoning is ADR 0018.
  *Not:* a thread parked in `read()` until end-of-file — no factory thread
  waits that way on any OS.
- **Kill grace** — the bounded wait between the cooperative terminate and the
  forced kill, so git and docker can remove their lock and temporary files on a
  catchable signal. A bound on waiting, not a sleep: a tree that stops early
  returns early.
- **Round scratch directory** — the one host directory an agent round writes
  outside the task branch, on the host as for a box round: a fresh temp
  directory per round that takes the CLI's stderr as a file
  (`StderrPolicy.ToFile`; in a box, through the host-side `docker exec`
  client's stderr), whose tail the round reads into its diagnostics after the
  exit — the failure's detail on a round that did not succeed, one DEBUG line
  on one that did — and which the round removes however it ended. Owned by
  `RoundScratchDirectory` in `adapters/agent` on both media. *Not:* the
  scratch area of an execution environment (`/gnomish/scratch` in a box), nor
  the decision file's place — in container mode that file lives in the task
  branch.
- **Post-exit bound** — the one wait every reader of a child's output allows
  after the child's exit for its drains to end: the kill grace
  plus a fixed margin (`ProcessSupervisor.postExitBound()`, 7 s by default),
  carried across the environment port as `ExecHandle.postExitBound()`. The
  reader waits at most the bound for its drain to end, then stops the drain and
  flags the capture as cut; nothing is closed and no read is interrupted. On a
  real process the JDK's reaper closes the pipe at the exit, so a drain ends
  within ticks of it even while a descendant holds the pipe, and the bound is a
  safety net (ADR 0018). A bound on a wait measured from the exit, never
  from the start, and never a reader's own constant. *Not:* a deadline on the
  command — a long command is not cut short by it.
- **Cut capture** — a capture whose drain the post-exit bound cut: the drain
  had not ended when the bound expired, so the stdout held may be a prefix.
  On a clean exit (exit code 0, `EXITED`) it is refused as a value —
  `CaptureRunner.await` throws `CutCaptureException`, carrying the partial
  capture — so no parser reads a prefix as a whole answer; a failed, timed-out
  or interrupted invocation keeps its partial output readable, the cut named
  in its detail. The flag means "the drain had not ended", not "bytes lost".
  On a real process it is reached only on the safety net — a descendant that
  outwrites the reaper's own drain at the exit — since the reaper closes the
  pipe at the exit (ADR 0018). *Not:* the output cap a reader applies itself (`GitExec`'s stdout cap) — a
  limit the caller chose, not a pipe left open.
- **Launch owner** — the one component that starts a supervised OS process:
  the role `ProcessLaunch` in `:subprocess`, whose only production form is
  `ProcessLaunch.grouped()` (`GroupedLaunch`). It resolves the executable and
  its `#!` interpreter chain before the launcher (`ExecutableLookup`),
  prefixes the platform's launcher (`HostLauncher`: `setsid` on Linux, a perl
  one-liner on macOS) so the child leads a new process group, sets the child's
  stdin from its **stdin source**, and registers the group in the **live group
  registry**. Specs hand a `FakeProcess` in through the same role. *Not:* a
  plain, ungrouped launch — none exists in production; the `docker` client of
  a box command is a host process launched the same way.
- **Stdin source** — what a launched child reads as its standard input,
  handed to the launch owner with every launch (`StdinSource` in
  `:subprocess`): `StdinSource.NONE` is `/dev/null`, so the child reads
  end-of-file at once and no pipe exists for a descendant to hold;
  `StdinSource.of(bytes)` is an owner-only file in the launch owner's input
  directory, written in full before the start and deleted right after it, read
  by the child through the descriptor it inherited. The factory never writes
  into a child's stdin pipe.
- **Process group sweep** — the one post-exit kill of a launched child's
  process group (`ProcessGroupSweep` in `:subprocess`), run by every waiter
  after the wait resolves, however it resolved: `kill -KILL -- -<pid>` repeated
  while the zero-signal probe `kill -0 -- -<pid>` is answered, at most five
  rounds, ending when the probe is refused — never judged by the text of
  `kill`'s error. It ends the descendants the child lost to reparenting, which
  no snapshot of the process tree lists. Best-effort hygiene, not a fence: a
  member that changed its uid or called `setsid` itself is beyond it. *Not:*
  the cooperative tree kill of the kill grace, which it follows.
- **Live group registry** — the process-wide set of launched groups no
  process group sweep has yet found empty (`LiveGroups` in `:subprocess`): the
  launch owner registers the child's pid once the start returns, the sweep
  deregisters it on a refused probe, and only the **shutdown step** sweeps it
  whole. A launch that threw registers nothing.
- **Shutdown step** — what the factory does to its children when it is told
  to stop, for every command (`RealProcessTreeKiller.killDescendants`): the
  cooperative kill of the JVM's descendants, then a process group sweep of
  every group in the live group registry. Run by `serve`'s shutdown and, for a
  command with no hook of its own (`run`, `take`), by the generic signal hook
  before the context closes — the only way an operator's Ctrl-C reaches a child
  that, in its own session, has no controlling terminal. *Not:* a guarantee
  under `SIGKILL`, which runs no hook.

## Test stand-ins

- **Stand-in** — a committed binary under `test-fixtures/src/main/resources/stand-in/`
  that a spec hands production in place of a real one (`git`, `docker`, the
  agent CLI, a hook, a supervised process): either a preset run by the one
  table interpreter `stand-in.sh` — or one of the process-shaped scripts under
  `process/` whose signal and fork behaviour is the subject itself. Written
  once, spec'd once; a spec never writes one, and `StandIn` in `:test-fixtures`
  is the one owner that hands them out (ADR 0015). *Never:* "fake git" written
  inline in a spec, "stub script" — both name the generated-per-test shape the
  term replaces.
- **Preset** — one committed stand-in behaviour: the section `[<preset>]` of a
  table under `tables/`, several presets to a table, and nothing else — the link
  named after it that carries the name to `stand-in.sh` is made by `StandIn` once
  per JVM, never committed. The answers its rows print are sections of the files
  under `data/` (`data/stderr#unable-to-access`). It holds no
  absolute path and writes only beside a per-run link — its `$0.log`, or a
  `$0.<name>` a `write` row names — never into the library. A preset that would
  differ from another in one word takes that word from its per-run link's name
  instead (`@name`, `export-name`, `data/stderr-fetch#@name`). *Not:* a template a test fills in — a preset
  is complete as committed; not the fake agent's *scenario*, which a preset may
  play.

## Daemon loops

Introduced by `supervise-daemon-loops-and-embed-dashboard`; the reasoning is
ADR 0013.

- **Supervised daemon loop** — the factory's one shape for a long-lived thread
  that repeats work for the life of the process: a tick run on a fixed interval
  or on an interval cut short by a signal, guarded so an `Exception` from the
  tick or from the wait never ends the loop, and stoppable without racing a
  respawn — a stop ends the wait in progress, never the tick. An `Error` ends
  the worker, and the loop's **restart policy** decides: *Unbounded* respawns
  forever after a doubling backoff (the reaper, the worktree janitor, the
  sandbox sweep, the snapshot writer — loops the factory's correctness or the
  operator's view depends on); *Bounded* gives up, with one ERROR line, after
  more than N restarts within a window (an optional loop such as the
  dashboard, whose repeated death means a bug). Owned by
  `app.daemon.SupervisedLoop`, built from a `LoopShape`. *Not:* a finite thread
  (one per claim, a batch, a stream drain), nor the claim heartbeat, whose
  death is the designed degradation and is never restarted. *Never:*
  background worker, scheduler thread.

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
  the logger: command reports, the takeover confirmation, and `--json` renderings. It has one
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
- **JSON contract** — a JSON document the factory writes for a reader outside
  the writing code, carrying an integer `version` and pinned byte-exactly by a
  committed reference file: the status report, the board, the serve snapshot
  and the ledger. A version is **released** once a tagged release has shipped a
  build that writes it; a breaking change (a field renamed, removed or retyped,
  a token's meaning changed) to a released version bumps `version` and adds a
  new reference file, an additive change keeps the version and regenerates the
  reference (`docs/adr/0016-json-contract-versioning.md`). *Not:* the product
  version, which names the factory build, nor the plugin-contract version.
  *Never:* schema version, API version.

## Plugin contract

- **Community plugin registry** — `docs/community-plugins.md`: the list of
  third-party plugins built against `gnomish-plugin-api`, one row per plugin
  (name, link, what it adapts, maintainer, license). Plugins are listed as-is:
  the project neither vets nor endorses them, and each is distributed under its
  own license, not the factory's. The maintainer adds a row from a plugin
  announcement. *Not:* a marketplace or a dependency source — the factory never
  fetches anything from it.
- **Plugin announcement** — an issue opened from the plugin-announcement form
  (`.github/ISSUE_TEMPLATE/plugin-announcement.yml`) asking for a row in the
  community plugin registry; its five fields are the registry's columns. It
  needs no pull request from the plugin author.
- **SPI context** — the one value a plugin factory's single `create` receives,
  carrying every collaborator the host hands it: `TrackerAdapterContext`
  (secrets, configuration, instance id, claim epochs, time equipment) and
  `CheckClientContext` (secrets, provider subsection, run context, time
  equipment), interfaces in `gnomish-plugin-api` that the host implements. A
  collaborator the host adds later is a new accessor on the context, never a
  second `create`. Introduced by `supervise-daemon-loops-and-embed-dashboard`
  (design D21).

## Operator configuration

Introduced by `add-project-registry`; the levels and the check are ADR 0011.

- **Factory home** — the one folder holding all operator state of an
  installation: the directory named by `GNOMISH_HOME`, default `~/.gnomish`.
  It holds the host file, the host secrets, the project-less log and one folder
  per registered project (`projects/<name>/`: project file, secrets, logs,
  serve state, worktrees). Only `FactoryHome` and `ProjectLayout` know its
  folder names. *Not:* the target repository's `.gnomish/` folder, which is the
  pipeline's law. *Never:* gnomish dir, data dir.
- **Host file** — `GNOMISH_HOME/factory.yaml`: the installation's own
  `factory.*` configuration, shared by every project.
- **Project file** — `GNOMISH_HOME/projects/<name>/project.yaml`: a registered
  project's clones (`clones:`) and its `factory.*` configuration (`factory:`).
  The only source of sandbox-boundary keys.
- **Registered project** — a project the operator declared with `gnomish
  project add`: a name matching `[a-z0-9][a-z0-9._-]*`, a project file and one
  or more registered clones. The name keys the project's configuration,
  secrets, logs, serve state and worktrees.
- **Registered clone** — one clone directory listed in a project file, found by
  exact path from `--dir` once per process; the value every project-scoped
  component takes (`RegisteredClone`). *Not:* the clone directory's folder name,
  which is only the default clone name.
- **Clone name** — the name of a registered clone within its project, by
  default the last segment of its path, unique within the project; it keys the
  clone's own worktree folder `worktrees/<clone>/`, so two clones of one project
  never share a worktree.
- **Configuration level** — where a `factory.*` key may be set: `host` (host
  file or command line), `project` (project file or command line), `any`, or
  `sandbox-boundary` (project file only). Declared once per key by
  `@ConfigLevel` on its definition; a key set where its level forbids stops
  startup.
- **Sandbox-boundary key** — a `factory.*` key that selects or widens the
  sandbox a gnome runs in: `factory.bindings.*`, `factory.sandbox.image`,
  `factory.sandbox.egress-allowlist`, `factory.sandbox.env-passthrough`. Read
  from the resolved project's own project file and nowhere else, the command
  line included.

## Build verification

The gates `./gradlew check` runs over the project's own sources and specs; how to
run and answer them is the developer guide's *Per-module verification* section.

- **Spec contract** — the project's own rules for its specs, on top of what
  OpenSpec checks: every requirement carries exactly one `**Implements**:` line
  naming the requirement ids it traces to, its `**Notes**:` lines only explain
  and never say `SHALL` or `MUST`, and every scenario of a main spec is linked to
  the test that exercises it by a `// Scenario: <spec-id> / <title>` marker, or
  is listed as an exemption naming what verifies it instead. The rules live in
  `traceability.md`, `delta-specs.md` and `testing.md`; the one gate that checks
  them is the Gradle task `checkSpecContract`, run by `check`, by
  `scripts/check-spec-contract.sh` and by the fix-artifacts and archive stages.
  Introduced by `tighten-spec-contract`. *Not:* OpenSpec's own validation
  (`openspec validate`), which checks a spec's structure and knows nothing of
  traceability or tests.
- **Linux spec runner** — the committed container that runs named Gradle tasks and specs of
  the factory's own build on Linux from a developer's machine: `scripts/linux-specs.sh` over
  `scripts/linux-runner/Dockerfile`, the platform of the CI `build` job (Temurin 25, git ≥ 2.56
  built from a pinned kernel.org release by `scripts/install-git.sh`, the same script CI runs),
  on a copy of the working tree, as a non-root user. `verification-scope.md` runs platform specs
  through it before the root `check` and before a push, so CI is never the first Linux signal.
  Introduced by `make-pipe-reads-non-parking` (ADR 0018, "The method rule"). *Not:* the sandbox
  base image, the box a gnome runs in.

## Release and distribution

Introduced by `add-release-pipeline`; the release flow is the developer guide's
*Releasing* section.

- **Product version** — the version of the factory as a product: the release
  tag without its leading `v` in a release build, `0.0.0-dev` in every other
  build. Stamped into the boot jar's build info and read at runtime only by
  `FactoryVersion`; `gnomish --version`, the serve snapshot and the ledger
  report it. *Not:* the plugin-contract version, which `gnomish-plugin-api`
  declares in its own build file. *Never:* build version, app version.
- **Distribution archive** — `gnomish-<version>.tar.gz` or
  `gnomish-<version>.zip`, the file a release publishes for operators to
  install from: one top-level folder `gnomish-<version>/` holding the launcher,
  the boot jar under `lib/`, `share/examples/`, `share/sandbox-image/`,
  `LICENSE`, `NOTICE` and `README.md`. *Never:* bundle, tarball, dist.
- **Launcher** — `bin/gnomish` in the distribution archive: the committed POSIX
  `sh` script that finds Java (`JAVA_HOME`, else `PATH`), refuses one older than
  25, adds `GNOMISH_JAVA_OPTS` and passes every argument to the factory
  unchanged, injecting no factory option. *Never:* start script, wrapper script.
- **Base tag** — the tag of the sandbox base image a factory release names: in
  the recipe's `GNOMISH_BASE_TAG` default, in the contract refusal's `FROM`
  line, and among the tags the release's image job pushes. `X.Y` for a final
  release (a tag the weekly rebuild refreshes), the full version for a
  pre-release (which never moves `X.Y`); a development build has none. Computed
  once by the build (`product-version-conventions`, read at runtime through
  `FactoryVersion.baseTag()`) and, on the shell side, by
  `scripts/release-preflight.sh`. Introduced by `add-sandbox-base-image`.
  *Not:* the product version, which is `X.Y.Z` for a final release.
  *Never:* base line.
- **Release notes file** — `docs/releases/<tag>.md`: the hand-written body of
  one release, used by the release workflow instead of notes generated from the
  merged pull requests when it exists at the tagged commit. *Never:* changelog.

## Abbreviations

| Abbreviation | Meaning                                                                       |
|--------------|-------------------------------------------------------------------------------|
| ADR          | Architecture Decision Record (`docs/adr/`)                                    |
| AI           | artificial intelligence                                                       |
| API          | application programming interface                                             |
| cgroups      | Linux control groups — kernel mechanism for resource limits                   |
| CI           | continuous integration                                                        |
| CLA          | Contributor License Agreement (`CLA.md`, ADR 0009)                            |
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
| SPDX         | Software Package Data Exchange — the standard license identifiers             |
| SSRF         | server-side request forgery                                                   |
| TLS          | Transport Layer Security                                                      |
| TOCTOU       | time-of-check to time-of-use — a race between a check and the acting on it    |
| TTL          | time to live — the staleness threshold of a lease                             |
| VM           | virtual machine                                                               |
| vz           | Apple Virtualization.framework backend — hardware virtualization on macOS     |

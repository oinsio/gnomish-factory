# Proposal: supervise-daemon-loops-and-embed-dashboard

## Why

Operators who want the factory and its dashboard together today start two
processes through the `.gnomish/factory/gnomish-up` wrapper: a background
`gnomish dashboard --watch` and a foreground `gnomish serve`, glued by PIDs and
a shell trap. When the wrapper kills the renderer, the page freezes on its last
"running" frame instead of saying the daemon stopped. Folding the renderer into
`serve` as an opt-in removes the second process. A dashboard inside the daemon
also has to be a background loop that can never take the factory down, and
that exposes a gap in the daemon's existing background loops.

Of the daemon's four long-lived loops, only the standing reaper is supervised:
it catches every `Throwable` and is respawned with backoff if its thread
dies (`fix-reaper-idle-liveness`, design D4/D5). The worktree janitor and the
sandbox lifecycle tick still have the shape that change called a defect: they
catch only `RuntimeException`, sleep outside the guard, and have no stop, so
`serve`'s shutdown never stops them. The snapshot writer catches only
`RuntimeException`, has no death handler, and loops without pause if its thread
is ever interrupted. If it dies, the snapshot goes stale while the daemon keeps
working, and the dashboard reports a dead daemon. That false alarm invites
the operator to kill a factory with live slots. Each loop has its own copy of
the loop shape, and two of them are a declared hand-synchronized pair.

The operator also cannot see the WIP budget on the dashboard. It shows slot
occupancy (per instance), but not the project-wide open-front count against
the WIP limit that decides whether fresh work starts.

Building the loop's repeat suppressor on the loop's own clock exposed a second
class of defect. The factory reads "now" through two unrelated types: the
domain's one-method `Clock` port and the JDK's `java.time.Clock`, with a third
seam, `MonotonicTime`, for elapsed time. The composition root declares two
beans for one value, so every component picks one, and where the two meet a
component constructs a third. The claim heartbeat measures its repeat
suppression on a system clock its own state never saw. Six tracker writes in
the GitHub adapter and one task-branch write stamp `Instant.now()` with no seam
at all, so no spec can place them in time. Three test fakes exist for one
concept, and eleven specs build two clocks and pin them to one instant by hand.
The build gate that guards time injection sees only `.system(` calls in tests;
it is blind to `new SystemClock()` and `Clock.systemUTC()` and to production
code. The JDK settled this in 2021: `java.time.InstantSource` is the one type
to pass "to any method that requires the current instant", and the port was
written before anyone here knew it.

## What Changes

- **ADDED** a supervised daemon loop: one component owning the loop shape of
  every long-lived `serve` loop. It catches failures of the tick and the wait,
  handles interrupts the same way for every loop, stops without racing a
  respawn, and takes a restart policy when the thread dies. The policy is
  either unbounded with backoff or bounded, giving up after N restarts within
  window T.
- **MODIFIED** the standing reaper, worktree janitor, sandbox lifecycle tick
  and snapshot writer to run on the supervised daemon loop; `serve`'s shutdown
  stops all of them. The janitor and sweep tick keep their immediate-then-cadence
  behavior. The snapshot writer keeps its single-writer final `stopped` write.
  The instance heartbeat is unchanged: by design its death is not resurrected.
- **ADDED** `gnomish serve --dashboard`, the property `factory.serve.dashboard`,
  and `--dashboard-out=<path>`. The daemon renders the same page `gnomish
  dashboard --watch` renders, on a bounded supervised loop, and renders it once
  more after the final `stopped` snapshot. The standalone `gnomish dashboard`
  is unchanged.
- **ADDED** a WIP stat in the dashboard status card: open fronts against the
  project's WIP limit, from the tracker board, with a working/waiting split.
- **ADDED** durable guidance that the supervised daemon loop is the one way to
  write a long-lived daemon loop, and when it applies: an ADR, a process rule
  loaded into every session, and a glossary entry.
- **MODIFIED** `.gnomish/factory/gnomish-up` to use `serve --dashboard` instead
  of a background renderer process. Browser opening and the log follower stay in
  the script.
- **ADDED** one time source: every production component that reads the current
  instant takes `java.time.InstantSource`. The domain `Clock` port, the
  `SystemClock` adapter, the `SuppressorClock` bridge and the second "now" bean
  are removed. Real time enters production in the composition root only; every
  hidden `Clock.systemUTC()`, `new SystemClock()`, `Instant.now()` and
  convenience `X.system()` call outside it is routed through the injected
  source. A build gate keeps it so.
- **ADDED** one carrier for real time's two halves — the current instant and
  waiting: a time-equipment value (instant source plus sleeper) built once in
  the composition root and handed to every component that needs both. The
  real sleeper becomes constructible in the composition root only, and no
  convenience factory that wires real time (`X.system()`) remains anywhere.
- **MODIFIED** the plugin SPI: each provider factory (tracker, check) exposes one
  `create(<context>)` method taking a host-built context object (secrets,
  config, instance identity, claim epochs, time equipment) instead of a chain
  of overloads that grows with every host-provided collaborator. This is a
  breaking change of `gnomish-plugin-api`, taken deliberately: no third-party
  plugin exists yet, so the better shape is cheaper now than compatibility.
- **MODIFIED** the test-time gate (`checkTestTimeInjection`) to flag every way of
  building a real clock or a real sleeper in a test source, and the three time
  fakes merge into one virtual instant source.

## Goals

- **G1** — No long-lived `serve` loop can die silently: every one either keeps
  running, is respawned, or reports an explicit give-up, each as a logged
  operator event.
- **G2** — One command starts the factory with its dashboard; the operator can
  still run either alone.
- **G3** — The operator sees the WIP budget (open fronts / limit) on the page.
- **G4** — The loop shape exists once; no hand-synchronized copies remain.
- **G5** — Real time — the current instant and waiting — has one type each,
  one carrier for the pair, and one production source: a component that
  stamps state and measures an interval about that state does both on the same
  instant source, and every such component runs on virtual time under test.
- **G6** — The plugin SPI grows by adding an accessor to a context object,
  never by adding an overload; the host hands every plugin the same time
  equipment it uses itself.

## Non-Goals

- **NG1** — Supervising the instance heartbeat. Its death degrades to stale
  claims that a reaper returns (design D3 of `add-claim-heartbeat`); that
  stays.
- **NG2** — Supervising finite threads: slot threads, take batches, the feed
  thread, shutdown hooks, subprocess and stream pumps.
- **NG3** — Opening a browser from the factory, or following the log file.
- **NG4** — A separate "Ready for work" block. Ready rows stay inside the
  in-progress block (FR5 of `redesign-dashboard`).
- **NG5** — Stage, attempt or escalation reason in the waiting-for-a-human and
  in-progress blocks (a tracker port extension, a separate change).
- **NG6** — New snapshot fields. Restart counts of the janitor, the sweep tick
  and the writer are reported in the log only; the reaper's existing
  `restartCount` is kept.
- **NG7** — An HTTP server for the dashboard; the page stays a `file://` page.
- **NG8** — Routing elapsed-time measurement (`System.nanoTime()`) through the
  `MonotonicTime` seam. Wall time and monotonic time stay separate concepts;
  the raw `nanoTime` sites are recorded as declared exemptions with reasons,
  and their routing is a later change.
- **NG9** — Changing the `Sleeper` port's interface. It covers what an instant
  source does not (waiting), and its contract stays as it is; only where its
  real implementation may be constructed changes (FR22).
- **NG10** — Backward compatibility of the plugin SPI `create` methods. No
  third-party plugin exists; the in-repo implementors (GitHub, in-memory, the
  sample plugin) move in the same change.

## Users & Scenarios

- **U1** — An operator starts the factory for a working session and wants the
  page in a browser: `gnomish-up` (or `gnomish serve --dashboard`) and one
  `Ctrl-C` stops everything, leaving a page that says "stopped".
- **U2** — An operator runs the daemon headless on a server and the dashboard
  on a workstation, as two processes, exactly as today.
- **U3** — An operator sets `factory.serve.dashboard: true` in the host file
  so every `serve` on that machine renders the page without a flag.
- **U4** — A rare `Error` kills the snapshot writer's thread mid-run; the
  operator sees an ERROR naming the restart, and the page keeps showing a live
  daemon instead of a false "daemon dead".
- **U5** — The fresh queue is idle; the operator glances at the status card,
  sees `WIP 10 / 10` with three tasks waiting for a human, and goes to answer
  escalations.
- **U6** — A developer writes a component that needs the current instant. There
  is one type to take, one fake to drive it with, and the build fails if a real
  clock is constructed anywhere but the composition root.

## Requirements

### Functional

- **FR1** — A supervised daemon loop SHALL run a tick on a cadence: either
  tick first, then wait, or wait first, then tick. The wait SHALL be either a
  fixed interval or an interval cut short by a wake signal, with surplus
  signals coalesced.
- **FR2** — A failure thrown by the tick or by the wait (any `Throwable`) SHALL
  be logged at WARN with a stable operator event, and the loop SHALL continue
  with its next wait.
- **FR3** — If the loop's thread dies anyway, the loop's restart policy
  SHALL decide what happens. *Unbounded*: respawn after an exponential backoff,
  logging ERROR with a rising restart count, never giving up. *Bounded*: the
  same, but after more than N restarts within window T, log one ERROR saying
  the loop is disabled and stop respawning. The process keeps running in
  both cases.
- **FR4** — A stop SHALL end the loop without a WARN or ERROR, SHALL let a
  tick in progress finish rather than interrupt it, SHALL prevent
  any later respawn, even one already backing off, and SHALL offer a variant
  that returns only after the current thread, including a respawned one, has
  exited.
- **FR5** — An interrupt on a loop's thread SHALL end the loop when a stop was
  requested; otherwise it SHALL be cleared and logged, and the loop SHALL keep
  its cadence. It SHALL never spin without waiting.
- **FR6** — The standing reaper, worktree janitor, sandbox lifecycle tick and
  snapshot writer SHALL run as supervised daemon loops with the Unbounded
  policy, keeping their current cadence, order and log component.
  `serve`'s shutdown SHALL stop all four.
- **FR7** — The snapshot writer SHALL remain the only writer of the snapshot
  file across respawns: no write SHALL land after its final `stopped` write.
- **FR8** — `gnomish serve` SHALL accept `--dashboard` and
  `--dashboard-out=<path>`, and read `factory.serve.dashboard` (default
  `false`). The flag SHALL enable the dashboard regardless of the property.
  `--dashboard-out` without the dashboard enabled SHALL be a usage error.
- **FR9** — With the dashboard enabled, `serve` SHALL render the same page as
  `gnomish dashboard --watch` for the same instance: same composition, same
  cadences, same default output path. It SHALL render on a supervised loop
  with the Bounded policy and print the page path at startup.
- **FR10** — The embedded dashboard SHALL read the board through its own
  read-only tracker client, built from the tracker configuration `serve`
  bound at startup. Its failures SHALL NOT count as failures of the daemon's
  tracker.
- **FR11** — On shutdown, after the final `stopped` snapshot, the embedded
  dashboard SHALL render once more, so the page shows the stopped state.
- **FR12** — The dashboard status card SHALL show a WIP stat: the board's
  open-front count against the WIP limit the board judged eligibility by.
  The tooltip SHALL hold the exact counts with the working and
  waiting-for-a-human split.
- **FR13** — The WIP stat SHALL render whenever a board model exists, the
  cached one included, even when no snapshot exists. It SHALL be absent when no
  board fetch has ever succeeded.
- **FR14** — `gnomish board --json` output SHALL stay unchanged.
- **FR15** — `.gnomish/factory/gnomish-up` SHALL start the dashboard through
  `serve --dashboard`, keeping its browser and log-follower behavior.
- **FR16** — The project SHALL document the supervised daemon loop as the
  required way to write a long-lived daemon loop. The documentation SHALL state
  when to use it, when not to (with the standing exemptions and their reasons),
  and how to choose its restart policy. It SHALL live where future authors read
  before writing code (an ADR, a process rule, the glossary), and the gate that
  enforces it SHALL be named.
- **FR17** — Every production component that reads the current instant SHALL
  take `java.time.InstantSource`. No other type for "now" SHALL exist in the
  codebase: the domain `Clock` port and its `SystemClock` adapter are removed,
  and no adapter between two clock types remains.
- **FR18** — Real time SHALL enter production only in the composition root.
  Outside it, no production code SHALL call `InstantSource.system()`,
  `Clock.systemUTC()`, `Instant.now()`, construct the real sleeper
  (`new ThreadSleeper()`), or call a convenience factory that wires one.
  Every component that today builds its own real clock or real sleeper,
  including through an `X.system()` factory called from an ordinary
  constructor or field, SHALL receive its time source instead. A build gate
  SHALL fail on any new such site.
- **FR19** — A component that stamps state with an instant and measures an
  interval about that state (a repeat-suppression window, a staleness window)
  SHALL measure it on the same instant source it stamps with. The claim
  heartbeat's repeat suppressor is the known violation and SHALL be fixed.
- **FR20** — Tracker and task-branch writes that stamp a time (GitHub markers,
  heartbeat comments, decisions, the task file's `createdAt`) SHALL read it from
  the injected instant source, never from `Instant.now()`.
- **FR21** — One virtual instant source in `:test-fixtures` SHALL serve every
  spec. The test-time gate SHALL flag `Clock.systemUTC(`, `InstantSource.system(`
  and `new SystemClock(` in test sources as it flags `.system(` today, with the
  same in-place justification marker for the few legitimate uses.
- **FR22** — Real time's two halves SHALL travel as one value: a component
  that needs both the current instant and waiting SHALL take one
  time-equipment value (instant source plus sleeper) rather than the pair as
  two parameters. The value SHALL be built once, in the composition root, and
  the real sleeper SHALL be constructible nowhere else (by type, not by
  convention). No convenience factory that wires real time (`X.system()`)
  SHALL exist in production code; every retry, suppressor and loop policy
  SHALL be built from the time equipment it is handed.
- **FR23** — Each plugin SPI factory (tracker adapter, check client) SHALL
  expose exactly one `create` method, taking a host-built context object that
  carries every host-provided collaborator (secrets, configuration, instance
  identity, claim epochs, time equipment). Adding a collaborator later SHALL
  mean adding an accessor to the context, never an overload. The in-repo
  implementors and the sample plugin SHALL move to the new shape in this
  change, and the plugin API version and compatibility baseline SHALL record
  the break.

### Non-Functional: Reliability

- **NFR-R1** — No failure of the embedded dashboard, of any kind, SHALL stop,
  delay or fail task work, the feed, or the other daemon loops.
- **NFR-R2** — Shutdown and respawn SHALL NOT race: a stopped loop never runs
  another tick.
- **NFR-R3** — No state lock of the supervised loop SHALL be held across a
  tick, a wait or a backoff (`lock-scope.md`).
- **NFR-R4** — Every time-dependent production component SHALL be drivable on
  virtual time by a spec, so no spec waits out a production bound or pins two
  clocks to one instant by hand.

### Non-Functional: Observability

- **NFR-O1** — Every tick failure, respawn, give-up and stop-caused end SHALL
  be one log line with a stable operator event and the loop's `component`
  MDC key; shutdown-caused ends follow the existing "interrupt is a stop"
  classification (no WARN/ERROR).
- **NFR-O2** — The startup anchor SHALL record whether the dashboard is
  enabled and where it writes.

### Non-Functional: Performance

- **NFR-P1** — The embedded dashboard SHALL add no tracker reads beyond the
  standalone `--watch` cadence (one `listReady` + `listOpen` pair per board
  interval).

### Non-Functional: Security

- **NFR-S1** — The embedded dashboard SHALL read tracker configuration only
  from what `serve` bound from origin's default branch, never re-read from the
  checkout (FR13 of `add-base-ref-resolution`).

### Non-Functional: Cost

- No AI tokens are involved; the tracker read budget is NFR-P1.

## Operator Experience Criteria

- **UX1** — `gnomish serve --dashboard` prints one line naming the page file
  before the first claim.
- **UX2** — After `Ctrl-C`, the open page shows the stopped state, not a frozen
  "running".
- **UX3** — The status card reads `slots 2 / 4  WIP 5 / 10  consecutive
  failures 0`; hovering WIP shows `5 of 10 open fronts: 2 working, 3 waiting for
  a human`. No alarm styling at any value.
- **UX4** — A disabled embedded dashboard is one ERROR line saying so and why;
  the daemon keeps serving.
- **UX5** — The operator guide states that a `serve --dashboard` and a
  standalone `dashboard --watch` writing the same file overwrite each other.

## Success Metrics

- **M1** — Hand-written long-lived daemon loops in `serve` (own
  `Thread.ofVirtual().name("gnomish-…")` start plus a `while` loop): 4 → 0,
  outside the supervised loop component and the declared exemptions.
- **M2** — Declared sync pairs for the daemon loop shape: 1 → 0.
- **M3** — Processes started by `gnomish-up`: renderer + daemon → daemon only.
- **M4** — In a spec where a loop's thread is killed by an `Error`, the loop
  is ticking again within one backoff: 4 of 4 loops (today 1 of 4).
- **M5** — In a real-thread spec racing a writer death against the final
  write, writes landing after the final `stopped` write: 0.
- **M6** — Types a production component may take for "now": 2 (`domain…Clock`,
  `java.time.Clock`) → 1 (`InstantSource`). Beans for it in the composition
  root: 2 → 1.
- **M7** — Production sites outside the composition root that construct real
  time (`Clock.systemUTC()`, `new SystemClock()`, `Instant.now()`,
  `new ThreadSleeper()`, or an `X.system()` factory called from an ordinary
  constructor or field): about 47 at proposal time (40 clocks listed in design
  D17, 7 sleepers in D20) → 0, by the gate's grep.
- **M9** — Convenience factories wiring real time (`static … system()` on a
  retry or suppressor): 3 → 0. Production signatures carrying the instant
  source and the sleeper as two parameters: 8 → 0.
- **M10** — `create` overloads on the plugin SPI factories: 3 (tracker) + 2
  (check) → 1 + 1.
- **M8** — Test fakes for the current instant: 3 (`VirtualClock`,
  `MovableClock`, `StepClock`) → 1, plus the declared layering copy in
  `:logtext`. Specs building two clocks for one flow: 11 → 0.

## Open Questions

- **Q1** — Values of the Bounded policy for the dashboard (N restarts within
  T). Proposal: 5 within 10 minutes. Settle in design.
- **Q2** — Backoff cap for the snapshot writer. It is the operator's only window
  into the daemon, so it may need a shorter cap than the reaper's 10 minutes.
  Settle in design.

## Capabilities

### New Capabilities

- `daemon-supervision`: the supervised daemon loop — tick/wait shape, failure
  guard, interrupt handling, stop, and restart policies — and which `serve`
  loops it governs.
- `time-source`: one type for the current instant, one carrier for the
  instant source and the sleeper, one production source in the composition
  root, one virtual fake, and the gates that keep it so.

### Modified Capabilities

- `factory-serve`: the `serve` command surface gains the dashboard options;
  shutdown stops every supervised loop; the embedded dashboard lifecycle.
- `observability/dashboard-page`: the status card gains the WIP stat; the page
  may be rendered by `serve` with a final stopped render.
- `serve-observability`: the snapshot writer survives its thread's death and
  keeps the single-writer guarantee across respawns.
- `stage-engine`: the engine's `Clock` environment port becomes
  `java.time.InstantSource`; the `Sleeper` port is unchanged.
- `plugin/plugin-discovery`: SPI factories receive their dependencies through
  one context object per `create`, not as a growing argument list.
- `plugin/plugin-api-contract`: a breaking version bump with a regenerated
  compatibility baseline for the `create` reshape.

## Impact

- `:application` — `app/lease/StandingReaper`, `RestartBackoff`;
  `app/serve/WorktreeJanitor`, `SandboxLifecycleTick`, `ServeShutdown`;
  `app/ServeCommand`, `ServeArguments(Parser)`, `ServeAssembly`,
  `ServeShutdownWiring`, `DashboardCommand`, `BoardCommand`, `TrackerWiring`;
  `ServeProperties`; `serveobservability/writer/SnapshotWriter`;
  `dashboard/*` (watch loop, status card, renderer); `board/BoardModel`,
  `BoardComposition`, `board/json/BoardJsonMapper`; `status/DaemonComponent`;
  `OperatorEvent` vocabulary.
- `:bootstrap` — Spring wiring for the dashboard option; two new architecture
  specs (daemon loops, time source); one `InstantSource` bean replaces two.
- Time source (FR17–FR21) — `:domain` loses `engine/port/Clock` and
  `engine/time/SystemClock`; every module that imported the port or
  `java.time.Clock` (`:application`, `:adapters`, `adapters/git`,
  `adapters/github`, `sandbox/*`, `:logtext`, `:bootstrap`) takes
  `InstantSource`; `:test-fixtures` keeps one fake; `build-logic`'s
  `TestTimeInjectionCheck` widens; `adapters/github` and `adapters/git` tracker
  and branch writes receive the source.
- Time equipment (FR22) — `:domain` gains `engine/time/TimeEquipment` and
  loses `engine/time/ThreadSleeper`, which moves to `:bootstrap`; the retry
  and suppressor factories `TerminalWriteRetry.system()` and
  `GitInfrastructureRetry.system()` are deleted; every holder of the
  instant-source-plus-sleeper pair (`:application` assemblies, loops,
  heartbeat, reaper, seams; `sandbox/docker` `BoxTiming`) takes the carrier;
  `:test-fixtures` builds its virtual retries on the same type.
- Plugin SPI (FR23) — `gnomish-plugin-api` 0.10.0 → 0.11.0 with a regenerated
  `compat-baseline/`; `TrackerAdapterFactory`, `CheckClientFactory` and the
  two new context types; implementors `adapters/github` (tracker and check
  factories), `adapters` (in-memory tracker factory, `http` check provider), the sample plugin; the
  callers `TrackerWiring`, `CheckEquipment` and
  `ProviderDispatchingExternalCheckClient` receive the time equipment.
- Docs — new `docs/adr/0013-supervised-daemon-loop.md`, new
  `docs/adr/0014-one-time-source.md`, new
  `.claude/rules/daemon-loops.md` (and its row in `CLAUDE.md`);
  `.claude/rules/testing.md` ("Time is injected");
  `docs/guides/operator-guide-serve.md`, `operator-guide-observability.md`,
  `operator-guide-dashboard.md`,
  `docs/glossary.md`;
  `.gnomish/factory/gnomish-up`, `.gnomish/README.md`.
- No new dependencies. No wire-format change (snapshot, ledger, board JSON).
- Sequencing: lands after `make-checkpoint-gate-durable`, which appends an
  operator-event code to the same catalog; this change's codes are numbered
  after it.

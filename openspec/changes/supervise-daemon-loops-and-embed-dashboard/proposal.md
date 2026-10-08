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

## Goals

- **G1** — No long-lived `serve` loop can die silently: every one either keeps
  running, is respawned, or reports an explicit give-up, each as a logged
  operator event.
- **G2** — One command starts the factory with its dashboard; the operator can
  still run either alone.
- **G3** — The operator sees the WIP budget (open fronts / limit) on the page.
- **G4** — The loop shape exists once; no hand-synchronized copies remain.

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
- **FR4** — A stop SHALL end the loop without a WARN or ERROR, SHALL prevent
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

### Non-Functional: Reliability

- **NFR-R1** — No failure of the embedded dashboard, of any kind, SHALL stop,
  delay or fail task work, the feed, or the other daemon loops.
- **NFR-R2** — Shutdown and respawn SHALL NOT race: a stopped loop never runs
  another tick.
- **NFR-R3** — No state lock of the supervised loop SHALL be held across a
  tick, a wait or a backoff (`lock-scope.md`).

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

### Modified Capabilities

- `factory-serve`: the `serve` command surface gains the dashboard options;
  shutdown stops every supervised loop; the embedded dashboard lifecycle.
- `observability/dashboard-page`: the status card gains the WIP stat; the page
  may be rendered by `serve` with a final stopped render.
- `serve-observability`: the snapshot writer survives its thread's death and
  keeps the single-writer guarantee across respawns.

## Impact

- `:application` — `app/lease/StandingReaper`, `RestartBackoff`;
  `app/serve/WorktreeJanitor`, `SandboxLifecycleTick`, `ServeShutdown`;
  `app/ServeCommand`, `ServeArguments(Parser)`, `ServeAssembly`,
  `ServeShutdownWiring`, `DashboardCommand`, `BoardCommand`, `TrackerWiring`;
  `ServeProperties`; `serveobservability/writer/SnapshotWriter`;
  `dashboard/*` (watch loop, status card, renderer); `board/BoardModel`,
  `BoardComposition`, `board/json/BoardJsonMapper`; `status/DaemonComponent`;
  `OperatorEvent` vocabulary.
- `:bootstrap` — Spring wiring for the dashboard option; a new architecture spec.
- Docs — new `docs/adr/0013-supervised-daemon-loop.md`, new
  `.claude/rules/daemon-loops.md` (and its row in `CLAUDE.md`);
  `docs/guides/operator-guide-serve.md`, `operator-guide-observability.md`,
  `docs/glossary.md`;
  `.gnomish/factory/gnomish-up`, `.gnomish/README.md`.
- No new dependencies. No wire-format change (snapshot, ledger, board JSON).

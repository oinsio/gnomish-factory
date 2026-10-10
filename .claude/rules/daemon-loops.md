# Rule: every daemon loop is a supervised daemon loop

Applies to every thread in production code that lives as long as the process or the daemon and
repeats work on a cadence or a signal. The decision, its alternatives and the restart-policy
reasoning live in `docs/adr/0013-supervised-daemon-loop.md`; the term is defined in
`docs/glossary.md` ("Supervised daemon loop"). This file is the checklist for adding or reviewing
one.

## The rule

**A daemon loop is built on `app.daemon.SupervisedLoop` and on nothing else.** The loop class
keeps its own tick and its own state, and holds one `SupervisedLoop` built from a
`LoopShape(DaemonComponent, LoopOrder, LoopWait, RestartPolicy)`, its tick and the
`TimeEquipment` it already holds (ADR 0014). It does not start a thread, write a `while`, catch
its own failures, build its own `RepeatSuppressor` or log its own death: the loop owns the thread,
the order of tick and wait, the guard (`catch (Exception)` around both; an `Error` ends the worker
and the restart policy decides), the stop, the restart, the operator events (`DAEMON_LOOP_*`,
GF152–GF156, raised only by `LoopEvents`) and the `component` MDC key.

## The failure this rule exists for

Before `supervise-daemon-loops-and-embed-dashboard`, each of the four `serve` loops (the standing
reaper, the worktree janitor, the sandbox lifecycle sweep, the snapshot writer) started its own
thread and held its own copy of the loop shape. Three caught only `RuntimeException` and slept
outside the guard, so an `Error` or a throwing sleeper ended the thread; two had no stop; the
writer had no death handler and spun without waiting after an interrupt. A dead loop fails
nothing: the daemon keeps claiming and running tasks while one of its duties has silently
stopped, and every unit spec of every loop stays green. A dead snapshot writer even made the
dashboard report a dead daemon over live work.

## Not a daemon loop: the exemptions

A thread is exempt when it is **finite** (it ends when its one job ends) or when **its death is
the designed degradation**. Five cases exist today. The first four are the gate's allowlist,
each file with its reason; the fifth lies outside the gate's scope.

| Case                                   | Where                                     | Why it is not supervised                                                                                                   |
|----------------------------------------|-------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| A slot: one thread per claimed task    | `app/serve/FeedCycle.java`                | finite: it ends with its task                                                                                              |
| A batch of take runs                   | `app/TakeBatch.java`                      | finite: it ends with the batch                                                                                             |
| The feed thread and JVM shutdown hooks | `app/ServeShutdownWiring.java`            | the feed thread *is* the daemon's lifetime; a hook runs once at exit                                                       |
| The claim heartbeat worker             | `app/lease/HeldClaims.java`               | its death is the designed degradation: claims expire and the lease path (ADR 0002) returns them; a restart would hide that |
| A stream or subprocess pump (drain)    | other modules, not `application/src/main` | finite: it ends when the stream closes                                                                                     |

`SupervisedLoop.java` itself is the fifth allowlisted file: the owner, not an exemption. A new
exemption is a new allowlist entry with its reason, argued in the change that adds it against
the two tests above — "it was simpler" is not a reason.

## Choosing the shape

- **Order.** `TICK_THEN_WAIT` by default (janitor, sweep, snapshot writer, dashboard).
  `WAIT_THEN_TICK` only when a startup path already did the first run's work (the reaper).
- **Wait.** `LoopWait.FixedInterval(Sleeper, Duration)` for a fixed cadence.
  `LoopWait.IntervalOrSignal(Duration)` when the loop must also wake early on demand; its
  `signal()` cuts the wait short and surplus signals coalesce into one wake (the snapshot
  writer's `markDirty()`). One `LoopWait` belongs to one loop.
- **Restart policy.** Ask what repeated death means — the ADR's table is the authority:
  - `RestartPolicy.Unbounded(base)` when the factory's correctness or the operator's view
    depends on the loop. It respawns forever after a doubling backoff. Pick `base` so one death
    stays inside what readers tolerate (the writer uses its interval, inside the dashboard's
    `3 × interval` staleness window).
  - `RestartPolicy.Bounded(base, maxRestarts, window, clock)` for an optional loop whose
    repeated death means a bug a restart will not cure (the dashboard: 5 restarts in 10 minutes).
  - No supervision at all only when death is the designed degradation — that is an exemption
    (above), not a policy.
- **Restart cap.** Nothing to choose: both policies cap the backoff at
  `RestartBackoff.MAX_BACKOFF`, the one ceiling every loop shares.
- **Roll-up period.** Nothing to choose: the loop derives it from its interval through
  `RollUpPeriod.forInterval`. Do not pass a period, and do not read the catalog default.
- **Time.** Through the `TimeEquipment` the class already takes (`testing.md`, "Time is injected
  in tests"), never real time.

## Adding a `DaemonComponent` constant

Every loop is named by a constant of `status/DaemonComponent`, whose key is what its log lines
carry (`grep component=<key>`). A new loop adds a constant with a one-line javadoc, never a string
literal at the call site, and in the same change updates the three places that enumerate the
set: `DaemonComponentSpec` (the closed vocabulary feature), the key list in `logging.md` ("A
daemon worker sets `component`"), and the class javadoc's count of workers.

## Specs

A loop class's specs cover its **tick** only, driven directly; the loop's own behavior is already
pinned once, in `application`'s `app/daemon` specs: `SupervisedLoopSpec` (order, guard, log
edges and roll-ups, on virtual time), `SupervisedLoopWaitSpec` and `SupervisedLoopInterruptSpec`
(the waits, signal coalescing, stray interrupts), `SupervisedLoopRestartSpec` and
`SupervisedLoopBoundedSpec` (the two policies), `SupervisedLoopStopSpec` and the two
`SupervisedLoopStop*ConcurrencySpec`s (real threads: a stop never races a respawn). Do not
re-test the guard's semantics per loop — the order, the log edges and roll-ups, the backoff, the
policies. One wiring spec per loop does earn its place: that the class really hands its tick and
shape to a running loop (`SnapshotWriterSupervisionSpec` and `SnapshotWriterComponentMdcSpec` are
the models). A failing tick is how that wiring is observed, so the loop's lifecycle spec may drive
one: a tick that throws an `Exception` and the loop running again under its own `component`, and
a tick that throws an `Error` and the respawned loop ticking again after one backoff — the
per-loop scenarios the `daemon-supervision` capability names (`WorktreeJanitorLifecycleSpec` and
`SandboxLifecycleTickLifecycleSpec` are the models). Those features assert that the loop survived,
or was respawned, as itself — not how the guard did it. A fixture kills a worker with a plain
`Error`; a throwable whose message throws belongs only to `SupervisedLoopDeathLineSpec`, where the
death line's own rendering is the subject.

## How this is checked

`DaemonLoopOwnerBoundarySpec` (`:bootstrap`) scans `application/src/main`, the layer that holds
every daemon loop, with comments stripped:

- `Thread.ofVirtual(`, `Thread.ofPlatform(` and `new Thread(` appear only in `SupervisedLoop.java`
  and the four exempt files above;
- no file spells `Executors.newScheduled`, `Executors.newSingleThreadScheduled`,
  `ScheduledExecutorService` or `new Timer(` — a scheduled task that throws cancels its own
  later runs without a word, the silent death this rule removes;
- `new RestartBackoff(` appears only in `RestartPolicy.java` and
  `app/serve/RemoteOutageProbeSchedule.java` (a probe backoff, not a loop);
- the loop files and `app/lease/BeatTiming.java` never spell `RepeatSuppressor.system(` or
  `new RepeatSuppressor(`, and `DEFAULT_ROLL_UP_INTERVAL` is read only in `RollUpPeriod.java`;
- every allowlisted file is asserted reached, so a moved file fails instead of widening the gate.

The gate cannot see a loop that runs on a thread someone else started — a `while` with a sleep
inside a method a caller runs on its own thread — nor a loop outside `application/src/main`. So,
as with `lock-scope.md`, review owns the rest: the reviewer and `/audit-codebase` check every
`while` around a sleep or a wait, every new thread outside `application`, and every new allowlist
entry against this file.

## Checklist

1. Does the thread live as long as the process and repeat work? If not, it is finite — say so.
2. Is it exempt? Then it is one of the five cases, or a new allowlist entry with its reason.
3. Otherwise: one `SupervisedLoop` from a `LoopShape`; no thread, `while`, catch or suppressor of
   its own; time from `TimeEquipment`.
4. Order, wait and restart policy chosen by the questions above, the policy matching ADR 0013.
5. A `DaemonComponent` constant, with `DaemonComponentSpec` and `logging.md` updated.
6. Specs for the tick and one wiring spec; `DaemonLoopOwnerBoundarySpec` green.

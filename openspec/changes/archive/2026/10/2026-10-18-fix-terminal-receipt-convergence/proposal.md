# Proposal: fix-terminal-receipt-convergence

Sequenced after `make-checkpoint-gate-durable` (archived 2026-10-09; it rewrote the resume
routes and the kill-point rows this change touches) and after `tighten-spec-contract` (archived
2026-10-14; it re-cut the three MODIFIED requirements below): both are in `openspec/specs/` now,
and every MODIFIED block restates that stable text. Alongside `add-claim-return` (which edits the
tracker-take return protocol; this change touches neither its requirement nor its verb).

## Why

Two terminal transitions of a `take` run end with a receipt the branch records only after the
tracker effect landed: a park (outcome commit → tracker park → marker cleared) and a finish
(outcome commit → tracker finish → cleanup commit). The 2026-10-06 audit found that both
recoveries decide from the **tracker's current state** — and by the time any pickup runs, a
fresh claim has already changed that state to `Working`. So a park killed after its tracker
write landed but before the marker cleared is probed as "absent" and re-driven: the task the
human just returned is parked again, and the human must return it a second time. A finish
killed after its tracker write landed is never picked up at all: a finished task is refused by
every disposition, so its `CompletedUncleaned` branch keeps the `.gnomish-task/` envelope
forever and its worktree is never disposed. `gnomish run --resume` of a completed task has the
same gap on its own medium: it reports and exits, never cleaning. The kill-point matrix did not
catch any of this because its pickups skip the claim — they probe a tracker still saying
`AwaitingHuman`, which production never sees.

The fact that survives a claim is not the state but the **tenure boundary**: the last
PARK/FINISH/ABORT/STALE_CLAIM_REMOVED marker on the task's thread, whatever claims follow it. The
GitHub adapter already derives `returned` and `finished` structurally from the whole thread the
same way. What the feed's shape facts carry is a different, claim-scoped fact — the boundary
*after the newest claim*, null by the time a pickup probes — so the receipt probes cannot borrow
it; they need the thread's last boundary, which no adapter reports today.

Implementing the first task surfaced a gap in the spec-contract gate itself. `testing.md` asks
for a scenario's marker to be written with its test, but `checkSpecContract` resolves a marker
only against the main specs, so a marker naming a scenario of this change's own delta is
reported as dangling until the archive: on 2026-10-14 the six markers of the new `tracker-port`
scenarios turned the gate red. This is the first change implemented since the gate landed; every
change after it would hit the same wall. The same gate also ignored a REMOVED requirement until
the archive: once this change removed "Cleanup on completion", two of its scenarios with no
successor still demanded a link, and the only way to keep the gate green was an exemption line
naming no mechanism plus a task to delete it, and a stale marker, at the archive.

The implementation audit of 2026-10-14 found the claimless cleanup failing on a clone that holds
the task branch only as a remote-tracking ref: the readers classify the branch from the ref the
locator found, the writers derive `refs/heads/`, and the step between — making the branch local —
exists three times (the host's `worktree add` of a short name, which git resolves itself; the
container's `git branch`; nothing on the claimless path). A second factory home therefore cannot
converge a finished task at all, and a `serve` janitor warns about such a branch on every tick.
The rule of three applies: one owner of **branch adoption**, used by every write path.

The architect review of 2026-10-15 (task 6.5) found the janitor's branch pass deciding from a
reading the medium does not guarantee. The pass lists the clone's `gnomish/*` refs without a
fetch and keeps only `CompletedUncleaned` tips; a branch the clone holds only as a remote-tracking
ref carries no factory register unless the factory's own touchpoint fetch brought one, so it
classifies `Unknown` and is skipped — silently, with no log line. The factory clone is an
ordinary clone (only the container's seed clone is `--single-branch`), so an operator's `git
fetch`, a `git pull`, or an IDE's periodic fetch (IntelliJ every 20 minutes, VS Code every 3)
brings every task branch into that state deterministically. `take <ref>` and `run --resume`
converge the same branch because their locate fetches the register first. One label, `Unknown`,
covered two states with different recovery: "the pair is present and inconsistent" (a diverged
register, quarantine) and "the pair is incomplete, not yet fetched" (fetch the register). The
same half-pair reaches a local head an operator checked out by hand, where `take` parks the task
as an infrastructure failure. The janitor is the only daemon path that converges a lost cleanup;
a silent skip there is a dead duty with a green build.

The final gate of this change (task 6.9) surfaced a defect of the test build: every PIT minion
and test JVM that touched Docker ran the operator's Docker credential helper
(`docker-credential-desktop get`, configured by `"credsStore": "desktop"` in
`~/.docker/config.json`). On macOS each call raises a system prompt that blocks the helper until
a human answers, so an unattended gate can stall on it; the same inherited configuration also
hands the operator's CLI plugin hooks and `proxies` block to the tests (a `proxies` block reaches
every container `docker run` creates, and `ContainerModeIsolationE2ESpec` asserts the exact
container environment). The test build already refuses the operator's git configuration
(`GIT_CONFIG_GLOBAL`) and factory variables (`GNOMISH_*`); Docker's client configuration is the
third medium of the same leak. It is folded in here because this change's gate is where it
stalls, and the cut is self-contained (§7).

The Linux gate of task 7.4 (2026-10-16, CI run 38879822474) failed on
`ProcessSupervisorInterruptSpec`, a spec this change had not touched; the architect session it
triggered found a class, not a flake. On Unix a pipe belongs to whoever holds a descriptor to it,
so a grandchild that inherited the child's stdout keeps it open after the child exited. The JDK
handles that exit on its reaper thread: `ProcessPipeInputStream.processExited` is `synchronized`,
drains what is available, closes the descriptor and swaps in a memory buffer — so a drain already
parked in `read()` holds the monitor and blocks the reaper until the grandchild closes the pipe,
while a drain that enters `read()` a few milliseconds later gets end-of-file and sees nothing the
grandchild writes (open bugs JDK-8169565 and JDK-8330773; the window measured on a workstation is
2–12 ms). Two halves follow. In the tests, three specs state "the drain entered `read()` first"
as a premise and fail under a loaded runner, and four more hide the same race behind a sleep in a
stand-in (`trailing.sh`'s 0.5 s linger, added for a CI failure of 2026-09-13, is the precedent).
In production, the question "when is the capture complete after the exit" has seven answers:
`CaptureRunner`/`Drain` and `ContainerFileChannel`/`ExecPipeDrain` join without a bound,
`CapturedExec` and `GitExec` (its stdout read, and its stderr pump joined after the wait) join
without a bound and cannot be interrupted, `StreamDrain` and `BoundedTail` are bounded; on the
write side, five writers pump a child's stdin — `GitExec`'s feed and the file channel's pump,
joined without a bound, `ChildProcessStdin` from the two environments' `exec`, fire-and-forget,
and `CaptureRunner`'s write on the calling thread before the wait — and none of them can be
bounded at all (a write parked on a full pipe holds the stream's monitor). The command deadline bounds only the wait, never the join after it:
measured, `CaptureRunner` returns `EXITED` exactly when the holder dies — never, for a daemon. Go
solved exactly this in 1.20 (`Cmd.WaitDelay`: one bound after the exit on both paths, then close
the parent's pipe ends, then a visible "may be truncated" outcome), and both shipped adopters pair
it with a process-group kill, because `ProcessHandle.descendants()` cannot see a reparented
orphan. A side finding of the same audit: agent rounds pass `mergeStderr=false` and nothing reads
the agent process's stderr, so a chatty CLI blocks on a full pipe until the round timeout. It is
folded in here because this change's own gate is where the class surfaced, and the cut is
self-contained (§8).

## What Changes

- **MODIFIED**: the tracker port's `fetchTask` carries the latest tenure boundary (park,
  finish, abort, stale-claim removal) beside the current state, on every adapter, under the port
  contract suite.
- **MODIFIED**: the park and finish receipt probes read the boundary, not the state: a landed
  park whose receipt was lost is confirmed, never re-driven; the human's return stands.
- **ADDED**: a delivered task whose cleanup never landed converges without a claim — through an
  explicit `take <ref>`, through `run --resume`, and through the `serve` worktree cleaner's
  branch pass — with one owner of the claimless route over one cleanup commit writer on both
  media, the bare-object one; the host's worktree-based cleanup writer is removed.
- **ADDED**: **task branch adoption** — one owner makes a branch held only as a remote-tracking
  ref local, by a ref transaction that creates the head under a compare-and-swap on absence;
  every write path, claimed or claimless, host or container, adopts through it before it
  writes. A head adoption created whose closing push origin rejects stays as it is — a diverged local line in one clone, a terminal shape every reader judges by its registered
  tip; nothing is undone and nothing removes it.
  The host's name resolution by git and the container's `git branch` go.
- **MODIFIED**: the `serve` worktree cleaner gains a branch pass over the clone's task
  branches: a `CompletedUncleaned` tip and a tip whose register the clone lacks are both handed
  to the claimless owner, which reads the branch through the fetching locate before it decides;
  nothing is skipped silently; its age pass still consults no tracker.
- **ADDED**: the branch shape **`Unregistered`** — a tip that carries an envelope while the
  clone holds no factory register for the task — is its own shape, whose recovery is the
  touchpoint fetch; `Unknown` is kept for a pair that is present and inconsistent (a register
  that is not an ancestor of the tip). A local-only listing (`status`) names it with its remedy.
- **MODIFIED**: the kill-point harness picks up through the production route — a human return
  (or the reaper) to `Ready`, then a claim by a *second* instance — so the two windows this
  change fixes are red before it and green after.
- **MODIFIED**: the spec-contract gate resolves a scenario marker against the scenarios of
  unarchived deltas as well as the main specs, and treats the main-spec scenarios of a
  requirement an unarchived delta REMOVES as retired: no link required; a marker or an
  exemption line naming one is still accepted, and the archive's own gate run reports any left.

- **MODIFIED**: every forked test JVM — each `Test` task and the `pitest` task with its minions —
  runs under the build's own Docker client configuration: a committed configuration with no
  credential helper, no hooks and no proxies, copied per task, and the operator's daemon endpoint
  handed on explicitly as `DOCKER_HOST` (with the socket path Testcontainers mounts on macOS), so
  the result of a test run depends on the operator's Docker setup only for where the daemon is.

- **ADDED**: the subprocess supervisor bounds the drain after an exit on both paths — normal and
  killed — by one bound derived from the kill grace, then closes the parent's ends of the pipes,
  so a drain parked on a pipe a descendant still holds returns and the JDK's reaper is freed; the
  capture says when it may be a prefix. One launch owner starts every supervised process, and
  specs drive the supervisor through a fake process rather than through a scheduling race.
- **ADDED**: every reader of a child's output builds on that one drain and that one post-exit
  bound: `GitExec` (stdout and stderr), `CapturedExec`, `ContainerFileChannel`, `BoundedTail` and
  the agent round's `StreamDrain` are migrated, `ExecPipeDrain` is deleted into `Drain`; a reader
  outside the supervision module takes the bound from the handle it holds; no reader of a child's
  output waits without a bound or without an interrupt; and no stdin pump exists at all — the
  launch owner gives every child its standard input from a file or from `/dev/null`, so nothing
  of the factory writes into a pipe a descendant could hold (five writers deleted).
- **ADDED**: a supervised process is launched in a process group of its own, and after its exit —
  however it ended — the group is swept, so a reparented orphan the descendant snapshot cannot see
  dies with the invocation; the launch owner has one production form, since every process it
  starts is a host process — the `docker` client of a box command included — and nothing inside a
  box is swept by the host, whose PID namespace ends with the box.
- **ADDED**: the agent round's stderr goes to a file in the round scratch directory — a host
  directory per round, on both media — read after the exit for diagnostics, instead of a pipe
  nothing drains.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `tracker-port`: "Task facts from fetchTask" (latest tenure boundary), "Port contract spec
  suite binds every adapter" (the new fact is in the suite).
- `tracker-take`: "A Completed-without-cleanup tip is finished, never re-executed"
  (convergence without a claim, from any clone of origin), new requirement "Receipts are
  confirmed from tenure history, not from the current state".
- `manual-run`: "Resume invocation" — completed → finish the cleanup if it is owed, then report.
- `factory-serve`: "Worktree cleaner disposes aged task environments" — a branch pass over
  `CompletedUncleaned` branches; the age pass keeps consulting no tracker.
- `git-task-persistence`: "Cleanup on completion" is removed and replaced by "Cleanup commit
  on completion" — one cleanup commit writer on both media, built from the tip's tree objects;
  the two scenarios of the host's staged worktree removal go, since MODIFIED cannot drop them;
  new requirement "Task branch adoption".
- `quality-gates`: "Scenario-to-test link" — a marker may name a scenario of an ADDED or
  MODIFIED requirement in an unarchived delta; the scenarios of a requirement an unarchived
  delta removes are retired (no link required; markers and exemption lines naming them still
  accepted); "Forked test JVMs carry no operator-process variables" — the Docker client
  configuration variables are replaced, not passed through; new requirement "Forked test JVMs
  use the build's own Docker client configuration".
- `subprocess-supervision`: new requirements "Post-exit output is bounded", "One launch owner",
  "Launched processes form their own process group".
- `agent-executor`: new requirement "The round's stderr is kept".

## Goals

- G1: no human returns a task twice because the factory lost a receipt.
- G2: every `CompletedUncleaned` branch converges to `Delivered` without anyone claiming a
  finished task.
- G3: the kill-point matrix reproduces production pickups.
- G4: a scenario marker written with its test keeps the spec-contract gate green from the task
  that writes it to the archive, and a removed requirement needs no marker or exemption kept
  alive until the archive.
- G5: a finished task's branch converges from any clone of origin, whether or not that clone
  holds the branch locally, and whoever fetched the branch into that clone — the factory, an
  operator, or an IDE.
- G6: no daemon duty is skipped silently: a branch the janitor's pass cannot judge is read
  through the fetching path, and one the fetch still cannot judge is named in the log.

- G7: a test run neither stops on nor depends on the operator's Docker client configuration —
  no credential helper, plugin hook, proxy block or context reaches a test process — on a
  developer's macOS machine and on the Linux CI runner alike.
- G8: no supervised invocation outlives its child by more than the post-exit bound, whoever still
  holds the child's pipes.
- G9: no spec of the supervisor states a scheduling order as a premise: the four race-premise
  specs pass on a loaded two-CPU runner as on a workstation.

## Non-Goals

- NG1: the pre-park windows and the stale `outcome` field — `make-checkpoint-gate-durable`.
- NG2: the fenced return itself — `add-claim-return`.
- NG3: any new tracker write; this change adds a read fact and reorders nothing.
- NG4: the divergence policy is unchanged — a claimless path still fails closed on a diverged
  local line, including the one this change's own adoption leaves behind when another clone
  delivers the cleanup first (FR9); that line has no recovery owner by design — one local ref, terminal and inert — and nothing
  here undoes, force-pushes or re-pushes it. Picked up by the change that gives claimless paths
  an arbitration of their own, or that routes a `Delivered` tip under a claim through the
  divergence reconcile.
- NG5: a **local** head without its register — an operator's `git checkout gnomish/<t>` in the
  factory clone — still classifies and parks as today: the locate of a local branch fetches
  nothing, so the register-following reconcile runs after the classification has decided. The
  `Unregistered` shape names the state; its drain on a local head is picked up by the change that
  next touches the local arm of the task-branch locate or the quarantine park.
- NG6: no fetch refspec is written into the clone's `remote.origin.fetch`: outside `refs/heads`
  and `refs/tags` git forces the update without `+`, so an IDE fetch would roll back a register
  ahead of origin's and `fetch.prune` would delete it (design D13). Rejected, not deferred.
- NG7: the claimed take's double locate — its routing classifies through a locate of its own and
  its resume bootstrap locates again, two touchpoint fetches per pickup of a tracking-only
  branch — stays; this change adds the port read that removes it (design D13, amended for task
  6.6). Picked up by the change that next touches the claimed take's routing (`TakeWorkRouter`)
  or its resume bootstrap.

- NG8: the factory's own `docker` invocations are unchanged: `DockerCli` keeps inheriting the
  factory process's environment, since the shipped factory relies on the operator's context and
  credential helper to pull `factory.sandbox.image` and the guard image. Only the test build
  changes.
- NG9: `~/.testcontainers.properties` is still read: a `tc.host` there is the operator's explicit
  choice and wins over `DOCKER_HOST`. Testcontainers reads it by `user.home`, which the test JVM
  must keep for every other tool; nothing in the repository writes that file.
- NG10: Windows, and any fix on the JDK's side (a wait-delay API, JDK-8330773): the factory runs
  on Linux and macOS, and the bound and the close are the factory's own.
- NG11: output a grandchild writes after the post-exit bound is dropped — the JDK drops it already
  whenever the drain lost the monitor; this change makes the loss bounded and visible, not absent.
  Picked up by the change that needs a grandchild's output as a result, which then owns that
  pipe itself (an `os.Pipe` of its own, as Go's maintainers put it).
- NG12: file redirection for the agent round's stdout — rejected, not deferred: `StreamDrain`
  parses stream-json per line while the process runs (`fix-round-stdout-drain`, D4), and a file
  has no live tail. The round's stderr takes the file (FR20).
- NG13: a concurrent drain of the agent's stderr with byte and line caps — the line
  `extract-agent-round-runner`'s delta carries today. That change is sequenced after this one and
  inherits `StderrPolicy.ToFile` and the round scratch directory (design D18); its line is the
  second drain NG12 rejects, and its proposal takes the matching sequencing statement in its own
  revision, not in this change's files.
- NG14: a whitelist of spawn sites in `ProcessSpawnBoundarySpec` (`add-subprocess-access-log`,
  design D14). After FR18 the whitelist is the launch owner alone, and `LaunchOwnerGateSpec` is
  that gate; `ProcessSpawnBoundarySpec` stays the ArchUnit package rule it is today. That change
  revises its D14 to cite the owner's gate when it is next updated.
- NG16: removing the input instead of the pump — one git process per commit over files
  (`hash-object -w -- <paths>`, `update-index --index-info`) instead of one `hash-object --stdin`
  per blob, and the round prompt through a CLI argument or a prompt file. Each removes consumers
  of the stdin source, not the source: the register transaction is stdin by git's contract and a
  prompt can exceed `ARG_MAX`. Picked up when a measurement attributes a round's git cost to the
  per-blob launches, or when every supported agent CLI documents a prompt-file argument.
  Streaming or interactive stdin is a non-goal of the same kind: every input the factory gives a
  child is complete before the launch.
- NG17: a memory-backed input (`memfd_create`) — Linux only and native; the file's window on
  disk is the launch itself, so the second medium buys milliseconds for a declared pair.
- NG15: bounding `GitExec`'s wait itself (NG3 of `bound-subprocess-commands` holds: local
  plumbing stays undeadlined); only its post-exit joins are bounded (FR19). NG4 of that change is
  taken up in part by FR19 — tail reader and stdout read on the shared mechanics — and the cap
  policy stays local, as it asked; the stdin feeds are not shared but removed (FR23, design
  D20) — the bytes a caller gives a child stay the caller's, only their medium moves to the
  launch owner.

## Users & Scenarios

- U1: **A reviewer** answers a question and moves the task back to ready; the factory crashed
  right after posting the question. The next pickup continues with their answer instead of
  re-asking.
- U2: **A maintainer** merging a delivered branch finds no `.gnomish-task/` directory in it even
  though the delivering instance died between the finish and the cleanup.
- U3: **A pipeline author** reading the kill-point table trusts that "second pickup is a no-op"
  was asserted the way production picks up.
- U4: **An operator** whose IDE fetches the factory clone every few minutes sees every finished
  task's branch cleaned by the next `serve` tick, and `status` names a branch the clone has not
  fetched the register of as exactly that, with the command that fetches it.
- U5: **An operator** whose stage's check command starts a background server that keeps the
  command's stdout gets a verdict when the command exits, not when the server stops; the slot
  moves on.

## Requirements

### Functional

- FR1: `fetchTask` SHALL carry the task's **tenure boundary** — the last `PARK`, `FINISH`,
  `ABORT` or `STALE_CLAIM_REMOVED` marker on the thread, or none — derived from the tracker's
  durable history and unchanged by any later claim; it is distinct from the claim-scoped
  boundary the shape facts carry. Every adapter SHALL pass the contract suite feature for it.
- FR2: the park receipt probe SHALL report "landed" when the latest boundary is `PARK`, and
  "absent" when it is `STALE_CLAIM_REMOVED` or none; it SHALL no longer read the current
  state.
- FR3: the finish receipt probe SHALL report "landed" when the latest boundary is `FINISH` or
  the `finished` fact is set.
- FR4: a `CompletedUncleaned` branch whose tracker reports finished SHALL converge — cleanup
  commit, push, workspace disposal — through one owner, reached by: `take <ref>` on a finished
  task (instead of refusing with nothing done), `run --resume` on a completed task, and the
  `serve` worktree cleaner's branch pass over the clone's task branches. None of these SHALL
  claim the task; the owner writes no tracker effect and runs under no claim guard, over one
  cleanup commit writer on both media.
- FR5: the kill-point harness SHALL pick up every park and finish row through the production
  route: the tracker returned to `Ready` (by the harness as the human, or by the reaper for a
  lost park), then a claim by a second instance with its own tenure record, then the real
  routing; the rows for the two windows above SHALL assert the human's return is not undone
  and the cleanup lands.
- FR6: `checkSpecContract` SHALL resolve a `// Scenario:` marker against the scenarios of the
  main specs and of the ADDED and MODIFIED requirements in the deltas of unarchived changes,
  with or without `--changes`; a title found only under a REMOVED requirement or only in an
  archived delta SHALL stay dangling. A main-spec scenario whose requirement the delta of an
  unarchived change REMOVES (matched by requirement title within the same capability path) is
  **retired**: it SHALL NOT be required to be linked. Retirement SHALL only relax the gate: a
  marker or an exemption line naming a retired scenario SHALL be neither dangling nor stale, and
  the set a marker resolves against and the set the exemption list is asserted reached against
  SHALL stay as above. Otherwise the set of scenarios that must be linked SHALL stay the main
  specs'.
- FR7: a task branch a clone holds only as `refs/remotes/origin/gnomish/<b>` SHALL be made local
  by one owner, the task-branch port's `ensureLocalTaskBranch`, as one ref transaction:
  `create refs/heads/<b> <tracking tip>` — a compare-and-swap on absence — with the clone's
  register unchanged; no `git branch`, no `worktree add` of a name git must resolve itself, no
  checkout. The owner SHALL answer with a typed result: adopted at a tip, already local, or
  nowhere; a `create` lost to another adopter SHALL answer already local, not fail.
- FR8: every path that writes the branch SHALL adopt before it writes: the host take and resume
  bootstraps (before the worktree is materialized, which SHALL take an existing local branch
  only), both container bootstraps, and the claimless cleanup; the two ref prefixes
  (`refs/heads/`, `refs/remotes/origin/`) SHALL be spelled by one owner.
- FR9: a head adoption created whose closing push origin rejects as non-fast-forward — another
  clone delivered first — SHALL stay as the clone holds it: no undo, no second push, no second
  write of the branch. The clone's later claimless passes SHALL read it as `Delivered` and write
  nothing; a claimed take that reaches the task (only when the tracker lost the finish) SHALL report it
  delivered and leave the line standing; a manual `run
  --resume` on that clone SHALL fail closed on the divergence (NG4). A push that did not reach
  origin SHALL leave the head for the next touchpoint, as any closing commit today.
- FR10: a branch tip that carries an envelope while the clone holds no factory register for the
  task SHALL classify as **`Unregistered`**, a shape of its own, whose recovery owner is the
  touchpoint fetch and whose disposition is quarantine: a reader that still sees it after its
  own read parks it as it parks `Unknown`. `Unknown` SHALL no longer cover a missing register;
  it keeps the diverged register and the legal-but-unrecognized combination. Every per-shape reader SHALL name the new shape explicitly (the sealed
  set grows by one); `status` SHALL render it with the command that fetches the register.
- FR11: the janitor's branch pass SHALL forward to the claimless owner every branch whose shape a
  domain table marks as forwarded — `CompletedUncleaned` and `Unregistered` — and no other; the
  owner SHALL read the branch through the fetching locate first, classify at the register it
  landed, read the tracker only for a `CompletedUncleaned` tip, then adopt and clean. A branch
  the pass does not forward SHALL cost no fetch and no tracker read.
- FR12: an `Unregistered` branch that stays `Unregistered` after the owner's read — origin
  delivered no register, origin could not be reached, or a local head the locate does not fetch
  (NG5) — SHALL be reported once per pass in one aggregate line naming the branches and the
  location kind of each, never asserting which cause holds, and left to the budgeted quarantine owner as an `Unknown` tip is today; nothing in
  the pass SHALL skip a branch without a log line.

- FR13: every forked test JVM (each `Test` task, the `pitest` task and the minions it forks) SHALL
  run with `DOCKER_CONFIG` naming a per-task copy of one committed client configuration that
  names no credential helper (`credsStore`, `credHelpers`), no plugin hooks and no `proxies`;
  the operator's `DOCKER_CONFIG` and `DOCKER_CONTEXT` SHALL NOT reach it.
- FR14: the build SHALL hand those JVMs the operator's daemon endpoint as `DOCKER_HOST`: the
  operator's own `DOCKER_HOST` unchanged when set, otherwise the endpoint of the operator's
  current context, resolved under the operator's configuration by `docker context inspect`. On
  a macOS host with a `unix://` endpoint it SHALL also set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`
  to `/var/run/docker.sock` unless the operator set it. An endpoint it cannot hand on as one
  variable — an `ssh://` host, a context with TLS material — SHALL fail the build with a message
  naming the variables to set; with no `docker` on the machine, or no daemon behind the context,
  it SHALL set no endpoint and the Docker specs skip as before.
- FR15: the per-task copy SHALL find the operator's CLI plugins: its `cliPluginsExtraDirs` names
  the `cli-plugins` directory of the operator's configuration directory when that directory
  exists, so `docker build` resolves `buildx` as it does outside the tests.

- FR16: after a supervised invocation's exit — normal, expired or interrupted — the supervisor
  SHALL join each drain for at most one **post-exit bound**, derived from the kill grace plus a
  fixed margin, then close the parent's ends of both pipes, so a drain parked on a pipe a
  descendant still holds returns and the JDK reaper's `processExited` is released; the capture
  SHALL be what was read until then.
- FR17: a capture whose drain was cut by the bound SHALL say so with a flag on the result,
  distinct from the termination and the exit code; the runner SHALL log it once with its catalog
  code as the holder's attribution. An invocation that exited on its own with code zero and a cut
  drain SHALL fail with a typed refusal carrying the capture (the prefix, stderr, which stream,
  the bound), raised by one owner in `:subprocess`, so that no reader anywhere consumes the
  prefix as a value — a reader of the exit code alone included, deliberately, since the runner
  cannot tell the two apart and every external effect is recovered through its receipt. An
  invocation that exited non-zero, timed out or was interrupted SHALL keep its own failure, with
  its partial output readable and the cut named in the failure's detail, never as a second
  finding (the exit error wins). *Amended 2026-10-17 (`/architect` Mode C on task 8.10's
  blocker): the original clause "a caller SHALL never fail an otherwise successful command on
  it" was written for diagnostic output; applied to the runners it let the flag die at the first
  reader while 51 call sites parsed the prefix as a value.*
- FR18: every supervised process SHALL be started by one **launch owner** in `:subprocess`, a role
  interface over `ProcessBuilder.start()`; a spec SHALL be able to hand the owner a fake process
  whose exit and whose pipes it controls with latches, so no spec of the supervisor states the
  order of a drain's first read and the child's exit as a premise.
- FR19: every production reader of a child's stdout or stderr — `CaptureRunner`, `GitExec` (its
  stdout read and its stderr pump), `CapturedExec`, `ContainerFileChannel`, `StreamDrain`,
  `BoundedTail` — SHALL take the drain and its post-exit bound from the one owner; a reader
  outside `:subprocess` SHALL take the bound from the handle it holds (`ExecHandle.postExitBound()`),
  never from a constant of its own — nor from an installation property: `StreamDrain`'s
  `factory.agent-cli-tail-drain-grace` is retired (design D17 as amended 2026-10-17); no reader SHALL join a drain without a bound, none SHALL join
  uninterruptibly, and `ExecPipeDrain` SHALL be deleted.
- FR20: the agent round SHALL direct the CLI's stderr to a file in the round scratch directory — a
  host directory per round, owned by the round on both media — and read it after the exit into
  the round's diagnostics — on a round that does not succeed the tail of that file SHALL ride the
  failure's detail, on a successful round it SHALL be logged at DEBUG only (design D18 as amended
  2026-10-17); the round SHALL remove the directory on every exit path; the round
  SHALL never leave the CLI's stderr as a pipe nothing reads.
- FR21: on the host, the launch owner SHALL start a supervised process as the leader of a new
  process group (`setsid(1)` on Linux, `/usr/bin/perl` with `POSIX::setsid` on macOS), so the
  group id equals the child's pid; after the exit the supervisor SHALL signal the group with
  `SIGKILL` until the signal finds no process, within a fixed number of rounds; a launch whose
  helper is missing SHALL fail the invocation naming the helper, never run ungrouped. *Amended
  2026-10-17:* before prefixing the launcher, the owner SHALL resolve the command's executable
  as `execvp` would — an absolute or relative path checked for existence and the execute bit, a
  bare name searched on the child's `PATH` and never on the working directory — and fail the
  launch with the JDK's `IOException` naming the binary when nothing is found, so a missing
  binary keeps the classification it had before the launcher (a thrown launch failure, never an
  exit code); for the residue the resolution cannot see (`ENOEXEC`, a missing interpreter), a
  launcher exit of 126 or 127 **together with** the launcher's own stderr text SHALL be
  classified as a launch failure — never the exit code alone, which a program may return itself;
  the sweep SHALL run only over a process that was started, with its pid asserted above one, and
  its `kill` SHALL be started by the owner ungrouped; the group kill is best-effort hygiene: a
  member that changed its uid or called `setsid` itself is beyond it; a child in its own session
  has no controlling terminal, so the factory's own termination path is the only way a signal
  reaches it; *amended 2026-10-17 (fourth):* that path SHALL exist for every command — at the
  factory's exit the shutdown step SHALL kill the descendant snapshot cooperatively, then sweep
  every group no waiter has swept yet.
- FR22: the launch owner SHALL have one production form: every process it starts is a host
  process — the `docker` client of a box command included — and is grouped and swept like any
  other; nothing inside a box SHALL be swept by the host, since the box's PID namespace ends with
  the box; no second implementation of the owner SHALL exist in production sources.
- FR23: the launch owner SHALL set every child's standard input itself, from a typed source the
  caller passes: with no input, `/dev/null`, so no pipe exists for a descendant to inherit; with
  input, a file created atomically with owner-only permissions in the input directory the launch owner is given (the JVM's temp directory by default),
  written in full before the start and deleted right after it, so the child and its descendants
  read it through the inherited descriptor and no thread of the factory writes into a pipe; no
  production source but the owner's SHALL open a child's stdin stream or set an input redirect;
  a launch whose input file cannot be written SHALL fail before any process starts, as an
  infrastructure failure.

### Non-Functional Performance

- NFR-P1: a clean exit with no holder SHALL cost no extra wait: the drains end at end-of-file and
  the join returns at once; only a held pipe pays the bound, once per invocation.

### Non-Functional Reliability

- NFR-R1: the cleanup of a finished task SHALL be idempotent across instances: two instances
  converging the same branch produce one cleanup commit; the loser's write fails without force
  and the loser reports the task done. On the host as in the container, no step before the ref
  transaction SHALL take a lock another instance can hold (the task worktree's index lock
  included). Across clones as well: two instances in different factory homes adopting and
  cleaning one branch land one cleanup commit at origin, and the loser stands down.
- NFR-R2: PIT stays at 100% in every touched module.
- NFR-R3: the janitor's handling of a half-pair SHALL be convergent: after one fetch the register
  is in the clone for good, so the next tick classifies the branch by its content and the set of
  `Unregistered` branches empties on its own, except for branches origin carries no register
  for and local heads (NG5).
- NFR-R4: the four race-premise specs (`ProcessSupervisorInterruptSpec`, `CaptureRunnerDrainSpec`,
  `GitExecPumpThreadsSpec`, `ShellCommandCheckRunnerTimeoutSpec`) SHALL pass 20 of 20 runs under a
  CPU load of two busy threads on the developer's machine; `trailing.sh` SHALL lose its 0.5 s
  linger, and the spec that needed it SHALL assert ordering through the fake process.

### Non-Functional Observability

- NFR-O1: a receipt confirmed from history logs one DEBUG line; a cleanup performed without a
  claim logs one INFO line with the task id.
- NFR-O2: the branch pass logs one DEBUG line per branch it does not forward, naming the shape,
  and one aggregate line per pass for the branches that stayed `Unregistered` after the owner's read
  (FR12); a healthy pass over delivered branches logs nothing above DEBUG.
- NFR-O3: a cut drain logs one WARN with its catalog code, the command and the bound — the
  holder's attribution, emitted whether or not the refusal of FR17 follows; the refusal itself is
  logged by the layer that handles it, as any invocation failure (two facts, not one failure
  logged twice); a group sweep that found a survivor logs one DEBUG per sweep naming the count; a
  healthy invocation logs nothing above DEBUG.

### Non-Functional Cost

- NFR-C1: the boundary fact SHALL cost no extra tracker call: it is derived from the comment
  thread `fetchTask` already reads.
- NFR-C2: the branch pass SHALL cost one touchpoint fetch per forwarded branch per tick and one
  tracker read per `CompletedUncleaned` tip, never a fetch or a tracker read for a branch it does
  not forward; under a remote outage the existing gate bounds the fetches.

- NFR-C3: the endpoint resolution SHALL cost at most one `docker` process per build; the
  endpoint, the socket override and the plugin directory SHALL NOT be task inputs, so a test
  task's cache key is the same on macOS and on Linux; the committed configuration is an input by
  content.

## Operator Experience Criteria

- UX1: a returned task is never re-parked with the same report; the thread shows one park
  comment per park.
- UX2: a delivered branch never carries `.gnomish-task/` once the factory has been run against
  the project again — including a branch an operator's or an IDE's `git fetch` brought into the
  `serve` clone.
- UX3: `status` on a clone whose task branches were fetched by hand lists them as
  `Unregistered` with the command that fetches the register, never as "an unrecognized
  combination".

- UX4: a full `./gradlew check` on a macOS machine whose Docker configuration names
  `"credsStore": "desktop"` raises no "would like to access data from other apps" prompt.
- UX5: a check command that leaves a background process holding its output ends its stage within
  the post-exit bound, with a verdict and one log line saying the output may be a prefix.

## Success Metrics

- M1: `ParkKillPoints` and `FinishKillPoints` rows pass with claim-through pickups; the
  pre-change code fails their new assertions (recorded in the task).
- M2: `grep -rn "TrackerTaskState.AwaitingHuman" application/src/main/java/com/github/oinsio/gnomish/app/take/GuardedPark.java`
  returns nothing.
- M3: `grep -rn "finishCleanup(" application/src/main bootstrap/src/main` shows calls in exactly
  five files, the allowlist of design D5: the owner `DeliveredCleanup` and the manual-run
  exemption's chain (`GitOutcomeRecorder`; `ContainerTerminalDrive` → `ContainerRunSupport` →
  `ContainerRunTermination`); the port declarations and the git adapters are outside the count.
- M6: `grep -rn '"branch", \|"worktree", "add"' adapters/git/src/main` — `"branch"` only in
  `TaskBranchCreator` (a fresh task's branch), `worktree add` only in `TaskWorktreeManager`, on
  a branch adoption made local first.
- M7: `grep -rn '"refs/heads/\|"refs/remotes/origin/' adapters/git/src/main application/src/main bootstrap/src/main`
  shows hits only in the prefix owner of design D5 (the `gittransfer` leaf's own `GitPush` and
  `RegisterRef` are outside the count).
- M5: `grep -rn 'CleanupCommit' adapters/git/src/main` shows no hit; `DeliveredCleanupRaceSpec`
  carries no `@PendingFeature` and both its features are green.
- M8: `grep -rn "instanceof BranchShape\." application/src/main/java/com/github/oinsio/gnomish/app/serve/DeliveredBranchPass.java`
  returns nothing: the pass forwards by the domain table alone (FR11).
- M9: `DeliveredCleanupIdentitySpec`'s janitor rows on a second clone seed the branch as a
  plain `git fetch` leaves it — no register, no `locate` run by the fixture — and pass.
- M4: `./gradlew checkSpecContract` is green with the six `tracker-port` markers of task 1.1 in
  place, before the change is archived, with
  `config/spec-contract-exemptions/git-git-task-persistence.txt` absent and no marker naming
  "Kill between the staged removal and its commit converges on the same worktree" (task 0.3).

- M10: `TestDockerEnvironmentFunctionalSpec` (`build-logic`) and `TestDockerEnvironmentSpec`
  (`:bootstrap`) are green on the developer's macOS machine and in the Linux CI job.
- M11: during a full `./gradlew check` on macOS with `"credsStore": "desktop"`, no
  `docker-credential-desktop` process is started by a test JVM (sampled `ps` and the TCC log over
  the run, recorded in task 7.4), and the Docker suites named in task 7.4 are green.
- M12: `grep -rn "\.join()" subprocess/src/main gitobjects/src/main sandbox/core/src/main sandbox/docker/src/main adapters/agent/src/main adapters/src/main/java/com/github/oinsio/gnomish/adapter/check`
  shows an unbounded join nowhere; `grep -rn "ExecPipeDrain" --include='*.java' --include='*.groovy' .`
  returns nothing.
- M13: `grep -rn "\.start()" --include='*.java' */src/main */*/src/main` on a `ProcessBuilder`
  shows the launch owner only; `DockerCli.start` and `HostTaskExecutionEnvironment.exec` route
  through it; `grep -rn "implements ProcessLaunch" --include='*.java' */src/main */*/src/main`
  shows the owner's one form; `grep -rn "getOutputStream()\|redirectInput(" --include='*.java' */src/main */*/src/main`
  shows the owner's file only, and `ChildProcessStdin` exists nowhere.
- M14: on the developer's macOS machine, 20 runs of the four specs of NFR-R4 by name with two busy
  threads: 80 of 80 green; the Linux CI job green on the pushed commit.
- M15: a stand-in that exits and leaves `sleep 600 &` holding stdout (`exit-leaky.sh`):
  `CaptureRunner.run` returns within the post-exit bound plus 100 ms, with the holder alive before
  the sweep and gone after it on the host; the timings recorded in the task. The per-launch cost
  of the sweep's probe is measured on `:gitobjects` plumbing (a commit of N blobs: N
  `hash-object` launches) and recorded in task 8.20 with the decision it drove; the observed
  drain time after a clean exit is recorded in 8.24, so the 7 s bound is a measured margin, not
  an asserted one.
- M16: `grep -rn "truncated()" --include='*.java' */src/main */*/src/main` shows the flag read in
  `:subprocess`'s owner and the two runners' report paths only — no result type outside
  `:subprocess` carries it, and no parser can reach a prefix; `ObjectId.of` refuses a string that
  is not 40 or 64 hex characters.

## Impact

- `gnomish-plugin-api`: `TrackerTask` (latest boundary), `Tracker` javadoc.
- `adapters/github`: `GithubTaskFetcher` / `GithubIssueDetail*` (boundary derived from the
  whole marker thread, beside `GithubHistoryFactReader`); `:adapters`
  (`adapter/tracker/inmemory`): `InMemoryTracker.fetchTask` reads the boundary from the
  task's thread.
- `:application`: `GuardedPark`, `FinishEffect`, `TakeDisposition`, `TakeLoadedBranchRoutes`
  (the cleanup handed to `TakeReconcileFinish`), `TakeEngineExecution` (fresh-finish cleanup),
  `ResumeMechanics` and its host/container implementations (`finishCleanup` removed),
  `GitResumeContinuation` / `ContainerResumeOutcomes` completed arms, new `DeliveredCleanup`,
  `serve/WorktreeJanitor`.
- `:bootstrap`: `TakeKillPointWorld` and the kill-point rows, port contract suite feature,
  `DeliveredCleanup` wiring, `ReceiptProbeGateSpec`.
- `build-logic`: `SpecContractCheck` and its scenario-link rule (delta scenario titles and
  REMOVED requirement titles become an input regardless of `--changes`), functional specs in
  `build-logic/src/functionalTest`.
- `adapters/git`: `GitTaskRepository.finishCleanup` and `GitTaskStore` (the host cleanup commit
  through the bare-object writer), `CleanupCommit` removed; `:gitobjects`: `GitObjects` class
  javadoc (any `.git`, the host clone included), `CommitMetadata.gitDate` (`@<epoch> +0000`,
  so a commit stamped near the epoch is not read as a calendar date).
- Branch adoption (FR7–FR9): `adapters/git` — `GitTaskBranches.ensureLocalTaskBranch` over a
  new `TaskBranchAdoption`, `ContainerResumeBranch` (its `git branch` arm deleted),
  `TaskWorktreeManager.ensureWorktree` (existing-branch form), the prefix owner `TaskBranchRefs`
  taking the literals of `TaskBranchLocator`, `ReplicaPairReconciler`, `TaskBranchLister`,
  `BaseRefresh`, `FactoryRegister` (`GitObjectsTaskRepository.refFor` and `FactoryRegister.branchRef` deleted);
  `:application` — port `TaskBranchGit` (typed result; no undo), `TaskResumeBootstrap`,
  `GitResumeRunner`, `TakeContainerResumeBootstrap`, `ContainerResumeRunner`,
  `ClaimlessDeliveredCleanup`, `DeliveredCleanup`; `:bootstrap` — `ReceiptProbeGateSpec`
  (branch-command and prefix gates), identity, race and cross-clone specs; `docs/glossary.md`
  (**branch adoption**), operator guide.
- The `Unregistered` shape (FR10–FR12): `:domain` — `BranchShape` (one more record in the
  sealed set, the forwarding table beside `recoveryOwner()`), `BranchShapeClassifier`
  (`TipRelation.NoRegister` over an envelope), `RecoveryOwner` / `RecoveryDisposition` (the
  touchpoint fetch as an owner); `:application` — `serve/DeliveredBranchPass` (forwards by the
  table, logs what it does not forward), `ClaimlessDeliveredCleanup` (locate → classify →
  `fetchTask` → adopt → clean), `branch/BranchShapeDiagnosis` and `BranchRepairLog` (the new
  arm); every exhaustive switch over `BranchShape` in `:application` and `:bootstrap`;
  `docs/glossary.md` (**Unregistered**), `.claude/rules/crash-consistency.md` (item 15);
  main-spec deltas `lifecycle/task-branch-contract`, `git/git-task-persistence`,
  `cli/task-inspection`, `serve/factory-serve`.
- `config/spec-contract-exemptions/git-git-task-persistence.txt`: the interim file task 3.5
  wrote for a removed scenario is deleted (task 0.3); `TransitionKillPointSpec` loses the marker
  of the removed staged-removal scenario.
- `.claude/rules/testing.md` ("Every scenario has a test"); `.claude/rules/manual-sync-pairs.md`
  (a new consumer of a declared pair), `.claude/rules/design-decisions.md` (the single-owner
  table's Requires column), `.claude/rules/lock-scope.md` (writers from different processes),
  `.claude/rules/proposal-format.md` (a deferral names its trigger).
- Docs: `docs/adr/0003-crash-consistency.md` (receipt probes read history), `docs/glossary.md`
  (**tenure boundary**), tracker adapter author guide, operator guides.

- Test build (D14): `build-logic` — `TestDockerEnvironment` (new owner beside
  `AdversarialGitConfig`), its convention applied by `test-conventions` to every `Test` task and
  to `pitest`, `TestEnvironmentHygieneFunctionalSpec` (its pass-through stand-in no longer
  `DOCKER_CONFIG`), new `TestDockerEnvironmentFunctionalSpec`;
  `test-fixtures/src/main/resources/docker-config/config.json` (committed); `:bootstrap` — new
  `TestDockerEnvironmentSpec`; `test-fixtures` — `TestChildEnvironment` unchanged (it already
  carries `DOCKER_HOST`, `DOCKER_CONFIG` and `TESTCONTAINERS_*` to children);
  `.claude/rules/testing.md` ("Test processes never inherit the operator's environment").

- Post-exit bound and process group (D15–D19, §8): `:subprocess` — `CaptureRunner`, `Drain` (the
  bound, the close, the flag on `Captured`), `ProcessSupervisor.postExitBound()`, the launch owner
  in its one grouped form with the stdin source (`StdinSource`; `CaptureRunner.run(builder,
  deadline, input)` routes its input through it), the process-group sweep; `:gitobjects` —
  `GitExec`, `GitExecStreams` (stdout and stderr through the drain, the stdin feed deleted, the
  cap kept), `GitObjects.open`;
  `sandbox/core` — `ExecHandle.postExitBound()`, `ExecCommand` (`StderrPolicy`), `CapturedExec`
  (a new edge to `:subprocess` in its layering allowlist); `sandbox/docker` —
  `ContainerFileChannel` (`ExecPipeDrain` deleted, `Piped`, its stdin pump deleted),
  `ChildProcessStdin` (deleted), `HostExecHandle`,
  `OomAnnotatedExecHandle`, `HostTaskExecutionEnvironment`, `ContainerTaskExecutionEnvironment`
  and `SelfCheckedEnvironment` (launch through the owner; the stderr policy), `DockerCli` (its
  constructors and `start`); `adapters/agent` — `StreamDrain` (`close()` without its own join),
  `RoundScratchDirectory`, `ExecutorRoundExecution` and `JudgeRoundExecution` (the stderr file);
  `:adapters` — `BoundedTail` and `CommandProcessRunner` (on the owner's drain and the handle's
  bound); `:bootstrap` — a launch-owner gate and a drain-owner gate; `test-fixtures` — the
  launcher helpers as committed resources, `exit-leaky.sh` and `parent.sh` kept, `trailing.sh`
  without its linger, `FakeProcess`, the five `ExecHandle` fakes; `docs/glossary.md` (**post-exit
  bound**, **launch owner**, **stdin source**, **process group sweep**, **round scratch
  directory**);
  `.claude/rules/testing.md` (a scheduling order is never a premise); `:operatorevent` — two
  catalog codes. Sequencing with other active changes: before `extract-agent-round-runner` (NG13)
  and before `add-subprocess-access-log`'s D14 revision (NG14). With `fix-docker-exec-env-argv` no
  order is fixed, but both rewrite `DockerCli.start(List<String>, boolean)`: that change into
  `start(DockerExecLaunch, boolean)`, this one by a `StdinSource` (task 8.12) and a `StderrPolicy`
  (task 8.16) — whichever lands second rebases its tasks onto the other's signature, and that
  change's ADR on secret channels words stdin as a 0600 file for the launch window (design D20,
  Risks); it takes the matching statement in its own revision, not in this change's files.

## Open Questions

- Q1: should the janitor's branch pass be bounded by the same age threshold as its worktree
  pass, or run on every tick? Proposed: every tick, it is one `ls-remote`-free local listing.
  Amended 2026-10-15 (FR11): the listing stays local and free; the fetch is paid per forwarded
  branch, not per tick.

# Bound subprocess commands

## Why

Every git invocation in `adapters/git` runs with no bound on how long it may take and with its
output drained on the calling thread, so a remote that accepts a connection and then goes silent
blocks a factory run forever — reproduced today: `GitProcessRunner.run` against a `git` that
stalls on `push` did not return in 8 seconds, blocking on the child's stdout
(`GitProcessRunner.java:181,186`) before ever reaching `waitFor`. The same run cannot tell
interruption from failure: an interrupted wait returns exit `-1`, which callers read as an
ordinary non-zero exit — `ParkDeliveryFence.java:80-87` spends its one re-attempt on it, then
fabricates a `Note: origin is behind this park` that was never established. A hung push is
strictly worse than a failed one: the claim is neither released nor escalated, so the task is
invisible to every other instance until a human notices.

A survey of the repository shows the same defect triad — undrained pipes, unbounded `waitFor`,
interrupt collapsed into exit `-1` — replicated across five subprocess disciplines, two of them
network-reaching: `DockerCli.run` is the same shape and `docker run` pulls a missing image from a
registry — an unbounded network hang; `CommandProcessRunner` runs a stage's `command` verify
checks (arbitrary scripts from the target repo's `.gnomish/`) with no timeout at any layer;
`HostExecHandle.kill` destroys only the parent — a timed-out agent CLI leaves children running —
and `waitForExit` returns `-1` on interrupt; `ContainerFileChannel.waitFor` shares both defects;
`GitExec` (`gitobjects`) is correct on drain and interrupt but is a fifth private copy.

## What Changes

- **ADDED** `subprocess-supervision` — one domain-neutral leaf module owning the factory's
  subprocess wait/kill/drain mechanics: concurrent draining, bounded wait, two-phase process-tree
  kill with reap, and a named outcome (`EXITED` / `TIMED_OUT` / `INTERRUPTED`).
- **MODIFIED** `git-task-persistence` — network-classified git commands carry a hard deadline with
  git's own stall detection enabled per invocation; interruption and expiry are named outcomes the
  push call sites neither re-attempt on nor report as a push failure; a timed-out push is an
  *unknown* remote outcome, re-verified before any `origin is behind` claim.
- **MODIFIED** `execution-environment` — docker management commands bounded and drained
  concurrently; a timeout kill terminates descendants; interruption is a named `ExecHandle` outcome.
- **MODIFIED** `check-provider-model` — `command` checks gain a configurable timeout; expiry is a
  quality failure carrying the captured output tail.
- **MODIFIED** `module-layering` — the new leaf joins the tree; `gitobjects` gains its one
  domain-neutral dependency, superseding the own-runner clause of design D19 of add-sandbox-core.

The three shared-capability deltas are ADDED-only — no existing requirement restated — so they
collide with none of the active changes touching the same capabilities (`fix-lifecycle-push`,
`fix-denial-attribution-durability`, `add-sandbox-*`); no active change touches `module-layering`.

## Goals

- G1: No subprocess the factory launches — git, docker, agent CLI, verify command — can block a run
  indefinitely, whatever the remote, registry, daemon, or script does.
- G2: Interruption, deadline expiry, and a command that ran and failed are distinguishable
  everywhere — in logs, in reports, and in every re-attempt decision.
- G3: A legitimately slow but progressing transfer is never killed by the bound.
- G4: The mechanics live in one module; no call site or module keeps a private wait/kill copy —
  `gitobjects` included.
- G5: No killed or interrupted invocation leaks descendant processes.

## Non-Goals

- NG1: Retry or backoff policy. Best-effort push stays one attempt, one WARN; this change bounds
  attempt duration, not attempt count.
- NG2: Proxy, egress, or transport configuration; stall settings are injected per invocation.
- NG3: Bounding local git plumbing (`commit`, `rev-parse`, `gitobjects`' object commands): a local
  command that hangs is a broken machine, not a broken remote. `gitobjects` stays network-free.
- NG4: Merging per-caller I/O policy (stdout caps, stdin feeds, tail readers, the credential
  scrub) into the shared module — mechanics unify, policy stays local.
- NG5: Per-check timeout overrides in the stage manifest (Q7) — one installation default now.

## Users & Scenarios

- U1: An operator leaves `gnomish take` running overnight; the origin's connection dies without
  closing. The push fails with one WARN naming the deadline; the task reaches a terminal state.
- U2: An operator sends SIGTERM while a park delivery push is in flight. The logs say interrupted;
  no second push is attempted; no `origin is behind` note is fabricated.
- U3: A slow-link fetch keeps progressing past the deadline's nominal window. It is not killed:
  stall detection governs; the deadline fires only on no motion.
- U4: An operator running purely locally (no `origin`, image present) sees no behavior change.
- U5: A box's image is missing and the registry is wedged. `docker run` returns within the docker
  deadline as an infrastructure failure instead of hanging the take.
- U6: A pipeline's `command` check enters an infinite loop. It fails as an ordinary quality
  failure within the check timeout, carrying the output tail as findings context.

## Requirements

### Functional

- FR1: The git runner SHALL classify each invocation as network or local by subcommand (`fetch`,
  `push`, `ls-remote`, `clone`, `remote update`), surviving leading `-c key=value` options, and
  apply the bound only to network commands.
- FR2: Every supervised invocation SHALL have stdout and stderr drained concurrently with the
  running process, so neither a full pipe nor a stalled child can block the wait.
- FR3: On deadline expiry the supervisor SHALL terminate the process and its descendants in two
  phases — cooperative terminate, a short kill grace (git and docker remove lock/temp files on
  SIGTERM), forced kill of the re-snapshotted tree — reap them, join the drains within a bound,
  and return a timed-out outcome carrying the output captured so far.
- FR4: The git runner SHALL enable git's own stall detection per invocation — HTTP low-speed abort;
  SSH connect, keepalive, and batch-mode limits — without writing to the operator's configuration.
- FR5: Each deadline SHALL be configurable and injectable, with documented defaults: git network
  300 s, docker management 300 s, command check 30 min.
- FR6: A timed-out or interrupted invocation SHALL be a distinct outcome of the command result in
  every runner — never an ordinary non-zero exit code.
- FR7: `ParkDeliveryFence` and `RemoteAttemptDelivery` SHALL NOT spend their bounded re-attempt on
  an interrupted or timed-out invocation. A timed-out push is an unknown remote outcome: the fence
  SHALL re-verify the remote tip once (bounded), claim `origin is behind` only when the tip is
  confirmed absent, and otherwise report that delivery could not be verified.
- FR8: `BestEffortPush`, `LifecyclePush`, and the push-decorating repositories SHALL log an
  interrupted or timed-out push distinctly from a failed one; all of them SHALL still never throw.
- FR9: One `subprocess-supervision` leaf module SHALL own the wait/kill/drain mechanics, and
  `GitProcessRunner`, `GitExec`, `DockerCli`, `ContainerFileChannel`, `HostExecHandle`, and
  `RealProcessTreeKiller` SHALL all delegate to it — no other module may retain a private copy.
- FR10: Docker management commands (`DockerCli.run`) SHALL be bounded by the docker deadline with
  concurrently drained output; the daemon-unreachable classification is unchanged.
- FR11: `ExecHandle`'s timeout kill SHALL terminate descendants; interruption SHALL be a named
  `Wait` outcome rather than exit `-1`, in `HostExecHandle` and `ContainerFileChannel` alike.
- FR12: `command` verify checks SHALL be bounded by the check timeout; expiry classifies as a
  quality failure whose findings carry the captured output tail.
- FR13: `GitExec` SHALL migrate onto the supervisor behavior-preserving: identical results, stdout
  cap, stdin feed, and environment hermeticity; its interrupt exception contract is unchanged.
- FR14: `RealProcessTreeKiller` SHALL reuse the supervisor's kill discipline, gaining the reap it
  currently lacks.

### Non-Functional — Reliability

- NFR-R1: Every bounded invocation SHALL return within its deadline plus the kill grace and a small
  margin, regardless of remote, registry, or daemon behaviour.
- NFR-R2: No process or descendant SHALL survive a deadline kill or an interrupt.
- NFR-R3: Commands that complete normally SHALL keep their current exit-code, stdout, and stderr
  semantics exactly — every existing spec in every migrated module stays green unchanged.

### Non-Functional — Observability

- NFR-O1: A deadline expiry SHALL log one WARN naming the command class, elapsed time, and the
  configured deadline; a check expiry names the check id.
- NFR-O2: An interrupt SHALL log its own message naming interruption; `push failed` SHALL NOT be
  logged for it.

### Non-Functional — Security

- NFR-S1: Stall settings SHALL be injected per invocation, never persisted to operator-owned config.
- NFR-S2: Credential scrubbing of captured stderr SHALL still apply to every path, including the
  partial output of a killed process.
- NFR-S3: The supervision module SHALL depend on nothing — no internal module, no Spring, no
  logging, no domain `Clock` — so `gitobjects` stays domain-independent; build-enforced.

### Non-Functional — Performance

- NFR-P1: Draining SHALL use virtual or daemon threads that cannot outlive their command and add no
  measurable overhead to the local commands that dominate a run.

### Non-Functional — Cost

Considered; no token or API cost surface is touched.

## Operator Experience Criteria

- UX1: All three deadlines are documented, overridable installation properties; an operator on a
  slow link can raise them without patching code.
- UX2: A park report never claims `origin is behind` on the strength of an interrupt or an
  unverified timeout.
- UX3: "The remote rejected the push", "the remote never answered", and "we were shut down" are
  readable from the WARN line alone.
- UX4: A hung verify command surfaces as an ordinary quality failure with its output tail — never
  as a silent hang diagnosed from a stuck run.

## Success Metrics

- M1: A spec drives a stalled network git command through the runner; wall clock under 2× the
  configured deadline against a fake git that stalls far longer.
- M2: A spec asserts no process or descendant survives a deadline kill, via `ProcessHandle`.
- M3: A spec asserts an interrupted park delivery spends no re-attempt and produces no
  `origin is behind` note; a timed-out one produces it only after a confirming re-check.
- M4: Same-shape stall specs pass for `DockerCli.run` and a hung `command` check.
- M5: The build proves `subprocess-supervision` has zero dependencies; the repository-wide count
  of timing-race `@DoNotMutate` exemptions decreases (five copies → one supervisor seam).
- M6: `./gradlew check` green across every module; mutation score 100% in every touched module;
  no new `excludedClasses` / `excludedTestClasses` entry.

## Open Questions

- Q1 (resolved, design D4): one deadline per command class, not a table. Q2 (resolved, D8): values
  bind on `FactoryProperties`. Q3 (resolved, D5): git's stall detection primary, deadline backstop.
- Q4: Does a real repository's initial clone stay under 300 s? Default-tuning; operator experience.
- Q5 (resolved, design D9/D13): the five wait/kill copies collapse into `subprocess-supervision`
  in this change; `gitobjects` migrates too, superseding D19's own-runner clause.
- Q6: Is `git://` on the process deadline alone acceptable long-term? It has no stall detection;
  acceptable while no factory path configures such an origin.
- Q7: Per-check `command` timeouts in the stage manifest (as `external` polling has)? Deferred;
  one installation default (FR5) ships first.
- Q8: Is 30 min the right check-timeout default for real pipelines? Tune from operator experience.

## Impact

- New module `subprocess` (`subprocess-supervision` capability); `settings.gradle`, build
  conventions, PIT wiring. `adapters/git`: `GitProcessRunner`, `GitCommandResult`,
  `ParkDeliveryFence`, `RemoteAttemptDelivery`, `BestEffortPush`, `LifecyclePush`, `RefspecPush`,
  push decorators. `sandbox/docker`: `DockerCli`, `HostExecHandle`, `ContainerFileChannel`;
  `sandbox/core`: `ExecHandle.Wait` gains an interrupted variant. `adapters`:
  `CommandProcessRunner`, `ShellCommandCheckRunner`. `gitobjects`: `GitExec`
  (behavior-preserving). `application`: `RealProcessTreeKiller`. `bootstrap`: property wiring.
- Supersedes the own-runner clause of design D19 of add-sandbox-core (archived, never edited;
  superseded by design D13 here). No new external dependencies; `domain` untouched.

# Design: bound-subprocess-commands

## Context

See `proposal.md` — Why. Constraints shaping the approach:

- The repository holds five separate subprocess wait/kill/drain disciplines: `GitProcessRunner`
  (adapters/git), `GitExec` (gitobjects), `DockerCli` + `ContainerFileChannel` + `HostExecHandle`
  (sandbox/docker), `CommandProcessRunner` via `ExecHandle` (adapters), `RealProcessTreeKiller`
  (application). Two are network-reaching and unbounded; three collapse interrupt into exit `-1`.
- `GitProcessRunner` is the single choke point for git and already carries two cross-cutting
  concerns of this shape (the per-clone mutation lock, the stderr credential scrub); the git-side
  policy stays there.
- `GitCommandResult` is a package-private 3-component record consumed by every class in the
  adapter; NFR-R3 requires existing construction sites and specs to keep compiling unchanged.
- Every push call site is deliberately non-throwing, so a new failure mode cannot be an exception.
- Git's no-progress detection is transport-specific: `http.lowSpeedLimit/Time` cover HTTP; SSH
  needs `ConnectTimeout` / `ServerAliveInterval` / `ServerAliveCountMax` / `BatchMode` via
  `GIT_SSH_COMMAND`; `git://` has neither.
- Git and docker remove their lock and temp files on a catchable signal (git's `tempfile.c`
  handlers) but not on SIGKILL; GitLab moved its git supervision to SIGTERM → short wait → SIGKILL
  for exactly this reason. `RealProcessTreeKiller` already follows that two-phase shape in-repo.
- `gitobjects` is import-independent of the factory (design D19 of add-sandbox-core) — the
  constraint is *domain* independence, which a dependency-free mechanics module does not breach.

## Goals / Non-Goals

**Goals (design-level):** one mechanics module, policy at the call sites; source-compatible with
every existing spec; the five timing-race `@DoNotMutate` exemptions collapse into one driven seam.

**Non-Goals (design-level):** any asynchronous invocation model (virtual threads make blocking
correct); moving per-caller I/O policy (caps, stdin, scrub, tail) into the shared module.

## Decisions

**D1 — The git bound lives in `GitProcessRunner.execute`, applied to a network classification
computed next to the existing mutation classification.** `isNetwork(args)` — `fetch`, `push`,
`ls-remote`, `clone`, `remote update` — with the same leading `-c key=value` skip. *Rationale:*
FR1, G4 — the choke point already owns lock and scrub. *Alternative rejected:* a timeout parameter
on network-touching call sites — five places to keep in step; the next one silently opts out.

**D2 — stdout and stderr are drained on two virtual threads started immediately after `start`,
joined after the wait resolves — with a bounded join on the kill path.** A descendant that
survived the kill snapshot can inherit the pipe and hold it open (the Gradle #3987 failure mode),
so "drains joined" is never a precondition for returning a result: on kill, join within a short
bound and return with whatever was captured. *Rationale:* FR2 — the reproduced hang is a blocking
`transferTo` before `waitFor`; a >64 KiB pipe nobody drains deadlocks the child regardless.
*Alternatives rejected:* `redirectErrorStream(true)` — merges streams the adapters parse
separately and pushes credentials into stdout past the scrub; temp-file redirection — file
lifecycle plus a second scrub site.

**D3 — Kill = snapshot descendants, cooperative `destroy` of the tree, a short kill grace
(default 5 s), then `destroyForcibly` on a re-snapshotted tree, then reap.** Git and docker clean
their lock/temp files on SIGTERM but not SIGKILL; the grace is bounded and part of NFR-R1's
margin. The descendant snapshot precedes killing the parent (a dead parent no longer enumerates
them); the re-snapshot catches children forked between snapshot and kill. `RealProcessTreeKiller`
is the in-repo precedent. *Rationale:* FR3, NFR-R2, G5. *Alternative rejected:* immediate
`destroyForcibly` — leaves `index.lock`-style litter in the clone the next lifecycle push trips
over; the doubled worst-case is a bounded 5 s, not a doubled deadline.

**D4 — One deadline per command class (git network 300 s, docker 300 s, check 30 min), not a
per-subcommand table.** With D5's stall detection primary, the git deadline only fires on a wedged
process — no command-specific character. *Alternative rejected:* short bound for `ls-remote` —
turns a slow-but-alive origin into a false "cannot verify" (UX3 noise).

**D5 — Git's own stall detection is the primary mechanism; the process deadline is the backstop.**
Network invocations are prefixed with `-c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60`;
`GIT_SSH_COMMAND` is set to `ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15
-o ServerAliveCountMax=4` **only when the operator has not already set it**. Both per-invocation;
nothing operator-owned is written (NFR-S1). `BatchMode=yes` closes ssh's own prompt paths the
askpass emptying cannot reach. Caveat documented for operators: `ServerAlive*` detects a dead
transport, not a live sshd whose `git-receive-pack` is wedged — that case is the deadline's.
*Alternatives rejected:* deadline alone — either kills large transfers or lets a dead remote stall
for minutes; clobbering an existing `GIT_SSH_COMMAND` — drops the operator's wrapper or jump host.

**D6 — The new outcome is a fourth `GitCommandResult` component, `Termination { EXITED, TIMED_OUT,
INTERRUPTED }`, with a 3-argument constructor defaulting to `EXITED`.** Every existing
construction site and spec keeps compiling; the callers that must branch get an exhaustively
switchable signal. *Alternatives rejected:* sentinel exit codes — the precise defect being fixed;
a thrown exception — every push call site is contractually non-throwing.

**D7 — Callers branch on `termination()`; a timed-out push is an *unknown* remote outcome.**
`ParkDeliveryFence`: `INTERRUPTED` → "delivery not verified", no second push, no note. `TIMED_OUT`
→ no re-attempt (a second 300 s wait on a wedged remote is the hang this change removes), but the
kill may have landed after the transfer: the fence re-runs its bounded `remoteTip.carries` check
once and writes `origin is behind` only when the tip is confirmed absent; an unanswerable check
reports "could not be verified". `RemoteAttemptDelivery` maps both to `CannotVerify`
(infrastructure), never a quality failure. Push loggers only vary WARN text (FR8, NFR-O2).
*Rationale:* FR7, UX2 — a fabricated note was the operator-visible half of the defect; asserting
"behind" from a local kill would re-create it. *Alternative rejected:* re-attempting on
`TIMED_OUT` — the bounded re-attempt exists for transient rejection, not a proven-dead remote.

**D8 — Deadlines bind as `factory.git-network-timeout`, `factory.docker-command-timeout`,
`factory.check-command-timeout` on `FactoryProperties`, passed in at the composition root.**
No-argument constructors keep the defaults so adapter specs and `gnomish run` paths are
unaffected and specs can inject sub-second bounds. `factory.agent-cli-tail-drain-grace` is the
precedent: installation-level `Duration`s, documented in the operator guide. *Alternative
rejected:* constructor-only — an operator on a slow link could not raise them without patching.

**D9 — The mechanics live in a new dependency-free leaf module `subprocess` (capability
`subprocess-supervision`).** JDK only: no Spring, no slf4j (it returns outcomes; callers own
logging), no domain types — in particular **no domain `Clock`**; the module uses `java.time`
directly with its own minimal tick seam for specs. That constraint is what lets `gitobjects`
depend on it without breaching domain independence, and it is load-bearing: any future domain
import breaks the `gitobjects` build, so the neutrality is enforced, not aspirational.
*Alternatives rejected:* `sandbox/core` — a port module (drags `domain` + Spring; mechanics are
not a port); `sandbox/docker` — `application` and `gitobjects` must not depend on a docker
backend; per-module copies — the defect being fixed.

**D10 — The module exposes two levels: a supervisor primitive and a capture runner.** The
primitive takes a started `Process` plus an optional deadline: starts drains when asked, waits
bounded, runs the D3 kill on expiry or interrupt, joins drains within a bound, returns
`Termination` plus exit code. The capture runner wraps it for the capture-shaped callers
(`GitProcessRunner`, `DockerCli.run`): start from a caller-built `ProcessBuilder`, capture stdout
and stderr separately, return `{termination, exitCode, stdout, stderr}`. Streaming callers
(`HostExecHandle`, `ContainerFileChannel`, `GitExec`'s capped stdout) use the primitive and keep
their own readers. The interrupt path gets one package-private seam (as `GitExec.await` has
today), driven deterministically by the module's own spec — the five per-module timing-race
`@DoNotMutate` exemptions collapse into it. *Alternative rejected:* one fat runner serving every
caller's I/O shape — caps, stdin, scrub, and tails are policy, and policy stays local (NG4).

**D11 — `DockerCli.run` moves onto the capture runner with the docker deadline; `start` keeps
returning a live `Process`, but its consumers (`HostExecHandle`, `ContainerFileChannel`) wait and
kill through the primitive.** Daemon-unreachable classification stays in `DockerCli` (policy).
`ExecHandle.Wait` gains an `Interrupted` variant; `waitForExit`'s `-1` convention is replaced at
its two call sites (`CommandProcessRunner`, in-box helpers) by the named outcome. The timeout kill
becomes a tree kill — fixing the latent leak where a timed-out agent round killed only the parent
CLI and left its children running. *Rationale:* FR10, FR11, G5.

**D12 — `command` checks are bounded by `factory.check-command-timeout` (default 30 min) through
`ExecHandle.waitForExitOrTimeout`; expiry is a quality failure carrying the captured tail.**
Quality, not infrastructure: the command ran and failed to finish — the same default `external`
polls use — so it burns a stage attempt and feeds findings back into the retry loop. The tail
reader stays as is; only the wait changes. *Alternative rejected:* a per-check manifest field now
— a public schema extension deferred to Q7 behind the installation default.

**D13 — `GitExec` migrates onto the primitive, behavior-preserving, superseding the own-runner
clause of design D19 of add-sandbox-core.** Its stdout cap, stdin feed, environment
neutralization, and interrupt exception contract are untouched; only `await` and the drain thread
plumbing move to the supervisor. D19's goal — `gitobjects` extractable, domain-independent — is
preserved by D9's neutrality constraint; extraction now carries two dependency-free modules
instead of one. *Alternative rejected:* leaving `GitExec` as a documented fifth copy — correct
today, but every future fix to the shared mechanics would need mirroring by hand.

**D14 — `RealProcessTreeKiller` reuses the primitive's kill discipline.** It already embodies the
two-phase shape; delegating adds the reap it lacks and removes the last private copy.

## Risks / Trade-offs

- [Two virtual threads per invocation on runs dominated by local commands] → virtual, command-
  scoped, joined before return; NFR-P1 keeps the cost an acceptance criterion.
- [`http.lowSpeedTime=60` could abort a transfer that dips below 1 kB/s for a full minute] → the
  values are common CI defaults; the failure mode is a WARN plus a next-round retry, never a lost
  commit (durability is the local branch state).
- [`git://` has no stall detection] → deadline backstop only; no factory path configures one (Q6).
- [Killing descendants could reach a process the parent handed off] → git/docker network helpers
  are strict children of the killed invocation; snapshots are taken at kill time.
- [This change now touches six modules while `fix-lifecycle-push` and
  `fix-denial-attribution-durability` are active in the same regions] → deltas here are ADDED-only
  for the shared capabilities; implementation is sequenced strictly after `fix-lifecycle-push`
  commits (tasks 0.x); the module-layering delta is the only MODIFIED one and no active change
  touches that capability.
- [Scope is at the top of the 1–4 week invariant] → the cut line, if needed, is D14 and D13 in
  that order — both are behavior-preserving migrations that can trail as their own change without
  reopening any interface decided here.
- [A wedged docker daemon makes in-box helpers hang on `docker exec` even with bounded checks] →
  they inherit the bound through `ExecHandle` where one exists (checks, rounds); purely local
  helpers remain NG3's broken-machine class, unchanged.

# Design: wire-host-mid-round-push

## Context

Motivation: see proposal.md — Why (driven by FR1–FR4, NFR-R1, NFR-O1).

Current state the design builds on:

- The engine reaches the agent-cli adapter through `StageExecutor.Request`, which carries the
  `TaskContext` (taskId), the `StageDefinition`, the opaque `Workspace`, and the attempt
  number — everything `MidRoundPushListener`'s constructor needs except the git runner and the
  suppressor. In host mode the workspace is a `DirectoryWorkspace` whose root is the task
  worktree (git mode) or the project directory (in-place mode).
- Per-round hooks already have one seam: `RoundEnvironmentSource.roundListener()`, joined to
  the executor's own listener in `ExecutorRoundExecution`. Sandbox mode fills it with
  `MidRoundHarvestListener` (built in `SandboxRoundEnvironmentSource.openRound`); host mode
  inherits the default no-op.
- The host `RoundEnvironmentSource` (`HostRoundEnvironmentSource`) is package-private in
  `adapters/agent` and built inside `CliStageExecutor`'s host constructor; the sandbox path
  instead passes a source in via the constructor that takes rounds explicitly.
- Container runners attach their adapter pieces to the run with
  `assembly.withSandbox(SandboxRunPieces)`; `ExecutorAdapterSelector` consumes the pieces.
  Host git-mode runners attach nothing today, which is exactly why the listener never joined.
- `adapters/git` and `adapters/agent` share no compile edge; both depend on `application`;
  `bootstrap` depends on both — so any composition of the two lives in `bootstrap`, and any
  shared type lives in `application`.

## Goals / Non-Goals

Design-level only (scope is in the proposal):

- Goal: reuse the existing per-round seam and the existing pieces-attachment idiom; introduce
  no new listener-plumbing mechanism (FR2) and no new module edges.
- Non-goal: restructuring `CliStageExecutor`'s constructors beyond exposing the host round
  source; no changes to `ExecutorRoundExecution` or the listener composition itself.

## Decisions

**D1 — Wire through `roundListener()`, via a decorating `RoundEnvironmentSource` in
`adapters/git`.** A new small class (working name `MidRoundPushRounds`) implements
`RoundEnvironmentSource`, delegates `openRound` to the host source, and wraps the returned
`Round` so that `roundListener()` returns a fresh `MidRoundPushListener` built from the
request's own facts: worktree root from the `DirectoryWorkspace`, taskId from
`request.context()`, stage from `request.stage().name()`, round from `request.attempt()`,
branch via `TaskIdSanitizer.branchName` — the same derivations `SandboxRoundEnvironmentSource`
uses. `openRound` is called exactly once per attempt request (`CliStageExecutor`), so the
attempt number *is* this seam's round number — the same equivalence the sandbox source
encodes in its `AttemptKey`. All other `Round` methods delegate untouched. *Rationale:* the seam exists precisely for
this (its javadoc even names the sandbox harvest poll as its purpose), the per-round lifecycle
the listener documents falls out of `openRound`'s own cadence, and the class lands in the
module that owns `MidRoundPushListener` and `GitProcessRunner`. *Alternative rejected:*
composing the listener into `executorProgressListener` in `ExecutorAdapterSelector` — that
listener is per-run, not per-round, so it cannot carry stage/attempt context and would violate
the listener's one-instance-per-round contract.

**D2 — Expose the host round source with a public factory in `adapters/agent`.** The
decorator needs the real host source as its delegate, and `CliStageExecutor` already accepts an
explicit `RoundEnvironmentSource` (the sandbox path uses that constructor). Add a public static
factory (e.g. `CliStageExecutor.hostRounds(clock, childEnv)`) returning the
`HostRoundEnvironmentSource` wiring that the host convenience constructor builds today, so
`bootstrap` can build `decorator(hostRounds)` and pass it through the existing
rounds-accepting constructor. *Rationale:* smallest seam; the package-private class and its
`DecisionFileTransport` internals stay hidden. *Alternative rejected:* making
`HostRoundEnvironmentSource` public — it would leak `DecisionFileTransport` construction
details into two modules for no gain.

**D3 — Attachment is a decorator-as-value:
`RunAssembly.withHostGitPush(UnaryOperator<RoundEnvironmentSource>)`, attached only by
git-mode host control flows, carried to them by `TaskGit`.** `application` gains the
`withHostGitPush(decoration)` copy method mirroring `withSandbox`'s idiom, realized in
`ManualRunAssembly` with `UnaryOperator.identity()` as the default (a Null Object:
`ExecutorAdapterSelector` applies the decoration unconditionally inside its existing
`sandbox == null` branch — no mode conditional appears, and the previous host construction is
exactly the identity application). The decoration travels as a fourth `TaskGit` component
(working name `midRoundPush`), defaulted to identity by a three-component convenience
constructor so the existing construction sites stay untouched; the real operator is built in
exactly one named place — `ManualRunConfiguration.taskGit(...)`, which already builds the
`GitProcessRunner` from `factoryProperties.gitNetworkTimeout()`. The attachment call sites are
the git-mode host control flows: `GitModeRunner.run`, `GitResumeContinuation`, and
`TakeEngineExecution.run` — the last covers both take entry points (fresh via
`TakeFreshClaim`, resume via `TakeResumeRunner`) in one place, since both funnel through it.
In-place mode never calls it, satisfying FR3 with absence rather than a mode flag. The
operator itself is stateless: per-task state (D4's shared suppressor) lives in the
`MidRoundPushRounds` instance each `apply` creates — one per `assemble`, i.e. one per run.
*Rationale:* the decorator-as-value shape is the established middleware idiom (Go
`func(Handler) Handler`, Tower `Layer`, OkHttp interceptors) and composition-root
interception per Seemann: mode knowledge stays in the mode-aware runners (type dispatch, not
a flag), git-adapter knowledge stays in `bootstrap`, and the operator's ride in `TaskGit` is
cohesive — the push decoration is a git capability co-travelling with the other git
capabilities (a future non-git decoration wanting the same ride is the signal the bundle is
degrading, not a precedent to follow). *Alternatives rejected:* a no-arg `withHostGitPush()`
flag — control coupling (the caller names the branch to take instead of handing the behavior)
and it moves git-adapter construction knowledge into the selector; threading the built
`RoundEnvironmentSource` down the take call chain — `TakeFreshClaim.claim` already takes 14
parameters and `TakeEngineExecution` 8 record components, so the ninth/fifteenth addition is
exactly the cascade the parameter-count rule exists to stop, and no surveyed middleware
system ships that shape.

**D4 — One `RepeatSuppressor` per task, held by the decorator.** The decorator is constructed
once per run (one `apply` of the D3 operator per `assemble`, per task) and hands the same suppressor to every round's listener, mirroring the
`harvestSuppressor` rationale recorded in `SandboxRoundEnvironmentSource` (NFR-O1): a tip that
cannot be resolved is one fault whether it spans polls of one round or rounds of one task.
*Alternative rejected:* per-round suppressors — they would re-announce a persistent failure
every round, exactly what FR4 of harden-logging-observability removed.

**D5 — Crash-consistency checklist: not triggered.** The wiring adds no new durable step and
no new transition: a mid-round push is a best-effort replication of already-durable local
commits, `BestEffortPush` is unchanged, and every kill window it can occupy is already owned
by the `task-branch-contract` capability's shapes (a pushed-or-not branch tip is exactly the
divergence reconciliation on resume already converges). Stated here so the checklist's
"written answer" requirement is met by naming why it does not apply.

**Sync surfaces.** This change touches declared pairs and must record the decision
(`manual-sync-pairs.md`):

- `MidRoundPushListener` ↔ `MidRoundHarvestListener` (declared in both javadocs): the
  synchronized invariant — per-event `VerifiedTip` poll, `MidRoundPollLog` edge suppression,
  per-round lifecycle, ancestry-proving push precondition — is not altered; the change only
  constructs the host end where the sandbox end is already constructed. **No mirrored change
  needed**; the harvest side's wiring is the template being mirrored, not modified.
- Host/container control-flow pairs (`GitModeRunner` ↔ `ContainerGitModeRunner`,
  `GitResumeRunner` ↔ `ContainerResumeRunner`, `TakeEngineExecution` ↔
  `TakeContainerEngineExecution`): the host ends gain the `withHostGitPush` attachment that is
  the host analogue of the container ends' existing `withSandbox` attachment. This narrows the
  per-mode asymmetry rather than widening it; the pairs stay declared pairs (no third
  implementation appears, so no abstraction extraction is due). The container ends need no
  mirrored edit — their attachment already exists.
- `HostRoundEnvironmentSource` ↔ `SandboxRoundEnvironmentSource` (registry row "round
  environment contract per mode"): the change alters what host git-mode rounds *carry* (a
  listener instead of the no-op default), but does so by wrapping the host source from the
  outside — `HostRoundEnvironmentSource` itself is not edited and the pair's synchronized
  contract (what a `Round` provides per mode) is unchanged. **No mirrored change needed**;
  the sandbox source's rounds already carry their listener.
- The new decorator is not a second implementation of any existing rule: it composes the
  existing listener and existing host source; no new pair is created.

## Risks / Trade-offs

- [Active change `add-command-executor` rewrites `ExecutorAdapterSelector` (its task 6.1
  replaces the executor with a type-keyed dispatching composite) and builds its
  `CommandStageExecutor` over the same `RoundEnvironmentSource` seam — the same class and
  branch logic task 3.1 here modifies] → whichever change lands second must re-thread the
  host-git piece through the new selector shape so no host branch silently loses the
  decorated rounds; record the mirrored note in `add-command-executor`'s artifacts when it
  is next revised.
- [The decorator casts `Workspace` to `DirectoryWorkspace` like the host source does; a future
  workspace type would break both] → the cast lives beside the identical existing cast, so any
  workspace generalization already has to visit this file's twin; keep them adjacent in review.
- [`withHostGitPush` and `withSandbox` could be attached together by a future caller, which
  would be contradictory] → `ExecutorAdapterSelector` consumes the host piece only when
  `sandbox == null`; the sandbox piece wins by construction and a comment records it.
- [Push noise on origin: mid-round pushes multiply remote updates] → bounded by the listener's
  design (one push per observed tip movement, ancestry-gated); already accepted by the spec's
  host scenario.

## Migration Plan

No data or deployment migration: behavior is additive in git-mode host runs only and each push
is best-effort. Rollback = not attaching the piece (one call site per runner).

## Open Questions

None.

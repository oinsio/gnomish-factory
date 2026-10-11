# Design: harden-verification-integrity

## Context

See proposal.md — Why. Constraints:

- The law is read from bare objects at the peeled base-ref commit (`GitObjectsLawSource`,
  `LawSource.list(ref)`, `PipelineLawReader`); the working copy plays no part for take/serve.
  Criteria files already come from it; scripts a `command` runs do not.
- `TaskExecutionEnvironment.putFile/readFile` is the one file channel into and out of a box,
  valid for the working copy and the per-environment scratch area; the scratch area dies with
  the environment. Host mode: `HostChannelFiles` resolves symlinks and refuses escapes.
- `FactoryOwnedPaths` (adapters/git) and `EnvelopePaths` (domain) own the `.gnomish-task/`
  paths; salvage restores factory-owned paths after a round.
- The attempt commit and the task base are both reachable from the factory clone through
  `gitobjects`; a diff needs no box.
- After `extract-agent-round-runner`: the agent round runner renders argv from a dialect whose
  describe carries a config-directory variable and repo-policy-disabling flags; the Claude
  dialect declares `CLAUDE_CONFIG_DIR` and the settings-source flags. After
  `migrate-verify-to-executor-contract`: checks are verdict executors with a threshold; a
  `verdict` request carries role inputs as delimited data.
- Threat registry: #22 claims the law closes "weakens criteria, tests, limits"; only criteria
  are closed. Highest row number today is 48.

## Goals / Non-Goals

**Goals:** assets from the law commit only (FR1); protected paths enforced deterministically
(FR2–FR4); repo-local policy inert (FR5); advisory severity (FR6); registry truthful (FR7).

**Non-Goals:** sealing the build tool binary (NG1), hidden-test runners (NG2), host-mode ref
isolation (NG3), baselines (NG4), judge reducers (NG5).

## Decisions

**D1 — Materialize the whole law tree, read-only, per environment.** (FR1, NFR-S1.) One
`LawMaterializer` writes every law entry through `putFile` into a factory-owned directory in
the scratch area (container: a path the task user cannot write; host: a directory outside the
worktree, mode 0555), exposes it as `GNOMISH_LAW_DIR` in the allowlisted environment, and does
so once per environment lifetime (idempotent re-run). *Rationale:* one rule, no per-check asset
list to keep in sync with the law; the law size cap already bounds it. *Alternative rejected:*
per-check `assets:` lists — a second declaration of what the law already contains, and the
first forgotten asset is the hole.

**D2 — `diff-scope` is a factory-side built-in over `gitobjects`.** (FR3, NFR-R2.) It reads
`attemptCommit..taskBase` from the factory clone, matches against `protected:` globs, applies
the deletion rule and the size cap, and emits SARIF. It is registered in `ExecutorRegistry`
like other built-ins and declares no box need (describe: `needsWorkspace: false`).
*Rationale:* deterministic, cheap, runs first in the chain by the fail-fast rule. *Alternative
rejected:* a `shell` check running `git diff` in the box — reads the gnome's git, not the
factory's objects.

**D3 — Protected-change list is a role input of every `verdict` request.** (FR4.)
`ProtectedChanges` is computed once per attempt by the same matcher `diff-scope` uses and
attached to every `verdict` request as delimited data. *Alternative rejected:* leaving it to the
judge's own diffing — grading the gnome's narrative is the anti-pattern the research named.

**D4 — Repo-local policy isolation lives in the runner, from describe.** (FR5, NFR-S2.) The
agent round runner creates a factory-owned config directory per round (empty or holding
factory-written settings), sets the declared variable, appends the declared flags; the loader
refuses an agent executor declaring neither unless `allow-repo-policy: <reason>` names it.
*Rationale:* the describe fields exist precisely for this; refusing by default makes a new
dialect declare its isolation story. *Alternative rejected:* a Claude-only fix — Cursor,
OpenCode, Amp, Gemini and Codex have the same class of file.

**D5 — `severity` is a check attribute read by the engine, not by executors.** (FR6.) The verify
orchestrator records an advisory check's result with its severity and treats it as passed for
control flow; the tracker publication tags it. *Alternative rejected:* an advisory threshold
(`note`) — conflates "what fails" with "whether failing matters".

**D6 — Threat registry edits.** (FR7.) #22: "closed by the law commit for criteria and
manifests; for scripts, tests and limits by `GNOMISH_LAW_DIR` materialization, `protected:` +
`diff-scope`". New #49: verification assets in gnome-writable space. New #50: repo-local agent
policy widens the gnome's permissions. #6: host mode notes the shared `.git` as an accepted
limitation of that mode.

**D7 — Sync surfaces.** Scout: registry and `Kept in sync with` markers; domain terms
"protected", "factory-owned", "law".
- `FactoryOwnedPaths` ↔ `EnvelopePaths`: not a pair (one owner, gate); `GNOMISH_LAW_DIR` is a
  scratch path, not an envelope path, so neither changes.
- Host vs container materialization: one `LawMaterializer` over `putFile`; medium differences
  (mode bits, location) live in the two environment adapters' existing `putFile` — no twin.
- `GitAttemptPersistence` ↔ `EnvironmentAttemptPersistence`: untouched; the law directory is
  outside the working copy and never committed or salvaged.
- Glob matching: `diff-scope` and `ProtectedChanges` share one `ProtectedPathMatcher`; a second
  matcher is forbidden by the table below.
- *No new pair created.*

**D8 — Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|---|---|---|---|---|
| `LawMaterializer` (adapters/law + sandbox seam) | `LawDir` (path inside the environment) | agent round runner (every round), `SandboxCheckEnvironmentSource` and `HostCheckEnvironmentSource` (every check environment), `shell` built-in (env var) | criteria read for the judge keeps going through the law source (text, not files) — exempt, named; no other law-content path into the box exists today | `LawDir` type on the request's environment; architecture spec in `:bootstrap`: no `putFile` of a `.gnomish/` path outside the materializer |
| `ProtectedPathMatcher` (domain) | `ProtectedChanges` (paths with matching glob and change kind) | `diff-scope` built-in, `verdict` request assembly (`VerifyOrchestrator`) | none (new); identity: the list the judge receives equals the list `diff-scope` fails on, `ProtectedChangesIdentitySpec` on a real bare origin | parameter type; grep gate: `PathMatcher.*glob` in `domain/verification` → one file |
| Repo-policy isolation (`AgentRoundRunner`) | `IsolationArgv` from `Describe` | every agent dialect through the runner | none today (the gap); Claude's `--setting-sources`/config dir never passed | load-time refusal without declaration or exemption; spec with the fake agent recording argv and `CLAUDE_CONFIG_DIR` (M2) |
| Severity handling (`VerifyOrchestrator`) | `Severity` enum on the check | attempt record, tracker publication, ledger | none (new) | round-trip spec; data-driven control-flow spec |

**D9 — Crash consistency.** No new durable step: the law directory lives in the scratch area
and is rebuilt with the environment; advisory results ride the existing attempt commit;
`diff-scope` is pure read. Kill anywhere leaves an already-named shape.

**D10 — Lock scope.** None added; materialization runs on the round's thread before launch.

## Risks / Trade-offs

- [Pipelines call `./gradlew` from the repo and cannot move it] → protect `gradlew` and
  `gradle/**` so edits fail `diff-scope`; the law may ship its own wrapper (NG1).
- [Flags change across CLI versions] → the dialect owns them; the describe version pins the
  tested CLI range; the fake-agent spec asserts presence, the paid smoke asserts effect.
- [Protected globs too broad block legitimate work] → `allows-protected` per stage, deletions
  still refused, `note`-level findings keep the trail.
- [Materializing the law per environment costs time on large law trees] → bounded by the law
  size cap; done once per environment, not per check.

## Migration Plan

1. After `extract-agent-round-runner` and `migrate-verify-to-executor-contract`.
2. Ship materialization and `GNOMISH_LAW_DIR` (additive); then `protected:` + `diff-scope`
   (opt-in by declaration); then isolation (refuses undeclared executors — the Claude dialect
   already declares); then severity.
3. Registry edits land with step 2. Rollback: each step is additive or declaration-gated.

## Open Questions

- Default `protected:` set (proposal Q1) — none in this change; a reference pipeline may
  publish a recommended list.

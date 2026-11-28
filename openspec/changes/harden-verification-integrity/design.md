# Design: harden-verification-integrity

## Context

See proposal.md — Why. Constraints:

- The law is read from bare objects at the peeled base-ref commit (`GitObjectsLawSource`,
  `LawSource.list(ref)`, `PipelineLawReader`); the working copy plays no part for take/serve.
  Criteria files already come from it; scripts a `shell` check runs do not.
- `TaskExecutionEnvironment.putFile(String path, byte[] content)`
  (`sandbox/core/.../TaskExecutionEnvironment.java:74`) and `readFile` are the file channel
  into and out of a box, valid for the working copy and the per-environment scratch area; the
  scratch area dies with the environment and is by contract "a factory-writable location"
  (`:99-108`). `putFile` takes no mode and no owner: the host adapter is a plain `Files.write`
  (`HostChannelFiles.java:28-39`, which resolves symlinks and refuses escapes), the container
  adapter a `mkdir -p … && cat > "$1"` in-box exec (`ContainerFileChannel.java:78,94`), so it
  cannot produce a sealed tree.
- `FactoryOwnedPaths` (adapters/git) and `EnvelopePaths` (domain) own the `.gnomish-task/`
  paths; salvage restores factory-owned paths after a round.
- The attempt commit and the task base are both reachable from the factory clone through
  `gitobjects`; a diff needs no box.
- After `extract-agent-round-runner` (landed): the Claude dialect's describe already declares
  `CLAUDE_CONFIG_DIR` and the flags `--setting-sources=user`, `--strict-mcp-config`
  (`ClaudeEnvironment.java:50,58`, `ClaudeDialect.java:120-121`); `--strict-mcp-config` is
  also rendered on every round by the dialect's policy compilation (`ClaudePolicyCompiler.java:57`,
  the `executor/claude-dialect` requirement "Policy compilation"); nothing enforces the declared
  isolation yet (its NG4). A round's environment is opened by the round sources
  (`HostRoundEnvironmentSource`, `SandboxRoundEnvironmentSource`, D14 of that change), not by
  the runner. After `migrate-verify-to-executor-contract`: checks are verdict executors with a
  threshold; a `verdict` request carries `RoleInputs` with four fixed fields
  (`RoleInputs.java:18-24`), part of the versioned wire contract (ADR 0016).
- The Claude CLI writes its own state into its config directory on every start, and with
  `CLAUDE_CONFIG_DIR` set writes there (`add-sandbox-base-image`, task 4.5, its design D14;
  its NG7 hands the content of `~/.claude` to D4 below).
- A factory child's environment is the factory-set `ExecCommand.env` fragment composed through
  `ChildEnvAllowlist` (`ExecCommand.java:15-25`); a box exec passes it by name
  (`fix-docker-exec-env-argv`).
- The law source caps each file at `GitObjectsLawSource.DEFAULT_READ_CAP_BYTES` (1 MiB,
  `GitObjectsLawSource.java:37-38`); nothing caps the tree's entry count or total size.
- Threat registry: #22 claims the law closes "weakens criteria, tests, limits"; only criteria
  are closed. Rows 49 (forging the factory's record) and 50 (program execution through the
  operator clone's local configuration) exist.

## Goals / Non-Goals

**Goals:** assets from the law commit only (FR1); protected paths enforced deterministically
(FR2–FR4); repo-local policy inert (FR5); advisory severity (FR6); registry truthful (FR7).

**Non-Goals:** sealing the build tool binary (NG1), hidden-test runners (NG2), host-mode ref
isolation (NG3), baselines (NG4), judge reducers (NG5).

## Decisions

**D1 — Materialize the whole law tree, read-only, per environment.** (FR1, NFR-S1, NFR-C1.)
One `LawMaterializer` reads every law entry through the law source and hands the whole tree to
a new environment operation, `TaskExecutionEnvironment.sealTree`, which writes it under a
factory-owned directory of the scratch root and seals it before returning the typed `LawDir`
(container: owned by root, mode 0555, the sealing exec running as root and touching only that
directory while every round, read and write runs as the task user; host: a directory outside
the worktree, mode 0555). `putFile` is unchanged. The materializer exposes the directory as
`GNOMISH_LAW_DIR` and does so once per environment lifetime (idempotent re-run). It copies only
the law tree's entries, each under the law source's per-file read cap; no tree-wide cap is added.
*Rationale:* one rule, no per-check asset list to keep in sync with the law; a sealed tree is an
operation of its own because the existing channel can produce neither an owner nor a mode, and
adding them to `putFile` would change a channel three other writers use.
*Alternative rejected:* per-check `assets:` lists — a second declaration of what the law already
contains, and the first forgotten asset is the hole. *Alternative rejected:* a `chmod` inside each
adapter's `putFile` — changes the semantics of every other writer's channel and leaves the
sealing rule in two undeclared places.

**D2 — `diff-scope` is a factory-side built-in over `gitobjects`.** (FR3, NFR-R2.) It reads
`taskBase..attemptCommit` from the factory clone — the task base is the SHA of the task's base
pin (the commit the task branch was created from), so the diff covers the task's whole work, not
one round's range — matches against `protected:` globs, applies the deletion rule and the size
cap, and emits SARIF. It is registered in `ExecutorRegistry` like other built-ins and is
dispatched like the box-less built-ins `github-actions` and `scm-status`, whose `Executor.start`
takes the request only and opens no environment (`GithubActionsExecutor.java:114`); no describe
field is added for it.
*Rationale:* deterministic, cheap, runs first in the chain by the fail-fast rule. *Alternative
rejected:* a `shell` check running `git diff` in the box — reads the gnome's git, not the
factory's objects.

**D3 — Protected-change list is a role input of every `verdict` request.** (FR4.)
`ProtectedChanges` is computed once per attempt by the same matcher `diff-scope` uses and
attached to every `verdict` request as an optional role input, `roleInputs.protectedChanges`
(path, glob, change kind per entry), added to `RoleInputs` and `executor-request.schema.json`
under the wire contract's additive-evolution rule: absent reads as empty, so an executor built
before it reads the request unchanged; a judge prompt renders it inside the data delimiters.
*Alternative rejected:* leaving it to the judge's own diffing — grading the gnome's narrative is
the anti-pattern the research named. *Alternative rejected:* appending it to the free-text
`criteria` — a judge cannot tell it from the criteria, and no typed value exists to assert the
identity of D8 row 2 on.

**D4 — Repo-local policy isolation lives in the runner, from describe.** (FR5, NFR-S2.) The
agent round runner creates a factory-owned config directory per round, sets the declared
variable, appends the declared flags; the loader refuses an agent executor declaring neither
unless `allow-repo-policy: <reason>` names it. The directory starts each round holding only
factory-written settings; it is writable by the CLI, which writes its own state files there on
every start (the measured fact above), and it is discarded with the round, so nothing a round
writes there reaches the next one. Login state: a round's credentials reach the CLI only through
the describe-declared credential variables, resolved through the child-environment allowlist
from the secrets port as today; no credential file is copied from the operator's home into the
directory. In host mode this is an operator-visible change — a CLI logged in only through
`HOME` loses that login once the variable points elsewhere — so the operator guide names the
variables to set (task 3.1). The runner is the one place a describe-declared flag is appended;
a flag the dialect's policy compilation already renders on every round
(`--strict-mcp-config`, owned by the `executor/claude-dialect` requirement "Policy compilation")
is not also declared in the describe, so the Claude describe declares `--setting-sources=user`
only. *Rationale:* the describe fields exist precisely for this; refusing by default makes a new
dialect declare its isolation story. *Alternative rejected:* a Claude-only fix — Cursor,
OpenCode, Amp, Gemini and Codex have the same class of file. *Alternative rejected:* a read-only
directory with only factory content — the CLI then fails its state write on every round
("continuing without persisting", measured). *Alternative rejected:* copying the operator's
credential file into the directory — a new plain-file secret channel to a child, outside the
secret channels of ADR 0017. *Alternative rejected:* the policy compiler stops rendering
`--strict-mcp-config` and the runner renders it — reopens a landed stable requirement for no
behavior gained.

**D5 — `severity` is a check attribute read by the engine, not by executors.** (FR6.) The verify
orchestrator records an advisory check's result with its severity and treats it as passed for
control flow; the tracker publication tags it. *Alternative rejected:* an advisory threshold
(`note`) — conflates "what fails" with "whether failing matters".

**D6 — Threat registry edits.** (FR7.) #22: "closed by the law commit for criteria and
manifests; for scripts, tests and limits by `GNOMISH_LAW_DIR` materialization, `protected:` +
`diff-scope`". Two new rows, numbered with the next free numbers at the time of the edit (51 and
52 today; rows are named here by topic, not by number): verification assets in gnome-writable
space; repo-local agent policy widens the gnome's permissions. #6: host mode notes the shared
`.git` as an accepted limitation of that mode.

**D7 — Sync surfaces.** Scout: registry and `Kept in sync with` markers; domain terms
"protected", "factory-owned", "law".
- `FactoryOwnedPaths` ↔ `EnvelopePaths`: not a pair (one owner, gate); `GNOMISH_LAW_DIR` is a
  scratch path, not an envelope path, so neither changes.
- Host vs container materialization: shared abstraction (preference 1) — one `LawMaterializer`
  over the new port operation `sealTree`; the two adapters' implementations (host, container)
  are two strategies of one port, held to one port contract spec, `SealedTreeContractSpec`, run
  over identical trees against both adapters (the precedent is the shared contract of
  `LawSource`, `LawSource.java:18`): same entries and bytes, every write by the task user fails,
  re-run idempotent. No `Kept in sync with` pair is declared, since the contract spec is what
  holds the two to one rule. `putFile` is untouched.
- `GitAttemptPersistence` ↔ `EnvironmentAttemptPersistence`: untouched; the law directory is
  outside the working copy and never committed or salvaged.
- Glob matching: `diff-scope` and `ProtectedChanges` share one `ProtectedPathMatcher`; a second
  matcher is forbidden by the table below.
- *No new pair created.*

**D8 — Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|---|---|---|---|---|
| `LawMaterializer` (adapters/law, over the environment port's `sealTree`) | `LawDir` (path inside the environment) | the two round environment sources, `HostRoundEnvironmentSource` (adapters/agent) and `SandboxRoundEnvironmentSource` (adapters/git), materializing at the open, once per environment, for every registry entry (agent, `command`, `program`); the two check environment sources, `HostCheckEnvironmentSource` and `SandboxCheckEnvironmentSource` (every check environment); the child-environment composition (`ExecCommand.env` composed through `ChildEnvAllowlist`), the one place `GNOMISH_LAW_DIR` is set for every child — agent, `shell`, `command`, `program` | criteria read for the judge keeps going through the law source (text, not files) — exempt, named; no other law-content path into the box exists today | `LawDir` type on the environment the round and check sources hand out; architecture spec in `:bootstrap` (`LawContentBoundarySpec`): law content (`LawSource.list`/`read` results) enters an environment only through `LawMaterializer` — an allowlist of the files reading `LawSource` with the reason of each, a ban on `sealTree(` outside the materializer and the port's implementations, asserted to reach every allowlisted file |
| `ProtectedPathMatcher` (domain) | `ProtectedChanges` (paths with matching glob and change kind), carried to an executor as `roleInputs.protectedChanges` | `diff-scope` built-in, `verdict` request assembly (`VerifyOrchestrator`) | none (new); identity: the `roleInputs.protectedChanges` the judge receives equals the list `diff-scope` fails on, `ProtectedChangesIdentitySpec` on a real bare origin | parameter type; grep gate: `PathMatcher.*glob` in `domain/verification` → one file |
| Repo-policy isolation (`AgentRoundRunner`) | `IsolationArgv` from `Describe` | every agent dialect through the runner | none today (the gap): the config directory and `--setting-sources=user` are declared but never passed; `--strict-mcp-config` stays the policy compilation's (`ClaudePolicyCompiler`) and leaves the describe, so no flag has two owners | load-time refusal without declaration or exemption; spec with the fake agent recording argv and `CLAUDE_CONFIG_DIR` (M2), asserting each flag appears once; grep gate: `--strict-mcp-config` in `adapters/agent/src/main` → one file (`ClaudePolicyCompiler.java`) |
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
- [Materializing the law per environment costs time on large law trees] → done once per
  environment, not per check; each entry is under the law source's per-file read cap, and no
  tree-wide cap is added (NFR-C1) — a law tree's size is the pipeline author's to keep small.
- [Host operators logged in to the agent CLI only through `HOME` lose that login once the
  config-directory variable points at the factory's directory (D4)] → the operator guide names
  the credential variables to set; the isolation step of the migration plan announces it.

## Migration Plan

1. After `extract-agent-round-runner` and `migrate-verify-to-executor-contract`.
2. Ship materialization and `GNOMISH_LAW_DIR` (additive); then `protected:` + `diff-scope`
   (opt-in by declaration); then isolation (refuses undeclared executors — the Claude dialect
   already declares; host operators set the credential variables, D4); then severity.
3. Registry edits land with step 2. Rollback: each step is additive or declaration-gated.

## Open Questions

- Default `protected:` set (proposal Q1) — none in this change; a reference pipeline may
  publish a recommended list.

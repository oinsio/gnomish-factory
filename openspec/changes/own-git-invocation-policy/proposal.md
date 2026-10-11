# Proposal: own-git-invocation-policy

## Why

The factory treats the operator clone's shared `.git/config` as two things it is not:

- **Its own storage.** Every fresh claim and every resume writes `core.hooksPath` into that
  file (`FactoryCloneHardening`, reached from seven call sites). git takes `.git/config.lock`
  with no wait before git 2.56, so two serve slots claiming at the same moment race, and one
  claim fails at random. CI went red on 2026-09-29 on exactly this
  (`TakeSlotRunnerContainerConcurrencySpec`). A `git branch <name> refs/remotes/origin/<name>`
  on the container resume path writes `branch.<name>.*` into the same file, so it races too. It
  also fails confusingly: the ref is created and git still exits 1, and the factory then reports
  "could not create local branch".
- **A trusted source of policy.** Every factory git command reads that file. The operator, the
  operator's tools (husky rewrites `core.hooksPath` on every `npm install`), or a host-mode gnome
  can all write to it. A one-time write does not hold. Verified on git 2.55:
  - `core.hooksPath` pointing at a planted hook runs it during the factory's `git commit`.
  - The planted hook runs in `update-ref`, both the runner's and `gitobjects`' own (the
    `reference-transaction` hook).
  - `commit.gpgSign` together with `gpg.program` runs an arbitrary program during the factory's
    `git commit`.
  - Separately, every factory `git commit` starts a detached `git maintenance run --auto` in the
    shared clone. That background work is serialized with nothing.

ADR 0008 already solved this for fetch and clone. The factory states its policy on every
invocation with `-c` pairs (command-line configuration, which beats every file scope) and
strips inherited per-process configuration. The other git commands — commit, branch,
update-ref, worktree, and the `gitobjects` plumbing — get none of that. The factory runs git
through two launchers with two different policies, and a third policy lives in the transfer
owner. The race is the visible symptom. The systemic defect is that the factory has no single
statement of how it invokes git.

## What Changes

- **ADDED** capability `git-invocation-policy`:
  - One owner holds a closed, command-scope configuration set, applied to **every** factory git
    process.
  - The set neutralizes hooks, commit and tag signing, fsmonitor, upstream auto-setup,
    automatic maintenance/gc and replace refs.
  - Inherited per-process configuration is stripped for every command, not only for transfers.
  - Transfers layer their existing set on top.
  - The factory writes no repository configuration: the runner refuses a config write the way it
    refuses an unowned transfer.
- **MODIFIED** `git-task-persistence`, "Factory git executes no untrusted content": the clause
  "every factory-managed clone SHALL have `core.hooksPath` pointed at an empty directory"
  becomes "every factory git invocation SHALL carry the hooks neutralization", and a planted
  local configuration SHALL execute nothing.
- **MODIFIED** `module-layering`: `gitobjects` may depend on `gittransfer`, which now also holds
  the invocation policy, so the plumbing launcher applies the same set.
- **REMOVED** (code): `FactoryCloneHardening`, `FactoryCloneHardeningException`,
  `TaskBranchGit.harden` and its seven call sites. This is **BREAKING** only for an operator
  who relied on the factory persisting `core.hooksPath` into their clone. That write stops; see
  UX2.
- Test build: the shared clone fixture gains an adversarial **local** configuration posture.
  The temporary `@Retry` on the concurrency spec is removed.
- Docs: ADR 0011; `docs/sandbox-threat-registry.md` row 38 revised and row 49 added;
  glossary entry.

## Capabilities

### New Capabilities
- `git-invocation-policy`: how every factory git process is invoked — one owner of the
  command-scope configuration set and stripped environment, no repository-configuration writes,
  both launchers covered, gate and identity specs.

### Modified Capabilities
- `git-task-persistence`: "Factory git executes no untrusted content" — hooks neutralized per
  invocation instead of by a persisted clone setting.
- `module-layering`: the `gitobjects` dependency set gains `gittransfer`; the `gittransfer`
  leaf's description gains the invocation policy.

## Goals

- **G1**: No factory git process reads a behavior-changing or program-running key from the
  clone's local configuration: hooks, signing, fsmonitor, upstream setup, maintenance, replace
  refs.
- **G2**: The factory never writes the clone's `.git/config`, so concurrent slots cannot race
  on it.
- **G3**: One owner of the invocation policy. Adding a launcher or a key is one edit, and a
  bypass fails the build.

## Non-Goals

- **NG1**: Closing git's untrusted-`.git` class in full — attribute-selected filter drivers,
  `info/attributes`, `objects/info/alternates` in a host-mode shared `.git`. git's own SECURITY
  guidance is a separate clone. That belongs to `add-sandbox-hardening`.
- **NG2**: Isolating the operator's credential and SSH configuration. `credential.helper` and
  `core.sshCommand` stay ambient (ADR 0008).
- **NG3**: Raising git's ref-lock timeouts. There is no evidence of ref-lock contention.
- **NG4**: Automatically unsetting the `core.hooksPath` that earlier factory versions persisted
  in operator clones. The operator is told how instead (UX2).
- **NG5**: Raising the git version floor (design, rejected alternative).
- **NG6**: The in-box git of a task environment. The gnome's own git and the box clone are
  outside the factory's trust domain.

## Users & Scenarios

- **U1**: A `serve` daemon with two slots claims two tasks from one clone at the same moment.
  Both claims proceed; neither fails on a configuration lock.
- **U2**: An operator's clone has husky installed (`core.hooksPath=.husky/_`) and a
  `commit.gpgSign=true` setting. Factory commits, ref updates and worktree operations run no
  hook and invoke no signing program. The operator's own commits keep their hooks.
- **U3**: An instance resumes a task another instance pushed. The local branch is recreated
  with no configuration written, so no misleading "could not create local branch" error
  appears.

## Requirements

### Functional

- **FR1**: Every factory git process — started by the factory git runner or by the git-object
  library — SHALL carry the invocation policy's command-scope configuration set and its
  environment stripping, placed before the subcommand.
- **FR2**: The set SHALL neutralize, by per-invocation value:
  - hooks;
  - commit and tag signing;
  - fsmonitor;
  - branch upstream auto-setup;
  - automatic maintenance and gc;
  - replace refs.
- **FR3**: Every factory git process SHALL be started with inherited `GIT_CONFIG_PARAMETERS`
  and `GIT_CONFIG_COUNT` removed.
- **FR4**: The transfer owner SHALL build a transfer on top of the invocation set. A transfer
  SHALL carry every key of the invocation set plus its own. Keys the transfer set re-asserts
  SHALL NOT diverge from the base set.
- **FR5**: The factory git runner SHALL refuse, before launching a process, a subcommand that
  writes repository configuration (`config` in a write form, `remote` in a write form), with an
  exception naming the policy. Read forms stay allowed.
- **FR6**: The factory SHALL no longer persist any configuration into a clone. The clone
  hardening step and its port method SHALL be removed.
- **FR7**: A build gate SHALL fail when a production class outside the two launchers starts a
  git process, and when a launcher builds its command line without the policy.
- **FR8**: A spec on real git SHALL run the factory's commit, ref-update, branch-creation and
  worktree paths against a clone whose local configuration plants a hook, a signing program,
  an fsmonitor command, `branch.autoSetupMerge=always` and a replace ref. It SHALL assert that
  nothing planted ran and that `.git/config` is byte-identical afterwards.

### Non-Functional Reliability

- **NFR-R1**: N concurrent fresh claims on one clone SHALL NOT fail on any `.git/config` lock.
  A spec with real threads pins this, and the concurrency spec passes without `@Retry`.
- **NFR-R2**: The container resume path SHALL create a local branch from a remote-tracking ref
  without writing configuration. A failing creation SHALL report a failure only when the ref
  was actually not created.

### Non-Functional Security

- **NFR-S1**: The hooks and signing neutralization SHALL hold even when the clone's local
  configuration was rewritten after the factory started — command-line scope beats local scope.
- **NFR-S2**: No key that carries the operator's credentials or transport (`credential.helper`,
  `core.sshCommand`, URL rewrites) SHALL be added to the set (ADR 0008 keeps them ambient).

### Non-Functional Observability

- **NFR-O1**: A refused configuration write SHALL surface as an exception naming the refused
  subcommand and the policy owner. No stderr parsing is involved.

### Non-Functional Performance / Cost

- Considered; none applies. The set adds a few argv elements per invocation and no process.
  No tokens are involved.

## Operator Experience Criteria

- **UX1**: After upgrading, an operator's clone is not modified by the factory. `git config
  --list --local` before and after a serve session is identical.
- **UX2**: Release notes (and `doctor`, once `add-doctor-command` lands) name the one line
  earlier versions left behind (`core.hooksPath=<clone>/.git/gnomish-empty-hooks`) and the
  command that removes it. Nothing is removed automatically.

## Success Metrics

- **M1**: `git.branches().harden` / `FactoryCloneHardening` occurrences in production: 7 call
  sites + 2 classes → 0.
- **M2**: Git process launchers applying the policy: 2 of 2 (today 0 of 2 for non-transfer
  commands).
- **M3**: `TakeSlotRunnerContainerConcurrencySpec` passes 20 consecutive local runs without
  `@Retry`; the new real-thread spec passes with 8 concurrent claims.
- **M4**: Adversarial local-configuration posture: 5 planted vectors, 0 executed across the
  existing real-git suites.

## Open Questions

- **Q1** (resolved in design): where the owner lives — inside `gittransfer` or in a new leaf.
- **Q2** (resolved in design): the `core.hooksPath` value — `/dev/null` or an empty directory.
- **Q3**: `add-doctor-command` must gain the UX2 check. Its artifacts are updated through
  `/opsx:update` on that change, not here.

## Impact

- `:gittransfer`: new `GitInvocation` owner; `GitTransfer` composes it.
- `:gitobjects`: `GitExec` applies the policy; new edge to `:gittransfer`.
- `:adapters:git`: `GitProcessRunner` applies the policy and refuses configuration writes.
  `FactoryCloneHardening*` is removed, and `GitTaskBranches.harden` with it.
- `:application`: `TaskBranchGit.harden` is removed, along with its 7 call sites. Three declared
  sync pairs have their markers reworded.
- `:bootstrap`: the new boundary spec and the concurrency spec; `RunnerStartHardeningSpec` is
  retired or rewritten.
- `:test-fixtures`: an adversarial local-configuration posture in `BareGitRepoFixture`.
- Docs: `docs/adr/0011-git-invocation-policy.md`, `docs/sandbox-threat-registry.md`,
  `docs/glossary.md`.
- Sequencing: this change lands before `add-subprocess-access-log`, which touches the same
  `GitProcessRunner.execute`.

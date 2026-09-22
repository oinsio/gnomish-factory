# Proposal: own-git-transfer-argv

Sequenced after `add-base-ref-resolution` (same `NarrowFetch` file; archived
2026-09-13), after `signal-outage-gate-on-origin-contact` (archived 2026-09-13
and synced: its origin-contact paragraph and two scenarios are part of the
stable "Base refresh fetch before task creation" text this change's delta is
written over), and after `split-logtext-leaves` (already synced into `openspec/specs/module-layering/`
on 2026-09-21; this change's module-layering delta is written over that stable
text, so the `:gittransfer` leaf sentences add to it rather than replace it), and
after the `module-layering` text of `add-subprocess-access-log` (still active,
but its two `module-layering` requirements already reached the stable spec
through the `add-base-ref-resolution` sync, so this change's delta carries the
access-log sentences and their `FR2, FR3` trailer as stable text; if
`add-subprocess-access-log` archives after this change, its `module-layering`
delta must be re-layered on the then-stable text, not synced as written). The three sandbox executor changes on the
roadmap (`add-sandbox-colima-vm`, `add-sandbox-cloud-executor`,
`add-sandbox-gha-executor`) each add a harvest source and are sequenced after
this change, so they add it through the owner this change introduces.

## Why

The factory runs `git fetch` and `git clone` from three kinds of source — the
trusted `origin`, an untrusted task container over git's `ext::` transport, and
the operator's own clone as the seed of a container's working copy — and each
of the three sites chose its own safety flags when its change was written.
ADR 0006 states that `NarrowFetch` builds every factory fetch; it builds one of
three. Reproduced on git 2.55 on 2026-09-13: the harvest fetch auto-follows a
tag the gnome created inside the box into the operator clone's `refs/tags/`
and writes `FETCH_HEAD`; the base refresh recurses into an initialized
submodule and performs a second, unnamed network fetch against the submodule's
remote; the seed clone runs in git's local-path mode, which bypasses object
validation, and carries the operator's local tags into the untrusted box. None
of the three validates the objects it receives (`fetch.fsckObjects` is off by
default), the protocol allowlist is opened for `ext::` rather than closed to
it, and the operator's `~/.gitconfig` (`submodule.recurse`, `fetch.prune`,
`url.<base>.insteadOf`) reaches every factory git subprocess. Three more
harvest sources are on the roadmap; without one owner they become sites four
to six.

## What Changes

- **ADDED** capability `git-transfer`: one owner builds the argv and the
  environment of every factory git transfer (fetch, clone) from a typed
  source kind and one refspec; a deny-by-default side-effect set common to
  every source; a closed per-source protocol allowlist; object validation on
  every transfer; per-source configuration isolation; the runner's untyped
  entry refuses transfer subcommands; a build gate keeps transfer argv inside
  the owner; a git version floor checked at startup. (FR1–FR10)
- **MODIFIED** `git-task-persistence` "Harvest protocol": the harvest fetch
  is built by the owner — no tag auto-follow, no `FETCH_HEAD`, `ext` as the
  only permitted protocol, validated objects, full configuration isolation.
  (FR6)
- **MODIFIED** `git-task-persistence` "Sandboxed working copy is an
  independent full clone": the seed clone runs through git's transport path
  (never local-path mode), carries no tags, validates objects, and is built
  by the owner. (FR7)
- **MODIFIED** `git-task-persistence` "Base refresh fetch before task
  creation": the refresh is built by the owner and therefore never recurses
  into submodules; its existing "shall not" list is stated as a consequence
  of the common set. (FR5)
- **MODIFIED** `module-layering`: a JDK-only `:gittransfer` leaf joins the
  module tree; `adapters:git` and `sandbox:docker` depend on it. (FR2)
- **ADDED** `docs/adr/0008-git-transfer-policy.md`; ADR 0006 keeps the
  refspec-per-kind decision and cites 0008 for the flag set. Three rows join
  `docs/sandbox-threat-registry.md`; "transfer" and "transfer source" join
  `docs/glossary.md`. (FR11)

## Capabilities

### New Capabilities
- `git-transfer`: the policy and the mechanism behind every git transfer the
  factory performs — what a transfer may change, from which source, under
  which protocol, with which validation, and what keeps every transfer inside
  the one owner.

### Modified Capabilities
- `git-task-persistence`: "Harvest protocol", "Sandboxed working copy is an
  independent full clone", "Base refresh fetch before task creation" (layered
  on `signal-outage-gate-on-origin-contact`).
- `module-layering`: "Layered Gradle module tree", "Enforced acyclic
  dependency direction" (written over the stable text as synced through
  `split-logtext-leaves`, which already holds the `add-subprocess-access-log`
  sentences for the same two requirements; that change is sequenced before
  this one for `module-layering`, and this delta preserves its text).

## Goals

- G1: every git transfer the factory performs changes exactly the refs it
  named, and nothing else in the operator's clone or in the box.
- G2: the operator's shell environment and global git configuration cannot
  alter what a transfer from an untrusted source does.
- G3: a new transfer source (the roadmap's VM, cloud, and GHA executors) is
  added by adding a source kind, not a flag list.

## Non-Goals

- NG1: `--negotiation-tip` (bounding what the box learns about the operator's
  commits during negotiation) — a disclosure concern, deferred to a later
  change; no active change carries it yet.
- NG2: a post-fetch connectivity check (`rev-list --objects <tip> --not
  --all`) — fsck validates objects; reachability closure is not this change.
- NG3: replacing the git subprocess with JGit or libgit2 (ADR 0001).
- NG4: `git bundle` as a harvest format. A bundle source kind is
  `add-sandbox-gha-executor`'s to add through the owner (G3); this change adds
  no bundle kind.
- NG5: pushes. Push argv stays where it is; this change owns fetch and clone.
- NG6: making the fsck legacy-ignore list operator-configurable (Q1).

## Users & Scenarios

- **U1 Operator whose clone the factory shares**: after a day of `serve`,
  `git tag` and `git for-each-ref` in their clone show nothing the factory
  did not name; `FETCH_HEAD` is untouched; their `~/.gitconfig` neither helps
  nor hurts a harvest.
- **U2 Gnome inside a box**: a tag it creates, a `.gitmodules` it plants, a
  hook it installs — none reaches the factory clone; a malformed object is
  refused at the boundary with a report the operator can read.
- **U3 Developer adding a sandbox executor**: implements a source kind with
  its transport and protocol name; the flag set, isolation, validation, gate,
  and specs come for free.
- **U4 Operator on an old git**: startup refuses with the floor, the installed
  version, and the reason (local-clone hook protection), instead of running
  with a silently weaker seed clone.

## Requirements

### Functional

- FR1: One owner SHALL build the argv and the environment of every factory
  git transfer from exactly two inputs: a transfer source (one of a closed set
  of kinds) and one refspec. The owner produces values (argument list,
  environment map); it launches nothing.
- FR2: The owner SHALL live in a JDK-only leaf module reachable from both
  `adapters:git` (which runs the argv through the git runner) and
  `sandbox:docker` (which renders the seed clone's argv into the seed
  helper's script).
- FR3: Every transfer SHALL carry the common deny-by-default set: no tag
  auto-following, no `FETCH_HEAD` write, no submodule recursion (flag and
  `-c fetch.recurseSubmodules=no`), no prune, no automatic maintenance or gc,
  an options terminator before the source and refspec, and — for a named
  remote — an empty refmap. Full depth on every transfer.
- FR4: Every transfer SHALL run under a closed protocol allowlist set by the
  owner per source (`GIT_ALLOW_PROTOCOL`): `https:ssh:file` for `origin`,
  exactly `ext` for a container, exactly `file` for a seed path. The
  allowlist is the whole protocol policy: git treats each listed protocol as
  `always` and every other as `never`, overriding every configuration scope,
  so no `protocol.*.allow` key and no `GIT_PROTOCOL_FROM_USER` value has an
  effect beside it and the owner emits neither. What git may start on its
  own (submodule recursion) is closed by FR3, not by protocol policy.
- FR5: Every transfer SHALL validate received objects (`fetch.fsckObjects` /
  `transfer.fsckObjects`), with exactly three legacy message ids downgraded
  to ignore (`badTimezone`, `missingSpaceBeforeDate`, `zeroPaddedFilemode`).
  A validation refusal is a named outcome: for a harvest, a boundary
  violation of the box; for a base refresh or task-branch locate, a
  task-level park with a report naming the object and the message id; never
  an infrastructure failure.
- FR6: Configuration isolation SHALL be per source. Container and seed
  sources run with the global and system configuration and the XDG config
  home pointed at nowhere; the seed helper's throwaway global file holding
  `safe.directory` is the one exception and holds nothing else. The `origin`
  source keeps the operator's global configuration (ambient credential
  helpers and URL rewrites are the operator's), and the owner re-asserts by
  `-c` every key the common set depends on. Every source strips inherited
  `GIT_CONFIG_PARAMETERS`, `GIT_CONFIG_COUNT`, `GIT_OBJECT_DIRECTORY`,
  `GIT_ALTERNATE_OBJECT_DIRECTORIES`, `GIT_WORK_TREE`, and `GIT_INDEX_FILE`.
- FR7: The seed clone SHALL use git's transport path (`--no-local`), never
  local-path mode, in addition to `--no-hardlinks`, `--single-branch`, and
  `--no-tags`; the resulting box holds exactly the task branch and no tag.
- FR8: The git runner's untyped entry SHALL refuse the transfer subcommands
  (`fetch`, `clone`, `pull`, `submodule update`, `remote update`) with an
  exception naming the owner; transfers enter the runner only as the owner's
  typed value. `pull` has no owner form: it stays forbidden on every path.
- FR9: A build gate in `:bootstrap` SHALL fail when a production source file
  outside the owner spells a transfer subcommand as a git argument literal or
  in a shell script literal, and SHALL assert it scanned every production
  source root.
- FR10: The factory SHALL check the installed git version once at startup
  against a floor of 2.45.1 and refuse to run below it with a report naming
  the floor, the installed version, and the reason; the check runs before any
  transfer.
- FR11: ADR 0008 records the policy (sources, common set, per-source table of
  what a transfer may and may not change, the floor and why); ADR 0006 is
  amended to cite it for flags and to drop the claim that `NarrowFetch`
  serves every fetch; the threat registry gains rows for object poisoning
  through harvest, ref planting into the operator clone, and operator ref
  disclosure into the box; the glossary defines the two terms.
- FR12: For each source kind one spec on real git asserts, on an adversarial
  fixture (tags pointing into the fetched history, a pre-existing tag of the
  same name, an initialized submodule with a newer remote commit, a global
  config enabling recursion and prune), that the set of refs changed by the
  transfer is exactly the named destination and `FETCH_HEAD` is untouched.

### Non-Functional Reliability

- NFR-R1: A validation refusal never burns a stage attempt as a quality
  failure and never charges the outage accounting of ADR 0005: it is the
  task's, with its report.
- NFR-R2: The floor check is idempotent and runs once per process; a git
  binary that cannot report its version is refused the same way as one below
  the floor.

### Non-Functional Observability

- NFR-O1: The startup log carries one INFO line with the detected git version
  and the floor; a refusal is one ERROR line with the same two values and the
  reason.
- NFR-O2: A validation refusal report carries the fsck message id and the
  object id, sanitized as untrusted text like every other git stderr excerpt.

### Non-Functional Security

- NFR-S1: Nothing produced inside a box (a tag name, a submodule URL, a
  config value, an object) can cause the factory to write a ref it did not
  name, contact a host it did not name, or run a command.
- NFR-S2: The operator's global git configuration and shell environment
  cannot widen what a container or seed transfer does.
- NFR-S3: No credential reaches a container or seed transfer: those sources
  carry no credential helper configuration and no askpass.

### Non-Functional Performance

- NFR-P1: Object validation adds no network round trip; its CPU cost is
  bounded by the pack already being received. No transfer becomes shallow.

## Operator Experience Criteria

- UX1: The operator's `git for-each-ref` before and after any factory
  operation differs only in the refs ADR 0008's table names for it.
- UX2: A refusal at startup reads like a precondition, not a crash: the
  floor, the installed version, and one sentence of why.
- UX3: A harvest refused for a malformed object parks the task with the same
  report shape as a history-rewrite refusal, naming what was refused.

## Success Metrics

- M1: The gate reports zero transfer literals outside the owner, and the
  scan reaches every production source root.
- M2: The four reproductions of 2026-09-13 (box tag, `FETCH_HEAD`, submodule
  recursion, seed-clone tags) exist as specs on real git and pass.
- M3: Every fetch/clone argv in production is produced by the owner: the
  runner's typed entry is the only path a fetch takes (identity spec on the
  runner).
- M4: Adding a fourth source kind touches one sealed type and one table row
  in ADR 0008.

## Open Questions

- Q1: Should the fsck legacy-ignore list be operator-configurable for a
  repository whose history carries other malformed-but-harmless objects?
  Decided for now: no; a refusal parks the task with the message id, and the
  list grows only by a change.
- Q2: Should the `origin` allowlist include `git://` (unauthenticated)?
  Decided for now: no; an operator with such a remote reports it.

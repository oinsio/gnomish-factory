# Design: own-git-transfer-argv

## Context

See proposal.md — Why. Constraints that shape the approach:

- Three fetch-family sites exist today: `NarrowFetch` (five callers: `BaseRefresh`,
  `TagBaseFetch`, `CommitBaseFetch`, `TaskBranchLocator`, `ReplicaPairReconciler`),
  `ContainerHarvestFetch` (one caller, `ContainerRunSupportFactory`), and the seed clone
  script in `DockerSeedCloneCommand` (rendered into a `docker run --rm` helper). Their
  flag sets are disjoint in the wrong places (proposal — Why).
- `adapters:git` depends on `sandbox:docker` (the harvest realizes a sandbox port), never
  the reverse. The seed clone is a shell script that runs inside a helper container, not a
  `ProcessBuilder` call. So no class in `adapters:git` can serve the seed clone.
- `GitProcessRunner` already classifies argv (`GitNetworkCommands.subcommandIndex` skips
  leading `-c` pairs) to decide bounding and the clone-mutation lock. It sets `LC_ALL`,
  `GIT_TERMINAL_PROMPT=0` and empties the askpass hooks; it isolates no configuration.
  `gitobjects`' `GitExec` already sets `GIT_CONFIG_GLOBAL`/`GIT_CONFIG_SYSTEM=/dev/null`
  per call — the in-repo model for isolation.
- Ambient credentials on `origin` are a standing decision (vigilante review, 2026-09): the
  operator's credential helper and URL rewrites must keep working for `origin`.
- The external precedent closest to the harvest direction is Gitaly (fetch from a
  user-controlled remote into a trusted store): one command factory, fixed environment,
  fixed `-c` set, `transfer.fsckObjects` with three legacy ignores, `--no-write-fetch-head`.
  libgit2's `git_fetch_options` and JGit's `FetchCommand` show the API shape: every side
  effect is a typed field, not a flag a caller may omit. `actions/checkout`, Cargo, and Go
  modules confirm `--no-tags`, `--end-of-options`, explicit refspecs.
- Driven by FR1–FR12, NFR-S1–S3, NFR-R1 of the proposal.

## Goals / Non-Goals

**Goals:** one owner, one common set, per-source differences expressed as data, the escape
hatch closed by type, the gate proving reach, identity specs on real git, the policy in an
ADR that outlives this change.

**Non-Goals:** NG1–NG6 of the proposal. Additionally at design level: no change to push
argv; no change to `GitProcessRunner`'s bounding, stall detection, or credential scrubbing;
no change to `gitobjects` (it performs no transfer).

## Decisions

**D1 — The owner is a value builder in a JDK-only leaf, `:gittransfer`.** A sealed
`TransferSource` (`Origin`, `Container(extUrl)`, `SeedPath(path)`) plus one `Refspec` go in;
a `GitTransfer` value — `List<String> argv` (from `-c` pairs through the refspec) and
`Map<String, String> environment` (set and unset entries) — comes out. Nothing in the leaf
launches a process or touches the filesystem. *Rationale:* FR2 — the two consumers sit on
opposite sides of the layering (`adapters:git` above `sandbox:docker`), and the seed clone
is script text, so only a leaf both can reach, holding values rather than behavior, serves
both from one owner; the same reason `:untrustedtext` and `:baseref` are leafs.
*Alternative rejected:* owner in `adapters:git` plus a declared sync pair with the seed
script. Rejected by `manual-sync-pairs.md`'s rule of three — this is the third
implementation, so extraction is mandatory, not optional — and by the roadmap: three more
harvest sources would each need a pair.

**D2 — The common set is one static list; per-source differences are a table of three
rows.** Common: `--no-tags --no-write-fetch-head --no-recurse-submodules --end-of-options`,
`-c fetch.recurseSubmodules=no -c submodule.recurse=false -c fetch.prune=false -c
maintenance.auto=false -c gc.auto=0 -c fetch.fsckObjects=true -c transfer.fsckObjects=true`
and the three `fetch.fsck.<id>=ignore` entries; `GIT_PROTOCOL_FROM_USER=0`; unset
`GIT_CONFIG_PARAMETERS`, `GIT_CONFIG_COUNT`, `GIT_OBJECT_DIRECTORY`,
`GIT_ALTERNATE_OBJECT_DIRECTORIES`, `GIT_WORK_TREE`, `GIT_INDEX_FILE`. Per source:

| Source | Subcommand | `GIT_ALLOW_PROTOCOL` | Config isolation | Extra |
|---|---|---|---|---|
| `Origin` | `fetch origin <refspec>` | `https:ssh:file` | keep global; keys above re-asserted by `-c` | `--refmap=` |
| `Container` | `fetch <ext-url> <refspec>` | `ext` | `GIT_CONFIG_GLOBAL`, `GIT_CONFIG_SYSTEM`, `XDG_CONFIG_HOME` → `/dev/null` | `-c protocol.ext.allow=user` stays (git's documented enable for `ext`; the allowlist is what closes the rest) |
| `SeedPath` | `clone --no-local --no-hardlinks --single-branch --branch <b> <path> <dest>` | `file` | `GIT_CONFIG_SYSTEM`, `XDG_CONFIG_HOME` → `/dev/null`; `GIT_CONFIG_GLOBAL` → the helper's throwaway `safe.directory` file | no `--refmap=` (a clone has no configured remote yet) |

*Rationale:* FR3–FR7. `file` on `Origin` is what the test fixtures' bare-path origins use;
`git://` is deliberately absent (proposal Q2). `--refmap=` is meaningless for a URL source and
git rejects it on `clone`, so it is the one flag that is per-source rather than common.
*Alternative rejected:* deriving `Origin`'s allowlist from the remote URL's scheme at run
time — one more git call per transfer to learn what a fixed list already says; and a scheme
list is policy, which belongs in the table.

**D3 — `Origin` keeps the operator's global configuration.** *Rationale:* FR6, and the
standing ambient-credentials decision — `credential.helper`, `url.<base>.insteadOf` for a
company mirror, and `core.sshCommand` are how the operator's push and fetch authenticate. What
the common set depends on is re-asserted by `-c`, which wins over every config scope, so the
global file cannot re-enable recursion, prune, or maintenance. An `insteadOf` rewrite cannot
smuggle `ext::` in: the allowlist for `Origin` omits `ext`, and git applies the allowlist after
the rewrite (spec scenario "Origin cannot use the container transport" pins it on real git).
*Alternative rejected:* full isolation for `Origin` with an explicit credential
configuration passed by the factory — this reopens a decision the project closed, and every
operator setup (helper, SSH agent, mirror) would need a factory-side equivalent.

**D4 — Object validation on every source, three legacy ignores, refusal classified per
caller.** `fetch.fsckObjects=true` (with `transfer.fsckObjects=true` for `clone`, which reads
the transfer key); `fetch.fsck.badTimezone`, `missingSpaceBeforeDate`, `zeroPaddedFilemode`
set to `ignore` — the three that break real-world histories and that Gitaly ignores
build-wide. Git prints `error: object <id>: <msg-id>: ...` and `fatal: fsck error in packed
object`; the git adapter classifies that stderr shape into a `ValidationRefused` outcome
carrying id and object as `UntrustedText`, before the existing non-fast-forward and daemon
classifications. `ContainerHarvestFetch` maps it to the boundary-violation exception the
rewrite refusal already uses; `RefreshedTip`/`TaskBranchLocator` map it to a task-level
`Refused`. *Rationale:* FR5, NFR-R1 — a refused object is the repository's or the box's, never
the daemon's, so it must not reach the outage accounting of ADR 0005. *Alternative
rejected:* validating `Container` only. Gnome branches live on `origin` too (every
task-branch locate on resume fetches gnome-authored history), so an origin fetch is not a
trusted-content fetch.

**D5 — The escape hatch closes in `GitProcessRunner`, by type and by refusal.**
`GitProcessRunner.run(Path, String...)` gains a guard: if `GitNetworkCommands.subcommandIndex`
lands on `fetch`, `clone`, `pull`, `submodule` (followed by `update`), or `remote` (followed
by `update`), it throws `UnownedTransferException` naming `GitTransfer`. A new
`run(Path, GitTransfer)` applies the value's environment on the `ProcessBuilder` (set and
unset), then executes its argv through the same `execute` path — bounded, stall-detected,
under the clone mutation lock, stderr scrubbed. *Rationale:* FR8, `implementation.md` item 3:
a `String...` entry that accepts `fetch` is the raw form the owner replaces. *Alternative
rejected:* a `-c`-aware allowlist of "known-good" flag sets in the runner — that is a second
copy of the policy, exactly the twin this change removes.

**D6 — The seed clone renders the owner's value into its script; the helper keeps its
`safe.directory` file.** `DockerSeedCloneCommand` takes a `GitTransfer` for `SeedPath` and
renders `env K=V ... git <argv>` (unset entries as `env -u K`) at the clone line; the
`GIT_CONFIG_GLOBAL` export the script already does for `safe.directory` is what the
`SeedPath` row names as the global file, so the environment the owner emits and the script's
own export agree by construction (one spec pins the rendered line). *Rationale:* FR6, FR7 —
git honors `safe.directory` from global/system scope only, so the throwaway file must exist;
it holds nothing else. *Alternative rejected:* mounting the factory clone with a uid map so
`safe.directory` is unnecessary — a Docker Desktop vs Linux difference the current comment
already documents as the reason for the file.

**D7 — The gate is a source scan in `:bootstrap`, precedent `BaseHeadDefaultBoundarySpec`,
plus a reach assertion.** `GitTransferBoundarySpec` scans every `src/main` root under the
build (enumerated from `settings.gradle` includes, so a new module cannot fall outside it —
FR9's reach clause) for the tokens `"fetch"`, `"clone"`, `"pull"`, `git fetch`, `git clone`,
`git pull`, `submodule update`, `remote update` in Java and script literals, with an allowlist
of exactly: the owner's files in `:gittransfer`, `GitNetworkCommands` and `GitProcessRunner`
(they classify these tokens; they do not build them). *Rationale:* FR9, M1. ArchUnit cannot
see string literals; a scan can, and the reach assertion answers "did the list keep up" —
the lesson the 2026-09-12 review left in `BaseHeadDefaultBoundarySpec`'s javadoc.
*Alternative rejected:* ArchUnit "only `:gittransfer` may reference `ProcessBuilder` with a
git argv[0]" — argv[0] is a runtime value, invisible to bytecode rules.

**D8 — The git floor is a startup step in `bootstrap`, 2.45.1.** `GitVersionFloor` runs
`git --version` through `GitProcessRunner` once, before any command that transfers
(`run`, `take`, `serve` all pass through it), parses `git version X.Y.Z` leniently
(vendor suffixes ignored), and refuses below `2.45.1` with `[GFnnn]`-coded ERROR naming
floor, installed, and "the seed clone relies on git's local-clone hook protections
(2.39.4/2.45.1)". *Rationale:* FR10, NFR-R2, NFR-O1; `--no-write-fetch-head` (2.29),
`--end-of-options` (2.24), `GIT_CONFIG_GLOBAL` (2.32) all sit below the floor, so one
floor covers every flag the owner uses. *Alternative rejected:* per-flag version gating
as Jenkins does — a branch per flag, and the seed clone's protection is a version, not a
flag.

**D9 — `NarrowFetch` is deleted, not kept as a facade.** Its five callers call
`runner.run(cloneDir, GitTransfer.fetch(TransferSource.ORIGIN, refspec))` directly; its
javadoc's flag rationale moves to ADR 0008. `ContainerHarvestFetch` keeps its name, URL
assembly, and classification; its argv comes from the owner. *Rationale:* a facade that only
forwards is the arid delegation `testing.md` exempts from mutation for a reason — it hides
the owner. *Alternative rejected:* keeping `NarrowFetch.of` as the `Origin` convenience —
two names for one construction site is how the ADR 0006 claim went stale.

**D10 — ADR 0008 owns the policy; ADR 0006 keeps refspec-per-kind.** ADR 0008 carries the
source table, the common set with a one-line reason per flag (moved from `NarrowFetch`'s
javadoc), what each source may and may not change, validation and its classification, the
floor, and the roadmap rule "a new source is a row here and a kind in the leaf". ADR 0006's
"Flags on every refresh" section is replaced by a pointer, and its sentence that `NarrowFetch`
"serves every factory fetch" is removed. The threat registry gains three rows (object
poisoning through harvest; ref planting into the operator clone; operator ref disclosure into
the box) mapped to this change. *Rationale:* FR11, `crash-consistency.md`'s referencing
rule — policy lives in `docs/adr/`, a design archives.

### Sync surfaces

The change touches the `GitAttemptPersistence ↔ EnvironmentAttemptPersistence` pair not at
all (persistence, not transfer). It **removes** an undeclared triple — the three fetch-family
flag sets — by extracting the shared abstraction (D1), per the rule-of-three preference. The
seed script's `env` line and the owner's environment map are not a pair: the script renders
the map (D6). No declared pair is touched.

### Single-owner mechanisms

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `GitTransfer.fetch/clone` (`:gittransfer`) | `GitTransfer` (argv + environment) | `BaseRefresh.fetchBranch`, `TagBaseFetch.fetch`, `CommitBaseFetch.fetch`, `TaskBranchLocator.locate`, `ReplicaPairReconciler` (all `adapters:git`); `ContainerHarvestFetch.fetch` (`adapters:git`); `DockerSeedCloneCommand.seedClone` (`sandbox:docker`) | `NarrowFetch` — deleted (D9); the literal argv in `ContainerHarvestFetch.fetch` — deleted; the `git clone ...` line in `SEED_SCRIPT` — replaced by the rendered value. Sweep pattern: `"fetch"`, `"clone"`, `git clone`, `git fetch` across every `src/main`. Exemptions: none | `GitProcessRunner.run(Path, GitTransfer)` is the only entry that runs a transfer; `run(Path, String...)` throws on a transfer subcommand (D5); `GitTransferBoundarySpec` scan with reach assertion (D7) |
| `GitVersionFloor` (`bootstrap`) | `GitVersion` (parsed, compared) | the three command entry points (`run`, `take`, `serve`) | none existed | startup ordering spec: no transfer before the floor check (`GitVersionFloorSpec` on the shared app-layer assembly fixture) |

Identity claims and their specs: "the refs changed by a transfer are exactly the named
destination" — `GitTransferIdentitySpec` (one feature per source kind, real git, adversarial
fixture, FR12); "the seed script's environment is the owner's" — `DockerSeedCloneCommandSpec`
asserts the rendered `env` line equals the `SeedPath` value's map.

## Risks / Trade-offs

- [A real repository's history fails fsck beyond the three ignores] → the task parks with the
  message id in the report (UX3), the operator sees which object; the list grows only by a
  change (Q1). Not silently ignoring is the point.
- [`--no-local` makes the seed clone copy objects through the pack protocol instead of
  hardlinking or copying files] → `--no-hardlinks` already forced a file copy; the transport
  path costs one pack build per seed, bounded by repository size, and buys validation and
  the 2024 local-clone CVE class. Measured once in the Docker-gated seed spec; no budget
  change expected.
- [An operator whose `origin` is `git://`] → refused by the allowlist with git's own
  "protocol not allowed" message; Q2 records the decision.
- [The gate's token list misses a future spelling (`git-fetch` helper, `fetch-pack`)] → the
  runner's refusal (D5) is the second layer: an argv the scan misses still cannot run through
  the runner; a script literal that bypasses both is a review item under `implementation.md`.
- [`GIT_ALLOW_PROTOCOL` on `Origin` breaks an operator using a custom remote helper
  (`hg::`, a corporate `s3::`)] → documented in ADR 0008 as unsupported; the allowlist is
  the policy, and widening it is a change.
- [Deleting `NarrowFetch` while `add-base-ref-resolution` is uncommitted] → sequencing: this
  change applies only after that work is committed; task 1 asserts a clean tree.

## Migration Plan

No data migration. The seed helper image needs git ≥ 2.45.1 too (it runs the clone): the
floor check covers the factory host; the helper's git version is asserted once in the
Docker-gated seed spec and documented as an image requirement. Rollback is a revert: no
persisted format changes.

## Open Questions

None that change the specs or the task breakdown. Q1 and Q2 of the proposal are decided for
now and recorded there.

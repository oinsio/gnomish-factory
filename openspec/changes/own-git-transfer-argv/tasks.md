# Tasks: own-git-transfer-argv

Sequenced after `add-base-ref-resolution` (its working-tree edits to
`NarrowFetch` and its callers must be committed before task 1.1),
`signal-outage-gate-on-origin-contact` and `split-logtext-leaves` (delta
layering, see the two delta preambles). Groups 2–5 leave the build red between
them on purpose — the runner's refusal (2.2) lands before the last caller
moves (5.x); groups 2–5 are one commit. Every group's report ends with the
old-way sweep of `implementation.md`: the grep run, the hits, and what
happened to each.

## 1. The leaf and its values

- [ ] 1.1 Assert a clean tree (`git status --porcelain` empty) and create the
      `:gittransfer` module: `settings.gradle` include with the rationale
      comment in the style of `:baseref`, `build.gradle` with
      `library-conventions` + `layering-conventions` and `allowedProjects = []`,
      no external dependency. Add the `:gittransfer` sentences to the
      `module-layering` deltas' counterpart gate config. Verify: `./gradlew
      projects` lists `:gittransfer`; `:gittransfer:check` green on an empty
      module (FR2).
- [ ] 1.2 TDD (red first) `TransferSourceSpec`: sealed `TransferSource` with
      `Origin` (singleton), `Container(String extUrl)`, `SeedPath(Path source,
      Path destination, String branch)`; each carries its protocol allowlist
      string and its configuration-isolation shape as values (design D2
      table). `Refspec` value type refusing a leading `-` and an empty string.
      Verify: data-driven spec over the three kinds asserts the allowlist
      strings `https:ssh:file` / `ext` / `file` (FR4).
- [ ] 1.3 TDD (red first) `GitTransferSpec`: `GitTransfer.fetch(source,
      refspec)` and `GitTransfer.clone(SeedPath)` return `argv()` and
      `environment()` (`Map<String, Optional<String>>` — empty means unset).
      Assert per source: the common set of design D2 (flags, every `-c` pair,
      the three fsck ignores), `--refmap=` on `Origin` only, `-c
      protocol.ext.allow=user` on `Container` only, `clone --no-local
      --no-hardlinks --single-branch --no-tags --branch <b>` on `SeedPath`,
      `--end-of-options` before source and refspec, environment set/unset
      entries per row, `GIT_PROTOCOL_FROM_USER=0` everywhere. Verify: one
      feature per source kind, green; PIT 100% on the leaf (FR1, FR3, FR4,
      FR5, FR6, FR7).
- [ ] 1.4 `GitVersion` value in the leaf: lenient parse of `git version X.Y.Z`
      (vendor suffixes such as `(Apple Git-…)` ignored), comparison, and the
      `FLOOR = 2.45.1` constant with its reason string. TDD: parse table
      including a blank and a garbage line → `Optional.empty()`. Verify:
      `GitVersionSpec` green (FR10, NFR-R2).

## 2. The runner: typed entry and refusal

- [ ] 2.1 TDD (red first) in a new `GitProcessRunnerTransferSpec`: `run(Path,
      GitTransfer)` applies the value's set and unset entries to the
      `ProcessBuilder` environment and executes the argv through the existing
      `execute` path — assert with the recording-git fixture that the argv
      carries the stall-detection prefix, that `GIT_ALLOW_PROTOCOL` and
      `GIT_PROTOCOL_FROM_USER` reach the child (the fake git echoes `env`),
      and that an inherited `GIT_CONFIG_COUNT` does not. Verify: green (FR8,
      FR6, NFR-S2).
- [ ] 2.2 TDD (red first) in the same spec: `run(Path, String...)` throws
      `UnownedTransferException` (message names `GitTransfer`) for `fetch`,
      `clone`, `pull`, `submodule update`, `remote update`, with and without
      leading `-c` pairs, before any process is launched (the fake git's
      record file stays absent); `rev-parse`, `push`, `ls-remote` still run.
      Implement via `GitNetworkCommands.subcommandIndex`. Verify: green;
      the mutation lock and bounding features of `GitProcessRunnerSpec`
      unchanged (FR8).
- [ ] 2.3 Add `ValidationRefused` to the git adapter's fetch classification:
      a stderr carrying `fsck error` / `error: object <id>: <msg-id>` maps to
      it, carrying id and object as `UntrustedText`, checked before the
      non-fast-forward and daemon classifications. TDD on the stderr shapes
      from git 2.55 (record real output in the spec's fixture). Verify:
      `FetchClassificationSpec` green (FR5, NFR-O2).

## 3. Origin consumers

- [ ] 3.1 Replace the five `NarrowFetch.of` calls — `BaseRefresh.fetchBranch`,
      `TagBaseFetch.fetch`, `CommitBaseFetch.fetch`, `TaskBranchLocator`,
      `ReplicaPairReconciler` — with `runner.run(cloneDir,
      GitTransfer.fetch(TransferSource.ORIGIN, refspec))`; delete
      `NarrowFetch.java`; move its per-flag rationale into ADR 0008 (task
      7.1); rewrite the `ReplicaPairReconciler` comment that names it.
      Verify: `:adapters:git:compileJava` green; `BaseRefreshSpec` "every
      refresh fetch carries the flags that keep it narrow" extended to assert
      `--no-recurse-submodules` and the fsck `-c` pairs (FR3, FR5).
- [ ] 3.2 `RefreshedTip.of` and `TaskBranchLocator` map `ValidationRefused`
      to a task-level `Refused` with the report naming message id and object;
      never to `Unavailable`. TDD with the recording git returning the fsck
      stderr. Verify: `BaseRefreshSpec` and `TaskBranchLocatorSpec` features
      green; the outage gate's `RemoteOutageSignalingBaseRefGitSpec` sees no
      `Unavailable` from it (FR5, NFR-R1).
- [ ] 3.3 Identity spec on real git, `GitTransferIdentitySpec` feature
      "origin": adversarial fixture per FR12 — bare origin with a tag inside
      the fetched history, a clone holding a same-name tag at another commit,
      an initialized submodule whose remote has advanced, and a temporary
      global config file (`GIT_CONFIG_GLOBAL` for the spec's own setup only)
      setting `submodule.recurse=true` and `fetch.prune=true`. Assert the
      `for-each-ref` diff of the clone is exactly the named tracking ref, the
      submodule's refs are unchanged, and `FETCH_HEAD` is absent. Verify: the
      feature is red against the pre-change argv (run once with the D2 flags
      removed to prove it bites) and green after (FR12, M2).
- [ ] 3.4 Real-git feature "origin cannot use ext": a global config
      `url."ext::…".insteadOf` rewriting the origin URL; assert git refuses
      with its protocol-not-allowed message and no process named in the
      rewrite runs (a marker file the rewritten command would create is
      absent). Verify: green (FR4, NFR-S2).

## 4. Harvest

- [ ] 4.1 `ContainerHarvestFetch.fetch` builds its argv from
      `GitTransfer.fetch(new Container(url(containerName)), refspec)` and
      runs it through the typed entry; `-c protocol.ext.allow=user` now comes
      from the owner. Update `ContainerHarvestFetchSpec`'s argv feature to
      the owner's exact list. Verify: spec green, no literal `"fetch"` left
      in the class (FR6).
- [ ] 4.2 Map `ValidationRefused` to the boundary-violation exception the
      rewrite refusal uses, with the same report shape naming what was
      refused. TDD with the fsck stderr fixture. Verify:
      `ContainerHarvestFetchSpec` feature green (FR5, UX3).
- [ ] 4.3 Identity spec feature "container": a `Container` source whose ext
      URL is `ext::git upload-pack <path>` standing in for `docker exec`
      (the argv is otherwise identical), a box repo with a tag inside the
      task branch's history and a tag elsewhere, the operator clone holding
      a same-name tag; assert the ref diff is exactly the task branch,
      `FETCH_HEAD` absent, and — with a `.gitmodules` symlink planted in the
      box branch — the fetch is refused as `ValidationRefused` and the branch
      ref is unchanged. Verify: red with the pre-change argv, green after
      (FR12, NFR-S1, M2).
- [ ] 4.4 Docker-gated: `ContainerGitMechanicsSpec` gains one feature
      asserting a tag created in a real box does not appear in the factory
      clone after harvest. Verify: green under the Docker-gated run (FR6).

## 5. Seed clone

- [ ] 5.1 `DockerSeedCloneCommand.seedClone` renders `GitTransfer.clone(new
      SeedPath(SEED_SOURCE, WORKING_COPY, branch))` into the script's clone
      line as `env K=V … env -u K … git <argv>`, keeping the `safe.directory`
      global file the script already writes and asserting in the rendering
      that the owner's `GIT_CONFIG_GLOBAL` entry names that same file.
      `DockerSeedCloneCommandSpec` pins the rendered line against the owner's
      value (the identity of design D6). Verify: spec green;
      `DockerCommandsSpec`'s `run --rm` assertion (fix-image-declared-volumes)
      unchanged (FR7, FR6).
- [ ] 5.2 Identity spec feature "seed": a factory clone with a lightweight and
      an annotated tag on the task branch; run the owner's clone argv with
      real git into a temp destination; assert the destination holds exactly
      `refs/heads/<branch>` and `refs/remotes/origin/<branch>`, no tag, and
      that a planted bad object on the branch is refused (proving the
      transport path, not local mode). Verify: red before (tags copied),
      green after (FR7, FR12, M2).
- [ ] 5.3 Docker-gated: the seed feature of
      `ContainerTaskExecutionEnvironmentContractSpec` asserts no tag in the
      box after materialize and that the helper image's `git --version` is at
      or above the floor. Verify: green under the Docker-gated run (FR7,
      FR10).

## 6. The gate and the floor

- [ ] 6.1 `GitTransferBoundarySpec` in `:bootstrap` `architecture`: enumerate
      every `src/main` root from `settings.gradle` includes, scan Java and
      script literals for `"fetch"`, `"clone"`, `"pull"`, `git fetch`, `git
      clone`, `git pull`, `submodule update`, `remote update`; allowlist
      exactly the `:gittransfer` owner files, `GitNetworkCommands`,
      `GitProcessRunner`; assert the scanned-root set equals the include set.
      Seed a violating temp file in the spec to prove the scan bites. Verify:
      `:bootstrap:check` green on the tree, red on the seeded file (FR9, M1).
- [ ] 6.2 `GitVersionFloor` in `bootstrap`: runs `git --version` through
      `GitProcessRunner` once per process, parses with `GitVersion`, logs one
      INFO line (version, floor) on pass, and on fail or unparsable output
      throws a startup exception whose message names floor, installed, and
      the reason; a new `[GFnnn]` code in the operator-event catalog for the
      refusal. Wire it in `FactoryApplication` before command dispatch so
      `run`, `take`, `serve` all pass through it. TDD with the recording git
      returning `git version 2.44.0`, `2.45.1`, `2.55.0 (Apple Git-200)`, and
      garbage. Verify: `GitVersionFloorSpec` green; `LogContractGateSpec`
      accepts the new code (FR10, NFR-R2, NFR-O1, UX2).
- [ ] 6.3 Startup-ordering spec on the shared app-layer assembly fixture: with
      the floor failing, no transfer, claim, or tracker write is attempted
      (the recording git sees only `--version`; the in-memory tracker sees
      nothing). Verify: green (FR10).

## 7. Documents

- [ ] 7.1 Write `docs/adr/0008-git-transfer-policy.md` (status accepted,
      introduced by this change): sources table, common set with one line per
      flag (moved from `NarrowFetch`'s javadoc), what each source may and may
      not change, validation and its classification, configuration isolation
      per source, the floor and its reason, the rule for adding a source, the
      alternatives (D1, D3, D4, D9 rejected forms). Mermaid flowchart: caller
      → owner → runner / seed script. Verify: file exists; every FR of the
      proposal is cited at least once (FR11).
- [ ] 7.2 Amend `docs/adr/0006-base-refresh-fetch.md`: "Flags on every
      refresh" becomes a pointer to ADR 0008; remove the sentence claiming
      `NarrowFetch` serves every factory fetch; add 0008 to "See also".
      Verify: `grep -n NarrowFetch docs/` returns nothing (FR11).
- [ ] 7.3 Three rows in `docs/sandbox-threat-registry.md` (object poisoning
      of the operator clone through harvest; ref planting into the operator
      clone from the box; operator ref and tag disclosure into the box), each
      naming its mitigation and this change; glossary entries "transfer" and
      "transfer source" under Sandbox; update the wording in
      `openspec/changes/signal-outage-gate-on-origin-contact/design.md` that
      derives contact from "`NarrowFetch` ran" to "the owner's fetch ran".
      Verify: rows and entries present; `grep -rn NarrowFetch openspec/changes
      --include=*.md | grep -v archive` returns only this change's own
      artifacts (FR11).

## 8. Verification and sweep

- [ ] 8.1 Full `./gradlew check` including PIT on `:gittransfer`,
      `:adapters:git`, `:sandbox:docker`, `:bootstrap`; the Docker-gated
      suites of 4.4 and 5.3 run once locally. Verify: green, mutation score
      100% (or documented exceptions per `testing.md`) (M1–M3).
- [ ] 8.2 Old-way sweep report: `grep -rn -E '"fetch"|"clone"|git (fetch|clone|pull)'
      --include=*.java --include=*.groovy */src/main adapters/*/src/main
      sandbox/*/src/main` — every hit is the owner, the runner's classifier,
      or listed with its reason; confirm the single-owner table of design.md
      row by row (consumers, old way removed, enforcement). Verify: the
      report is appended to this file's completion note; M4 checked by
      describing the fourth-source procedure in ADR 0008.

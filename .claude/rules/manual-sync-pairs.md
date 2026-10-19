# Rule: manually synchronized pairs

Applies whenever one logical rule, contract, or vocabulary is implemented in
two (or more) places that must be kept in step by hand — with no shared
abstraction the compiler could enforce. Typical shapes in this codebase:

- **Execution-medium twins**: a host-mode and a container-mode implementation
  of the same rule (e.g. salvage, round-boundary check, attempt persistence).
- **Wire vocabulary pairs**: a JSON writer and its reader in decoupled
  modules, each holding its own string table for the same enum.
- **Mirrored validation**: a validator that must accept exactly what a
  resolver elsewhere resolves.

Pairs are not banned — some are deliberate layer decoupling, others are
honest debt from a second medium arriving after the first. What is banned is
an **undeclared** pair: synchronization that exists only in the author's
memory is where divergence bugs are born.

## Preference order

1. **Shared abstraction** — one implementation, two strategies
   (`ResumeMechanics<B>` is the in-repo model). Required at the latest when a
   *third* implementation of the same rule appears (rule of three).
2. **Declared pair** — both ends carry the marker (below). Acceptable when
   the abstraction is premature or the duplication is deliberate decoupling.
3. **Undeclared pair** — not acceptable. An audit finding.

## Declaration marker

Every end of a pair carries, in its class-level javadoc, the exact phrase
`Kept in sync with` followed by a `{@link}` to the other end, plus one line
stating **what** must stay in sync (the invariant, not the file):

```java
/**
 * ...
 * <p>Kept in sync with {@link WorktreeSalvage}: both must produce a salvage
 * commit carrying the claim-epoch trailer and restore factory-owned paths.
 */
```

The phrase is the greppable contract: `grep -rn "Kept in sync with" src/main`
must enumerate every manual synchronization in the codebase. Both ends carry
the marker — a one-directional declaration is a finding.

## Audit obligations (enforced by `/audit-implementation`)

- A change touching one end of a declared pair must either change the other
  end or state in its artifacts why no mirrored change is needed.
- A change adding a second implementation of a rule that already exists in
  another mode/layer must either extract the shared abstraction or declare
  the pair at both ends in the same change.
- A change adding a *third* implementation must extract the abstraction.

## A new consumer of a declared pair

A declared pair is two implementations that were each right for the consumers they had.
A change that adds a **consumer** to a rule implemented as a pair chooses the end in its
`design.md` with the reason, and states whether both ends hold under the consumer's conditions
— the writers it may have at once, the processes it runs from, the medium it needs, the claim
it holds or does not hold. "Different media" is not a reason when the consumer's condition is
about something else. An end that fails the condition is the trigger for preference 1 — collapse
the pair — in the same change, not a reason to pick the other end for one medium.

The failure this exists for: `DeliveredCleanup` (`fix-terminal-receipt-convergence`) became a
consumer of `CleanupCommit` ↔ `GitObjectsTerminalCommits.cleanUp` with a condition neither end
had been asked — many writers from different processes, under no claim. It took the host end
for the host and the container end for the container. The host end serialised on the task
worktree's index lock, one step before the compare-and-swap that the "both media are CAS" risk
row relied on, and the loser failed instead of standing down (task 3.3, 2026-10-14). The
container end needed only a `.git`, which the host has. Design D8 of the same change collapsed
the pair: both media now write the cleanup commit through `GitObjectsTerminalCommits.cleanUp`,
`GitTaskRepository` opens its `GitObjects` over its own clone's `.git`, `CleanupCommit` is
deleted, and `DeliveredCleanupRaceSpec` races two host cleanups on real threads.

## Initial registry

Known pairs predating this rule. Remove a row once both ends carry the
marker, or once the pair is collapsed into an abstraction. Until removed,
audits treat these rows as if the markers were present.

The envelope file names (`GnomishTaskPaths` in `adapters/git` against
`BranchShapeClassifier` in `:domain`, with a third hand-typed spelling in
`:bootstrap`) never earned a row: `fix-envelope-medium` dissolved them into one
owner, `domain.branch.EnvelopePaths`, under a build gate, rather than declaring
a pair that already had three ends. The manual-checkpoint sentence
(`Manual checkpoint reached`), spelled by hand in five places across `run` and
`take`, took the same path: `make-run-headless` dissolved it into one owner,
`TerminalOutcomeRender` in `:application`, together with the return-path line
of a stopped `run`, rather than declaring a pair that already had five ends;
`TerminalRenderOwnerSpec` in `:bootstrap` keeps it single-owner by asserting
that each literal appears in exactly one production file.

The round-boundary check of the gnome's commits — a declared pair of the host
`RoundBoundaryCheck` and the container `HarvestedBoundaryCheck`, each holding its
own copy of the rule — left by preference 1: design D18 of
`tighten-spec-contract` collapsed it into one range inspection,
`GnomeRangeInspection`, with two callers (the harvest admission over
`register..quarantine`, the host round close over `previousTip..HEAD`), and
deleted both classes. What the two persistence media still keep in step by hand
is recorded in the `GitAttemptPersistence` row below.

The refusal of a cut capture — declared for one task as a pair of `CutCaptureException` (raised
by `CaptureRunner.await`) and `GitExec.await` — left by preference 1 when the third reader
arrived: task 8.11 of `fix-terminal-receipt-convergence` gave `CapturedExec` the same rule, and
the condition became one function, `CutCaptureException.refuses` (`EXITED`, exit code 0, a cut
reported by `Drain.joinAll`), with the note's words (`notice`) and its rendering (`withNotice`)
beside it in `:subprocess`. Each reader keeps only the exception type its own callers classify by
(`CutCaptureException`, `GitObjectsException`, an `UncheckedIOException`); `CutCaptureRefusalSpec`
pins the judgement over every termination.

The post-exit join of a child's output — an undeclared seven-way pair (`Drain`, `ExecPipeDrain`,
the future in `CapturedExec`, `GitExecStreams.readCapped` and `GitExecStreams.drain`,
`StreamDrain`, `BoundedTail`) — never earned a row: design D17 of
`fix-terminal-receipt-convergence` made `Drain` the one implementation joined with the one
post-exit bound and deleted `ExecPipeDrain`, and design D20 removed the write side's stdin pumps
rather than sharing them; `DrainOwnerGateSpec` in `:bootstrap` keeps the join single-owner.

| End A                                            | End B                                                            | Synchronized invariant                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
|--------------------------------------------------|------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `adapters/git/.../GitAttemptPersistence`         | `adapters/git/.../EnvironmentAttemptPersistence`                 | attempt commit + state-file write sequence, and three obligations of `tighten-spec-contract` (design D13, D18; task 9.13c): the gnome range is judged by the one `GnomeRangeInspection` before it becomes part of the round (the former boundary twins are deleted, preference 1); the round commit carries the factory's own owned paths (host: restored from the registered commit before `git add -A` by `EnvelopeRestore`; container: the in-box commit stages exactly the two files and the admission refuses any other owned path); and the round commit lands with its register in one ref transaction (`WorktreeClosingCommit`, or the admission's transaction through `FactoryRegister`), carried to origin in one atomic push (`BestEffortPush.closing`, `PushBestEffortAttemptPersistence`) — that sequence is now the whole invariant. Two halves have left it, each because a single owner replaced the hand-sync: **tip resolution**, since both media resolve through `VerifiedTip` (`harden-logging-observability` FR13), so a blank or failed resolution refuses on both by construction; and the **failure message**, since both now throw `GitPersistFailedException(…, UntrustedText)` and the exception composes its own message from the carrier, so neither medium can word or neutralize the captured git output differently (provenance: `type-untrusted-text`). The denial cursor and denial identity are deliberately environment-side only and are **not** part of the synchronized invariant: host mode has no egress guard, so no denial source exists to mirror (provenance: `fix-denial-attribution-durability`). |
| `adapters/agent/.../DecisionFileTransport`       | `adapters/git/.../BranchDecisionFile`                            | `GNOMISH_DECISION_FILE` env var name and the size cap; the tolerant read of the content. Each end owns its **own staleness rule** and they are deliberately different: the host end deletes its per-round temp file after the read, the branch end names the file by the round token and reads exactly that path (`make-checkpoint-gate-durable` D10) — a change to either rule states why the other end needs no mirror                                                                                                                                                                                                                                                                                                                                                                                                 |
| `adapters/agent/.../RoundTimeout`                | `adapters/.../pipeline/AgentSettingsValidator`                   | accepted `roundTimeout` shapes                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `serveobservability/json/LedgerJsonMapper`       | `dashboard/LedgerAggregator` + `dashboard/SweepActionAggregator` | ledger wire tokens (`TaskOutcome`, `SweepVerdictCategory`); `add-base-ref-resolution` task 7.4 added the `remoteOutage` line, read back by `LedgerAggregator` itself (folded in rather than a third aggregator class)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `serveobservability/json/SnapshotJsonMapper`     | `serveobservability/json/SnapshotJsonReader`                     | snapshot wire tokens (`FeedPhase`, `HeartbeatState`, `LifecycleState`)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `application/.../app/serve/FeedState`            | `serveobservability/FeedPhase`                                   | deliberate layer decoupling: constant sets must match                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `application/.../app/serve/HeartbeatWorkerState` | `serveobservability/HeartbeatState`                              | deliberate layer decoupling: constant sets must match                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |

## Declared pairs with no shared classpath

Both ends carry the marker, so the rule above would have them leave the
registry — but neither end can name the other with a resolvable `{@link}`,
because the two deliberately share no compile edge. The registry is their only
navigational index, so these rows stay listed for as long as the pair does.

| End A                                           | End B                                                | Synchronized invariant                                                                                                                                                                                                                                                                                                                                                     |
|-------------------------------------------------|------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `adapters/agent/.../HostRoundEnvironmentSource` | `adapters/git/.../SandboxRoundEnvironmentSource`     | round environment contract per mode: both open/close rounds with a decision-file handle, a round listener, and close-round semantics consistent with FR4/FR21; `adapters/agent` and `adapters/git` share no compile edge.                                                                                                                                                  |
| `build-logic/.../buildlogic/AdversarialGitConfig`    | `test-fixtures/.../adapter/git/AdversarialGitConfig` | the variable (`GIT_CONFIG_GLOBAL`) and the committed file path (`test-fixtures/src/main/resources/adversarial-gitconfig`) the test build hands git, and the ref-format variable and value (`GIT_DEFAULT_REF_FORMAT=reftable`, design D13 of `tighten-spec-contract` as amended 2026-10-13) (design D11 of `own-git-transfer-argv`); the build cannot load a test fixture, so both spell the constants. `AdversarialGitConfigSpec` in `:bootstrap` pins the identity: what git lists as global equals the resource, and the variable the test JVM sees equals the fixture's backend. |
| `adapters/git/src/test/.../ScriptedGit`         | `test-fixtures/src/main/resources/stand-in/stand-in.sh` | the argv-prefix match rule: the leading `-c <name>=<value>` pairs dropped, each prefix word a shell glob (`*`, `?`) against the argument at its position, first matching row wins, an unmatched invocation refused rather than defaulted (design D27 of `supervise-daemon-loops-and-embed-dashboard`). One is Groovy in a module's test source set, the other a POSIX script in a test resource; neither can name the other with a `{@link}`. |
| `adapters/.../tracker/inmemory/ClaimLeases`     | `adapters/github/.../tracker/github/GithubIndexRepair` | the index-repair marker's shape phrase (`observedShape`): `claim pending` when no boundary follows the newest claim, `index lagging behind the <boundary> marker` otherwise, so both adapters name the observed shape alike (scenario tracker/tracker-port "The repair marker names the observed shape"; finding F81 of `tighten-spec-contract`). `:adapters` and `:adapters:github` share no compile edge, and moving the phrase into `:gnomish-plugin-api` would grow the published surface for a rendering choice. `InMemoryRepairMarkerSpec` and `GithubRepairMarkerSpec` pin each end. |
| `build-logic/.../buildlogic/TestDockerEnvironment` | `scripts/linux-specs.sh` | the Docker client configuration a `docker` call of the build gets (testing.md, "The Docker client configuration is replaced"): a copy of the committed `test-fixtures/src/main/resources/docker-config/config.json` whose `cliPluginsExtraDirs` names `${DOCKER_CONFIG:-$HOME/.docker}/cli-plugins` when it exists, `DOCKER_HOST` from the operator or from `docker context inspect` of the current context (the `DockerContextEndpoint.FORMAT` query) read before the switch, an `ssh://` or TLS endpoint refused with the variables to set, and `DOCKER_CONTEXT` removed. The Linux spec runner (design D9 of `make-pipe-reads-non-parking`) is a host shell script; it cannot load a build-logic class. |
| `build-logic/.../buildlogic/TestTimeInjectionCheck`  | `bootstrap/.../architecture/TimeSourceOwnerBoundarySpec` | the real-time literal set (`Clock.systemUTC(`, `Clock.systemDefaultZone(`, `InstantSource.system(`, `Instant.now(`, `new SystemClock(`, `new ThreadSleeper(`, `.system(`), the test side and the production side of one rule (design D17, D20 of `supervise-daemon-loops-and-embed-dashboard`); the build cannot load a test class, so both spell the set. The spec's identity feature reads the check's `REAL_TIME` block from its source and fails when the two sets differ. |

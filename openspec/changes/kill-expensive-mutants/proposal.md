# Proposal: kill the expensive mutants of `:adapters:git` first

## Why

The `:adapters:git` mutation gate costs 25 of the 57 minutes a whole-tree `./gradlew check`
takes on the reference machine (measured 2026-10-03 from the daemon logs and `mutations.xml`): 879
mutants, 6 249 test executions. The cost is not spread evenly. Ten mutants — six in
`GitProcessRunner.execute` (the network branch: `isNetwork`, stall detection, the deadline, the
elapsed time), two in `StateUsageMapper`, one in `CloneMutationLock.runLocked` — each need 190 to
675 test runs before PIT reaches the spec that kills them, because PIT runs a mutant's covering
tests from fastest to slowest and the only killers are real-git specs that take seconds. Those ten
account for about 3 600 of the 6 249 executions, well over half the module's mutation phase.

Separately, one feature of `ContainerHarvestFetchSpec` takes 62.5 s to prove a 2 s deadline.
Reading the code shows why: the spec's stand-in `git` is `sh` + `sleep 60` for every subcommand,
and `GitProcessRunner.run` resolves the clone key for a mutating command through a *local*
`rev-parse` first — unbounded by requirement — so the stand-in sleeps its full minute before the
bounded fetch even starts. The deadline is sound; the fixture is not. It is also one of several stalling `git`
stand-ins that each spell the stall by hand: two private helpers in the module
(`GitProcessRunnerBoundedNetworkSpec`, this one), a declared pair of traits in `:test-fixtures`
(`StallingGitFixture`, `StallingReadGitFixture`), and five more scenario scripts in the module's
test tree that sleep on a subcommand of their own choosing. The stall mechanics — strip the leading
`-c` pairs, sleep on a chosen subcommand set, answer the rest — are past the rule-of-three point
for one owner.

With `scope-pit-locally` in place, this module's gate still runs in full whenever a task touches
its tests or sources — and `:adapters:git` is where most git-related tasks land. Making its gate
cheap without making it weaker is the second half of the speed-up.

## What Changes

- **ADDED** — fast, decision-targeted specs that kill the ten expensive mutants in milliseconds:
  a recording fake `git` (a shell script that prints its argv and environment) for the
  network-versus-local branch of `GitProcessRunner.execute`; a direct round-trip spec for
  `StateUsageMapper`; a two-thread hand-off spec for `CloneMutationLock` that proves the unlock
  without waiting out a timeout. The slow real-git specs stay — they prove other things; they
  simply stop being the first killer.
- **ADDED** — one stalling-`git` stand-in builder in `:test-fixtures` that owns the stall
  mechanics (which subcommands stall, for how long, what the others answer, how long a local
  command takes); the two private helpers and the pure-stall scenario scripts are rewired to it,
  the two existing traits build their scripts through it, and the scripts that remain their own
  are listed as exemptions with a reason. The 62.5 s feature drops to about the deadline.
- **ADDED** — a mutation-cost report after every PIT run: the mutants that needed the most test
  executions, written beside the PIT report and summarized in one log line, so the next hotspot is
  seen when it appears rather than reconstructed from daemon logs.
- No change to gate strength: threshold, mutators, `excludedClasses`, `excludedTestClasses` and
  `@DoNotMutate` are untouched; no production class is modified.

## Goals

- **G1** — `:adapters:git` PIT total falls from ~25 min to ≤ 15 min on the reference machine.
- **G2** — No mutant in `GitProcessRunner.execute`, `StateUsageMapper` or `CloneMutationLock`
  needs more than 20 test executions to die.
- **G3** — The slowest feature of `ContainerHarvestFetchSpec` takes ≤ 5 s.
- **G4** — A reader of any future PIT run can name its ten most expensive mutants from the build
  output without parsing XML.

## Non-Goals

- **NG1** — No change to PIT's threshold, mutator set, exemptions or `pitestVerifyAllKilled`.
- **NG2** — No production code change in `:adapters:git` or `:subprocess`; the 62.5 s is a fixture
  defect, and if the investigation task proves otherwise the production fix is its own change.
- **NG3** — No removal or weakening of the existing real-git specs; a spec that was the killer of
  record keeps its assertions.
- **NG4** — No gate on mutation cost. Test execution counts depend on PIT's recorded timings, which
  move with machine load; a gate on them would be flaky by construction. The report is a report.
- **NG5** — No work on other modules' expensive mutants beyond what the report makes visible.

## Users & Scenarios

- **U1 — the sub-agent working in `:adapters:git`.** Its scoped `check` mutates the whole module
  (a test change widens the scope, `scope-pit-locally` FR3); the module's gate now finishes in
  the time the coverage pass alone used to take.
- **U2 — the reviewer of a future PIT run.** Reads the cost line, sees a mutant at 400
  executions, and knows where the next fast spec is owed.
- **U3 — the author of the next stalling-git spec.** Uses the shared stand-in instead of writing a
  fourth one that forgets the local-command path.

## Requirements

### Functional

- **FR1** — For each mutant in the hotspot list a spec exists whose covering feature runs in
  under 500 ms, requires no network, no Docker and no real repository, and kills it. The list,
  named by mutation rather than by line so it survives the two active changes that rewrite
  `GitProcessRunner.execute` (`own-git-invocation-policy`, `add-subprocess-access-log`):
  `GitProcessRunner.execute` — the network-or-not choice of argv (NegateConditionals), the
  network-only SSH stall detection (NegateConditionals on the guard, VoidMethodCall on the
  call), the transfer-environment application (VoidMethodCall), the network-only deadline
  (NegateConditionals) and the elapsed-time subtraction (Math);
  `StateUsageMapper.toByTool` (EmptyObjectReturnVals) and `toTokensByModel` (VoidMethodCall,
  EmptyObjectReturnVals); `CloneMutationLock.runLocked` — the unlock (VoidMethodCall). As of
  the 2026-10-05 report these are lines 253, 269, 270, 274, 276, 279; 51; 73, 74; 58.
- **FR2** — One stand-in builder in `:test-fixtures` owns how a stalling `git` is spelled: the
  subcommands that stall (a set, or everything), the stall length, an optional marker touched when
  the stall begins, what the other subcommands answer (stdout, exit code; by default success with
  no output, so the clone key falls back to the working directory), and a local-command delay
  (zero by default; a spec that proves "local commands are not bounded" asks for one longer than
  its deadline). Every stand-in whose stall is a plain sleep on a subcommand set is built through
  it; a script that stays its own is an exemption named in the gate with its reason.
- **FR3** — After every `pitest` run the build writes `expensive-mutants.txt` beside
  `mutations.xml`: every mutant with `numberOfTestsRun` above a documented threshold, sorted
  descending, with class, method, line, mutator, test count and killing test; and logs one
  lifecycle line with the count above threshold and the single most expensive one.
- **FR4** — The report task is wired after `pitest` in every module through the shared PIT
  conventions, participates in `pitestAll`, and is skipped when no `mutations.xml` was produced.

### Non-Functional — Reliability

- **NFR-R1** — Every new spec is deterministic under PIT's minion load: no assertion compares
  elapsed time against a bound tighter than ten times the stand-in's sleep, and no new spec waits
  on a timeout longer than 100 ms to go green.
- **NFR-R2** — New specs that provoke a WARN register it with the log-expectation gate; none
  wires production real time (`checkTestTimeInjection` stays green).

### Non-Functional — Observability

- **NFR-O1** — The cost line names the module, the threshold, the count above it and the top
  mutant as `class.method:line (mutator) — N tests`.

### Non-Functional — Cost

- **NFR-C1** — The report task is one parse of `mutations.xml` and one file write: no subprocess,
  no network, no second read of the report, and no input that makes `pitest` re-run.

## Operator Experience Criteria

- **UX1** — The cost line reads as an invitation, not an error: it names what to look at and
  where the full list is, and never turns a green gate red.
- **UX2** — The shared stand-in's javadoc explains the local-command path in one sentence, so the
  next author understands why "sleep on everything" was wrong.

## Success Metrics

- **M1** — `:adapters:git:pitest` wall time ≤ 15 min on the reference machine (baseline 24.9 min,
  measured 2026-09-28 and 2026-10-01).
- **M2** — In the post-change `mutations.xml`, the maximum `numberOfTestsRun` over the hotspot
  list is ≤ 20 (baseline 675).
- **M3** — `ContainerHarvestFetchSpec` "FR7: a fetch cut off on its deadline" ≤ 5 s (baseline
  62.5 s); the module's `test` task sum of feature times drops by ≥ 60 s.
- **M5** — `grep -rn "sleep" adapters/git/src/test test-fixtures/src/main` lists only the owner,
  the exemptions named in the gate, and non-script sleeps (virtual sleepers, poll loops).
- **M4** — `expensive-mutants.txt` exists for every module whose gate ran, and the lifecycle
  line appears once per module.

## Open Questions

- **Q1** — Threshold for the report: 100 test executions is proposed (the current `:adapters:git`
  list has 13 above it, 3 between 40 and 100). Fixed at implementation; recorded in the
  convention's comment.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `quality-gates`: adds the mutation-cost report as a behavior of the mutation gate (the report
  file, its content and its log line). No existing requirement's text changes.

FR1, FR2, NFR-R1, NFR-R2 and UX2 describe the test tree and the test fixtures only; they have no
capability behavior to specify and are carried by tasks 1–3 and the architecture spec of task 1.5
(`traceability.md`: a test is an implementing entity).

## Impact

- `test-fixtures/src/main/groovy/.../adapter/git/` — the `StallingGit` builder; `StallingGitFixture`
  and `StallingReadGitFixture` build through it and drop their mutual "Kept in sync with" markers.
  A change under `test-fixtures/src/` widens the mutation scope to every module
  (`scope-pit-locally`, "Test changes widen the mutation scope"), so the measuring runs of this
  change are whole-tree runs.
- `adapters/git/src/test/groovy/.../adapter/git/` — new specs (`GitProcessRunnerNetworkBranchSpec`,
  `StateUsageMapperSpec`, a hand-off feature in `CloneMutationLockSpec`), a `RecordingGit` helper,
  six specs rewired to the builder, three exemptions documented in place.
- `bootstrap/src/test` — `StallingGitOwnerSpec`, the whole-tree scan over both test trees.
- `build-logic/src/main/groovy/pitest-gate-conventions.gradle` (or a sibling file under the size
  cap) — the report task; `build-logic/src/functionalTest` — a TestKit scenario over a canned
  `mutations.xml`.
- `docs/guides/developer-guide.md` — one paragraph on the cost report under per-module
  verification.
- No production Java; no port, adapter or dependency change.
- Sequenced after `scope-pit-locally` (archived 2026-10-05) and **before** `own-git-invocation-policy`
  and `add-subprocess-access-log`: both rewrite `GitProcessRunner.execute`, so the fast killers of
  this change are written against the method as it is today and re-verified by their scoped PIT
  runs when those changes land. `add-subprocess-access-log` (task 7.3) will also minimize the git
  child's environment; the pass-through assertion on `GIT_SSH_COMMAND` (design D1 (e)) then moves
  onto that change's retained set. Neither change modifies a requirement this one touches.

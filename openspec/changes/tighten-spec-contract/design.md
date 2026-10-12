# Design: tighten-spec-contract

## Context

See proposal.md — Why. The facts the decisions rest on, all verified on 2026-10-09 against
OpenSpec 1.14.1 and a scratch project (counts refreshed on 2026-10-11, after the archives of
`kill-expensive-mutants` and `supervise-daemon-loops-and-embed-dashboard`):

- OpenSpec measures a requirement as every non-blank, non-fenced line between
  `### Requirement:` and the first `####` heading; a physical line matching `**Key**:` is
  metadata and is left out whenever other text remains. A wrapped continuation of a metadata
  line is ordinary text. `openspec show --json` strips metadata lines from the requirement
  text; the plain `openspec show` prints the file. `MODIFIED` and `ADDED` blocks carrying
  `**Implements**:` and `**Notes**:` survive `openspec archive` verbatim.
- `openspec validate <change> --strict` checks the length of `ADDED` requirements only; the
  merged main spec is checked by `validate --specs --strict` at the archive stage.
- 537 requirements in 50 main specs, all traced by `<!-- implements … -->`; about 271 exceed
  the limit without the comments (266 on 2026-10-09, of which 138 carried `SHALL`/`MUST` past
  the 500th character, 111 explanation only, 17 `never`/`only`/`at most` wording). 1 457
  scenarios; twenty informal `// Scenario:` comments already sit in three `:bootstrap` specs
  (`TakeBareAutoSpec` 11, `TakeDispositionSpec` 6, `serve/TakeSlotRunnerSpec` 3), none in the
  `<spec-id> / <title>` form; tests otherwise cite `FR` ids.
- 26 unarchived changes; 17 carry 77 `MODIFIED` requirement headings; 24 carry a long `ADDED`
  requirement. The OpenSpec `MODIFIED` merge is replace-only (`delta-specs.md`).
- The project's build-side gates are Gradle tasks in `build-logic` with a functional spec each
  (`TestTimeInjectionCheck`, `TestEnvironmentHygiene`, `LogExpectationGateCheck`; the
  parameter-count gate is an Error Prone check in the `build-checks` included build, only its
  functional spec lives in `build-logic`); architecture
  specs in `:bootstrap` hold allowlists in code and assert them reached.

## Goals / Non-Goals

**Goals:** one owner for the traceability line; one owner for scenario identity between spec
and test; the migration auditable requirement by requirement through OpenSpec's own merge; the
checker green inside `check` from the moment the migration lands and never before.

**Non-Goals:** reproducing any OpenSpec rule (proposal NG2); any pin of the CLI (NG1); any edit
to archived deltas (NG3); a build gate on scenarios of unarchived deltas (NG6) — they are
findings, not gate subjects (D9).

## Decisions

**D1 — Traceability and notes are OpenSpec metadata lines.** The link is
`**Implements**: <ids> of <change>[; …]`, one line per requirement, after the text and before
the first scenario; explanation that belongs to the requirement and nowhere else is a
`**Notes**:` line, one physical line each, never `SHALL`/`MUST` (FR1, FR2). *Rationale:* a
metadata line is excluded from OpenSpec's measured text by the reader's own rule, survives the
merge in place, renders as text, and sits inside the requirement block so a `MODIFIED` of a
neighbour cannot delete it. *Alternative rejected:* a comment above the `### Requirement:`
heading — it lands in the previous requirement's last scenario, and a `MODIFIED` of that
neighbour erased it in the scratch test; a comment after the last scenario — survives today but
rides on scenarios accepting any text, and a scenario appended later buries it mid-block.

**D2 — Notes are the exception, and the gate says so.** A note may not carry a normative word;
a normative remainder of a shortened requirement is a scenario or an `ADDED` requirement (FR4).
*Rationale:* metadata is invisible to `openspec show --json`; text that binds behavior must stay
where every reader of the contract sees it, and a scenario is what gets a test. *Alternative
rejected:* notes as the general overflow — it clears the warning and hides the contract from
the tool, the "green build, wrong record" shape `implementation.md` exists to stop.

**D3 — The main specs change only through this change's deltas.** A generator writes, per
non-archived main spec, a delta with one `MODIFIED` block per requirement: heading verbatim,
comment turned into the metadata line, every other line verbatim; it proves the id set per
requirement equal before and after and refuses to overwrite a delta that differs from its own
last output (FR8, NFR-R2). The long requirements are then edited inside those deltas. *Rationale:*
the operator's decision — the OpenSpec merge is the audit trail, `/opsx:sync` in a scratch
worktree gives a strict-validated preview, and the change archives like any other. *Alternative
rejected:* editing `openspec/specs/` in place and archiving with `--skip-specs` — fewer files,
but the migration then has no delta to review and no merge to validate, and it sets a precedent
for bypassing the medium.

**D4 — Scenario identity is the title, spelled once in the spec and once in a test marker.**
`// Scenario: <spec-id> / <title>`, title verbatim, on the line above the feature (or inside it
when one feature covers several scenarios); the checker requires both directions (FR5).
*Rationale:* the title is the only identity a scenario has; a one-directional check lets a
rename leave a dangling marker that excuses nothing. The spec id prefix is needed because titles
repeat across capabilities (`Works`, `Empty queue`). *Alternative rejected:* naming the Spock
feature exactly as the scenario — Spock feature names are prose chosen for the test's reader,
and 1 457 renames would touch every spec file for no behavior; an annotation — Groovy specs
could carry one, but shell and functional-test fixtures could not.

**D5 — One checker class, three callers.** `SpecContractCheck` in `build-logic`, a cacheable
task whose inputs are `openspec/specs/**`, the unarchived deltas when `--changes` is set, and
the test trees and the exemption files; root `check` depends on it; `scripts/check-spec-contract.sh` is
`./gradlew -q checkSpecContract` with its arguments passed through; the fix-artifacts and
archive stages call the task in their verification (FR6). The exemption list is data, one file
per capability under `config/spec-contract-exemptions/` (header `spec: <spec-id>`, then
`<scenario title> => <mechanism>` lines), each entry a scenario and the mechanism that verifies
it, asserted reached; it is not in the root build file because several hundred `pending-test:`
entries would break that file's 200-line cap (task 5.2b). *Rationale:* the operator's decision —
one logic, two launchers. *Alternative rejected:* a standalone shell or
Python script the build execs — two runtimes to keep installed, and the project's checks are
Groovy with functional specs; a second copy in the stage YAML — the sync pair the rule forbids.

**D6 — The checker reads structure, not OpenSpec's rules.** It recognises headings, fenced
blocks, metadata lines and scenario headings — the shapes OpenSpec documents — and judges only
the project's rules on them (NFR-R3). It never counts characters, scenarios or `SHALL`s for
OpenSpec's sake. *Rationale:* proposal NG2 — a copy of an upstream rule drifts with the next
patch and nobody owns the pair. *Alternative rejected:* running `openspec validate` from the
task — a subprocess whose version the build does not control (NG1) inside a cacheable task.

**D7 — Order: checker first, red; wiring into `check` last.** The checker is built with its
functional spec before any spec is touched, so its first run over the real tree is the
inventory (FR9); it joins root `check` in the final group, after the deltas, the markers and the
queue are done. *Rationale:* a gate wired in while the tree is being migrated makes every
intermediate `check` red and hides the real failures. *Alternative rejected:* wiring it in from
the start with a growing allowlist — the allowlist would be the whole tree, and shrinking it is
the same work with a worse record.

**D8 — The queue is layered on this change, after this change.** Each of the 18 changes with a
`MODIFIED` requirement rewrites it over this change's delta text with the
`Layered on … as modified by tighten-spec-contract (sequenced before this change)` preamble;
each of the 22 changes with a long `ADDED` requirement shortens it by D2 (FR10). *Rationale:*
`delta-specs.md` already prescribes this for overlapping `MODIFIED`s, and this change overlaps
every requirement there is. *Alternative rejected:* leaving the queue to the fix-artifacts stage
— it would fix the `ADDED` length but not the layering, and the first archive after this one
would silently reinstate 62 long requirements.

**D9 — Findings are listed and sorted at decision points, never fixed in passing or dropped.**
One findings file under the change's working documents (`findings.md`, one row per finding:
id, capability, scenario or requirement, kind, evidence, proposed disposition) collects
everything the letter of the change does not cover (FR11): a defect a new test reveals, a
queued delta's scenario that describes behavior the code already has with no test, a scenario
with no verifier in any test tree, a normative remainder with no home. Two decision points
sort every open row — 5.2 after the scenario inventory, 6.8 after the queue rework — into
"this change" or "new change `<name>`"; the sorted rows are copied into `tasks.md` under a
"Decisions" heading, and the file must have no open row at archive. What goes to a new change
is carried meanwhile without hiding the gap: a scenario by a `pending-test: <change>` exemption
entry, a revealed defect by its test kept in place under Spock's `@PendingFeature` with the
finding id in its reason (the marker stays, the gate stays green, the red is recorded, not
deleted). What stays is done here by the ordinary rules — a production fix by TDD with the
scoped PIT run of `verification-scope.md`. *Rationale:* tests cite `FR` ids today, so neither
the missing count nor the defects are knowable before the inventory; `process-invariants.md`
bounds a change at four weeks; a finding fixed on the way is an edit nobody reviews, and one
dropped is the silent gap this change exists to close. *Alternative rejected:* a flat non-goal
("no production code, no queue scenarios") — it decides before the evidence exists and loses
the list; writing tests and fixes until time runs out — unbounded, with no record of what is
left. *Revised 2026-10-12 (operator):* a scenario whose only gap is its missing test is not
moved out — group 8 writes it here; the six area changes decided at 5.2 are withdrawn.

**D10 — Regroup by `git mv`, after the generator is gone and before the gate (FR12).** One
mapping table old → new is the single source; one script applies it to the main specs, the
deltas of every unarchived change (this change's 50 included, with their `# Spec Delta:` lines),
the markers (anchored on `Scenario: <old> /`) and the exemption files. It runs after 7.2 removed
`generate-spec-deltas.sh` and its sidecars, so no hash is recomputed and the generator's
`verify` need not survive the move, and before 7.4, so the one whole-tree `check` proves it. D3
governs content, not path: a rename is byte-identical and is not an edit of `openspec/specs/`
by hand. *Alternative rejected:* a separate change after archive — `c926b70b` deferred 18
specs "as their active changes archive" and the queue has since grown to 26, so the deferral
never ends; and OpenSpec has no operation that moves a capability, so a later change would
still be a `git mv`. *Alternative rejected:* renaming leaves to drop repeated prefixes
(`sandbox/sandbox-egress` → `sandbox/egress`) — the marker edit stops being mechanical and
every id in prose, findings and follow-up proposals stops matching.

**D11 — The follow-ups of 8.14 are fixed here (operator, 2026-10-12, second revision).** The
sixteen findings 8.14 sorted to new changes — F74, F79, F82, F85, F90, F93, F100, F106, F107,
F109, F111, F113, F115, F119, F120, F122 — are fixed in this change, group 9 (FR13–FR16). Each
takes the disposition its `findings.md` row proposed first: the code is made true to the
scenario, never the scenario restated to the code. F97 stays with `add-claim-return`, which
replaces the release it names. *Rationale:* every one of them already has its failing test and
its evidence in this change; twelve small changes would each re-read the same context, and the
`@PendingFeature` markers would stand in the tree for as long as the queue takes. *Alternative
rejected:* the 8.14 sort (twelve follow-up changes proposed after archive) — the operator's
decision; it keeps this change smaller but leaves known defects, two of them security
(F107, F122), behind a green gate.

**D12 — Assembly escape hatches close at the type (F74, F82, F119; `implementation.md` item 3).**
*F74:* `:adapters:git` gets one public assembly, `GitTaskBackend.taskGit(runner, retry, clock,
book, …)`, that builds `GitTaskStore`, `GitTaskBranches` and `GitTaskWorktrees` over the one
`ClaimEpochBook` it puts into the `TaskGit` it returns; the three writers' constructors become
package-private, so an assembly whose writers stamp from one book while the tracker fills another
no longer compiles (`SplitTenureRecordSpec` rewritten against the new surface: the split shape
calls the writer constructors and fails to compile; the control calls the assembly).
*F82:* the installation's box equipment (`DockerCli`, `SandboxProperties`, `BoxTiming`, the
guard config root, `OwnershipMode`) becomes one value, `BoxEquipment`, built once by
`ContainerEnvironmentFactory`; `ContainerEnvironmentBuilder`, `ContainerTaskExecutionEnvironment`
and `ContainerEnvironments` take that value plus the task's own inputs (the key, `BoxGitLink`,
`ChildEnvAllowlist`, the project identity, the denial restoration read), never both halves as
loose parameters. *Amended at task 9.9:* the first list put `BoxGitLink`, `ChildEnvAllowlist`
and `ObjectOwnership` in the installation half; they are a task's inputs (the
`sandbox/execution-environment` requirement "Container environment factory" and
`ContainerEquipmentSplitSpec` type them so, and `forTask` receives them), and `ObjectOwnership`
is the installation's mode beside the task's project identity, so `BoxEquipment.ownership(projectId)`
composes it. A record holding both halves could not be built once, and would only move the mixed
list into its own constructor. *F119:* the use cases take a
`WorkspaceFactory` port (per-worktree `Workspace`) and a `ConsoleIO`, both supplied by
`:bootstrap`; `:application` no longer constructs `DirectoryWorkspace` or `SystemConsoleIO`.
*Rationale:* each finding is a type that accepts what the owner was introduced to replace; only
a signature change makes the wrong assembly unconstructible. *Alternative rejected:* restating
the THENs to name the boundary specs that catch the split today — the operator chose the fix
(D11), and a whole-tree scan is a weaker guard than the compiler.

**D13 — Delivery is found by walking the factory's own round records (F122, NFR-S2).** The
classifier no longer asks "is there a commit with the cleanup subject after STARTED". It walks the
first-parent line down from the tip: the tip-most factory commit is the starting point; every
attempt commit names, in its subject, the round token — the tip the round opened on — so the
commits between that token and the attempt commit are the gnome's and are skipped whole, and the
walk continues at the token. A cleanup commit counts only where the walk lands on it; one inside
a gnome range never does, whatever its message. *Rationale:* the round token is already the
identity crash-consistency item 13 uses for liveness; the gnome writes only inside a round, and
the range a round covers is fixed by commits the factory wrote after the round closed. No key, no
second medium. *Alternatives rejected:* signing the factory's commits — in host mode the gnome
runs as the same OS user and can read the key; reading delivery from the tracker — the branch
classifier would read a second medium, against crash-consistency item 11.

**D14 — The egress guard is probed at the close of every round (F85).** The close of a round in
container mode, and an in-box retry before its next round, call the guard's liveness probe (the
check `EgressGuard.ensureRunning` performs, without the restart); a guard found dead classifies
the round as an infrastructure failure — no attempt burned, the round's grading skipped — and the
next materialize restarts it as today. *Rationale:* the denial read is fail-open by design (its
own scenario), so it cannot be the detector; a probe at the boundary is. *Alternative rejected:*
restating the THEN to "restarted at the next materialize" — leaves a broken round graded as a
quality failure.

**D15 — Dashboard browser tests run Selenium in a Testcontainers browser (F93, FR14).** The six
scenarios load the rendered dashboard file in a headless Chrome from the Testcontainers Selenium
module (`BrowserWebDriverContainer`), in `:bootstrap`'s Docker-dependent end-to-end layer
(`docker-test-conventions`), on the page the production renderer wrote; time is driven by the page
clock the script reads, set from the spec. *Rationale:* the behaviors are the script's and the
stylesheet's (tick, banner, scroll restore, layout at phone width, themes) — only a layout engine
observes them; Testcontainers is the project's tool for the end-to-end layer. *Alternatives
rejected:* Playwright for Java — downloads browsers into the user cache on first use, so the build
is no longer hermetic; HtmlUnit — no layout, so width and theme legibility cannot be asserted.

**D16 — A one-time scenario leaves by REMOVED, its standing neighbours by ADDED under a new
heading (F115, F120, FR16).** OpenSpec refuses a `MODIFIED` block that drops a scenario. So
`Full clean-build time not regressed` and `Behavior-preserving split` (every scenario one-time)
become `REMOVED` blocks; `Source file size cap`, `Shared app-layer assembly fixture`, `Use-case
dependencies inverted onto ports`, `Single-purpose gate convention` and `Sandbox port contract
preserved; AdapterBinding opened by change C` become a `REMOVED` block plus an `ADDED` requirement
under a new heading carrying the requirement's text and its standing scenarios verbatim (their
markers unchanged, since the scenario titles are). No queued delta modifies any of the seven
headings (checked 2026-10-12). *Rationale:* the one route OpenSpec allows inside one change.
*Alternatives rejected:* rewording each THEN into a standing property under the same title —
titles like `Existing sandbox specs pass unchanged` would then misname their scenario; the
two-change route of F1 — two more changes for what one delta can carry.

**D17 — The remaining fixes take the finding's first disposition.** F79: a removal re-driven over
a voided claim scopes its marker by the epoch the voided claim comment still carries and deletes
that comment, so a re-drive lands on its own marker. F90: the sweep vital carries the source of
the last skipped verdict and the stall alarm names it. F100: `docs/adr/0016-json-contract-versioning.md`
states the policy and the scenario's exemption names it. F106: a higher source's list shadows
the lower source's indexed keys whole (`OperatorConfigLoader`). F107: the agent credential seam
and the child environment's credential layer read through `SecretsProvider` (NFR-S3). F109: the
text report renders a passing check's run URL. F111: the cause of the inert `UnusedMethod` /
`UnusedVariable` checks is found and recorded, the checks fire again, and the dead code they
then report is deleted. F113: the jackson override reaches the `build-logic` and root buildscript
classpaths and both lockfiles are regenerated. *Rationale:* D11. *Alternative rejected:* the second
disposition each row offered (restate the THEN) — the operator's decision.

**Sync surfaces.** `scripts/check-spec-contract.sh` and the two stage YAML verification commands
are launchers only (one line each invoking the task); no rule lives in them. The twenty informal
`// Scenario:` comments in `TakeBareAutoSpec`, `TakeDispositionSpec` and `TakeSlotRunnerSpec` are
converted to the marker form in task 5.3, and the checker reports any `// Scenario:` line that is
not in the `<spec-id> / <title>` form as a malformed marker, so no second spelling of the link
remains or returns. The three documents that describe the format
(`traceability.md`, `delta-specs.md`, `rules.specs` in `openspec/config.yaml`) are prose, each
with one example, and the checker is what keeps them honest. No code of this change implements
a rule that exists elsewhere. Two declared pairs of `manual-sync-pairs.md` are touched by findings
fixed here, both ends in the same task: `ClaimLeases` / `GithubIndexRepair` (F81, 8.15 — the
in-memory end now names the shape the GitHub end does) and `SnapshotJsonMapper` /
`SnapshotJsonReader` (F90 — the new sweep-vital field is written and read in task 9.4, and the
snapshot reference regenerated). `ChildEnvAllowlist` keeps its single `compose` formula; F107
changes where its credential values come from, not the composition.

**Single-owner mechanisms.**

| Owner                           | Value (type)                                             | Consumers                                                                                                                                                                                                  | Old way removed                                                                                                                                                                                                                             | Enforced by                                                                                                                  |
|---------------------------------|----------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| The `**Implements**:` line (D1) | the requirement's traceability ids, as one metadata line | every requirement under `openspec/specs/`; every requirement in `openspec/changes/<unarchived>/specs/`; `traceability.md`, `delta-specs.md`, `rules.specs` (the three places that tell an author the form) | `<!-- implements … -->` in Requirement/Scenario specs, deleted outside the archive by the generated deltas (D3) and the queue rework (D8); the YAML-like `# implements` form is not replaced — it lives in blocks OpenSpec does not measure | `SpecContractCheck`: a comment-form hit in the scanned trees is a failure; a requirement with zero or two lines is a failure |
| The scenario title (D4)         | the identity of a scenario, as `<spec-id> / <title>`     | the spec's `#### Scenario:` heading; the `// Scenario:` marker in the test that exercises it; the exemption list entry for a scenario verified elsewhere                                                   | the informal `// Scenario:` comments in `TakeBareAutoSpec`, `TakeDispositionSpec` and `TakeSlotRunnerSpec` (converted; a malformed `// Scenario:` line is a finding); the implicit "the FR id covers it" link (kept for FR traceability, no longer the only link)                                                                         | `SpecContractCheck` in both directions, exemption list asserted reached                                                      |
| `SpecContractCheck` (D5)        | the verdict on the project's spec-contract rules         | root `check`; `scripts/check-spec-contract.sh`; the fix-artifacts and archive stage verification commands                                                                                                  | none existed; the launchers are forbidden to hold a rule by the functional spec, which runs the script and the stage command against the same fixture tree and expects the same findings                                                    | `SpecContractCheckFunctionalSpec`, `SpecContractScenarioLinkFunctionalSpec`, `SpecContractLauncherFunctionalSpec`, `StageSpecContractCommandSpec`, `SpecContractRuleExampleFunctionalSpec` (the `delta-specs.md` example passes the gate) (split for file size) |
| `GitTaskBackend.taskGit` (D12, F74) | the task-git bundle over one tenure record (`TaskGit` holding one `ClaimEpochBook`) | `bootstrap/.../ManualRunConfiguration.java` (four constructions today); `test-fixtures/.../TaskGitFixture.groovy`; the test constructions listed by `grep -rn "new GitTaskStore(\|new GitTaskBranches(\|new GitTaskWorktrees("` in `:application` and `:bootstrap` test trees, each moved to the assembly or to `TaskGitFixture` (task 9.8: `GitModeMidRoundPushSpec`, `killpoint/ReclaimKillPoints`, `killpoint/CreationWorld`, `killpoint/KillPointWorld`, `take/RevocationHandlerSpec`) | the public constructors `GitTaskStore(…, ClaimEpochSource, …)`, `GitTaskBranches(…, ClaimEpochSource, …)`, `GitTaskWorktrees(…, ClaimEpochSource)` become package-private; `:adapters:git`'s own specs keep using them from the package | the compiler (`SplitTenureRecordSpec`, both shapes compiled); for Groovy sources, which the compiler does not hold to package-private access, `SplitTenureRecordSpec`'s whole-tree scan for hand-built writers outside `adapters/git`; `ClaimlessGitBoundarySpec`; `LayerReachSpec` names `GitTaskBackend` as the one adapter class that may name `ClaimEpochBook` and asserts it holds no field (task 9.8) |
| `BoxEquipment` (D12, F82)       | the installation's box equipment, one value               | `sandbox/docker/.../ContainerEnvironmentFactory.java` (holds it); `ContainerEnvironmentBuilder`, `ContainerTaskExecutionEnvironment`, `ContainerEnvironments` (take it); test constructions through `test-fixtures/.../BoxEquipmentFixture.groovy` or `new BoxEquipment(` (`ContainerEnvironmentsFixture`, `ContainerEnvironmentsSeamSpec`, `ScriptedSandboxDocker`, task 9.9) | the three mixed constructors' loose equipment parameters; the per-task `new DockerCli(` in `forTask`, the `new ObjectOwnership(` in `forTask` and the `new TaskContainerSettings(` and `guardConfigRoot.resolve(` in `ContainerEnvironmentBuilder.build` (now `BoxEquipment.docker`, `ownership`, `containerSettings`, `guardConfig`) | `ContainerEquipmentSplitSpec` (reflection over the chain's constructors and static factories); the compiler (no constructor of the three accepts the loose members) |
| `WorkspaceFactory` / `ConsoleIO` from the root (D12, F119) | the per-worktree `Workspace`; the console | `application/.../TakeEngineExecution.java`, `PipelineStartup.java`, `GitModeRunner.java`, `GitResumeContinuation.java` (workspace); `ConsoleTakeoverConfirmation.java` (console) | `new DirectoryWorkspace(` and `new SystemConsoleIO(` in `application/src/main`; the two adapters move to `:bootstrap` or stay constructed only there (`FactoryApplication`, `OperatorConfigLoader`, `ManualRunConfiguration` construct the console today) | `UseCasePortSpec` |
| The round-record walk (D13, F122) | delivery of a task branch (`boolean`, the classifier's fact) | `adapters/git/.../GitShowTip.java` (`cleanupCommit`), `DeliveredBranchReader.java`, `RefTipSource.java`, `BranchTipFactsReader.java` → `BranchShapeClassifier` | the subject search for `gnomish: cleanup` over the first-parent line after STARTED, deleted | `DeliveryAncestrySpec` (the gnome-subject feature green, the existing delivery features unchanged) |
| `SecretsProvider` (D17, F107)   | a credential value (`Optional<String>` through the port)  | `adapters/agent/.../ExecutorRoundExecution.java`, `JudgeRoundExecution.java` (via `AgentAiSeam`); `bootstrap/.../RunAssembler.java`, `ContainerRunSupportFactory.java` (via `ChildEnvAllowlist.of`) | `System.getenv()` in `AgentAiSeam.fromFactoryEnvironment`; `System::getenv` handed to `ChildEnvAllowlist.of`                                                                                                                         | `SecretsPortBoundarySpec`, `AgentCredentialSeamBoundarySpec` |

## Risks / Trade-offs

- [OpenSpec changes how it reads metadata lines, and `**Notes**:` counts again] → the archive
  stage goes red on the first task after the upgrade, exactly as on 2026-10-08, and the fix is
  a change against the new reader; the project chose this over a pin (NG1). The checker's
  functional spec pins the shapes this design relies on, so the difference is named, not guessed.
- [A note is a long physical line; its diff is one replaced line] → notes are the exception
  (D2); a note longer than a sentence or two is a sign the text wanted a scenario.
- [The generated deltas copy the whole corpus into the change] → it is the medium the operator
  chose (D3); the generator's id-set proof and `/opsx:sync` in a scratch worktree make the copy
  reviewable by `diff`, not by reading.
- [Missing tests or revealed defects outnumber what one change can carry] → D9: the decision
  points at 5.2 and 6.8; the `pending-test` exemption and the `@PendingFeature` test carry what
  moves out, so the record is complete and the gate is honest; for missing tests the operator
  chose to carry all 302 here, cut per capability (NFR-C2) into group 8.
- [A finding gets fixed on the way because it looked small] → every production edit cites a
  finding id sorted into this change; `/audit-implementation` reads the Decisions heading
  against the diff.
- [A scenario's "test" is a functional spec, an architecture spec or a shell fixture] → all
  live under a scanned tree (`src/test`, `build-logic/src/functionalTest`); the marker is a
  comment and works in every language there.
- [The queue rework touches 26 changes other work may be editing] → sequenced first, and each
  change's rework is one task validated by `openspec validate <change> --strict`.
- [Group 9 grows the change past the four weeks `process-invariants.md` allows] → the operator's
  decision (D11); each finding is one task with its own TDD cycle and scoped PIT, so the group can
  stop after any task with the rest still carried by their `@PendingFeature` markers.
- [Restored unused-code checks report dead code across the whole build (F111)] → the task deletes
  what they report; a finding that is not dead (reflection, a framework entry point) is suppressed
  on its declaration with the reason, and the count is in the task report.
- [Group 9 moves code queued changes plan to touch] → `add-claim-return` (F79,
  `GithubStaleClaimRemoval`), `extract-agent-round-runner`, `fix-docker-exec-env-argv` and
  `define-executor-contract` (F107, `AgentAiSeam`, `ChildEnvAllowlist`), `add-command-executor`
  and `fix-docker-exec-env-argv` (F82, the container chain) each get a plain bullet in their
  `tasks.md` (task 9.16), the precedent of F59 and F66–F69.

## Migration Plan

1. Checker and functional spec (group 1), unwired. 2. Rules and guides (group 2). 3. Generated
deltas, id set proven (group 3). 4. Editorial pass per capability (group 4). 5. Inventory,
decision point, markers, tests (group 5). 6. Queue rework (group 6). 7. Scratch-worktree sync,
strict validation, wiring into `check`, regroup of `openspec/specs/` by D10 (7.3a–7.3d), full
`check` once (group 7). 8. Tests for the scenarios carried by `pending-test` (group 8),
decision point, whole-tree `check` once more. 9. The 8.14 follow-ups fixed here (group 9, D11–D17),
then the whole-tree `check` once more. Rollback before archive is
deleting the change; after archive, the comment form is gone and a rollback is a new change.

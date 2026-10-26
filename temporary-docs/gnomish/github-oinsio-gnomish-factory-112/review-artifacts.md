# Review of add-doctor-command — 2026-10-26

## Artifacts Review: add-doctor-command

Reviewed against the working copy at `34153505` (branch `gnomish/github-oinsio-gnomish-factory-112`);
`origin/main` is not ahead of it (`git log HEAD..origin/main` is empty), so every finding is
against both. `openspec validate add-doctor-command --strict`: valid. The change's artifacts were
last revised in `c6a4df18` (2026-10-21), the commit that also archived `own-git-invocation-policy`.

### Summary

| Dimension            | Result                                                                 |
|----------------------|------------------------------------------------------------------------|
| Freshness            | 5 stale claims (1 delta restates a superseded requirement text)        |
| Completeness         | 17/18 requirement IDs fully covered (NFR-P1 has no task)               |
| Internal consistency | 0 contradictions                                                       |
| External consistency | 3 conflicts (1 spec, 2 code: a sync surface and a side-effecting port) |

### Landed since

Changes archived after `c6a4df18` (2026-10-21), plus the one archived in that same commit, whose
delta the change did not absorb:

- **`own-git-invocation-policy`** (archived in `c6a4df18`, the change's own last-revision commit)
  — overlaps the MODIFIED requirement `Git version floor at startup`: its delta raised the floor
  to 2.56.0, replaced the `**Notes**:` line and added `FR10, UX3 of own-git-invocation-policy` to
  the `**Implements**:` line (`openspec/specs/git/git-transfer/spec.md:189-194`;
  `gittransfer/.../GitVersion.java:47`). The doctor's delta still restates the
  `tighten-spec-contract` text (2.51.0). Its UX2/Q3 (the `core.hooksPath` leftover) is taken up by
  FR8/D6 of this change — that part is fresh.
- **`narrow-branch-mutation-scope`** (`693ff432`, 2026-10-22) — overlaps `tasks.md:4` ("Root
  `check` green after every section"): `.claude/rules/verification-scope.md` now runs the root
  `check` once at the end and scopes PIT per task.
- **`add-sandbox-base-image`** (`26df44da`, 2026-10-26; folded-in serve-readiness decisions
  D17–D22, ADR 0019) — overlaps the image-contract check and the whole "assess, then refuse"
  idea: `ImageContractCheck.require(ImageFacts)` (`sandbox/docker/.../ImageContractCheck.java:65`)
  is package-private and has two consumers, `ContainerMaterializer` (per box) and
  `DockerImageReadiness.assess` (`sandbox/docker/.../DockerImageReadiness.java:49-52`), the Docker
  adapter of the new port `SandboxImageReadiness.assess(String image)`
  (`application/.../port/run/SandboxImageReadiness.java:30`); a startup owner
  `ServePreconditions.assess(clone, definition)` returning `StartupAssessment`
  (`application/.../ServePreconditions.java:77-91`) runs every static operator precondition
  before the first claim, through ports only; `OperatorPreconditionRefusals.kindOf`
  (`application/.../OperatorPreconditionRefusals.java:34-42`) is the one classifier of the four
  precondition refusals (`CLONE_REF_FORMAT`, `CONTAINER_RUNTIME`, `BOX_GIT_FLOOR`,
  `IMAGE_CONTRACT`). Its NG10 hands the non-refusing form to this change *built on that startup
  owner*, and ADR 0019 (`docs/adr/0019-operator-preconditions.md:45`) records the same. It also
  made the box's git floor equal to the factory's (NG5 of `own-git-invocation-policy`).
- **`fix-docker-exec-env-argv`** (`0d5e8b9b`, 2026-10-26) — `docker exec` environment passing;
  touches none of the files, keys or requirements this change names. No overlap.

Archived before the last revision and relied on: `fix-terminal-receipt-convergence` (D19 host
launcher), `tighten-spec-contract`, `add-project-registry`, `add-release-pipeline` — all archived,
all as the proposal states (`change.md` of this task confirms the six prerequisites).

### Freshness

1. ❌ `specs/git/git-transfer/spec.md:3-15` — the MODIFIED block is "Layered on … as modified by
   `tighten-spec-contract`" and restates the floor as **2.51.0**, with that change's
   `**Implements**:` and `**Notes**:` lines. Reality: the main spec
   (`openspec/specs/git/git-transfer/spec.md:189-194`) reads **2.56.0**, its `**Implements**:`
   ends in `FR10, UX3 of own-git-invocation-policy`, and its note explains the 2.56.0 reason (the
   hook switch `hook.<event>.enabled`); the code agrees (`GitVersion.java:47`:
   `FLOOR = new GitVersion(2, 56, 0)`). The OpenSpec MODIFIED merge is replace-only
   (`delta-specs.md`), so syncing this delta as written would rewrite the main spec's floor back
   to 2.51.0 and drop the traceability of `own-git-invocation-policy`. Edit: restate the current
   main-spec text verbatim, keep its `**Implements**:` plus `FR7 of add-doctor-command`, keep its
   note and add the doctor note after it, and re-word the preamble as "Layered on … as modified by
   `own-git-invocation-policy` (sequenced before this change)". → R1.
2. ⚠️ `design.md:21-22`, D1 (`design.md:36-39`), single-owner row 3 (`design.md:119`),
   `tasks.md:16-19`, `proposal.md:167-168` — "`ImageFacts` / `ImageContractCheck` … throwing on
   mismatch", "`ImageContractCheck.assess(ImageFacts)`; the environment builder refuses on
   `fail`". Reality: the method is `require(ImageFacts)` on a package-private class
   (`ImageContractCheck.java:36,65`), held by `BoxEquipment`/`ContainerMaterializer` per box and
   by `DockerImageReadiness` for the startup assessment (`DockerImageReadiness.java:42-52`); the
   component the startup path uses is the port `SandboxImageReadiness` behind
   `ServePreconditions` (`ServePreconditions.java:117-126`), and `ServePreconditions` is the owner
   NG10 of `add-sandbox-base-image` and ADR 0019 hand this change. The port's only method,
   `assess(String image)`, inspects the image *and* runs `BoxGitFloor.require(image)`, which
   starts one helper container (`docker run --rm --network none <image> git --version`,
   `BoxGitFloor.java:24`) — a box start NFR-R1/NG1 forbid and NFR-P1 cannot afford. The design
   decides nothing about this: `:bootstrap` cannot reach `ImageContractCheck` at all, and the
   reachable port has a side effect. → R3.
3. ⚠️ `proposal.md:90-102` (FR1), `proposal.md:147` (M1), `specs/cli/operator-doctor/spec.md:19-30`,
   `tasks.md:54-63, 78-81` — the check list is the one written before `add-sandbox-base-image`
   landed. Reality: the startup path now also refuses on the **factory clone's ref backend**
   (`TaskBranchGit.requireRefFormat`, `DaemonConditionKind.CLONE_REF_FORMAT`,
   `ServePreconditions.java:79,119`) and on the **box image's git floor**
   (`DaemonConditionKind.BOX_GIT_FLOOR`, `DockerImageReadiness.java:52`), and
   `OperatorPreconditionRefusals` names exactly four precondition kinds. Neither appears in FR1,
   in the scenario "Every readiness check is reported", in M1's thirteen rows or in task 3.2/4.2,
   so G1 ("everything that would stop the factory") and G2 ("never disagrees with the real
   startup path") would be false on the day `doctor` ships: a `files`-backend clone passes
   `doctor` and refuses `serve` at startup. → R4.
4. ❌ `design.md:24-25`, D4 (`design.md:70-72, 80-81`), `tasks.md:23-26`, single-owner row 5 —
   "Nothing today locates the agent CLI binary before a round fails to start it";
   `AgentCliBinary.locate(binary, path)` in `:adapters:agent` resolves the binary "against the
   host `PATH`". Reality: the host launch owner already resolves every command's first word
   before any process exists — `GroupedLaunch.start`
   (`subprocess/.../GroupedLaunch.java:107-110`) calls
   `ExecutableLookup.resolve(command.getFirst(), builder.directory(), childPath == null ? jvmPath : childPath)`,
   and `ExecutableLookup` (`subprocess/.../ExecutableLookup.java`) is the one `execvp`-shaped
   rule: absolute `PATH` entries only, regular executable file, the `#!` interpreter chain
   followed up to four hops (design D19 of `fix-terminal-receipt-convergence`, as amended
   2026-10-17). A second resolver in `:adapters:agent` is the same rule implemented twice, which
   `Sync surfaces: none` (`design.md:109-111`) denies. → R2.
5. ⚠️ `design.md:18-20` — "`BoundTracker.credentialEnvVars()` and `CheckEquipment.credentialNames(…)`
   declare the secrets"; "`DockerRuntimeProbe.dockerAvailable()` probes the daemon". Reality:
   `CheckEquipment.credentialNames` is `private` (`bootstrap/.../CheckEquipment.java:133`) — the
   public declaration seam is `CheckProviderSeam.credentialEnvVars(subsections, registry)`
   (`bootstrap/.../CheckProviderSeam.java:157`) beside the package-private
   `BoundTracker.credentialEnvVars()` (`application/.../BoundTracker.java:44`); and the startup
   path reaches the Docker probe through the port `ContainerRuntimeProbe`
   (`SandboxBindingConfiguration.java:72-73`: `DockerRuntimeProbe::dockerAvailable`), read by
   `SandboxModeSelector.runsInBox` (`SandboxModeSelector.java:132`), never the static directly.
   Naming only. → R5.

Fresh (verified): `GitVersionCheck.verify()` logs `STARTUP_GIT_VERSION_REFUSED` and throws
(`GitVersionCheck.java:66-99`); `ManualRunRunner.run` calls `gitVersionCheck.verify()` before
dispatch (`ManualRunRunner.java:106`); `OperatorConfigLoader` collects violations, prints and
throws `ConfigurationViolationsException` (`OperatorConfigLoader.java:97-106`), and its
`Subcommand.parse` switch is exhaustive (`:121-129`, a `DOCTOR` arm is the implementer's);
`Subcommand` enum and `ArgumentsParsingSupport.rejectUnknownOptions` exist; `ConsoleIO.print` /
`printMachine` (`ConsoleIO.java:48,57`); `FactoryVersion.current()` and `baseTag()`;
`FactoryHome` has no legacy-folder reader (D4 is new work); `SecretsProvider.find(String)`;
`PipelineLoader`, `AgentSettingsValidator`, `ExternalCheckSeamValidator`, `BindingResolver`,
`ProjectRegistry`, `OperatorConfigSourcesSpec`, `FactoryHomeBoundarySpec` all exist under the
named paths; `HostLauncher.prefix()` checks `/bin/kill`, `/usr/bin/perl` and `setsid` and refuses
(`HostLauncher.java:96-120`) — D4's `assessHelper()` and task 2.3 are consistent with it; the
`core.hooksPath` writer is gone from production (no `gnomish-empty-hooks` in any `.java`), the
suffix and the unset line match the release note (`docs/releases/v0.1.0.md:58-63`), and the
`--local` requirement matches the archived design's note for this change
(`2026-10-21-own-git-invocation-policy/design.md:157-159`). No part of the change is implemented:
no `doctor` token, no `DoctorCheck`, `HooksPathLeftover`, `AgentCredentials` or `TrackerReadProbe`
exists anywhere in `src/`.

### Coverage matrix

| ID      | proposal | delta spec                                                        | task                   |
|---------|----------|-------------------------------------------------------------------|------------------------|
| FR1     | ✅       | ✅ operator-doctor "Doctor reports every readiness check…"         | ✅ 3.2                 |
| FR2     | ✅       | ✅ same, "Outside any project"                                     | ✅ 3.2                 |
| FR3     | ✅       | ✅ "Doctor shares every decision…" (offline/online scenarios)      | ✅ 3.3                 |
| FR4     | ✅       | ✅ "Doctor shares every decision…"                                 | ✅ 1.1–1.3, 4.1        |
| FR5     | ✅       | ✅ "Doctor output is readable…"                                    | ✅ 3.4                 |
| FR6     | ✅       | ✅ "Doctor reports every readiness check…"                         | ✅ 3.4                 |
| FR7     | ✅       | ✅ git-transfer + operator-configuration MODIFIED (❌ stale text, R1) | ✅ 1.1, 1.2, 3.1    |
| FR8     | ✅       | ✅ "Doctor names the hooks-path line…"                             | ✅ 2.4, 3.2            |
| NFR-R1  | ✅       | ✅ "Doctor shares every decision…"                                 | ✅ 4.3                 |
| NFR-S1  | ✅       | ✅ "Secret named, never shown"                                     | ✅ 3.4                 |
| NFR-O1  | ✅       | ✅ "Doctor output is readable…"                                    | ✅ 3.4                 |
| NFR-P1  | ✅       | ⚠️ cited by "Doctor shares every decision…", no scenario           | ❌ none                |
| NFR-C1  | ✅ none  | — (declared none)                                                 | —                      |
| UX1     | ✅       | ✅ "Doctor output is readable…"                                    | ✅ 3.4                 |
| UX2     | ✅       | ✅ "Healthy setup"                                                 | ✅ 3.4                 |
| M1      | ✅       | —                                                                 | ✅ 4.2                 |
| M2      | ✅       | —                                                                 | ✅ 4.1                 |
| M3      | ✅       | —                                                                 | ✅ 4.3, 5.4            |

### Completeness

- `proposal.md`: every section of `proposal-format.md` present; IDs follow `traceability.md`;
  Open Questions "None"; NG4 names its pickup condition implicitly (the owners it lists) — fine.
- Delta specs: ADDED/MODIFIED structure correct, every requirement has scenarios, each carries one
  `**Implements**:` line. The two MODIFIED blocks keep every current scenario title.
- `design.md`: decisions D1–D6 with rationale and a rejected alternative each; `Sync surfaces`
  stated — but "none" is contradicted by the existing executable lookup (Freshness 4, R2);
  single-owner table present — row 3's consumers and value are stale (Freshness 2, R3), and
  rows 4–5 name `DoctorBoundarySpec`/"M1 table row" as enforcement, which is acceptable.
- `tasks.md`: NFR-P1 (offline run under 5 s, no pull, no container start) has no task and no
  scenario; task 4.3 is the natural home. → R7. Task 5.4 is a human step, but it is M3's own
  operator-stand acceptance and not a verification a spec replaces — left as is.
- `tasks.md:4` "Root `check` green after every section" contradicts
  `verification-scope.md` (landed with `narrow-branch-mutation-scope`). → R6.

### Internal consistency

- No contradiction found between proposal, design, deltas and tasks. The thirteen-check count is
  consistent across FR1, M1, task 3.2 and task 4.2 (and FR8 is counted separately everywhere); it
  becomes wrong only against the landed code (R4), which changes all four places together.
- Requirement IDs are unique; every ID a task or decision cites is defined in `proposal.md`.
- Terminology is stable (`doctor`, `check`, `verdict`, `owner`, `assess`).
- Task order is implementable: owners (1.x, 2.x) precede the subcommand (3.x), gates (4.x) and
  docs (5.x).

### External consistency

- **Vs stable specs:** the git-transfer MODIFIED block restates a superseded text (R1). The
  operator-configuration MODIFIED block restates the current main-spec text verbatim
  (`openspec/specs/operator/operator-configuration/spec.md:57-72`) and only adds one scenario and
  one note — fresh. The new capability `cli/operator-doctor` duplicates no existing requirement
  (`openspec/specs/cli/` holds `cli-arguments`, `manual-run`, `operator-console`,
  `task-inspection`).
- **Vs implemented functionality:** (a) a second executable resolver beside `ExecutableLookup`
  (R2); (b) the reachable image-readiness port starts a helper container, against NFR-R1/NG1
  (R3); (c) the ref-format and box-git-floor preconditions are refused at startup and absent from
  the check list (R4).
- **Vs other active changes, MODIFIED overlap:** no other active delta modifies `Git version floor
  at startup` or `Violations stop startup with one complete report` (grep over
  `openspec/changes/*/specs`), and no other active change names `ManualRunRunner`, `Subcommand`,
  `OperatorConfigLoader`, `GitVersionCheck`, `ImageContractCheck`, `HostLauncher` or
  `ServePreconditions`. No collision.
- **Vs narrowed scenarios:** none — both MODIFIED blocks add a scenario and remove no promise; the
  delta's scenarios are unchanged since `c6a4df18` (`git diff HEAD -- openspec/changes/add-doctor-command/specs` is empty).
- **Vs rules and ADRs:** tech choices within ADR 0001; the "assess, then refuse" shape matches
  ADR 0019 decision 4 ("the checks throw without logging; the layer that decides writes the one
  line") once built on the startup owner (R3); scope fits one initiative; kebab-case name;
  artifacts cite no scratch location.

### Deferrals

- `add-sandbox-base-image` **NG10** ("a non-refusing assessment of the same preconditions for an
  operator's `doctor` command. Owned by `add-doctor-command`, which builds its assessment on the
  startup owner this change introduces (design D17)") — condition met by this change; **taken up
  only in part**: the design builds on `ImageContractCheck` directly and never names
  `ServePreconditions`, `SandboxImageReadiness` or `OperatorPreconditionRefusals`, and the check
  list misses two of the four precondition kinds. → R3, R4.
- `own-git-invocation-policy` **UX2 / Q3** (the `core.hooksPath` leftover check, read with
  `--local`) — taken up: FR8, D6, task 2.4, delta requirement "Doctor names the hooks-path line…".
- `fix-terminal-receipt-convergence` D19 ("`add-doctor-command` checks its presence" of `setsid`;
  risk row: "the doctor command reports the missing helper") — taken up: FR1, D4, task 2.3, the
  two launcher scenarios.
- `add-release-pipeline` **NG4** and `add-project-registry` **NG4** (both: "`gnomish doctor`") —
  this change is the pickup. `add-project-registry` **NG3** (no migration of the old
  `~/.gnomish/{logs,secrets,serve,worktrees}` layout) — this change detects and warns (FR1, D4)
  and migrates nothing (NG2): consistent with the deferral, which no condition reopens.

### Recommendations, ordered

1. **CRITICAL — git-transfer delta restates a superseded floor (2.51.0)** (`openspec/changes/add-doctor-command/specs/git/git-transfer/spec.md:3-15`)
   Problem: the MODIFIED block of `Git version floor at startup` is layered on `tighten-spec-contract` and restates 2.51.0 with that change's `**Implements**:` and `**Notes**:` lines, while the main spec (`openspec/specs/git/git-transfer/spec.md:189-194`) now reads 2.56.0, carries `FR10, UX3 of own-git-invocation-policy` and a different note, after `own-git-invocation-policy` was archived in the same commit that last revised this change (`c6a4df18`); the code agrees (`GitVersion.java:47`).
   Impact: the OpenSpec MODIFIED merge is replace-only, so archiving this change rewrites the main spec's floor back to 2.51.0, drops the traceability of `own-git-invocation-policy` and its reason, and leaves the spec contradicting `GitVersion.FLOOR` and `GitVersionCheck`'s implements line.
   Fix: replace the block with the current main-spec text verbatim (2.56.0, its `**Implements**:` line plus `; FR7 of add-doctor-command`, its `**Notes**:` line, then the doctor note), keep the two existing scenarios verbatim and the added scenario "Doctor reports an old git"; reword the preamble to "Layered on `Git version floor at startup` as modified by `own-git-invocation-policy` (sequenced before this change)…" and update `proposal.md:6-8` accordingly.
   Fix risk: none — the edit restores what is already in `openspec/specs/` and adds only the doctor note and scenario.

2. **CRITICAL — a second executable resolver beside `ExecutableLookup`** (`openspec/changes/add-doctor-command/design.md:24-25, 70-72, 80-81, 109-111`)
   Problem: D4 introduces `AgentCliBinary.locate(binary, path)` in `:adapters:agent` on the premise that "nothing today locates the agent CLI binary before a round fails to start it", and the design states `Sync surfaces: none`. The host launch owner already resolves every command's first word before any process exists — `GroupedLaunch.start` (`subprocess/.../GroupedLaunch.java:107-110`) through `ExecutableLookup.resolve` (`subprocess/.../ExecutableLookup.java`), with the `execvp` rule (absolute `PATH` entries only, executable regular file, `#!` chain up to four hops; design D19 of `fix-terminal-receipt-convergence` as amended 2026-10-17).
   Impact: two implementations of one rule with no shared abstraction — the undeclared pair `manual-sync-pairs.md` bans — and the exact drift G2 forbids: a binary `doctor` reports `ok` (a relative `PATH` entry, a script whose interpreter is absent) that the launch then refuses with exit 127, or the reverse, with every unit spec green.
   Fix: in D4, make the location a non-refusing assessment of the host launch owner in `:subprocess` beside `assessHelper()` (one `assessExecutable(name, directory, path)` built on `ExecutableLookup.resolve`, handed the child's `PATH` as `GroupedLaunch.start` computes it), drop `AgentCliBinary.locate` or reduce it to the thin adapter that calls that assessment; update `design.md:24-25` (the premise), the single-owner row 5 (owner `ExecutableLookup` via the launch owner, consumers `GroupedLaunch.start` and the agent-CLI doctor check, enforcement `DoctorBoundarySpec` plus the launcher's own spec), task 2.1 and task 2.3 (one task on the launch owner), and keep `Sync surfaces: none` true by that edit.
   Fix risk: `ExecutableLookup` and `GroupedLaunch` are package-private in `:subprocess`, so the assessment needs a public seam there (as `assessHelper()` of D4 already does); the lookup follows the `#!` chain by reading the file head, which is a read and not a write, so NFR-R1 holds.

3. **WARNING — the image-contract owner and the startup owner landed differently from D1 row 3** (`openspec/changes/add-doctor-command/design.md:21-22, 36-39, 119`; `tasks.md:16-19`)
   Problem: D1 and single-owner row 3 build the sandbox-image check on `ImageContractCheck.assess(ImageFacts)` with "the environment builder's refusal" as the other consumer. Since `add-sandbox-base-image` (D17, ADR 0019) the method is `require(ImageFacts)` on a package-private class (`ImageContractCheck.java:36,65`) with two consumers, `ContainerMaterializer` and `DockerImageReadiness.assess` (`DockerImageReadiness.java:49-52`), the latter being the Docker adapter of the port `SandboxImageReadiness.assess(String)` that `ServePreconditions` (`ServePreconditions.java:117-126`) — the startup owner NG10 of that change hands this one — runs; and that port's one method also runs `BoxGitFloor.require(image)`, which starts a helper container (`BoxGitFloor.java:24`), a side effect NFR-R1 and NG1 forbid and NFR-P1 cannot afford.
   Impact: the implementer cannot build the task as written (`:bootstrap` cannot reach `ImageContractCheck`), and the only reachable owner starts a container, so either the check is re-spelled in the doctor package (a second owner of the contract decision, the drift D1 exists to prevent) or `doctor` violates its own no-side-effect requirement.
   Fix: in D1 and row 3, name `SandboxImageReadiness` as the owner `doctor` reads through, and decide its non-refusing form: a verdict-returning `assess` split into the contract inspection (one `ImageFacts` read, no container) and the box git floor probe (the one container start), with `doctor` reporting the contract from the first and the box git floor as `warn`/skipped-with-reason or run only behind `--online`-style opt-in; list the consumers as `ServePreconditions.assess`, `DaemonConditions`' probe (`ServePreconditions.rechecks`) and `SandboxImageDoctorCheck`; rewrite task 1.3 over the port and its adapter (`DockerImageReadiness`), and name `ServePreconditions`/`OperatorPreconditionRefusals` in the Context as the startup owner the assessment builds on (ADR 0019 decision 1, 4).
   Fix risk: splitting the port's method touches `ServePreconditions.check` and its `DaemonConditions` rechecks (`ServePreconditions.java:102-126`), which must keep running both halves; `ServePreconditionSpellingSpec` in `:bootstrap` pins that the owner adds no spelling, so the split stays in the port and its adapter.

4. **WARNING — the check list omits two operator preconditions the startup path now refuses on** (`openspec/changes/add-doctor-command/proposal.md:90-102, 147`; `specs/cli/operator-doctor/spec.md:19-30`; `tasks.md:54-63, 78-81`)
   Problem: FR1, the scenario "Every readiness check is reported", M1's thirteen rows and tasks 3.2/4.2 list no check for the factory clone's ref backend (`TaskBranchGit.requireRefFormat`, `DaemonConditionKind.CLONE_REF_FORMAT`, `ServePreconditions.java:79,119`) nor for the box image's git floor (`DaemonConditionKind.BOX_GIT_FLOOR`, `DockerImageReadiness.java:52`), both of which `serve` refuses at startup and `take`/`run` end on with exit 8 since `add-sandbox-base-image` (`OperatorPreconditionRefusals.java:34-42`).
   Impact: G1 ("everything that would stop the factory on this machine") and G2 ("never disagrees with the real startup path") are false on delivery — a `files`-backend clone passes `doctor` and refuses `serve` — and M1's identity table, built from FR1's list, cannot show it.
   Fix: add to FR1 "the factory clone's ref backend (reftable), through the ref-format check the startup assessment runs" and "the box image's git meets the floor (container-bound; see R3 for the probe's side effect)"; add both to the scenario "Every readiness check is reported" (the first under the clone-level checks, the second under the container-bound `AND`); raise the count in M1, task 3.2 and task 4.2 from thirteen to fifteen; add a row to the single-owner table for `TaskBranchGit.requireRefFormat` (owner, consumers `ServePreconditions`, the touchpoint fetch/classification/locate, the doctor check).
   Fix risk: the box git floor check depends on R3's decision about the container start; if it is reported only behind an opt-in, M1's row for it asserts the skipped verdict rather than the floor.

5. **SUGGESTION — name the reachable seams for secrets and the runtime probe** (`openspec/changes/add-doctor-command/design.md:18-20`)
   Problem: the Context names `CheckEquipment.credentialNames(…)`, which is `private` (`CheckEquipment.java:133`), and `DockerRuntimeProbe.dockerAvailable()`, which the startup path reaches only through the port `ContainerRuntimeProbe` (`SandboxBindingConfiguration.java:72-73`, `SandboxModeSelector.java:132`); the public declaration seam is `CheckProviderSeam.credentialEnvVars(subsections, registry)` (`CheckProviderSeam.java:157`) beside `BoundTracker.credentialEnvVars()`.
   Impact: clarity for the implementer of task 3.2 only; FR4's "the component the real startup path uses" is the port, not the static.
   Fix: replace the two names with `CheckProviderSeam.credentialEnvVars` + `BoundTracker.credentialEnvVars()` and `ContainerRuntimeProbe` (adapter `DockerRuntimeProbe::dockerAvailable`), and list `ContainerRuntimeProbe` in task 3.2's owner list.
   Fix risk: none.

6. **SUGGESTION — per-section root `check` contradicts the verification rule** (`openspec/changes/add-doctor-command/tasks.md:4`)
   Problem: "Root `check` green after every section" predates `narrow-branch-mutation-scope` and `.claude/rules/verification-scope.md`, which runs the root `check` once at the end (task 5.3 already does) and scopes each task to its own specs and PIT classes.
   Impact: an implementer following the task text spends hours re-mutating the branch per section, the failure that rule records; nothing is built wrong.
   Fix: replace the sentence with "Each task verified per `verification-scope.md`; root `check` once in 5.3."
   Fix risk: none.

7. **SUGGESTION — NFR-P1 has no task and no scenario** (`openspec/changes/add-doctor-command/proposal.md:135-136`; `tasks.md:82-84`)
   Problem: the offline 5-second bound with no pull and no container start is cited by the requirement "Doctor shares every decision with the startup path" but has no scenario and no task.
   Impact: the bound is never asserted; a doctor that starts the floor-probe helper (R3) would pass every spec.
   Fix: in task 4.3, add "and no container was created (`docker ps -a` by ownership label empty), the run under 5 s on the E2E host" and add a scenario "Offline doctor starts no container" under the same requirement.
   Fix risk: a wall-clock assertion is a flake source on a loaded runner; assert the no-container and no-pull effects and keep the time bound as a logged measurement.

### Verdict

**needs revision** — blockers: R1 (the git-transfer delta would revert the landed 2.56.0 floor and drop `own-git-invocation-policy`'s traceability on archive), R2 (a second executable resolver beside `ExecutableLookup`, with `Sync surfaces: none` contradicted), R3 (the image-contract owner is unreachable and the reachable port starts a container, undecided), R4 (two landed operator preconditions missing from the check list, so G1/G2 are false on delivery). The change's premise stands — no `doctor` exists anywhere in `src/` — and the rest of the artifacts are fresh.

Nothing in the project was modified by this review; the human decides what to apply.

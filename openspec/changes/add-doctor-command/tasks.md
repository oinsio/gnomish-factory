# Tasks

Starts after `add-project-registry`, `add-release-pipeline` and
`add-sandbox-base-image` are archived. Root `check` green after every section.

## 1. Assess, then refuse (design D1) — single-owner rows 1–3

- [ ] 1.1 `GitVersionCheck.assess()` returning `GitVersionVerdict`; `verify()`
      becomes assess + the existing ERROR line and exception (FR4, FR7). Verify
      `GitVersionCheckSpec` data table: `verify` refuses exactly when `assess`
      fails; the existing log and exception specs pass unchanged.
- [ ] 1.2 `OperatorConfigLoader` collect mode for the `doctor` subcommand:
      violations published as a bean instead of thrown; every other subcommand
      unchanged (FR7). Verify `OperatorConfigSourcesSpec` gains a row: with
      `doctor` the same violations are published and no exception is thrown.
- [ ] 1.3 `ImageContractCheck.assess(ImageFacts)` returning
      `ImageContractVerdict`; the environment builder refuses on `fail` as
      before. Verify the execution-environment contract scenarios still pass
      and a table shows refusal iff `assess` fails.

## 2. New owners (design D4)

- [ ] 2.1 `AgentCliBinary.locate` and `AgentCredentials.presentNames` in
      `:adapters:agent` (names only, never values; javadoc cites FR1, NFR-S1).
      Verify Spock specs with a fake `PATH` directory and a fake environment
      map; PIT 100%.
- [ ] 2.2 `FactoryHome.legacyFolders()` listing old root-level
      `logs/gnomish.log`, `serve/`, `worktrees/`, `secrets/` entries. Verify a
      spec over a temporary home with and without leftovers, and that
      `FactoryHomeBoundarySpec` still passes.

## 3. The `doctor` subcommand (design D2, D3, D5)

- [ ] 3.1 `doctor` in `Subcommand` with accepted options `--dir`, `--online`,
      `--json` (through `rejectUnknownOptions`); dispatched in
      `ManualRunRunner` before `gitVersionCheck.verify()` (FR7). Verify a spec
      that `doctor` runs with a fake git below the floor and still reports every
      check.
- [ ] 3.2 `DoctorCheck`, `DoctorContext` and the twelve checks of FR1 as thin
      adapters over their owners (`FactoryVersion`, `GitVersionCheck`,
      `OperatorConfigLoader` bean, `ProjectRegistry`, `PipelineLoader`,
      `BoundTracker` + `CheckEquipment` + `SecretsProvider`,
      `AgentCredentials`, `DockerRuntimeProbe`, `ImageFacts` +
      `ImageContractCheck`, guard image via `ImageFacts`, `AgentCliBinary`,
      `FactoryHome.legacyFolders`); project checks skipped with a reason when no
      project resolves (FR1, FR2). Verify one spec per check for ok/warn/fail.
- [ ] 3.3 `TrackerReadProbe` behind `--online`, using the tracker's read-only
      resolution; without `--online` no network call (FR3). Verify with
      WireMock: one read with `--online`, none without.
- [ ] 3.4 `DoctorReport` text and `--json` renderings through `ConsoleIO`
      `print`/`printMachine`; exit 0 without `fail`, 1 with (FR5, FR6, NFR-O1,
      NFR-S1, UX1, UX2). Verify specs for both renderings, the exit codes, and
      that a present secret's value never appears in either output.

## 4. Enforcement and identity (single-owner rows 4–5)

- [ ] 4.1 `DoctorBoundarySpec` in `:bootstrap`: no `ProcessBuilder`,
      `Runtime.exec`, `System.getenv`, `System.getProperty`, YAML/JSON parser
      or `Files.read*` in the doctor package; asserts every doctor source was
      scanned (M2). Verify red with a planted `System.getenv`.
- [ ] 4.2 Identity table (M1): for each of the twelve checks, one fixture run
      through the startup path (`take` or the owner's refusing form) and through
      `doctor`, asserting the same verdict. Verify the table has twelve rows and
      passes.
- [ ] 4.3 E2E: packaged jar, temporary `GNOMISH_HOME`, a registered clone with a
      missing token and a missing sandbox image → one run, two `fail` lines,
      exit 1, no worktree or box created (NFR-R1, M3). Verify the spec.

## 5. Docs and wrap-up

- [ ] 5.1 README install section ends with `gnomish doctor`; operator guides
      point to it from their troubleshooting sections; `docs/glossary.md` entry
      for doctor. Verify the README step exists.
- [ ] 5.2 Traceability: every FR/NFR/UX named by a spec or javadoc. Verify
      `git grep -n "of add-doctor-command"` against the proposal's IDs.
- [ ] 5.3 Root `./gradlew check` green. Verify the exit code.
- [ ] 5.4 Operator-stand acceptance (M3, human step): break three things
      (old image, missing token, unregistered clone), run `gnomish doctor` once.
      Verify three `fail` lines with fixes.

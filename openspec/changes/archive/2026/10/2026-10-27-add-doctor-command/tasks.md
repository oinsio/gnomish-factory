# Tasks

Starts after `add-project-registry`, `add-release-pipeline` and
`add-sandbox-base-image` are archived. Each task verified per
`.claude/rules/verification-scope.md`; root `check` once in 5.3.

## 1. Assess, then refuse (design D1) — single-owner rows 1–4

- [x] 1.1 `GitVersionCheck.assess()` returning `GitVersionVerdict`; `verify()`
      becomes assess + the existing ERROR line and exception (FR4, FR7). Verify
      `GitVersionCheckSpec` data table: `verify` refuses exactly when `assess`
      fails; the existing log and exception specs pass unchanged.
- [x] 1.2 `OperatorConfigLoader` collect mode for the `doctor` subcommand:
      violations published as a bean instead of thrown; every other subcommand
      unchanged (FR7). Verify `OperatorConfigSourcesSpec` gains a row: with
      `doctor` the same violations are published and no exception is thrown.
- [x] 1.3 Split the port `SandboxImageReadiness` (D1): a contract inspection
      (one `ImageFacts` read through `ImageContractCheck`, no container)
      beside the box git floor probe in its adapter `DockerImageReadiness`;
      `assess(image)` keeps running both, so `ServePreconditions.assess` and
      its `DaemonConditions` rechecks are unchanged. Give `ServePreconditions`
      a per-kind, non-refusing verdict for `doctor` (`CLONE_REF_FORMAT`,
      `CONTAINER_RUNTIME`, the image contract from the inspection), built on
      the checks it already runs. Verify the execution-environment contract
      scenarios and `ServePreconditionSpellingSpec` still pass, a table shows
      startup refuses on `IMAGE_CONTRACT` iff the inspection fails, and the
      inspection starts no container.

## 2. New owners (design D4, D6)

- [x] 2.1 `AgentCredentials.presentNames` in `:adapters:agent` (names only,
      never values; javadoc cites FR1, NFR-S1). Verify Spock specs with a fake
      environment map; PIT 100%.
- [x] 2.2 `FactoryHome.legacyFolders()` listing old root-level
      `logs/gnomish.log`, `serve/`, `worktrees/`, `secrets/` entries. Verify a
      spec over a temporary home with and without leftovers, and that
      `FactoryHomeBoundarySpec` still passes.

- [x] 2.3 Two non-refusing assessments on the host launch owner of
      `fix-terminal-receipt-convergence` (`:subprocess`, D19 there), as a public
      seam its launching form is built on (D1, D4): `assessHelper()`, the verdict
      over both tools the launching form refuses on — the platform's launcher and
      the sweep's `/bin/kill` (`HostLauncher.prefix`, task 8.20 there); and
      `assessExecutable(name, directory, path)` over `ExecutableLookup.resolve`,
      the rule `GroupedLaunch.start` refuses on. Verify specs that the launch
      refuses exactly when `assessHelper` fails, over both platforms' helpers and
      the kill path handed in as parameters, and exactly when
      `assessExecutable` fails, over a fake `PATH` directory (relative entry,
      non-executable file, missing `#!` interpreter).

- [x] 2.4 `HooksPathLeftover.assess(clone)` in `:adapters:git` (D6; FR8 of
      this change, UX2 of `own-git-invocation-policy`): reads
      `git config --local --get core.hooksPath` through `GitRunner`; `warn` with
      the fix `git config --local --unset core.hooksPath` only when the value
      ends in `/gnomish-empty-hooks`, `ok` otherwise; writes nothing. Verify a
      spec over a real clone with the invocation policy in force: the leftover
      value warns, an unset value and an operator's own path are `ok` with no
      unset line, and the clone's `.git/config` is byte-identical afterwards.

## 3. The `doctor` subcommand (design D2, D3, D5)

- [x] 3.1 `doctor` in `Subcommand` with accepted options `--dir`, `--online`,
      `--json` (through `rejectUnknownOptions`); dispatched in
      `ManualRunRunner` before `gitVersionCheck.verify()` (FR7). Verify a spec
      that `doctor` runs with a fake git below the floor and still reports every
      check.
- [x] 3.2 `DoctorCheck`, `DoctorContext` and the fourteen checks of FR1 as thin
      adapters over their owners (`FactoryVersion`, `GitVersionCheck`,
      `OperatorConfigLoader` bean, `ProjectRegistry`, `ServePreconditions`
      for the clone's ref backend (1.3), `PipelineLoader`,
      `BoundTracker` + `CheckProviderSeam` + `SecretsProvider`,
      `AgentCredentials`, `ContainerRuntimeProbe`, `ServePreconditions` over
      the `SandboxImageReadiness` contract inspection (1.3; the box git floor
      named as not probed), guard image via the sandbox adapter's image
      inspection, the host launch owner's `assessExecutable` and
      `assessHelper` (2.3),
      `FactoryHome.legacyFolders`), plus the hooks-path check of FR8 over
      `HooksPathLeftover` (2.4); project checks skipped with a reason when no
      project resolves (FR1, FR2, FR8). Verify one spec per check for ok/warn/fail.
- [x] 3.3a `GithubLabelProvisioner.assess(owner, repo, labelDefs)` in
      `:adapters:github` (D1 applied, D7): the listing request and its status
      classification move out of `provision` into `assess`, returning a verdict
      (readable with the missing label names; or refused with the HTTP class,
      the cause the current message names — 404 worded as not found or not
      visible to this token — and, on 403, the `X-Accepted-GitHub-Permissions`
      header verbatim); `provision` becomes assess, create the missing labels,
      refuse on a failed verdict, with its current exception and message. Verify
      `GithubLabelProvisionerSpec`: `provision` refuses exactly when `assess`
      fails, over one data table of statuses, and `assess` sends no `POST`.
- [x] 3.3b `TrackerAdapterFactory.probeRead(SecretsProvider, TrackerConfig)` in
      `:gnomish-plugin-api` (D7), a default method returning a probe verdict
      type of the api; the default makes no call and reports not applicable
      (the in-memory adapter keeps it), the GitHub override builds its client
      as `refuseForeignRef` does and returns `assess`; the japicmp baseline is
      regenerated in the same task. Verify the in-memory factory's default makes
      no call, the GitHub override's spec on WireMock, and the compatibility
      gate green on the regenerated baseline.
- [x] 3.3c `TrackerReadProbe` behind `--online` over `TrackerWiring.probeRead`
      (a relay, like `refuseForeignRef`), never `create` or the read-only
      tracker resolution; the line reports reading confirmed, the labels startup
      will create, and that write access is not verified; without `--online` no
      network call (FR3, NG5). Verify with WireMock on a repository without the
      labels: exactly one `GET` and no `POST` with `--online`, no request
      without; and the refused scenario (404 and 403 lines).
- [x] 3.4 `DoctorReport` text and `--json` renderings through `ConsoleIO`
      `print`/`printMachine`; exit 0 without `fail`, 1 with (FR5, FR6, NFR-O1,
      NFR-S1, UX1, UX2). Verify specs for both renderings, the exit codes, and
      that a present secret's value never appears in either output.

## 4. Enforcement and identity (single-owner rows 5–10)

- [x] 4.1 `DoctorBoundarySpec` in `:bootstrap`: no `ProcessBuilder`,
      `Runtime.exec`, `System.getenv`, `System.getProperty`, YAML/JSON parser
      or `Files.read*` in the doctor package; asserts every doctor source was
      scanned (M2). Verify red with a planted `System.getenv`.
- [x] 4.2 Identity table (M1): for each of the fourteen checks, one fixture run
      through the startup path (`take` or the owner's refusing form) and through
      `doctor`, asserting the same verdict. Verify the table has fourteen rows and
      passes.
- [x] 4.3 E2E: packaged jar, temporary `GNOMISH_HOME`, a registered clone with a
      missing token and a missing sandbox image → one run, two `fail` lines,
      exit 1, no worktree created and no container started — neither a box nor
      the box git floor helper (NFR-R1, NG1, M3). Verify the spec.

## 5. Docs and wrap-up

- [x] 5.1 README install section ends with `gnomish doctor`; operator guides
      point to it from their troubleshooting sections; `docs/glossary.md` entry
      for doctor. Verify the README step exists.
- [x] 5.2 Traceability: every FR/NFR/UX named by a spec or javadoc, and UX2 of
      `own-git-invocation-policy` named by the hooks-path check's spec. Verify
      `git grep -n "of add-doctor-command"` against the proposal's IDs.
- [x] 5.3 Root `./gradlew check` green. Verify the exit code.
- [x] 5.4 Operator-stand acceptance (M3, human step): break three things
      (old image, missing token, unregistered clone), run `gnomish doctor` on
      the registered clone, then with `--dir` naming an unregistered clone.
      Verify two `fail` lines with fixes on the first run, one `fail` line and
      the project checks `skipped` on the second. Done by the operator on the
      time-zones stand, 2026-10-27: first run 2 `fail` (declared secret, sandbox
      image without the image-contract label), exit 1; second run 1 `fail`
      (project registration), 10 `skipped`, exit 1.

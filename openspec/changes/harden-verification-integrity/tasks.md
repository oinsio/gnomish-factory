# Tasks: harden-verification-integrity

## 1. Sealed law tree in the environment

- [ ] 1.1 Implement `LawMaterializer`: write every law entry through `putFile` into a
  factory-owned read-only directory in the scratch area, once per environment, exposed as
  `GNOMISH_LAW_DIR`; container mode: owned by root, mode 0555; host mode: directory outside the
  worktree, 0555; specs: idempotent re-run, write attempt fails, content equals the law commit
  while the working copy differs (FR1, NFR-S1, NFR-R1, D1)
- [ ] 1.2 Wire it into the agent round runner (every round) and both check environment sources
  (every check); `shell` built-in receives the variable; spec: a check running
  `$GNOMISH_LAW_DIR/verify/run-tests.sh` ignores the working-copy edit (FR1, M1)
- [ ] 1.3 Old-way sweep: `grep -rn "putFile(" --include=*.java */src/main adapters/*/src/main` →
  no `.gnomish/` path outside the materializer; architecture spec in `:bootstrap` pinning it;
  the judge criteria path (law source text) listed as the named exemption (D8)
- [ ] 1.4 Glossary: law directory, protected path, `diff-scope`, advisory finding; operator guide
  section on moving verification assets under `.gnomish/`

## 2. Protected paths and diff-scope

- [ ] 2.1 Loader: `protected:` globs (lexical validation, law-tree glob rejected),
  `allows-protected` (error without `protected:`), `severity` enum with round-trip spec (FR2,
  FR6)
- [ ] 2.2 `ProtectedPathMatcher` in `domain/verification` producing `ProtectedChanges` from a
  path list with change kinds; data-driven glob specs (D8)
- [ ] 2.3 `diff-scope` built-in over `gitobjects`: attempt vs base diff, matcher, deletion rule,
  size cap, SARIF findings "protected path changed: <path> (by <glob>)"; registered in
  `ExecutorRegistry` with `needsWorkspace: false`; specs on a real bare origin for each scenario
  (FR3, NFR-R2, UX1, M1)
- [ ] 2.4 Attach `ProtectedChanges` to every `verdict` request as delimited data;
  `ProtectedChangesIdentitySpec`: the judge's list equals `diff-scope`'s (FR4, D3, D8)

## 3. Repo-local policy isolation

- [ ] 3.1 Runner: per-round factory-owned config directory, set the describe-declared variable,
  append the declared flags; loader refuses an agent executor with neither unless
  `allow-repo-policy: <reason>` (blank reason is an error); specs with the fake agent recording
  argv and `CLAUDE_CONFIG_DIR`: attempt 2 after a widening `.claude/settings.json` has the same
  effective policy (FR5, NFR-S2, M2, D4)
- [ ] 3.2 Claude dialect describe: `CLAUDE_CONFIG_DIR` and the settings-source flags; paid-smoke
  recorder scenario asserting the effect on the real CLI (recorded fixture)

## 4. Advisory severity

- [ ] 4.1 `VerifyOrchestrator`: record advisory results with severity, treat as passed for
  control flow, fail-fast unaffected, infrastructure failure of an advisory check logged as
  unavailable; tracker publication and ledger tag `advisory`; data-driven control-flow spec
  (FR6, UX2, D5)

## 5. Threat registry and docs

- [ ] 5.1 Reword #22, add #49 (verification assets in gnome-writable space) and #50 (repo-local
  agent policy widening) with closing mechanisms, note the host-mode limitation on #6; verify
  numbering is unique and sections are topical (FR7, M3)

## 6. Integration

- [ ] 6.1 End-to-end in container mode: law with `protected: [gradlew, src/test/**]`, a `shell`
  check from `GNOMISH_LAW_DIR`, an attempt that edits `gradlew` to `exit 0` fails `diff-scope`
  before the shell check runs; an advisory check reports without burning an attempt (M1, G4)
- [ ] 6.2 `./gradlew check` with PIT; sweep report for D8 rows; recommend a commit message

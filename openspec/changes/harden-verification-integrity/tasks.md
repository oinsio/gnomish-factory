# Tasks: harden-verification-integrity

## 1. Sealed law tree in the environment

- [ ] 1.1 Add `TaskExecutionEnvironment.sealTree` (write a whole tree under a factory-owned
  directory of the scratch root and seal it, returning the typed `LawDir`; `putFile` unchanged)
  with its two implementations: host (`HostTaskExecutionEnvironment`, a directory outside the
  worktree, mode 0555) and container (`ContainerTaskExecutionEnvironment`, owned by root, mode
  0555, the sealing exec as root touching only that directory, every other exec as the task
  user); the delegating decorators of the port (`LeasedEnvironment`, `SelfCheckedEnvironment`,
  `WireObservation`) forward it; `SealedTreeContractSpec` run over identical trees against both adapters. Implement
  `LawMaterializer` over it: every law entry from the law source, once per environment, exposed
  as `GNOMISH_LAW_DIR`; specs: idempotent re-run, write attempt fails, content equals the law
  commit while the working copy differs, the tree holds exactly the law tree's entries (FR1,
  NFR-S1, NFR-R1, NFR-C1, D1, D7)
- [ ] 1.2 Wire it into the two round environment sources (`HostRoundEnvironmentSource`,
  `SandboxRoundEnvironmentSource`: at the open, for every registry entry) and the two check
  environment sources (`HostCheckEnvironmentSource`, `SandboxCheckEnvironmentSource`); set
  `GNOMISH_LAW_DIR` in the child-environment composition (`ExecCommand.env` through
  `ChildEnvAllowlist`) so every child — agent, `shell`, `command`, `program` — receives it; spec:
  a check running `$GNOMISH_LAW_DIR/verify/run-tests.sh` ignores the working-copy edit (FR1, M1,
  D8)
- [ ] 1.3 Old-way sweep: `grep -rn "LawSource\|sealTree(" --include='*.java' */src/main
  */*/src/main` (`adapters`, `sandbox/*`, `bootstrap`, `application`) → law content enters an
  environment only through `LawMaterializer`; architecture spec `LawContentBoundarySpec` in
  `:bootstrap` pinning it (allowlist with reasons, asserted reached); the judge criteria path
  (law source text) listed as the named exemption (D8)
- [ ] 1.4 Glossary: law directory, protected path, `diff-scope`, advisory check (a check
  declaring `severity: advisory`; distinct from the below-threshold finding of a blocking check),
  task base (the SHA of the task's base pin, the commit the task branch was created from);
  operator guide section on moving verification assets under `.gnomish/`

## 2. Protected paths and diff-scope

- [ ] 2.1 Loader: `protected:` globs (lexical validation, law-tree glob rejected),
  `allows-protected` (error without `protected:`), `severity` enum with round-trip spec (FR2,
  FR6)
- [ ] 2.2 `ProtectedPathMatcher` in `domain/verification` producing `ProtectedChanges` from a
  path list with change kinds; data-driven glob specs (D8)
- [ ] 2.3 `diff-scope` built-in over `gitobjects`: attempt vs base diff, matcher, deletion rule,
  size cap, SARIF findings "protected path changed: <path> (by <glob>)"; registered in
  `ExecutorRegistry` and dispatched box-less like `github-actions` (no environment opened, no
  describe field); specs on a real bare origin for each scenario (FR3, NFR-R2, UX1, M1, D2)
- [ ] 2.4 Attach `ProtectedChanges` to every `verdict` request as the optional role input
  `roleInputs.protectedChanges`: `RoleInputs`, `executor-request.schema.json`, the reference
  executor and the conformance kit's wire check accept it (absent reads as empty); the judge
  prompt renders it inside the data delimiters; `ProtectedChangesIdentitySpec`: the judge's list
  equals `diff-scope`'s (FR4, D3, D8)

## 3. Repo-local policy isolation

- [ ] 3.1 Runner: per-round factory-owned config directory, set the describe-declared variable,
  append the declared flags; loader refuses an agent executor with neither unless
  `allow-repo-policy: <reason>` (blank reason is an error); specs with the fake agent recording
  argv and `CLAUDE_CONFIG_DIR`: attempt 2 after a widening `.claude/settings.json` has the same
  effective policy, each flag appears once on the argv; the directory starts each round with
  factory-written content only and dies with the round; credentials reach the round only through
  the describe-declared variables, never a login under the operator's home; operator guide names
  the credential variables a host operator sets (FR5, NFR-S2, M2, D4)
- [ ] 3.2 The Claude describe stops declaring `--strict-mcp-config` (the policy compilation keeps
  rendering it; `ClaudeEnvironment.POLICY_DISABLING_FLAGS` and `ClaudeDialectDescribeSpec`
  updated) — the describe's `CLAUDE_CONFIG_DIR` and `--setting-sources=user` already landed in
  `extract-agent-round-runner`; grep gate `--strict-mcp-config` in `adapters/agent/src/main` →
  `ClaudePolicyCompiler.java` only; paid-smoke recorder scenario asserting the effect on the real
  CLI (recorded fixture) (FR5, D4, D8)

## 4. Advisory severity

- [ ] 4.1 `VerifyOrchestrator`: record advisory results with severity, treat as passed for
  control flow — an advisory non-pass never stops the chain, a blocking one still does — infrastructure failure of an advisory check logged as
  unavailable; tracker publication and ledger tag `advisory`; data-driven control-flow spec
  (FR6, UX2, D5)

## 5. Threat registry and docs

- [ ] 5.1 Reword #22, add two rows under the next free numbers (51 and 52 today): verification
  assets in gnome-writable space, and repo-local agent policy widening, with closing mechanisms, note the host-mode limitation on #6; verify
  numbering is unique and sections are topical (FR7, M3)

## 6. Integration

- [ ] 6.1 End-to-end in container mode: law with `protected: [gradlew, src/test/**]`, a `shell`
  check from `GNOMISH_LAW_DIR`, an attempt that edits `gradlew` to `exit 0` fails `diff-scope`
  before the shell check runs; an advisory check reports without burning an attempt (M1, G4)
- [ ] 6.2 `./gradlew check` with PIT; sweep report for D8 rows; recommend a commit message

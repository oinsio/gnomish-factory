## Review Resolution: kill-expensive-mutants

Source: temporary-docs/gnomish/github-oinsio-gnomish-factory-92/review-artifacts.md

### 1 — applied
- Recommendation: WARNING — `StallingGitSpec` cannot live in `:test-fixtures`
- Re-verified: `test-fixtures/build.gradle:12-14` — "this module has no test source set of its own, so `test-conventions` would only add an unused Spock/Groovy test stack"; `ls test-fixtures/src` shows `main` only; `bootstrap/src/test/groovy/com/github/oinsio/gnomish/adapter/git/AdversarialGitConfigSpec.groovy` exists and is the precedent for a fixture's spec living in `:bootstrap`.
- Edit: `openspec/changes/kill-expensive-mutants/tasks.md` task 1.2 Verify — the spec now lives in `:bootstrap` (`bootstrap/src/test/groovy/com/github/oinsio/gnomish/adapter/git/`, beside `AdversarialGitConfigSpec`, with the reason); `proposal.md` Impact, `bootstrap/src/test` bullet — names `StallingGitSpec` (the builder's own spec; `:test-fixtures` has no test source set) beside `StallingGitOwnerSpec`. As proposed.

### 2 — applied
- Recommendation: WARNING — the recording stand-in of D1 has an undeclared existing counterpart
- Re-verified: `adapters/git/src/test/groovy/com/github/oinsio/gnomish/adapter/git/GitProcessRunnerTransferSpec.groovy:201-219` — `recordingRunner()` answers `rev-parse` with `.git` and appends `argv=`, `allow=`, `count=`/`key0=`, `askpass=`/`ssh=`, `global=` lines to a record file, exit 0; its "FR6, NFR-S2" feature (`:47-64`) runs a transfer through the typed entry and asserts the allowlist is set and the inherited configuration gone. `GitVersionCheckSpec.groovy:133-145` and `ContainerHarvestFetchSpec.groovy:166-177` record argv and then print a scripted answer. `design.md` Sync surfaces said "the change adds no parallel implementation" — false as written.
- Edit: a smaller edit than proposed, closing the same gap — extract, do not duplicate — without the second scan pattern and its two exemptions. The proposed enforcement row would make the change's `:bootstrap` scan two-clause and add two allowlisted files for a helper with two consumers; `manual-sync-pairs.md` asks only for the shared abstraction (preference 1), which the extraction provides, and the design makes no "only recorder" single-owner claim that would need a table row. Locations: `design.md` D1 — the transfer-environment paragraph now says the key-resolution `rev-parse` is answered and not recorded (as `recordingRunner()` does today), names `RecordingGit` as extracted from `GitProcessRunnerTransferSpec.recordingRunner()`, which builds through it with its five record lines (so the caller names each record line and the subcommands answered before recording), makes the existing "FR6, NFR-S2" feature the first killer with a new feature only on a measured cause, and classifies the two `fakeGit` helpers as scripted-answer stand-ins; `design.md` Sync surfaces — replaces "the change adds no parallel implementation" with the extraction statement and the classification; `tasks.md` 2.1 — extract `RecordingGit` from `recordingRunner()` and rewire that spec to it, assertions unchanged (NG3), `GitProcessRunnerTransferSpec` green in Verify; `tasks.md` 2.3 — rewritten to make the existing FR6 feature the fast killer, adding a new feature only if the scoped PIT run still records it slow, with the cause stated; the old "second block" wording is replaced by the transfer's block, consistent with D1; `proposal.md` Impact, `adapters/git/src/test` bullet — `RecordingGit` is extracted from `GitProcessRunnerTransferSpec`'s recording script, which is rewired to it.

### 3 — applied
- Recommendation: WARNING — the `StallingGit` builder surface cannot express two of its eight listed consumers
- Re-verified: `TaskBranchLocatorSpec.groovy:299-313` — `rev-parse --git-common-dir` → `.git`, any other `rev-parse` → exit 1, `remote` → 128, default exit 1; `test-fixtures/.../StallingGitFixture.groovy:84-99` — `rev-parse` qualified by `$2`, `ls-remote) cat …; exit "$(cat …)"` read at run time, and on `push` the attempts line, the hook and the started marker run before `sleep`. `design.md` D4 listed only `answer(String subcommand, String stdout, int exit)` and `markOnStall`, and said "the hook stays a spec-side `answer` line", which would answer `push` instead of stalling it.
- Edit: `design.md` D4 builder paragraph — adds `beforeStall(String shellLine)`, defines `markOnStall` as `beforeStall` of a `touch`, `answer(List<String> argvPrefix, …)` in declaration order with the single-subcommand form as its one-element case, and `answerWith(String subcommand, String shellFragment)` for file-backed answers, with the javadoc caveat that it is not a second way to spell a stall; `design.md` D4 table — the `TaskBranchLocatorSpec` row lists the qualified `rev-parse` rows and `answer('remote', '', 128)` (an `answerWith` if the spec reads the stderr text — the original script prints one); the `StallingGitFixture` row uses `beforeStall` for the attempts line, the hook and the marker, the qualified `rev-parse` rows and `answerWith('ls-remote', …)`, and "the hook is a `beforeStall` line"; `tasks.md` 1.2 — the builder surface lists the new options and `StallingGitSpec` gains one Verify case per new option; `tasks.md` 1.4 — names the options `StallingGitFixture` builds through.

### 4 — applied
- Recommendation: SUGGESTION — the sweep of task 5.2 misses the helper name `stallingNetworkGit()`
- Re-verified: `TaskBranchLocatorSpec.groovy:299` — `private Path stallingNetworkGit()`; `tasks.md` 5.2 grepped `stallingGit()\|stallingOn(` only.
- Edit: `design.md` D4 table — the row is named `TaskBranchLocatorSpec.stallingNetworkGit()` (writes `stalling-network-git.sh`); `tasks.md` 5.2 — the grep adds `stallingNetworkGit()`. A local reference fix, trivially safe.

### 5 — applied
- Recommendation: SUGGESTION — `own-git-invocation-policy` is named as an active change but does not exist
- Re-verified: `ls openspec/changes/` and `openspec/changes/archive/` hold no such change; `git log --all -- openspec/changes/own-git-invocation-policy` is empty; the name occurs only in this change's three artifacts. `add-subprocess-access-log` is active.
- Edit: `proposal.md` FR1 — "survives a later rewrite of `GitProcessRunner.execute` (`add-subprocess-access-log`, active, minimizes the child environment; any later routing of the argv through one invocation policy)"; `proposal.md` Impact, sequencing bullet — sequenced before `add-subprocess-access-log` only; `design.md` Context, last bullet, and Risks bullet — name `add-subprocess-access-log` and "any later rewrite"; `tasks.md` preamble — before `add-subprocess-access-log` only. Reference fix only; no ordering of a real change moves.

### 6 — applied
- Recommendation: SUGGESTION — the count of hand-written stall scripts drifts between seven, eight and nine
- Re-verified: `design.md:30` said "nine times", `design.md:112` "the module held seven", `proposal.md:19-22` counted two helpers plus five scripts; the D4 table holds nine module-side scripts — six rewired, two stand-in exemptions, one tracing wrapper.
- Edit: `design.md` Context — "nine scripts in the module (eight stand-ins and one tracing wrapper), plus the two traits"; `design.md` D4 Rationale — "the module held eight stand-ins"; `proposal.md` Why — "six more scenario scripts" (stand-ins only; the wrapper is not a stand-in). Wording only.

### Other edits
None.

### Validation
- `openspec validate kill-expensive-mutants --strict`: Change 'kill-expensive-mutants' is valid

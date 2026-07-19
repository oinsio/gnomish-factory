# Tasks: add-git-workflow

TDD throughout (`.claude/rules/testing.md`): each task pairs a failing Spock spec
with the code that passes it; spec descriptions carry FR references. FR/NFR/UX/M
are proposal.md ids; D-references are design.md. Depends on add-agent-executor:
`state.json` inner forms mirror status-report v1 *after* its `tokensByModel`
rework (its section 10), and task 3.9 lands inside the agent-cli live loop that
change builds — implement those parts only once add-agent-executor is done.

## 1. Contracts and ports

- [x] 1.1 Add `TaskRepository` port in `app/port` (create task, append decision, record outcome) with doc traceability — FR1
- [x] 1.2 Add state-file DTOs + Jackson mappers for `task.json` v1 (task context, decisions, outcome, lastEscalation) with Spock round-trip specs — FR3, FR4
- [x] 1.3 Add state-file DTOs + mapper for `state.json` v1 (position, attemptsUsed, attempts, totals; forms per status-report v1) — FR3, FR4
- [x] 1.4 Version-gate reading: unknown fields ignored, unknown version → refusal with a clear error naming the file and version, shared by resume and `status`/`usage` (spec-driven) — FR4
- [x] 1.5 Add `trace.jsonl` line writer for `attempts/<stage>/<round>/` (nothing reads it in v1 — resume never consults the trace, design D5) — FR3

## 2. Git plumbing

- [x] 2.1 Git subprocess runner (command, cwd, error surface) + local bare-repo Spock fixture helper — FR2
- [x] 2.2 taskId sanitization to branch/dir name (deterministic rules, invalid-id rejection) with data-driven spec — FR2
- [x] 2.3 Branch creation from clone state / `--base`, base commit recorded in `task.json` — FR2, FR7
- [x] 2.4 Worktree manager: create-or-reuse under `~/.gnomish/worktrees/<project>/<task>/`; resume on a machine without the worktree materializes it — FR6, FR8
- [x] 2.5 Worktree cleanup by outcome (Completed → remove, Escalated/Paused → keep, Aborted → always keep) + `git worktree prune` at runner start — FR6
- [x] 2.6 Task branch locator: local → remote-tracking → narrow fetch of exactly `gnomish/<task>` → "not found"; shared by resume and inspection — FR8, FR13
- [x] 2.7 Service commit-message scheme (`gnomish: round <stage>#<n>` / `gnomish: task <event>` / `gnomish: salvage` / `gnomish: cleanup`) as shared constants with spec — FR2 (Q1, D14)

## 3. Git persistence adapter

- [x] 3.1 Git `AttemptPersistence`: round = commit of whole tree (changes + state.json + trace), messages per 2.7, persist failure → throw — FR2, NFR-R1
- [x] 3.2 Run the shared `AttemptPersistence` production contract suite against the git adapter (port-contract rule, `.claude/rules/testing.md`) — FR2
- [x] 3.3 Round-boundary protocol checks (on branch, ancestry, `.gnomish-task/` untouched) → violation throws — FR12
- [x] 3.4 Git `TaskRepository`: create branch + first `task.json` commit, append decision, record outcome/escalation (messages per 2.7) — FR1
- [x] 3.5 Outcome protocol in `task.json`: null during a visit, reset to null in the commit carrying the resume decision, `lastEscalation` kept separately — FR5
- [x] 3.6 Cleanup commit removing `.gnomish-task/` on Completed: tip clean, every round still reachable in history — FR15 (M4)
- [x] 3.7 Best-effort push after round; silent when no remote; failure → WARN and continue — FR11
- [x] 3.8 Push safety rules: exact refspec `origin gnomish/<task>`, never `--force`, ancestry pre-checks — NFR-S1
- [x] 3.9 Mid-round push trigger in the agent-cli live loop on tip movement (coordinated with add-agent-executor) — FR11
- [x] 3.10 Adapter logging with taskId/stage/round context for every skipped/failed push, salvage, and protocol-check failure; logs stay in `~/.gnomish/logs/` and never enter the branch — NFR-O2

## 4. Run CLI rework

- [x] 4.1 Argument parsing: rename `--project` → `--dir` (default stays current directory), add `--mode git|in-place` (default git) — FR7
- [x] 4.2 Parse git-only flags `--base <ref>`, `--resume <task>`, `--discard-work`; exclusion matrix (`--resume` vs `--task`/`--task-file`/`--task-id`/`--from-stage`, git-only flags vs `--mode in-place`) → usage error exit 2 — FR7, FR8 (Q2)
- [x] 4.3 Preserve in-place mode as explicit legacy path with the in-memory reminder — FR7, UX4
- [x] 4.4 Git-mode wiring: create branch + worktree, print branch and worktree path upfront — FR7, UX1
- [x] 4.5 Git-mode workspace hygiene: decision-file temp dirs and logs stay outside the worktree, so round commits contain only gnome changes and `.gnomish-task/` — NFR-S2
- [x] 4.6 Resume bootstrap: locate the branch via 2.6, load + version-gate `task.json`, materialize the worktree — FR8
- [x] 4.7 Outcome-driven continuation: escalated → decision dialog, paused → confirmation, null → continue from recorded position, completed → report and exit 0; dialogs mirror the in-process ones — FR8, UX2
- [x] 4.8 Divergence reconciliation on resume (equal/behind/ahead/diverged) — FR9, NFR-R3
- [x] 4.9 Salvage default (service commit, not a round) and `--discard-work` reset to last round — FR10

## 5. Inspection CLI

- [x] 5.1 Subcommand dispatch in the entrypoint: `run` | `status` | `usage`; manual-run exit-code families reused — FR13, FR14
- [x] 5.2 Branch state reader: `.gnomish-task/` files via `git show` (locator 2.6), version-gated via 1.4 → StatusReport with live-only fields null; no worktree, no checkout, no local branch — FR13, NFR-O1
- [x] 5.3 `gnomish status --dir <clone> <task>`: render text/JSON v1 from the branch state reader, show the worktree path — FR13, FR6
- [x] 5.4 List mode: table over local + remote-tracking `gnomish/*` branches, deduplicated per task (local tip preferred) (task, stage, attempts, outcome) + `--json` — FR13
- [x] 5.5 `gnomish usage` reconstruction: chronological walk of `state.json` history, row per new AttemptRecord (attempts list grew or position advanced), service commits yield no rows — FR14, NFR-C1
- [x] 5.6 `gnomish usage` renders: text stage/round table with totals; `--json` full granularity (tokensByModel, judge votes) under its own `"version": 1` mini-contract — FR14, NFR-C1
- [x] 5.7 "Task not found" path (missing/deleted branch) without warnings or stack traces; settle its exit code inside the tool-failure family — FR13, UX3

## 6. Cross-cutting verification

- [x] 6.1 Contract test: StatusReport from state files ≡ from live events, anchored by `status-report-v1.reference.json` — FR4 (M2)
- [x] 6.2 Kill/resume integration spec on local bare repos: interrupted round → salvage → completion — FR8, FR10 (M1)
- [x] 6.3 Interrupted honesty: rounds present + outcome null renders as in-progress/interrupted in `status` — NFR-R2
- [x] 6.4 Read-only guarantee specs: status/usage leave the clone unchanged except narrow fetch; git-mode run leaves the clone's working copy, index, and current branch untouched — FR7, FR13, FR14 (M3, G4)
- [x] 6.5 Gitea Testcontainers harness: container fixture with HTTP auth remote, Spock lifecycle, skip with a clear message when Docker is absent — FR11 (G2 infra)
- [x] 6.6 Gitea E2E scenarios: best-effort push over HTTP auth after rounds; cross-instance resume from origin — FR11, NFR-R3 (G2)
- [x] 6.7 Full quality gates green: `./gradlew check` incl. JaCoCo + PIT targets — all FR

## 7. Documentation

- [x] 7.1 Update README/docs: run modes and the `--project` → `--dir` rename, worktree locations, resume, squash-merge recommendation for gnome PRs — FR15, UX3
- [x] 7.2 Document `gnomish status` / `gnomish usage`: list mode, JSON output incl. the usage `"version": 1` mini-contract, "task not found" after a merged PR is normal — FR13, FR14, UX3

## 8. Review follow-ups

Remediation of the code-review findings on this change. Pre-existing >200-line
files from earlier changes (`PipelineLoader` 229, `AgentProcessLauncher` 216,
`StreamJsonParser` 202) are out of scope here — tracked in the separate
`fix-oversized-adapters` change.

- [x] 8.1 Bring `GitResumeRunner.java` (214) under the 200-line cap: drop its private `readFinalState` in favour of the existing `GitFreshTaskSupport.readFinalState` (already used by `GitResumeContinuation`) and move `readTaskJson` into a shared reader; tighten class javadoc, keeping all `Implements …` links — process-invariants file-size cap
- [x] 8.2 Bring `GitResumeContinuation.java` (212) under the 200-line cap: condense class/method javadoc (keep the "extracted for file-size" note, traceability tags, and throws contracts); no behaviour change — process-invariants file-size cap
- [x] 8.3 Add a code-side `Implements NFR-R2 of add-git-workflow` tag on the class that renders an outcome-null branch as honest in-progress status (the path `StatusInterruptedHonestySpec` exercises) — NFR-R2
- [x] 8.4 Dedicated WARN-message specs in `BestEffortPushSpec`: each of the three WARN branches (HEAD off branch, previousTip not ancestor, push failed) logs exactly one `WARN` whose message carries taskId/stage/round/branch context — NFR-O2
- [x] 8.5 Split `GitResumeRunnerSpec.groovy` (533) by outcome: extract shared setup/builders into `GitResumeSpecBase` (mirroring `StatusReconstructionSpecBase`), then `GitResumeBootstrapSpec` (bootstrap cases) and `GitResumeOutcomeSpec` (run/outcome cases) — testing rule "one file, one thing"
- [x] 8.6 Quality gates green after the split/edits: `./gradlew check` incl. JaCoCo + PIT — all FR

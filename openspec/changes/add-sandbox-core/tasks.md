# Tasks: add-sandbox-core

Order follows the migration plan (design): behavior-neutral port refactor
first, container mechanics second, egress and hardening third, E2E last.
Before group 4: re-verify tool state per Q1 (mitmproxy SNI mode, Docker
ladder review note in explore notes).

## 1. Environment port and host adapter (behavior-neutral)

- [ ] 1.1 Define `TaskExecutionEnvironment` port (materialize with optional factory-chosen commit pin + per-environment scratch area / exec with optional stdin / putFile / readFile with size cap, paths under working copy or scratch only / harvest / dispose, streamed exec result; file ops boundary-only; no snapshot op) with javadoc traceability (FR1, NFR-S3)
- [ ] 1.2 Define adapter capability passport model and `SecretsProvider` port with env/file adapter (FR14, FR18, NFR-S1, D12)
- [ ] 1.3 Extract host adapter over existing worktree + `ProcessBuilder` mechanics; passport declares no isolation (FR2)
- [ ] 1.4 Route `AgentProcessLauncher` and `CommandProcessRunner` consumers through the port; forbid direct spawning by design (FR4)
- [ ] 1.5 Port-level contract spec suite (Spock) run against the host adapter; existing app/e2e specs stay green (M1)
- [ ] 1.6 Route tracker-token resolution through `SecretsProvider` (github-tracker delta); `GNOMISH_GITHUB_TOKEN` becomes the env adapter's backing name; credential names are refused in child-env allowlists (FR18, NFR-S1)
- [ ] 1.7 Prompts via stdin in launcher and judge voter (all modes); update fake-agent contract specs — the one deliberately non-behavior-neutral point of this group (FR24, D18)

## 2. Pipeline config: sandbox declarations

- [ ] 2.1 Extend stage model + loader with Mechanism sandbox declarations: needs, `requires-fresh`, `verify-in` (FR12, FR13)
- [ ] 2.2 Tighten-only validation: reject host/adapter requests and weakenings as located `ConfigError`s (FR14)
- [ ] 2.3 Loader specs: declarations load typed; violations reported; defaults applied (FR12–FR14)
- [ ] 2.4 External check declaration gains optional pin paths (definition files of the check); repo-relative data, never read by the loader, exempt from the `.gnomish/` traversal rule; reject absolute / non-normalized (`.`, `..`) forms as located `ConfigError`; typed into the model, loader specs (FR16)
- [ ] 2.5 Pipeline-law binding per invocation: law read at invocation start from the factory clone of the base branch (git modes) or the workspace snapshot (in-place), frozen for the invocation; control files and judge criteria read from the law source, never lazily from the working copy (`ControlFilePreflight` rework); contract test proves gnome-branch edits never affect the running task (FR19, NFR-S2, D14)

## 3. Binding resolution and reconciliation

- [ ] 3.1 Factory config: adapter bindings per stage, `factory.sandbox.image`, `factory.sandbox.runtime`, resource limits, egress allowlist, child-env passthrough names (FR3, FR7, FR9, FR10)
- [ ] 3.2 Needs-vs-passport reconciliation, fail-closed with named unmet need; container default binding, no silent host fallback (FR14, G2, UX2)
- [ ] 3.3 Segment computation: contiguous equal-binding stages share an environment; boundary = harvest → dispose → materialize; `requires-fresh` splits segments (FR12, FR13, NFR-P1)
- [ ] 3.4 Specs for reconciliation, defaulting, and segment lifecycle incl. in-segment reuse without re-materialization (M1, NFR-P1)

## 4. Container adapter

- [ ] 4.1 Docker CLI runner (subprocess, like git): create/inspect/remove network, volume, container; factory labels on every object (FR3)
- [ ] 4.2 `exec` into the container with streamed stdout/stderr + exit code; workdir in the volume working copy (FR3, FR4)
- [ ] 4.3 Resource limits from config on `run` (`--cpus`, `--memory`, `--pids-limit`, volume size) (FR10)
- [ ] 4.4 Idempotent `dispose` (container+volume+network); startup orphan sweep by label (FR11, NFR-R2)
- [ ] 4.5 Contract suite from 1.5 passes against the container adapter (Docker-gated integration spec) (M1)
- [ ] 4.6 Read-only control surfaces inside the container: agent-CLI config, shell rc files, baked proxy/CA/build configs root-owned or `:ro`-mounted; spec asserts in-box writes to them fail (FR20)
- [ ] 4.7 Runtime-outage classification: Docker daemon unavailable at materialize or mid-task = infrastructure failure, no attempt burned; persistent outage escalates cannot-execute (NFR-R1)
- [ ] 4.8 Keep semantics for ended tasks: stop the container, retain volume + network; serve cleaner disposes aged environments via the port using runtime-metadata age (factory-serve delta, NFR-R2)

## 5. Git mechanics: clone, harvest, salvage

- [ ] 5.1 Materialize = `git clone --no-hardlinks` from the factory clone into the environment; agent identity, `gc.auto 0`, no remote/creds inside (FR3)
- [ ] 5.2 Harvest = fixed-refspec ff-only `--no-recurse-submodules` fetch from the environment; precedes any push; rate-limited tip polling (event-driven detection, if enabled, watches `.git/logs/HEAD` — not packable refs — and only wakes the poller) (FR5)
- [ ] 5.3 Resume materializes through the bound environment from the branch alone; salvage via in-box commit + harvest, with lost-environment fallback; the tracker-take revocation path reuses the same salvage → harvest → factory-side push mechanics (FR6, tracker-take delta)
- [ ] 5.4 Factory git hardening: bare-object reads of gnome branches, empty `core.hooksPath` on all factory-managed copies (FR17)
- [ ] 5.5 Specs with local bare repos: no-hardlink independence, rewritten-history refusal, hooks don't cross, salvage paths, resume-by-second-instance (M4)
- [ ] 5.6 Snapshot-first round protocol: in-box snapshot commit (hooks off, as gnome user) → harvest → verification against the attempt commit → state files via putFile → state commit → harvest with read-back and parent-check; resume treats snapshot-without-state as interrupted verification, no attempt burned (FR21, FR22, D15, D16)
- [ ] 5.7 Boundary verification factory-side on harvested refs with the `decisions/<stage>-a<attempt>.json` carve-out; in-branch decision protocol wired through `$GNOMISH_DECISION_FILE` with stale-name exclusion (FR21, FR23, D17)
- [ ] 5.8 `gitobjects` library package (top-level, beside `domain`/`app`/`adapter`): `GitObjects` facade (resolveRef / readBlob with size cap / commit), sealed `TreeEdit` (PutFile mode 100644 / DeletePath), temp-index plumbing chain (read-tree → hash-object --stdin → update-index → write-tree → commit-tree), atomic CAS `update-ref`, path validation (no absolute paths, no `..`, no `.git/**`), caller-supplied identity/timestamps/message; JDK + SLF4J API only — no factory, Spring, or Jackson imports; ArchUnit boundary rules in both directions (introduces ArchUnit per ADR 0001) (FR25, D19)
- [ ] 5.9 `gitobjects` Spock specs on real temp repositories: works on a bare repo (no-checkout proof), hooks never fire, stale-tip CAS refusal leaves the ref unchanged, untouched subtrees keep identical tree hashes, directory delete preserves history, path-validation refusals, byte round-trip and read size cap, deterministic commit ids from fixed metadata, temp-index cleanup on failure (FR25, D19)
- [ ] 5.10 Route sandboxed lifecycle writes through `gitobjects`: branch creation with `task.json`, resume decision before materialize, outcome recording incl. aborted-on-last-harvested-tip with a dead or quarantined box, Completed outcome + cleanup after dispose; host mode unchanged (FR25, D19)

## 6. Egress guard and self-check

- [ ] 6.1 Guard lifecycle: mitmdump (SNI/CONNECT mode, no TLS opening) as a factory-managed container in task network + bridge; allowlist rendered from config; DNS via guard (FR7)
- [ ] 6.2 Structured denial log → findings in the task report (metadata only) (NFR-O1)
- [ ] 6.3 In-box self-check before the first gnome-product process in every materialized environment (round and fresh-box verification/judge alike; the probes themselves run via exec): direct egress fails / non-listed denied / listed passes / isolation matches passport; failure = infrastructure failure (FR8)
- [ ] 6.4 Guard outage classification as infrastructure failure + guard restart (NFR-R1)
- [ ] 6.5 Specs: allowlist enforcement (WireMock/local guard), self-check verdicts, denial findings (M2)

## 7. Env allowlist

- [ ] 7.1 Layered allowlist construction for both adapters: child env = adapter base ∪ passthrough names (values live from the factory env at exec) ∪ factory-set vars; container base empty (`--env` only — the image supplies runtime env), host base = fixed documented set via `environment().clear()` + puts; rewrite `AgentProcessLauncher`'s javadoc, which documents the replaced inherit-everything-minus-scrub behaviour; credential names in passthrough refused as `ConfigError`; applied names (never values) logged at debug (FR9, D6)
- [ ] 7.2 Specs asserting no factory env var leaks into `exec` children in either adapter (M3)
- [ ] 7.3 Findings-file path allocated in the environment's scratch area and read back through `readFile` (size-capped) in both adapters; container reads stream via exec, never host-side archive extraction (FR1, NFR-S3)

## 8. Verification hardening

- [ ] 8.1 Findings funnel component: strict verdict schema (judge parse failure = infra failure), size caps at read/poll, ANSI/control sanitization, fenced tracker rendering with escaped mentions (FR15)
- [ ] 8.2 Route judge/external/command findings through the funnel; engine branching untouched (FR15, Q3)
- [ ] 8.3 Pin-check guard component wrapping any `ExternalCheckClient`: pin set = law-declared paths ∪ adapter-contributed paths; byte-compare vs base branch as bare object reads at the attempt commit; diff = Fail with diff findings, adapter never invoked; empty union passes vacuously (FR16, D10)
- [ ] 8.4 Guard wired into every assembly that binds an external check client (interactive included); contract case in `ExternalCheckClientContract`: pinned-diff refusal and vacuous pass; early-substitution-caught-at-point-of-use spec (FR16, D10) — also close the wiring gap carried from add-external-check-github-actions Q5: construct `GithubCheckExternalClient` from factory config (base-URL config key, `SecretsProvider` token) and inject it into the stage engine behind the guard, making the adapter operator-enableable; replace the adapter-local `GithubCheckWorkspace` stand-in with the concrete workspace type carrying the attempt commit; `GNOMISH_GITHUB_ACTIONS_TOKEN` becomes the env adapter's backing name for the external-check token and is declared as a credential name refused in child-env allowlists (FR26, D15, github-external-check delta)
- [ ] 8.4a Extend `PollStatus.Pass` to carry the platform run URL (domain-model change) and preserve it into the recorded check result, so a green external check's run link reaches the tracker report the same way a Fail's does today; adapter fills it from the authoritative run; closes add-external-check-github-actions Q4 (NFR-O2, stage-engine + github-external-check deltas)
- [ ] 8.4b External-check push precondition: before the poll loop starts, verify the attempt commit is delivered to the remote, re-attempting the push; an undeliverable commit classifies the check as CannotVerify (infrastructure failure, no attempt burned), never a poll-timeout quality failure (FR21, stage-engine delta)
- [ ] 8.5 Model-output write confinement: symlink-resolving path guard, `.git/**` refusal, contract test (FR17)
- [ ] 8.6 Funnel/pin-check/confinement specs incl. injection, escape-sequence, truncation, traversal cases (FR15–FR17)
- [ ] 8.7 Judge hardening: input artifacts wrapped in hard delimiters as data; sandboxed judge votes run in a fresh environment materialized from the attempt commit (votes of one attempt may share it); specs for delimiter integrity and fresh-box judging — engine vote/majority mechanics untouched (FR15, D9)
- [ ] 8.8 Builtin checks (`files_exist`, future schema checks) evaluate the attempt commit via bare object reads in sandboxed mode — one implementation for all adapters; host modes keep filesystem checks (FR21, D15)

## 9. E2E, image, docs

- [ ] 9.1 Reference image recipe: JDK, git, agent CLI, baked CA, JVM/Gradle proxy configs, registry endpoints as build args (UX4, D7)
- [ ] 9.2 E2E (Testcontainers + Gitea): full pipeline in container mode — clone, rounds in box, harvest, outside push (M2)
- [ ] 9.3 E2E: self-check catches broken isolation; in-box env holds no tracker token / host vars (M2, M3)
- [ ] 9.4 E2E: kill mid-round, second instance resumes/salvages in container mode; kill between snapshot and state commit — resume re-verifies; pending decision request recovered from the branch (M4, FR21, FR23)
- [ ] 9.5 Operator docs: sandbox config, host-mode passport honesty (isolation: none; the env allowlist bounds variables, not filesystem access), egress-allowlist maintenance, host env passthrough recipes (`SSH_AUTH_SOCK` for private SSH dependencies; reward-hacking caution: a missing env var fails checks as a quality failure and can push the gnome to route around it), Docker-inside ladder (step 0 = CI external check; `gnomish/*` workflows without privileged secrets; step 1 neighbor stacks arrive with change D — see NG5), `verify-in: fresh-box` recommendation for final quality gates, limits tuning, interim threat-#45 guidance (disable provider-side web tools until change B) (UX1–UX5, NG5, NG7)

## 10. Gates

- [ ] 10.1 `./gradlew check` green: Spock, JaCoCo, PIT on changed Java scope, Error Prone/NullAway, Spotless
- [ ] 10.2 Traceability grep: every FR/NFR/UX of add-sandbox-core has an implementing entity in code or specs

# Tasks: add-base-ref-resolution

## 1. `:baseref` leaf module — pure resolution policy

- [x] 1.1 Create the `:baseref` Gradle module with the project conventions
      (PIT 100%, `layering { allowedProjects = [] }`, no external deps) and
      verify `./gradlew projects` lists it and `:baseref:check` passes empty
      (FR10, module-layering delta)
- [x] 1.2 TDD the menu value types and pattern grammar: menu entry
      (pattern + role `development`/`release`, role defaults to
      development), pattern compile/match semantics for literal names and
      `*` series; Spock data tables over match/no-match cases (FR1)
- [x] 1.3 TDD the module's own base-designator input value
      (absent | single | conflict) and its validation against the menu;
      the classification from candidates is NOT here (task 3.1) (FR3)
- [x] 1.4 TDD `BaseRefResolver`: priority order (explicit base > designator
      validated against menu > configured default > repo default branch >
      local HEAD in manual mode only), decision value
      `(ref, rule, reason)`, underdetermined classification (out-of-menu,
      conflict, autonomous with no remote/default); data-driven spec over
      the full priority matrix including the defensive
      empty-menu-with-designator escalation (FR4, FR5)
- [x] 1.5 Verify `:baseref:check` is green with 100% mutation score and the
      layering gate rejects a probe dependency (revert the probe); run
      `./gradlew :baseref:check`

## 2. Config: `base:` block in `.gnomish/config.yaml`

> Landed under the pre-D16 vocabulary (root-level `base:`, `menu`). The
> items below stay checked as history; section 9 moves the section to
> `task-branch.base` / `allowed` and renames the concept in code.

- [x] 2.1 TDD the loader DTO + validation for the `base:` section per the
      pipeline-config delta: `type` discriminator (only `patterns`,
      defaulting when absent), `default`, `menu`; located `ConfigError`s
      for unknown type, unknown keys (including a stray `select`), invalid
      pattern, bad role, default outside the menu; absent section loads as
      empty config (FR1, UX1)
- [x] 2.2 Map the DTO into `:baseref` value types (compiled at load) and
      verify via loader specs that the typed definition exposes menu,
      roles, and default; aggregation with unrelated core errors covered
      (FR1)
- [x] 2.3 TDD the trusted-tier startup check: when the tracker adapter
      factory reports designator kind `base` among its configured kinds
      and the menu is empty, loading fails with a located `ConfigError`
      naming the rule's location and `base.menu`; a rule with a non-empty
      menu and an empty menu with no rule both load (FR3, UX1,
      pipeline-config "Selection rule without a menu is a load error")

## 3. Tracker port: designator facts and contract suite

- [x] 3.1 TDD the designator vocabulary and the one classification function
      in `:gnomish-plugin-api` (candidates → absent | single | conflict,
      equal duplicates collapse, conflicts keep every value); add
      `designators` to `TrackerTask` and the configured-kinds query to the
      `TrackerAdapterFactory` seam (default: no kinds) (FR3)
- [x] 3.2 TDD the GitHub adapter end: `tracker.github.designators` map
      validated on the subsection seam (kind → regex compiles, exactly one
      capture group, located errors like `labels`), candidate extraction
      from issue labels in `GithubTaskFetcher`, configured kinds reported
      through the factory; in-memory adapter: a test operation that sets a
      task's designators, no subsection (FR3)
- [x] 3.3 Add the three-shape designator coverage to the contract suite
      (absent / single / conflict / equal duplicates for kind `base`) and
      verify both adapters pass it unchanged; map the port shape onto the
      `:baseref` input value in `:application` (FR3)

## 4. Git adapter: discovery and refresh fetch

- [x] 4.1 TDD default-branch discovery (`ls-remote --symref` parse) in
      `:adapters:git` under the bounded-network rules; specs cover a
      renamed default branch, a no-origin clone (refusal result, not a
      guess), and scrubbed failure output (FR5, NFR-P1)
- [x] 4.2 TDD the narrow base refresh per D11: branch →
      `+refs/heads/<n>:refs/remotes/origin/<n>`; tag →
      `refs/tags/<n>:refs/tags/<n>` without force, a diverging local tag
      yielding the task-level refusal with both commits; SHA →
      `cat-file -e <sha>^{commit}` first, fetch by SHA only when absent,
      verify again, no ref written; every call with `--no-tags`,
      `--no-write-fetch-head`, empty `--refmap=`, full depth; the SHA read
      back from the destination ref or object, never `FETCH_HEAD`; assert
      against a local bare remote that HEAD, index, working tree,
      `refs/heads/*`, pre-existing `refs/tags/*` and unrelated
      `refs/remotes/origin/*` are untouched, and that a non-standard
      `remote.origin.fetch` moves nothing extra (FR6, D11)
- [x] 4.3 Wrap discovery + refresh in `GitInfrastructureRetry` with the
      infrastructure-failure outcome and verify on virtual time that
      retries are bounded and a dead remote yields the typed failure, not
      an exception leak (FR9, NFR-R1)

## 5. Law source by ref (D12–D14)

- [x] 5.1 TDD tree listing in `:gitobjects`: `listTree(commit, path)` over
      `git ls-tree` reporting regular files, directories, and symlink
      entries distinctly; spec covers an absent path and a blob-vs-tree
      mismatch (FR11)
- [x] 5.2 TDD the law source abstraction (read file / is regular file /
      list directory, relative to a root) with the working-tree and
      git-objects realizations; one contract spec runs both over identical
      trees; the git-objects realization refuses symlink entries and applies
      the lexical half of `PathSafety` as its whole traversal guard (FR11,
      D12)
- [x] 5.3 Route `GnomishFiles`, `ReferencedFiles`, `PipelineLoader`, and
      `PipelineLawReader.freeze` through the law source; existing loader and
      reader specs pass unchanged over the working-tree realization; add
      the git-objects twin of `PipelineLawReaderSpec` (FR11)
- [x] 5.4 Replace the `"HEAD"` pin ref in `RunAssembler` with the law commit
      carried by the assembly: `assemble` takes a law binding (working tree
      at a project root, or git objects at a law commit) and, separately, the
      repository root for the pin guard — two typed arguments, never one
      `Path`; the binding is the one owner of the law-root rule
      (`workingTree(root)` → `WorkingTreeLawSource(root/.gnomish)`,
      `atCommit(repoRoot, lawCommit)` → `GitObjectsLawSource(git, lawCommit,
      ".gnomish")`), no call site resolves `.gnomish` itself; every
      `assemble` call site passes a law commit (take/serve/container/resume
      paths) or the working tree (in-place, manual `run` without `--base`);
      amend `WorkingTreeLawSource` to refuse symlink entries like the
      git-objects realization and extend `LawSourceContractSpec` to assert
      the same verdict from both over one tree; delete the duplicated
      clone-root law files from the 15 fixture sites (e.g.
      `TwoInstanceTakeFixture`, `TakeDeathAndRecoverySpecBase`,
      `.gnomish-fixtures/e2e/stages/`) and re-point the in-code
      `StageDefinition` specs (`GitModeLawBindingSpec`,
      `ResumeSpecFixtureBase`, `ContainerGitModeRunnerSpec`,
      `SandboxLifecycle*E2ESpec`, `KillPointWorlds`, …) at
      `.gnomish/instructions.md`; give `ollama-e2e` its single `.gnomish/`
      copy and a real assertion; add the `Kept in sync with` markers to both
      ends of `TakeEngineExecution` / `TakeContainerEngineExecution`
      (invariant line names the law-binding argument) and remove their
      `manual-sync-pairs.md` registry row; build gates: no `"HEAD"` literal
      in law-source or pin wiring, and no production reader opens
      `.gnomish/**` except through `LawSource` (FR11, M5, D12)
- [x] 5.8 Close the 5.4 review findings before 5.5 (sequenced here because
      5.5–5.6 build on the binding's shape): (a) `LawBinding` carries its
      repository root (`workingTree(repoRoot)`, `atRevision(repoRoot, rev)`,
      `atCheckout(repoRoot)`); `assemble` and the `Take*EngineExecution`
      records drop the separate `repositoryRoot` and return under the
      seven-parameter rule; the binding is the one owner of both the law-root
      rule and the "law belongs to this repository" invariant, asserted by
      `LawBindingSpec`; (b) the pin is a typed peeled commit id: `LawSources`
      returns it, `PinCheckedExternalCheckClient` takes it and performs no
      `resolveRef` of its own — a spec moves the clone's `HEAD` after binding
      and asserts the guard still compares against the bound commit; (c) one
      shared segment walk classifies a law path for both realizations over
      per-realization tree entries (the `ResumeMechanics<B>` shape), any
      symlink entry at any segment is REFUSED regardless of target;
      `GitObjectsLawSource` no longer reports a symlinked directory as
      ABSENT; `LawSourceContractSpec` gains the symlinked-directory case
      asserting one verdict from both, and `WorkingTreeLawSource`'s javadoc
      claim of parity becomes true; (d) extract the "open git objects, open
      law, freeze, build the pin guard" seam out of `RunAssembler` into one
      class that owns it, bringing `RunAssembler` under the 200-line cap by
      moving a responsibility, not lines; `LawRootBoundarySpec` gates stay
      green (FR11, M5, D12)
- [x] 5.5 TDD startup-plus-per-task loading in `serve`/`take`: the default
      branch definition is loaded and validated at startup as today; after
      resolution the task tier loads from the base SHA; a load error on the
      base parks the task with a report naming ref, law commit, and located
      errors, burns no attempt, releases no claim; `board`/`dashboard`
      unchanged (FR13, UX5, D14)
- [x] 5.6 TDD resume law binding: autonomous resume narrow-fetches the
      pinned ref name and binds from its tip; manual resume without a
      remote binds from the local ref tip; a ref that resolves nowhere parks
      with a report; add the `Kept in sync with` markers to both ends of
      `TakeResumeRunner` / `TakeContainerResumeRunner` (invariant line
      names pinned-ref tip resolution) and remove their
      `manual-sync-pairs.md` registry row (FR12, D13)
- [x] 5.7 Extend `GitModeLawBindingSpec` (or add a sibling) with: clone
      checked out at `main`, task based on `release/1.18` — law and pin
      come from the base; uncommitted clone edits play no part in take;
      manual `run` without `--base` still binds uncommitted edits (FR11,
      FR8, M5)

## 6. Application: funnel wiring, pin, and failure routing

- [x] 6.1 Wire `BaseRefResolver` into `GitFreshTaskSupport`; delete the
      null→HEAD default there and the `TaskBranchCreator.startPoint()`
      duplicate; all four fresh-start paths receive resolved refs; verify
      by existing suites plus a grep gate that no `"HEAD"` default remains
      outside the manual-run tier (FR4, FR10, M2)
- [x] 6.2 Insert resolve + base-refresh between `harden()` and
      `createTask()` in `TakeFreshClaim` and `TakeContainerFreshClaim`; add
      the `Kept in sync with` markers to both ends (invariant line includes
      the new resolve step) and remove their `manual-sync-pairs.md` registry
      row; mirrored specs cover both media; the `task-branch.base` section comes from the
      trusted tier bound at startup (task 5.5) — read through the
      git-objects law source at the refreshed default-branch tip, never
      re-read per claim (a spec asserts no default-branch fetch and no
      config read on the claim path), and the law-source contract test pins
      that source (FR2, FR6, D6, D15, sync surface)
- [x] 6.3 TDD the pin: mapper writes `(ref, sha, rule)` in the
      task-creation commit behind the version gate; the pin flows through
      both ends of the `GitTaskRepository` / `GitObjectsTaskRepository`
      lifecycle pair identically (serialization stays single-point in the
      shared `TaskJsonMapper`); legacy `baseCommit`-only
      files read as unpinned; data-driven round-trip spec over every rule
      constant plus the unknown-token forward-compat arm (FR7)
- [x] 6.4 TDD resume behavior: pinned tasks never re-resolve (no trusted-tier
      read, no designator read on resume — asserted with throwing fakes;
      the task-tier read of 5.6 is the only law read);
      a kill between claim and creation commit freezes `Claimed` with no
      branch ref; the reaper restores `Ready` on virtual-time TTL, a second
      reaper pass is a no-op, and the next claimant re-resolves from
      scratch (FR7, NFR-R1, NFR-R2)
- [x] 6.5 TDD underdetermined-input escalation: a disallowed selection and a
      conflict park the task with a report naming the found values and the
      allowed bases, no stage attempt burned; manual `run` without `--base` still branches
      from local HEAD offline with zero network calls (specs assert no
      remote invocation) (FR4, FR8, UX2, UX3)

## 7. take/serve failure handling and observability

- [x] 7.1 TDD the failure classification at the fresh-claim step: a typed
      reachability failure (connect, DNS, timeout, retries exhausted) versus
      task-level causes (missing ref, auth refusal, underdetermined
      designator); only the former is the infrastructure class; the abort
      protocol's uncaught-exception arm is asserted unreached with a
      throwing-tracker fake (FR9)
- [x] 7.2 TDD claim release on base infrastructure failure in take: plain
      `release` (label untouched; the reaper returns the task to Ready
      after the claim TTL), no abort marker, no comment, abort
      facts and backoff unchanged (seed a task with two aborts and assert
      still two); new `TakeResult` variant with exit code 16 in the exit
      mapper and a typed outcome-log line in serve (no `taskOutcome`
      ledger line, like `Skipped`); a later take
      succeeds once the fake remote recovers (FR9, M4, tracker-take
      MODIFIED exit codes)
- [x] 7.3 TDD the remote outage gate as one owner class on virtual time:
      open on a slot's infrastructure failure, consulted by the feed before
      every claim (no claim while open — assert zero tracker claim calls),
      tracker-free `ls-remote` probe on a jittered interval growing from the
      idle interval to the cap via `RestartBackoff`'s policy, close on the
      first successful probe, re-arm on a failed one; the interval resets
      to idle only on the first successful base refresh after a close
      (flapping-remote spec: close, immediate refresh failure, reopen with
      a longer interval); in-flight slots keep running; process-local (a
      fresh daemon starts closed) (FR14, NFR-R3)
- [x] 7.4 TDD gate observability: one WARN with a new `OperatorEvent` code
      on open and one ERROR code for sustained-open, both registered in the
      `:logtext` catalog per factory-logging (one code per call site, never
      reused, catalog round-trip spec extended); one INFO recovery line on
      close with duration and probe count; DEBUG + `RepeatSuppressor`
      roll-up between, with the suppression key per remote target so two
      remotes never mask each other, and every suppressor edge pinned per
      the factory-logging suppression-site rule; one ERROR past the
      sustained-open duration; `remote` snapshot section
      beside `tracker` (immediate write on transition, absent section reads
      as no gate, `version` stays 1, `SnapshotJsonMapper`/`SnapshotJsonReader`
      pair updated together); one `remoteOutage` ledger line per closed
      outage (`LedgerJsonMapper`/aggregator pair updated together);
      dashboard status-card alarm line while open and per-day outage count
      in history; extend the operator-event sync spec if a code lands in the
      domain emitters' pair (NFR-O1, NFR-O3, UX6)
- [x] 7.5 Serve end-to-end on virtual time: three slots, remote dead for an
      hour, then back — at most three claims and releases total, none after
      the gate opened, one WARN and one recovery line, the first successful
      probe precedes the first post-outage claim, every task claimable
      again with zero abort facts (M4, G5)
- [x] 7.7 Move the slot's gate signals to the base read itself (revision of
      2026-09-11): a `BaseRefGit` decoration on the slot's `TaskGit` reports
      `refresh`/`resolveForResume` outcomes to the gate as they return;
      the terminal-result mapping in the slot runner is removed, and the
      gate ignores a successful-refresh signal while open. Specs: the
      decoration's three arms on a real gate, the slot's real refresh
      observed on the gate's health, and the gate's own stale-signal
      regressions (FR14, D9)
- [x] 7.6 Integration spec against a local bare remote: zero-config serve
      claim branches from the remote default-branch tip observed at claim
      (M1); label-selected release base is fetched, validated, and pinned
      (U2)

## 8. Documentation and verification

- [x] 8.1 Add glossary entries (base ref, allowed bases —
      `task-branch.base.allowed`, *Never:* menu — designator — written
      kind-generic: a per-task selection of a given kind carried as
      tracker metadata in one of three shapes, with `base` as the first
      kind and `type` named as the next — base pin,
      law commit, law root — `.gnomish/`, the one root of stage file
      references in every medium — beside working copy root — the root of
      pin paths and artifact paths — with a table naming which manifest
      field is relative to which, trusted tier, task tier, remote outage
      gate); extend the
      existing *Task branch* entry with the `task-branch:` configuration
      section it names (D16); and the
      operator-guide section: `task-branch.base` section reference, the
      `tracker.github.designators` rule reference (and the adapter author
      guide's designator obligations), the external-automation escape
      hatch, the two configuration tiers (the trusted tier binds at
      startup — a merged change to the allowed bases needs a restart of `serve`), the
      unchanged `run` behavior (and `--base` reading law from git objects
      at the given ref), and the outage gate (its log lines, snapshot
      section, and exit code 16); verify by docs build/lint conventions
      (UX1, UX4, UX6, D10, D12, D15)
- [x] 8.2 Write `docs/adr/0007-pipeline-law-source.md`: law by ref from git
      objects, the two tiers, resume from the pinned ref tip as a recorded
      deviation from the re-run model, the rejected worktree and checkout
      alternatives; the law-root rule (`.gnomish/` in every medium, the
      load-vs-run divergence it closes, the two-root table shared with
      `enforce-artifact-contracts`, symlink entries refused in both
      realizations with the Kustomize/Argo CD precedent, and the reserved
      repository-anchored prefix as the named non-goal); reference it from
      D12 and the glossary (D12–D14)
- [x] 8.3 Traceability sweep: grep confirms every FR/NFR/UX of this change
      has at least one implementing spec or code reference, and the
      superseded D7 wording is gone from the merged spec view
      (`openspec validate --strict` passes)
- [x] 8.4 Full build green: `./gradlew check` including PIT for touched
      modules; kill-point specs for the new window pass twice (recovery
      idempotence), including a kill between the failed fetch and the claim
      release (NFR-R1, NFR-R3)
- [x] 8.5 Reconcile `docs/adr/0005-dependency-outage-accounting.md` and
      `docs/adr/0006-base-refresh-fetch.md` (both accepted with this
      change's planning, status "implementation pending") with what
      landed: exact flags, exit code, event codes, the flapping-remote
      reset; flip their status to accepted-and-implemented; reference them
      from the glossary entries (D9, D11)

## 9. Vocabulary rename: `task-branch.base` / `allowed` (D16)

> Sequenced first: finish this section before resuming 4.2, so every later
> item builds on the settled names. Sections 1–3 stay checked; their specs
> are updated in place here, not re-done.

- [x] 9.1 Move the config section: `ConfigDto` gains a `taskBranch` field
      (a `TaskBranchDto` holding `base`), the `base` subsection keeps `type`
      and `default` and takes `allowed` in place of `menu`; a root-level
      `base:` and a `task-branch.base.menu` key are unknown keys — located
      errors, no alias; error locations become
      `task-branch.base.allowed[i]` and `task-branch.base.default`, and the
      designator-without-allowed-bases error of 2.3 names
      `task-branch.base.allowed`; the loader specs of 2.1–2.3 assert the
      new shape and the two rejected draft keys (FR1, UX1, pipeline-config
      "The earlier draft shape is not an alias")
- [x] 9.2 Rename the concept in code and specs: `BaseMenu` → `AllowedBases`,
      `BaseMenuEntry` → `AllowedBase`, `BaseMenuEntryDto` → `AllowedBaseDto`,
      `DesignatorMenuSeam` → `DesignatorAllowedBasesSeam`, every `menu`
      field, parameter, constant (including the underdetermined cause for a
      disallowed selection), javadoc, error text, and Spock feature name;
      grep gate: `menu` is absent from `src/main` and `src/test` of
      `:baseref`, `:adapters`, `:application` (D16, no-jargon rule)
- [x] 9.3 `./gradlew :baseref:check :adapters:check :application:check`
      green with 100% mutation score after the rename, and every checked
      item of sections 1–3 still holds under the new names

## 10. Branch start point from the peeled law commit (D12 revision 2026-09-10, D7 kind)

> Sequenced before `add-pipeline-entry-precondition` 4.1 and before
> `add-pipeline-routing` resumes: both build on the `createTask` signature
> revised here. Context: the post-implementation review reproduced a task
> branch created from a stale local `main`, a failed `createTask` for an
> origin-only branch, and a planted local tag winning the start point — all
> after a successful refresh (FR15, NFR-S1).

- [x] 10.1 TDD `LawBinding.atCommit(ObjectId)`: a binding that already holds
      the peeled commit; `LawSources.open` returns it as the law commit with
      no `rev-parse`, and the git-objects realization is rooted at it exactly
      as for `atRevision`; `LawBindingSpec` covers the new variant and the
      law-source contract test asserts no resolution call for it (D12)
- [x] 10.2 `TaskTierLaw.Bound` carries the typed `ObjectId lawCommit` next to
      the definition and exposes `LawBinding.atCommit(lawCommit)` as the
      binding to assemble under — the hex downgrade is deleted;
      `TaskTierLawSpec` asserts the commit in `Bound` is the one
      `LawSources.open` peeled (D12)
- [x] 10.3 Revise the port: `TaskRepository.createTask(context, lawCommit,
      pin, initialState)` — the start point is a typed commit, the pin
      `(ref, kind, rule)` is metadata; `TaskBranchCreator.createBranch` and
      `GitObjectsTaskRepository.createTask` take the commit, run no
      `rev-parse` of a base name, verify the object exists as a commit
      (`cat-file -e <sha>^{commit}` / `resolveRef(<sha>)`), and record the
      same commit as `baseCommit`; `BranchCreationResult.BaseRefNotResolved`
      becomes "base commit missing"; both ends of the
      `GitTaskRepository` / `GitObjectsTaskRepository` pair change in this
      step and their `Kept in sync with` invariant line names the commit
      input; the two push-best-effort decorators pass it through
      (FR15, sync surface)
- [x] 10.4 Wire the fresh-claim pair: `TakeFreshClaim` and
      `TakeContainerFreshClaim` pass `bound.lawCommit()` into
      `GitFreshTaskSupport.createTask`; `FreshClaimBaseBinding.Bound` keeps
      the `BaseRefKind` from `Refreshed` for the pin instead of discarding
      it; `Kept in sync with` markers updated at both ends; mirrored specs
      assert the branch's first parent equals `Refreshed.commit` on both
      media (FR15, D6)
- [x] 10.5 Manual `run` (`GitModeRunner`, `ContainerGitModeRunner`): bind the
      law through `ManualRunLawBinding` first, then create the branch from
      the bound law commit; a working-tree binding that yields no checkout
      commit fails with a `UsageException` naming the missing repository,
      never with a fallback to a name; the offline no-`--base` scenario still
      makes zero remote calls (FR8, FR15, UX3)
- [x] 10.6 TDD the pin's kind: `BasePin` gains `BaseRefKind`; `TaskJsonMapper`
      writes and reads `baseKind` behind the wire version gate; a pin without
      it reads with kind absent; data-driven round-trip spec iterates every
      `BaseRefKind` constant plus the unknown-token arm (FR7)
- [x] 10.7 Resume by pinned kind: `BaseRefGit.resolveForResume` takes the
      optional pinned kind; `BaseRefresh` skips the `ls-remote`
      classification and fetches that namespace only when a kind is given,
      classifies as before when it is absent; `ResumeLawBinding` and
      `ManualResumeLawBinding` pass the pin's kind; spec: a tag pushed under a
      pinned branch's name does not park the resume (FR7, D7, D11a)
- [x] 10.8 Regression spec on a bare origin (`adapters/git`, real git): a clone
      whose local `main` is behind origin, a branch existing only on origin,
      and a planted local tag carrying the base name; for each, the task
      branch's first parent, the law commit, and `task.json`'s `baseCommit`
      equal `Refreshed.commit`, and the clone's `refs/heads/*`, tags, and
      HEAD are unchanged; run twice to assert idempotent recovery is
      untouched (FR15, NFR-S1, git-task-persistence scenarios)
- [x] 10.9 Grep gate in the style of 6.1: no `rev-parse` of a base *name*
      remains in `adapters/git` outside `BaseRefresh`, `RefreshedTip`,
      `TagBaseFetch`, `CommitBaseFetch`, and `ResumeBaseResolution.localTip`;
      amend `docs/adr/0006-base-refresh-fetch.md` with the durable rule "the
      commit read from the destination is the only start point; a ref name
      crosses a port only as pin metadata or inside a `LawBinding`"; rewrite
      the `TaskBranchCreator` javadoc, which still states the pre-refresh
      contract; glossary entry *base pin* names the kind (D12, no-jargon rule)
- [x] 10.10 `./gradlew :adapters:check :adapters:git:check :application:check
      :bootstrap:check` green with 100% mutation score; the kill-point matrix
      of 8.4 passes unchanged, confirming no durable step was added (NFR-R1)
- [x] 10.11 Make the shared clone fixture adversarial by default
      (`testing.md`, "Git fixtures are adversarial by default"): the clone
      `BareGitRepoFixture` hands out carries a local branch under the base
      name one commit behind origin and a local tag under the same name
      pointing elsewhere; specs needing the converged posture opt in
      explicitly; the whole `adapters/git`, `application`, and `bootstrap`
      suites stay green on the new default — any spec that goes red is a
      bare-name resolution to fix, not a fixture to relax (FR15, NFR-S1)

## 11. Credential-refusal classification, recorded (check-issue 2026-09-11)

> No code change: the `/check-issue` verification of the refs read found the
> behavior right and the written rule wrong. FR9 listed every "authentication
> refusal" as task-level, while a credential the remote refuses before any ref
> is confirmed is — and should be — the daemon's. The record is what moves.

- [x] 11.1 Amend `docs/adr/0005-dependency-outage-accounting.md` with the
      credential-failure split: origin refused the read (daemon, opens the
      gate, no probe can close it) versus origin answered and refused one ref
      (task, parks with a report through `OriginProbe`); the 2026-09-11
      survey (Renovate, Flux, Argo CD, go-git/libgit2) that finds the same
      split; the two accepted limitations — the bounded retry is spent first,
      and the operator wording is reachability-shaped; the rejected
      alternative of parking the task by matching git's stderr (FR9, FR14)
- [x] 11.2 Bring the artifacts that state the old rule into line: FR9 in
      `proposal.md`, the risk line in `design.md` that claimed a revoked
      credential parks only its own task, and the failure-classes paragraph
      of `docs/adr/0006-base-refresh-fetch.md`
- [x] 11.3 TDD `RemoteAuthRefusalSpec` against a real HTTP origin answering
      401 — a rejected token and no credentials at all both classify as the
      daemon's for the base refresh and the default-branch discovery, the
      gate's probe cannot pass, and the bounded retry is spent first; the
      served-request count is the evidence that origin answered and refused,
      which no exit-code fake could establish (FR5, FR9, FR14)

## 12. Untrusted-text findings of the 2026-09-11 review (check-issue report 4)

> The review's report 4 named raw git stderr in `TakeResult.reason()`; the
> stderr half was refuted (`GitCommandResult.failureDetail` already sanitizes),
> the real vector was tracker/`task.json`/manifest text folded raw into the
> three base reports and the two bindings. The per-field `LogText.forLog`
> patch is in the tree (`BaseReportSanitizingSpec`, `ResumeLawBindingSpec`).
> The systemic follow-ups are `harden-untrusted-text-sinks`,
> `split-logtext-leaves`, `type-untrusted-text`; the three items below are
> this change's own code and stay here.

- [x] 12.1 Per-field sanitizing in `FreshClaimBaseReport`, `ResumeBaseReport`,
      `BaseLawReport`, and the bare `resolvedRef`/`pinnedRef` log arguments and
      `InfrastructureUnavailable` text in `FreshClaimBaseBinding` /
      `ResumeLawBinding` (FR2, FR6, FR9, FR12, FR13; FR6 of
      harden-logging-observability). Verify: `BaseReportSanitizingSpec` (five
      features, red before the fix) and the `ResumeLawBindingSpec` release-reason
      feature green; `:application:check` and `:bootstrap:check` green
- [x] 12.2 TDD (red first): `RefNameSyntax` at the three ref entries the review
      found unvalidated — the pin read at `TaskJsonMapper:125` (a malformed
      `baseRef` in `task.json` parks with a report naming the document, never
      reaches a fetch), `task-branch.base.default` when `allowed` is empty
      (`BaseConfigMapper.checkDefault` — a located `ConfigError`), and the branch
      name parsed by `RemoteDefaultBranch.symrefBranch` (a name with a control
      character is `Undetermined`, not `Discovered`) (FR1, FR4, NFR-S3; refuse,
      not escape — the git `check-ref-format` posture). Verify: three red specs,
      then green; existing `BaseConfigMapperSpec` / `RemoteDefaultBranchSpec` /
      `TaskJsonMapperBasePinSpec` green. Done 2026-09-11; the sweep found a
      fourth entry, the operator's `--base` argument (bounded by no pattern), and
      `BaseRefResolver` now refuses it under
      `UnderdeterminedCause.EXPLICIT_BASE_MALFORMED` in the same task
- [x] 12.3 `GitCommandResult.cannotVerifyDetail` applies `CredentialScrub` like
      `failureDetail` does, and its javadoc names the invariant it rests on
      (`GitProcessRunner:230` scrubs at capture) instead of leaving it implicit
      (NFR-S2 of fix-lifecycle-push). Verify: a `GitCommandResultSpec` feature with
      a `https://token@host` URL in stderr shows `***` in both details


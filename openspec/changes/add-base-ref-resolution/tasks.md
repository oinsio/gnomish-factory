# Tasks: add-base-ref-resolution

## 1. `:baseref` leaf module — pure resolution policy

- [ ] 1.1 Create the `:baseref` Gradle module with the project conventions
      (PIT 100%, `layering { allowedProjects = [] }`, no external deps) and
      verify `./gradlew projects` lists it and `:baseref:check` passes empty
      (FR10, module-layering delta)
- [ ] 1.2 TDD the menu value types and pattern grammar: menu entry
      (pattern + role `development`/`release`, role defaults to
      development), pattern compile/match semantics for literal names and
      `*` series; Spock data tables over match/no-match cases (FR1)
- [ ] 1.3 TDD the module's own base-designator input value
      (absent | single | conflict) and its validation against the menu;
      the classification from candidates is NOT here (task 3.1) (FR3)
- [ ] 1.4 TDD `BaseRefResolver`: priority order (explicit base > designator
      validated against menu > configured default > repo default branch >
      local HEAD in manual mode only), decision value
      `(ref, rule, reason)`, underdetermined classification (out-of-menu,
      conflict, autonomous with no remote/default); data-driven spec over
      the full priority matrix including the defensive
      empty-menu-with-designator escalation (FR4, FR5)
- [ ] 1.5 Verify `:baseref:check` is green with 100% mutation score and the
      layering gate rejects a probe dependency (revert the probe); run
      `./gradlew :baseref:check`

## 2. Config: `base:` block in `.gnomish/config.yaml`

- [ ] 2.1 TDD the loader DTO + validation for the `base:` section per the
      pipeline-config delta: `type` discriminator (only `patterns`,
      defaulting when absent), `default`, `menu`; located `ConfigError`s
      for unknown type, unknown keys (including a stray `select`), invalid
      pattern, bad role, default outside the menu; absent section loads as
      empty config (FR1, UX1)
- [ ] 2.2 Map the DTO into `:baseref` value types (compiled at load) and
      verify via loader specs that the typed definition exposes menu,
      roles, and default; aggregation with unrelated core errors covered
      (FR1)
- [ ] 2.3 TDD the trusted-tier startup check: when the tracker adapter
      factory reports designator kind `base` among its configured kinds
      and the menu is empty, loading fails with a located `ConfigError`
      naming the rule's location and `base.menu`; a rule with a non-empty
      menu and an empty menu with no rule both load (FR3, UX1,
      pipeline-config "Selection rule without a menu is a load error")

## 3. Tracker port: designator facts and contract suite

- [ ] 3.1 TDD the designator vocabulary and the one classification function
      in `:gnomish-plugin-api` (candidates → absent | single | conflict,
      equal duplicates collapse, conflicts keep every value); add
      `designators` to `TrackerTask` and the configured-kinds query to the
      `TrackerAdapterFactory` seam (default: no kinds) (FR3)
- [ ] 3.2 TDD the GitHub adapter end: `tracker.github.designators` map
      validated on the subsection seam (kind → regex compiles, exactly one
      capture group, located errors like `labels`), candidate extraction
      from issue labels in `GithubTaskFetcher`, configured kinds reported
      through the factory; in-memory adapter: a test operation that sets a
      task's designators, no subsection (FR3)
- [ ] 3.3 Add the three-shape designator coverage to the contract suite
      (absent / single / conflict / equal duplicates for kind `base`) and
      verify both adapters pass it unchanged; map the port shape onto the
      `:baseref` input value in `:application` (FR3)

## 4. Git adapter: discovery and refresh fetch

- [ ] 4.1 TDD default-branch discovery (`ls-remote --symref` parse) in
      `:adapters:git` under the bounded-network rules; specs cover a
      renamed default branch, a no-origin clone (refusal result, not a
      guess), and scrubbed failure output (FR5, NFR-P1)
- [ ] 4.2 TDD the narrow base refresh per D11: branch →
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
- [ ] 4.3 Wrap discovery + refresh in `GitInfrastructureRetry` with the
      infrastructure-failure outcome and verify on virtual time that
      retries are bounded and a dead remote yields the typed failure, not
      an exception leak (FR9, NFR-R1)

## 5. Law source by ref (D12–D14)

- [ ] 5.1 TDD tree listing in `:gitobjects`: `listTree(commit, path)` over
      `git ls-tree` reporting regular files, directories, and symlink
      entries distinctly; spec covers an absent path and a blob-vs-tree
      mismatch (FR11)
- [ ] 5.2 TDD the law source abstraction (read file / is regular file /
      list directory, relative to a root) with the working-tree and
      git-objects realizations; one contract spec runs both over identical
      trees; the git-objects realization refuses symlink entries and applies
      the lexical half of `PathSafety` as its whole traversal guard (FR11,
      D12)
- [ ] 5.3 Route `GnomishFiles`, `ReferencedFiles`, `PipelineLoader`, and
      `PipelineLawReader.freeze` through the law source; existing loader and
      reader specs pass unchanged over the working-tree realization; add
      the git-objects twin of `PipelineLawReaderSpec` (FR11)
- [ ] 5.4 Replace the `"HEAD"` pin ref in `RunAssembler` with the law commit
      carried by the assembly; every `assemble` call site passes a law
      commit (take/serve/container/resume paths) or the working tree
      (in-place, manual `run` without `--base`); add the `Kept in sync
      with` markers to both ends of `TakeEngineExecution` /
      `TakeContainerEngineExecution` (invariant line names the law-commit
      argument) and remove their `manual-sync-pairs.md` registry row; grep
      gate: no `"HEAD"` literal in law-source or pin wiring (FR11, M5)
- [ ] 5.5 TDD startup-plus-per-task loading in `serve`/`take`: the default
      branch definition is loaded and validated at startup as today; after
      resolution the task tier loads from the base SHA; a load error on the
      base parks the task with a report naming ref, law commit, and located
      errors, burns no attempt, releases no claim; `board`/`dashboard`
      unchanged (FR13, UX5, D14)
- [ ] 5.6 TDD resume law binding: autonomous resume narrow-fetches the
      pinned ref name and binds from its tip; manual resume without a
      remote binds from the local ref tip; a ref that resolves nowhere parks
      with a report; add the `Kept in sync with` markers to both ends of
      `TakeResumeRunner` / `TakeContainerResumeRunner` (invariant line
      names pinned-ref tip resolution) and remove their
      `manual-sync-pairs.md` registry row (FR12, D13)
- [ ] 5.7 Extend `GitModeLawBindingSpec` (or add a sibling) with: clone
      checked out at `main`, task based on `release/1.18` — law and pin
      come from the base; uncommitted clone edits play no part in take;
      manual `run` without `--base` still binds uncommitted edits (FR11,
      FR8, M5)

## 6. Application: funnel wiring, pin, and failure routing

- [ ] 6.1 Wire `BaseRefResolver` into `GitFreshTaskSupport`; delete the
      null→HEAD default there and the `TaskBranchCreator.startPoint()`
      duplicate; all four fresh-start paths receive resolved refs; verify
      by existing suites plus a grep gate that no `"HEAD"` default remains
      outside the manual-run tier (FR4, FR10, M2)
- [ ] 6.2 Insert config-refresh + resolve + base-refresh between `harden()`
      and `createTask()` in `TakeFreshClaim` and
      `TakeContainerFreshClaim`; add the `Kept in sync with` markers to
      both ends (invariant line includes the new resolve step) and remove
      their `manual-sync-pairs.md` registry row; mirrored specs cover both
      media; the `base:` block is
      read through the git-objects law source at the refreshed default-branch
      tip, and the law-source contract test pins that source (FR2, FR6, D6,
      sync surface)
- [ ] 6.3 TDD the pin: mapper writes `(ref, sha, rule)` in the
      task-creation commit behind the version gate; the pin flows through
      both ends of the `GitTaskRepository` / `GitObjectsTaskRepository`
      lifecycle pair identically (serialization stays single-point in the
      shared `TaskJsonMapper`); legacy `baseCommit`-only
      files read as unpinned; data-driven round-trip spec over every rule
      constant plus the unknown-token forward-compat arm (FR7)
- [ ] 6.4 TDD resume behavior: pinned tasks never re-resolve (no trusted-tier
      read, no designator read on resume — asserted with throwing fakes;
      the task-tier read of 5.6 is the only law read);
      a kill between claim and creation commit freezes `Claimed` with no
      branch ref; the reaper restores `Ready` on virtual-time TTL, a second
      reaper pass is a no-op, and the next claimant re-resolves from
      scratch (FR7, NFR-R1, NFR-R2)
- [ ] 6.5 TDD underdetermined-input escalation: out-of-menu and conflict
      park the task with a report naming the found values and the menu, no
      stage attempt burned; manual `run` without `--base` still branches
      from local HEAD offline with zero network calls (specs assert no
      remote invocation) (FR4, FR8, UX2, UX3)

## 7. take/serve failure handling and observability

- [ ] 7.1 TDD the failure classification at the fresh-claim step: a typed
      reachability failure (connect, DNS, timeout, retries exhausted) versus
      task-level causes (missing ref, auth refusal, underdetermined
      designator); only the former is the infrastructure class; the abort
      protocol's uncaught-exception arm is asserted unreached with a
      throwing-tracker fake (FR9)
- [ ] 7.2 TDD claim release on base infrastructure failure in take: plain
      `release`, task back to Ready, no abort marker, no comment, abort
      facts and backoff unchanged (seed a task with two aborts and assert
      still two); new `TakeResult` variant with exit code 16 in the exit
      mapper and a typed outcome-log line in serve (no `taskOutcome`
      ledger line, like `Skipped`); a later take
      succeeds once the fake remote recovers (FR9, M4, tracker-take
      MODIFIED exit codes)
- [ ] 7.3 TDD the remote outage gate as one owner class on virtual time:
      open on a slot's infrastructure failure, consulted by the feed before
      every claim (no claim while open — assert zero tracker claim calls),
      tracker-free `ls-remote` probe on a jittered interval growing from the
      idle interval to the cap via `RestartBackoff`'s policy, close on the
      first successful probe, re-arm on a failed one; the interval resets
      to idle only on the first successful base refresh after a close
      (flapping-remote spec: close, immediate refresh failure, reopen with
      a longer interval); in-flight slots keep running; process-local (a
      fresh daemon starts closed) (FR14, NFR-R3)
- [ ] 7.4 TDD gate observability: one WARN with a new `OperatorEvent` code
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
- [ ] 7.5 Serve end-to-end on virtual time: three slots, remote dead for an
      hour, then back — at most three claims and releases total, none after
      the gate opened, one WARN and one recovery line, the first successful
      probe precedes the first post-outage claim, every task claimable
      again with zero abort facts (M4, G5)
- [ ] 7.6 Integration spec against a local bare remote: zero-config serve
      claim branches from the remote default-branch tip observed at claim
      (M1); label-selected release base is fetched, validated, and pinned
      (U2)

## 8. Documentation and verification

- [ ] 8.1 Add glossary entries (base ref, base menu, designator — written
      kind-generic: a per-task selection of a given kind carried as
      tracker metadata in one of three shapes, with `base` as the first
      kind and `type` named as the next — base pin,
      law commit, trusted tier, task tier, remote outage gate) and the
      operator-guide section: `base:` block reference, the
      `tracker.github.designators` rule reference (and the adapter author
      guide's designator obligations), the external-automation escape
      hatch, the two configuration tiers, the
      unchanged `run` behavior, and the outage gate (its log lines, snapshot
      section, and exit code 16); verify by docs build/lint conventions
      (UX1, UX4, UX6, D10, D12)
- [ ] 8.2 Write `docs/adr/0007-pipeline-law-source.md`: law by ref from git
      objects, the two tiers, resume from the pinned ref tip as a recorded
      deviation from the re-run model, the rejected worktree and checkout
      alternatives; reference it from D12 and the glossary (D12–D14)
- [ ] 8.3 Traceability sweep: grep confirms every FR/NFR/UX of this change
      has at least one implementing spec or code reference, and the
      superseded D7 wording is gone from the merged spec view
      (`openspec validate --strict` passes)
- [ ] 8.4 Full build green: `./gradlew check` including PIT for touched
      modules; kill-point specs for the new window pass twice (recovery
      idempotence), including a kill between the failed fetch and the claim
      release (NFR-R1, NFR-R3)
- [ ] 8.5 Reconcile `docs/adr/0005-dependency-outage-accounting.md` and
      `docs/adr/0006-base-refresh-fetch.md` (both accepted with this
      change's planning, status "implementation pending") with what
      landed: exact flags, exit code, event codes, the flapping-remote
      reset; flip their status to accepted-and-implemented; reference them
      from the glossary entries (D9, D11)

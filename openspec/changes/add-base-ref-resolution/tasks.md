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
- [ ] 1.3 TDD the designator classification: kind-generic extraction of
      label values through a compiled one-capture-group rule into
      absent | single | conflict; conflicts keep every value; specs cover
      no-match, one match, duplicate same-value labels, and differing
      captures (FR3)
- [ ] 1.4 TDD `BaseRefResolver`: priority order (explicit base > designator
      validated against menu > configured default > repo default branch >
      local HEAD in manual mode only), decision value
      `(ref, rule, reason)`, underdetermined classification (out-of-menu,
      conflict, autonomous with no remote/default); data-driven spec over
      the full priority matrix including the empty-menu-with-designator
      escalation (FR4, FR5)
- [ ] 1.5 Verify `:baseref:check` is green with 100% mutation score and the
      layering gate rejects a probe dependency (revert the probe); run
      `./gradlew :baseref:check`

## 2. Config: `base:` block in `.gnomish/config.yaml`

- [ ] 2.1 TDD the loader DTO + validation for the `base:` section per the
      pipeline-config delta: `type` discriminator (only `patterns`,
      defaulting when absent), `default`, `menu`, `select.label` with
      exactly one capture group; located `ConfigError`s for unknown type,
      unknown keys, invalid regex/pattern, bad role, default outside the
      menu; absent section loads as empty config (FR1, UX1)
- [ ] 2.2 Map the DTO into `:baseref` value types (compiled at load) and
      verify via loader specs that the typed definition exposes menu,
      roles, default, and selection rule; aggregation with unrelated core
      errors covered (FR1)

## 3. Tracker port: label facts and contract suite

- [ ] 3.1 Extend task facts with raw labels in the tracker port model and
      the GitHub + in-memory adapters; port-level contract suite asserts
      identical label reporting for both (FR3)
- [ ] 3.2 Add the three-shape designator coverage to the contract suite
      (absent / single / conflict for kind `base` through the extraction
      seam) and verify both adapters pass it unchanged (FR3)

## 4. Git adapter: discovery and refresh fetch

- [ ] 4.1 TDD default-branch discovery (`ls-remote --symref` parse) in
      `:adapters:git` under the bounded-network rules; specs cover a
      renamed default branch, a no-origin clone (refusal result, not a
      guess), and scrubbed failure output (FR5, NFR-P1)
- [ ] 4.2 TDD the narrow base refresh: single-ref fetch for branches and
      tags (tag objects included), local-verify + permitted fetch-by-SHA
      for bare SHAs, full-depth single-ref (no shallow); assert the
      operator clone's HEAD, working tree, and local branches are
      untouched, against a local bare remote (FR6, D11)
- [ ] 4.3 Wrap discovery + refresh in `GitInfrastructureRetry` with the
      infrastructure-failure outcome and verify on virtual time that
      retries are bounded and a dead remote yields the typed failure, not
      an exception leak (FR9, NFR-R1)

## 5. Application: funnel wiring, pin, and failure routing

- [ ] 5.1 Wire `BaseRefResolver` into `GitFreshTaskSupport`; delete the
      null→HEAD default there and the `TaskBranchCreator.startPoint()`
      duplicate; all four fresh-start paths receive resolved refs; verify
      by existing suites plus a grep gate that no `"HEAD"` default remains
      outside the manual-run tier (FR4, FR10, M2)
- [ ] 5.2 Insert config-refresh + resolve + base-refresh between `harden()`
      and `createTask()` in `TakeFreshClaim` and
      `TakeContainerFreshClaim`; update both `Kept in sync with` markers'
      invariant line; mirrored specs cover both media (FR2, FR6, D6,
      sync surface)
- [ ] 5.3 TDD the pin: mapper writes `(ref, sha, rule)` in the
      task-creation commit behind the version gate; legacy `baseCommit`-only
      files read as unpinned; data-driven round-trip spec over every rule
      constant plus the unknown-token forward-compat arm (FR7)
- [ ] 5.4 TDD resume behavior: pinned tasks never re-resolve (no config
      read, no designator read on resume — asserted with throwing fakes);
      a crash between claim and creation commit re-resolves from scratch
      per the kill-point matrix, second recovery pass a no-op (FR7,
      NFR-R1, NFR-R2)
- [ ] 5.5 TDD underdetermined-input escalation: out-of-menu and conflict
      park the task with a report naming the found values and the menu, no
      stage attempt burned; manual `run` without `--base` still branches
      from local HEAD offline with zero network calls (specs assert no
      remote invocation) (FR4, FR8, UX2, UX3)

## 6. take/serve failure handling and observability

- [ ] 6.1 TDD claim release on base infrastructure failure in take: claim
      removed via the existing path, task back to Ready, no tracker
      comment, typed run outcome; a later take succeeds once the fake
      remote recovers (FR9, M4)
- [ ] 6.2 TDD serve-side logging: first refresh failure per target logs
      one WARN with a new `OperatorEvent` code, repeats suppressed to
      DEBUG with roll-up via `RepeatSuppressor`; slot and daemon keep
      running (NFR-O1); extend the operator-event sync spec if the code
      lands in the domain emitters' pair
- [ ] 6.3 Integration spec against a local bare remote: zero-config serve
      claim branches from the remote default-branch tip observed at claim
      (M1); label-selected release base is fetched, validated, and pinned
      (U2)

## 7. Documentation and verification

- [ ] 7.1 Add glossary entries (base ref, base menu, designator, base pin)
      and the operator-guide section: `base:` block reference, the
      external-automation escape hatch, and the unchanged `run` behavior;
      verify by docs build/lint conventions (UX1, UX4, D10)
- [ ] 7.2 Traceability sweep: grep confirms every FR/NFR/UX of this change
      has at least one implementing spec or code reference, and the
      superseded D7 wording is gone from the merged spec view
      (`openspec validate --strict` passes)
- [ ] 7.3 Full build green: `./gradlew check` including PIT for touched
      modules; kill-point specs for the new window pass twice (recovery
      idempotence) (NFR-R1)

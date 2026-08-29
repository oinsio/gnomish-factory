# Tasks — add-tracker-task-hierarchy

## 1. Port model (gnomish-plugin-api)

- [ ] 1.1 Add hierarchy fact types to the tracker port package (parent ref,
      child entry with ref + stable key + open/terminal state,
      `dependencyBlocked`) with defaults meaning "no relationships"; verify
      the module compiles and existing port specs stay green (FR1)
- [ ] 1.2 Extend `ReadyTask` with the `dependencyBlocked` fact (default
      false), keeping constructors within the 7-parameter rule (parameter
      object per design D1); verify existing feed specs pass unchanged (FR3)
- [ ] 1.3 Add `createSubtask(parentRef, childKey, title, body, blockedBy)`
      to `Tracker` returning created-ref or `AlreadyExists(existingRef)`,
      plus child-listing access on task facts; verify javadoc carries the
      traceability line and the API compiles for both adapters (FR2)

## 2. Contract suite

- [ ] 2.1 Extend the port-level contract spec suite with hierarchy-fact
      scenarios (parent/children/none, blocked fact set and cleared) per the
      tracker-port delta; verify the suite runs red against the untouched
      in-memory adapter (TDD) (FR1, FR3)
- [ ] 2.2 Add contract scenarios for create-subtask: creation with edges,
      stable-key round-trip via child listing, duplicate key →
      `AlreadyExists`, no duplicate task; verify red first (FR2, NFR-R1)

## 3. In-memory reference adapter

- [ ] 3.1 Implement hierarchy storage, create-subtask, and the blocked fact
      in the in-memory tracker plus test seeding hooks for parent/child/edge
      setups; verify all new contract scenarios pass against it (FR5)

## 4. GitHub adapter

- [ ] 4.1 Add sub-issues endpoint reads (parent lookup, children listing,
      per-level pagination) mapped into hierarchy facts; verify with WireMock
      specs using documented payload shapes (FR1)
- [ ] 4.2 Add issue-dependencies reads mapped to `dependencyBlocked`; verify
      WireMock spec covering open-blocker true / closed-blocker false (FR1)
- [ ] 4.3 Implement create-subtask with the D3 write order (issue with key
      marker → parent link → blocked-by edges) and lookup-before-create
      convergence; verify WireMock specs for the happy path and both kill
      windows (w1 retry completes link, w2 retry returns `AlreadyExists`)
      (FR2, NFR-R1)
- [ ] 4.4 Wire the blocked fact into feed enrichment behind the
      conditional-request economy (read on representation change, cached
      otherwise, TTL constant beside existing polling constants); verify the
      unchanged-page spec asserts zero extra dependency requests (FR3,
      NFR-P1)
- [ ] 4.5 Run the extended contract suite against the GitHub adapter harness;
      verify parity with the in-memory adapter (FR5)

## 5. Claim selection

- [ ] 5.1 Drop `dependencyBlocked` entries in
      `FeedPolicy.selectClaimCandidates` beside the backoff filter, before
      the returned/fresh split; verify Spock specs for skip-without-write and
      the fully-blocked named no-op reason (FR4)
- [ ] 5.2 Verify serve's `FeedCycle` inherits the exclusion through the
      shared policy with one integration spec asserting a blocked task is
      never claimed by serve (FR4)

## 6. Documentation and gates

- [ ] 6.1 Update the adapter author guide (hierarchy facts, create-subtask
      convergence contract, Gitea E2E limitation for sub-issues) and add
      glossary entries for "subtask", "dependency-blocked", "stable child
      key"; verify glossary terms match code naming (FR5)
- [ ] 6.2 Run full module checks (`:gnomish-plugin-api:check`,
      `:adapters:check`, `:adapters:github:check`, `:application:check`)
      including PIT; verify mutation gate passes with no new exemptions, or
      each new exemption carries its written rationale (all FRs)

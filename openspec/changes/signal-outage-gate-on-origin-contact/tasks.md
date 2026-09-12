# Tasks: signal-outage-gate-on-origin-contact

Sequenced after `add-base-ref-resolution` (both deltas are layered on its
deltas; the decoration this change edits is its task 7.7). Independent of
`type-untrusted-text` (different record components — design, Risks). Group 1
leaves the tree red on purpose until group 2 lands; groups 1 and 2 are one
commit.

## 1. The fact on the port

- [ ] 1.1 Add `OriginContact { CONTACTED, CLONE_ONLY }` to `app.port.git` with
      a javadoc stating the rule of design D2 (a label on the path taken, never
      derived from git output) and append it as the last component of
      `BaseRefreshOutcome.Refreshed` and `ResumeBaseOutcome.Bound`; no
      convenience constructor (design D1). Traceability: `Implements FR1 of
      signal-outage-gate-on-origin-contact`. Verify: `./gradlew
      :application:compileJava` fails only at the production consumers named
      in 1.2 and the adapter sites of 2.1 — the compiler's list is the
      consumer list.
- [ ] 1.2 Update the four production pattern-match consumers to destructure the
      new component as `var _` and change nothing else: `FreshClaimBaseBinding`
      (`refresh` switch), `ResumeLawBinding` (`bind` switch),
      `TrustedTierStartup` (default-branch refresh switch),
      `ResumeBaseResolution` (the remote-backed re-wrap, which passes the
      refresh's own value into `Bound` — design D2) (FR4). Verify:
      `:application:compileJava` and `:adapters:git:compileJava` green once
      2.1 lands; `FreshClaimBaseBindingSpec`, `ResumeLawBindingSpec`,
      `TrustedTierStartupSpec` pass with only construction sites edited (3.1).

## 2. The adapter sets it

- [ ] 2.1 TDD (red first) in `BaseRefreshSpec`, on the existing recording-git
      fixture (`recordedSubcommands`): the present-commit feature ("costs no
      network at all") asserts `CLONE_ONLY` alongside its no-`fetch`
      assertion; the fetched-commit, branch, and tag features assert
      `CONTACTED` alongside `count('fetch') == 1`. Then set the value at the
      three `BaseRefreshOutcome.Refreshed` sites (`CommitBaseFetch` ×2,
      `RefreshedTip` ×1) per design D2. Traceability comments on each site.
      Verify: the four features red before, green after; the abbreviated-SHA
      offline feature also reads `CLONE_ONLY` (FR1, NFR-R1, M2).
- [ ] 2.2 TDD (red first) in `ResumeBaseResolutionSpec`: the no-origin
      local-tip feature asserts `CLONE_ONLY`; the configured-origin fetch
      feature asserts `CONTACTED`; the present-commit resume feature asserts
      `CLONE_ONLY` (pass-through of the refresh's value). Then set the value at
      the two `ResumeBaseOutcome.Bound` sites. Verify: red before, green after;
      `:adapters:git:check` green with PIT 100% (FR1, M2).

## 3. The decoration reads it

- [ ] 3.1 Update every remaining construction site of the two records in test
      code, choosing `CONTACTED` where the stub stands in for a remote answer
      and `CLONE_ONLY` where it stands in for a local hit, with no assertion
      changed (M3): `application` — `RunChainFakes`, `FreshClaimBaseBindingSpec`,
      `ResumeLawBindingSpec`, `TakeClaimAndWorkSpec`, `TakeResumeReplicationSpec`,
      `TakeFenceScopeSpec`, `TakeResumeShapeTailSpec`, `TrustedTierStartupSpec`,
      `RemoteOutageSignalingBaseRefGitSpec`; `adapters/git` —
      `GitBaseRefsDelegationSpec` (plus 2.1/2.2's own); `bootstrap` —
      `TakeResumeRunnerLawBindingSpec`. Verify: `./gradlew compileTestGroovy`
      across the three modules green; `git diff` on these files touches only
      constructor argument lists.
- [ ] 3.2 TDD (red first) in `RemoteOutageSignalingBaseRefGitSpec`: make the two
      success features data-driven over `OriginContact`, asserting for both
      `refresh` and `resolveForResume` that `CONTACTED` sets the gate's
      `lastSuccessAt` and `CLONE_ONLY` leaves it null; add a feature on a real
      gate on virtual time for the factory-serve scenario "A clone-served
      success after a close keeps the grown interval" (open, fail a probe,
      close on a probe, `CLONE_ONLY` success, reopen, assert the next probe
      waits the grown interval, not idle). Then branch on the fact in
      `RemoteOutageSignalingBaseRefGit` (design D3) and delete the "known
      imprecision" javadoc paragraph. Verify: the `CLONE_ONLY` rows and the
      scenario feature red before, green after; `:application:check` green
      with PIT 100% (FR2, FR3, NFR-O1, M1).
- [ ] 3.3 Run the `:bootstrap` slot specs that drive the real decoration
      (`TakeSlotRunnerSpec`, `TakeSlotRunnerContainerConcurrencySpec`,
      `ServeShutdownWiringSpec`) and `:bootstrap:spotlessCheck`. Verify: green;
      the flow feature "a slot's real base refresh is reported to the remote
      outage gate" still observes `lastSuccessAt` set, since a branch fetch
      against the spec's own origin is `CONTACTED` (FR2, M3).

## 4. Documents

- [ ] 4.1 Glossary: extend the "Remote outage gate" entry with the contact
      rule (a refresh served from the clone alone is not a successful refresh
      for the gate) and add an entry for the origin-contact fact under the
      base-ref context, naming its two values in plain words (process-invariants:
      a change that introduces a term adds its entry). Verify: both entries
      present; no banned synonym introduced.
- [ ] 4.2 `docs/adr/0005-dependency-outage-accounting.md`, gate section: one
      sentence after "reported at the base read itself" stating that only a
      read that contacted origin counts, with the clone-served cases named.
      Verify: the ADR's state diagram needs no change (the transition label
      "first base refresh succeeds" is unchanged; the qualification lives in
      prose).
- [ ] 4.3 Single-owner sweep per `implementation.md`: grep for every
      construction of `Refreshed(`/`Bound(` in `src/main` and confirm each is
      one of the five sites in design's table; grep for every reader of
      `OriginContact` and confirm the decoration is the only one that branches
      on it. Record the grep, the hits, and the disposition of each in the
      task report. Verify: five constructors, one branching reader, zero
      unlisted survivors.

# Tasks: split-logtext-leaves

Sequenced after `harden-untrusted-text-sinks` (its `forConsole` and record cap
move here with the table) and after `add-base-ref-resolution` (module-layering
base). Before `type-untrusted-text`. Every group ends green; group 3 is an
imports-only commit by design (design — Risks).

## 1. The `:untrustedtext` leaf

- [ ] 1.1 Create `untrustedtext/build.gradle` on the `:baseref` template (design D7):
      `library-conventions` + `layering-conventions`, `allowedProjects = []`, no
      production dependency block, header comment stating the emptiness is load-bearing;
      `package-info.java` with the neutrality contract (JDK only) and `@NullMarked`; add
      to `settings.gradle`. Verify: `./gradlew projects` lists `:untrustedtext`;
      `verifyModuleLayering` green with the empty allowlist.
- [ ] 1.2 Move the text primitives into `:untrustedtext` (design D2): the package-private
      classes `CharacterTable`, `ConsoleNotation`, `LineFlattening` unchanged, and the
      primitive bodies of `LogText` into the new owner `untrustedtext.TextSafety` —
      `strip`, `capTail`, `flatten`, `forConsole`, `capRecord`, `DEFAULT_CAP_CHARS`,
      `RECORD_CAP_CHARS`, `TRUNCATION_MARKER_RESERVE`. Move their specs and helpers
      (`LogTextSpec` primitive features, `LogTextConsoleSpec`, `LogTextRecordCapSpec`,
      `LogTextIdempotenceSpec`, `AdversarialCorpus`, `HostileCodePoints`) into the
      leaf's test tree, renamed for the owner. Traceability: `Implements FR1, NFR-S1 of
      split-logtext-leaves`. Verify: `:untrustedtext:check` green, PIT 100%;
      `logtext/src/main` holds none of the three primitive classes.
- [ ] 1.3 Turn `LogText` into a facade: every public method delegates one-to-one to
      `TextSafety` (`forLog` keeps composing strip → capTail → flatten); the `Kept in
      sync with FindingsSanitizer` javadoc paragraph is deleted; `logtext/build.gradle`
      gains `api project(':untrustedtext')` and `allowedProjects = [':untrustedtext']`
      (FR2, FR7). Verify: `:logtext:check` green (PIT posture per design — Risks,
      recorded in the build file if `excludedClasses` is used); no call site outside
      `:logtext` changed.
- [ ] 1.4 Transitive allowlists for `:untrustedtext` (FR11, design D9): add
      `':untrustedtext'` with a `// transitive via :logtext` comment to the allowlists of
      `:application`, `:adapters`, `:adapters:agent`, `:adapters:git`, `:adapters:github`,
      `:sandbox:docker`, `:bootstrap`; add it with `// transitive via :domain` to
      `:gnomish-plugin-api`, `:gnomish-plugin-api:sample` and `:sandbox:core` now, so
      4.1 lands green (the entries are permanent — the gate walks the transitive graph).
      Verify: `./gradlew verifyModuleLayering` green across the build after 1.3.
- [ ] 1.5 Turn `FindingsSanitizer` into a facade over `TextSafety`: `strip` and
      `capTail` delegate, `forLog` composes them with `LOG_TAIL_CAP_CHARS`; delete the
      private `ANSI` pattern, `isStrippedControl` and its three helpers, and the `Kept
      in sync with LogText` paragraph (FR3). Verify: `FindingsSanitizerSpec` green
      unchanged in substance; the sample plugin compiles with `gnomish-plugin-api` as its
      only declared dependency.
- [ ] 1.6 TDD (red first): `TextSafetyOwnerSpec` in `:bootstrap` replacing
      `SanitizerPairEquivalenceSpec` from `:application` (design D8, FR3): three-way
      identity of `LogText.strip` / `FindingsSanitizer.strip` / `TextSafety.strip` (and
      `capTail`) over the whole corpus, plus a `repoRoot` source scan asserting neither
      facade holds an escape-character or control-class literal (seeded violation
      detected). Verify: red on a seeded private table, green on the tree; the old spec
      deleted from `:application`.

## 2. The `:operatorevent` leaf

- [ ] 2.1 Create `operatorevent/build.gradle`, `package-info.java` and the
      `settings.gradle` entry on the same template as 1.1 (FR4). Verify: listed by
      `./gradlew projects`; layering gate green.
- [ ] 2.2 Move `OperatorEvent` to `com.github.oinsio.gnomish.operatorevent.OperatorEvent`
      with its specs; delete the javadoc paragraph about the domain pair (design D3).
      Verify: `:operatorevent:check` green, PIT 100%; `:logtext` no longer contains the
      enum.

## 3. Relocation commit — imports only

- [ ] 3.1 Rewrite every `import com.github.oinsio.gnomish.logtext.OperatorEvent` to the
      new package across production (~80 files: `:application` 41, `:adapters` 6,
      `:adapters:git` 13, `:adapters:github` 3, `:adapters:agent` 8, `:sandbox:docker` 9)
      and test sources (~96 files); add `api`/`implementation project(':operatorevent')`
      and the allowlist entry to each of those modules' `build.gradle` (design D3), and
      the `// transitive via :domain` allowlist entry to the consumers that do not import
      it — `:sandbox:core`, `:gnomish-plugin-api`, `:gnomish-plugin-api:sample`,
      `:bootstrap` (FR11, design D9). Verify: `git diff --stat` shows only import lines
      plus build files; `./gradlew compileJava compileTestGroovy verifyModuleLayering`
      green across the build.

## 4. Domain reaches the leafs

- [ ] 4.1 `domain/build.gradle`: `api project(':untrustedtext')`, `api
      project(':operatorevent')`, `allowedProjects = [':untrustedtext', ':operatorevent']`
      (FR6). Verify: `verifyModuleLayering` green across the build (every consumer
      already lists both leafs from 1.4 and 3.1); `DomainPuritySpec` green (the leafs
      are none of its forbidden packages).
- [ ] 4.2 TDD (red first): `DomainLeafPuritySpec` in `:bootstrap` for design D5 — every
      project in `:domain`'s allowlist declares an empty `allowedProjects` itself and no
      production-scope external artifact, read from its `build.gradle` through
      `repoRoot`. Verify: red when a leaf is given a fake dependency in a scratch build
      script; green on the tree.
- [ ] 4.3 Replace the four literal heads — `AttemptJournal:70`, `Events:56`,
      `RoundExecution:121`, `VerifyOrchestrator:150` — with `OperatorEvent.<CONSTANT>.head()`
      and delete the four `Kept in sync with …logtext.OperatorEvent` javadoc paragraphs
      (FR5, design D4); delete `DomainOperatorEventHeadSpec`. Verify: `:domain:check`
      green; `DomainLoggerAllowlistSpec` unchanged and green; `grep -rn "Kept in sync"
      domain/src/main` empty.
- [ ] 4.4 TDD (red first): the literal-head rule in `LogContractGateSpec` (FR10, design
      D4) — a production log call whose format string starts with a `"[GF` literal fails
      the gate naming the site; the existing feature "a coded site is not flagged, in
      either the catalog or the literal form" is inverted: the catalog form stays
      accepted, the literal form becomes the seeded violation. Verify: red on the tree
      before 4.3 (the four domain literals), green after; a seeded literal head in any
      module fails it.

## 5. Published contract and durable documents

- [ ] 5.1 `gnomish-plugin-api/build.gradle`: version 0.6.0 → 0.7.0, a header comment
      above the `fix-claim-epoch-fence` entry naming this change and the reason (the
      two leafs enter the published jar graph through `:domain`), and
      `./gradlew :gnomish-plugin-api:updateApiCompatibilityBaseline` regenerating
      `compat-baseline/` to four jars — `gnomish-plugin-api-0.7.0`, `domain`,
      `untrustedtext`, `operatorevent` (design D6, FR8). Verify: `japicmpApiGate`
      green; the diff against the previous baseline shows no signature change on
      `FindingsSanitizer`; `ls compat-baseline/` lists exactly the four.
- [ ] 5.2 `manual-sync-pairs.md`: delete both dissolved rows from "Declared pairs with
      no shared classpath" (FR3, FR5). Verify: `grep -rn "Kept in sync with" */src/main
      */*/src/main` lists no `LogText`, `FindingsSanitizer` or `OperatorEvent` end.
- [ ] 5.3 ADR 0004 (FR9): record the domain-may-reach-JDK-only-leafs principle beside
      accepted deviation 1; drop the deviation's literal-head paragraph in "The operator
      plane is addressed by code"; in layer 2 of "Untrusted text is neutralized in three
      layers" replace "kept in step as a declared pair … equivalence spec" with "both
      facades over the `:untrustedtext` owner, verified by the three-way identity spec";
      delete the See-also row for the `LogText ↔ FindingsSanitizer` pair; rewrite the
      "Two follow-ups are sequenced after the backstop" sentence so this change is
      provenance (done) and `type-untrusted-text` the remaining follow-up. Verify:
      `grep -n "literal\|declared pair\|manual-sync-pairs\|follow-ups"
      docs/adr/0004-logging-policy.md` shows only the OperatorEvent-in-`:operatorevent`
      statement and the type-untrusted-text follow-up.
- [ ] 5.4 `docs/glossary.md`: *operator event* owner → `OperatorEvent` in
      `:operatorevent`, drop the round-trip-spec sentence; *log text sanitization* owner
      sentence → facade over the `:untrustedtext` leaf, drop the declared-pair sentence;
      add *untrusted-text leaf* only if a second document needs the term (rule: define
      in place otherwise). Verify: entries read consistently with the module tree.
- [ ] 5.5 Full build: `./gradlew check` — layering gate, dependency-analysis, Spotless,
      Error Prone/NullAway, all Spock suites, PIT 100% in `:untrustedtext`,
      `:operatorevent`, `:logtext`, `:domain`, `:gnomish-plugin-api` (M2, M3). Verify:
      BUILD SUCCESSFUL; and for UX1/G3, `git diff HEAD --stat -- '*/src/test/**'
      '*/*/src/test/**'` shows only import-line edits, renames/moves, and the two
      replaced pair specs — no existing assertion changed (record the filtered diff in
      the task report).
- [ ] 5.6 Old-way sweep report (implementation.md): `grep -rn "logtext.OperatorEvent"`
      over the tree returns nothing; `grep -rn '"\[GF[0-9]' --include='*.java'` over
      production sources returns nothing (the gate rule of 4.4 now pins this); `grep -rln
      "Pattern.compile" logtext/src/main gnomish-plugin-api/src/main` returns no
      sanitizer file; `grep -rn "Kept in sync" --include='*.java'` over production
      sources names no `LogText`, `FindingsSanitizer`, `OperatorEvent` or domain-emitter
      end. Record all four in the task report. Verify: as stated (M1, M4).

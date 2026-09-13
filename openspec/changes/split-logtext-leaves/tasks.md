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
- [ ] 1.2 Move the text primitives from `LogText` into `untrustedtext.TextSafety`
      (working name, design D2): the character-class table, `strip`, `capTail`,
      `flatten`, `forConsole`, `capRecord`, `DEFAULT_CAP_CHARS`. Move their specs
      (`LogTextSpec` primitive features, `LogTextConsoleSpec`, `LogTextRecordCapSpec`)
      into the leaf's test tree, renamed for the owner. Traceability: `Implements FR1,
      NFR-S1 of split-logtext-leaves`. Verify: `:untrustedtext:check` green, PIT 100%.
- [ ] 1.3 Turn `LogText` into a facade: every public method delegates one-to-one to
      `TextSafety`; the `Kept in sync with FindingsSanitizer` javadoc paragraph is
      deleted; `logtext/build.gradle` gains `api project(':untrustedtext')` and
      `allowedProjects = [':untrustedtext']` (FR2, FR7). Verify: `:logtext:check` green
      (PIT posture per design — Risks, recorded in the build file if `excludedClasses`
      is used); no call site outside `:logtext` changed.
- [ ] 1.4 Turn `FindingsSanitizer` into a facade over `TextSafety`; delete its private
      table and its `Kept in sync with LogText` paragraph; `gnomish-plugin-api` reaches
      the leaf transitively through `:domain` (after 4.3) — until then, add it to the
      contract module's allowlist explicitly and remove the explicit entry in 4.3 (FR3).
      Verify: `FindingsSanitizerSpec` green unchanged in substance; the sample plugin
      compiles with `gnomish-plugin-api` as its only declared dependency.
- [ ] 1.5 TDD (red first): `TextSafetyOwnerSpec` in `:bootstrap` replacing
      `SanitizerPairEquivalenceSpec` (design D8, FR3): three-way identity of
      `LogText.strip` / `FindingsSanitizer.strip` / `TextSafety.strip` (and `capTail`)
      over the whole corpus, plus a source scan asserting neither facade holds an
      escape-character or control-class literal (seeded violation detected). Verify:
      red on a seeded private table, green on the tree; the old spec deleted.

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
      and the allowlist entry to each of those modules' `build.gradle` (design D3).
      Verify: `git diff --stat` shows only import lines plus build files; `./gradlew
      compileJava compileTestGroovy` green across the build.

## 4. Domain reaches the leafs

- [ ] 4.1 `domain/build.gradle`: `api project(':untrustedtext')`, `api
      project(':operatorevent')`, `allowedProjects = [':untrustedtext', ':operatorevent']`
      (FR6). Verify: `verifyModuleLayering` green; `DomainPuritySpec` green (the leafs
      are none of its forbidden packages).
- [ ] 4.2 TDD (red first): a `DomainPuritySpec` feature for design D5 — every project in
      `:domain`'s allowlist declares an empty allowlist itself and no external artifact
      in its production configurations. Verify: red when a leaf is given a fake
      dependency in a scratch build script; green on the tree.
- [ ] 4.3 Replace the four literal heads — `AttemptJournal:70`, `Events:56`,
      `RoundExecution:121`, `VerifyOrchestrator:150` — with `OperatorEvent.<CONSTANT>.head()`
      (FR5, design D4); delete `DomainOperatorEventHeadSpec`; remove the domain
      exemption from `LogContractGateSpec`'s scan and confirm the gate now covers
      `:domain` sources; remove the explicit leaf entry added to `gnomish-plugin-api` in
      1.4 if it was needed. Verify: `LogContractGateSpec` green with the domain in scope;
      a seeded literal head in a domain class fails it; `DomainLoggerAllowlistSpec`
      unchanged and green.

## 5. Published contract and durable documents

- [ ] 5.1 `gnomish-plugin-api/build.gradle`: version 0.6.0, a header comment naming this
      change and the reason (transitive `untrustedtext` in the jar graph), and
      `./gradlew :gnomish-plugin-api:updateApiCompatibilityBaseline` regenerating
      `compat-baseline/` to three jars (design D6, FR8). Verify: `japicmpApiGate` green;
      the diff against the previous baseline shows no signature change on
      `FindingsSanitizer`.
- [ ] 5.2 `manual-sync-pairs.md`: delete both dissolved rows from "Declared pairs with
      no shared classpath" (FR3, FR5). Verify: `grep -rn "Kept in sync with" */src/main
      */*/src/main` lists no `LogText`, `FindingsSanitizer` or `OperatorEvent` end.
- [ ] 5.3 ADR 0004: record the domain-may-reach-JDK-only-leafs principle beside accepted
      deviation 1 and drop the deviation's literal-head sentences; name this change as
      provenance (FR9). Verify: `grep -n "literal" docs/adr/0004-logging-policy.md`
      shows no remaining literal-head claim.
- [ ] 5.4 `docs/glossary.md`: *operator event* owner → `OperatorEvent` in
      `:operatorevent`, drop the round-trip-spec sentence; *log text sanitization* owner
      sentence → facade over the `:untrustedtext` leaf, drop the declared-pair sentence;
      add *untrusted-text leaf* only if a second document needs the term (rule: define
      in place otherwise). Verify: entries read consistently with the module tree.
- [ ] 5.5 Full build: `./gradlew check` — layering gate, dependency-analysis, Spotless,
      Error Prone/NullAway, all Spock suites, PIT 100% in `:untrustedtext`,
      `:operatorevent`, `:logtext`, `:domain`, `:gnomish-plugin-api` (M2, M3). Verify:
      BUILD SUCCESSFUL.
- [ ] 5.6 Old-way sweep report (implementation.md): `grep -rn "logtext.OperatorEvent"`
      over the tree returns nothing; `grep -rn '"\[GF[0-9]' --include=*.java` over
      production sources returns nothing; `grep -rln "Pattern.compile" logtext/src/main
      gnomish-plugin-api/src/main` returns no sanitizer file. Record all three in the
      task report. Verify: as stated (M1, M4).

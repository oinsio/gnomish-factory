# Design: split-logtext-leaves

## Context

See proposal.md — Why. Facts that shape the cut:

- `:logtext` holds 11 classes / 1115 lines (plus `package-info`): four
  text-safety classes — `LogText` (201 lines) and the three package-private
  primitives it drives, `CharacterTable` (110), `ConsoleNotation` (75),
  `LineFlattening` (56), all carved out by `harden-untrusted-text-sinks` — and
  seven logging-plumbing classes (`OperatorEvent`, `RepeatSuppressor` +
  `RepeatOccurrence` + `RepeatRecovery` + `FailureReason`, `MdcAwareThread`,
  `ShutdownPhase`). Its layering allowlist is `[]`; its only external edge is
  `slf4j-api`, needed by `MdcAwareThread` and `RepeatSuppressor`. Its test
  tree carries the corpus helpers `AdversarialCorpus` and `HostileCodePoints`
  and the four `LogText*Spec`s (`LogTextSpec`, `LogTextConsoleSpec`,
  `LogTextRecordCapSpec`, `LogTextIdempotenceSpec`).
- `OperatorEvent` imports nothing (JDK-only enum, 148 codes, already exposing
  both `code()` and `head()`). `FindingsSanitizer` imports only
  `java.util.regex.Pattern`; its `strip` and `capTail` bodies are byte-identical
  to `LogText`'s, and it also exposes `forLog` with a `LOG_TAIL_CAP_CHARS` cap.
- `:domain` allowlist `[]`; `DomainPuritySpec` is an ArchUnit rule over
  production bytecode forbidding `..adapter..`, `java.nio.file..`,
  `com.fasterxml.jackson..`; `DomainLoggerAllowlistSpec` pins the four logging
  classes. `:domain` already declares `slf4j-api`. The four emitters each
  carry a `Kept in sync with …logtext.OperatorEvent` javadoc paragraph.
- `gnomish-plugin-api` is at 0.6.0 (bumped by `fix-claim-epoch-fence`) with a
  committed `compat-baseline/` of two jars (`gnomish-plugin-api-0.6.0`,
  `domain`). The japicmp surface is the module's jar plus every *project*
  artifact on its `runtimeClasspath` (`api-compatibility-gate-conventions`), so
  any leaf `:domain` reaches — by `api` or `implementation` — enters the
  baseline.
- `verifyModuleLayering` is TRANSITIVE (`layering-conventions.gradle`): it
  walks the resolved `compileClasspath` and `runtimeClasspath` graphs and fails
  on any project outside the module's own `allowedProjects`, so a leaf
  reached through `:domain` or `:logtext` must be listed by every consumer of
  those (precedent: `:adapters` lists `:atomicfile` "transitive via
  `:application`").
- `LogContractGateSpec` today ACCEPTS a literal head: its feature "a coded
  site is not flagged, in either the catalog or the literal form" pins
  `log.error("[GF110] …")` as a valid coded site (design D15 of
  `harden-logging-observability`). There is no domain exemption in it; the
  domain sources are already in its scan and pass because of that feature.
- `SanitizerPairEquivalenceSpec` lives in `:application`'s test tree (the
  lowest module that sees both facades). `:bootstrap` keeps a second
  `AdversarialCorpus` in `testsupport`, declared a deliberate copy of
  `:logtext`'s because `:logtext` reaches no other project.
- `settings.gradle` lists modules explicitly.

## Goals / Non-Goals

**Goals:**
- Two JDK-only leafs with the extraction-ready posture of `:baseref`
  (proposal NFR-S1).
- Zero behavioral change; only relocations, delegations and import edits
  (proposal G3, M4).
- Both "no shared classpath" pairs dissolved into single owners (G1).

**Non-Goals:**
- Anything `type-untrusted-text` owns (the carrier, exits by consumer, the
  typed gate). This change ships the *home*, not the type.

## Decisions

**D1 — Two leafs, not one.** `:untrustedtext` (text safety) and
`:operatorevent` (the catalog) are separate modules. *Rationale:* they share
nothing but "JDK-only and reachable from the domain"; one module named for
both would be a category error and would give the published contract the
catalog it has no use for. The cost — one more `settings.gradle` line and one
more `build.gradle` of ~20 lines — is below the cost of the wrong name.
*Alternative rejected:* one leaf ("operator plane vocabulary") — discussed on
2026-09-11 and rejected by the user for exactly the coupling reason.

**D2 — Facade-over-leaf, not move-and-rename, for `LogText` and
`FindingsSanitizer`.** `LogText`'s public methods keep their names and
semantics and delegate one-to-one to `untrustedtext.TextSafety` (the leaf's
single owner class; the name is fixed, since `type-untrusted-text` already
builds on it). `CharacterTable`, `ConsoleNotation` and `LineFlattening` move
into the leaf unchanged as its package-private collaborators.
`FindingsSanitizer` does the same — `strip`, `capTail` and `forLog` all stay,
`forLog` composing the leaf's `strip` and `capTail` with its own cap constant.
*Rationale:* 44 production `LogText` import sites and every plugin author's
`FindingsSanitizer` call keep compiling; the japicmp diff for
`FindingsSanitizer` shows no signature change; `type-untrusted-text` re-cuts
the exits anyway and will retire the facade where it wants to. *Alternative
rejected:* move `LogText` wholesale to the leaf and re-import everywhere — the
same relocation noise this change is trying to keep out of the type change,
paid twice.

**D3 — `OperatorEvent` moves with a package rename; no facade.** The enum is
relocated to `com.github.oinsio.gnomish.operatorevent.OperatorEvent`; the
~80 production and ~96 test imports are edited mechanically (one `sed` over
the tree, reviewed as one commit). *Rationale:* an enum cannot be delegated —
a facade would be a second enum, which is a wire-vocabulary pair; and unlike
`LogText`, no third party names it. *Alternative rejected:* keep the package
name `…logtext.OperatorEvent` inside `:operatorevent` — a split package
across two modules, which the module system and the layering gate both
treat as one module's leak into another.

**D4 — Domain emitters take the constant; deviation 1 keeps its logger
half.** `AttemptJournal`, `Events`, `RoundExecution`, `VerifyOrchestrator`
render `OperatorEvent.X.head()` and lose their `Kept in sync with` javadoc
paragraphs; `DomainOperatorEventHeadSpec` is deleted;
`DomainLoggerAllowlistSpec` is unchanged (they still hold a logger — the
framework-free `EngineEvent.PortFailed` alternative stays deferred, as ADR
0004 records). What replaces the round-trip pin is a new rule in
`LogContractGateSpec` (proposal FR10): a string literal matching `"[GF` in
any production log call fails the build naming the site. The gate's present
feature that accepts the literal form (D15 of `harden-logging-observability`)
is inverted into the seeded-violation feature of the new rule, so the gate
keeps a pinned example of both the accepted and the rejected form. The
literal form was accepted only because the domain could not reach the
catalog; once it can, the acceptance is the escape hatch `implementation.md`
item 3 says to close. *Alternative rejected:* removing the four loggers now —
scope creep the ADR already declined. *Alternative rejected:* leaving the
gate as is and relying on review — after this change nothing else stops a
literal head from reappearing in any module, and the single-owner table
needs an enforcement that is not "convention".

**D5 — `:domain → JDK-only leafs` is stated as a principle, not as a list of
exemptions.** The module-layering spec and ADR 0004 say: the domain may
depend on a leaf that declares no internal module and no external library,
and on nothing else internal. A new `DomainLeafPuritySpec` in `:bootstrap`
(beside `DomainPuritySpec`, not inside it — that spec is an ArchUnit rule over
bytecode, and this check reads build files through `repoRoot`) asserts the
definition over every project in `:domain`'s allowlist: its
`layering { allowedProjects }` is empty and its `dependencies` block declares
no production-scope artifact. *Rationale:* the
next JDK-only leaf the domain needs (a value type for something else) should
not require a spec amendment; the principle is what the gate enforces.
*Alternative rejected:* listing the modules by name in the spec as one-off
exemptions — the third one would arrive as "just one more".

*Amended during apply (2026-09-15).* The principle admits a leaf; it does not
oblige the domain to declare an edge it does not use. `:domain` takes exactly
one leaf edge here — `:operatorevent`, which its four emitters read — and
`:untrustedtext` joins with `type-untrusted-text`, the change that brings the
first domain type naming it. Declaring the text leaf now was the original plan
and it fails `buildHealth`: dependency-analysis reports an unused declaration,
which is precisely the defect that gate exists to catch, and silencing it with
an exclusion would spend the project's only real protection against dead edges
to hold a placeholder. Nothing in G2 is lost — the leaf exists, sits below the
domain, and is admitted by the principle, so `type-untrusted-text` adds one
line per consumer instead of arguing layering. The published baseline is
unaffected, because `:gnomish-plugin-api` declares the text leaf on its own
account (D2's facade), so all four jars are on the contract's runtime
classpath either way. Consequence for D9: `:sandbox:core` lists
`:operatorevent` only, since the text leaf no longer reaches it.

**D6 — `gnomish-plugin-api` 0.6.0 → 0.7.0, baseline regenerated to four
jars.** The japicmp surface is every project artifact on the contract's
runtime classpath, so the baseline gains both `untrustedtext` and
`operatorevent` — the catalog arrives through `:domain`'s edge whatever
configuration `:domain` declares it in, which D1's two-leaf cut cannot
prevent and does not need to: a third party never *names* the catalog, and
its compile surface is unchanged. Pre-1.0, a MINOR bump with
`updateApiCompatibilityBaseline` in the same commit, per the precedent
comments in its `build.gradle` (the newest of which, `fix-claim-epoch-fence`,
took 0.6.0). `FindingsSanitizer`'s signatures are unchanged, so the japicmp
diff records nothing incompatible — only the two added artifacts.
*Alternative rejected:* keep 0.6.0 — the baseline jar set is part of the
gate's input; a silently changed jar graph is the drift the gate exists to
catch. *Alternative rejected:* choosing `implementation project(':operatorevent')` in
`:domain` in the belief that it keeps the catalog out of the contract — it
still lands on the runtime classpath and therefore in the baseline, so the
configuration cannot buy that. It is nonetheless the configuration `:domain`
declares, for an unrelated and decisive reason: no signature of `:domain` names
the enum — the head is a `String` read inside four method bodies — so
dependency-analysis requires `implementation`, and `api` would be a false claim
about the module's surface. The two facts are consistent: the configuration
settles who compiles against the catalog, the runtime graph settles who
resolves it, and only the second feeds the baseline.

**D7 — Leaf build files follow `:baseref` verbatim.** `library-conventions`
+ `layering-conventions`, `allowedProjects = []`, no `dependencies` block for
production scope, a header comment stating the emptiness is load-bearing
(the domain and the contract reach it). PIT scope: the leaf's own package.
*Alternative rejected:* `java-library` without the conventions — every other
leaf carries the gates; a leaf without PIT is a hole in the 100% rule.

**D8 — `SanitizerPairEquivalenceSpec` moves from `:application` to
`:bootstrap` as `TextSafetyOwnerSpec`.** It keeps its corpus and asserts
three-way identity (`LogText`, `FindingsSanitizer`, the leaf) plus "no facade
holds a character literal" (source scan through `repoRoot`, the same shape
`harden-untrusted-text-sinks` uses for its converters). It moves because the
source scan needs `repoRoot`, which only `:bootstrap`'s `test` task wires, and
`:bootstrap` sees both facades on one classpath. The `:bootstrap` twin of
`AdversarialCorpus` in `testsupport` stays as it is: its stated reason (the
`:logtext` corpus is unreachable from another module) still holds for test
trees, and folding the two is `type-untrusted-text`'s business when it
re-cuts the corpus around the carrier. *Alternative rejected:* deleting the
spec — the facades could re-acquire a private table; the identity spec is
what the single-owner table's "Enforced by" column names.

**D9 — Transitive allowlists are edited in one place per consumer, with the
edge named.** Because the layering gate walks the transitive graph (Context),
every module whose production classpath reaches a leaf lists it, in the same
task that adds the edge, with a comment naming the path it arrives through
(`// transitive via :domain`). The consumers are: `:sandbox:core`,
`:gnomish-plugin-api`, `:gnomish-plugin-api:sample` (both leafs via
`:domain`); `:application`, `:adapters`, `:adapters:agent`, `:adapters:git`,
`:adapters:github`, `:sandbox:docker`, `:bootstrap` (both leafs via `:domain`
and `:logtext`, and `:operatorevent` directly where they import it). The
gate itself is the proof the list is complete: a missed consumer is red.
*Rationale:* the gate's transitivity is the feature that makes the layering
reviewable as data; working around it (a shared "leaf set" the convention
plugin injects) would make the allowlists lie about what each module reaches.
*Alternative rejected:* making the convention plugin auto-allow any project
with an empty allowlist — it removes exactly the review the gate exists for,
and the third JDK-only leaf would arrive unreviewed.

## Sync surfaces

**Sync surfaces: two declared pairs dissolved, none added.** Both rows of
`manual-sync-pairs.md`'s "Declared pairs with no shared classpath" table that
this change touches are removed because the shared abstraction now exists
(preference 1 of that rule): `LogText ↔ FindingsSanitizer` collapses into
the `:untrustedtext` owner (D2, D8); `OperatorEvent ↔ domain literal heads`
collapses into the `:operatorevent` constant (D3, D4). The `Kept in sync
with` markers on `LogText` and `FindingsSanitizer` are deleted; the
`OperatorEvent` javadoc paragraph about the domain pair is deleted. The row
`HostRoundEnvironmentSource ↔ SandboxRoundEnvironmentSource` is untouched.

## Single-owner mechanisms

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `untrustedtext.TextSafety` (`:untrustedtext`) | the character-class table and the primitives `strip` / `capTail` / `flatten` / `forConsole` / `capRecord` | `logtext.LogText` (facade), `app.findings.FindingsSanitizer` (facade); via `LogText`: `SinkNeutralizer` and `SafeThrowableConverter` in `:bootstrap`, `SystemConsoleIO` in `:application` | `CharacterTable`, `ConsoleNotation`, `LineFlattening` and the primitive bodies in `LogText` — moved into the leaf; the private `ANSI` pattern, `isStrippedControl` and its three helpers in `FindingsSanitizer` — deleted, both classes become delegates | `TextSafetyOwnerSpec` (D8): three-way identity over the corpus + no character literal in either facade; the layering gate (`:logtext` and `:gnomish-plugin-api`'s transitive set include the leaf) |
| `operatorevent.OperatorEvent` (`:operatorevent`) | the `[GFnnn]` head, as `String` from `head()` | every production WARN/ERROR site — `:application` (41 files), `:adapters` (6), `:adapters:git` (13), `:adapters:github` (3), `:adapters:agent` (8), `:sandbox:docker` (9), and the four `:domain` emitters | the four literal heads in `AttemptJournal:70`, `Events:56`, `RoundExecution:121`, `VerifyOrchestrator:150` — deleted; `DomainOperatorEventHeadSpec` — deleted; the gate's acceptance of the literal form — inverted (D4) | `LogContractGateSpec`'s new literal-head rule (D4, FR10): a `"[GF` string literal in a production log call fails the build; seeded-violation feature pins it |

The `head()` value is a `String` a consumer could type by hand — which is
exactly what the new gate rule exists to catch; the primitive is acceptable
because the gate, not the type, is the enforcement (the same posture the
catalog has had since `harden-logging-observability`, now without the
literal-form exception).

## Risks / Trade-offs

- [A 176-file import rename hides a real edit] → the rename is its own
  commit containing only import lines and the two `settings.gradle`/
  `build.gradle` edits; the reviewer diff-filters `^[-+]import`.
- [The japicmp gate reads the baseline jar names; a stale `domain` jar in
  `compat-baseline/` fails the regeneration] → regenerate all four in one
  `updateApiCompatibilityBaseline` run, as the 0.5.0→0.6.0 precedent did.
- [A consumer's allowlist is missed and the gate goes red mid-sequence] →
  D9 names every consumer; group 4 adds them all in one task, and the gate
  itself is the completeness check.
- [PIT in `:logtext` drops to "no coverage" for the facade methods after
  delegation] → facade methods are arid delegation; list `LogText` under
  `excludedClasses` with the `TextSafetyOwnerSpec` named as the covering
  suite (testing.md, "arid wiring"), or keep the existing `LogTextSpec` as a
  facade spec — decide by what PIT reports; both are within the rule.
- [`type-untrusted-text` already names `TextSafety` and its methods] → the
  name and the primitive set are treated as a contract with that change; a
  rename here would break its artifacts, so none happens.

## Migration Plan

1. Create both leafs with their specs moved in (`LogTextSpec` primitives,
   `LogTextConsoleSpec`, `LogTextRecordCapSpec`, `LogTextIdempotenceSpec`,
   corpus helpers → `:untrustedtext`; `OperatorEventSpec` → `:operatorevent`).
   Build green.
2. Facades (`LogText`, `FindingsSanitizer`) delegate; `TextSafetyOwnerSpec`
   replaces the pair spec; markers removed; every consumer's allowlist gains
   `:untrustedtext` (D9). Build green.
3. `OperatorEvent` relocation commit (imports only, plus `:operatorevent` in
   every consumer's allowlist). Build green.
4. The domain's `:operatorevent` edge + emitters + literal-head gate rule +
   `DomainOperatorEventHeadSpec` deletion + `DomainLeafPuritySpec`. Build
   green.
5. plugin-api 0.7.0 + baseline; ADR 0004, glossary, `manual-sync-pairs.md`,
   module-layering main spec sync at archive.
Each step is independently revertible; no step changes observable output.

## Open Questions

None. Proposal Q1 is moot: `OperatorEvent` already exposes `code()`.

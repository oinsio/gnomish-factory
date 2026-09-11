# Design: split-logtext-leaves

## Context

See proposal.md — Why. Facts that shape the cut:

- `:logtext` holds 8 classes / 866 lines: `LogText` (text safety) and seven
  logging-plumbing classes (`OperatorEvent`, `RepeatSuppressor` +
  `RepeatOccurrence` + `RepeatRecovery` + `FailureReason`, `MdcAwareThread`,
  `ShutdownPhase`). Its layering allowlist is `[]`; its only external edge is
  `slf4j-api`, needed by `MdcAwareThread` and `RepeatSuppressor`.
- `OperatorEvent` imports nothing (JDK-only enum). `FindingsSanitizer` imports
  only `java.util.regex.Pattern`; its `strip` body is byte-identical to
  `LogText.strip`.
- `:domain` allowlist `[]`; `DomainPuritySpec` forbids `..adapter..`,
  `java.nio.file..`, `com.fasterxml.jackson..`; `DomainLoggerAllowlistSpec`
  pins the four logging classes. `:domain` already declares `slf4j-api`.
- `gnomish-plugin-api` is at 0.5.0 with a committed `compat-baseline/` of two
  jars (`gnomish-plugin-api`, `domain`); the japicmp gate walks the published
  jar graph. `verifyModuleLayering` pins its declared project set
  transitively.
- After `harden-untrusted-text-sinks`, `LogText` also carries `forConsole` and
  `capRecord`; both are text primitives and move with the table.
- The layering gate reads `allowedProjects` per module and walks transitive
  edges; `settings.gradle` lists modules explicitly.

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
semantics and delegate one-to-one to `untrustedtext.TextSafety` (working
name; the leaf's single owner class). `FindingsSanitizer` does the same.
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
render `OperatorEvent.X.head()`; `DomainOperatorEventHeadSpec` is deleted;
`DomainLoggerAllowlistSpec` is unchanged (they still hold a logger — the
framework-free `EngineEvent.PortFailed` alternative stays deferred, as ADR
0004 records). `LogContractGateSpec` already fails a literal head anywhere;
the domain sources simply stop being exempt. *Alternative rejected:*
removing the four loggers now — scope creep the ADR already declined.

**D5 — `:domain → JDK-only leafs` is stated as a principle, not as two
exceptions.** The module-layering spec and ADR 0004 say: the domain may
depend on a leaf that declares no internal module and no external library,
and on nothing else internal. `DomainPuritySpec` gains a feature that checks
the two allowed leafs against that definition (their allowlists are empty
and their `build.gradle` declares no external artifact). *Rationale:* the
next JDK-only leaf the domain needs (a value type for something else) should
not require a spec amendment; the principle is what the gate enforces.
*Alternative rejected:* listing the two modules by name in the spec as
one-off exemptions — the third one would arrive as "just one more".

**D6 — `gnomish-plugin-api` 0.5.0 → 0.6.0, baseline regenerated to three
jars.** The published jar graph gains `untrustedtext`; pre-1.0, a MINOR bump
with `updateApiCompatibilityBaseline` in the same commit, per the precedent
comments in its `build.gradle`. `FindingsSanitizer`'s signatures are
unchanged, so the japicmp diff records only the added transitive artifact.
*Alternative rejected:* keep 0.5.0 — the baseline jar set is part of the
gate's input; a silently changed jar graph is the drift the gate exists to
catch.

**D7 — Leaf build files follow `:baseref` verbatim.** `library-conventions`
+ `layering-conventions`, `allowedProjects = []`, no `dependencies` block for
production scope, a header comment stating the emptiness is load-bearing
(the domain and the contract reach it). PIT scope: the leaf's own package.
*Alternative rejected:* `java-library` without the conventions — every other
leaf carries the gates; a leaf without PIT is a hole in the 100% rule.

**D8 — `SanitizerPairEquivalenceSpec` becomes `TextSafetyOwnerSpec` in
`:bootstrap`.** It keeps the corpus and asserts three-way identity (`LogText`,
`FindingsSanitizer`, the leaf) plus "no facade holds a character literal"
(source scan, the same shape `harden-untrusted-text-sinks` uses for its
converters). It stays in `:bootstrap` because it needs both facades on one
classpath. *Alternative rejected:* deleting it — the facades could re-acquire
a private table; the identity spec is what the single-owner table's
"Enforced by" column names.

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
| `untrustedtext.TextSafety` (`:untrustedtext`) | the character-class table and the primitives `strip` / `capTail` / `flatten` / `forConsole` / `capRecord` | `logtext.LogText` (facade), `app.findings.FindingsSanitizer` (facade), the three sink converters in `:bootstrap` (via `LogText`) | the private table and primitive bodies in `LogText` and in `FindingsSanitizer` — deleted, both classes become delegates | `TextSafetyOwnerSpec` (D8): three-way identity over the corpus + no character literal in either facade; the layering gate (`:logtext` and `:gnomish-plugin-api`'s transitive set include the leaf) |
| `operatorevent.OperatorEvent` (`:operatorevent`) | the `[GFnnn]` head, as `String` from `head()` | every production WARN/ERROR site — `:application` (41 files), `:adapters` (6), `:adapters:git` (13), `:adapters:github` (3), `:adapters:agent` (8), `:sandbox:docker` (9), and the four `:domain` emitters | the four literal heads in `AttemptJournal:70`, `Events:56`, `RoundExecution:121`, `VerifyOrchestrator:150` — deleted; `DomainOperatorEventHeadSpec` — deleted | `LogContractGateSpec` (already fails any literal head; the domain exemption is removed from its allowlist) |

The `head()` value is a `String` a consumer could type by hand — which is
exactly what `LogContractGateSpec` exists to catch; the primitive is
acceptable because the gate, not the type, is the enforcement (the same
posture the catalog has had since `harden-logging-observability`).

## Risks / Trade-offs

- [A 176-file import rename hides a real edit] → the rename is its own
  commit containing only import lines and the two `settings.gradle`/
  `build.gradle` edits; the reviewer diff-filters `^[-+]import`.
- [The japicmp gate reads the baseline jar names; a stale `domain` jar in
  `compat-baseline/` fails the regeneration] → regenerate all three in one
  `updateApiCompatibilityBaseline` run, as the 0.4.0→0.5.0 precedent did.
- [PIT in `:logtext` drops to "no coverage" for the facade methods after
  delegation] → facade methods are arid delegation; list `LogText` under
  `excludedClasses` with the `TextSafetyOwnerSpec` named as the covering
  suite (testing.md, "arid wiring"), or keep the existing `LogTextSpec` as a
  facade spec — decide by what PIT reports; both are within the rule.
- [`type-untrusted-text` renames `TextSafety`] → it is one class in one leaf
  with two facade consumers; a rename there is cheap. The working name is
  chosen to avoid pre-empting that change's vocabulary.

## Migration Plan

1. Create both leafs with their specs moved in (`LogTextSpec` primitives →
   leaf; `OperatorEvent` specs → leaf). Build green.
2. Facades (`LogText`, `FindingsSanitizer`) delegate; `TextSafetyOwnerSpec`
   replaces the pair spec; markers removed. Build green.
3. `OperatorEvent` relocation commit (imports only). Build green.
4. Domain emitters + allowlists + `DomainOperatorEventHeadSpec` deletion +
   `DomainPuritySpec` feature. Build green.
5. plugin-api 0.6.0 + baseline; ADR 0004, glossary, `manual-sync-pairs.md`,
   module-layering main spec sync at archive.
Each step is independently revertible; no step changes observable output.

## Open Questions

None. Proposal Q1 (`code()` accessor) is resolved as "unchanged" — no
consumer needs it.

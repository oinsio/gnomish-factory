# Proposal: split-logtext-leaves

## Why

`:logtext` was created by `harden-logging-observability` (design D5) to answer
one question: where can a sanitizer live that `:application`, every adapter
module and the sandbox backends all reach? The answer — a dependency-free leaf,
slf4j-api only — was right, and the module then grew into the factory's
logging-support module: the operator-event catalog (`OperatorEvent`, 146 codes,
80 production imports), the repeat suppressor family, MDC propagation and the
shutdown-phase flag now sit beside `LogText`. Two concerns share one module, and
both of the codebase's remaining "declared pairs with no shared classpath"
(`manual-sync-pairs.md`) exist *because* of that packaging:

- `LogText ↔ FindingsSanitizer`: byte-identical `strip` bodies kept in step by
  hand, because D9 refused to pull `:logtext` — and with it `ShutdownPhase` and
  the whole catalog — into the published `gnomish-plugin-api` POM for the sake
  of 25 lines.
- `OperatorEvent ↔ four `:domain` literal heads`: `OperatorEvent` is a JDK-only
  enum, yet `:domain` repeats its codes as string literals because reaching it
  means an edge to the slf4j-bearing `:logtext`.

The next change, `type-untrusted-text`, needs a carrier type that `:domain`,
`:gnomish-plugin-api`, `:sandbox:core` and every adapter can name. It cannot
live in `:logtext` for exactly the reason above, and it cannot live in `:domain`
without making the domain the owner of terminal-safety policy. The clean cut is
the one the module already has inside it: text safety is not logging plumbing.
This change makes that cut before the type lands, so the type change carries
only its own semantics and none of the 140-import relocation noise.

## What Changes

- **ADDED** module `:untrustedtext` — a JDK-only leaf holding the untrusted-text
  primitives: the one character-class table, `strip`, `capTail`, `flatten`, the
  console notation, and the record cap (everything `LogText` owns today that is
  about *text*, not about *logging*). `LogText` in `:logtext` becomes a thin
  delegate over it, keeping every existing call site compiling unchanged.
- **ADDED** module `:operatorevent` — a JDK-only leaf holding the
  `OperatorEvent` catalog, moved out of `:logtext` with its package renamed;
  the four `:domain` emitters take their `[GFnnn]` head from the constant
  instead of a literal.
- **MODIFIED** `module-layering`: the module tree gains the two leafs; `:domain`
  may depend on JDK-only leafs (a principle, stated once); `:logtext` depends on
  `:untrustedtext`; `:gnomish-plugin-api` reaches `:untrustedtext` transitively
  and `FindingsSanitizer` delegates to it.
- **MODIFIED** `factory-logging`: the sanitizer requirement drops the declared
  pair (one table, one owner); the operator-event requirement drops the
  literal-head exemption (every emitter reaches the catalog).
- **MODIFIED** `plugin/plugin-api-contract`: the contract module's transitive
  surface gains `:untrustedtext`; the findings-sanitization utility is a
  delegate over it; **BREAKING** for pre-1.0 semver — a MINOR bump with a
  regenerated baseline, as `FindingsSanitizer`'s primitives now resolve to
  another module's types in the published jar graph.
- **REMOVED** from `manual-sync-pairs.md`: both "no shared classpath" rows, and
  the two specs that verified them (`SanitizerPairEquivalenceSpec` becomes a
  single-table spec; `DomainOperatorEventHeadSpec` is deleted).

## Capabilities

### New Capabilities
_None — the two modules are homes for existing behavior; their contracts are
stated as modifications of the layering, logging and plugin capabilities._

### Modified Capabilities
- `module-layering`: "Layered Gradle module tree" and "Enforced acyclic
  dependency direction" — two new leafs, the `:domain`-may-reach-JDK-only-leafs
  principle, the `:logtext → :untrustedtext` edge.
- `factory-logging`: "Untrusted text enters logs only sanitized" — the
  declared-pair sentence becomes a single-owner sentence; "Operator lines carry
  a stable event identity" — the literal-head exemption is removed.
- `plugin/plugin-api-contract`: "Thin plugin-api contract module" and "Findings
  sanitization available to every plugin" — the transitive surface and the
  delegate.

## Goals

- G1: After this change `grep -rn "Kept in sync with"` and the registry in
  `manual-sync-pairs.md` list no pair between `LogText`/`FindingsSanitizer` and
  none between `OperatorEvent` and `:domain` — both collapsed into one owner.
- G2: `:domain`, `:gnomish-plugin-api` and `:sandbox:core` can name a type from
  `:untrustedtext` — the precondition `type-untrusted-text` needs — while
  `:domain` still depends on no module that carries slf4j, Spring, Jackson or
  the filesystem.
- G3: No behavior changes: every existing spec passes unchanged in substance;
  the only source edits outside the two leafs are import lines and the four
  domain literals.

## Non-Goals

- NG1: The `UntrustedText` carrier, its exits, the typed gate —
  `type-untrusted-text`.
- NG2: Any change to what `LogText.forLog`, `forConsole` or `FindingsSanitizer`
  produce.
- NG3: Moving `RepeatSuppressor`, `MdcAwareThread` or `ShutdownPhase` — they
  are logging plumbing and stay in `:logtext`.
- NG4: Removing ADR 0004's accepted deviation 1 (the four domain classes still
  hold an SLF4J logger); only its literal-head half goes.
- NG5: Extracting `:untrustedtext` or `:operatorevent` to a separately
  published artifact — they are internal leafs like `:atomicfile`.

## Users & Scenarios

- U1: A plugin author depending only on `gnomish-plugin-api` calls
  `FindingsSanitizer.strip` as before; it compiles and behaves identically; the
  jar graph now also carries `untrustedtext-<version>.jar`, which their build
  tool resolves transitively like `domain`.
- U2: A developer adds a fifth domain emitter and writes
  `OperatorEvent.SOMETHING.head()`; the `DomainLoggerAllowlistSpec` still asks
  them to justify the fifth logger, but no literal code and no round-trip pin
  is needed.
- U3: `type-untrusted-text` declares `record TaskSnapshot(UntrustedText title,
  …)` in `gnomish-plugin-api` and `EscalationReport.CannotVerify(…,
  UntrustedText reason, …)` in `:domain`; both compile because both modules
  reach the leaf.

## Requirements

### Functional

- FR1: A JDK-only module `:untrustedtext` SHALL own the untrusted-text
  primitives: one character-class table (ANSI/CSI/OSC/DCS/APC/PM/SOS with
  terminators, C0, C1, DEL, bidi overrides and isolates, zero-width and tag
  characters, U+2028/U+2029), `strip`, `capTail`, `flatten`, the console
  notation and the record cap; it SHALL declare no internal module and no
  external library.
- FR2: `LogText` (`:logtext`) SHALL keep its public surface and semantics,
  delegating every primitive to `:untrustedtext`; no production or test call
  site outside `:logtext` needs an edit for `LogText`.
- FR3: `FindingsSanitizer` (`gnomish-plugin-api`) SHALL keep its public surface
  and semantics, delegating its primitives to `:untrustedtext`; the
  `Kept in sync with` markers on both classes are removed and the pair's row in
  `manual-sync-pairs.md` deleted; the equivalence spec becomes a single-table
  spec asserting `LogText.strip`, `FindingsSanitizer.strip` and the leaf's
  `strip` are the same function over the corpus.
- FR4: A JDK-only module `:operatorevent` SHALL own the `OperatorEvent` catalog
  under a package of its own; every production import moves; the catalog's
  content and the `[GFnnn]` codes are unchanged.
- FR5: The four `:domain` emitters (`AttemptJournal`, `Events`,
  `RoundExecution`, `VerifyOrchestrator`) SHALL render their message head from
  the `OperatorEvent` constant; the literal heads, the
  `DomainOperatorEventHeadSpec` and the registry row are removed.
- FR6: `:domain`'s layering allowlist SHALL name exactly `:untrustedtext` and
  `:operatorevent`; the module-layering spec SHALL state the principle that
  `:domain` may depend on a JDK-only leaf and on nothing else internal.
- FR7: `:logtext`'s layering allowlist SHALL name exactly `:untrustedtext`; its
  external dependency stays slf4j-api only.
- FR8: `gnomish-plugin-api` SHALL bump to 0.6.0 with a regenerated
  `compat-baseline/` that includes `untrustedtext`; the japicmp gate SHALL pass
  against it.
- FR9: ADR 0004 SHALL record the domain-may-reach-JDK-only-leafs principle and
  drop the literal-head half of accepted deviation 1; `docs/glossary.md` SHALL
  name the new owners of *operator event* and *log text sanitization*.

### Non-Functional Reliability

- NFR-R1: The relocation is behavior-preserving: every spec in every module
  passes with only import edits; PIT stays at 100% per module (the moved code
  carries its specs with it).

### Non-Functional Security

- NFR-S1: `:untrustedtext` SHALL be extraction-ready in the `:baseref` sense:
  an empty allowlist and no external library, so the text-safety policy can
  know nothing of subprocesses, trackers, logging or configuration formats.

## Operator Experience Criteria

- UX1: Nothing observable changes: log lines, codes, console output, tracker
  comments are byte-identical before and after.

## Success Metrics

- M1: `manual-sync-pairs.md` "Declared pairs with no shared classpath" table
  has one row left (`HostRoundEnvironmentSource ↔ SandboxRoundEnvironmentSource`).
- M2: `./gradlew projects` lists `:untrustedtext` and `:operatorevent`; the
  layering gate passes with the new allowlists; `:domain`'s allowlist is exactly
  the two leafs.
- M3: `./gradlew check` green with PIT 100% in `:untrustedtext`,
  `:operatorevent`, `:logtext`, `:domain`, `:gnomish-plugin-api`.
- M4: Zero lines changed in any production class outside the two leafs,
  `LogText`, `FindingsSanitizer`, the four domain emitters, and import lines.

## Open Questions

- Q1: Whether `OperatorEvent`'s `head()` should stay the only rendering method,
  or the enum should also expose `code()` for the ledger. Not needed by any
  consumer today; proposed: unchanged.

## Impact

- New: `untrustedtext/`, `operatorevent/` (build.gradle, package-info, specs).
- Edited: `settings.gradle`; `domain/build.gradle`, `logtext/build.gradle`,
  `gnomish-plugin-api/build.gradle` (allowlists, version, baseline);
  `LogText`, `FindingsSanitizer`, the four domain emitters; ~80 production and
  ~96 test files for the `OperatorEvent` import; `DomainOperatorEventHeadSpec`
  deleted; `SanitizerPairEquivalenceSpec` reshaped; `manual-sync-pairs.md`,
  ADR 0004, glossary, `module-layering` spec.
- Sequencing: after `harden-untrusted-text-sinks` (which adds `forConsole` and
  the record cap to `LogText` — both move here) and after
  `add-base-ref-resolution` (whose `module-layering` delta this one layers on).
  Before `type-untrusted-text`.

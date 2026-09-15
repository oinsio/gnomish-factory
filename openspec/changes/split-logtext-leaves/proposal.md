# Proposal: split-logtext-leaves

## Why

`:logtext` was created by `harden-logging-observability` (design D5) to answer
one question: where can a sanitizer live that `:application`, every adapter
module and the sandbox backends all reach? The answer — a dependency-free leaf,
slf4j-api only — was right, and the module then grew into the factory's
logging-support module: the operator-event catalog (`OperatorEvent`, 148 codes,
80 production imports), the repeat suppressor family, MDC propagation and the
shutdown-phase flag now sit beside `LogText` and the three text-primitive
classes it drives (`CharacterTable`, `ConsoleNotation`, `LineFlattening`,
carved out by `harden-untrusted-text-sinks`). Two concerns share one module, and
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
  may depend on JDK-only leafs (a principle, stated once) and takes its first
  such edge, to `:operatorevent`; `:logtext` depends on `:untrustedtext`;
  `:gnomish-plugin-api` declares `:untrustedtext` itself, because
  `FindingsSanitizer` delegates to it; because the layering gate is transitive,
  every module whose production classpath reaches a leaf through `:domain`,
  `:logtext` or `:gnomish-plugin-api` lists that leaf in its allowlist.
- **MODIFIED** `factory-logging`: the sanitizer requirement drops the declared
  pair (one table, one owner); the operator-event requirement drops the
  literal-head exemption (every emitter reaches the catalog) and the
  log-contract gate gains the rule that makes that stick — a literal
  `[GFnnn]` head in any production source fails the build.
- **MODIFIED** `plugin/plugin-api-contract`: the contract module's transitive
  surface gains the two leafs; the findings-sanitization utility is a delegate
  over `:untrustedtext`. No signature changes, so japicmp reports nothing
  incompatible; the version still moves (pre-1.0 MINOR, baseline regenerated)
  because the committed baseline jar set is part of the gate's input and a
  silently changed jar graph is the drift the gate exists to catch.
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
  principle with the domain's first edge under it (`:operatorevent`), the
  `:logtext → :untrustedtext` and `:gnomish-plugin-api → :untrustedtext` edges,
  transitive allowlist entries in every consumer that reaches a leaf.
- `factory-logging`: "Untrusted text enters logs only sanitized" — the
  declared-pair sentence becomes a single-owner sentence; "Operator lines carry
  a stable event identity" — the literal-head exemption is removed and the
  gate rejects literal heads.
- `plugin/plugin-api-contract`: "Thin plugin-api contract module" and "Findings
  sanitization available to every plugin" — the transitive surface and the
  delegate.

## Goals

- G1: After this change `grep -rn "Kept in sync with"` and the registry in
  `manual-sync-pairs.md` list no pair between `LogText`/`FindingsSanitizer` and
  none between `OperatorEvent` and `:domain` — both collapsed into one owner.
- G2: the `:untrustedtext` leaf exists below `:domain`, is named by
  `:gnomish-plugin-api` already, and is admissible to `:domain` and
  `:sandbox:core` under a stated principle rather than a per-module exemption —
  so `type-untrusted-text` adds a build-file line per consumer and no layering
  argument. That is the precondition it needs. Meanwhile `:domain` still depends
  on no module that carries slf4j, Spring, Jackson or the filesystem.
- G3: No behavior changes: every existing spec passes unchanged in substance;
  the only production source edits outside the two leafs and the two facades
  are import lines, the four domain literals and the javadoc pair markers.

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
  `FindingsSanitizer.strip`, `forLog` and `capTail` as before; it compiles and
  behaves identically; the jar graph now also carries
  `untrustedtext-<version>.jar` and `operatorevent-<version>.jar`, which their
  build tool resolves transitively like `domain`.
- U2: A developer adds a fifth domain emitter and writes
  `OperatorEvent.SOMETHING.head()`; the `DomainLoggerAllowlistSpec` still asks
  them to justify the fifth logger, but no literal code and no round-trip pin
  is needed.
- U3: `type-untrusted-text` declares `record TaskSnapshot(UntrustedText title,
  …)` in `gnomish-plugin-api` — which already declares the leaf — and
  `EscalationReport.CannotVerify(…, UntrustedText reason, …)` in `:domain`,
  whose one-line edge to the leaf that change adds beside the type that needs
  it. Neither has a layering case to make: the leaf is in place and the
  principle already admits it.

## Requirements

### Functional

- FR1: A JDK-only module `:untrustedtext` SHALL own the untrusted-text
  primitives: one character-class table (ANSI/CSI/OSC/DCS/APC/PM/SOS with
  terminators, C0, C1, DEL, bidi overrides and isolates, zero-width and tag
  characters, U+2028/U+2029), `strip`, `capTail`, `flatten`, the console
  notation and the record cap — today the classes `CharacterTable`,
  `ConsoleNotation`, `LineFlattening` and the primitive bodies of `LogText`; it
  SHALL declare no internal module and no external library.
- FR2: `LogText` (`:logtext`) SHALL keep its public surface and semantics,
  delegating every primitive to `:untrustedtext`; no production or test call
  site outside `:logtext` needs an edit for `LogText`.
- FR3: `FindingsSanitizer` (`gnomish-plugin-api`) SHALL keep its public surface
  (`strip`, `capTail`, `forLog` with its tail cap) and semantics, delegating
  its primitives to `:untrustedtext`; the
  `Kept in sync with` markers on both classes are removed and the pair's row in
  `manual-sync-pairs.md` deleted; the equivalence spec becomes a single-table
  spec asserting `LogText.strip`, `FindingsSanitizer.strip` and the leaf's
  `strip` are the same function over the corpus.
- FR4: A JDK-only module `:operatorevent` SHALL own the `OperatorEvent` catalog
  under a package of its own; every production import moves; the catalog's
  content and the `[GFnnn]` codes are unchanged.
- FR5: The four `:domain` emitters (`AttemptJournal`, `Events`,
  `RoundExecution`, `VerifyOrchestrator`) SHALL render their message head from
  the `OperatorEvent` constant; the literal heads, the four `Kept in sync
  with` javadoc paragraphs on those classes, the `DomainOperatorEventHeadSpec`
  and the registry row are removed. Every code that spec was the only test
  source naming SHALL be re-pinned in a spec that actually provokes its event
  through a real appender, not merely mentions its name — `GF110`-`GF113` and
  `GF042`, the last of which rode a code example in that spec's javadoc and so
  was never genuinely pinned.
- FR6: `:domain`'s layering allowlist SHALL name exactly the JDK-only leafs it
  uses — after this change `:operatorevent` alone, since the first domain type
  naming `:untrustedtext` arrives with `type-untrusted-text`; the
  module-layering spec SHALL state the principle that `:domain` may depend on a
  JDK-only leaf and on nothing else internal, so admitting the next leaf is a
  build-file line rather than a spec amendment. A leaf edge SHALL be declared by
  the change that uses it and not ahead of one: an unused declaration is what
  the dependency-analysis gate exists to reject, and the layering allowlist
  describes reach, not intent.
- FR7: `:logtext`'s layering allowlist SHALL name exactly `:untrustedtext`; its
  external dependency stays slf4j-api only.
- FR8: `gnomish-plugin-api` SHALL bump from 0.6.0 to 0.7.0 with a regenerated
  `compat-baseline/` holding the whole published project jar graph
  (`gnomish-plugin-api`, `domain`, `untrustedtext`, `operatorevent`); the
  japicmp gate SHALL pass against it with no signature change on
  `FindingsSanitizer`.
- FR9: ADR 0004 SHALL record the domain-may-reach-JDK-only-leafs principle,
  drop the literal-head half of accepted deviation 1, drop every remaining
  reference to the `LogText ↔ FindingsSanitizer` declared pair (the layer-2
  paragraph and the See-also row), and turn its "two follow-ups" sentence into
  provenance for this change; `docs/glossary.md` SHALL name the new owners of
  *operator event* and *log text sanitization*.
- FR10: The log-contract gate SHALL reject a `[GFnnn]` head spelled as a string
  literal in any production source; the catalog constant's `head()` is the only
  accepted form. The gate's existing "literal form is not flagged" feature is
  inverted, not deleted.
- FR11: Every module whose production classpath reaches `:untrustedtext` or
  `:operatorevent` — directly or transitively through `:domain` or `:logtext` —
  SHALL list it in its layering allowlist with a comment naming the edge it
  arrives through, because the layering gate walks the transitive graph.

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
  comments are byte-identical before and after. Evidence: no existing assertion
  is weakened or removed — the test-tree diff holds import lines, moved files,
  the two replaced pair specs, and five one-line assertions ADDED to specs that
  already drive the affected path (the re-pin below), each of which passes
  against unchanged production output.

## Success Metrics

- M1: `manual-sync-pairs.md` "Declared pairs with no shared classpath" table
  has one row left (`HostRoundEnvironmentSource ↔ SandboxRoundEnvironmentSource`).
- M2: `./gradlew projects` lists `:untrustedtext` and `:operatorevent`; the
  layering gate and `buildHealth` both pass with the new allowlists and
  declarations; `:domain`'s allowlist is exactly `:operatorevent`, and every
  entry in it satisfies the JDK-only-leaf definition; a seeded literal head in
  any module fails the log-contract gate.
- M3: `./gradlew check` green with PIT 100% in `:untrustedtext`,
  `:operatorevent`, `:logtext`, `:domain`, `:gnomish-plugin-api`.
- M4: Zero lines changed in any production class outside the two leafs,
  `LogText`, `FindingsSanitizer`, the four domain emitters, and import lines.

## Open Questions

- Q1 (resolved): `OperatorEvent` already exposes both `code()` and `head()`;
  the enum's surface is unchanged.

## Impact

- New: `untrustedtext/`, `operatorevent/` (build.gradle, package-info, specs).
- Edited: `settings.gradle`; `domain/build.gradle`, `logtext/build.gradle`,
  `gnomish-plugin-api/build.gradle` (allowlists, version, baseline); the
  allowlists of every other module that reaches `:domain` or `:logtext`
  (`:application`, `:adapters`, `:adapters:agent`, `:adapters:git`,
  `:adapters:github`, `:sandbox:core`, `:sandbox:docker`, `:bootstrap`,
  `:gnomish-plugin-api:sample` — `:sandbox:core` lists `:operatorevent` only,
  since `:untrustedtext` reaches it through neither `:domain` nor
  `:gitobjects`); `LogText`, `FindingsSanitizer`, the four domain emitters; ~80 production and ~96 test files for the `OperatorEvent`
  import; `DomainOperatorEventHeadSpec` deleted; `SanitizerPairEquivalenceSpec`
  moved from `:application` to `:bootstrap` and reshaped;
  `LogContractGateSpec` (one feature inverted, one rule added);
  `manual-sync-pairs.md`, ADR 0004, glossary, `module-layering` spec.
- Sequencing: after `harden-untrusted-text-sinks` (which adds `forConsole` and
  the record cap to `LogText` — both move here) and after
  `add-base-ref-resolution` (whose `module-layering` delta this one layers on).
  Before `type-untrusted-text`.

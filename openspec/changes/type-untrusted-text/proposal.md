# Proposal: type-untrusted-text

## Why

The factory's untrusted-text rule is enforced by name: a source gate recognizes
eight accessor names (`stderr()`, `stdout()`, `output()`, `label()`, …) at log
call sites. The 2026-09-11 audit of the whole tree found ~45 record fields that
carry text from outside the trust boundary — git and docker output, agent
banners and decision files, issue titles and claim-marker holders, `task.json`
values, `.gnomish/` manifest strings — and **none of them is a distinct type**:
each is a `String`, indistinguishable from a string the factory wrote itself.
That is the root cause of every laundering path the audit listed: fold the
text into an exception message (33 sites), a record's `toString()`, an MDC
value or an assembled report, and the name the gate keys on is gone. The
sink-side backstop (`harden-untrusted-text-sinks`) makes those paths *safe*;
it does not make them *visible*, and it does nothing for the tracker comment,
which is not a sink the log encoder owns — `TrackerFence`, the fenced-comment
owner, has one consumer while eight components write park reports around it.

The literature's answer is uniform (Google's safe-types design, Palantir's
`SafeArg`/`UnsafeArg`, the Checker Framework's tainting checker, "parse, don't
validate"): carry the trust status in the type, mint it once at the boundary,
let sinks accept only what the type can produce. With the two JDK-only leafs in
place (`split-logtext-leaves`), every carrier — domain records, the published
contract's `TaskSnapshot`, the sandbox port's `CapturedExec`, every adapter —
can name one type. This change introduces it and moves the codebase onto it.

## What Changes

- **ADDED** `untrusted-text` capability: the carrier `UntrustedText` — a value
  with provenance (`SUBPROCESS`, `CONTAINER`, `AGENT`, `TRACKER`, `MANIFEST`,
  `BRANCH_DOCUMENT`), a raw accessor reserved for annotated exit owners, three
  exits (`forLog`, `forConsole`, `forComment`), and a `toString()` that yields
  the log-safe form — so concatenation, the classic laundering move, becomes
  safe by default instead of unsafe by default.
- **MODIFIED** carrier types across `:domain`, `:gnomish-plugin-api`,
  `:sandbox:core` and the adapters: every field that holds attacker-influenced
  text becomes `UntrustedText` (the full list is design.md's carrier table).
  **BREAKING** for the published contract (`TaskSnapshot`, and the re-exposed
  domain types `EscalationReport`, `Verdict`, `PollStatus`, `CheckRef`,
  `ConfigError`, `TaskContext`) — a pre-1.0 MINOR bump.
- **MODIFIED** the seven capture families to mint the type where the text
  enters the process: git runner, docker CLI, in-box exec, agent stream/decision
  readers, tracker adapters, the `.gnomish/` loader, the task-branch document
  readers.
- **MODIFIED** the 33 exception constructors and the three report builders to
  take `UntrustedText`; their messages are built from the log-safe form.
- **MODIFIED** `factory-logging`: the accessor-name gate is replaced by a
  type-level gate — untrusted accessors return `UntrustedText`; `raw()` is
  called only from annotated exit owners; log/exception/report sinks never
  receive `UntrustedText` directly (only through an exit).
- **MODIFIED** `verification/verification-hardening`: the fenced-comment owner
  becomes the single owner of every tracker comment that carries untrusted text
  — all park reports, not only escalation details; `@`/`#` neutralization and
  the labeled fence apply to all of them.
- **MODIFIED** `plugin/plugin-api-contract`: `TaskSnapshot` carries
  `UntrustedText`; a third-party adapter mints it; 0.6.0 → 0.7.0.

## Capabilities

### New Capabilities
- `untrusted-text`: the carrier type — provenance, exits, the raw-access
  discipline, and the identity between what a mint received and what an exit
  renders.

### Modified Capabilities
- `factory-logging`: "Untrusted text enters logs only sanitized" — the gate's
  basis changes from accessor names to the carrier type.
- `verification/verification-hardening`: "Sanitized logs and fenced tracker
  publication" — the fence owns every untrusted tracker comment.
- `plugin/plugin-api-contract`: "Thin plugin-api contract module" — the
  snapshot's text fields are typed; "Surface growth is additive and
  re-baselined" — this growth is breaking pre-1.0 and re-baselined as such.

## Goals

- G1: A raw untrusted string cannot be logged, thrown, printed or posted
  without passing an exit: the compiler refuses it where a type mismatch
  exists, and one build gate refuses the residue (`raw()` outside an exit,
  an untrusted accessor returning `String`).
- G2: The 33 exception-constructor sites and the three report builders are
  brought under the rule by the type, not by a per-site `LogText` call — the
  "honest debt" section of `logging.md` is closed, not restated.
- G3: The tracker comment has one owner for untrusted text, with every park
  report as a consumer.
- G4: The accessor-name gate and its hand-maintained list are retired.

## Non-Goals

- NG1: Sink-side neutralization (done in `harden-untrusted-text-sinks`) —
  stays as defense in depth; this change does not remove it.
- NG2: The findings funnel (`Finding` records, `FindingsSanitizer` as a
  plugin's tool) — it is already a typed boundary of its own kind; a finding's
  text stays `String` prepared by the funnel.
- NG3: Secret masking; structured logging; MDC values (covered at the sink).
- NG4: Validated structural values — a ref name that passed `RefNameSyntax`,
  a task id that passed `TaskIdSanitizer` — stay `String`: they are parsed,
  not untrusted.
- NG5: A Checker Framework or Error Prone taint checker — the type plus an
  ArchUnit gate is the enforcement; a compiler plugin is not added.

## Users & Scenarios

- U1: A developer writes `throw new GitPersistFailedException(taskId, "commit",
  result.stderr())`. It compiles only because the constructor takes
  `UntrustedText`; the message it renders is single-line and capped, and the
  ERROR line that later logs it carries the escape as inert text.
- U2: A developer writes `log.warn("holder {}", marker.instance())` where
  `instance()` is `UntrustedText`. It compiles (SLF4J takes `Object`), and the
  rendered value is `toString()` — the log-safe form. The gate still flags the
  site, asking for an explicit `forLog()`, so intent is visible in the code.
- U3: A plugin author implementing a tracker adapter constructs
  `new TaskSnapshot(id, UntrustedText.tracker(title), UntrustedText.tracker(body))`
  — the contract says which text is untrusted, in the signature.
- U4: `TakeQuarantinePark` posts a park report quoting a branch shape and a
  git error; the comment arrives fenced and labeled, `@mentions` inert, like
  escalation details already do.

## Requirements

### Functional

- FR1: `UntrustedText` SHALL be a final value type in `:untrustedtext` holding
  the raw text and a provenance; equality by both; `raw()` returns the text;
  `toString()` returns exactly `forLog()`; `forLog()`, `forConsole()` and
  `forComment()` render through the leaf's owner primitives; a mint per
  provenance (`UntrustedText.subprocess(String)`, …); a bounded
  `excerpt(int)` for reports.
- FR2: The identity `exit(mint(x)) == primitive(x)` SHALL hold for every exit
  and every provenance over the adversarial corpus, and `toString()` SHALL
  equal `forLog()` byte for byte.
- FR3: `raw()` SHALL be called only from classes annotated `@UntrustedExit` in
  the leaf — the three exits, the JSON/state writers that carry raw bytes to a
  machine medium, and the findings funnel entry — enforced by an architecture
  spec; the annotation lives in the leaf so the gate keys on the class, not on
  a name list in build logic.
- FR4: Every carrier field in design.md's table SHALL be `UntrustedText`
  (`List<UntrustedText>` for lists); every mint site in design.md's mint table
  SHALL construct it with its provenance; no other production code SHALL call
  a mint.
- FR5: Every exception constructor that today concatenates subprocess or
  container output (design.md lists the 33) SHALL take `UntrustedText` and
  render its message from `forLog()`; `WorktreeResync` and the docker
  `IllegalStateException` sites SHALL move to a typed exception.
- FR6: The three base reports and every other report builder that quotes
  untrusted text SHALL take `UntrustedText` and render through an exit; the
  per-field `LogText.forLog` calls `add-base-ref-resolution` added are removed
  in favor of the type.
- FR7: The gate SHALL be type-level: (a) every production method named by the
  former accessor list, and every method returning text from a mint family,
  returns `UntrustedText`; (b) `raw()` only inside `@UntrustedExit` classes;
  (c) an `UntrustedText`-typed expression is never a direct argument of an
  SLF4J call, a `Throwable` constructor, a `ConsoleIO` print or a tracker
  write — it passes an exit first. The accessor-name gate is deleted.
- FR8: `TrackerFence` (moved to the leaf as the `forComment` exit's rendering,
  facade kept in `:application`) SHALL be the only path by which untrusted
  text reaches a tracker comment; the eight park-report writers use it.
- FR9: `docs/adr/0004-logging-policy.md` SHALL describe the three layers with
  the type as the second; `.claude/rules/logging.md` SHALL replace the
  accessor list and the "honest debt" paragraph with the type rule;
  `docs/glossary.md` SHALL define *untrusted text* and *provenance*.

### Non-Functional Reliability

- NFR-R1: Every existing spec keeps passing with type-level edits only; the
  end-to-end sink invariant from `harden-untrusted-text-sinks` keeps passing
  unchanged — the type adds a layer, it does not replace one.
- NFR-R2: Reading a `task.json` written before this change (raw `String`
  fields) SHALL mint `BRANCH_DOCUMENT` values on read; no wire format changes.

### Non-Functional Security

- NFR-S1: A newly added carrier field of type `String` whose accessor name is
  in the mint families' vocabulary fails the type gate; a new mint outside the
  mint table fails it; both are asserted with seeded violations.
- NFR-S2: The comment exit SHALL neutralize `@user` and `#123` references,
  fence with a run longer than any in the content, label the block, and keep
  line structure; asserted over the corpus plus a mention/fence corpus.

### Non-Functional Cost

- NFR-C1: `toString()` renders on demand and is not cached; a carrier is
  rendered at most once per sink — the sink backstop's idempotence makes a
  second pass a no-op, so no double cost on the hot path.

## Operator Experience Criteria

- UX1: Log, console and tracker output are unchanged for benign text; hostile
  text now renders inertly *and identically* whether it arrived through a
  report, an exception or a direct log argument.
- UX2: Every park comment quoting machine output is fenced and labeled the way
  escalation details already are — one look, not two.

## Success Metrics

- M1: `UntrustedLogTextGateSpec` is deleted; its replacement passes with zero
  allowlisted exemptions.
- M2: `grep -rn "\.stderr()\|\.stdout()\|\.output()" --include=*.java` over
  production sources finds only `UntrustedText`-returning accessors.
- M3: `TrackerFence`/`forComment` has ≥ 9 production consumers (the eight park
  writers plus the escalation dialog).
- M4: `logging.md` contains no "honest debt" paragraph and no accessor list.
- M5: PIT 100% in every touched module; `./gradlew check` green.

## Open Questions

- Q1: Whether `toString()` should render `forLog()` (proposed, safe by
  default) or throw (loud by default). Proposed: render — a throw turns every
  accidental concatenation into a production crash in a path that is, by
  construction, an error path already.
- Q2: Cut line if the change overruns four weeks: cut A ships subprocess/
  container/agent carriers, the exceptions, the type and the gate; cut B
  (tracker/manifest/branch-document carriers, reports, the fence ownership,
  the contract bump) becomes `type-untrusted-text-tracker`, sequenced after.

## Impact

- `:untrustedtext`: the type, provenance, `@UntrustedExit`, the comment exit
  (fence/mention logic moved from `TrackerFence`).
- `:domain`: `EscalationReport`, `Verdict`, `PollStatus`, `TaskOutcome.Aborted`,
  `TaskContext`, `ConfigError`, `CheckRef`, `Activity`, `StackTraces`.
- `:gnomish-plugin-api`: `TaskSnapshot`; version 0.7.0; baseline; sample.
- `:sandbox:core` / `:sandbox:docker`: `CapturedExec`, `DockerResult`,
  `DenialCursor`, exception sites.
- `:adapters:git`: `GitCommandResult`, `RemoteBaseRef`, `BaseRefreshOutcome`
  family, 19 exception sites, `TaskJsonMapper`; `:adapters:agent`: `AgentEvent`,
  `DecisionFileReader`, `MissingResultEventException`; `:adapters:github`:
  `ParsedMarker`, `GithubTaskFetcher`; `:adapters`: the `.gnomish/` loader.
- `:application`: `TakeResult`, `StatusReport`, the report builders, the
  renderers (`StatusLineFormatter`, `StatusTextRenderer`, `BoardTextRenderer`,
  `EscalationResumeDialog`), the eight park writers, `TrackerFence` facade.
- `:bootstrap`: the type gate (ArchUnit), deletion of the accessor gate.
- Sequencing: after `split-logtext-leaves`. No other active change edits the
  carrier records' field types; `add-tracker-task-hierarchy` and
  `add-pipeline-routing` touch `tracker-port` requirements this change does
  not modify.

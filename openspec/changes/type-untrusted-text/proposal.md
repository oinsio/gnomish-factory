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
text into an exception message (37 sites), a record's `toString()`, an MDC
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
  `BRANCH_DOCUMENT`), a raw accessor reserved for annotated exit owners, four
  exits (`forLog`, `forConsole`, `forComment` and its unfenced inline shape
  `forCommentInline`), and a `toString()` that yields the log-safe form — so
  concatenation, the classic laundering move, becomes safe by default instead
  of unsafe by default.
- **MODIFIED** carrier types across `:domain`, `:gnomish-plugin-api`,
  `:sandbox:core` and the adapters: every field that holds attacker-influenced
  text becomes `UntrustedText` (the full list is design.md's carrier table).
  **BREAKING** for the published contract (`TaskSnapshot`, the port payload
  `AbortRecord`, and the re-exposed domain types `EscalationReport`, `Verdict`,
  `PollStatus`, `CheckRef`, `TaskContext`) — a pre-1.0 MINOR bump. `ConfigError`
  is not a carrier (NG6).
- **MODIFIED** the seven capture families to mint the type where the text
  enters the process: git runner, docker CLI, in-box exec, agent stream/decision
  readers, tracker adapters, the `.gnomish/` loader, the task-branch document
  readers.
- **MODIFIED** the 37 exception constructors and the three report builders to
  take `UntrustedText`; their messages are built from the log-safe form.
- **MODIFIED** `factory-logging`: the accessor-name gate is replaced by a
  type-level gate — untrusted accessors return `UntrustedText`; `raw()` is
  called only from annotated exit owners; log/exception/report sinks never
  receive `UntrustedText` directly (only through an exit). Machine-readable
  capture — the `stdout` every caller parses rather than prints — leaves
  through a parsing exit with an allowlist of its own, so the exit-owner list
  stays the machine writers that put bytes on a medium a parser reads
  (design D11). The accessor-name scan is retargeted at the capture point
  rather than deleted: the type guards only text already minted, so the gate
  that catches a source nobody wrapped has to look at the capture (FR11,
  design D12).
- **MODIFIED** `verification/verification-hardening`: the fenced-comment owner
  becomes the single owner of every tracker comment that carries untrusted text
  — all park reports, not only escalation details; `@`/`#` neutralization and
  the labeled fence apply to all of them.
- **MODIFIED** `plugin/plugin-api-contract`: `TaskSnapshot` carries
  `UntrustedText`; a third-party adapter mints it; 0.7.0 → 0.8.0, the sixth
  BREAKING move.

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
- G2: The 37 exception-constructor sites and the three report builders are
  brought under the rule by the type, not by a per-site `LogText` call — the
  "honest debt" section of `logging.md` is closed, not restated.
- G3: The tracker comment has one owner for untrusted text, with every park
  report as a consumer.
- G4: The accessor-name gate stops guarding sinks — the type does that — and
  its hand-maintained list of accessor names is gone; the scan itself is
  retargeted at the capture point, which no type can guard.

## Non-Goals

- NG1: Sink-side neutralization (done in `harden-untrusted-text-sinks`) —
  stays as defense in depth; this change does not remove it.
- NG2: The findings funnel (`Finding` records, `FindingsSanitizer` as a
  plugin's tool) — it is already a typed boundary of its own kind; a finding's
  text stays `String` prepared by the funnel.
- NG3: Secret masking; structured logging; MDC values (covered at the sink).
- NG4: Validated structural values — a ref name that passed `RefNameSyntax`,
  a task id that passed `TaskIdSanitizer` — stay `String`: they are parsed,
  not untrusted. An agent-supplied model id joins them by being *made* parsed
  (`ModelIdSyntax`, design D11), so the token-usage map key, the state file
  and the dashboard keep their `String`.
- NG5: A Checker Framework or Error Prone taint checker — the type plus an
  ArchUnit gate is the enforcement; a compiler plugin is not added.
- NG6: `ConfigError` as a carrier. Its three fields stay factory-authored
  `String`: the message is a template the factory wrote, so a mint there would
  be `FACTORY` prose (FR4) carrying no capture — and the 97 constructor sites
  span `:domain` rules, the `.gnomish/` loader, the vendor bundle and the SPI
  validator interfaces third parties implement, so minting at each would put
  minting outside the factory, in code the factory does not own. The manifest
  fragment a message quotes (a key, a type token, a URL) is minted once, where
  the loader's outcome leaves the loader: `ConfigError.render()` returns a
  `MANIFEST` carrier (design D10).

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
- U5: A developer parsing `git rev-parse` output writes
  `result.stdout().forParsing().trim()` in `VerifiedTip`. It compiles because
  `VerifiedTip` is annotated `@UntrustedParser` and returns a verified commit
  id, not the text; the same line in a class that would hand the string back
  unchanged fails the gate, which tells them to carry the carrier instead.

## Requirements

### Functional

- FR1: `UntrustedText` SHALL be a final value type in `:untrustedtext` holding
  the raw text and a provenance; equality by the text alone — provenance
  travels with the value as evidence, not as part of its identity, so a value
  written to a durable medium and read back equals the value written;
  `raw()` returns the text;
  `toString()` returns exactly `forLog()`; `forLog()`, `forConsole()`,
  `forComment()` and `forCommentInline()` render through the leaf's owner
  primitives; a mint per
  provenance (`UntrustedText.subprocess(String)`, …); a bounded
  `excerpt(int)` for reports.
- FR2: The identity `exit(mint(x)) == primitive(x)` SHALL hold for every exit
  and every provenance over the adversarial corpus, and `toString()` SHALL
  equal `forLog()` byte for byte.
- FR3: `raw()` SHALL be called only from classes annotated `@UntrustedExit` in
  the leaf — the carrier itself, which holds the exits, and the writers that
  carry raw bytes to a machine medium: the JSON/state mappers and the judge
  verdict extractor — enforced by an architecture
  spec; the annotation lives in the leaf so the gate keys on the class, not on
  a name list in build logic. Parsing a machine-readable capture is a
  different way out with a different allowlist (FR10), so this set does not
  grow to hold parsers.
- FR4: Every carrier field in design.md's table SHALL be `UntrustedText`
  (`List<UntrustedText>` for lists); every capture site in design.md's mint
  table SHALL construct it with its provenance; every other mint SHALL take
  the family of the text it first wraps (design D3) — `FACTORY` for a sentence
  the factory composed, the capture's own family for text interpolated raw.
  Choosing the right family is a review obligation (`/audit-implementation`),
  not a build gate; what the build enforces is that no capture escapes a mint
  (NFR-S1).
- FR5: Every exception constructor that today concatenates subprocess or
  container output (design.md lists the 37) SHALL take `UntrustedText` and
  render its message from `forLog()`; `WorktreeResync` and the docker
  `IllegalStateException` sites SHALL move to a typed exception.
- FR6: The three base reports and every other report builder that quotes
  untrusted text SHALL take `UntrustedText` and render through an exit; the
  per-field `LogText.forLog` calls `add-base-ref-resolution` added are removed
  in favor of the type.
- FR7: The gate SHALL be type-level: (a) every production method declared in
  this repository whose name is in the capture vocabulary of the carrier table
  (design D4), and every method returning text from a mint family, returns
  `UntrustedText` — a third-party accessor the former list named
  (`JsonProcessingException.getOriginalMessage()`) is not a method this
  repository declares, so its text is minted at the catch site instead
  (design D5); (b) `raw()` only inside `@UntrustedExit` classes;
  (a2) `forParsing()` only inside `@UntrustedParser` classes, whose pinned set
  fails the spec on growth and each of whose members yields a value that is no
  longer untrusted text;
  (c) an `UntrustedText`-typed expression is never a direct argument of an
  SLF4J call, a `Throwable` constructor, a `ConsoleIO` print or a tracker
  write — every text-carrying `Tracker` method: `park`, `finish`,
  `declineFinished`, `acknowledgeDecision`, `postNote`, and `recordAbort`
  through `AbortRecord.cause` — it passes an exit first. The accessor-name
  gate stops guarding sinks (FR11).
- FR8: `TrackerFence` (moved to the leaf as the `forComment` exit's rendering,
  facade kept in `:application`) SHALL be the only path by which untrusted
  text reaches a tracker comment; every tracker write that carries untrusted
  text uses it — the eight park-report writers, the finish summary, the abort
  marker's cause (rendered by the adapter that posts it), the decision
  acknowledgement echoing a human reply, and the two stop notes.
- FR9: `docs/adr/0004-logging-policy.md` SHALL describe the three layers with
  the type as the second; `.claude/rules/logging.md` SHALL replace the
  accessor list and the "honest debt" paragraph with the type rule;
  `docs/glossary.md` SHALL define *untrusted text* and *provenance*.
- FR10: The carrier SHALL offer two ways out besides the exits and
  `raw()`: queries that yield no text (`isBlank()`, `contains(String)`,
  `length()`), open to every caller; and `forParsing()`, which yields the
  bytes as captured and SHALL be callable only from classes annotated
  `@UntrustedParser`. A `@UntrustedParser` method SHALL turn the text into a
  value that is no longer untrusted text — a typed value, a `String` that
  passed a named syntax gate, or a carrier re-minted with another provenance —
  and SHALL NOT return it unchanged as a plain `String`; a class that reads a
  document's content without converting it carries the carrier onward instead
  (design D11). A transformation that keeps the text inside the carrier —
  `cappedTo(int)`, the abort-cause budget's head+tail truncation returned as a
  carrier of the same provenance — SHALL need no annotation and SHALL NOT widen
  the exit allowlist, for the same reason the queries do not: no text leaves
  (design D13).
- FR11: The scan that preceded the type gate SHALL be retargeted rather than
  deleted: it stops keying on accessor names at log calls — rules (a), (b) and
  (c) own that — and instead guards the capture point, failing the build where
  production code outside the mint owners named in the mint table reads a
  subprocess stream, an HTTP response body, or a document file into a plain
  `String`. The scan SHALL assert it reached every file it claims to cover,
  on the `BaseHeadDefaultBoundarySpec` precedent, and its allowlist SHALL name
  each mint owner with the family it mints (design D12).

### Non-Functional Reliability

- NFR-R1: Every existing spec keeps passing with type-level edits only; the
  end-to-end sink invariant from `harden-untrusted-text-sinks` keeps passing
  unchanged — the type adds a layer, it does not replace one.
- NFR-R2: Reading a `task.json` written before this change (raw `String`
  fields) SHALL mint `BRANCH_DOCUMENT` values on read; no wire format changes.
  A carrier written to a document and read back SHALL equal the carrier
  written and render identically through every exit (FR1): the round trip
  crosses a medium, and provenance records which medium it was read from, not
  which one it came from originally.

### Non-Functional Security

- NFR-S1: A newly added carrier field of type `String` whose accessor name is
  in the mint families' vocabulary fails the type gate; a capture source that
  reads text from outside the trust boundary without minting fails the capture
  gate; both are asserted with seeded violations. The gate is on the *capture
  point*, not on the mint call: since `FACTORY` (FR4) is a family any class may
  legitimately mint, no allowlist of mint callers can separate a correct mint
  from a wrong one, so what the build can check is that nothing captures
  without minting.
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
  allowlisted exemptions. Measured at the close of cut B; at the close of cut A
  rule (b) is declared over cut A's families only (design D9) — the vocabulary
  grows by family, never by an exemption.
- M2: every capture accessor in rule (b)'s vocabulary returns the carrier —
  `UntrustedTextGateSpec` rule (b) is green over the whole tree with no
  exemption, and `RawCaptureGateSpec`'s allowlist names every source that reads
  raw bytes, each with the family it mints. Counted by the two gates, not by a
  grep over accessor names: `GitExec.Result.stderr` (the mechanics carve-out of
  design D3) and `ExecHandle.output()` (an `InputStream`, not text) answer the
  same names for reasons the type rule never covered.
- M3: every production text-carrying `Tracker` write (the eight park writers,
  `FinishEffect`, `DecisionAck`, the two stop notes) and every `new AbortRecord(`
  passes `TrackerPublicationOwnerSpec`; every `forComment()` caller in
  production is one of the builders and comment-rendering adapters design D7
  names, and no untrusted text reaches a tracker comment by another path.
  Counted by callers of the exit, not by the writers: the park writers receive
  builder output and never call the exit themselves (design D7).
- M4: `logging.md` contains no "honest debt" paragraph and no accessor list.
- M5: PIT 100% in every touched module; `./gradlew check` green.
- M6: `@UntrustedExit` names exactly the nine classes design D2 lists, every
  one of which really reads `raw()` — it does not grow to admit a parser, and it
  does not hold a marker no call warrants; `@UntrustedParser` names exactly the
  27 design D11 lists, each yielding a converted value. Both sets are pinned in
  `UntrustedTextGateSpec` and fail the build on growth, and the exit set's
  warrant is a spec of its own. (The counts are the ones the pinned sets
  enforce; the "nine" and "26" this metric carried until task 7.3's sweep
  predate tasks 2.4, 3.3, 5.0 and 6.1, each of which admitted a class while the
  metric was already written, and the "twelve" it then carried counted three
  markers that task 7.8 removed as unwarranted.)

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

- `:untrustedtext`: the type, provenance, `@UntrustedExit`, `@UntrustedParser`,
  the queries, `forParsing()`, the comment exit (fence/mention logic moved from
  `TrackerFence`).
- `:domain`: `EscalationReport`, `Verdict`, `PollStatus`, `TaskOutcome.Aborted`,
  `TaskContext`, `CheckRef`, `Activity`, `StackTraces`; `ConfigError.render()`
  becomes the manifest mint for validation text (fields unchanged, NG6).
- `:gnomish-plugin-api`: `TaskSnapshot`, `AbortRecord.cause`; version 0.8.0;
  baseline; sample. The SPI validator interfaces returning `ConfigError` are
  unchanged.
- `:sandbox:core` / `:sandbox:docker`: `CapturedExec`, `DockerResult`,
  `DenialCursor`, exception sites, 9 `@UntrustedParser` classes.
- `:adapters:git`: `GitCommandResult`, `RemoteBaseRef`, `BaseRefreshOutcome`
  family, 19 exception sites, `TaskJsonMapper`, 16 `@UntrustedParser` classes;
  `:adapters:agent`: `AgentEvent`, `DecisionFileReader`,
  `MissingResultEventException`, `ModelIdSyntax` + `TokenUsageMapper`; `:adapters:github`:
  `ParsedMarker`, `GithubTaskFetcher`, `GithubStateWrites` (the abort comment
  renders `AbortRecord.cause` through the comment exit); `:adapters`: the
  `.gnomish/` loader.
- `:application`: `TakeResult`, `StatusReport`, the report builders, the
  renderers (`StatusLineFormatter`, `StatusTextRenderer`, `BoardTextRenderer`,
  `EscalationResumeDialog`), the eight park writers, `FinishEffect` /
  `TakeFinishReport`, `AbortHandler`, `DecisionAck`, the two stop-note writers
  (`TakeContainerEngineExecution`, `RevocationHandler`), `TrackerFence` facade.
- `:bootstrap`: the type gate (ArchUnit) with both pinned annotation sets,
  deletion of the accessor gate.
- Sequencing: after `split-logtext-leaves`; before
  `add-subprocess-access-log`, which is unimplemented and edits the same
  factory-logging requirement ("Untrusted text enters logs only sanitized").
  Its delta still restates the pre-sinks text, so at its next revision it
  must be re-layered on the stable spec as synced by this change ("Layered on
  ... `type-untrusted-text`"); archiving it as written would replace the
  sink-layer paragraph and this change's type-level text. No other active
  change edits the carrier records' field types; `add-tracker-task-hierarchy`
  and `add-pipeline-routing` touch `tracker-port` requirements this change
  does not modify.

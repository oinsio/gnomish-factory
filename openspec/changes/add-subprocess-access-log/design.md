# Design: add-subprocess-access-log

## Context

See proposal.md — Why. The facts that shape the design:

- Every field the record needs is already computed at four seams and discarded:
  `GitProcessRunner` (adapters/git), `DockerCli` (sandbox/docker), the
  `TaskExecutionEnvironment.exec` port (the sole gnome-process seam — agent
  rounds, command checks, in-box git, both modes), and `GitExec` (gitobjects).
- Two hard module constraints exist below the seams: `:subprocess` has a
  neutrality contract (JDK-only, no logging, `allowedProjects = []`), and
  `:gitobjects` is extraction-ready (JDK + `:subprocess` only, never a
  factory module).
- `harden-logging-observability` (sequenced before this change) introduces the
  `:logtext` leaf — slf4j-api only, reachable from `application`, adapters,
  and sandbox backends — and the hardened Logback bootstrap configuration.
- The known secret-in-argv paths are concrete: `DockerCommands.exec` inlines
  env values as `-e NAME=value` (including `ANTHROPIC_AUTH_TOKEN` from the AI
  seam), and git argv can carry credential-bearing remote URLs
  (`CredentialScrub` already exists for stderr).
- Correlation keys (`taskId`, `stage`, `attempt`) live in the MDC
  (`MdcEventListener`); `StreamDrain` already demonstrates
  `MDC.getCopyOfContextMap()` capture for cross-thread work.

## Goals / Non-Goals

Design-level only (scope is in the proposal): keep format and redaction in
exactly one component; add no module and no dependency edge beyond the grants
`harden-logging-observability` already shapes; leave `:subprocess` and
`:gitobjects` contracts untouched.

## Decisions

**D1 — One emitter in `:logtext`; sites pass structured facts.** A single
emitter (access record value + JSON-line composer + redactor) lives in
`:logtext`, beside the untrusted-text sanitizer, and owns field names, schema
version, timestamps, truncation, and redaction (FR3, FR5, FR6). Emission
sites construct a structured fact (executable, argv, cwd, start instant,
duration, exit code, termination, family, declared secrets, MDC copy) and
hand it over. JSON is composed by hand with minimal escaping — `:logtext`
admits at most `slf4j-api`, and the record is flat scalars, so Jackson below
the application layer is not warranted. *Rationale:* four writers with one
format owner is the anti-scatter shape; `:logtext` is the only leaf every
spawn family's module may reach. *Alternative rejected:* per-site formatting
against a documented schema — four hand-synced format copies, exactly the
undeclared-pair defect `manual-sync-pairs.md` bans; also rejected: emitter in
`application` — invisible from `:sandbox:docker` and `:adapters:git` without
new inverted ports for a pure observability concern.

**D2 — Coverage = one decorator plus three direct emissions.** The
`TaskExecutionEnvironment` family is covered by an `AuditedEnvironment`
decorator (precedent: `LeasedEnvironment`, `SelfCheckedEnvironment`) in
`:sandbox:docker`, wrapping both the host and container adapters; it wraps
the returned `ExecHandle` and emits when the wait resolves
(`Exited`/`TimedOut`/`Interrupted`), using `ExecHandle.startedAt()` and the
MDC captured at `exec` time (FR2, FR8). `GitProcessRunner`, `DockerCli`, and
`GitExec` emit directly at the point where they already measure duration and
receive the `Termination` — the only places holding argv + outcome +
startedAt together. One record per resolved outcome; no start-event lines
(proposal Q2): every current caller of `exec` awaits its handle under the
supervision contract, so an unawaited handle is a bug, not a coverage class.
*Rationale:* the decorator gives both modes and all gnome-product processes
one implementation; the runners cannot be decorated from outside without
losing argv. *Alternative rejected:* hosting emission in `CaptureRunner` —
it is a record (nothing to decorate) and `:subprocess` neutrality forbids
logging and dependencies; also rejected: emitting at higher-level callers
(check runner, round runner) — they see neither the final argv nor, for
docker, the management commands at all.

**D3 — `:gitobjects` reports through a JDK-only observer hook.** `GitExec`
cannot import `:logtext` (extraction contract: JDK + `:subprocess` only), so
`:gitobjects` defines a small observer interface in its own public API — a
functional interface taking the execution facts as JDK types — with a no-op
default; `bootstrap` wires the factory's implementation, which forwards to
the D1 emitter. The module acquires no dependency (module-layering delta
pins this with a scenario). *Rationale:* keeps the format owner single while
honoring extraction-readiness; a no-op default means standalone extraction
just loses records, not compilation. *Alternative rejected:* direct SLF4J
JSON emission inside `GitExec` — duplicates the format and redaction into a
second owner, recreating the scatter D1 removes; also rejected: emitting at
`GitObjectsTaskRepository` call sites in `:adapters:git` — the plumbing argv
and timing are private to `GitExec`.

**D4 — Constructive redaction first, structural scrub second (FR4, NFR-S1).**
Three layers, in order, all inside the D1 redactor: (1) env-value elision —
the docker family passes argv with the `-e NAME=value` spans identified (the
seam that built them knows them), and the redactor logs names with a
placeholder value, never values; (2) exact-value substitution — seams that
inject secrets they hold in hand (the AI seam's token values, credential
userinfo in remote URLs the git adapter composes) declare those exact
strings in the fact, and the redactor replaces every occurrence anywhere in
argv; (3) the `CredentialScrub`-style `scheme://userinfo@` structural regex
as the second net over the whole rendered argv. Redaction precedes
truncation and the full-argv hash, so a secret is neither recoverable nor
confirmable from a hash. *Rationale:* the seams *know* the secret values —
pattern-matching alone is a guess, and a naive argv log leaks
`ANTHROPIC_AUTH_TOKEN` today via `docker exec -e`. *Alternative rejected:*
regex-only redaction (the vigilante-style heuristic as the sole mechanism) —
token formats change, and one miss is a credential in a rotated file;
also rejected: logging no argv at all — that is today's state, and it is
what makes the log unable to answer "what ran".

**D5 — Sink: dedicated logger name routed by bootstrap Logback (FR7).**
The emitter writes each line at INFO through a dedicated logger (e.g.
`gnomish.access`); `bootstrap`'s Logback config attaches a JSONL file
appender to that logger with additivity off, async with the same no-discard
stance `harden-logging-observability` sets for the file appender (the
per-execution volume is a few lines per second at worst, so a blocking
hand-off cannot stall a seam in practice), and `logback-test.xml` keeps test
runs out of operator files. *Rationale:* SLF4J is the one channel every
module already reaches with zero new edges — the application-layer
`LedgerAppender` is invisible from sandbox/adapters (module-layering), while
a logger name is layering-free; async + rotation come from Logback for free.
*Alternative rejected:* a dedicated file-writer component threaded through
constructors to four modules — new wiring through every seam for what
Logback already does; also rejected: appending to the serve ledger — wrong
lifecycle (serve-only, tracker-outcome vocabulary) and wrong layer.

**D6 — Rotation and retention are Logback's, bounded (proposal Q1).** Daily
UTC-named files (`access-YYYY-MM-DD.jsonl`) via time-based rolling with a
bounded history cap configured in bootstrap; no reader, no recovery, torn
last line tolerated by consumers — the same disposable-history stance as the
serve ledger. *Rationale:* the factory never reads this file back; inventing
a retention sweeper duplicates what the rolling policy does. *Alternative
rejected:* reusing the serve snapshot-writer retention sweep — it exists
only in serve mode, and the access log must live in `run`/`take` too.

**D7 — Record schema: OTel `process.*` names, per-line version, bounded argv
(FR5, FR6).** Field names follow the OTel process semantic conventions where
one exists (`process.executable.name`, `process.command_args` — redacted,
`process.exit.code`, `error.type` carrying the termination for non-exited
outcomes), plus factory fields: `schema_version`, RFC3339 UTC `ts`,
`duration_ms`, `termination`, `family`, `cwd`, `taskId`/`stage`/`attempt`/
`component` from the MDC copy. Argv over the budget is truncated with a
marker plus a SHA-256 of the full redacted argv. *Rationale:* semconv names
make the file readable by standard tooling without a bespoke schema doc;
the hash keeps truncated invocations distinguishable. *Alternative
rejected:* a fully bespoke vocabulary — no interop payoff and one more
dictionary to document.

## Sync surfaces

**D8 — Sync surfaces: one shared abstraction, one declared pair.**

- The four emission sites are **not** a manual-sync pair: they share the D1
  emitter as the single implementation of format + redaction
  (preference order 1 of `manual-sync-pairs.md`). A site contributes only its
  facts; no site holds a copy of the schema, the tokens, or the scrub. This
  is the design's answer to the multi-writer question: adding a field or a
  redaction rule is a one-module change in `:logtext`.
- One **declared pair** is created deliberately: `:logtext` cannot import
  `:subprocess`, so the emitter defines its own termination vocabulary
  (`exited | timed_out | interrupted`), and the emission sites map
  `subprocess` `Termination` (and the decorator maps `ExecHandle.Wait`) into
  it. This is the same deliberate layer decoupling as
  `FeedState`/`FeedPhase`: constant sets must match. Both ends carry the
  `Kept in sync with` javadoc marker per `manual-sync-pairs.md`, and a
  data-driven round-trip spec iterates every `Termination` constant and every
  `ExecHandle.Wait` variant into the wire tokens (testing.md's wire-vocabulary
  rule), so a constant added on one side fails a spec, not production.
  *Alternative rejected:* letting the emitter accept plain strings with no
  owned vocabulary — that silently reopens per-site token drift.
- No end of any pair already registered in `manual-sync-pairs.md` is touched
  in a way that changes its synchronized invariant; the emission call added
  to a twin (e.g. host/container environment adapters) lands through the one
  decorator, not per-twin edits.

## Risks / Trade-offs

- [A new seam starts injecting a secret without declaring it] → the
  structural second net catches URL-shaped credentials; the per-seam
  leak-scan specs and the E2E fake-secret scan (M2) are the gate for the
  rest; the capability spec makes declaration a requirement, so review has a
  citable rule.
- [Blocking async hand-off under a burst] → volume is bounded by subprocess
  rate (orders of magnitude below queue capacity); accepted for the
  no-discard guarantee, consistent with harden-logging NFR-R1.
- [`:gitobjects` extracted standalone loses its records] → accepted by
  construction: the no-op default is the extraction contract's price, and
  in-factory wiring always installs the observer.
- [Sequencing on `harden-logging-observability` (large, in flight)] → this
  change starts after `:logtext` and the Logback hardening land; if that
  change re-scopes, the emitter's home moves with the module decision there
  — never duplicated here.
- [Truncation budget too small for real docker exec argv] → the budget is a
  named constant with a derivation comment; the hash preserves
  identifiability regardless of the cap chosen.

## Migration Plan

Purely additive: a new file appears beside the existing logs; no schema or
data migration, no config required to keep prior behavior (the appender
ships enabled; disabling is a Logback level override on the dedicated
logger). Rollback = removing the appender and the emission calls; no durable
state depends on the file.

## Open Questions

None — proposal Q1 resolved by D6, Q2 by D2.

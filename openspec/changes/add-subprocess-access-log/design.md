# Design: add-subprocess-access-log

## Context

See proposal.md — Why. The facts that shape the design:

- Most fields the record needs are already computed and discarded at three of
  the four seams: `GitProcessRunner` (adapters/git), `DockerCli` (sandbox/
  docker), and the `TaskExecutionEnvironment.exec` port (the sole gnome-process
  seam — agent rounds, command checks, in-box git, both modes) all measure
  duration and receive the termination. Two gaps must be closed by this
  change: the measured starts are monotonic `System.nanoTime()` readings, not
  the wall-clock start timestamp FR1 requires — an injected `Clock` supplies
  it at each emission site (time injection per testing.md); and `GitExec`
  (gitobjects) measures nothing today (its `await` is deliberately unbounded),
  so start capture and duration measurement land together with the observer
  hook.
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
- Correlation keys live in the MDC: `stage`/`attempt` are set by
  `MdcEventListener`, `taskId` by the runner boundaries (manual-run drive,
  take dispatcher/slot/resume bootstraps); `StreamDrain` already demonstrates
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

**D2 — Coverage = one decorator plus four direct emissions.** The
`TaskExecutionEnvironment` family is covered by an `AuditedEnvironment`
decorator (precedent: `LeasedEnvironment`, `SelfCheckedEnvironment`) in
`:sandbox:docker`, wrapping both the host and container adapters. The two
modes wire differently today: the container path already has a decorator
layer (the container environment builder / bootstrap run support apply
`SelfCheckedEnvironment` and `LeasedEnvironment`), while the host path has
none — three separate host environment sources (round, judge, check) each
return a bare `HostTaskExecutionEnvironment` — so the decorator is applied
at each of those four wiring points, not at one choke point. It is applied
**innermost**, directly around the raw adapter (FR12): the audit showed
`EnvironmentSelfCheck` holds the raw environment, so an outer decorator
would silently miss the five self-check probes — the first commands a new
box runs — and innermost placement additionally records the final composed
command (proxy env included) while still observing the raw wait, because
the forensics handle wrapper sits inside the adapter. The self-check is
constructed over the decorated exec seam. The decorator wraps the returned
`ExecHandle` and emits when the wait resolves
(`Exited`/`TimedOut`/`Interrupted`), using `ExecHandle.startedAt()` and the
MDC captured at `exec` time (FR2, FR8); `Wait.Exited` carries no exit code,
so the decorator obtains it the way the forensics wrapper does, via
`waitForExit()` after a natural exit. `GitProcessRunner`, `DockerCli`
(management commands at `capture`), the container file channel (its own
`docker exec` spawns, at the channel's wait resolution — FR10), and
`GitExec` emit directly at the point where each holds argv + outcome +
startedAt together. One record per resolved outcome; no start-event lines
(proposal Q2): every current caller of `exec` awaits its handle under the
supervision contract, so an unawaited handle is a bug, not a coverage
class; a spawn that never produced a process is the one exception and gets
its own record (D10). *Rationale:* the decorator gives both modes and all
gnome-product processes one implementation; the runners cannot be decorated
from outside without losing argv. *Alternative rejected:* hosting emission
in `CaptureRunner` — it is a record (nothing to decorate) and `:subprocess`
neutrality forbids logging and dependencies; also rejected: emitting at
higher-level callers (check runner, round runner) — they see neither the
final argv nor, for docker, the management commands at all; also rejected:
outermost decorator placement — it reads as "audit sees everything" but
provably misses the probe execs and the proxy-composed env.

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

**D4 — Constructive redaction first, structural scrubs second (FR4,
NFR-S1).** With D9 (no wrapper argv logged) and D11 (no env values on the
wrapper argv at all), the primary control is that secret-bearing text never
reaches a record's inputs; the redactor's layers are ordered nets over what
remains: (1) exact-value substitution — seams that hold secrets in hand
(the AI seam's token values, credential userinfo in remote URLs the git
adapter composes) declare those exact strings in the fact, and the redactor
replaces every occurrence anywhere in any record; (2) structural second
nets over the whole rendered argv — the `CredentialScrub`-style
`scheme://userinfo@` regex, plus an env-span scrub that rewrites any
`-e NAME=value` / `--env NAME=value` span to a value-less form, so a future
seam that reintroduces env-on-argv leaks a placeholder, not a value.
Environment-family records carry env variable *names* only, taken from the
composed env map, never values. Redaction precedes truncation and the
full-argv hash, so a secret is neither recoverable nor confirmable from a
hash. *Rationale:* the seams *know* the secret values — pattern-matching
alone is a guess; and moving env off the argv at the source (D11) follows
the systemd-credentials posture: fix the injection, then treat log
redaction as defense in depth. *Alternative rejected:* regex-only redaction
(the vigilante-style heuristic as the sole mechanism) — token formats
change, and one miss is a credential in a rotated file; also rejected:
logging no argv at all — that is today's state, and it is what makes the
log unable to answer "what ran".

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

**D9 — The environment family logs the logical layer; the wrapper argv is
never a record (FR9).** For gnome-product processes the record describes
what the factory asked the box to run — the `ExecCommand` argv — enriched
with the physical facts the port observes (duration, exit code, termination)
plus a `mechanism` token (`host | container`), the container identity where
one exists, env variable names from the composed map, and a generated
execution id for joining the record to the round transcript and attempt
journal. The physical `docker exec …` wrapper argv gets no record of its
own: its only content beyond the logical command is transport plumbing and
(today) the env injection D11 removes. This is the convergent
external-precedent shape: Bazel's execution log records the action's own
argv and demotes the sandbox wrapper to a `runner` field; sudo logs the
command it wraps; CI runners keep wrapper argv out of the durable job
record; the systems that recorded the request/transport layer (Kubernetes
exec audit, Nomad HTTP audit) got the secret leak and no
exit-code/duration. *Rationale:* the log answers "what did the factory
execute", and the answer an operator can act on is the logical command; the
wrapper is reconstructable from `mechanism` + container id. *Alternative
rejected:* measuring `DockerCli.start` and logging the physical argv — the
`Process` is handed off so `DockerCli` never learns the termination kind,
the argv drags secrets into scope, and it duplicates every environment
record for dedup to clean up.

**D10 — Start failures get a record; the vocabulary gains `start_failed`
(FR11).** A `ProcessStartException` means no handle and no wait, so
handle-based emission never fires; the seam that catches it emits a record
with termination `start_failed`, no exit code, zero-ish duration. It is a
fourth, emitter-owned token: it maps from no `Termination` constant and no
`Wait` variant, and the round-trip spec pins it as emitter-only (Bazel's
`status`-vs-`exit_code` separation, folded into the existing termination
field). *Rationale:* "the factory tried to run X and could not start it" is
exactly an access-log fact, and completion-only silence here reproduces the
Kubernetes-audit blind spot. *Alternative rejected:* a separate `status`
field beside `termination` — two fields whose value sets overlap invite
inconsistent pairs; one closed vocabulary keeps the round-trip spec total.

**D11 — Env moves off the container exec argv at the source (FR14, FR15).**
`DockerCommands.exec` stops rendering `-e NAME=value`: it emits value-less
`-e NAME` flags, and the docker client process receives the values through
its own (cleared, explicitly composed) environment — the docker CLI
propagates a value-less `-e NAME` from its environment into the box. In the
same stroke, the git and docker client subprocesses stop inheriting the
factory's full environment: their builders clear and re-add only what each
client needs (FR15), which is also what makes the value-propagation path
deliberate rather than ambient. Host mode already passes env off-argv via
`ProcessBuilder.environment()`; after D11 the two media are symmetric.
*Rationale:* the token in `docker exec` argv is world-readable via
`ps`/`/proc/cmdline` for the whole round; log-side redaction protects only
our log. Fixing the source is the systemd-credentials posture and shrinks
the redactor's first net to declared values. *Alternative rejected:*
`--env-file` on a 0600 file — workable, but adds file lifecycle (creation,
permissions, cleanup on crash) for no gain over client-env propagation;
also rejected: keeping values on argv and relying on redaction — leaves the
world-readable exposure untouched.

**D12 — The three silent unbounded waits get deadlines (NFR-R2).** The
container file channel bounds its `docker exec` waits with the configured
docker-command timeout (the bound `DockerCli.start`'s javadoc already
assigns to the caller — the caller now takes it); the environment
self-check probes and in-box service git commands pass a deadline through
`waitForExitOrTimeout` instead of the unbounded `waitForExit`; the docker
runtime probe is constructed with the operator-configured timeout instead
of the default. Expiry follows each seam's standard timeout handling and
emits its access record with termination `timed_out`. Waits that stay
unbounded (local `GitExec` plumbing, local git per bound-subprocess NG3)
keep their written justifications. *Rationale:* each of these is a
permanent-stall path — a wedged `docker exec` in `putFile` hangs the take
forever, and a wedged probe blocks every round; the supervision spec's
"optional deadline" made the omission legal but the silence was not.
*Alternative rejected:* a global "no null deadline" rule in
`ProcessSupervisor` — it would outlaw the deliberately unbounded local-git
cases that bound-subprocess-commands justified.

**D13 — The judge WARN goes through the choke point; the gate learns the
shape (FR16).** `JudgeVerdictExtractor`'s raw-message WARN switches from
`FindingsSanitizer.forLog` (no newline flattening — it guards a different
trust boundary) to `LogText.forLog`, restoring ADR 0004's "no embedded
newline can forge a record" invariant; the untrusted-log-text gate is
extended so a raw model/agent string held in a local variable cannot slip
past the accessor-pattern scan again (at minimum: `FindingsSanitizer.` as a
callee inside a log argument fails the gate outside the findings funnel).
*Rationale:* the site's own javadoc justified the funnel choice against a
rule that predates FR6/ADR 0004; the divergence is silent today and the
access log raises the stakes of log-record integrity. *Alternative
rejected:* teaching `FindingsSanitizer.forLog` to flatten — it would break
its documented contract for findings, which deliberately preserve line
structure.

**D14 — The spawn-boundary gate enumerates the real spawn map (FR13).**
`ProcessSpawnBoundarySpec` widens from "two adapter packages must not touch
`ProcessBuilder`" to a whitelist of the exact allowed spawn sites (the two
environment adapters, `DockerCli`, `CaptureRunner`, `GitExec`, the
container file channel); any other `ProcessBuilder` reference anywhere in
production sources fails the build, which is the mechanical form of FR2's
"no fifth path". The sole-seam wording in `TaskExecutionEnvironment`'s
javadoc and the `execution-environment` spec is narrowed to name the two
disclosed bypasses (file channel; git `ext::` grandchild, which the
supervised git tree kills and reaps). *Rationale:* the audit found the gate
green while the claim was false — the bypass package was on the allowed
side; a claim the build cannot check rots. *Alternative rejected:* leaving
the claim broad and documenting exceptions in prose only — that is the
state that produced the finding.

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
  (`exited | timed_out | interrupted | start_failed`), and the emission
  sites map `subprocess` `Termination` (and the decorator maps
  `ExecHandle.Wait`) into it; `start_failed` is emitter-owned and maps from
  no constant (D10). This is the same deliberate layer decoupling as
  `FeedState`/`FeedPhase`: constant sets must match. Both ends carry the
  `Kept in sync with` javadoc marker per `manual-sync-pairs.md`, and a
  data-driven round-trip spec iterates every `Termination` constant and every
  `ExecHandle.Wait` variant into the wire tokens (testing.md's wire-vocabulary
  rule) and pins `start_failed` as the sole unmapped token, so a constant
  added on one side fails a spec, not production.
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
- [`fix-denial-attribution-durability` (active) adds a build gate requiring
  every delegating view of the port to forward the whole interface, and
  widens the port surface (denial findings/cursor/restore)] → soft ordering,
  not a blocker: prefer landing that change first so `AuditedEnvironment` is
  written complete against the final surface under the gate from day one;
  if this change lands first, that change extends its gate green-run and
  denial forwarding over `AuditedEnvironment`.
- [`polish-sandbox-forensics` (active) wraps the same `ExecHandle` returned
  by the container adapter's `exec()` and edits the same decorator
  neighborhood] → the two wrappers compose: its handle wrapper lives inside
  the adapter, so the audited decorator (innermost around the adapter, D2)
  still observes the raw wait; coordinate landing order to avoid a textual
  merge conflict in the container environment classes.
- [A round that dies with the factory leaves no record] → accepted
  completion-only gap, named: a multi-minute agent exec killed together
  with the factory (SIGKILL, OOM, host crash) is invisible to the access
  log. The compensating owners are the attempt journal and the task
  branch, which already record what was in flight; the paired
  begin/end-record pattern (Kubernetes audit, docker `exec_create`/
  `exec_die`) is deliberately not adopted for a single-host operational
  log, and the execution id (D9) is the hook if it is ever needed.
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

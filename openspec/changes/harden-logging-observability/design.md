# Design: harden-logging-observability

## Context

See proposal.md — Why. Current state that shapes the approach:

- The engine's run window is already event-shaped: seven sealed `EngineEvent`
  variants rendered by `LoggingEventListener` at INFO, MDC maintained by
  `MdcEventListener`. The gaps are *around* the run: claim, serve lifecycle,
  container lifecycle, git lifecycle commits.
- Per-task summary facts already exist in serve mode: `TaskOutcomeLine`
  (outcome, parkReason, stage, attemptsUsed, startedAt/finishedAt, wallMillis,
  tokensByModel), assembled at the slot's terminal write point.
- Logback: rolling file at INFO, console WARN+ (stdout) and ERROR+ (stderr),
  root INFO, no test config, no charset pinned, all appenders synchronous.
  Spring Boot auto-registers a shutdown hook that closes the context and stops
  logging concurrently with the serve shutdown hook.
- Module layering (post `bound-subprocess-commands` /
  `harden-task-branch-contract`): `application` cannot be seen from
  `sandbox:*`; `adapters` sees `application`; the only precedent for a shared
  dependency-free leaf is `atomicfile`.
- `FindingsSanitizer` (ANSI/control stripping preserving `\n`/`\t`, tail cap)
  lives in `gnomish-plugin-api` as part of the published plugin contract
  (moved there by `close-plugin-api-compilability-gap`; the module's gate
  pins `allowedProjects = [':domain']` — a plugin compiles against one
  declared dependency). Its `forLog` is applied at exactly one log site;
  `application` and `adapters` reach it through their allowed plugin-api
  dependency, the sandbox modules cannot. `StatusLineFormatter` already
  hand-rolls `strip(...)` plus newline flattening — an ad-hoc preview of
  `LogText`.
- 57 Spock specs already assert log output; `LogCaptureSupport` exists but is
  used by 2 of them.

Constraints: crash-consistency rule (multi-step transitions need named
windows — applies to the shutdown sequence), manual-sync-pairs rule (declared
pairs, rule of three), file-size and parameter-count limits, PIT 100% gate.

## Goals / Non-Goals

**Goals:**
- One owner per mechanism: anchor vocabulary, summary rendering, repeat
  suppression, untrusted-text sanitization, shutdown ordering.
- Every convention backed by a gate or a contract spec, not by review vigilance.
- No new module cycles; the shared pieces live below every consumer.

**Non-Goals:**
- Changing what the ledgers/snapshots record (the machine plane is untouched
  except for reusing its facts).
- Redesigning the three-appender console model — it is confirmed and kept.
- Building a general structured-logging framework; text stays text.

## Decisions

### D1. Policy lands as ADR + rule; artifacts here only reference it

`docs/adr/0004-logging-policy.md` carries: level semantics as reader reaction,
best-effort-must-log, one-failure-one-log (the deciding layer logs), the
log-expendable/ledger-durable retention rationale, the accepted deviations
(the four domain port-failure logs stay — rejected alternative: an
`EngineEvent.PortFailed` variant, deferred as scope creep with no current
consumer; unstructured text log — the ledger is the structured plane), and the
untrusted-text and throwable conventions. `.claude/rules/logging.md` is the
emitter checklist (trigger-scoped to `**/*.java`). Rationale: capabilities and
rules outlive changes (crash-consistency.md referencing precedent); a policy
in design.md would archive and govern nothing. Context: driven by FR1.

### D2. Anchor vocabulary: one `AnchorLog` class, not an event bus

`AnchorLog` (application, package `status` — note the package
`com.github.oinsio.gnomish.status` sits outside the component-scan root
`com.github.oinsio.gnomish.app`, so `AnchorLog` is wired explicitly, like its
package neighbors `LoggingEventListener`/`MdcEventListener`) owns the
operator-plane anchor forms: `claimAcquired(ref, freeSlots, wip)`, `serveStarted(config…)`,
`serveStopping(reason)`, `taskSummary(TaskSummary)`. Both claim paths
(`FeedCycle.claimOrAbandon`, `BareTakeClaimWalk`) call the same method.
*Alternative rejected:* an application-level lifecycle event bus mirroring
`EngineEvent` — the ledger/snapshot observers already watch these transitions
through their own seams (`SlotLedger`, `FeedState`), so a bus would be a third
mechanism serving only log lines; `AnchorLog` collapses into a renderer if a
real event vocabulary ever appears. Remote-module anchors (container
create/reattach/dispose in `sandbox:docker`, lifecycle commits in
`adapters/git` at `GitTaskRepository.commitWith` / `TaskLifecycleCommitWriter.
build`) are plain INFO at their own choke points — pulling them through a
shared class would add cross-module dependencies for a log line. Context: FR2.

### D3. Canonical summary: one neutral value, one renderer, two declared assemblers

A `TaskSummary` value (outcome word, stage, attempts, wall time,
tokensByModel) is the renderer's only input; `AnchorLog.taskSummary` is the
only renderer. Two assemblers produce it:

- serve/take: a mapper from `TaskOutcomeLine` at the existing slot write point
  (facts are already complete there, including outcomes that happen outside
  the engine — revoked, quarantined per post-harden `TakeResult`);
- manual run: `SummaryAccumulatorListener`, an `EngineEventListener` that
  accumulates usage/attempts/wall time and emits on `TaskFinished` (fires on
  crash-shaped exits because `TaskFinished` is the run bookend; the runner's
  outcome loop covers the abort path).

*Alternative rejected:* wiring the event accumulator into serve slots too —
it would duplicate the already-designed `TaskOutcomeLine` write point (serve
observability design D6) and still miss non-engine outcomes. The two
assemblers are a **declared sync pair** (both must populate the full
`TaskSummary` vocabulary); see D8. Context: FR3, proposal Q2 — resolved.

### D4. Repeat suppression: one `RepeatSuppressor` owner; sandbox uses local aggregates

`RepeatSuppressor` is logger-agnostic: keyed by (component, subject, reason),
it answers `firstOccurrence / repeat / rollUpDue(count) / recovered(elapsed)`;
the call site chooses levels per policy (first at site level, repeats DEBUG,
roll-up at site level with count, recovery INFO). In-memory only (NFR-R2), no
durable state, thread-safe via a `ConcurrentHashMap`. Routed sites:
workflow-run poll, first-push retry, mid-round harvest poll, GitHub retry
listener. Sites in `sandbox:docker` that flood per-item within one read
(guard-denial parse loop, scratch-tree deletion) use **local aggregate
counters** emitting one summary line per operation — that is a different,
simpler invariant (aggregate-per-call, not edge-across-calls), so it is not a
second implementation of the suppressor rule and no pair is declared.
*Alternative rejected:* Logback `DuplicateMessageFilter` — raw-message keying,
no expiry, no recovery line, config-global blast radius. Context: FR4.

### D5. Shared leaf module `:logtext` for sanitization (and the suppressor's home)

`LogText` (strip control/ANSI, flatten newlines — including the Unicode line
separators `U+2028`/`U+2029` — to a visible escape, cap length) must be
reachable from
`adapters`, `sandbox:docker`, and `application`; the layering allows no
existing common home (`application` is invisible to sandbox; `sandbox:core`
is invisible to nothing that matters but owning log utilities there is a
wrong responsibility). A new dependency-free leaf module `:logtext`
(precedent: `atomicfile`) holds `LogText` and `RepeatSuppressor` (pure
logic, `slf4j-api` only, no internal deps). The module-layering delta admits
`:logtext` alongside `atomicfile` in every consumer's allowed list.

`FindingsSanitizer` stays self-contained in `gnomish-plugin-api` — it guards
a *different trust boundary* with a *different contract*: a plugin sanitizes
untrusted machine output entering findings and deliberately preserves line
structure, while `LogText` sanitizes text entering a log line and
deliberately destroys it (one event = one line). One choke point per
boundary is the canonical shape (OWASP logging guidance; Logback/kubectl
both sanitize at the writing layer). What the two genuinely share is the
character vocabulary — the ANSI/control stripping table and the tail-cap
semantics, ~25 lines — kept in step as a **declared pair** (see D8) backed
by an executable equivalence spec, not by a production dependency.

*Alternative rejected:* the original plan — `FindingsSanitizer` delegates
its stripping primitives to `LogText`. It was written against a stale module
map (the sanitizer's adapters home predates
`close-plugin-api-compilability-gap`) and would give the published API
module an internal dependency: `:logtext` would enter the published POM
(even `implementation` scope publishes as runtime), become a
coordinates-bearing artifact whose semver couples into the API's japicmp
contract, and break the build-enforced one-declared-dependency promise —
all to deduplicate ~25 stable lines whose divergence the pair spec catches
mechanically ("a little copying is better than a little dependency"; the
rule of three extracts a shared core only when a third implementation
appears). Context: FR6, proposal Q1 — resolved.

### D6. Shutdown: one owned sequence; Spring's auto hook disabled

`FactoryApplication` calls `SpringApplication.setRegisterShutdownHook(false)`
(its `main` currently uses the static `SpringApplication.run(...)`, so it
switches to a constructed `SpringApplication` instance to have a receiver);
the serve hook (both drain and forever paths) owns the full order:
drain slots → close the application context → stop the logging system
(flushing the async FILE appender). Logback's own `<shutdownHook>` stays
absent (it would be a second racer). Kill-window analysis (crash-consistency
checklist): the sequence has no durable multi-step writes of its own — every
window freezes into states the existing lease/TTL/reaper machinery already
converges; the only new invariant is ordering of in-process teardown, asserted
by spec. A `volatile` shutdown-phase flag (set first in the hook) lets the
slot crash boundary, `InstanceHeartbeat.onWorkerDeath`, and subprocess
supervisors classify deaths as shutdown-caused (WARN, no stack) versus
independent (unchanged ERROR). Non-serve commands (run/take/dashboard) get the
same ordering via a shared exit path in bootstrap: work → context close → log
stop. *Alternative rejected:* keeping Spring's hook and ordering via
`getShutdownHandlers()` — handlers run only after context close, which is the
wrong side of the drain. Context: FR9, NFR-R1, M5.

### D7. Logback config: async FILE, UTF-8, runtime level, test isolation

FILE wraps in `AsyncAppender` (`discardingThreshold=0`, `neverBlock=false`,
queue sized in config with a comment); consoles stay synchronous (post-cleanup
WARN+ is a trickle; ERROR must reach the terminal before death). All three
encoders pin UTF-8. Root level reads `${GNOMISH_LOG_LEVEL:-INFO}` (Logback
variable substitution — no rebuild; Spring `logging.level.*` continues to work
for finer grain). Pattern gains `component=%X{component}` next to the task
triple. A `logback-test.xml` on the test classpath of every module that boots
a context routes to console-only (or a build-dir file), closing the observed
test pollution of `~/.gnomish/logs/`. `LogCaptureSupport` moves to
`test-fixtures` so all modules reach it; the rule (not a bulk migration)
makes it the documented idiom. Context: FR10, FR11, NFR-P1, M4.

### D8. Sync surfaces (mandatory per propose-checked)

Declared pairs this change touches — mirrored edits are in scope, and both
ends receive `Kept in sync with` markers where they are edited:

| Pair (registry row unless noted)                                                                                        | Mirrored edit here                                                                                                       |
|-------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| `RoundBoundaryCheck` ↔ `HarvestedBoundaryCheck`                                                                         | three-outcome boundary rule (D11) implemented at both ends; invariant text updated                                       |
| `WorktreeSalvage` ↔ `EnvironmentSalvage` (marker-declared; already removed from the registry)                           | degrade-path logs get task context on both ends; discard/restore failures logged symmetrically                           |
| `GitAttemptPersistence` ↔ `EnvironmentAttemptPersistence`                                                               | lifecycle-commit anchor and level fixes applied to both write sequences; verified tip resolution (D11) confirmed on both |
| `TakeResumeRunner` ↔ `TakeContainerResumeRunner` (and the fresh-claim / engine-execution / manual-run rows they anchor) | claim/summary anchor calls added symmetrically per mode                                                                  |
| `HostRoundEnvironmentSource` ↔ `SandboxRoundEnvironmentSource`                                                          | harvest-poll suppression applied to the polling twin; host twin verified for the same flood shape                        |

New parallel implementations introduced, decided by the preference order:

- **Summary assemblers (D3): declared pair.** `TaskOutcomeLine→TaskSummary`
  mapper ↔ `SummaryAccumulatorListener`. Invariant: both populate the full
  `TaskSummary` vocabulary for every terminal outcome family. Markers at both
  ends + a registry row; a data-driven spec asserts equivalent summaries from
  equivalent facts. Abstraction rejected for now (two genuinely different fact
  sources); a third assembler mandates extraction. Naming: the new types must
stay distinguishable from the existing ledger-plane
`serveobservability.RunSummaryAccumulator`/`RunSummaryLine` (different role:
drain-run `runSummary` ledger lines, untouched here) — prefer names carrying
"task summary" and let the glossary entry *canonical task summary* draw the
distinction from the ledger's `runSummary`.
- **Sanitizer (D5): declared pair**, not a shared abstraction —
  `LogText` (`:logtext`, internal log-line boundary) ↔ `FindingsSanitizer`
  (`gnomish-plugin-api`, plugin findings boundary). Invariant: the
  ANSI/control character-stripping table and the tail-cap semantics — and
  only those; newline handling deliberately differs (findings preserve line
  structure, log lines flatten). Markers at both ends + a registry row; a
  data-driven equivalence spec feeds one adversarial corpus (CR/LF,
  `U+2028`/`U+2029`, ANSI CSI/OSC, NUL, DEL, C1 range, overlong input) to
  both and asserts equivalent neutralization of the shared subset —
  test-scope coupling only, no production edge. A third implementation of
  the stripping table mandates extraction.
- **Suppression (D4): single owner** + local aggregate counters in sandbox,
  which implement a different invariant (documented at those sites); not a
  pair.

### D9. Convention gates: source-scan spec + ArchUnit, not a custom Error Prone checker

The throwable-trailing-arg rule and the untrusted-text routing rule are
enforced by a repo-source-scanning Spock spec (regex over `src/main` log
calls: no `\.toString\(\)`/`getMessage()` as a format argument in a call
carrying an exception name; stderr/agent-output identifiers only inside
`LogText.*(...)` wrappers at log sites) plus an ArchUnit rule keeping
`LoggerFactory` out of `domain` beyond the four allowed classes. *Alternative
rejected:* a custom Error Prone `BugChecker` — precise but a build-logic
subproject of its own; the scan spec is two orders cheaper and its false
positives are suppressible by the same annotation-comment idiom the codebase
already uses for exemptions. Revisit if the regex gate proves noisy. Context:
FR7, G4, M2.

### D10. MDC completeness

The capture/apply/clear pattern (`StreamDrain` precedent) becomes a tiny
helper (`MdcAwareThread` factory in `:logtext`, slf4j-api only) applied at
the virtual-thread hops that log (`ChildProcessStdin`, `ContainerFileChannel`
pump, `ExecPipeDrain`). `stage`/`attempt` clearing moves from
`TaskFinished`-only to the same four thread boundaries that clear `taskId`
(the listener still clears eagerly on `TaskFinished`; the boundary clear is
the backstop). Daemon threads set `component` once at worker start; reaper and
janitor wrap per-task work in `MDC.putCloseable("taskId", …)`. Context: FR8.

### D11. Exit-code defects: cannot-verify is infrastructure, blank tips refuse to persist

The three audit-adjacent correctness bugs share one shape — evidence consumed
without checking the producing invocation — and get one rule (FR13):

- **Round boundary**: the check gains a third outcome, cannot-verify, mapped
  to the round's *infrastructure* failure path — no attempt burned, no
  violation attributed. *Alternative rejected:* throwing the boundary
  violation on a failed diff — simple, but it misattributes an infrastructure
  fault to the gnome, burns an attempt, and feeds false evidence into the
  escalation report. The stage contract already separates quality from
  infrastructure failures; this rides that split. The two boundary media
  (`RoundBoundaryCheck` worktree diff ↔ `HarvestedBoundaryCheck` harvested
  refs) are the registry's first pair row: the three-outcome rule is
  implemented on both ends, markers placed, and the pair's invariant text
  updated to name it.
- **Durable tip resolutions** (`EnvironmentAttemptPersistence`,
  `EnvironmentRoundSnapshot`): a failed or blank `rev-parse` fails the
  persist with the git evidence — a corrupt durable record outlives the
  process, a failed persist is already handled by the existing
  infrastructure machinery. The host twin (`GitAttemptPersistence`) is
  verified for the same rule (mirror obligation of the declared pair).
- **Read-only poll** (`MidRoundHarvestListener`): a failed resolution skips
  the observation (never "tip moved"), logged via the suppressor (task 5.1).
- **List enumeration** (`TaskBranchLister`): enumeration failure fails the
  command; per-branch degradation rules from harden are unchanged.

Crash-consistency note: no durable step is added or reordered anywhere —
failure classification changes which existing path runs, so no new kill
window appears and the kill-point matrix needs no new rows; the existing
resume/abort specs cover the changed classification.

## Risks / Trade-offs

- [Async FILE loses the last instants on `kill -9`] → accepted and documented
  in the ADR: durable truth lives in ledger/branch/tracker; SIGTERM/Ctrl+C
  tails are protected by D6's owned flush.
- [Overlap with `add-stage-finished-event` on the sealed `EngineEvent`: that
  change adds a variant, and this change adds `SummaryAccumulatorListener`
  plus touches other exhaustive-switch listeners] → whichever change lands
  second adds the new switch arm in the affected listeners; no other coupling,
  no ordering constraint.
- [Overlap with `fix-denial-attribution-durability` in guard-denial code
  (`GuardDenialLog`/`GuardDenialReads`)] → this change's edits there are
  small (aggregate counter, key threading); sequence after harden archives
  and coordinate with the denial change's wiring tasks; rebase cost is
  bounded to two files.
- [Module-layering delta lands while harden's own layering delta archives] →
  base the delta on the post-harden spec text; verify at apply time with
  `openspec status` that harden archived first.
- [Regex convention gate false positives] → suppression idiom + the gate spec
  documents each exemption inline; escalate to a real Error Prone checker only
  if exemptions accumulate (recorded as the revisit trigger in D9).
- [Releveling WARN→INFO/DEBUG could hide a signal someone relied on] → every
  demotion is listed in tasks with its audit rationale; the suppressor keeps
  first occurrences at the original level.
- [`setRegisterShutdownHook(false)` changes shutdown for run/take/dashboard
  too] → the shared bootstrap exit path gives them the same ordered stop; a
  spec per command family asserts the context still closes and logs flush.

## Migration Plan

No data migration. Config changes ship with the code; the log pattern gains
`component=` (readers grep by key, not position). Rollback = revert the
change; the log format addition is backward-tolerable for existing greps.
Glossary gains: *anchor line*, *canonical task summary*, *repeat suppression
(edge logging)*, *log text sanitization* — same change, per the glossary rule.

## Open Questions

None — Q1 (suppressor placement) resolved by D5, Q2 (summary assembly) by D3.

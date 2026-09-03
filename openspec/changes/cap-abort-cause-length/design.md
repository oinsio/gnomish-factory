# Design: cap-abort-cause-length

## Context

See proposal.md — Why. The relevant mechanics:

- Every infrastructure abort funnels through `AbortHandler.handle` — both take modes
  (host and container engine execution converge on the shared `TakeOutcomeDispatch`) and
  the crash arm (`TakeCrashAbort`). The cause reaches three sinks from there: the ERROR
  log, `AbortRecord.cause` (→ tracker comment body via the adapter), and
  `AbortReportBuilder.build` (→ `park(INFRA)` report body).
- Cause producers differ wildly in size: `TakeCrashAbort` builds a one-line
  `"uncaught exception during the take run: " + crash`, while `AttemptJournal` renders the
  full stack trace of the persist failure (`StackTraces.render`), chain and suppressed
  included — unbounded in practice.
- Both tracker writes are best-effort (NFR-R2 of add-tracker-port): a body-too-long
  rejection is swallowed, which is precisely how an oversized cause corrupts the K-fuse
  accounting instead of failing loudly.

## Goals / Non-Goals

**Goals**

- Every abort-cause byte that reaches a tracker write fits any supported tracker's
  comment budget, deterministically.
- The diagnostic record (ERROR log, branch state) stays complete.

**Non-Goals**

- No capping of other tracker-bound text (escalation reports, checkpoint parks, finish
  reports, decision acks). Those bodies are human-authored-scale today; if one grows a
  producer like `StackTraces.render`, it gets its own change — see Open Questions.
- No splitting of oversized bodies into multiple comments (the CI-bot alternative):
  an abort marker is one structural record; two comments would break the adapter's
  marker-per-tenure accounting.
- No configuration property. The cap guards a hard API limit, not an operator preference.

## Decisions

### D1: Cap at the `AbortHandler` choke point, not per producer and not in the adapter

The truncation runs once, inside `AbortHandler.handle`, on the `cause` parameter before
it feeds `AbortReportBuilder.build` and `AbortRecord`. The ERROR log line, which is
emitted first, logs the original uncapped text.

- *Rejected: capping in each producer* (`AttemptJournal`, `TakeCrashAbort`, …) — N sites
  to keep in step is the divergence-pair antipattern this project bans; and the producer
  is also the source of the branch-state cause, which must stay full.
- *Rejected: capping in the GitHub adapter's marker writer* — protects any comment body,
  but moves the policy into one adapter: the planned Jira adapter would need its own copy
  (a new manual-sync pair), and the fuse-trip park report would still be capped nowhere
  when a different adapter writes it. The port contract is the right owner of "fits every
  tracker"; the core side of the port is where one implementation covers all adapters.
- Precedent: `CredentialScrub` sits at the equivalent choke point on the git-stderr path
  (one scrub in `GitProcessRunner`, not ~10 call sites).

### D2: Fixed constant sized from the smallest tracker budget, with framing headroom

The budget is a named constant: **28,000 characters** for the cause itself. Derivation:
Jira Cloud's comment limit is 32,767 characters (the smallest known among supported and
planned trackers; GitHub's is 65,536); the fuse-trip report wraps the cause in its own
framing (counts, timestamps, guidance — well under 1,000 characters today), and the abort
marker adds a fixed prefix. Reserving ~4,700 characters of headroom keeps both bodies
comfortably inside the Jira limit even if the framing grows, without meaningfully
shrinking the diagnostic payload.

- *Rejected: a `factory.*` property* — industry practice for tracker bots is a constant
  derived from the API limit; a configurable value invites raising it past the limit,
  reintroducing the silent-loss bug the change exists to fix.
- *Rejected: sizing to GitHub's 65,536* — the port promises tracker-agnostic behavior;
  sizing to the current adapter would make the Jira adapter's arrival a breaking rescope.

### D3: Head+tail truncation with an explicit omission marker

An over-budget cause becomes `head + "\n… [" + omitted + " characters omitted] …\n" +
tail`, with the head taking roughly two thirds of the budget and the tail the rest. For
a rendered Java exception, the head carries the top-level message and throw-site frames;
the tail carries the deepest `Caused by:` — the root cause an operator opens the report
for. The marker names the exact number of characters dropped, so truncation is always
visible (never a silent cut).

- *Rejected: head-only* — drops the `Caused by:` chain, the most diagnostic part; this
  exact failure is documented across CI-log and telemetry tooling, which converged on
  head+tail after tail-only and head-only each lost the half that mattered.
- *Rejected: reordering root-cause-first (logback `rootFirst` style)* — solves the same
  problem by rewriting the trace, but the cause is an opaque string here (already
  rendered), and reordering free text is not meaningful.
- The head/tail split point cuts on a line boundary when one is near, purely for
  readability; correctness only requires the length bound and the marker.

### D4: One new value-level class owns the constant and the algorithm

`AbortCauseBudget` (working name) in `application/.../app/take/`: the constant, a static
`cap(String) → String`, no state. `AbortHandler` calls it; `AbortReportBuilder` and
`AbortRecord` stay unchanged. Keeps `AbortHandler` under the file-size target and gives
the algorithm its own spec file (one capability per spec).

### D5: Sync surfaces

Per the propose-checked scout:

- The change touches no end of any declared pair. The declared
  `TakeEngineExecution`/`TakeContainerEngineExecution` pair sits *above* the cap: both
  ends converge on the shared `TakeOutcomeDispatch` → `AbortHandler`, which is where the
  cap lives, so no mirrored edit is needed and the pair's synchronized invariant (engine
  execution wiring per mode) is unaffected.
- The change adds no parallel implementation: one truncation function, called from one
  choke point, covers all cause producers in both execution modes. The container-mode
  `recordAborted` writes the outcome to the task branch, not the tracker, and is out of
  scope by D1's log/branch exemption.

- The codebase already holds one declared text-capping pair —
  `LogText`/`FindingsSanitizer` (ANSI/control stripping + tail-cap) — but it
  implements a different rule at a different boundary: log lines and plugin
  findings, tail-only truncation. `AbortCauseBudget` caps tracker comment
  bodies with head+tail semantics; neither invariant subsumes the other, so no
  third implementation of an existing rule arises and no abstraction is owed.

Sync surfaces: none beyond the analysis above — this change adds no parallel
implementation and touches no declared pair.

## Risks / Trade-offs

- **[Framing growth]** `AbortReportBuilder`'s framing could someday grow past the
  reserved headroom → the headroom is ~4,700 characters against a framing that is
  <1,000 today; the budget spec pins the invariant "report body ≤ smallest tracker
  limit", so a framing that outgrows it fails a spec, not production.
- **[Multi-byte characters]** Jira's limit counts characters, GitHub's counts 4-byte
  unicode characters; the cap counts Java `char`s (UTF-16 units), which is equal to or
  stricter than both for any real stack-trace text → acceptable; the headroom absorbs
  the difference, and the spec asserts the bound in `char`s.
- **[Marker inside markdown]** A cause cut mid-markdown-construct could render oddly in
  the tracker comment → cosmetic only; the omission marker is plain text and the abort
  comment already wraps the cause as free text.

## Migration Plan

Pure additive behavior change on the write path; no stored data changes shape. Deploy is
the normal jar roll; rollback is the previous jar. Existing oversized comments (if any
ever landed) are unaffected.

## Open Questions

- Should other tracker-bound report bodies (escalation, checkpoint park, finish) get the
  same guard? Deferred deliberately: none of them has an unbounded producer today, and
  folding them in would widen this change past "one initiative". If it becomes real, the
  same `AbortCauseBudget`-style guard generalizes to a port-level body budget.

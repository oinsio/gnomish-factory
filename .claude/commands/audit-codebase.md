---
description: Read-only periodic audit of the whole codebase — duplication, dead code, antipatterns, concurrency, crash-consistency, security, logging, test quality, docs drift; changes nothing in the project, saves the report to temporary-docs/
argument-hint: "[dimensions] (comma-separated, default all)"
---

# Codebase Audit (read-only, periodic)

Whole-project counterpart of `/audit-implementation`. That command gates one change before
archiving; this one sweeps the entire production codebase for defects that accumulate *across*
changes and that no diff-scoped check can see. Run it periodically — every few archived
changes, or before a release. **Strictly read-only** with respect to the project: no edits
to code, specs or OpenSpec artifacts, no git state changes. The command produces two
artifacts and nothing else: the report in the reply, and the same report saved as one
Markdown file under `temporary-docs/` (final step) — the file is what the next audit reads
for its deltas; the human decides what to act on.

## Input

- `$1` — optional comma-separated dimension list from the table below; default: all.
  Example: `/audit-codebase duplication,concurrency`.

| Dimension           | What it sweeps                                              |
|---------------------|-------------------------------------------------------------|
| `duplication`       | copy-paste, logic scatter, undeclared sync pairs            |
| `dead-code`         | unreferenced production code, test-only surface             |
| `antipatterns`      | god classes, layering, primitive obsession, exceptions      |
| `concurrency`       | shared state, happens-before, interrupts, vthread pinning   |
| `crash-consistency` | implementation vs the `crash-consistency.md` checklist      |
| `security`          | injection, secrets, sandbox boundaries                      |
| `logging`           | level misuse, silent degradation, noise, MDC, log injection |
| `test-quality`      | PIT exemption validity, traceability coverage               |
| `docs-drift`        | glossary/ADR/guides vs actual code and CLI                  |

## Method

Scope: production code under `*/src/main`, ignoring `build/`. Before fanning out, run
`git status --short` and note uncommitted/untracked areas — findings there are likely
in-flight work of the active change, not defects; every dimension must label them separately.

Launch **one Explore subagent per selected dimension, all in parallel**, each read-only and
"very thorough". Each returns findings with `file:line`, a short evidence excerpt, severity
(CRITICAL / WARNING / SUGGESTION), and confidence. Then synthesize (step below). If a previous
audit report exists in `temporary-docs/` (files matching `*audit*`), read it first and have
the report call out deltas: fixed since last time, still open, new.

### Dimension briefs

Give each subagent the project context (orchestrator, ports & adapters, module list from
`settings.gradle`, virtual threads + subprocesses) plus its brief:

- **duplication** — same logic implemented in more than one place; one responsibility spread
  across packages/modules; parallel twin hierarchies. Verify every pair declared in
  `.claude/rules/manual-sync-pairs.md` (markers `Kept in sync with` + the registry) is
  actually in sync — a divergence between declared pair ends is CRITICAL. Hunt for
  *undeclared* pairs: same rule reimplemented per mode/layer with no marker. Also: repeated
  private helpers, repeated string/numeric constants, repeated parsing/formatting knowledge.
- **dead-code** — public types/methods with no production references (grep across all
  modules; a class used only by tests is production-dead), enum constants never
  produced/matched, config properties bound but never read, refactor leftovers, javadoc
  claiming callers that do not exist (that is either dead code or a missing-wiring bug —
  say which). Static analysis already catches unused privates; look for what it misses.
- **antipatterns** — god classes/methods (parameter count, body length), split-for-file-size
  pairs with open field access, layering violations (domain→adapter, application→adapter
  concrete), primitive obsession on ids/branches/paths, temporal coupling (init/attach
  before use), broad `catch`, I/O in constructors, direct `System.out/err` where a port
  exists. Check the largest files and widest constructors first.
- **concurrency** — enumerate objects reachable from concurrent paths (serve slots, reapers,
  tickers); for each mutable field: who writes, who reads, on which thread, what
  happens-before edge. Interrupt protocol: every blocking wait answers interruption, flag
  restored. Lock scope: every `synchronized`/`lock()` region against `lock-scope.md` — a
  subprocess, network call, file I/O, sleep or caller-supplied callback inside one is a
  finding unless the class's javadoc claims the resource-serializing exception and answers
  its three bar items (the harm is waiter starvation, not carrier pinning, which JEP 491
  removed). Attach/set-after-construction across threads is a finding even without a proven
  race.
- **crash-consistency** — take every multi-step durable transition (commit+push,
  push+tracker write, effect+receipt, create+delete) in `adapters/git` and the take/serve
  paths; check it against the `crash-consistency.md` checklist: kill windows named, each
  window a shape of the owning capability's closed set, one recovery owner, constructive
  before destructive, intent→effect→receipt, kill-point specs exist. Divergence between the
  declared contract and code is CRITICAL.
- **security** — subprocess arguments from tracker/task data passed as argv (never
  shell-interpolated — inspect every place a shell script is built by concatenation); refs
  and paths from task data sanitized; secrets never in logs/commits/error messages;
  credential scrub lists complete; sandbox/egress claims enforced, fail closed.
- **logging** — audit against the logging policy (`.claude/rules/logging.md` and the
  logging ADR where present; the checks below stand alone regardless). **Silent
  degradation**: catch blocks that swallow and return a degraded/default value with no log;
  subprocess/git invocations whose exit code is unchecked before the output is consumed as
  evidence; retries/backoffs invisible in the log. **Level misuse**: WARN/ERROR on
  normal or recovered flow (levels encode required reader reaction — the WARN+ console is
  the operator channel); per-item or per-loop chatter at INFO+; unbounded repetition in
  poll loops with no first-occurrence/roll-up suppression. **Throwable handling**:
  `e.toString()`/`getMessage()` interpolated as a format argument instead of the trailing
  throwable (stack trace amputated); `getMessage()` that can render `null`. **Log
  injection**: untrusted text (agent/LLM output, subprocess stderr, tracker strings)
  interpolated raw — unsanitized ANSI/control chars, interior newlines that forge log
  records, unbounded payload length. **Context**: log statements on virtual-thread hops
  without MDC propagation; daemon-thread lines with no component identity; per-task
  decisions not findable by a `taskId` grep. **Lifecycle**: state transitions and
  best-effort cleanup paths that leave no trace; one failure logged at multiple layers
  along one call path. **Hygiene**: test suites writing to the operator's production log.
- **test-quality** — every `@DoNotMutate`, `excludedClasses`, `excludedTestClasses` entry
  still meets its written bar in `testing.md` (the named covering suite exists and covers
  the claimed scenarios); exemption count trend; per `traceability.md`, every FR/NFR of
  *active* changes has an implementing entity (grep).
- **docs-drift** — `docs/glossary.md` vs code naming (banned synonyms absent, domain terms
  match), ADRs vs implementation, `README.md`/operator guides vs actual CLI commands and
  flags, Mermaid diagrams vs current architecture.

### Synthesis

Do not concatenate the subagent reports. Merge and rank:

1. Deduplicate findings that surfaced in several dimensions; keep the strongest framing.
2. Where dimensions disagree (one proposes a unification another argues against), resolve
   with a reasoned verdict — do not report both recommendations.
3. Separate **defects** (wrong today) from **debt** (costs tomorrow); rank defects by
   consequence, debt by value/effort.
4. For recurring *classes* of findings, recommend the class-level prevention (a new rule, an
   `/audit-implementation` check) rather than only the instance fixes — instance lists that
   should die after a one-time migration are noted as such.
5. Note explicitly what was checked and found **clean** — absence of findings is information.

## Report

```
## Codebase Audit: <date> (dimensions: ...)

### Summary — one line per dimension: N findings by severity, or "clean"
### Deltas vs previous audit (if one was found) — fixed / still open / new
### Defects, ranked          — each with file:line, evidence, consequence, concrete fix
### Debt, ranked by value/effort — same format
### Class-level prevention   — recommended rule/check changes, with the recurring class named
### Checked and clean        — what was verified with no findings
### Suggested next steps     — small direct fixes vs OpenSpec-change-sized work, in order
```

Every finding carries `file:line` and an actionable recommendation. When uncertain, downgrade
severity rather than guess. Findings in uncommitted/untracked code are labeled "in-flight —
verify against the active change's tasks" and never counted as defects. End with a reminder
that nothing in the project was modified and the human decides what to apply.

## Persist the report

After the report is written to the reply, save it **whole** — verbatim, same language as the
reply, no translation, no rewording — to one new file:

```
temporary-docs/audit-codebase-<YYYY-MM-DD>.md
```

The whole report, not only its tail, because the "Deltas vs previous audit" step of the next
run reads this file to tell fixed from still-open from new; a file holding only
recommendations could not answer that. The file is written in the language the conversation
is held in: `temporary-docs/` is the one place in the repository where documentation may be
in the human's language rather than English (`process-invariants.md`, "Documentation
language"). When that language is not English, add its ISO 639-1 code before the extension
(`.ru.md`, the convention the directory already follows); an English report keeps plain
`.md`. Start the file with the report's own level-1 heading. If the file already exists (a
second audit on the same day), overwrite it — the newer audit supersedes the older one.
Nothing else is written. In the reply, name the file's path in one line after the report.

`openspec/**` artifacts must not reference this file (`process-invariants.md`, "No references
to temporary files"); a finding worth keeping becomes a change or a `docs/` entry by the
human's hand, not a link.

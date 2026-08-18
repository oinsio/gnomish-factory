---
description: Diagnose uncommitted files for errors, code duplication, and unused code using IDE diagnostics
---

# Diagnose Uncommitted Files

Work through `uncommitted-files.md` one file at a time via sub-agents, strictly sequentially. Fix issues found, mark each file done through the script. **No commits** (project invariant: the agent never commits).

The checklist is resumable: an interrupted run continues from the first unchecked item, and re-running the generator merges in new files without losing progress. Never hand-edit `uncommitted-files.md` — the `done` subcommand stamps a content hash alongside the checkbox, and a hand-written `[x]` carries no hash, so the next sync silently demotes it back to `[ ]`.

The whole fix-verify cycle runs **through the JetBrains IDE model**: open the file, read IDE diagnostics, edit via the IDE (auto-saves and keeps the index consistent), re-read diagnostics. This keeps `get_file_problems` accurate on every cycle — no filesystem/reindex lag. Do NOT edit these files with the built-in Edit tool during a cycle, or the next diagnostics read may be stale.

## Run to completion — do not ask, do not batch

Invoking this command is standing authorization to process **every** unchecked item, however many there
are. Once started, keep going until `next` prints `ALL DONE`.

Specifically forbidden:

- Asking "there are N files, shall I continue / process them in batches / start with the first 10?" —
  the answer is always "all of them, one after another". Do not ask it.
- Stopping after some round number of files to report progress and await a go-ahead.
- Proposing a subset ("I'll do the adapters first and you can tell me whether to continue").
- Declaring the run finished while `next` still returns a path.

A long list is not a reason to check in. The loop is stateless — each iteration is `next` → sub-agent →
`done`, and nothing from the previous file needs to stay in context — so a 200-file list costs the same
per file as a 5-file one and cannot run away. Progress is durable in `uncommitted-files.md`, so even a
hard interruption resumes exactly where it stopped (step 1).

Keep the per-file log to the compact note in step 3.1 — a headline plus one line per fix and per skip.
The sub-agent's full report stays in its own context; do not restage it as prose. Paragraph-per-file
summaries are what makes a long run feel unbounded.

The only legitimate early stops, and both are reported rather than asked about:

- `./scripts/uncommitted-files.sh next` or `done` exits non-zero — the checklist is broken; report the
  error and stop.
- The same file fails its 3 fix-verify cycles twice in a row across re-runs — report it, mark nothing,
  and move on to the next file rather than stopping the run.

If the operator wants a partial run, they say so when invoking the command; absent that, run it all.

## Steps

### Step 1: Generate or resume the checklist

Run `./scripts/uncommitted-files.sh` (subcommand `sync`, the default). It creates `uncommitted-files.md`
in the project root, or merges the current git status into an existing one:

| Situation | Result |
|---|---|
| file still uncommitted, checked, unchanged since | stays `[x]` |
| file still uncommitted, checked, edited afterwards | demoted to `[ ]` — needs another pass |
| file new or still unchecked | `[ ]` |
| file no longer in git status (committed, reverted, deleted) | dropped from the list |

So the normal way to both *start* and *resume* is the same command — run it at the beginning of every
invocation, including after an interrupted run. Progress survives; a stale list heals itself.

Use `./scripts/uncommitted-files.sh reset` **only** when the whole list should be discarded and rebuilt
from scratch (branch switched, or the operator explicitly asks to start over). It is the one operation
that throws away `[x]` marks — never reach for it just because the list looks out of date, since `sync`
already handles that case.

The list is scoped to module source files — any path with a `src/` segment, across every Gradle module
(`domain/`, `gitobjects/`, `gnomish-plugin-api/`, `application/`, `adapters/` and its sub-modules,
`sandbox/*`, `bootstrap/`, `test-fixtures/`, `build-logic/`). There is no root `src/` since the
multi-module split. Untracked directories are expanded to individual files, so the checklist never
contains a bare folder; deleted paths are skipped and renames are listed under their new path.

### Step 2: Pick the next file

Run `./scripts/uncommitted-files.sh next`. It prints the path of the first unchecked item, or the
literal `ALL DONE` when nothing is left — then skip to step 4. Do not scan the markdown by hand; the
numbering shifts on every sync and only `next` reflects the demotions from step 1.

### Step 3: Analyze files sequentially via sub-agents

For each unchecked file, launch a **foreground sub-agent** (Agent tool, subagent_type: general-purpose) with:

```
Analyze `<relative-path>` for issues. No commits. Route ALL edits through the IDE
(`mcp__jetbrains__replace_text_in_file`), never the built-in Edit tool. Up to 3 fix-verify cycles:

1. Open the file so IntelliJ's analyzer is primed, then read diagnostics:
   - `mcp__jetbrains__open_file_in_editor(filePath: "<relative-path>", projectPath: "$ARGUMENTS")`
   - `mcp__jetbrains__get_file_problems(filePath: "<relative-path>", projectPath: "$ARGUMENTS", errorsOnly: false)`
   **CRITICAL**: After the call, list EVERY returned issue verbatim in this format:
   - [SEVERITY] line N: "description" — `lineContent snippet`
   If the response contains zero items, write: "IDE returned 0 issues."
   Do NOT summarize or skip issues. Every single item must be listed before proceeding.

2. Fix errors and warnings (ERROR, WARNING, WEAK WARNING). This includes Error Prone / NullAway
   findings surfaced as IDE inspections (nullability, JSpecify `@Nullable` contracts, unused code).
   Apply each fix with `mcp__jetbrains__replace_text_in_file(pathInProject: "<relative-path>",
   oldText: "...", newText: "...", replaceAll: false, projectPath: "$ARGUMENTS")` — always
   `replaceAll: false` with enough surrounding context that `oldText` is unique, or you will clobber
   other occurrences.
   Default is to fix. Every issue you do not fix must be reported with a reason tag in the final
   message — an unreported skip is the one outcome that makes this whole pass worthless.
   The pre-approved exceptions (`traceability-comment` / `spock-block`), which need no further
   justification:
   - "Duplicated code fragment" that is a traceability doc-comment (`* Implements FRx of <change-name>`
     / `# implements FR-X of <change-name>`) — intentionally repeated across artifacts per
     `.claude/rules/traceability.md`; never extract.
   - "Duplicated code fragment" that is Spock block scaffolding (`given:` / `when:` / `then:` /
     `expect:` labels and their trivial setup) — cross-spec similarity is inherent to BDD; extracting
     harms test readability.
   All OTHER "Duplicated code fragment" warnings MUST be handled in step 3A.
   Anything else you leave unfixed needs the `false-positive` or `out-of-scope` tag and a concrete
   reason — never leave an issue out of the report because it looked minor.

3. Fix duplication — three layers, in priority order:
   **A) IDE-reported**: For each "Duplicated code fragment" from step 1 (except the two skippable
   patterns above): read the other fragment via
   `mcp__jetbrains__get_file_text_by_path(pathInProject: "<other>", projectPath: "$ARGUMENTS")`,
   compare both, extract shared code into a helper class / static utility / shared record.
   **B) Similar file names**: `mcp__jetbrains__find_files_by_glob(globPattern: "**/src/**/*Label*.java",
   projectPath: "$ARGUMENTS")` (adapt the pattern to the file's name stem; the leading `**/` is required
   — sources live under `<module>/src/`, not a root `src/`). If candidates share >50%
   logic, extract the shared behavior into a common class/interface, reduce originals to thin
   delegators. Respect the file-size cap (≤120 lines target, 200 hard) and module boundaries from
   `.claude/rules/process-invariants.md`: shared code may only move *down* the layer stack
   (`bootstrap` → `adapters`/`application` → `domain`/`gitobjects`/`gnomish-plugin-api`), never
   sideways between sibling modules and never into a module the current one does not already depend on.
   Test-only helpers shared across modules belong in `test-fixtures/`. If an extraction would need a new
   module dependency, report it instead of adding one.
   **C) Duplicate definitions**: `mcp__jetbrains__search_in_files_by_regex(regexPattern:
   "(class|record|interface|enum)\\s+<Name>", fileMask: "*.java", projectPath: "$ARGUMENTS")` for each
   type/method/constant defined in the file. Before extracting, confirm the declaration with
   `mcp__jetbrains__get_symbol_info`. If a symbol is defined in multiple places, move the canonical
   definition to the best shared location and replace others with imports; when aligning names, use
   `mcp__jetbrains__rename_refactoring` (context-aware, updates all references) rather than text
   replacement.
   Run `get_file_problems` on ALL files changed during dedup (not just the original).

4. Check unused code — unused private methods, fields, imports, local variables. Use
   `mcp__jetbrains__search_in_files_by_regex` across production + Spock specs to confirm truly unused
   before removing. Beware reflection / Spring `@ConfigurationProperties` / Jackson binding, where a
   member can be used without a direct reference.

5. Re-read diagnostics with `get_file_problems` (the file is still open). List ALL returned issues
   verbatim (same format as step 1). If new issues appeared, repeat from step 2.

**Note**: dedup in step 3 may modify files outside the current one. That's expected — later sub-agents
will see those changes.

Your final message MUST use this exact format:
---
**Issues found**: <number> (list each: severity, line, description)
**Issues fixed**: <number>
  - <SEVERITY> l.<N> <short description> -> <what you changed, ~6 words>
**Issues skipped**: <number>
  - <SEVERITY> l.<N> <short description> -> <reason-tag>: <why, ~10 words>
**Files changed**: <list or "none">
---

`<reason-tag>` is exactly one of:
- `traceability-comment` — duplicated fragment that is an `Implements FR-X` doc comment
- `spock-block` — duplicated fragment that is Spock `given:`/`when:`/`then:` scaffolding
- `false-positive` — the inspection is wrong here; say what makes it wrong
- `out-of-scope` — fixing it would change public API, cross a module boundary, or need a new
  dependency; say which
Anything you skip for a reason outside these four is a deviation: still skip it rather than guess, tag
it `out-of-scope`, and state plainly that the reason is not on the approved list.
```

#### 3.1 Log the result

After `done` returns, print exactly this note — no more, no less:

```
Checked <n>/<total>: <path> — <F> fixed, <S> skipped
  fix  <SEVERITY> l.<N> <description> -> <what changed>
  skip <SEVERITY> l.<N> <description> -> <reason-tag>: <why>
```

One `fix` line per fixed issue, one `skip` line per skipped issue, copied from the sub-agent's report.
Drop the body entirely when the file was clean (`— 0 fixed, 0 skipped` alone). Above 6 fixes, collapse
them to `fix  6 more (unused imports, formatting)` — but **never collapse or omit a `skip` line**: the
skips are the judgement calls the operator is reading this log to audit, and a silent skip is
indistinguishable from a missed issue. If the reason tag is `false-positive` or `out-of-scope`, the
`why` is mandatory and must name the concrete blocker, not restate the tag.

**CRITICAL**: Wait for each sub-agent to finish before launching the next — sequential, never parallel
(two agents editing overlapping files through the IDE clobber each other). "Wait" means wait, not stop:
as soon as one returns, mark it done and launch the next without pausing for confirmation.

After each sub-agent completes:
- Run `./scripts/uncommitted-files.sh done <relative-path>` — flips the item to `[x]` and stamps the
  file's current content hash, so a later `sync` knows the file was verified in exactly this state
- Print the note from step 3.1
- Go back to step 2 for the next path

Dedup in step 3 may fix a file that appears later in the list. Do not mark it done — it was not put
through the full cycle. Its `[x]` would in any case be undone by the next `sync`, since the hash it
carried was stamped before the dedup edit.

### Step 4: Final verification

Run **sequentially** (wait for each before starting next):

1. `./gradlew spotlessApply` — auto-fix formatting on all changed files.
2. `./gradlew check` — runs `spotlessCheck`, Error Prone / NullAway, Spock tests, JaCoCo across all
   modules. If it fails, fix the reported files (via the IDE, as in step 3) and re-run. Repeat up to
   3 times. While iterating on a failure you may narrow to the owning module (`./gradlew :domain:check`,
   `./gradlew :adapters:github:check`), but the last run before reporting must be the full root `check`
   — edits in one module routinely break another.

`check` can also be run through the IDE:
`mcp__jetbrains__execute_run_configuration(configurationName: "gnomish-factory [check]", projectPath: "$ARGUMENTS")`.
Do NOT run the paid/E2E tasks (`paidSmokeTest`, `ollamaE2eTest`, `pitest`) — they are deliberately
outside `check` and out of scope here.

### Step 5: Report

Summary:
- Total files analyzed
- Files with errors / duplication / unused code fixed
- Files clean
- Skips grouped by reason tag, as counts: `traceability-comment: 14, spock-block: 9,
  false-positive: 2, out-of-scope: 1`. Then re-list every `false-positive` and `out-of-scope` skip in
  full (`<path> l.<N> <description> -> <why>`) — those are the only ones needing a human decision, and
  they are easy to lose in a 200-file scrollback
- Verification status (`spotlessApply` / `check`)

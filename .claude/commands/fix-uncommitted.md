---
description: Diagnose uncommitted files for errors, code duplication, and unused code using IDE diagnostics
---

# Diagnose Uncommitted Files

Read `uncommitted-files.md` and analyze each listed file via sub-agents — one per file, strictly sequentially. Fix issues found, mark each file done. **No commits** (project invariant: the agent never commits).

The whole fix-verify cycle runs **through the JetBrains IDE model**: open the file, read IDE diagnostics, edit via the IDE (auto-saves and keeps the index consistent), re-read diagnostics. This keeps `get_file_problems` accurate on every cycle — no filesystem/reindex lag. Do NOT edit these files with the built-in Edit tool during a cycle, or the next diagnostics read may be stale.

## Steps

### Step 1: Generate the checklist

Run `./scripts/uncommitted-files.sh` to create or update `uncommitted-files.md` in the project root. It scopes the list to files under `src/`.

### Step 2: Read the checklist

Read `uncommitted-files.md`. Only process unchecked items (`[ ]`), skip `[x]`.

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
   The ONLY acceptable exceptions to skip (do not fix):
   - "Duplicated code fragment" that is a traceability doc-comment (`* Implements FRx of <change-name>`
     / `# implements FR-X of <change-name>`) — intentionally repeated across artifacts per
     `.claude/rules/traceability.md`; never extract.
   - "Duplicated code fragment" that is Spock block scaffolding (`given:` / `when:` / `then:` /
     `expect:` labels and their trivial setup) — cross-spec similarity is inherent to BDD; extracting
     harms test readability.
   All OTHER "Duplicated code fragment" warnings MUST be handled in step 3A.

3. Fix duplication — three layers, in priority order:
   **A) IDE-reported**: For each "Duplicated code fragment" from step 1 (except the two skippable
   patterns above): read the other fragment via
   `mcp__jetbrains__get_file_text_by_path(pathInProject: "<other>", projectPath: "$ARGUMENTS")`,
   compare both, extract shared code into a helper class / static utility / shared record.
   **B) Similar file names**: `mcp__jetbrains__find_files_by_glob(globPattern: "src/**/*Label*.java",
   projectPath: "$ARGUMENTS")` (adapt the pattern to the file's name stem). If candidates share >50%
   logic, extract the shared behavior into a common class/interface, reduce originals to thin
   delegators. Respect the file-size cap (≤120 lines target, 200 hard) and module boundaries from
   `.claude/rules/process-invariants.md`.
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
**Issues skipped (acceptable)**: <number> (list each with reason: traceability-comment / spock-block)
**Files changed**: <list or "none">
---
```

**CRITICAL**: Wait for each sub-agent to finish before launching the next.

After each sub-agent completes:
- Mark `[ ]` → `[x]` in `uncommitted-files.md`
- Log result summary

### Step 4: Final verification

Run **sequentially** (wait for each before starting next):

1. `./gradlew spotlessApply` — auto-fix formatting on all changed files.
2. `./gradlew check` — runs `spotlessCheck`, Error Prone / NullAway, Spock tests, JaCoCo. If it fails,
   fix the reported files (via the IDE, as in step 3) and re-run. Repeat up to 3 times.

`check` can also be run through the IDE:
`mcp__jetbrains__execute_run_configuration(configurationName: "gnomish-factory [check]", projectPath: "$ARGUMENTS")`.
Do NOT run the paid/E2E tasks (`paidSmokeTest`, `ollamaE2eTest`, `pitest`) — they are deliberately
outside `check` and out of scope here.

### Step 5: Report

Summary:
- Total files analyzed
- Files with errors / duplication / unused code fixed
- Files clean
- Verification status (`spotlessApply` / `check`)

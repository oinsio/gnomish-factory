# Design: harden-reference-dump-scrubber

## Context

Driven by FR1–FR6, NFR-R1, NFR-S1 of proposal.md. `ReferenceDumpScrubber.scrub(line, workspaceRoot, sessionId, label)` is a line-level string replacer: it swaps the workspace path and session id and leaves everything else byte-for-byte. The real `claude` CLI `result` event, recorded verbatim by `PaidSmokeReferenceDumpSpec`, therefore commits `total_cost_usd`, per-model `costUSD`, a `permission_denials` array (raw tool inputs, file contents, paths) and per-event `uuid` and `request_id` identifiers into the fixtures. `TokenUsageMapper` reads only `inputTokens`/`outputTokens`/`cacheCreationInputTokens`/`cacheReadInputTokens`, so none of that is consumed — it is dead, and partly sensitive, weight in the repo. The three fixtures already on disk were recorded by the operator; refreshing them must not cost money.

## Goals / Non-Goals

**Goals:**
- Strip money, `permission_denials`, `uuid`, and `request_id` from every captured line while keeping resolved model ids and all token/cache counts (FR1–FR4).
- One code path: the same scrub used by live recording also refreshes committed files (FR6), so the two never diverge.
- Idempotent, zero-cost refresh (NFR-R1, NFR-C1) plus a permanent regression guard in `check`.

**Non-Goals:**
- Changing production/domain code — `TokenUsageMapper` and the money-free domain stay as they are.
- Stripping representative real fields beyond the identified set (durations, `result` text, event shape stay — D11 realism).

## Decisions

### D1: JSON-aware key pruning, not regex

Removing `costUSD` (nested in each `modelUsage` entry), `total_cost_usd`, `permission_denials` (an array of objects), `uuid`, and `request_id` reliably from compact JSON is a parse job, not a regex job — a regex over nested arrays/objects is fragile. `scrub` will, after the existing path/session string replacement, parse the line with the Jackson `ObjectMapper` already on the test classpath, recursively remove the deny-listed field names from every object node, and re-serialize compactly. Jackson `ObjectNode` preserves insertion order and emits compact JSON, so the output is the original line minus the stripped keys — order and formatting preserved, making the transform near-byte-minimal and stable. A line that does not parse as JSON (should not occur for stream-json) falls through with only the string scrubbing applied.

**Deny-list** (recursive, by field name): `total_cost_usd`, `costUSD`, `permission_denials`, `uuid`, `request_id`. `request_id` sits alongside `uuid` because both are per-run CLI/API envelope identifiers (G2/NFR-S1); API message ids and `tool_use_id` stay — they carry the tool_use → tool_result linkage that makes the fixture representative (NG3). Deny-list over allow-list deliberately: D11's whole point is committing the *real* event shape that a local Ollama run cannot produce, so an allow-list that reduced the event to a canonical skeleton would defeat the exercise. We remove only the specifically identified sensitive/non-representative fields and keep the rest real.

### D2: Refresh via a gated rewrite feature, guard via an always-on spec

`ReferenceDumpScrubber` lives in the test source set, so the refresh driver lives there too — no Gradle-task plumbing to reach test classes. A new `ReferenceDumpHygieneSpec` carries both roles.

**Placement is load-bearing:** the `e2e/paidsmoke` package is excluded from the normal `test` task (`build.gradle` excludes `com/github/oinsio/gnomish/e2e/paidsmoke/**`; only `paidSmokeTest` includes it), so a hygiene spec placed next to the scrubber would silently never run in `check`. The spec therefore lives in `com.github.oinsio.gnomish.adapter.agent` (beside `StreamJsonReferenceDumpSpec`, the fixtures' consumer) and imports `ReferenceDumpScrubber` from the paidsmoke package — same test source set, so no build change is needed for the import.

**Reaching the committed files:** only `paidSmokeTest` currently sets the `referenceDumpDir` system property; under plain `test` the classpath copy in `build/resources/test` is NOT the committed file, so an in-place rewrite there would silently touch nothing. `build.gradle` sets the same `referenceDumpDir` property on the normal `test` task too (it is just a path — harmless when only read), and the hygiene spec resolves all fixtures through it.

The spec's two roles:
- **Always-on assertion features** (run in every `check`): each committed `*.reference.json` — all four, including the hand-authored `result-without-model-usage` placeholder — contains none of the deny-listed fields (M1/M2) and no absolute home-directory path (`/(Users|home)/...` — NFR-S1); the three refreshed dumps still contain resolved model ids and per-model token counts under `modelUsage`, the placeholder its top-level `usage` token fields (M3). This is the permanent regression guard — a future un-scrubbed dump fails the build.
- **A gated rewrite feature**, `@Requires({ System.getProperty('rescrubReferenceDumps') })`, that re-scrubs each committed file (all four — the already-clean placeholder round-trips unchanged, no special case) in place line by line. It is skipped in normal `check` (so the build never mutates committed sources) and run once now, explicitly, to refresh the fixtures:
  `./gradlew test --tests '*ReferenceDumpHygieneSpec' -DrescrubReferenceDumps=true`.

For the in-place refresh, path/session arguments are passed empty: the files are already path/session-scrubbed, so those replacements are no-ops and only the new JSON pruning takes effect. Idempotency (NFR-R1) follows from D1's stable re-serialization: re-scrubbing a pruned line finds no deny-listed keys and round-trips to the same bytes.

### D3: `PaidSmokeReferenceDumpSpec` is unchanged

It already calls `ReferenceDumpScrubber.scrub(...)` per line; extending that method means the next paid recording is scrubbed automatically. No change to the recorder, so M4/D11 behaviour is untouched.

### D4: Collapse machine temp paths by regex; keep opaque per-run tokens

Driven by FR7, NFR-S1. A real recording leaves machine-identifying absolute paths the workspace-literal replacement (D1's string phase) does not catch, because they are *not* the workspace root: the macOS per-user temp hash `/private/var/folders/<xx>/<hash>…`, the per-uid `/private/tmp/claude-<uid>/…`, and — crucially — Claude Code's *dashed* project-dir encoding of such a path (`-private-var-folders-…-workspaceRoot<n>`) that surfaces in `memory_paths.auto` and a subagent's `output_file`. These are collapsed to `/workspace-scrubbed` (`-workspace-scrubbed` for the dashed form) by an ordered list of anchored regexes in the string phase, alongside the pre-existing `/(Users|home)/…` rule. *Rationale:* like the home-path collapse, these paths are structurally recognizable and not JSON-key-scoped (they appear inside string *values*), so a targeted regex is the right tool — a key deny-list (D1) cannot reach a substring of a path value. Each rule rewrites to a fixed placeholder that matches none of the patterns, so re-scrubbing is a no-op (NFR-R1).

*Boundary — what stays.* The random `workspaceRoot<n>` suffix rides inside the `var-folders` segment and is removed with it, but the opaque per-run tokens `agentId`/`task_id` (a random hex, no machine or user identity) are **kept**, on the same footing as the API `msg_`/`toolu_` ids D1 already keeps: they make the subagent linkage representative (NG3). The alternative — adding `agentId`/`task_id` to the deny-list and stripping the init-event config (plugins, MCP servers, `memory_paths`) — was rejected: it shrinks the fixture but erases exactly the real event shape a local Ollama run cannot reproduce, defeating D11.

*Alternative rejected:* narrowing NFR-S1's wording to the home-path guard + field deny-list (a doc-only fix) — cheaper, but it would knowingly leave a per-user temp hash committed in the repo, which is the machine-identifying data NFR-S1 exists to remove.

## Risks / Trade-offs

- **Re-serialization vs byte-fidelity**: Jackson round-trips compact JSON with preserved key order, but a pathological numeric reformat (e.g. `0.16131700000000002`) is possible. Mitigation: the hygiene spec asserts token counts are still present and correct after refresh; the diff is reviewed by the human before commit (the agent never commits). Accepted: fixtures are for shape/parse tests, not exact-number contracts.
- **Deny-list drift**: a future CLI version could add a new sensitive field the deny-list does not name. Mitigation: the always-on hygiene spec makes the omission visible on the next refresh (a reviewer sees the new field in the diff); the deny-list is one edit to extend. Documented in the scrubber javadoc (NFR-O1).
- **Idempotency depends on stable serialization**: guarded directly by an idempotency scenario (scrub twice → identical), so any instability fails a test rather than silently churning.

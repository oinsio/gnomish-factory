## Why

The paid-smoke recorder commits a real `claude -p --output-format stream-json` transcript verbatim, running each line only through `ReferenceDumpScrubber`, which today replaces workspace paths and session ids and nothing else. As a result the refreshed `*.reference.json` fixtures now carry data the parser never consumes and that does not belong in the repository: the CLI's cost accounting (`total_cost_usd` and per-model `costUSD`), a `permission_denials` block that echoes raw tool inputs (file paths and file contents), and per-event `uuid` and `request_id` identifiers. The scrubber's own javadoc claims it keeps only model ids and token counts — behaviour and contract have diverged. Harden the scrubber and refresh the committed dumps deterministically, without a paid CLI run.

## What Changes

- **MODIFIED** reference-dump scrubbing (agent-executor paid-smoke tooling, D11/M4): the scrubber additionally strips money (`total_cost_usd`, every nested `costUSD`), the `permission_denials` array, per-event `uuid` and `request_id`, and machine-identifying absolute temp paths (`var/folders` hashes, `/tmp/claude-<uid>` dirs, and their dashed project-dir encoding) from each captured line, while preserving resolved model ids, every token/cache count, and opaque per-run tokens (`agentId`, `task_id`).
- **ADDED** a deterministic, idempotent re-scrub over the already-committed `*.reference.json` fixtures — refresh them from disk with no `claude` invocation and no spend.
- The three committed dumps (`plain-round`, `subagent-round`, `judge-verdict`) are regenerated through the hardened scrubber.
- The scrubber javadoc is corrected to list exactly what is stripped vs kept.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `agent-executor`: adds a requirement governing what a committed reference-dump fixture may contain (the scrubber's strip/keep contract); no change to executor or judge runtime behaviour.

## Goals

- G1: no committed fixture contains dollar figures.
- G2: no committed fixture contains raw tool inputs, file contents, home- or temp-directory absolute paths, usernames, or the `uuid`/`request_id` envelope identifiers. Opaque per-run tokens that carry no machine or user identity (`agentId`, `task_id`, the random `workspaceRoot<n>` temp suffix, API `msg_`/`toolu_` ids) are kept as representative CLI output (see NG3).
- G3: token and resolved-model realism (the point of the paid smoke, D11) is preserved intact.
- G4: fixtures can be refreshed without a paid CLI run.
- G5: the scrubber's documented contract matches its behaviour.

## Non-Goals

- NG1: not changing what the parser reads (`TokenUsageMapper` already ignores cost); no production/domain code changes.
- NG2: not re-running `paidSmokeTest` or spending any money in this change.
- NG3: not stripping legitimately representative real fields beyond the identified set (durations, `result` text, API message ids and the `tool_use_id` linkage, and overall event shape stay — that realism is D11's purpose).
- NG4: not re-introducing or re-modelling cost anywhere — the domain is already money-free.

## Users & Scenarios

- U1: a maintainer refreshing fixtures after a CLI version or parser change, who must not leak local machine data.
- U2: a reviewer reading a committed dump in a pull request, who should see token/model realism but no dollars, tool inputs, or identifiers.

## Requirements

### Functional

- FR1: the scrubber SHALL remove `total_cost_usd` and every nested `costUSD` entry from a captured line.
- FR2: the scrubber SHALL remove the `permission_denials` array entirely from a captured line.
- FR3: the scrubber SHALL remove every per-event `uuid` and `request_id` field from a captured line.
- FR4: the scrubber SHALL preserve resolved model ids and all four token/cache counts under each `modelUsage` entry, and the top-level `usage` token fields, unchanged.
- FR5: the existing workspace-path and session-id scrubbing SHALL remain, unchanged in behaviour.
- FR6: a deterministic operation SHALL re-scrub the committed `*.reference.json` files in place from disk, requiring no `claude` invocation.
- FR7: the scrubber SHALL collapse machine- or user-identifying absolute temp paths a real recording leaves behind — the macOS per-user temp root (`/private/var/folders/…`, `/var/folders/…`), the per-uid `/private/tmp/claude-<uid>/…` (and `/tmp/claude-<uid>/…`), and Claude Code's dashed project-dir encoding of such a path (`-private-var-folders-…`) — to `/workspace-scrubbed`, while preserving opaque per-run tokens that carry no machine or user identity (`agentId`, `task_id`, the `workspaceRoot<n>` suffix) per NG3.

### Non-Functional Reliability

- NFR-R1: the re-scrub SHALL be idempotent — applying it to an already-scrubbed file produces no further change (a second run yields an empty git diff).

### Non-Functional Security

- NFR-S1: after scrubbing, no committed fixture SHALL contain file contents, home- or temp-directory absolute paths (including the per-user macOS `var/folders` hash and the `/tmp/claude-<uid>` uid, in both slash and dashed-encoded form), usernames, raw tool inputs, cost figures, `permission_denials`, `uuid`, or `request_id`. Opaque per-run identifiers that carry no machine or user identity (`agentId`, `task_id`, `workspaceRoot<n>`, API `msg_`/`toolu_` ids) are deliberately retained as representative CLI output (see NG3).

### Non-Functional Cost

- NFR-C1: refreshing the fixtures SHALL spend zero tokens (no CLI process is launched).

### Non-Functional Observability

- NFR-O1: the scrubber's javadoc SHALL enumerate exactly what it strips and what it keeps, matching the implemented behaviour.

## Operator Experience Criteria

- UX1: a committed reference dump reads as realistic CLI output — real resolved model ids and token/cache counts — with no dollar amounts, `permission_denials`, `uuid`, or `request_id` fields.

## Success Metrics

- M1: `grep -rE --include='*.reference.json' 'costUSD|total_cost_usd' src/test/resources/stream-json-reference` returns zero matches (scoped to the fixtures: the directory's README legitimately names the stripped fields as documentation).
- M2: `grep -rE --include='*.reference.json' 'permission_denials|"uuid"|"request_id"' src/test/resources/stream-json-reference` returns zero matches.
- M3: resolved model ids and `inputTokens`/`outputTokens`/cache counts are still present in every refreshed dump (> 0 matches).
- M4: the re-scrub is idempotent — a second application leaves the files byte-identical (empty diff).
- M5: `./gradlew check` is green, including a hygiene spec that fails if any committed fixture regains a stripped field (the scrubber is Groovy test-support, outside the PIT mutation gate by design; its behaviour is covered by unit scenarios instead).
- M6: `grep -rE --include='*.reference.json' 'var[-/]folders|/tmp/claude-[0-9]' src/test/resources/stream-json-reference` returns zero matches — no machine temp path (slash or dashed-encoded) survives in any fixture (FR7, NFR-S1).

## Open Questions

- Q1: strip volatile timing fields (`duration_ms`, `duration_api_ms`, `ttft_ms`, `ttft_stream_ms`) as well? Proposed answer: no — they carry nothing sensitive and preserve realism; revisit only if diff churn on re-record becomes a problem.
- Q2: denylist the identified fields vs allowlist a canonical result-event shape? Resolved in design.md.

# Stream-json reference dumps (parser fixtures)

Committed `*.reference.json` fixtures the stream-json parser's specs
(`StreamJsonReferenceDumpSpec`, `src/test/groovy/.../adapter/agent/`) run
against directly — design D11 layer 1 of add-agent-executor, closing the
format-drift risk M3 and Q1 name. Each file is one round's full
`--output-format stream-json --verbose` transcript, one JSON object per
line (JSONL content; the `.reference.json` extension follows this
project's naming convention for approved/canonical test fixtures — see
`src/test/resources/status-report-v1.reference.json` — never "golden").

## Recorded and scrubbed

The `plain-round`, `subagent-round` and `judge-verdict` dumps are **real
recorded `claude` CLI transcripts** (design D11's "(3b) Paid smoke"),
each captured line passed through `ReferenceDumpScrubber` before commit.
Resolved model ids, real cache-token counts a local Ollama run cannot
produce, `result` text and the tool_use → tool_result linkage are the
genuine article; only machine-identifying and non-representative data is
removed. The `result-without-model-usage` dump stays **hand-authored** —
no live CLI still emits a `result` event that omits `modelUsage`, so the
fallback-to-init-model path needs a synthetic fixture. It round-trips
through the scrubber unchanged.

`ReferenceDumpScrubber` (in `e2e/paidsmoke/`, see its javadoc for the
exact strip/keep contract — change `harden-reference-dump-scrubber`)
strips: workspace and home paths, the macOS `/private/var/folders/…` temp
hash and per-uid `/private/tmp/claude-<uid>/…` dir (and their dashed
project-dir encoding) → `/workspace-scrubbed`; real session ids →
`ref-session-<label>-1`; the `permission_denials` array (raw tool inputs);
every `uuid`/`request_id`. It keeps: resolved model ids, all token/cache
counts and the cost figures beside them (`total_cost_usd`, every `costUSD`
— kept since `define-executor-contract`, FR22), durations, API
message ids and the `tool_use_id` linkage, `result` text, event shape,
and opaque per-run tokens (`agentId`, `task_id`) that carry no machine or
user identity. `ReferenceDumpHygieneSpec` is the always-on regression
guard — the build fails if any fixture regains a stripped field; the
same spec's gated feature re-scrubs these files in place
(`-DrescrubReferenceDumps=true`, zero CLI calls) after a fresh recording.

If a future real run's wire shape ever disagrees with a file here, the
real run wins; re-record and re-scrub, and if the parser mismapped a
field, treat it as the format-drift bug this whole test layer exists to
catch.

## Files

| File                                        | Exercises                                                                                                                                                                        |
|---------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `plain-round.reference.json`                | a plain round: init, top-level `Read`/`Write` calls, final text, `result` with flat `usage`, `modelUsage` and cost (`costUSD`, `total_cost_usd`)                                |
| `subagent-round.reference.json`             | a top-level `Agent` call delegating to a subagent: nested events carry `parent_tool_use_id` (excluded from the top-level trace); the subagent ran on the main model, so `modelUsage` has one entry |
| `judge-verdict.reference.json`              | a judge-shaped round: a read-only tool call (`Read`), final message is a fenced JSON verdict (`{"passed": true, ...}`)                                                         |
| `result-without-model-usage.reference.json` | hand-authored: a `result` event carrying only the flat `usage` field — `modelUsage` omitted entirely, exercising the fallback-to-init-model path                                 |

## Distinction from `fake-agent/scenarios/`

`fake-agent/scenarios/*/stdout.jsonl` are the fake CLI binary's scripted
playback data — a different purpose (ProcessBuilder/pipe/exit-code
mechanics, port-contract edge cases) that happens to need similarly
realistic content. This directory is the parser's own regression fixture
set, named and placed separately per M3/D11.

# Stream-json reference dumps (parser fixtures)

Committed `*.reference.json` fixtures the stream-json parser's specs
(`StreamJsonReferenceDumpSpec`, `src/test/groovy/.../adapter/agent/`) run
against directly — design D11 layer 1 of add-agent-executor, closing the
format-drift risk M3 and Q1 name. Each file is one round's full
`--output-format stream-json --verbose` transcript, one JSON object per
line (JSONL content; the `.reference.json` extension follows this
project's naming convention for approved/canonical test fixtures — see
`src/test/resources/status-report-v1.reference.json` — never "golden").

## PLACEHOLDER — not yet byte-real

**These four files are hand-authored placeholders, not recordings from a
real `claude` CLI run.** Design D11 calls this test layer "reference
dumps (recorded from real runs, sensitive data scrubbed)"; that recording
is task 11.3's job — a manual, paid, no-CI Gradle task that has not run
yet. Task 3.6 (this fixture set) exists to satisfy M3's literal
requirement — "the stream-json parser passes unit tests on committed
reference dumps" — today, ahead of that recording.

Until 11.3 runs, treat these files as *plausible*, not *authoritative*:
they were written by hand to match the wire shapes already documented in
this package's javadoc (`AgentEvent`, `StreamJsonLine`,
`TokenUsageMapper`) and the same real-CLI-shaped style already used by
`src/test/resources/fake-agent/scenarios/*/stdout.jsonl` (see that
directory's README for the identical caveat applied to the fake binary's
playback scripts). Model ids, session ids, and token counts here are
invented for readability, not sampled from a real bill.

**Task 11.3 is expected to overwrite every file in this directory** with
real recordings (resolved model ids, real cache-token counts Ollama
cannot produce, sensitive data scrubbed) — see design D11's "(3b) Paid
smoke" and proposal M4. If a real run's wire shape ever disagrees with a
file here, the real run wins; update the fixture and, if the parser
mismapped a field, treat it as the format-drift bug this whole test layer
exists to catch.

## Files

| File                                       | Exercises                                                                 |
|---------------------------------------------|-----------------------------------------------------------------------------|
| `plain-round.reference.json`                 | a clean single-model round: init, top-level Read/Write tool calls, final text, `result` with both flat `usage` and single-entry `modelUsage` |
| `subagent-round.reference.json`              | a top-level `Task` call delegating to a subagent: nested events carry `parent_tool_use_id`, `result.modelUsage` reports two distinct models |
| `judge-verdict.reference.json`               | a judge-shaped round: read-only tool calls (`Read`, `Grep`), final message is a fenced JSON verdict (`{"passed": true, ...}`) |
| `result-without-model-usage.reference.json`  | a `result` event carrying only the flat `usage` field — `modelUsage` omitted entirely, exercising the fallback-to-init-model path |

## Distinction from `fake-agent/scenarios/`

`fake-agent/scenarios/*/stdout.jsonl` are the fake CLI binary's scripted
playback data — a different purpose (ProcessBuilder/pipe/exit-code
mechanics, port-contract edge cases) that happens to need similarly
realistic content. This directory is the parser's own regression fixture
set, named and placed separately per M3/D11 even though today's content
is, for the same reason, also hand-authored.

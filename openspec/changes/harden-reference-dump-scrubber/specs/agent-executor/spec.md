## ADDED Requirements

### Requirement: Reference-dump fixtures carry realism without sensitive or per-run data
The paid-smoke recorder SHALL commit a real CLI stream-json transcript only after scrubbing each captured line so that no committed `*.reference.json` fixture contains machine-identifying data (absolute paths, usernames), real session ids, cost figures (`total_cost_usd` and every nested `costUSD`), the `permission_denials` array (raw tool inputs), or any per-event `uuid` or `request_id`; resolved model ids and all token/cache counts (top-level `usage` and per-model `modelUsage`) SHALL be preserved intact so the fixture stays representative of real CLI output. The committed fixtures SHALL be refreshable in place from disk deterministically, without invoking `claude`, and that refresh SHALL be idempotent.
<!-- implements FR1, FR2, FR3, FR4, FR5, FR6, NFR-R1, NFR-S1, NFR-C1 of harden-reference-dump-scrubber -->

#### Scenario: Money is stripped
- **WHEN** a captured result line carries `total_cost_usd` and per-model `costUSD` entries
- **THEN** the scrubbed line contains neither field
- **AND** every resolved model id and its four token/cache counts remain

#### Scenario: Raw tool inputs and identifiers are stripped
- **WHEN** a captured line carries a `permission_denials` array, a `uuid`, and a `request_id`
- **THEN** the scrubbed line contains none of the `permission_denials` array, the `uuid`, or the `request_id`

#### Scenario: Paths and session ids stay scrubbed
- **WHEN** a captured line embeds the workspace absolute path and the real session id
- **THEN** the scrubbed line shows `/workspace` and the synthetic `ref-session-<label>-1` in their place

#### Scenario: Deterministic zero-cost refresh
- **WHEN** the committed fixtures are refreshed from disk
- **THEN** no `claude` process is launched and no tokens are spent
- **AND** applying the refresh a second time leaves the files byte-identical

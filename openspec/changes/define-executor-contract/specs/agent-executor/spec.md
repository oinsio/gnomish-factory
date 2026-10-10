# agent-executor — delta for define-executor-contract

## ADDED Requirements

### Requirement: Decision file is read through the shared result reader
The adapter SHALL read the decision file through the executor contract's single result reader,
with its size cap and untrusted-text wrapping, and SHALL surface the request as a
`decision-request` answer on a `completed` status. The decision-file path, naming and tolerant
fallbacks of the existing protocol SHALL be unchanged.
<!-- implements FR6 of define-executor-contract -->

#### Scenario: Decision file read is capped
- **WHEN** the gnome writes a decision file larger than the result cap
- **THEN** at most the cap is read, truncation is recorded, and the round still resolves as a
  decision request

#### Scenario: Adapter constructs no failure class
- **WHEN** the agent process is killed on its round timeout
- **THEN** the adapter reports the run with the contract's status and the engine, not the
  adapter, classifies it as infrastructure

### Requirement: Per-model cost mapping
The adapter SHALL map each `modelUsage` entry's `costUSD` beside that model's four token
counts, for stage rounds and judge votes alike; an entry without `costUSD` SHALL carry no
cost, never zero, and the flat-usage fallback of older CLIs carries no cost. The adapter SHALL
NOT price tokens itself.
<!-- implements FR22, NG10 of define-executor-contract -->

#### Scenario: Cost rides with its model
- **WHEN** the result event reports two model ids, one with `costUSD`
- **THEN** the round's usage carries the cost under that model only and the other model's cost is absent

#### Scenario: Judge vote carries cost
- **WHEN** a judge vote's result event reports `modelUsage` with `costUSD`
- **THEN** the vote's `verdict` entry carries that cost per model


### Requirement: Reference-dump fixtures carry realism and cost without sensitive or per-run data
The paid-smoke recorder SHALL commit a real CLI stream-json transcript only after scrubbing each captured line so that no committed `*.reference.json` fixture contains machine-identifying data (home- or temp-directory absolute paths — including the macOS `var/folders` per-user hash and the `/tmp/claude-<uid>` uid, in both slash and dashed-encoded form — and usernames), real session ids, the `permission_denials` array (raw tool inputs), or any per-event `uuid` or `request_id`; resolved model ids, all token/cache counts (top-level `usage` and per-model `modelUsage`), cost figures (`total_cost_usd` and every nested `costUSD` — a derived view of the counts already kept, not sensitive data), and opaque per-run tokens that carry no machine or user identity (`agentId`, `task_id`, API `msg_`/`toolu_` ids) SHALL be preserved intact so the fixture stays representative of real CLI output. The committed fixtures SHALL be refreshable in place from disk deterministically, without invoking `claude`, and that refresh SHALL be idempotent.
<!-- implements FR1, FR2, FR3, FR4, FR5, FR6, FR7, NFR-R1, NFR-S1, NFR-C1 of harden-reference-dump-scrubber -->
<!-- implements FR22 of define-executor-contract -->

#### Scenario: Money is kept
- **WHEN** a captured result line carries `total_cost_usd` and per-model `costUSD` entries
- **THEN** the scrubbed line keeps both fields
- **AND** every resolved model id and its four token/cache counts remain

#### Scenario: Machine temp paths are collapsed but opaque per-run tokens survive
- **WHEN** a captured line embeds a macOS `var/folders` temp hash, a per-uid `/tmp/claude-<uid>` dir, or their dashed project-dir encoding, alongside an `agentId`/`task_id`
- **THEN** every such path is collapsed to `/workspace-scrubbed`, leaving no `var/folders`, `var-folders`, or `/tmp/claude-<uid>` substring
- **AND** the opaque `agentId`/`task_id` tokens remain unchanged

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

## REMOVED Requirements

### Requirement: Reference-dump fixtures carry realism without sensitive or per-run data
**Reason**: superseded by "Reference-dump fixtures carry realism and cost without sensitive or per-run data" below — cost figures are a derived view of the token counts the fixture already keeps, so the money rule is withdrawn while every other scrubbing rule stays.
**Migration**: the scrubber's deny-list drops `total_cost_usd` and `costUSD`; the committed reference dumps are refreshed with cost.

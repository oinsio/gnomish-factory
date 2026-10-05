# Spec Delta: manual-run

## MODIFIED Requirements

### Requirement: Manifest-driven mechanism with interactive override
`gnomish run` SHALL bind mechanisms from the manifest alone: `agent-cli` stages to the CLI stage executor and judge checks to the CLI judge voter (models and settings from the manifest); `external` checks to the configured provider's client. No flag SHALL substitute a human for any of the three roles; `--interactive` in any form SHALL be a usage error like any unknown flag. A pipeline containing an `api` stage, or an `external` check whose provider has no `factory.check.<provider>` section, SHALL fail fast in the startup validation chain — pipeline-load exit code, before any dialog, naming the stage and, for a check, the check and the provider. No confirmation gate precedes the first paid round: the manifest-driven run is the tool's purpose and the operator is present.
<!-- implements FR1, FR2, FR3 of remove-interactive-console -->

#### Scenario: Flagless run uses real adapters
- **WHEN** `gnomish run` executes an `agent-cli` manifest with a judge check and no flags
- **THEN** the stage round and the judge vote both run through the CLI adapters with the manifest's pinned models

#### Scenario: Api stage rejected before any dialog
- **WHEN** the loaded pipeline contains a stage with `executor.type: api`
- **THEN** the process exits with the pipeline-load exit code naming the stage, without prompting

#### Scenario: Unconfigured check provider rejected before any dialog
- **WHEN** the loaded pipeline declares an `external` check whose provider is discovered but has no `factory.check.<provider>` section
- **THEN** the process exits with the pipeline-load exit code naming the stage, the check and the provider, and no branch, worktree or dialog is created

#### Scenario: Mixed run for judge calibration
- **WHEN** `gnomish run --interactive=judge` is invoked
- **THEN** the process exits with the usage-error code before loading the pipeline: no role is ever swapped for a human

#### Scenario: Full interactive override
- **WHEN** `gnomish run --interactive` is invoked
- **THEN** the process exits with the usage-error code before loading the pipeline

### Requirement: Exit codes by outcome family
Exit codes SHALL split into two families at 10: `< 10` — the tool itself could not do its job (0 reserved for success), `≥ 10` — the factory reached a legitimate non-completed outcome, so a scripted consumer can test `$? -ge 10`. All codes stay outside the shell signal zone (128+n); 1 and 2 keep their conventional roles. Code 4 is retired and SHALL NOT be returned; it stays listed as a gap so that no other code shifts.
<!-- implements FR5, FR8 of remove-interactive-console -->

| Code | Family       | Meaning                                                                                                                                             |
|------|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| 0    | success      | `Completed`                                                                                                                                         |
| 1    | tool failure | internal error (unexpected exception; `PipelineMismatch`, unreachable in-process)                                                                   |
| 2    | tool failure | usage error (bad flags, unknown `--from-stage`)                                                                                                     |
| 3    | tool failure | pipeline load failure (`.gnomish/` invalid, `api` stage, unconfigured check provider; loader errors printed as-is)                                  |
| 4    | retired      | used in the MVP for stdin ending inside an interactive adapter; removed as unneeded; kept as a gap so other codes do not shift; may be filled by a future tool failure |
| 5    | tool failure | diverged branch on a claimless resume                                                                                                               |
| 6    | tool failure | task not found (`status` / `usage` only)                                                                                                            |
| 7    | tool failure | branch shape refused on pickup                                                                                                                      |
| 10   | outcome      | `Escalated` — operator left with the task in escalation                                                                                             |
| 11   | outcome      | `Paused` — operator left at a manual checkpoint                                                                                                     |
| 12   | outcome      | `Aborted` — persistence failed                                                                                                                      |

#### Scenario: Persistence failure is Aborted
- **WHEN** the persistence port fails (breaking fake)
- **THEN** the process prints the cause and unpersisted-state summary to stderr and exits 12

#### Scenario: Retired code is never returned
- **WHEN** any run terminates, by any path
- **THEN** the exit code is one of 0, 1, 2, 3, 5, 6, 7, 10, 11, 12

### Requirement: EOF semantics without a TTY
Piped stdin SHALL be a first-class mode: no TTY check. No engine port SHALL read the console, so input can end only at a runner prompt (escalation decision, checkpoint confirmation); EOF there SHALL exit with that outcome's code. Exhausted input SHALL never hang the process or re-enter an input-requiring dialog.
<!-- implements FR5 of remove-interactive-console -->

#### Scenario: Deliberate exit at an escalation
- **WHEN** the operator presses Ctrl-D at the resume prompt
- **THEN** the process exits 10

#### Scenario: Deliberate exit at a checkpoint
- **WHEN** the operator presses Ctrl-D at the checkpoint confirmation
- **THEN** the process exits 11

#### Scenario: Script too short
- **WHEN** stdin is closed before the run starts and the pipeline completes without escalation or checkpoint
- **THEN** the process exits 0 and no prompt was printed; no path returns the retired code 4

### Requirement: Port-contract compliance of new adapters
The CLI adapters SHALL pass the same port-level contract suites the add-stage-engine fakes pass, driven through the fake agent binary where a subprocess is needed. A contract variant an adapter cannot produce SHALL be recorded as a port-shape finding, not worked around.
<!-- implements FR4, FR6 of remove-interactive-console -->

#### Scenario: One suite, many adapters
- **WHEN** the port-contract suite runs against the CLI stage executor with the fake agent
- **THEN** every suite scenario passes without modification

## REMOVED Requirements

### Requirement: Interactive stage executor
**Reason**: no production adapter reads the console on behalf of a gnome; the fake agent binary is the only scripted gnome, and it lives in the test tree.
**Migration**: specs that scripted the executor through stdin drive the `fake-agent` fixture; there is no operator migration, the flag is gone.
<!-- implements FR4 of remove-interactive-console -->

### Requirement: Interactive external poll
**Reason**: an `external` check is served by a configured provider or refused at startup; a console stand-in for CI hid a misconfiguration behind a prompt.
**Migration**: configure `factory.check.<provider>` or remove the check from the stage.
<!-- implements FR3, FR4 of remove-interactive-console -->

### Requirement: Interactive judge vote
**Reason**: no production adapter reads the console on behalf of a judge; judge-prompt debugging runs the CLI judge against the fake agent's `judge-verdict-*` scenarios in the test tree.
**Migration**: none for operators; specs move to the fake agent.
<!-- implements FR4 of remove-interactive-console -->

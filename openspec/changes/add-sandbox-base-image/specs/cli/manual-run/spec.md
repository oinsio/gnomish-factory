## MODIFIED Requirements

### Requirement: Exit codes by outcome family
Exit codes SHALL split into two families at 10: `< 10` — the tool itself could not do its job (0 reserved for success), `≥ 10` — the factory reached a legitimate non-completed outcome, so a scripted consumer can test `$? -ge 10`. All codes stay outside the shell signal zone (128+n); 1 and 2 keep their conventional roles. Code 8 is an operator precondition the run could not meet (the clone's ref format, the container runtime, the box's git floor, the image contract). Code 4 is retired and SHALL NOT be returned; it stays a gap so that no other code shifts.
**Implements**: FR5, FR8 of remove-interactive-console; FR14 of add-sandbox-base-image
**Notes**: Code 4 was used in the MVP for stdin ending inside an interactive adapter and was removed as unneeded; a future tool failure may fill it.
**Notes**: Code 8 is shared with `take` ("Shared codes mean the same in run"), in the spirit of `sysexits` `EX_CONFIG` (design D18 of add-sandbox-base-image).

#### Scenario: Persistence failure is Aborted
- **WHEN** the persistence port fails (breaking fake)
- **THEN** the process prints the cause and unpersisted-state summary to stderr and exits 12

#### Scenario: Retired code is never returned
- **WHEN** any run terminates, by any path
- **THEN** the exit code is one of 0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12

#### Scenario: Each terminal outcome has its exit code
- **WHEN** a run ends with `Completed`, `Escalated`, `Paused` or `Aborted`
- **THEN** the exit code is 0 (success), 10 (the operator is left with the task in escalation), 11 (the operator is left at a manual checkpoint) or 12 (persistence failed) respectively

#### Scenario: Each tool failure has its exit code below 10
- **WHEN** a command ends because the tool itself could not do its job
- **THEN** the exit code is 1 for an internal error (an unexpected exception; `PipelineMismatch`, unreachable in-process), 2 for a usage error (bad flags, unknown `--from-stage`), and 3 for a pipeline load failure (`.gnomish/` invalid, an `api` stage, an unconfigured check provider; loader errors printed as-is)
- **AND** the exit code is 5 for a diverged branch on a claimless resume, 6 for a task not found (`status` / `usage` only), and 7 for a branch shape `status` refuses to inspect (a quarantine shape)
- **AND** the exit code is 8 for an operator precondition the run could not meet: the factory clone's ref format, an unreachable container runtime, the box's git floor or the image contract, each printed with its fix

#### Scenario: A precondition refusal in run exits 8, never 1
- **WHEN** `gnomish run` stops because the factory clone's ref format is refused
- **THEN** the process prints the refusal's own line and exits 8, and no stack trace is printed

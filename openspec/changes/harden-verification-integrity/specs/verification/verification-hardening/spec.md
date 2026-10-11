# verification/verification-hardening — delta for harden-verification-integrity

## ADDED Requirements

### Requirement: Repo-local agent policy cannot widen the gnome
For every agent executor round the runner SHALL set the executor's declared config-directory
variable to a factory-owned directory holding only factory-written content and SHALL pass the
declared repo-policy-disabling flags; an agent executor declaring neither SHALL be a located load
error unless the law exempts it by name with a reason.
<!-- implements FR5, NFR-S2, M2 of harden-verification-integrity -->

#### Scenario: Settings file in the working copy changes nothing
- **WHEN** attempt 1 writes `.claude/settings.json` granting every tool and attempt 2 starts
- **THEN** attempt 2's argv carries the policy-disabling flags, its config directory is the
  factory's, and its effective tool set equals attempt 1's

#### Scenario: Executor without isolation fields is refused
- **WHEN** an agent executor's describe declares no config-directory variable and no flags
- **THEN** loading reports a located error naming the executor, unless the law exempts it

### Requirement: Advisory checks report without failing
A check declaring `severity: advisory` SHALL have its findings recorded in the attempt and
published to the tracker tagged `advisory`, SHALL NOT fail the stage and SHALL NOT burn an
attempt; `blocking` is the default and keeps today's behavior.
<!-- implements FR6, UX2, G4 of harden-verification-integrity -->

#### Scenario: Lint warnings do not block
- **WHEN** an advisory `shell` check fails with three findings
- **THEN** the stage proceeds, `attemptsUsed` is unchanged, and the three findings appear on the
  task tagged advisory

#### Scenario: Advisory infrastructure failure
- **WHEN** an advisory check's executor returns `failed/infrastructure`
- **THEN** the stage proceeds and the ledger records the check as unavailable

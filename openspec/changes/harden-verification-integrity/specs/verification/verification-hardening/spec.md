# verification/verification-hardening — delta for harden-verification-integrity

## ADDED Requirements

### Requirement: Repo-local agent policy cannot widen the gnome
For every agent executor round the runner SHALL set the executor's declared config-directory
variable to a factory-owned directory that starts each round with factory-written content only
and is discarded with the round, and SHALL pass the declared repo-policy-disabling flags; an
agent executor declaring neither SHALL be a located load error unless the law exempts it by name
with a reason. A round's credentials SHALL reach it only through the describe-declared
credential variables.
**Implements**: FR5, NFR-S2, M2 of harden-verification-integrity

#### Scenario: Settings file in the working copy changes nothing
- **WHEN** attempt 1 writes `.claude/settings.json` granting every tool and attempt 2 starts
- **THEN** attempt 2's argv carries the policy-disabling flags, its config directory is the
  factory's, and its effective tool set equals attempt 1's

#### Scenario: The CLI's own state does not outlive the round
- **WHEN** the agent CLI writes a state file into its config directory during attempt 1 and
  attempt 2 starts
- **THEN** attempt 2's config directory holds only factory-written content at its start

#### Scenario: A login under the operator's home is not carried into the round
- **WHEN** the operator's home holds an agent CLI login and a host-mode round starts
- **THEN** the round's config directory holds no copy of that login, and the round receives
  credentials only through the describe-declared credential variables

#### Scenario: Executor without isolation fields is refused
- **WHEN** an agent executor's describe declares no config-directory variable and no flags
- **THEN** loading reports a located error naming the executor, unless the law exempts it

### Requirement: Advisory checks report without failing
A check declaring `severity: advisory` SHALL have its findings recorded in the attempt and
published to the tracker tagged `advisory`, SHALL NOT fail the stage and SHALL NOT burn an
attempt; `blocking` is the default and keeps today's behavior.
**Implements**: FR6, UX2, G4 of harden-verification-integrity

#### Scenario: Lint warnings do not block
- **WHEN** an advisory `shell` check fails with three findings
- **THEN** the stage proceeds, `attemptsUsed` is unchanged, and the three findings appear on the
  task tagged advisory

#### Scenario: Advisory infrastructure failure
- **WHEN** an advisory check's executor returns `failed/infrastructure`
- **THEN** the stage proceeds and the ledger records the check as unavailable

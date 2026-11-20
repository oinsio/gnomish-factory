# check-provider-model

## Purpose

Bring the external-check port to parity with the tracker port: a `provider` discriminator on the `CheckClientFactory` SPI, a `ServiceLoader`-populated registry, per-provider params and operator-subsection validators returning located `ConfigError` data, SPI-declared credential names feeding the child-environment scrub and never-allowlist sets, per-check provider selection inside a stage manifest, `provider`+`checkId` findings identity, and the adapter pin-path contribution hook — so github is one provider among many rather than a hardwired special case.

## Requirements

### Requirement: Command checks are bounded
A stage's `command` verify check SHALL be bounded by a configured,
installation-level timeout. A check that has not exited when the timeout
expires SHALL be terminated (tree-wide, with the supervision kill discipline)
and SHALL classify as a quality failure whose findings carry the captured
output tail — the command ran and failed to finish, exactly as a red exit code
would have failed it — burning a stage attempt and feeding the tail back as
retry context.
**Implements**: FR12, FR5, NFR-O1, UX4 of bound-subprocess-commands

#### Scenario: A hung check fails instead of hanging the run
- **WHEN** a `command` check enters an infinite loop and its timeout expires
- **THEN** the check resolves as a quality failure within the timeout plus the
  kill margin, its findings carry the output tail captured so far, and the
  stage's ordinary retry loop proceeds

#### Scenario: A check that finishes in time is untouched
- **WHEN** a `command` check exits before the timeout
- **THEN** its exit code, tail capture, and verdict classification are exactly
  as before

#### Scenario: A timed-out check is logged with its bound
- **WHEN** a `command` check's timeout expires
- **THEN** the expiry is logged naming the check id, the elapsed time, and the
  configured timeout

### Requirement: Check identity for findings correlation is executor plus checkId
Findings SHALL be correlated by executor name plus `checkId`, so two checks on the same
executor remain distinguishable in the attempt record and the tracker.
**Implements**: FR1 of migrate-verify-to-executor-contract

#### Scenario: Same executor, two checks
- **WHEN** two `http` checks with different `checkId`s run in one stage
- **THEN** their findings carry distinct identities

## MODIFIED Requirements

### Requirement: Exit codes by take result
`gnomish take` SHALL exit with: 0 — Delivered, or a clean bare-mode no-op;
1 — failure outside a claimed run; 2 — usage error; 3 — pipeline load
failure; 8 — an operator precondition refused after the claim (the clone's ref
format, the container runtime, the box's git floor, the image contract), the
claim released; 10 —
parked as escalation; 11 — parked as checkpoint; 12 — infrastructure abort
below the fuse; 13 — parked as infra; 14 — revoked; 15 — refused or skipped;
16 — claim released on a base-refresh infrastructure failure before any work
started.
**Implements**: FR9 of add-tracker-port; FR10 of add-tracker-port; FR15 of add-tracker-port; FR9 of add-base-ref-resolution; FR14 of add-sandbox-base-image
**Notes**: 8 is the below-10 family's configuration code, in the spirit of `sysexits` `EX_CONFIG`: the operator fixes it, a retry will not (design D18).

#### Scenario: Empty queue exits clean
- **WHEN** bare `take` finds no eligible ready task
- **THEN** the process exits 0 reporting an empty queue

#### Scenario: Escalation park exit
- **WHEN** a take run parks its task as an escalation
- **THEN** the process exits 10

#### Scenario: Refusal exit
- **WHEN** `take <ref>` refuses a task held by another instance
- **THEN** the process exits 15 naming the holder

#### Scenario: Released-on-outage exit
- **WHEN** a single-shot `take` releases its claim because the base refresh
  hit a dead remote
- **THEN** the process exits 16 naming the remote and the cause, and the
  task is Ready with no new marker

#### Scenario: Failure before a claim exits 1
- **WHEN** the tracker is unreachable at startup, or label provisioning fails
- **THEN** the process exits 1

#### Scenario: Infra park exit
- **WHEN** a take run parks its task as infra, by a fuse trip or an infrastructure escalation
- **THEN** the process exits 13

#### Scenario: Every refusal and skip exits 15
- **WHEN** `take <ref>` finds its task already done, closed or nonexistent, or names a foreign
  repo
- **THEN** the process exits 15

#### Scenario: Shared codes mean the same in run
- **WHEN** `gnomish run` and `gnomish take` end with the same result family (usage error,
  pipeline load failure)
- **THEN** both exit with the same code

#### Scenario: An uncaught exception never exits a bare 1
- **WHEN** a claimed take run dies with an uncaught exception
- **THEN** the abort protocol runs and the process exits 12, or 13 at the fuse — never 1

#### Scenario: Operator precondition refused after the claim exits 8
- **WHEN** a single-shot `take` claims its task and the clone's ref format, the box's git floor or the image contract refuses
- **THEN** the process exits 8 printing the refusal and its fix, the claim is released, the task carries no abort marker, and the outcome log recorded the typed precondition result

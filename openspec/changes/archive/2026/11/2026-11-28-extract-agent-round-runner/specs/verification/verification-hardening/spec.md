# verification/verification-hardening — delta for extract-agent-round-runner

## MODIFIED Requirements

### Requirement: Sandboxed judge votes run in a fresh environment
In sandboxed mode, every `judge` request SHALL execute in a fresh environment of its own, materialized from the attempt commit — never in the gnome-touched round environment, whose out-of-branch residue (PATH shims, planted binaries) could fake a verdict, and never in the environment of another request.
**Implements**: FR15, NFR-S2 of add-sandbox-core; FR4 of extract-agent-round-runner

#### Scenario: Poisoned round box cannot grade itself
- **WHEN** a round leaves a PATH shim outside the working copy and a judge check then runs
- **THEN** the vote executes in a fresh environment built from the image and the attempt commit, where the shim does not exist

#### Scenario: Two judge checks do not share a box
- **WHEN** a stage's two `judge` checks run on one attempt commit
- **THEN** each request runs in a box materialized for it and disposed when it ends

# Gaps: law and checks

Entries of [`../gaps.md`](../gaps.md) about the law as sealed and the checks that grade an
attempt. The form and the vocabulary are in the index.

### GP-LAW-UNSEALED-AGENT-FILES — The agent CLI's own instruction and policy files are not law
**Breaks:** `UC-SEAL-THE-LAW`, `UC-ASK-ONLY-TO-TIGHTEN` (the agent's repo-local policy file can widen its tool permissions, where the law can only tighten)
**Where today:** `pipeline/pipeline-config`: Pipeline law binds per invocation (names `.gnomish/` only); `CLAUDE.md`, `.claude/settings.json` and their equivalents are read from the gnome-writable copy
**Kind:** `missing`
**Resolution:** `planned: harden-verification-integrity` (repo-local agent policy cannot widen the gnome; instruction files only where the law declares them protected)

### GP-VERIFY-RUNS-GNOME-TREE — A check runs the project's commands from the gnome's tree
**Breaks:** `UC-SEAL-THE-LAW`, `UC-PROTECT-THE-EVIDENCE`, `UC-PIN-THE-EXTERNAL-CHECK`
**Where today:** `pipeline/stage-engine`: Ordered fail-fast verification; the check is law, what it invokes is gnome-editable; `verification/verification-hardening`: Pin-check guards external checks (the pin covers the workflow file, not the build scripts and tests the workflow runs from the attempt commit); no check fails an attempt for touching a test, a threshold or a build script
**Kind:** `wrong-turn` (decision: a command check runs in the attempt's working tree, and an external check runs on it; premise: what the check invokes is the project's code; refuted by `UC-SEAL-THE-LAW`, whose **whatever the gnome writes in its copy** covers the build scripts the check runs)
**Resolution:** `planned: harden-verification-integrity` (sealed assets, protected paths, diff-scope verdict)

### GP-EVERY-FINDING-BLOCKS — A check cannot report without failing the stage
**Breaks:** `UC-REPORT-WITHOUT-FAILING`
**Where today:** `pipeline/stage-engine`: Ordered fail-fast verification (the first non-pass verdict ends the attempt; a check carries no severity)
**Kind:** `missing`
**Resolution:** `planned: harden-verification-integrity` (`severity: blocking | advisory` on a check; advisory findings reach the tracker and the attempt record and burn nothing)

### GP-CHECK-TARGET-UNCHECKED — A check's target is found missing only after a paid round
**Breaks:** `UC-HEAR-EVERY-LAW-ERROR-AT-ONCE`
**Where today:** `pipeline/pipeline-config`: Local sanity validation of mechanism and check configs (the loader validates no target liveness by decision: a workflow name, a command binary, a model name); `verification/github-external-check`: Misconfiguration fails fast without burning an attempt (found at the first poll, after the stage's rounds were paid for)
**Kind:** `missing`
**Resolution:** `open` (`add-doctor-command` checks the secrets and the binaries the checks declare, not whether a named workflow or model exists)

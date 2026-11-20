# cli/manual-run — delta for migrate-verify-to-executor-contract

## Purpose

The shell check's exit-code semantics leave this capability: the exit code is status, never a
verdict (FR5, design D5 as amended 2026-11-08), so the two requirements that bound exit 0 to
`Pass`, non-zero to `Fail` and the drop of a findings file on a green check are removed here,
and their kept duties are restated in `verification/verdict-executors` in the derived form.

## REMOVED Requirements

### Requirement: Command check runner
**Reason**: exit 0 → `Pass` and other non-zero → `Fail` made the exit code the verdict's source, which FR5 and "Executors report status, the engine derives the verdict" forbid; the kept duties (`sh -c` through `exec()` of the bound task environment, the working copy as working directory, the bounded output tail, the layered allowlist, 126/127 as infrastructure) move to `verification/verdict-executors`, "The shell executor runs the command in the task environment".
**Migration**: none for operators; the markers of its four scenarios move to the new titles in task 3.2.

### Requirement: Findings-JSON wire format
**Reason**: "a findings file on a green check is ignored" contradicts "A pass keeps its below-threshold findings" (D5 as amended): the file is read on every exit code but 126/127, SARIF or the legacy `{"findings":[...]}` form, and the engine derives the verdict from it; the kept duties (the scratch-area path, `GNOMISH_FINDINGS_FILE`, the read through the environment, the synthetic result when nothing valid was written) move to `verification/verdict-executors`, "The shell executor reads its findings path whatever the exit code".
**Migration**: a reporter that wrote findings on a green run now sees them recorded — a `warning` result is carried on the pass, an `error` result fails the check; a reporter that must not fail a green run writes results below the check's threshold or no file.

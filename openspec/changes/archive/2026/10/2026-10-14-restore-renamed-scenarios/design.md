## Context

Driven by FR1-FR3 of the proposal; the mechanism is decided in `retire-misnamed-scenarios` (D1-D3).

## Decisions

### D1: requirement text is copied by script, not retyped

Each ADDED block is the exact block extracted from the current `openspec/specs/` file with one
title substituted, so nothing but the title can differ. The scratch-worktree `diff` is the check.

### D2: the F1 pair is split by requirement

Both scenarios of tracker-port are titled `Suite passes on both adapters`. Only the one under
`Contract suite covers the returned fact` is renamed: `add-claim-return` modifies the
lease-maintenance requirement and cites its title; no pending change cites the returned-fact one.
The two tests sharing the marker today: the GitHub and in-memory contract specs cover both
requirements, so each file gets the new marker beside the old one.

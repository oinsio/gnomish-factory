Change: own-git-invocation-policy
Verdict: no-blocking-dependencies

## Notes
- Prerequisites tighten-spec-contract and fix-terminal-receipt-convergence are archived (proposal.md:212). It must land before add-subprocess-access-log (proposal.md:209), which only waits on it.

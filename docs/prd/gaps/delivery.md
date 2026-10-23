# Gaps: delivery

Entries of [`../gaps.md`](../gaps.md) about the pull request, the merge and what the factory
leaves behind. The form and the vocabulary are in the index.

### GP-DELIVERY-IS-A-GNOME-STAGE — The pull request is opened by the gnome with a write token
**Breaks:** `UC-RECEIVE-AN-ORDINARY-PULL-REQUEST`, `UC-DELIVER-WITHOUT-ARMING-THE-GNOME`
**Where today:** nothing in the engine; the reference pipelines' `deliver` stage is an agent round running the forge's command line, and `operator-guide.md` has the operator pass a token with write access to contents and pull requests into the box through `sandbox/execution-environment`: Layered positive environment allowlist (the passthrough layer)
**Kind:** `missing`
**Resolution:** `open` (`add-command-executor` turns the round into a command, but the token stays in the box; a factory-side delivery through the tracker or a forge port has no change)

### GP-SQUASH-MERGE-IS-ADVICE — Only a squash merge keeps the round journal off the main branch
**Breaks:** `UC-KEEP-MAIN-FREE-OF-BOOKKEEPING`
**Where today:** `git/git-task-persistence`: Cleanup commit on completion (the tip is clean, the history is not); `operator-guide-run.md` recommends a squash merge; `lifecycle/task-branch-contract`: Total branch-shape classification tolerates a merged journal in the base
**Kind:** `missing`
**Resolution:** `open`

### GP-REGISTER-REF-OUTLIVES-THE-BRANCH — The factory register stays in origin after the branch is gone
**Breaks:** `UC-LEAVE-NOTHING-BEHIND-AFTER-MERGE`
**Where today:** `git/git-task-persistence`: Factory register ref (kept per task in origin, named by no deletion); the forge deletes the merged branch and nothing deletes `refs/gnomish-factory/<task>`
**Kind:** `missing`
**Resolution:** `open`

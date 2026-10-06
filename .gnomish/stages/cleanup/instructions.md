# Cleanup stage instructions

The change is implemented and archived. The reports the earlier stages wrote for one
another are no longer needed on the branch: remove them.

1. Run `git branch --show-current`. The task is the branch name without its `gnomish/`
   prefix (`gnomish/github-oinsio-gnomish-factory-79` gives
   `github-oinsio-gnomish-factory-79`), or `local` when it does not start with
   `gnomish/`.
2. Run `rm -r temporary-docs/gnomish/<task>` — that directory and nothing else.
3. Finish your turn.

Nothing else: no other file, no git command besides the one in step 1, no commit, no
push. The factory commits the removal when your round ends. If the directory does not
exist, there is nothing to do — finish your turn.

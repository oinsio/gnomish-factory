# Deliver stage instructions

The change is implemented, archived, and the pipeline's reports are removed. Now open
the pull request that a human will review and merge.

`<task>` below is the name of this task's branch without its `gnomish/` prefix —
`git branch --show-current` shows it (`gnomish/github-oinsio-gnomish-factory-79` gives
`github-oinsio-gnomish-factory-79`). The task's issue number is the last `-`-separated
part of `<task>` (`79`).

**The title and the body are the project's own `/pr-description` command: read
`.claude/commands/pr-description.md` and carry out its steps 1–3**, for a human at the
keyboard and for you alike. Everything below is only what differs; where it is silent,
the command decides.

## What differs in this stage

1. **Read the commands, do not invoke them.** The Skill tool is not available in this
   round. There are no `$ARGUMENTS`.
2. **The base is the commit the branch was cut from, not `main`.** Read it once:
   `jq -r .baseCommit .gnomish-task/task.json`. Wherever the command writes a range
   against `main`, use that commit instead: `git log --oneline <base>..HEAD`,
   `git diff --stat <base> HEAD`, `git diff <base> HEAD -- <path>`. The branch holds only
   this task's work, so these show exactly what the pull request delivers, however far
   `main` has moved since. Leave `.gnomish-task/` out of the description: it is the
   factory's bookkeeping and leaves the branch when the task completes.
3. **The change** is the one this branch archived: the directory of the
   `openspec/changes/archive/**/proposal.md` file that `git diff --stat <base> HEAD`
   lists as added. Read its `proposal.md` and `design.md` as the
   command's step 1 says. The pipeline's reports are gone from the working copy; do not
   go looking for them in history.
4. **The body** is the command's template, then one line `Closes #<issue>`. Write it
   with the Write tool to `$TMPDIR/pr-body.md` (run `echo "$TMPDIR"` first), never into
   the working copy. Time and token usage are not yours to report: the next stage posts
   them.
5. **Open the pull request** instead of the command's step 4, which only prints:
   1. `git ls-remote --heads origin <branch>` — the factory pushes the branch after every
      round. If origin does not have it, escalate; never push it yourself.
   2. `gh pr list --head <branch> --state all --json number,state,url`:
      - none → `gh pr create --base main --head <branch> --title "<title>" --body-file "$TMPDIR/pr-body.md"`;
      - one `OPEN` → a previous round created it: update it with
        `gh pr edit <number> --title "<title>" --body-file "$TMPDIR/pr-body.md"`, never a second one;
      - only `MERGED` or `CLOSED` ones → escalate: a human already decided about this branch.
6. **A rejected round's feedback** names what the check found in the open pull request:
   fix it with `gh pr edit`.
7. **The escalation exit.**
   1. run `echo "$GNOMISH_DECISION_FILE"` to learn where the decision file goes;
   2. write that path with the Write tool — one JSON object,
      `{"question": "...", "options": ["...", "..."]}`, the whole situation in
      `question`, newlines as `\n`, options that fit the case;
   3. finish your turn.

## Rules of this medium

- **Nobody is watching this round.** A turn that ends in a question to the operator is a
  lost attempt; the escalation exit is the only channel a human reads.
- **Change no file of the repository** — a check rejects any. No commit, no push, no
  `checkout`, `reset`, `stash`, `restore`, `clean` or `pull`; `gh` only for
  `pr list`, `pr create`, `pr edit` and `pr view`. Never merge, close or comment on
  anything.
- The title and body are in English, whatever language the issue is in.

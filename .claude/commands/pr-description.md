---
description: Prepare a PR title and markdown body from the diff between main and the current branch
argument-hint: "[extra emphasis or context for the description]"
---

# PR Description

Produce a **pull request title** and a **markdown body** for the current branch against
`main`. Reads history only — no code, no commits, no push.

`$ARGUMENTS` — optional extra context or emphasis (reviewer audience, a risk to call out,
an issue number). Empty is normal.

## Steps

### 1. Read the change

Run each command as its own call — never chained with `&&` or `;`, so one refusal does not
cost the rest:

```bash
git rev-parse --abbrev-ref HEAD
git log --oneline main..HEAD
git diff --stat main...HEAD
```

Three dots in `diff` (and two in `log`) already resolve to the **merge base**, so commits
that landed on `main` after the branch started stay out. Do not call `git merge-base` — the
project's deny list blocks `git merge*` and refuses it.

Stop and say so if the branch *is* `main`: there is nothing to describe.

Then read the diff itself, in slices when it is large:

```bash
git diff main...HEAD -- <path from the --stat output>
```

Take every path from `--stat`, never guessed globs, and read each slice to its end (`sed -n`
over ranges, not `head`) until the slices cover the whole `--stat`. A file you did not finish
reading is a file you may not describe.

Uncommitted work is not part of the PR. If the diff touches `openspec/changes/**` (including
`archive/`), read that change's `proposal.md` and `design.md` — they carry the *why* and the
requirement IDs the body should reference instead of re-deriving intent from the code.

### 2. Write the title

One line, Conventional Commits shape, matching this repo's history (`feat(take):`,
`fix(git):`, `test(take):`, `docs:`, `refactor:`, ...): ≤ 72 characters, imperative mood, no
trailing period, scope = the module or capability touched when there is one obvious one.
Describe the outcome, not the mechanics ("cap the abort cause before every tracker write",
not "add a call to capCause").

### 3. Write the body

Use this structure; drop a section rather than filling it with filler:

```markdown
## What

One paragraph: what this branch changes, in plain language.

## Why

The problem or requirement that drove it. Reference the OpenSpec change by name and the
requirement IDs (`FR3`, `NFR-R1`) where they apply.

## How

The design decisions a reviewer needs in order to read the diff — the non-obvious parts
only, not a narration of every file.

## Verification

Which specs cover the change and which gates it passes (`check`, PIT, static analysis).
Name spec classes, not counts.

## Notes for reviewers

Known limitations, deliberate omissions, follow-up work, migration or configuration
impact. Omit if there is nothing.
```

Rules for the body:

- **English**, per the project's documentation-language invariant — even when the
  conversation with the human is in another language.
- Plain, precise language; no jargon. Domain terms (factory, gnome, box, guard) are welcome
  and must match `docs/glossary.md`.
- Markdown only; Mermaid where a diagram genuinely beats prose — never ASCII art, no images.
- Describe what the diff *does*, not what the branch intends to do next.
- No invented facts: if the diff does not show a test, do not claim one exists.

### 4. Deliver

Print the title, then the body inside a fenced block so it can be copied verbatim — use four
backticks, since the body itself contains fenced blocks. Do **not** run `gh pr create` or
push; the human opens the PR.

After the description, outside it, note anything about the branch's hygiene worth fixing
before the PR opens: uncommitted changes (`git status --short`), duplicate or reworded
repeats of the same commit, commits unrelated to the branch's topic. One short list, no
description of what to do about it — the call is the human's.

If the human explicitly asks to save the description, write it to the session scratchpad,
never into the repository.

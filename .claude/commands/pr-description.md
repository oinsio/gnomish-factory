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

The body is for a reviewer who will read the diff anyway. It tells them **why** the change
exists and **where to look first**; it does not retell what the diff shows. Budget: the whole
body fits on one screen — about 150 words, never more than 25 lines. When the branch is one
commit, one paragraph is enough. Cut, do not compress: fewer sentences, not denser ones.

```markdown
## Why

Two or three sentences: the problem, and the OpenSpec change that owns it (name plus the
requirement IDs it settles, e.g. `own-git-transfer-argv` FR3, NFR-R1). Link, do not
summarize — the proposal already carries the reasoning.

## What changed

Three to six bullets, one line each, highest-impact first: a behavior, a new owner of a
mechanism, a removed escape hatch, a changed signature or configuration key. Name a file
only when the reviewer has to open it first.

## Reviewer notes

Only if something is non-obvious: a decision that looks wrong until explained (one sentence
each), a deliberate omission, a follow-up change, a migration or configuration impact.
Drop the section when it would be empty.
```

What stays out of the body:

- **A narration of the diff.** No walk through files, packages, or commits; no "also updated
  the tests". If a bullet could be reconstructed from `--stat`, it is filler.
- **The proposal, retold.** Motivation, alternatives, and design rationale live in
  `proposal.md` / `design.md`; the body cites them by change name and requirement ID.
- **Verification as a list.** One line at most, and only when it says something the build
  does not already guarantee (`check` is green on every PR): a new architecture spec, a gate
  added, a PIT exemption with its reason. Never a roll-call of spec classes or counts.
- **Adjectives and reassurance.** "Comprehensive", "robust", "carefully", "ensures" — delete
  the sentence they appear in and see whether anything was lost.
- **Restating the title**, headings that introduce a single bullet, and any sentence that
  describes the branch's intentions instead of what the diff does.

Rules for the body:

- **English**, per the project's documentation-language invariant — even when the
  conversation with the human is in another language.
- Plain, precise language; no jargon. Domain terms (factory, gnome, box, guard) are welcome
  and must match `docs/glossary.md`.
- Markdown only; a Mermaid diagram only when the change *is* a flow or state machine that
  prose would garble — never ASCII art, no images.
- No invented facts: if the diff does not show a test, do not claim one exists.

Before delivering, reread the body once with the budget in hand and remove every line that
fails the tests above. A body that a reviewer skims in thirty seconds is the target.

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

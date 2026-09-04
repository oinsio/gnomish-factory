---
description: Prepare a PR title and markdown body from the diff between main and the current branch
argument-hint: "[extra emphasis or context for the description]"
---

# PR Description

Compare the current branch against `main` and produce a **pull request title** and a
**markdown body** describing the change. This command writes no code and creates no commits —
it only reads history and prints the description.

## Input

- `$ARGUMENTS` — optional. Extra context or emphasis the human wants in the description
  (a reviewer audience, a risk to call out, a related issue number). Empty is normal.

## Steps

### 1. Establish the comparison base

```bash
git rev-parse --abbrev-ref HEAD
git fetch origin main --quiet || true
git merge-base HEAD origin/main || git merge-base HEAD main
```

Use the **merge base**, not `main`'s tip — otherwise commits that landed on `main` after the
branch started show up as part of this PR.

Refuse to continue if the current branch *is* `main`: there is nothing to describe. Say so and stop.

### 2. Read the actual change

```bash
git log --oneline <base>..HEAD
git diff --stat <base>..HEAD
git diff <base>..HEAD
```

If the diff is large, read it in slices (by directory, then by file) rather than skipping it.
Uncommitted work is **not** part of the PR — describe only what is committed. If `git status`
shows uncommitted changes relevant to the branch, mention that fact once at the end, outside
the description.

### 3. Collect the change artifacts

If the diff touches `openspec/changes/**` (including `archive/`), read the change's
`proposal.md` and `design.md`. They carry the *why* and the requirement IDs — the PR body
should reference them rather than re-deriving intent from the code.

### 4. Write the title

One line, Conventional Commits shape, matching this repo's history
(`feat(take):`, `fix(git):`, `build(deps):`, `test(take):`, `docs:`, `refactor:`, ...):

- ≤ 72 characters, imperative mood, no trailing period
- Scope = the module or capability touched, when there is a single obvious one
- Describes the outcome, not the mechanics ("cap the abort cause before every tracker write",
  not "add a call to capCause")

### 5. Write the body (markdown)

Use this structure; drop any section that would be empty rather than filling it with filler:

```markdown
## What

One paragraph: what this branch changes, in plain language.

## Why

The problem or requirement that drove it. Reference the OpenSpec change by name and the
requirement IDs (`FR3`, `NFR-R1`) where they apply.

## How

The approach — the design decision a reviewer needs in order to read the diff. Keep it to the
non-obvious parts; do not narrate every file.

## Verification

How the change is verified: which specs cover it, which gates it passes (`check`, PIT,
static analysis). Name spec classes, not counts.

## Notes for reviewers

Anything a reviewer should know before reading: known limitations, deliberate omissions,
follow-up work, migration or configuration impact. Omit the section if there is nothing.
```

Rules for the body:

- **English**, per the project's documentation-language invariant — even when the conversation
  with the human is in Russian.
- Plain, precise language; no jargon. Domain terms (factory, gnome, box, guard) are welcome
  and must match `docs/glossary.md`.
- Markdown only. Use Mermaid where a diagram genuinely helps a reviewer (state machines,
  multi-step flows) and prose alone is clumsy — never ASCII art, never images.
- Describe what the diff *does*, not what the branch *intends to do next*.
- No invented facts: if the diff does not show a test, do not claim one exists.

### 6. Deliver

Print the title and the body in the chat, with the body inside a fenced block so it can be
copied verbatim. Do **not** run `gh pr create` or push anything — the human opens the PR.

If the human explicitly asks to save it, write it to the session scratchpad, not into the
repository.

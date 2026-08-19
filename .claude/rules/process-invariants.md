# Rule: development process invariants

These rules apply to ALL work in the project, regardless of artifact type.

## Archived changes are immutable

Never edit files in `openspec/changes/archive/`. If a change needs correction, create a new change with `Supersedes: <old-change-name>` in the proposal.

## File size limit

Target 100–120 lines per file; 200 is a hard cap, used only when splitting would hurt clarity. Long files degrade AI context quality. One file = one thing.

## Module boundaries

Modules expose an explicit public API; never import from internal files of a sibling module. (Concrete mechanism depends on the tech stack — to be refined once the stack is chosen.)

## Change naming

Use `kebab-case-descriptive` for change names: `add-tracker-port`, `fix-claim-race`, `refactor-stage-engine`. Never use generic names like `update`, `changes`, `wip`.

## Context hygiene

Clean the AI agent context before running `/opsx:apply`, especially for large changes. Stale context leads to incoherent code.

## Artifact layering

Lower-layer artifacts always reference IDs from upper layers:

1. PRD (`proposal.md`) — what and why
2. Domain spec + behavior spec — entities, rules, use cases
3. Contract spec (ports) + ADR — how components connect
4. Code + tests — implementation

If an artifact has no upward reference — it is either unnecessary or missing a link.

## Change scope

One change = one initiative, completable in 1–4 weeks. If a change grows beyond that, split it into smaller changes.

## Git commits

The AI agent NEVER creates git commits in this project — no `git commit`, `git commit --amend`, or any other history-writing command. Instead, after completing a unit of work, the agent recommends a commit message based on the diff since the last commit (`git status` / `git diff HEAD`); the human reviews and commits. The recommendation should summarize what changed and reference the OpenSpec change / requirement IDs where applicable.

Keep the recommended message short: a Conventional Commits subject line (≤ ~72 chars) plus, only when the "why" is not obvious from the subject, a brief body of 1–3 lines. Don't restate the diff, re-explain the rationale at length, or pad with metrics — the diff and the change artifacts already carry that. Reference the OpenSpec change / requirement IDs on a trailer line.

## Documentation language

All project documentation, specs, rules, code comments, and commit messages are written in English.

## No jargon; domain terminology is welcome

Documentation and discussions use plain, precise language — no slang, no
insider jargon, no cutesy shorthand that a newcomer (human or AI) would have to
decode. Established **domain terminology** is not jargon and is actively
encouraged: project terms (**factory**, **gnome**, **box**, **guard**, ...) and
industry terms (egress, allowlist, fail-closed) are precise names for real
things. The distinction: a domain term has a written definition the reader can
follow; jargon relies on tribal knowledge.

The project's terms and abbreviations are defined in `docs/glossary.md` — the
normative ubiquitous-language dictionary, grouped by bounded context:

- A change that introduces a new domain term adds its glossary entry in the
  same change; a change that shifts a term's meaning updates the entry.
- Domain code, ports, and fields are named by glossary terms; renaming the
  concept means renaming the code.
- Banned synonyms listed in glossary entries (*Never:* ...) must not appear
  anywhere — code, docs, or discussions.
- A term used only within one document may instead be defined where it is
  introduced; promote it to the glossary once a second document needs it.

## No references to temporary files

`openspec/**` artifacts (proposals, designs, specs, tasks) may only reference project files that live under `docs/` — never scratch/explore locations, which are not part of the durable record and may be deleted at any time. When an idea needs to be cited from an ephemeral note: either inline the relevant meaning directly into the OpenSpec artifact, or propose creating a durable `docs/` file (an ADR, an operator guide, a scope note) and reference that instead.

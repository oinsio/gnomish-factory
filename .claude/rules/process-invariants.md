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

## Documentation language

All project documentation, specs, rules, code comments, and commit messages are written in English.

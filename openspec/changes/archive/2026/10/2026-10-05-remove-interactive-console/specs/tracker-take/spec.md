# Spec Delta: tracker-take

## MODIFIED Requirements

### Requirement: take subcommand surface
`gnomish take` SHALL be a separate subcommand, always in git mode, with three
forms: `take <ref>` (explicit mode), `take <ref> <ref> ...` (batch mode, two
or more refs), and bare `take` (auto mode). Supported flags: `--dir`,
`--base` (single explicit-mode start only), `--discard-work` (salvage-only:
reset the working copy to the last recorded round and replay the interrupted
round — see git-task-persistence "Salvage of interrupted rounds"), and the
headless takeover flag. `--discard-work` SHALL play no role in divergence:
divergence between the local branch and origin resolves automatically under
the live claim (see "Divergence resolves automatically under the lease"),
and no operator flag selects that resolution. `take` SHALL have no `--mode`, no ad-hoc source flags
(`--task`, `--task-file`, `--task-id`, `--resume`), no `--from-stage`, and no
`--interactive` in any form — the token is a usage error like any unknown flag;
the bare form SHALL reject start modifiers (`--base`) and the headless
takeover flag (which authorizes an explicit `take <ref>` takeover only); the
batch form SHALL reject `--base`. The `gnomish run` flag
matrix SHALL remain unchanged. Short refs (`42`, `#42`) expand via the
configured binding; a full canonical id naming a foreign repo is an error
(subject to the adapter's rename tolerance).
<!-- implements FR1 of remove-interactive-console -->

#### Scenario: Flag validation
- **WHEN** `take` is invoked with `--mode`, `--task`, `--resume`, `--interactive`, or bare
  `take` with `--base` or `--takeover`
- **THEN** each invocation fails with a validation error before touching the
  tracker

#### Scenario: Batch rejects interactivity
- **WHEN** `take 42 43 --interactive` is invoked
- **THEN** the invocation fails with a validation error before touching the
  tracker, as for any unknown flag

#### Scenario: Foreign canonical id is refused
- **WHEN** `take github:other/repo#7` names a repo that is neither the configured
  binding nor (via the adapter's rename tolerance) a predecessor of it
- **THEN** the run refuses (exit 15) before fetching the task, naming both repos

#### Scenario: Short ref expansion
- **WHEN** the operator runs `take 42` with a configured GitHub binding
- **THEN** the run targets the canonical id built from the binding and issue 42

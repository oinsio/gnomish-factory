# pipeline-config — delta for add-base-ref-resolution

## ADDED Requirements

### Requirement: Optional base section with pattern menu
`.gnomish/config.yaml` SHALL support an optional `base:` section: `type` (a
discriminator; `patterns` is the only supported value in this version, and it
is the default when absent), `default` (optional ref name), and `menu` (a list
of entries, each `pattern` plus optional `role` of `development` | `release`,
defaulting to `development`). Patterns SHALL compile at load. Located
`ConfigError`s under the existing aggregation contract SHALL cover: an
unknown `type` value, an unknown key anywhere in the section, an invalid
pattern, an unknown `role`, and a `default` that matches no menu pattern when
a menu is declared. The section holds no tracker-specific selection rule: how
a task names its base is the tracker adapter's own configuration
(`tracker.<type>.designators`, owned and validated by the adapter per
tracker-port). An absent section SHALL be valid: an empty menu, no configured
default, and all previously specified loading behavior unchanged. The loader
parses and validates only — resolution semantics belong to
base-ref-resolution.
<!-- implements FR1 of add-base-ref-resolution -->
<!-- implements UX1 of add-base-ref-resolution -->

#### Scenario: Settled shape loads
- **WHEN** the section declares `type: patterns`, `default: main`, and a menu
  of `main` and `release/*` (role `release`)
- **THEN** loading succeeds and the typed definition exposes the compiled
  menu, the default, and the roles

#### Scenario: No base section
- **WHEN** a `.gnomish/` without a `base:` section is loaded
- **THEN** loading succeeds exactly as before and the definition reports an
  empty menu with no default

#### Scenario: Selection rule without a menu is a load error
- **WHEN** the tracker adapter reports that it extracts designator kind
  `base` (for GitHub, a `tracker.github.designators.base` rule) and the
  `base:` section is absent or declares no menu entry
- **THEN** loading fails with a located error naming the rule's location and
  `base.menu`, stating that the rule can only ever reject a selection — add a
  menu or remove the rule

#### Scenario: Default outside the menu is a load error
- **WHEN** the section declares `default: develop` and a menu containing
  only `release/*`
- **THEN** loading fails with a located error naming `base.default` and the
  menu it failed to match

#### Scenario: Unknown discriminator is a load error
- **WHEN** the section declares `type: script`
- **THEN** loading fails with a located error naming the unknown type — the
  discriminator exists so future selection mechanisms arrive as new values,
  not as schema breaks

#### Scenario: Unknown keys are not ignored
- **WHEN** the section contains a misspelled key such as `defualt:`
- **THEN** loading fails with a located error naming the unknown key

### Requirement: Definition validated at startup, bound per task from the base
`serve` and `take` SHALL load and validate the full definition from the
refreshed repository default branch at startup — fail-fast for the common
base, the source of the trusted tier, and the definition `board` and
`dashboard` display. That load SHALL NOT be authoritative for a task: after
base resolution the task tier SHALL be loaded from the task's law commit and
frozen from it, per task. A definition that fails to load from a base SHALL
park the task with a configuration report naming the base ref, the law
commit, and the located errors — no stage attempt burned, no claim released
for retry, because the failure is deterministic.
<!-- implements FR13, UX5 of add-base-ref-resolution -->

#### Scenario: Startup still fails fast on the default branch
- **WHEN** the default branch's `.gnomish/` carries a malformed stage manifest
- **THEN** `serve` exits at startup with the located errors, before any claim

#### Scenario: A broken base parks only its task
- **WHEN** the default branch loads cleanly and a task resolves to
  `release/1.18`, whose `.gnomish/` fails validation
- **THEN** that task parks with the located errors and the base ref in its
  report, the claim is not released into a retry loop, and other slots keep
  working

## MODIFIED Requirements

### Requirement: Pipeline law binds per invocation
Pipeline law — `.gnomish/` stage manifests, stage instructions, and judge
acceptance criteria — SHALL be bound at invocation start and frozen for the
invocation's lifetime, including the in-process outcome loop. The law source
SHALL be read through one law-source abstraction with exactly two
realizations: **git objects at one commit — the law commit —** in every path
that resolved a ref (tracker-driven modes, manual `run` with `--base`), and
the **working tree** in the git-less in-place mode and in manual `run`
without `--base`. Where a ref was resolved, the factory clone's working tree,
index, and `HEAD` SHALL play no part in the law. Configuration has two tiers:
the trusted tier (`tracker:`, `base:`, and future selector sections) binds
from the refreshed repository default branch; the task tier (stages,
instructions, criteria, the remainder of `config.yaml`) binds from the base's
law commit — the pinned SHA on a fresh start, the current tip of the pinned
ref name on resume (a tag or SHA base makes these equal). The external-check
pin guard SHALL compare against the law commit. A symlink entry in a git tree
SHALL be treated as an unreadable law file. Control files and judge
acceptance criteria SHALL be read from the law source, never from the
gnome-writable working copy at use time. Copies of law files in the gnome's
working copy are project content: editable, but never law for the current
task. A contract test SHALL enforce the source in git modes — including a
base that differs from the clone's checked-out ref and the default-branch
source of the trusted tier.
<!-- implements FR19, NFR-S2 of add-sandbox-core -->
<!-- implements FR2, FR11, FR12, NFR-S1, M5 of add-base-ref-resolution -->

#### Scenario: Gnome edits to the law have no effect
- **WHEN** the gnome branch modifies `.gnomish/` manifests, stage instructions, or judge acceptance criteria
- **THEN** the running task continues under the law bound at invocation start, and the edits reach production law only via a human merge — for later tasks

#### Scenario: Criteria are not read lazily from the working copy
- **WHEN** a judge vote runs after the gnome edited the acceptance-criteria file in its working copy
- **THEN** the vote uses the criteria from the law source, and the working-copy edit plays no part in it

#### Scenario: Resume picks up human-fixed criteria
- **WHEN** a human fixes acceptance criteria on the base branch and returns an escalated task to work
- **THEN** the resuming invocation binds the corrected law from the tip of the pinned ref name, and the gnome branch content plays no part in it

#### Scenario: Base differs from the clone's checked-out ref
- **WHEN** the factory clone has `main` checked out and a task resolves to
  `release/1.18`, whose stage instructions differ from `main`'s
- **THEN** the frozen law and the pin guard use `release/1.18`'s content at
  the pinned SHA, and nothing from the checked-out `main` tree reaches the
  task

#### Scenario: Uncommitted clone edits are not law in autonomous modes
- **WHEN** an operator edits a stage instruction in the factory clone's
  working tree without committing and serve claims a task
- **THEN** the task binds the committed instruction at its law commit, and
  the uncommitted edit plays no part

#### Scenario: Manual run without a base keeps working-tree law
- **WHEN** `gnomish run` starts without `--base` in a clone with uncommitted
  `.gnomish/` edits
- **THEN** the edited files are the law, exactly as before this change

#### Scenario: Resume after the pinned ref disappeared
- **WHEN** a task pinned to `release/1.18` is resumed after that branch was
  deleted from the remote
- **THEN** the task parks with a report naming the pinned ref and SHA, and
  no law is bound from the pinned SHA silently

#### Scenario: The chosen base's own `base:` block plays no part in choosing it
- **WHEN** a task resolves to base `release/1.18`, whose `.gnomish/config.yaml`
  carries a `base:` block differing from the default branch's
- **THEN** resolution used the default-branch `base:` block, and the rest of
  the law — including the remainder of `release/1.18`'s `config.yaml` —
  binds from `release/1.18`'s law commit

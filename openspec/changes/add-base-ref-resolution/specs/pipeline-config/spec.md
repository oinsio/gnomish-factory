# pipeline-config — delta for add-base-ref-resolution

## ADDED Requirements

### Requirement: Optional base section with pattern menu
`.gnomish/config.yaml` SHALL support an optional `base:` section: `type` (a
discriminator; `patterns` is the only supported value in this version, and it
is the default when absent), `default` (optional ref name), `menu` (a list of
entries, each `pattern` plus optional `role` of `development` | `release`,
defaulting to `development`), and `select.label` (a regular expression with
exactly one capture group mapping a task label to a ref name). Patterns and
the `select.label` regex SHALL compile at load. Located `ConfigError`s under
the existing aggregation contract SHALL cover: an unknown `type` value, an
unknown key anywhere in the section, an invalid pattern or regex, a
`select.label` without exactly one capture group, an unknown `role`, and a
`default` that matches no menu pattern when a menu is declared. An absent
section SHALL be valid: an empty menu, no configured default, no selection
rule, and all previously specified loading behavior unchanged. The loader
parses and validates only — resolution semantics belong to
base-ref-resolution.
<!-- implements FR1 of add-base-ref-resolution -->
<!-- implements UX1 of add-base-ref-resolution -->

#### Scenario: Settled shape loads
- **WHEN** the section declares `type: patterns`, `default: main`, a menu of
  `main` and `release/*` (role `release`), and `select.label: "base:(.+)"`
- **THEN** loading succeeds and the typed definition exposes the compiled
  menu, the default, the roles, and the selection rule

#### Scenario: No base section
- **WHEN** a `.gnomish/` without a `base:` section is loaded
- **THEN** loading succeeds exactly as before and the definition reports an
  empty menu with no default and no selection rule

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

## MODIFIED Requirements

### Requirement: Pipeline law binds per invocation
Pipeline law — `.gnomish/` stage manifests, stage instructions, and judge
acceptance criteria — SHALL be bound at invocation start and frozen for the
invocation's lifetime, including the in-process outcome loop. The law source
SHALL be the factory-owned clone of the base branch in tracker-driven and git
modes, and the workspace snapshot at startup in the git-less in-place mode —
with one carve-out: the `base:` section of `config.yaml` SHALL bind from the
factory-owned clone of the repository default branch, refreshed by fetch,
before the base is chosen. That section is the input that chooses the base
and therefore cannot come from the ref it selects; its resolution semantics
are owned by the `base-ref-resolution` capability. All other content of
`config.yaml`, and every other law file, continues to bind from the chosen
base. Control files and judge acceptance criteria SHALL be read from the law
source, never from the gnome-writable working copy at use time. Copies of law
files in the gnome's working copy are project content: editable, but never
law for the current task. A contract test SHALL enforce the source in git
modes — including the default-branch source of the `base:` section.
<!-- implements FR19, NFR-S2 of add-sandbox-core -->
<!-- implements FR2, NFR-S1 of add-base-ref-resolution -->

#### Scenario: Gnome edits to the law have no effect
- **WHEN** the gnome branch modifies `.gnomish/` manifests, stage instructions, or judge acceptance criteria
- **THEN** the running task continues under the law bound at invocation start, and the edits reach production law only via a human merge — for later tasks

#### Scenario: Criteria are not read lazily from the working copy
- **WHEN** a judge vote runs after the gnome edited the acceptance-criteria file in its working copy
- **THEN** the vote uses the criteria from the law source, and the working-copy edit plays no part in it

#### Scenario: Resume picks up human-fixed criteria
- **WHEN** a human fixes acceptance criteria on the base branch and returns an escalated task to work
- **THEN** the resuming invocation binds the corrected law from the base branch, and the gnome branch content plays no part in it

#### Scenario: The chosen base's own `base:` block plays no part in choosing it
- **WHEN** a task resolves to base `release/1.18`, whose `.gnomish/config.yaml`
  carries a `base:` block differing from the default branch's
- **THEN** resolution used the default-branch `base:` block, and the rest of
  the law — including the remainder of `release/1.18`'s `config.yaml` —
  binds from the chosen base as before

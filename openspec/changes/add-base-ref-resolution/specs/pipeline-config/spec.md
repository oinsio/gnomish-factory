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

# github-tracker — delta for add-base-ref-resolution

## ADDED Requirements

### Requirement: Designator candidates are extracted from issue labels by configured rule
For every kind declared under `tracker.github.designators`, the adapter SHALL
apply the kind's regular expression to each label name the issue carries,
take the single capture group of every full match as a candidate value, and
ignore labels that do not match; it SHALL then classify the candidates only
through the port's shared classification function (tracker-port) and carry
the result in the `fetchTask` facts. The adapter SHALL report the declared
kinds through the adapter factory seam and nothing else: a kind with no rule
is never extracted and never reported. No resolution, defaulting, or
conflict-picking happens in the adapter.
<!-- implements FR3 of add-base-ref-resolution -->

#### Scenario: One matching label yields one candidate
- **WHEN** `designators.base` is `"base:(.+)"` and the issue carries the
  labels `base:release/1.18` and `gnomish:ready`
- **THEN** the `base` designator is the single value `release/1.18` and the
  state label plays no part

#### Scenario: Partial matches do not count
- **WHEN** the rule is `"base:(.+)"` and a label is `my-base:foo`
- **THEN** the label yields no candidate

#### Scenario: Two captures with different values are a conflict
- **WHEN** the issue carries `base:release/1.18` and `base:release/1.19`
- **THEN** the `base` designator is a conflict listing both values, in label
  order as the API returned them

#### Scenario: Undeclared kind is neither extracted nor reported
- **WHEN** the subsection declares no `designators` map
- **THEN** the factory reports no configured kinds and every designator the
  facts carry is absent

## MODIFIED Requirements

### Requirement: tracker.github config subsection owned by the adapter
The adapter SHALL declare and validate its `tracker.github` subsection:
`api-url` (mandatory, no code default), `repo` (`owner/name`),
`labels.{ready,working,needs-human,delivered}` as `{name, color}` objects with
hex color validation, and an optional `designators` map of designator kind →
regular expression, where every value SHALL compile and SHALL contain exactly
one capture group and every key SHALL be a non-blank kind name (kinds are
open; `base` and `type` are the ones the factory consumes). In place of the
inline `api-url`, the subsection MAY
reference a named operator-config connection profile as `connection: <name>`,
resolving the endpoint and the token's credential name from the profile (the
vendor-connection-profile capability). A referencing subsection MAY still
declare inline keys the referenced profile does not define — they overlay the
profile — but declaring an inline key the referenced profile also defines is
ambiguous and SHALL be a load error, one per overlapping key. Validation SHALL aggregate errors and fail fast at load,
consistent with pipeline-config error reporting. The token SHALL be resolved
through the `SecretsProvider` port by name — never from yaml and never read
from process env directly; the env/file adapter backs the name with
`GNOMISH_GITHUB_TOKEN`. The token SHALL never reach a task environment or
prompts; the adapter SHALL declare its credential name — through the SPI's
connection-aware credential declaration, resolving the profile-supplied name
when a `connection:` profile is referenced — so the variable can
never be admitted into a child-environment allowlist.
<!-- implements FR17, NFR-S1 of add-tracker-port -->
<!-- implements FR18, NFR-S1 of add-sandbox-core -->
<!-- implements FR16 of add-plugin-architecture -->
<!-- implements FR17 of add-plugin-architecture -->
<!-- implements UX3 of add-plugin-architecture -->
<!-- implements FR3 of add-base-ref-resolution -->

#### Scenario: Missing api-url is a load error
- **WHEN** `tracker.github` lacks `api-url` and declares no `connection:`
  profile reference
- **THEN** loading fails with a located error; no built-in default is applied

#### Scenario: Subsection resolves its connection from a named profile
- **WHEN** `tracker.github` declares `connection: <name>` and the operator
  config defines that profile
- **THEN** the adapter resolves the endpoint and the token's credential name
  from the profile; inline keys the profile does not define overlay it

#### Scenario: Overlapping inline key alongside a profile reference is a load error
- **WHEN** `tracker.github` declares `connection: <name>` and, inline, a key
  the referenced profile also defines
- **THEN** loading fails with a located error per overlapping key, naming both
  the reference and the ambiguous inline key

#### Scenario: Designator rule without exactly one capture group is a load error
- **WHEN** `designators.base` is `"base:.+"` (no group) or `"(base):(.+)"`
  (two groups)
- **THEN** loading fails with a located error naming
  `tracker.github.designators.base`, aggregated with any other config errors

#### Scenario: Token stays out of the gnome
- **WHEN** a stage executes via the agent CLI while a tracker task is being worked
- **THEN** the task environment's allowlisted env contains no tracker credential

#### Scenario: Backend switch requires no adapter change
- **WHEN** the operator switches the configured `SecretsProvider` adapter
- **THEN** the tracker adapter resolves the same secret name with no code change

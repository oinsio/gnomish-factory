# plugin-api-contract — delta for split-logtext-leaves

Both requirements are written over the main spec; no active change modifies
them.

## MODIFIED Requirements

### Requirement: Thin plugin-api contract module
A `gnomish-plugin-api` module SHALL contain exactly the third-party contract
surface — port interfaces, the tracker SPI factory (bearing `type()`,
`subsectionValidator()`, and the connection-aware credential declaration), the
check SPI factory `CheckClientFactory` (with `CheckParamsValidator`, the
`ExternalCheckPinContributor` hook, its subsection validator, and both halves
of its credential declaration — from the configured connection and, for a
provider whose targets are per-check, from a check's own params),
`SecretsProvider`, SPI validator interfaces, the `AttemptCommitWorkspace`
workspace-capability interface, and the `FindingsSanitizer` contract utility —
and nothing from `application` or `bootstrap` internals. Domain value and
config types referenced by these ports stay in `domain` and are exposed through
a transitive `api` dependency; the untrusted-text primitives the findings
sanitizer delegates to stay in the JDK-only `untrustedtext` leaf and reach a
third party through the same transitive edge, so the single declared
dependency contract is unchanged.
<!-- implements FR4 of split-into-modules -->
<!-- implements FR5, FR12, FR15, FR17 of add-plugin-architecture -->
<!-- implements FR1, FR2 of close-plugin-api-compilability-gap -->
<!-- implements FR3, FR8 of split-logtext-leaves -->

#### Scenario: api artifact excludes application and bootstrap internals
- **WHEN** dependency-analysis inspects the `gnomish-plugin-api` artifact
- **THEN** it contains zero imports from `application` or `bootstrap` internals
- **AND** it exposes the port interfaces, the tracker and check SPI factories,
  `SecretsProvider`, validator SPIs, `AttemptCommitWorkspace`, and
  `FindingsSanitizer`

#### Scenario: A third party compiles against a single declared dependency
- **WHEN** a third-party adapter is compiled with `gnomish-plugin-api` as its
  only declared dependency (the `domain` and `untrustedtext` types the ports
  and the sanitizer reference arrive transitively)
- **THEN** it can implement any exposed port and its SPI factory — tracker or
  check — without needing `application` or `bootstrap`
- **AND** an external-check implementation can read the attempt-commit sha of
  the round under verification through `AttemptCommitWorkspace` and sanitize
  its findings through `FindingsSanitizer` using only that dependency

#### Scenario: The first-party vendor bundle satisfies the same constraint
- **WHEN** the production dependency declarations of the github vendor bundle
  (`api` / `implementation` — the configurations the layering gate walks) are
  inspected
- **THEN** no dependency on `application` (or `bootstrap`) appears — the
  bundle compiles against the published contract exactly as a third-party
  bundle would

### Requirement: Findings sanitization available to every plugin
The api SHALL provide the findings-sanitization utility (control-character /
ANSI strip and tail cap) so any check plugin can apply the same
pre-publication hygiene as first-party adapters; first-party call sites SHALL
keep enforcing it after the relocation. The utility is a facade over the
untrusted-text leaf's primitives: it holds no character-class table of its
own, so it cannot drift from the log-line sanitizer.
<!-- implements FR2, NFR-S1 of close-plugin-api-compilability-gap -->
<!-- implements FR3 of split-logtext-leaves -->

#### Scenario: A hostile CI log is neutralized with api-only means
- **WHEN** a check implementation depending only on `gnomish-plugin-api`
  processes a multi-gigabyte log containing ANSI escapes and control characters
- **THEN** it can strip and tail-cap the text through the api's sanitizer
  before placing it in a finding

#### Scenario: First-party sanitization is unchanged by the move
- **WHEN** the existing first-party log-sanitization specs run after the
  relocation
- **THEN** they pass unchanged in substance (imports aside): strip and cap
  behavior is identical

#### Scenario: The facade holds no table of its own
- **WHEN** the findings sanitizer's source is scanned for escape-character or
  control-class literals
- **THEN** none is found — every primitive resolves to the untrusted-text
  leaf

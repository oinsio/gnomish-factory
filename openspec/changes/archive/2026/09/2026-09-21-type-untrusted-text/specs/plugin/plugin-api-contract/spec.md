# plugin-api-contract — delta for type-untrusted-text

Both requirements below are written over the main spec as synced by
`split-logtext-leaves` (archived 2026-09-16): the first repeats that synced
text — the `untrustedtext` self-declaration and the four-jar published graph
— and adds only the typed title/body sentence and its scenario line; the
second adds the typed-field break rule and its scenario.

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
sanitizer delegates to stay in the JDK-only `untrustedtext` leaf, which this
module declares itself and resolves alongside its own artifact, so the single
declared dependency contract is unchanged. The published project jar graph —
and with it the committed compatibility baseline — is `gnomish-plugin-api`,
`domain`, the `untrustedtext` leaf this module declares, and every JDK-only
leaf `domain` reaches (today `operatorevent`);
a change to that set is re-baselined with a version bump even when no
signature moves. The task snapshot's title and body SHALL be carried as
untrusted text from that same leaf, so an adapter author mints them and the
contract itself says which fields the tracker controls.
<!-- implements FR4 of split-into-modules -->
<!-- implements FR5, FR12, FR15, FR17 of add-plugin-architecture -->
<!-- implements FR1, FR2 of close-plugin-api-compilability-gap -->
<!-- implements FR3, FR8 of split-logtext-leaves -->
<!-- implements FR4 of type-untrusted-text -->

#### Scenario: api artifact excludes application and bootstrap internals
- **WHEN** dependency-analysis inspects the `gnomish-plugin-api` artifact
- **THEN** it contains zero imports from `application` or `bootstrap` internals
- **AND** it exposes the port interfaces, the tracker and check SPI factories,
  `SecretsProvider`, validator SPIs, `AttemptCommitWorkspace`, and
  `FindingsSanitizer`

#### Scenario: A third party compiles against a single declared dependency
- **WHEN** a third-party adapter is compiled with `gnomish-plugin-api` as its
  only declared dependency (the `domain` and `untrustedtext` types the ports
  and the sanitizer reference arrive transitively, as does the `operatorevent`
  jar the domain's own emitters use)
- **THEN** it can implement any exposed port and its SPI factory — tracker or
  check — without needing `application` or `bootstrap`
- **AND** an external-check implementation can read the attempt-commit sha of
  the round under verification through `AttemptCommitWorkspace` and sanitize
  its findings through `FindingsSanitizer` using only that dependency
- **AND** a tracker implementation mints the snapshot's title and body as
  tracker-provenance untrusted text using only that dependency

#### Scenario: The first-party vendor bundle satisfies the same constraint
- **WHEN** the production dependency declarations of the github vendor bundle
  (`api` / `implementation` — the configurations the layering gate walks) are
  inspected
- **THEN** no dependency on `application` (or `bootstrap`) appears — the
  bundle compiles against the published contract exactly as a third-party
  bundle would

### Requirement: Surface growth is additive and re-baselined
The api additions SHALL be backward-compatible for every pre-existing
`gnomish-plugin-api` type, and the compatibility gate SHALL pass against a
deliberately regenerated baseline that includes the new surface. A pre-1.0
breaking move — a re-exposed domain type or the snapshot changing a field's
type — SHALL be a MINOR bump with the baseline regenerated in the same
commit and the break named in the module's build script, so a reviewer sees
the break and the bump together.
<!-- implements FR5 of close-plugin-api-compilability-gap -->
<!-- implements FR4 of type-untrusted-text -->

#### Scenario: Compatibility gate passes on the grown surface
- **WHEN** the api compatibility check runs after the baseline regeneration
- **THEN** it passes, and the diff against the previous baseline shows
  additions only for pre-existing types

#### Scenario: A typed-field break is bumped and re-baselined together
- **WHEN** the snapshot's title and body and the abort marker's cause change
  from plain strings to the untrusted-text carrier
- **THEN** the api version takes a MINOR bump, the baseline is regenerated in
  the same commit, and the build script names the break

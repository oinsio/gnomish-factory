## ADDED Requirements

### Requirement: Operator config supports named vendor connection profiles
Operator config SHALL support named per-vendor connection profiles
(`factory.connections.<name>`) carrying the vendor endpoint and the credential
name(s) resolved through `SecretsProvider`. A port subsection MAY reference a
profile as `connection: <name>` instead of inlining endpoint and credential-name
keys; two ports referencing the same profile share one definition, so one
vendor serving two ports (e.g. github tracker + github checks) keeps a single
connection/credentials block. Provider selection SHALL remain independent per
port — a profile shares connection data, never the provider choice.
<!-- implements FR16 of add-plugin-architecture -->
<!-- implements UX3 of add-plugin-architecture -->

#### Scenario: Two ports share one named profile
- **WHEN** the github tracker subsection and the github checks subsection both
  declare `connection: github-main`
- **THEN** both ports resolve the endpoint and credential name from the one
  `factory.connections.github-main` profile, with no duplicated keys to drift

#### Scenario: Mixed-vendor configuration is unaffected
- **WHEN** the tracker references one vendor's profile and a check declares a
  different provider with its own connection
- **THEN** each port resolves its own connection independently; sharing is
  opt-in per subsection, never implied by the vendor

### Requirement: Profile-resolved credential names join the scrub set
A credential name a port resolves from a connection profile SHALL enter the
child-environment scrub and never-allowlist machinery exactly as an
adapter-declared name does: the SPI credential declaration is
connection-aware, so the declared set follows the profile. A profile SHALL
carry the credential name only, never a credential value.
<!-- implements FR16 of add-plugin-architecture -->
<!-- implements FR17 of add-plugin-architecture -->

#### Scenario: Renamed credential in a profile is still scrubbed
- **WHEN** a profile names a non-default credential for a vendor and a stage
  runs via the agent CLI
- **THEN** the profile-resolved name is scrubbed from the child environment and
  cannot be admitted into the passthrough allowlist, exactly as the vendor's
  default name would be

### Requirement: A connection profile reference is validated at load
Core SHALL grade the `connection:` reference itself, and only it: a blank or
non-string reference and a name no `factory.connections` profile defines SHALL
each be a located config error. A referencing subsection MAY still declare
inline keys the profile does not define — they overlay the profile (design D8)
— but each inline key the referenced profile also defines SHALL be a located
config error of its own, naming that key. A subsection declaring *neither*
form is not a core-level error: what it is missing only the provider knows, so
it stays that provider's own missing-key validation error.
<!-- implements FR16 of add-plugin-architecture -->

#### Scenario: Undefined profile name is a located error
- **WHEN** a port subsection declares `connection: <name>` and no
  `factory.connections.<name>` profile exists
- **THEN** loading fails with a located error naming the missing profile and
  listing the profiles that are defined

#### Scenario: An inline key the profile also defines is a located error
- **WHEN** a subsection declares a `connection:` reference and, inline, a key
  the referenced profile also carries
- **THEN** validation reports a located error at that key, identifying the
  subsection, the profile and the conflict

#### Scenario: A non-overlapping inline key overlays the profile
- **WHEN** a subsection declares a `connection:` reference plus an inline key
  the referenced profile does not define
- **THEN** loading succeeds and the provider is handed the profile's keys with
  the subsection's own overlaid, the reference key itself dropped

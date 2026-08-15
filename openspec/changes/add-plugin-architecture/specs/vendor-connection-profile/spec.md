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

### Requirement: A connection profile reference is validated at load
A subsection referencing an undefined profile name SHALL be a located config
error naming the missing profile. A subsection SHALL declare exactly one of the
two forms — inline connection keys or a `connection: <name>` reference;
declaring both or neither SHALL be a located config error.
<!-- implements FR16 of add-plugin-architecture -->

#### Scenario: Undefined profile name is a located error
- **WHEN** a port subsection declares `connection: <name>` and no
  `factory.connections.<name>` profile exists
- **THEN** loading fails with a located error naming the missing profile

#### Scenario: Ambiguous connection declaration is a located error
- **WHEN** a subsection declares both a `connection:` reference and inline
  endpoint keys, or neither
- **THEN** validation reports a located error identifying the subsection and
  the conflict

# vendor-connection-profile

## Purpose

Let operator config define named per-vendor connection profiles (`factory.connections.<name>`) carrying a vendor endpoint and the credential name(s) resolved through `SecretsProvider`, so two ports served by one vendor keep a single connection/credentials block via `connection: <name>` instead of duplicated inline keys. Provider selection stays independent per port, profile-resolved credential names join the child-environment scrub and never-allowlist sets, and core grades the reference itself: an undefined profile name and an inline key the profile also defines are each located config errors, while a non-overlapping inline key overlays the profile.

## Requirements

### Requirement: Operator config supports named vendor connection profiles
Operator config SHALL support named per-vendor connection profiles
(`factory.connections.<name>`) carrying the vendor endpoint and the credential
name(s) resolved through `SecretsProvider`. A port subsection MAY reference a
profile as `connection: <name>` instead of inlining endpoint and credential-name
keys.
**Implements**: FR16 of add-plugin-architecture; UX3 of add-plugin-architecture

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

#### Scenario: A profile never selects the provider
- **WHEN** two port subsections reference the same connection profile
- **THEN** each port uses the provider its own subsection selects, and the
  profile contributes connection data only

### Requirement: Profile-resolved credential names join the scrub set
A credential name a port resolves from a connection profile SHALL enter the
child-environment scrub and never-allowlist machinery exactly as an
adapter-declared name does: the SPI credential declaration is
connection-aware, so the declared set follows the profile. A profile SHALL
carry the credential name only, never a credential value.
**Implements**: FR16 of add-plugin-architecture; FR17 of add-plugin-architecture

#### Scenario: Renamed credential in a profile is still scrubbed
- **WHEN** a profile names a non-default credential for a vendor and a stage
  runs via the agent CLI
- **THEN** the profile-resolved name is scrubbed from the child environment and
  cannot be admitted into the passthrough allowlist, exactly as the vendor's
  default name would be

### Requirement: A connection profile reference is validated at load
Core SHALL grade the `connection:` reference itself, and only it: a blank or
non-string reference and a name no `factory.connections` profile defines SHALL
each be a located config error.
**Implements**: FR16 of add-plugin-architecture
**Notes**: The overlay of inline keys the profile does not define is design D8 of add-plugin-architecture.

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

#### Scenario: A subsection with neither form is left to its provider
- **WHEN** a port subsection declares neither a `connection:` reference nor
  the provider's inline connection keys
- **THEN** core reports no error for it, and the provider's own validation
  reports the key it is missing

### Requirement: A profile may grant egress hosts
A connection profile, and a `factory.check.<name>` subsection that inlines its connection, MAY
carry an `egress` list in the one egress entry grammar: the hosts the connection may be redirected
to or may call beside its endpoint. The endpoint's host and the `egress` list SHALL both be grants
of the operator medium; an inline `egress` key beside a `connection:` reference whose profile also
defines one SHALL be a located config error like any overlapping key.
**Implements**: FR17 of migrate-verify-to-executor-contract
**Notes**: Replaces the `redirect-hosts` key of the 2026-11-08 amendment (design D16 as amended 2026-11-09).

#### Scenario: A profile grants the enterprise log store
- **WHEN** `factory.connections.ghe` names the enterprise endpoint and `egress: [store.ghe.example]`, and the `github-actions` subsection declares `connection: ghe`
- **THEN** a redirect of a job-log request to `store.ghe.example` is admitted, and a redirect elsewhere is refused naming `factory.check.github-actions`

#### Scenario: A profile's egress grants a law-medium tracker host
- **WHEN** the tracker subsection declares `connection: ghe` and an inline `api-url` on `ghe.example.com`, and `factory.connections.ghe` defines no `api-url` and its `egress` lists `ghe.example.com`
- **THEN** the tracker's calls to that host proceed with no `factory.egress.in-process` entry

# operator/vendor-connection-profile — delta for migrate-verify-to-executor-contract

## ADDED Requirements

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
- **WHEN** the tracker subsection declares `connection: ghe` and the profile's endpoint is the enterprise host
- **THEN** the tracker's calls to that host proceed with no `factory.egress.in-process` entry

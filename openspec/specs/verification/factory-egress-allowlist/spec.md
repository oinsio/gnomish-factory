# factory-egress-allowlist

## Purpose

Guard the factory's own outbound calls made by the built-in http check. `EgressGuard` protects only the agent sandbox, so this operator-owned allowlist — never widenable from a repo-committed stage manifest — is the sole defence against the http check becoming an SSRF or exfiltration vector: it decides which destinations are reachable, enforces https-only, blocks link-local, cloud-metadata and RFC1918 address classes, bounds redirects, response size and total time, re-checks redirect targets, and restricts `${...}` interpolation to a fixed engine-defined variable whitelist.

## Requirements

### Requirement: The http check is governed by an operator-owned egress allowlist
The http check SHALL be governed by a factory-side egress allowlist owned by
operator configuration, never by the repo-committed stage manifest. Because
`EgressGuard` protects only the agent sandbox and not the factory's own outbound
calls, this allowlist is the sole guard against the http check being used as an
SSRF / exfiltration vector. A request whose destination is not permitted SHALL be
refused before any connection is attempted, and the refusal SHALL be reported
with its reason (scheme, address class, or missing allowlist entry).
<!-- implements NFR-S2 of add-plugin-architecture -->
<!-- implements UX2 of add-plugin-architecture -->

#### Scenario: Allowlist lives in operator config, not the manifest
- **WHEN** a stage manifest names an http target
- **THEN** whether that target is reachable is decided by the operator-config
  allowlist, and no allowlist entry in the manifest can widen it

#### Scenario: Non-permitted destination is refused before connecting
- **WHEN** an http check targets a host not on the allowlist
- **THEN** the check is refused before any socket is opened, and the refusal is
  reported with its reason

### Requirement: The http check enforces scheme, address-class, and resource limits
The http check SHALL permit only `https`; SHALL block link-local, cloud-metadata,
and RFC1918 addresses unless a target is explicitly allowlisted; and SHALL bound
the number of redirects, the response size, and the total time of a request.
Redirects SHALL be re-checked against the same rules, so a permitted host cannot
redirect into a blocked address class.
<!-- implements NFR-S2 of add-plugin-architecture -->

#### Scenario: Plain http is refused
- **WHEN** an http check target uses the `http` scheme
- **THEN** the request is refused; only `https` is permitted

#### Scenario: Metadata and private ranges are blocked
- **WHEN** an http check resolves to a link-local, cloud-metadata, or RFC1918
  address that is not explicitly allowlisted
- **THEN** the request is refused with the blocked address class named

#### Scenario: A redirect cannot escape into a blocked class
- **WHEN** an allowlisted host responds with a redirect to a blocked address class
- **THEN** the redirect target is re-checked and refused, and the redirect count
  and response size stay within their bounds

### Requirement: Interpolation into http requests is restricted to a whitelist
Any `${...}` interpolation into an http check's URL, headers, or body SHALL draw
only from the fixed, engine-defined whitelist of variables — `${task.id}`,
`${task.branch}`, `${attempt.commit}`, `${stage.name}` — which neither the
manifest nor operator config can widen. A reference to a variable outside the
whitelist SHALL be a located validation error, so a manifest cannot smuggle
arbitrary values (including secrets or attacker-controlled data) into the
request.
<!-- implements NFR-S2 of add-plugin-architecture -->

#### Scenario: Non-whitelisted interpolation is rejected
- **WHEN** an http check interpolates a `${...}` variable that is not on the
  whitelist
- **THEN** validation reports a located error identifying the check and the
  disallowed variable

#### Scenario: Whitelisted run variables address a branch-scoped result
- **WHEN** an http check's URL interpolates `${task.branch}` and
  `${attempt.commit}`
- **THEN** the request targets the endpoint with the task branch and attempt
  commit substituted, so a CI or quality result is queried for exactly this run

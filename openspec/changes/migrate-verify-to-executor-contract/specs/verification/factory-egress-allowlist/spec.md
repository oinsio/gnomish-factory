# verification/factory-egress-allowlist — delta for migrate-verify-to-executor-contract

## ADDED Requirements

### Requirement: Egress admission follows the author of the URL
The factory's HTTP egress guard SHALL admit a destination by who authored its URL: a target a
stage manifest names SHALL be admitted only by the operator allowlist, `https` only; an
operator-configured connection — the tracker's `api-url`, a verdict built-in's connection, a
notification URL — SHALL be admitted by that configuration itself, scheme and host as declared,
plus the redirect hosts the connection names or the executor declares. The address-class rules
SHALL apply to every hop of both.
**Implements**: FR15, FR17 of migrate-verify-to-executor-contract
**Notes**: The manifest is written by whoever commits to the repository, the gnome included; the connection is the operator's own consent (design D16 as amended 2026-11-08).

#### Scenario: An operator connection is admitted by its own configuration
- **WHEN** `tracker.github.api-url` names a GitHub Enterprise host that no allowlist lists
- **THEN** the tracker's calls to that host proceed, with the address-class, redirect and resource rules still applied to each

#### Scenario: A manifest target is never admitted by a connection
- **WHEN** an http check targets the host of the configured tracker connection and `factory.check.http.allowlist` does not name it
- **THEN** the check is refused before any connection, with the missing allowlist entry as the reason

#### Scenario: A plain-http operator connection is admitted as declared
- **WHEN** a verdict built-in's connection names an `http://` endpoint on the operator's network
- **THEN** the built-in's calls to that endpoint proceed, while an `http://` target in a stage manifest stays refused

#### Scenario: A refused operator connection is a located load error
- **WHEN** an operator connection's host resolves only to a blocked address class and the operator did not allowlist that literal address
- **THEN** loading reports a located error naming the connection's configuration key and the blocked class, and no call is made

### Requirement: The guarded exchange serves every factory HTTP consumer
Every HTTP call the factory process makes with a credential attached SHALL pass through the one
guarded exchange of the egress leaf. The exchange SHALL hand the consumer the response status,
headers and body; the response bound and the request timeout SHALL be the consumer's own, and a
consumer MAY read a body as a bounded tail instead of refusing it.
**Implements**: FR15, FR17 of migrate-verify-to-executor-contract
**Notes**: The `http` built-in's findings keep rendering status and body only; a header never reaches a finding.

#### Scenario: Response headers reach the consumer
- **WHEN** the GitHub core sends a conditional request through the guarded exchange and the platform answers `304` with an `ETag`
- **THEN** the core reads the header and serves the cached body, as it did over its own client

#### Scenario: The response bound is the consumer's
- **WHEN** a comment thread the tracker reads exceeds the `http` built-in's status-document bound but not the GitHub core's declared bound
- **THEN** the thread is read whole, and a response above the core's own bound is still refused

#### Scenario: A job log is read as a bounded tail
- **WHEN** a failed job's log exceeds the GitHub core's response bound
- **THEN** the finding carries the log's tail and the check reaches a verdict, not cannot-verify

#### Scenario: Every request carries its consumer's timeout
- **WHEN** a platform accepts a GitHub request and never answers
- **THEN** the call fails within the GitHub core's declared request timeout and the slot is released

### Requirement: A redirect hop keeps the request's meaning
On a redirect the guarded exchange SHALL re-judge the target and SHALL send the next hop as the
HTTP semantics of the status prescribe: a `301`, `302` or `303` hop SHALL be a `GET` without a
body, a `307` or `308` hop SHALL keep the method and body; a hop to another host SHALL carry no
header of the first request forward.
**Implements**: FR15 of migrate-verify-to-executor-contract
**Notes**: The JDK's own `Redirect.NORMAL` already strips `Authorization` and `Cookie` across hosts (measured on JDK 25.0.3 and 27.0.1); the leaf keeps the property where it can be asserted.

#### Scenario: A see-other redirect becomes a GET
- **WHEN** a `POST` to a transferred repository is answered with `301` to the repository's new location on the same host
- **THEN** the next hop is a `GET` of that location, and the consumer receives its answer

#### Scenario: A cross-host redirect carries no credential
- **WHEN** an admitted host redirects to another admitted host
- **THEN** the second request carries no `Authorization` header and no other header of the first

### Requirement: An egress refusal is a configuration fault
A refusal of the egress guard SHALL name the configuration key of the consumer whose URL it
judged; no consumer SHALL retry a refused request, and a refusal SHALL raise no remote-outage
signal.
**Implements**: NFR-R5, UX4 of migrate-verify-to-executor-contract

#### Scenario: A refusal names the consumer's configuration key
- **WHEN** a redirect of a `github-actions` call is refused
- **THEN** the refusal names `factory.check.github-actions`, not `factory.check.http.allowlist`

#### Scenario: A refusal is neither retried nor an outage
- **WHEN** the GitHub core's request is refused by the guard mid-run
- **THEN** the core makes no further attempt, the remote-outage gate receives no signal, and the failure reaches the caller as a configuration fault naming the key

### Requirement: A built-in declares the redirect hosts its platform answers with
A built-in verdict executor whose platform answers a call with a redirect to another host SHALL
declare those hosts in its describe as defaults, and the operator's connection MAY name further
redirect hosts under `redirect-hosts`; a redirect to a host neither names SHALL be refused before
any connection.
**Implements**: FR15, FR17 of migrate-verify-to-executor-contract
**Notes**: GitHub serves job logs by `302` to a storage host; `github-actions` declares `results-receiver.actions.githubusercontent.com` and `*.blob.core.windows.net`, and a GHES operator names its own store.

#### Scenario: Job logs download through the platform's storage host
- **WHEN** GitHub answers the job-log request with `302` to a host under `blob.core.windows.net`
- **THEN** the hop is admitted by the built-in's declared redirect hosts and the log tail reaches the finding

#### Scenario: A GHES log store is named by the operator
- **WHEN** `factory.check.github-actions.redirect-hosts` names the enterprise's storage host and the platform redirects there
- **THEN** the hop is admitted, and a redirect to any other host is refused naming the key

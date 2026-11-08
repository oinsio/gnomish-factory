# verification/factory-egress-allowlist — delta for migrate-verify-to-executor-contract

## MODIFIED Requirements

### Requirement: The http check is governed by an operator-owned egress allowlist
The http check SHALL be governed by a factory-side egress allowlist owned by
operator configuration — the `in-process` scope of the operator egress block,
`factory.egress.in-process` — never by the repo-committed stage manifest. A request
whose destination is not permitted SHALL be refused before any connection is
attempted, and the refusal SHALL be reported with its reason (scheme, address
class, or missing allowlist entry).
**Implements**: NFR-S2 of add-plugin-architecture; UX2 of add-plugin-architecture; FR17 of migrate-verify-to-executor-contract
**Notes**: `EgressGuard` protects only the agent sandbox and not the factory's own outbound calls, so this allowlist is the sole guard against the http check being used as an SSRF / exfiltration vector. The former `factory.check.http.allowlist` is a removed key (design D16 as amended 2026-11-09).

#### Scenario: Allowlist lives in operator config, not the manifest
- **WHEN** a stage manifest names an http target
- **THEN** whether that target is reachable is decided by the operator-config
  allowlist, and no allowlist entry in the manifest can widen it

#### Scenario: Non-permitted destination is refused before connecting
- **WHEN** an http check targets a host not on the allowlist
- **THEN** the check is refused before any socket is opened, and the refusal is
  reported with its reason

## ADDED Requirements

### Requirement: Egress admission follows the medium of the URL
The factory's HTTP egress guard SHALL admit a destination by the medium its URL comes from: a
host an operator file names SHALL be granted; a host the law tree names SHALL be a request,
admitted only by an operator grant or by the vendor default the adapter's describe declares.
A request nobody grants SHALL be a located load error at the line of the request, naming the
ways to grant it.
**Implements**: FR15, FR17 of migrate-verify-to-executor-contract
**Notes**: Operator files: a `factory.check.<name>` subsection, a `factory.connections.<name>` profile, the operator egress block. Law tree: a stage manifest's http target, an inline `tracker.github.api-url`, an `executors.<name>.egress` entry. A grant is an egress entry naming the host or its address class, or a `connection:` profile naming the host. `.gnomish/config.yaml` is the target repository's, written by whoever commits to it; only the `factory.*` files of ADR 0011 are the operator's (design D16 as amended 2026-11-09).

#### Scenario: The vendor default needs no grant
- **WHEN** `tracker.github.api-url` is `https://api.github.com` and no operator file names the host
- **THEN** loading succeeds and the tracker's calls proceed, with the address-class, redirect and resource rules still applied to each

#### Scenario: An inline enterprise host needs an operator grant
- **WHEN** `tracker.github.api-url` in `.gnomish/config.yaml` names a GitHub Enterprise host and no operator file names it
- **THEN** loading reports a located error at that line naming the two ways to grant the host: an `in-process` egress entry, or a `connection:` profile declaring it

#### Scenario: An operator profile grants the enterprise host
- **WHEN** the tracker subsection declares `connection: ghe` and `factory.connections.ghe` names the enterprise endpoint, or `factory.egress.in-process` names its host
- **THEN** the tracker's calls to that host proceed

#### Scenario: A plain-http operator connection is admitted as declared
- **WHEN** `factory.check.scm-status` names an `http://` Gitea endpoint on the operator's network
- **THEN** the built-in's calls to that endpoint proceed, while an `http://` target in a stage manifest stays refused

#### Scenario: A manifest target is never admitted by a connection
- **WHEN** an http check targets the host of the configured tracker connection and `factory.egress.in-process` does not name it
- **THEN** the check is refused before any connection, with the missing egress entry as the reason

### Requirement: One grammar for every egress entry
Every egress list the factory reads SHALL be parsed by one grammar with one owner. A class word
SHALL grant every host resolving only into that class and waive its block; no entry SHALL lift
the block on a cloud-metadata address. `factory.sandbox.egress-allowlist` and
`factory.check.http.allowlist` SHALL be removed keys whose startup violation names the new key.
**Implements**: FR17 of migrate-verify-to-executor-contract
**Notes**: The lists: `factory.egress.sandbox`, `factory.egress.in-process`, a connection's `egress`, a law's `executors.<name>.egress`, an executor's describe `egress`. The grammar: an exact host name; `*.suffix`, matching any depth of subdomain and never the apex; an IP literal; a CIDR block; the address-class words `loopback` and `private`. Wildcard semantics follow Cilium `toFQDNs` and Gitea `ALLOWED_HOST_LIST`; the class words follow Gitea. A port form is NG8.

#### Scenario: A wildcard never matches the apex
- **WHEN** an entry is `*.blob.core.windows.net`
- **THEN** `abc.blob.core.windows.net` and `a.b.blob.core.windows.net` are admitted and `blob.core.windows.net` is not

#### Scenario: A class word admits loopback for the specs
- **WHEN** `factory.egress.in-process` lists `loopback` and a connection or a granted law host resolves to `127.0.0.1`
- **THEN** the call proceeds, and a host resolving to a private address is still refused

#### Scenario: No entry lifts the metadata block
- **WHEN** an entry names `169.254.169.254` literally and a hop resolves to it
- **THEN** the hop is refused naming the cloud-metadata class

#### Scenario: A removed allowlist key names its replacement
- **WHEN** a project file sets `factory.sandbox.egress-allowlist` or `factory.check.http.allowlist`
- **THEN** startup stops with a violation naming the file, the line and the `factory.egress` scope that replaces the key

### Requirement: The guarded exchange serves every factory HTTP consumer
Every HTTP call the factory process makes with a credential attached SHALL pass through the one
guarded exchange of the egress leaf. The exchange SHALL hand the consumer the response status,
headers and body; the response bound and the request timeout SHALL be the consumer's own, and a
consumer MAY read a body as a bounded tail instead of refusing it.
**Implements**: FR15, FR17 of migrate-verify-to-executor-contract
**Notes**: The `http` built-in's findings keep rendering status and body only; a header never reaches a finding. The `http` built-in keeps 1 MiB and 30 s; the GitHub core declares 16 MiB and 60 s (design D16).

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

### Requirement: A redirect target is an egress target
A redirect hop SHALL be judged as a first request is, against the same admitted set: the
connection's host, the connection's `egress`, the built-in's describe `egress`, and the
`in-process` scope. No separate list of redirect hosts SHALL exist. A built-in whose platform
redirects to another host SHALL declare that host in its describe `egress`; an operator whose
platform redirects elsewhere SHALL name the host in the connection's `egress`.
**Implements**: FR15, FR17 of migrate-verify-to-executor-contract
**Notes**: GitHub serves job logs by `302` to a storage host; the `github-actions` describe declares `api.github.com`, `results-receiver.actions.githubusercontent.com` and `*.blob.core.windows.net`, and a GHES operator names its own store.

#### Scenario: Job logs download through the platform's storage host
- **WHEN** GitHub answers the job-log request with `302` to a host under `blob.core.windows.net`
- **THEN** the hop is admitted by the built-in's describe `egress` and the log tail reaches the finding

#### Scenario: A GHES log store is named by the operator
- **WHEN** `factory.check.github-actions.egress` names the enterprise's storage host and the platform redirects there
- **THEN** the hop is admitted, and a redirect to a host no admitted set names is refused naming the connection's key

### Requirement: A redirect hop keeps the request's meaning
On a redirect the guarded exchange SHALL re-judge the target and send the next hop as the HTTP
semantics of the status prescribe: a `301`, `302` or `303` hop SHALL be a `GET` without a body or
`Content-*` headers, a `307` or `308` hop SHALL keep method and body; a downgrade to `http` and a
hop onto a visited URL SHALL be refused; hops SHALL be bounded. A hop to another host SHALL drop
the credential deny-set and carry every other header forward.
**Implements**: FR15 of migrate-verify-to-executor-contract
**Notes**: The credential deny-set: `Authorization`, `Cookie`, `Host`, `Origin`, `Referer`, `Proxy-Authorization`, `WWW-Authenticate`; a same-host hop carries every header. RFC 9110 §15.4 as the JDK's `RedirectFilter`, Go `net/http`, OkHttp and curl implement it; the JDK's `Redirect.NORMAL` already drops the five names across hosts (measured on JDK 25.0.3 and 27.0.1), and the leaf keeps the property where it can be asserted. A subdomain is another host.

#### Scenario: A see-other redirect becomes a GET
- **WHEN** a `POST` to a transferred repository is answered with `301` to the repository's new location on the same host
- **THEN** the next hop is a `GET` of that location with no body and no `Content-*` header, and the consumer receives its answer

#### Scenario: A cross-host redirect carries no credential but keeps negotiation
- **WHEN** an admitted host redirects to another admitted host
- **THEN** the second request carries no `Authorization` and no `Cookie`, and still carries the first request's `Accept` and `User-Agent`

#### Scenario: A downgrade redirect is refused
- **WHEN** an `https` request is answered with a redirect to an `http` URL
- **THEN** the hop is refused before any connection, naming the downgrade

### Requirement: An egress refusal is a configuration fault
A refusal of the egress guard SHALL name the configuration key of the consumer whose URL it
judged; no consumer SHALL retry a refused request, and a refusal SHALL raise no remote-outage
signal. Load SHALL resolve no name: a law-medium host without a grant and a literal blocked
address are judged at load, the address class of a named host at connect; an unresolvable host
SHALL be an infrastructure failure of the call, never a configuration refusal.
**Implements**: NFR-R5, UX4 of migrate-verify-to-executor-contract
**Notes**: Terraform `validate` reads no remote and Envoy never resolves synchronously; ADR 0019, decision 5, places an operator connection's truth at the daemon's level, not a task's law.

#### Scenario: A refusal names the consumer's configuration key
- **WHEN** a redirect of a `github-actions` call is refused
- **THEN** the refusal names `factory.check.github-actions`, not `factory.egress.in-process`

#### Scenario: A refusal is neither retried nor an outage
- **WHEN** the GitHub core's request is refused by the guard mid-run
- **THEN** the core makes no further attempt, the remote-outage gate receives no signal, and the failure reaches the caller as a configuration fault naming the key

#### Scenario: A literal blocked address is refused at load
- **WHEN** `factory.check.scm-status` names `http://169.254.169.254/` as its endpoint
- **THEN** loading reports a located error naming the key and the cloud-metadata class, with no lookup and no call

#### Scenario: An unresolvable host is an outage, not a refusal
- **WHEN** the tracker's configured host does not resolve when a call is made
- **THEN** the call fails on the tracker's transport path and the remote-outage gate is signalled, and no configuration violation is reported

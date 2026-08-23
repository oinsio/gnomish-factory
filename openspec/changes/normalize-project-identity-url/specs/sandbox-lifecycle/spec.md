# Delta: sandbox-lifecycle (normalize-project-identity-url)

## MODIFIED Requirements

### Requirement: Project identity derivation
The project identity SHALL be resolved, in precedence order, from: the operator's explicit
override (`factory.sandbox.project-id`); otherwise a stable truncated digest of the clone's
**normalized** `origin` remote URL; otherwise a stable truncated digest of the clone's own
canonical absolute path. Normalization SHALL remove the URL's userinfo, lower-case the scheme and
host, remove an explicitly written default port of the scheme (`http` 80, `https` 443, `ssh` 22,
`git` 9418), remove one trailing `/`, remove a trailing `.git`, and render the scp-style
`[user@]host:path` form in the same shape as the equivalent `ssh://host/path` URL — so one project
keeps one identity across a credential rotation and across the cosmetic variants that name the
same remote.
Normalization SHALL NOT conflate remotes that differ in host, path, non-default port, or scheme
(beyond case):
those remain distinct identities. A remote URL in a shape the normalization does not recognize
SHALL fall back to the raw string rather than failing — an unusual remote costs identity stability,
never a run or a sweep pass. The raw remote URL SHALL never be used as the identity, since it may
carry an embedded credential, and no removed userinfo SHALL appear in any label, log line, or error
message. A clone with no `origin` SHALL NOT fall back to a shared constant — that would place
every origin-less project on a host into one sweep scope, the exact cross-project reach the label
exists to prevent. An override SHALL be rejected unless it matches `[A-Za-z0-9._-]+`: the label
set is rendered and read back as `k1=v1,k2=v2`, so a value carrying a comma or an equals sign
could forge a second label pair and strip an object of its ownership mode. A rejected override
SHALL name the property without echoing the offending value.
<!-- implements FR8 of add-serve-sandbox-lifecycle -->
<!-- implements FR1, FR2, FR4, NFR-R1, NFR-S1 of normalize-project-identity-url -->

#### Scenario: A rotated credential keeps the project identity
- **WHEN** the clone's `origin` URL embeds a credential and that credential is replaced with a new one
- **THEN** the resolved identity is unchanged, and objects created before the rotation stay in the
  sweep's scope

#### Scenario: Cosmetic URL variants of one remote resolve alike
- **WHEN** the same remote is written with and without a `.git` suffix, with and without a trailing
  slash, with a differently-cased host, with an explicitly written default port, or in the
  scp-style form
- **THEN** every variant resolves to the same identity

#### Scenario: Distinct remotes keep distinct identities
- **WHEN** two clones name remotes differing in host, path, non-default port, or scheme (beyond
  case)
- **THEN** they resolve to different identities and neither appears in the other's verdicts

#### Scenario: An unparseable remote URL does not fail the run
- **WHEN** the `origin` URL is in a shape the normalization does not recognize
- **THEN** the identity is derived from the raw string and the run and the sweep proceed normally

#### Scenario: Two checkouts of one origin-less repository ignore each other
- **WHEN** a sweep runs in a clone that has no `origin` and no configured override, while a second
  checkout of the same repository has objects on the same daemon
- **THEN** the two resolve different identities and neither appears in the other's verdicts

#### Scenario: An override that could forge a label is refused
- **WHEN** `factory.sandbox.project-id` is set to a value containing a comma or an equals sign
- **THEN** resolution fails naming the property, and no object is created or swept under it

### Requirement: Project scoping
Sweep and reaper SHALL act only on objects carrying this factory's own project identity label.
Objects of another project SHALL be excluded at listing and never touched, in any ownership mode or
state. When the identity derives from the `origin` URL and the digest of the raw, un-normalized URL
differs from the normalized one, this factory's own identities SHALL include that **legacy**
identity in addition to the identity it stamps, so objects created before normalization stay in
scope instead of being orphaned; with an override set or no `origin` configured, no legacy identity
exists. Objects SHALL be stamped with the normalized identity only, and no object SHALL ever be
relabelled. The legacy scope SHALL cost at most one extra listing per object kind per pass, and
only while a legacy identity exists. A failed legacy listing SHALL abort the pass under the existing fail-closed rule
rather than degrade to a partial object set. When legacy-labelled objects are found, the pass SHALL
log one INFO naming the count.
<!-- implements FR8 of add-serve-sandbox-lifecycle -->
<!-- implements FR3, NFR-R2, NFR-O1, NFR-C1 of normalize-project-identity-url -->

#### Scenario: Two projects share one Docker host
- **WHEN** project A's sweep runs while project B has live and kept objects on the same daemon
- **THEN** project B's objects appear in no verdict of project A's sweep

#### Scenario: Objects labelled before normalization stay in scope
- **WHEN** a sweep runs against objects stamped with the digest of the raw `origin` URL, while the
  normalized identity differs from it
- **THEN** those objects are classified and acted on exactly as if they carried the current
  identity, and one INFO records how many were found

#### Scenario: A legacy listing failure yields no verdicts
- **WHEN** the extra legacy listing cannot be obtained
- **THEN** the pass emits no verdicts and no completed tick, exactly as for any other failed listing

#### Scenario: No extra cost when no legacy identity exists
- **WHEN** no legacy identity exists (an override is set, there is no `origin`, or the URL is
  already normal)
- **THEN** the pass performs no additional listing

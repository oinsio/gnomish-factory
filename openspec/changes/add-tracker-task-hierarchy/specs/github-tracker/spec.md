# github-tracker — delta for add-tracker-task-hierarchy

## ADDED Requirements

### Requirement: Hierarchy facts via native sub-issues and dependencies
The GitHub adapter SHALL source hierarchy facts from GitHub's native
primitives: parent/children from the sub-issues REST endpoints and
`dependencyBlocked` from the issue-dependencies (blocked-by) REST endpoints.
Hierarchy SHALL be walked one level at a time with paginated REST calls —
never a deep nested query — and the adapter SHALL respect GitHub's limits
(100 sub-issues per parent, 8 nesting levels) by reporting, not enforcing,
whatever depth exists.
<!-- implements FR1, FR5 of add-tracker-task-hierarchy -->

#### Scenario: Parent and children read from sub-issue endpoints
- **WHEN** `fetchTask` runs for an issue that is a sub-issue and itself has
  sub-issues
- **THEN** the parent ref comes from the issue's parent lookup and the child
  list from its sub-issues listing, in GitHub's stored order

#### Scenario: Blocked fact reflects unresolved blocked-by edges
- **WHEN** an issue has one open and one closed blocking issue
- **THEN** the adapter reports `dependencyBlocked` true, and false once the
  open blocker closes

### Requirement: Subtask creation writes issue, link, and edges
Create-subtask SHALL create the child issue, link it under the parent via the
sub-issues endpoint, record the caller's stable key durably on the child so
that listing the parent's children recovers it, and add the requested
blocked-by edges. The write order SHALL put the stable-key record and parent
link before any blocked-by edge, so a crash mid-creation leaves a child that
recovery can identify by key; a retry after any mid-creation crash SHALL
converge to exactly one child per key.
<!-- implements FR2, NFR-R1 of add-tracker-task-hierarchy -->

#### Scenario: Created child is discoverable by key
- **WHEN** create-subtask completes for parent P with key `k3`
- **THEN** listing P's children yields the new issue carrying key `k3`

#### Scenario: Crash between link and edge converges on retry
- **WHEN** creation crashed after the parent link landed but before the
  blocked-by edge, and create-subtask is retried with the same key
- **THEN** the retry returns `AlreadyExists` for the existing child and the
  caller can complete the missing edge without a duplicate issue

### Requirement: Feed blocked fact within the polling economy
The ready-feed enrichment SHALL deliver the `dependencyBlocked` fact without
adding a per-task request for every feed poll: dependency state SHALL be read
only for tasks whose feed representation changed since the last poll, reusing
the adapter's conditional-request economy; an unchanged task reuses its
previously derived fact.
<!-- implements FR3, NFR-P1 of add-tracker-task-hierarchy -->

#### Scenario: Unchanged feed page adds no dependency requests
- **WHEN** two consecutive feed polls see an unchanged feed page
- **THEN** the second poll issues no additional dependency reads and reports
  the same blocked facts

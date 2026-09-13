# verification-hardening — delta for type-untrusted-text

Written over the main spec; no active change modifies this requirement.

## MODIFIED Requirements

### Requirement: Sanitized logs and fenced tracker publication
Before logging, findings text SHALL be stripped of ANSI/control sequences and
truncated to a length cap. Tracker publication of any untrusted text — check
findings, judge details, agent-authored decision questions, subprocess
output quoted in a park report, tracker-sourced strings echoed back — SHALL
go through one comment exit that wraps the text in a fenced block labeled as
untrusted machine output, with mentions and issue references escaped and the
fence longer than any run the content starts a line with. Every component
that parks a task with a report SHALL render the report's untrusted parts
through that exit; no tracker write SHALL carry a raw carrier.
<!-- implements FR15 of add-sandbox-core -->
<!-- implements FR8, NFR-S2 of type-untrusted-text -->

#### Scenario: Terminal escape attack is neutralized
- **WHEN** command-check output contains ANSI escape sequences and an `@team`
  mention
- **THEN** the log line contains neither, and the tracker comment shows the
  text fenced, mention escaped

#### Scenario: A park report quoting git output is fenced
- **WHEN** a task is parked because its base could not be refreshed and the
  report quotes git's error text
- **THEN** the tracker comment shows the quoted text inside the labeled fence
  with mentions escaped, and the factory-authored instruction lines outside it

#### Scenario: Every park writer uses the exit
- **WHEN** production sources are scanned for tracker park and comment writes
- **THEN** every write that includes a carrier renders it through the comment
  exit, and the type gate fails a write that does not

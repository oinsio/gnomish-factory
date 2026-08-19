# sandbox-egress — delta

## MODIFIED Requirements

### Requirement: Denials are captured as structured findings
Every guard denial SHALL be recorded as structured metadata (host, path, method — never request bodies) and readable as findings from the guard, so a blocked attempt is a machine-readable signal rather than silence. Captured denials SHALL reach the task report for the round they occurred in, independently of the round's verdict: attaching a denial SHALL NOT change any check verdict, the stage outcome, or the feedback context of a retry.
<!-- implements NFR-O1 of add-sandbox-core -->
<!-- implements FR3, NFR-O1, NFR-S1 of fix-denial-report-attachment -->

#### Scenario: A denied request is captured as a structured finding
- **WHEN** the gnome attempts a request to a non-allowlisted host during a round
- **THEN** the guard records a structured denial finding carrying the denied host, path, and method (never the request body), readable back from the guard

#### Scenario: Denied exfiltration attempt reaches the report on a passing attempt
- **WHEN** a round records a guard denial and every check of the attempt passes
- **THEN** the attempt's report entry carries the denial finding while the attempt result stays passed

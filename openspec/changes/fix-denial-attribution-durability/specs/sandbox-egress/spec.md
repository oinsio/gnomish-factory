# sandbox-egress — delta

## MODIFIED Requirements

### Requirement: Denials are captured as structured findings
Every guard denial SHALL be recorded as structured metadata (host, path, method — never request bodies) and readable as findings from the guard, so a blocked attempt is a machine-readable signal rather than silence. The recorded path SHALL carry no query string: a denial finding is committed to the task branch, and a query is gnome-chosen request payload rather than metadata about the destination. Captured denials SHALL reach the task report for the round they occurred in, independently of the round's verdict and independently of whether that round ever closed: attaching a denial SHALL NOT change any check verdict, the stage outcome, or the feedback context of a retry. A round that dies before its close SHALL still have its denials read from the environment and carried out on its escalation, since no attempt record exists to hold them.
<!-- implements NFR-O1 of add-sandbox-core -->
<!-- implements FR3, NFR-O1, NFR-S1 of fix-denial-report-attachment -->
<!-- implements FR1, NFR-O1 of fix-denial-attribution-durability -->

#### Scenario: A denied request is captured as a structured finding
- **WHEN** the gnome attempts a request to a non-allowlisted host during a round
- **THEN** the guard records a structured denial finding carrying the denied host, path, and method (never the request body), readable back from the guard

#### Scenario: A denied request's query string never enters the finding
- **WHEN** the denied request carries a query string (`GET /upload?token=…`)
- **THEN** the recorded finding names the destination and the path up to the query, and the query itself appears nowhere in the finding

#### Scenario: Denied exfiltration attempt reaches the report on a passing attempt
- **WHEN** a round records a guard denial and every check of the attempt passes
- **THEN** the attempt's report entry carries the denial finding while the attempt result stays passed

#### Scenario: Denied exfiltration attempt of a hung round reaches the report
- **WHEN** a round that recorded a guard denial is killed before it could close
- **THEN** the denial is read from the environment and reported under the task's escalation rather than only in the factory log

## ADDED Requirements

### Requirement: Denial read-back is durable across processes
The read position that makes each denial read a delta SHALL outlive the factory process that advanced it, for as long as the guard log it indexes exists, on every path that records denials: a factory process resuming a task over a surviving guard SHALL start its next read past every denial already recorded against an attempt or an escalation of that task. The position SHALL advance durably only together with the record carrying the denials it delimits — never ahead of it — so a lost write degrades to a re-read (a duplicated denial) and never to a skipped read (a lost one). A position SHALL share the lifetime of the log it indexes: where the guard and its log are gone, starting fresh is correct rather than a fallback, and a position naming a container that is not the live guard SHALL be discarded. Reading and persisting the position SHALL be best-effort: when it cannot be recovered, the read SHALL fall back to reporting more rather than less, and the fallback SHALL be logged so duplicates in a report are explainable. The read SHALL stay bounded in every case by the guard log tail cap.
<!-- implements FR3, FR4, FR5, NFR-R1, NFR-R2, NFR-O2, NFR-C1 of fix-denial-attribution-durability -->

#### Scenario: Resume by another instance does not re-report past denials
- **WHEN** a task whose denials are recorded against attempts and against a `cannotExecute` escalation is parked, and another factory instance resumes it over the surviving guard container
- **THEN** the first round after the resume reports only the denials recorded during that round — neither the attempts' nor the escalation's

#### Scenario: A lost read position prefers duplicates over silence
- **WHEN** the durable read position cannot be recovered for a task that has a guard log
- **THEN** the read returns the denials it can see, the report may repeat an already-recorded denial, and the fallback is logged

#### Scenario: A denial is reported once while the position holds
- **WHEN** a task runs several rounds across two factory processes with the read position intact
- **THEN** each denial appears under exactly one recorded round or escalation

# sandbox-egress — delta

## MODIFIED Requirements

### Requirement: Denials are captured as structured findings
Every guard denial SHALL be recorded as structured metadata (host, path, method — never request bodies) and readable as findings from the guard, so a blocked attempt is a machine-readable signal rather than silence. The recorded path SHALL carry no query string: a denial finding is committed to the task branch, and a query is gnome-chosen request payload rather than metadata about the destination. Captured denials SHALL reach the task report for the round they occurred in, independently of the round's verdict and independently of whether that round ever closed: attaching a denial SHALL NOT change any check verdict, the stage outcome, or the feedback context of a retry. A round that dies before its close SHALL still have its denials read from the environment and carried out on its escalation, since no attempt record exists to hold them. Attribution assigns a denial to the round whose read collected it; after a crash that may be the round following the one that triggered it — a declared relaxation of event-time attribution, not an accident.
<!-- implements NFR-O1 of add-sandbox-core -->
<!-- implements FR3, NFR-O1, NFR-S1 of fix-denial-report-attachment -->
<!-- implements FR1, FR5, NFR-O1 of fix-denial-attribution-durability -->

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
The read position that makes each denial read a delta SHALL outlive the factory process that advanced it, for as long as the guard log it indexes exists, on every path that records denials: a factory process resuming a task over a surviving guard SHALL start its next read past every denial already recorded against an attempt or an escalation of that task. The position SHALL advance durably only together with the record carrying the denials it delimits — never ahead of it — so a lost write degrades to a re-read (a duplicated denial) and never to a skipped read (a lost one). A position SHALL share the lifetime of the log it indexes: where the guard and its log are gone, starting fresh is correct rather than a fallback, and a position naming a container that is not the live guard SHALL be discarded. Reading the position from the environment SHALL be best-effort: when it cannot be recovered, the read SHALL fall back to reporting more rather than less, and the fallback SHALL be logged so duplicates in a report are explainable. The authoritative position is the one at the branch tip on origin after a successful push; a local-only position is advisory, and the position and its record SHALL ride one commit so a push delivers both or neither. The read SHALL stay bounded in every case by the guard log tail cap.
<!-- implements FR3, FR4, FR5, NFR-R1, NFR-R2, NFR-R3, NFR-O2, NFR-C1 of fix-denial-attribution-durability -->

#### Scenario: Resume by another instance does not re-report past denials
- **WHEN** a task whose denials are recorded against attempts and against a `cannotExecute` escalation is parked, and another factory instance resumes it over the surviving guard container
- **THEN** the first round after the resume reports only the denials recorded during that round — neither the attempts' nor the escalation's

#### Scenario: A lost read position prefers duplicates over silence
- **WHEN** the durable read position cannot be recovered for a task that has a guard log
- **THEN** the read returns the denials it can see, the report may repeat an already-recorded denial, and the fallback is logged

#### Scenario: A denial is reported once while the position holds
- **WHEN** a task runs several rounds across two factory processes with the read position intact
- **THEN** each denial appears under exactly one recorded round or escalation

### Requirement: Denial identity and idempotent merge
Every recorded denial SHALL carry a source-assigned identity — the denial source's own event timestamp paired with the source identity — kept from the log line it was parsed from. Attaching denials to a record SHALL merge idempotently by that identity against the denials already recorded at the branch tip: a fallback re-read re-attaches only the denials not already recorded, and the merge outcome is logged ("N already present, M recovered"). The identity is environment bookkeeping: it SHALL be carried in the task branch documents additively under contract v1 (an absent identity reads as "unknown, keep"), and SHALL NOT appear in `status.json` or the text render. Two denials to the same destination remain two events: identity comes from the source's event coordinates, never from the finding's content.
<!-- implements FR7, NFR-R3 of fix-denial-attribution-durability -->

#### Scenario: A fallback re-read records no duplicates
- **WHEN** the durable read position is lost but the recorded denials carry identities, and the read falls back to the full tail
- **THEN** only the denials whose identities are not yet recorded are attached, and the report shows each denial exactly once

#### Scenario: Repeated denials to the same destination are counted, not collapsed
- **WHEN** the gnome triggers two denials to the same host, path, and method in one round
- **THEN** both are recorded as distinct events with distinct identities

#### Scenario: Records written before identities existed still merge
- **WHEN** a re-read encounters recorded denials with no identity field
- **THEN** they are kept as-is and the read degrades to the duplicate-tolerant fallback for them alone

### Requirement: Denial loss is visible in the report
When the factory can see that denials were lost — the tail cap saturated a read (lines older than the read window are gone), or a committed cursor names a source that no longer holds its log while recorded history says denials existed — it SHALL record a loss marker as a synthetic finding through the same findings channel the denials use, funnel-fenced like any finding, naming the loss window it can bound. A report SHALL thereby distinguish "no denials" from "no data": a task with neither denials nor loss shows nothing. The marker SHALL gate nothing, exactly like the denials it stands in for.
<!-- implements FR8, NFR-O3, UX3 of fix-denial-attribution-durability -->

#### Scenario: A saturated tail cap surfaces as loss
- **WHEN** a read returns the tail cap's maximum and the cursor shows the window began after the last read position
- **THEN** the record carrying that read's denials also carries a loss marker naming the bounded gap, in both task documents and the text render

#### Scenario: A quiet task shows nothing
- **WHEN** a task records no denial and no loss condition occurs
- **THEN** no denial entry and no loss marker appear on any report surface

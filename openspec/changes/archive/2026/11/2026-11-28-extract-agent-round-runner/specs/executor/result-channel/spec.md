# executor/result-channel — delta for extract-agent-round-runner

## ADDED Requirements

### Requirement: Answer and findings files each have one read
An executor's answer file and the command check's findings file SHALL each be read through the
shared result reader with its cap and untrusted-text posture, by one function per file kind. The
findings file SHALL keep its synthetic-finding fallback; the answer file SHALL have none.
**Implements**: FR21 of extract-agent-round-runner; FR6 of define-executor-contract

#### Scenario: An unparseable answer file is refused, never a question
- **WHEN** an answer file holds text that is not a valid answer
- **THEN** the read refuses it with its cause, and no decision request is built from the text

#### Scenario: The answer read has one caller path for live and recovery
- **WHEN** production sources are scanned for reads of an answer file path
- **THEN** every one goes through the one answer read, and the architecture spec in the
  composition-root module fails naming a class that parses an answer file itself

#### Scenario: A garbage findings file keeps its synthetic finding
- **WHEN** a command check's findings file holds text that is not valid findings
- **THEN** a warning is logged and the check keeps its synthetic result, as
  `verification/verdict-executors` states it

## REMOVED Requirements

### Requirement: Decision and findings files are result files
**Reason**: The decision file is the answer file since this change, and its tolerant fallback is
retired by design D15. The fate of each scenario:
- "Garbage decision file keeps its fallback" — dropped: unparseable content is no longer a
  decision request whose question is the raw text. It is refused by the read ("An unparseable
  answer file is refused, never a question" above) and is the round's quality failure
  (`executor/agent-round-runner`, "An invalid transform answer is a quality failure", scenario
  "An unparseable answer file burns one attempt").
The findings file's read and its synthetic-finding fallback are unchanged and restated in
"Answer and findings files each have one read".
**Migration**: none for manifests. An agent that wrote free text to its decision file now gets
that text back as a finding of the failed round and answers again in the answer's shape.

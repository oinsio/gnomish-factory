## ADDED Requirements

### Requirement: Command checks are bounded
A stage's `command` verify check SHALL be bounded by a configured, installation-level timeout.
A check that has not exited when the timeout expires SHALL be terminated (tree-wide, with the
supervision kill discipline) and SHALL classify as a quality failure whose findings carry the
captured output tail — the command ran and failed to finish, exactly as a red exit code would
have failed it — burning a stage attempt and feeding the tail back as retry context. The
timeout expiry SHALL be logged naming the check id, the elapsed time, and the configured value.
<!-- implements FR12, FR5, NFR-O1, UX4 of bound-subprocess-commands -->

#### Scenario: A hung check fails instead of hanging the run
- **WHEN** a `command` check enters an infinite loop and its timeout expires
- **THEN** the check resolves as a quality failure within the timeout plus the kill margin, its
  findings carry the output tail captured so far, and the stage's ordinary retry loop proceeds

#### Scenario: A check that finishes in time is untouched
- **WHEN** a `command` check exits before the timeout
- **THEN** its exit code, tail capture, and verdict classification are exactly as before

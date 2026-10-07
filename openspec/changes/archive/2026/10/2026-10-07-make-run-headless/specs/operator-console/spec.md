# Spec Delta: operator-console

## MODIFIED Requirements

### Requirement: Machine-readable console output is verbatim
The console owner's machine-readable path SHALL write its input byte for byte:
JSON produced for `--json` flags and other machine-parsed output is never
altered by the console owner, because its consumer is a parser, not a
terminal, and JSON encoding already bounds its own metacharacters. The
`--json` subcommand flags are the only machine-readable entry: the console
owner has no input side, so no in-dialog meta-command exists.
<!-- implements FR6 of make-run-headless -->

#### Scenario: JSON survives the console untouched
- **WHEN** a status report is printed with `--json` for a task whose title
  carries an escape sequence
- **THEN** the printed bytes equal the JSON mapper's output exactly, escape
  sequence included as the JSON string encoded it

#### Scenario: The in-dialog status meta-command takes the same path
- **WHEN** a build scans production code for a console read below the console owner
- **THEN** the only readers are the console owner's own stream wrapper and the takeover confirmation; no dialog interception exists to take any path

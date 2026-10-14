# Spec Delta: cli/operator-console

## MODIFIED Requirements

### Requirement: Human-readable console output is neutralized visibly
All non-logger text written to the operator's terminal SHALL pass through one
console owner. Its human-readable path SHALL render control and escape
sequences *visibly* rather than dropping them, while preserving line structure
and length, so an operator report of any length stays readable and an attempt
to drive the terminal is seen as text. The end-to-end property SHALL be
asserted over the same adversarial corpus the log sink uses.
**Implements**: FR5, NFR-S2, NFR-O1 of harden-untrusted-text-sinks

#### Scenario: A hostile issue title cannot reach the clipboard
- **WHEN** the board is rendered for a task whose tracker title carries an
  OSC 52 clipboard-write sequence
- **THEN** the title column shows `^[]52;…` as text and nothing is written to
  the operator's clipboard

#### Scenario: A multi-line report keeps its lines
- **WHEN** an escalation report of forty lines is printed for the operator
- **THEN** the forty lines are printed as forty lines, unchanged where they
  carried no control characters

#### Scenario: The corpus is inert on the human path
- **WHEN** the adversarial corpus is written through the console owner's
  human-readable path into a captured stream
- **THEN** the captured bytes contain no ESC, no C1 byte, no bidi override and
  no carriage return, and every original line break is preserved

#### Scenario: Each control class has its visible form
- **WHEN** text carrying each class of control character is written through the
  console owner's human-readable path
- **THEN** ESC is shown as `^[`, every C0 control other than the line feed and
  the tab in caret notation, DEL as `^?`, C1 controls and bidirectional-override
  and invisible format characters as their `\uXXXX` escape, and carriage return
  as `\r`
- **AND** the line feed and the tab are kept as themselves

### Requirement: Machine-readable console output is verbatim
The console owner's machine-readable path SHALL write its input byte for byte:
JSON produced for `--json` flags and other machine-parsed output is never
altered by the console owner, because its consumer is a parser, not a
terminal, and JSON encoding already bounds its own metacharacters. The
`--json` subcommand flags are the only machine-readable entry: the console
owner has no input side, so no in-dialog meta-command exists.
**Implements**: FR6 of make-run-headless

#### Scenario: JSON survives the console untouched
- **WHEN** a status report is printed with `--json` for a task whose title
  carries an escape sequence
- **THEN** the printed bytes equal the JSON mapper's output exactly, escape
  sequence included as the JSON string encoded it

#### Scenario: The in-dialog status meta-command takes the same path
- **WHEN** a build scans production code for a console read below the console owner
- **THEN** the only readers are the console owner's own stream wrapper and the takeover confirmation; no dialog interception exists to take any path

### Requirement: No direct process-stream writes in production code
No production class other than the console owner SHALL write to the process
standard output or standard error streams; a build gate SHALL fail naming any
new site. Every pre-existing direct write is routed through the owner and
classified as human-readable or machine-readable.
**Implements**: FR6 of harden-untrusted-text-sinks

#### Scenario: A new direct write fails the build
- **WHEN** a production class outside the console owner gains a
  `System.out`/`System.err` print call
- **THEN** the build fails naming the file and line

#### Scenario: The owner is the only survivor
- **WHEN** production sources are scanned for direct process-stream writes
- **THEN** every hit is inside the console owner

# Spec Delta: quality-gates

The deltas of the 47 other capabilities (their requirements rewritten in the new format and
within OpenSpec's limit) are generated and edited during apply — proposal, Capabilities; tasks
3 and 4. This delta holds the behaviors the gate itself adds.

## ADDED Requirements

### Requirement: Spec traceability line
Every requirement of a Requirement/Scenario spec under `openspec/specs/` or in the delta of an
unarchived change SHALL carry exactly one `**Implements**:` metadata line, naming the requirement
ids and the change that introduced them, after the requirement text and before its first
scenario. The `<!-- implements … -->` form SHALL NOT appear in those trees; archived changes are
untouched.
**Implements**: FR1 of tighten-spec-contract
**Notes**: The line is metadata for OpenSpec's reader, so it does not count toward its requirement-length limit; `openspec show --json` omits it from the requirement text, which is why the project's checker reads the files rather than the CLI's output.

#### Scenario: A requirement without the line fails the gate
- **WHEN** a requirement under `openspec/specs/` has no `**Implements**:` line
- **THEN** `checkSpecContract` fails, naming the spec file, the requirement heading and the line to add

#### Scenario: A retired comment form fails the gate
- **WHEN** a spec under `openspec/specs/` or an unarchived delta contains `<!-- implements`
- **THEN** `checkSpecContract` fails, naming the file and line and the metadata line that replaces it

#### Scenario: Two traceability lines fail the gate
- **WHEN** a requirement carries two `**Implements**:` lines
- **THEN** `checkSpecContract` fails, asking for one line with the ids joined by `;`

#### Scenario: Archived deltas are not read
- **WHEN** a delta under `openspec/changes/archive/` still carries the comment form
- **THEN** `checkSpecContract` reports nothing about it

### Requirement: Spec notes are one-line and non-normative
A `**Notes**:` metadata line of a requirement SHALL be one physical line that contains neither
`SHALL` nor `MUST`. A line of ordinary text directly after any metadata line SHALL be reported
as a wrapped note. Notes carry what explains a requirement and belongs nowhere else; a
normative statement is a requirement or a scenario.
**Implements**: FR2 of tighten-spec-contract

#### Scenario: A note with a normative word fails the gate
- **WHEN** a `**Notes**:` line contains the word `SHALL` or `MUST`
- **THEN** `checkSpecContract` fails, naming the file and line and asking for a scenario or a requirement instead

#### Scenario: A wrapped note fails the gate
- **WHEN** a `**Notes**:` line is followed by a line of ordinary text before the next scenario
- **THEN** `checkSpecContract` fails, naming the continuation line as a wrapped note

#### Scenario: Several one-line notes pass
- **WHEN** a requirement carries three consecutive `**Notes**:` lines, each one physical line without `SHALL` or `MUST`
- **THEN** `checkSpecContract` reports nothing about them

### Requirement: Scenario-to-test link
For every scenario of every requirement of a main spec, at least one test source SHALL contain
the marker `// Scenario: <spec-id> / <title>` with the title verbatim, and every such marker in
the test sources SHALL name a scenario that exists. A scenario verified by a mechanism that is
not a test SHALL be listed in the gate's exemption list with that mechanism, and the list is
asserted reached: an entry naming no scenario fails the gate.
**Implements**: FR5 of tighten-spec-contract
**Notes**: `<spec-id>` is the capability path OpenSpec lists (`quality-gates`, `verification/verification-hardening`); the title is the text after `#### Scenario:` with surrounding whitespace trimmed. Test sources are every module's `src/test` and the functional-test tree of `build-logic`.

#### Scenario: A scenario with no marker fails the gate
- **WHEN** a main spec holds a scenario whose title appears in no marker of any test source and in no exemption entry
- **THEN** `checkSpecContract` fails, naming the spec id, the title and the marker to write above the feature that exercises it

#### Scenario: A dangling marker fails the gate
- **WHEN** a test source carries a marker whose spec id or title names no scenario of a main spec
- **THEN** `checkSpecContract` fails, naming the test file and line and the nearest existing title

#### Scenario: A renamed scenario breaks the link on both sides
- **WHEN** a scenario title is changed in the main spec and its marker is not
- **THEN** `checkSpecContract` reports the new title as unlinked and the old marker as dangling in the same run

#### Scenario: An exempt scenario passes with its reason
- **WHEN** a scenario is verified by a CI workflow and the exemption list names the scenario and that workflow
- **THEN** `checkSpecContract` reports nothing about it

#### Scenario: A stale exemption fails the gate
- **WHEN** the exemption list names a scenario that no main spec holds
- **THEN** `checkSpecContract` fails, naming the entry to remove

#### Scenario: Scenarios of unarchived deltas are not linked
- **WHEN** a delta of an unarchived change holds a scenario with no marker anywhere
- **THEN** `checkSpecContract` reports nothing about it, with or without `--changes`

### Requirement: One spec-contract checker, two launchers
The spec-contract rules SHALL be implemented once, as a Gradle task `checkSpecContract` that the
root `check` task depends on. The manual launcher `scripts/check-spec-contract.sh` and the
verification commands of the fix-artifacts and archive stages SHALL run that task rather than a
second implementation; the task SHALL read files only and produce no subprocess.
**Implements**: FR6, NFR-R1, NFR-C1 of tighten-spec-contract

#### Scenario: Root check runs the gate
- **WHEN** a developer runs `./gradlew check` on a tree that violates one spec-contract rule
- **THEN** the build fails in `checkSpecContract` before any module's mutation gate is consulted

#### Scenario: The manual launcher is the same gate
- **WHEN** a developer runs `scripts/check-spec-contract.sh --changes` on that tree
- **THEN** the output names the same findings as the Gradle task, plus those of the unarchived deltas for the traceability-line and notes rules

#### Scenario: Findings read like the other gates
- **WHEN** the gate fails
- **THEN** every finding names the file, the line, the rule and what to write instead, and the run ends with one summary line of scenarios checked, linked and exempt

#### Scenario: The gate is cacheable
- **WHEN** `./gradlew check` runs twice with no change under `openspec/` or any test tree
- **THEN** the second `checkSpecContract` is reported up to date

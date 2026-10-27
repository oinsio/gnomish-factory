## ADDED Requirements

### Requirement: Scenario exemption list
A scenario verified by a mechanism that is not a test SHALL be listed in the gate's exemption
list with that mechanism, and the list SHALL be asserted reached: an entry naming no scenario
of a main spec fails the gate.
**Implements**: FR5 of tighten-spec-contract; FR6 of fix-terminal-receipt-convergence; FR2 of split-long-spec-requirements
**Notes**: The form of the list and its file names are in `testing.md`, "Every scenario has a test".

#### Scenario: An exemption entry names its mechanism
- **WHEN** the exemption list names a scenario and the mechanism that verifies it (a CI workflow, a build script)
- **THEN** `checkSpecContract` reports nothing about the scenario and counts it as exempt in its summary line

#### Scenario: An entry naming no scenario fails the gate
- **WHEN** an exemption file carries a line whose title no scenario of its capability has
- **THEN** `checkSpecContract` fails, naming the file, the line and the entry to remove

### Requirement: Merged main specs are previewed before the archive
A preview SHALL archive one unarchived change in a scratch copy of the spec tree with the
OpenSpec CLI and run OpenSpec's strict validation over the merged main specs there, leaving the
repository untouched and removing the copy on every exit; the fix-artifacts stage SHALL run it
as a verification command and fail the round, naming the spec, when the merged specs would not
validate.
**Implements**: FR4, FR5, FR6, NFR-R1, NFR-R2, NFR-S1, NFR-C1, UX1 of split-long-spec-requirements
**Notes**: The preview is `scripts/check-merged-specs.sh <change>`; a developer runs it by hand before `/opsx:archive` (`docs/guides/developer-guide.md`).
**Notes**: OpenSpec measures a MODIFIED block only after the merge (its `validate <change>` leaves MODIFIED alone), which is the gap the preview closes; the project's own checker still measures nothing (NG2 of tighten-spec-contract).

#### Scenario: A MODIFIED requirement grown past the limit fails the preview
- **WHEN** a delta's MODIFIED block measures over 500 characters while `openspec validate <change> --strict` passes
- **THEN** the preview exits 1, printing the spec id and OpenSpec's own warning line, and the fix-artifacts round fails before its artifacts are frozen

#### Scenario: A passing preview leaves no trace
- **WHEN** the merged main specs validate strictly
- **THEN** the preview exits 0, nothing under `openspec/` has changed, and no scratch copy remains

#### Scenario: A delta that no longer applies fails the preview
- **WHEN** the archive in the scratch copy refuses the change (a MODIFIED header the main spec no longer holds)
- **THEN** the preview exits 1 with the archive's own words, and nothing under `openspec/` has changed

#### Scenario: A preview without the CLI fails closed
- **WHEN** the OpenSpec CLI cannot be run, or the name given is not an active change
- **THEN** the preview exits 1 naming the cause, never 0

#### Scenario: The fix-artifacts round runs the preview after the change's own validation
- **WHEN** the fix-artifacts stage verifies a round
- **THEN** one verification command runs the preview over the task's change, after `openspec validate <change> --strict`, and its failure message names the script and the rule file to read

## MODIFIED Requirements

### Requirement: Scenario-to-test link
For every scenario of every requirement of a main spec, except a requirement that the delta of
an unarchived change REMOVES, at least one test source SHALL contain the marker
`// Scenario: <spec-id> / <title>` with the title verbatim, and every such marker in the test
sources SHALL name a scenario that exists under a main-spec requirement or under an ADDED or
MODIFIED requirement of the delta of an unarchived change.
**Implements**: FR5 of tighten-spec-contract; FR6 of fix-terminal-receipt-convergence; FR1, FR2 of split-long-spec-requirements
**Notes**: `<spec-id>` is the capability path OpenSpec lists (`quality-gates`, `verification/verification-hardening`); the title is the text after `#### Scenario:` with surrounding whitespace trimmed. Test sources are every module's `src/test` (`*/src/test`, `*/*/src/test`), the test trees of the `build-checks` included build (`build-checks/*/src/test`) and `build-logic/src/functionalTest`.
**Notes**: A marker written with its test resolves through the change's delta until the archive merges the scenario into the main spec; the archive's own gate run then resolves it there, with no edit to the marker.
**Notes**: A REMOVED requirement retires the main-spec requirement of the same title in the same capability path (`delta-specs.md`). Retirement only waives the link: a marker or exemption line that still names a retired scenario is neither dangling nor stale until the archive moves the scenario out of the main spec, when the archive's own gate run reports it. A delta that removes a requirement whose tests still run (a change proposed but not yet implemented) therefore turns nothing red.
**Notes**: A scenario verified by something other than a test is the requirement "Scenario exemption list".

#### Scenario: A scenario with no marker fails the gate
- **WHEN** a main spec holds a scenario, under a requirement no unarchived delta removes, whose title appears in no marker of any test source and in no exemption entry
- **THEN** `checkSpecContract` fails, naming the spec id, the title and the marker to write above the feature that exercises it

#### Scenario: A dangling marker fails the gate
- **WHEN** a test source carries a marker whose spec id or title names no scenario of a main spec and no scenario of an ADDED or MODIFIED requirement in the delta of an unarchived change
- **THEN** `checkSpecContract` fails, naming the test file and line and the nearest existing title

#### Scenario: A marker naming an unarchived delta's scenario is not dangling
- **WHEN** a test source carries a marker whose spec id and title name a scenario that exists only under an ADDED or MODIFIED requirement in the delta of an unarchived change
- **THEN** `checkSpecContract` reports nothing about the marker, with or without `--changes`

#### Scenario: A marker naming a removed or archived scenario is dangling
- **WHEN** a test source carries a marker whose title exists only under a REMOVED requirement of an unarchived delta, or only in a delta under `openspec/changes/archive/`
- **THEN** `checkSpecContract` fails, reporting the marker as dangling

#### Scenario: A malformed marker fails the gate
- **WHEN** a test source carries a `// Scenario:` line that is not in the `<spec-id> / <title>` form
- **THEN** `checkSpecContract` fails, naming the test file and line and the form to write

#### Scenario: A renamed scenario breaks the link on both sides
- **WHEN** a scenario title is changed in the main spec and its marker is not
- **THEN** `checkSpecContract` reports the new title as unlinked and the old marker as dangling in the same run

#### Scenario: An exempt scenario passes with its reason
- **WHEN** a scenario is verified by a CI workflow and the exemption list names the scenario and that workflow
- **THEN** `checkSpecContract` reports nothing about it

#### Scenario: A stale exemption fails the gate
- **WHEN** the exemption list names a scenario that no main spec holds
- **THEN** `checkSpecContract` fails, naming the entry to remove

#### Scenario: A scenario of a requirement removed by an unarchived delta need not be linked
- **WHEN** a main spec holds a scenario with no marker and no exemption entry, and the delta of an unarchived change removes its requirement by title
- **THEN** `checkSpecContract` reports nothing about the scenario, with or without `--changes`
- **AND** a delta under `openspec/changes/archive/` that removes a requirement of the same title retires nothing

#### Scenario: A marker or exemption naming a retired scenario is accepted
- **WHEN** a test source carries a marker, or the exemption list carries an entry, naming a scenario that a main spec holds only under a requirement an unarchived delta removes
- **THEN** `checkSpecContract` reports nothing about the marker or the entry, with or without `--changes`

#### Scenario: Scenarios of unarchived deltas are not linked
- **WHEN** a delta of an unarchived change holds a scenario with no marker anywhere
- **THEN** `checkSpecContract` reports nothing about it, with or without `--changes`

Amended by design D14 of fix-terminal-receipt-convergence: the Docker client configuration
variables are replaced by the build's own (the ADDED requirement "Forked test JVMs use the
build's own Docker client configuration"), so "Only the factory's own namespace is removed" now
excepts `DOCKER_CONFIG` and `DOCKER_CONTEXT`; every other scenario is unchanged.

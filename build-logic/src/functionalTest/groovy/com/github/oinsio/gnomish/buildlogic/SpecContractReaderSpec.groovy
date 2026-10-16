package com.github.oinsio.gnomish.buildlogic

import spock.lang.Specification

/**
 * Pins the structural reading of {@link SpecContractReader} and {@link ScenarioMarkerReader}
 * (NFR-R3 of tighten-spec-contract): one feature per shape OpenSpec documents, so a difference
 * from the installed CLI's reading is found here, not by a red archive stage. Plain Spock: the
 * readers are data parsers and need no TestKit.
 *
 * <p>This file lives in a tree the scenario gate scans, so a test-source fixture spells the
 * marker through {@link #MARKER} — a source line that starts with the marker itself would be read
 * as one of this suite's own links.
 */
class SpecContractReaderSpec extends Specification {

    static final String MARKER = '/' + '/ Scenario:'

    def "FR1: a metadata-only body is read as the requirement's metadata, its scenarios after it"() {
        when:
        def document = SpecContractReader.readSpec('''\
# demo

## ADDED Requirements

### Requirement: Short one
**Implements**: FR1 of some-change; FR2 of other-change

####   Scenario:   Works
- **WHEN** it runs
- **THEN** it works
''')
        def requirement = document.requirements.first()

        then:
        document.requirements.size() == 1
        requirement.section == 'ADDED Requirements'
        requirement.name == 'Short one'
        requirement.line == 5
        requirement.metadata()*.number == [6]
        requirement.metadata()*.metadataKey() == ['Implements']
        requirement.metadata()*.metadataValue() == ['FR1 of some-change; FR2 of other-change']
        requirement.body*.kind == [SpecDocument.Kind.METADATA, SpecDocument.Kind.BLANK]
        requirement.scenarios.collect { [it.line, it.title] } == [[8, 'Works']]
    }

    def "FR5: a fenced #### Scenario: is not a scenario, and nothing inside a fence is structure"() {
        when:
        def document = SpecContractReader.readSpec('''\
### Requirement: With an example
The text.
**Implements**: FR5 of some-change

~~~~markdown
### Requirement: Not a block
**Implements**: not metadata
#### Scenario: Not a scenario
```
still fenced: a shorter or different run does not close it
~~~~

#### Scenario: The real one
- **THEN** it counts
''')
        def requirement = document.requirements.first()

        then:
        document.requirements*.name == ['With an example']
        requirement.scenarios*.title == ['The real one']
        requirement.metadata()*.number == [3]
        requirement.body.findAll { it.kind == SpecDocument.Kind.FENCED }*.number == (5..11).toList()
        document.lines[11].kind == SpecDocument.Kind.BLANK
    }

    def "FR2: a ### divider ends the body, and a wrapped metadata continuation is ordinary text"() {
        when:
        def document = SpecContractReader.readSpec('''\
## Requirements

### Requirement: First
The first behavior.
**Notes**: one note that wraps
onto a second physical line.
### Unrelated divider
**Implements**: belongs to no requirement

### Requirement: Second
**Implements**: FR2 of some-change
''')

        then:
        document.requirements*.name == ['First', 'Second']
        document.requirements*.section == ['Requirements', 'Requirements']
        document.requirements[0].body.collect { [it.number, it.kind] } == [
            [4, SpecDocument.Kind.TEXT],
            [5, SpecDocument.Kind.METADATA],
            [6, SpecDocument.Kind.TEXT],
        ]
        document.requirements[0].metadata()*.metadataKey() == ['Notes']
        document.requirements[0].scenarios.isEmpty()
        document.lines[7].kind == SpecDocument.Kind.METADATA
        document.requirements[1].metadata()*.number == [11]
    }

    def "FR5: a marker inside a block comment is read like one on its own line"() {
        when:
        def markers = ScenarioMarkerReader.read([
            'class SomeSpec {',
            "    ${MARKER} quality-gates / A scenario with no marker fails the gate",
            '    /*',
            "     ${MARKER} verification/verification-hardening / Title / with a slash  ",
            "     * ${MARKER} quality-gates / Inside a javadoc-style run",
            '     */',
            "    /* ${MARKER} quality-gates / Opened on the same line */",
            "    def x = 1 ${MARKER} quality-gates / Trailing code is not a marker",
            '}',
        ].join('\n'))

        then:
        markers.malformed.isEmpty()
        markers.markers.collect { [it.line, it.specId, it.title] } == [
            [2, 'quality-gates', 'A scenario with no marker fails the gate'],
            [4, 'verification/verification-hardening', 'Title / with a slash'],
            [5, 'quality-gates', 'Inside a javadoc-style run'],
            [7, 'quality-gates', 'Opened on the same line'],
        ]
    }

    def "FR5: a // Scenario: line not in the <spec-id> / <title> form is reported as malformed"() {
        when:
        def markers = ScenarioMarkerReader.read([
            "${MARKER} Empty queue — listReady has nothing eligible; clean no-op",
            "${MARKER} quality-gates /",
            "${MARKER} Quality-Gates / Upper case is not a spec id",
            "${MARKER} quality-gates/No spaces around the slash",
            "${MARKER} quality-gates / Well formed",
        ].join('\n'))

        then:
        markers.markers.collect { [it.line, it.title] } == [[5, 'Well formed']]
        markers.malformed*.line == [1, 2, 3, 4]
        markers.malformed[0].text == "${MARKER} Empty queue — listReady has nothing eligible; clean no-op".toString()
    }
}

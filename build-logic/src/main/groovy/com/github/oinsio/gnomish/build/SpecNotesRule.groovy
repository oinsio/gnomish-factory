package com.github.oinsio.gnomish.build

import java.util.regex.Pattern

/**
 * The notes rules of a Requirement/Scenario spec (FR2 of tighten-spec-contract, design D2): a
 * {@code **Notes**:} line carries no normative word, and every note is one physical line.
 *
 * <p>OpenSpec reads a metadata line as one line: a wrapped continuation is ordinary text, so it
 * lands in the requirement's measured text and reads as part of the requirement. So a line of
 * ordinary text directly after any metadata line of a requirement body is reported as a wrapped
 * note. A blank line, another metadata line or a fenced block after it is not.
 *
 * <p>Implements FR2 of tighten-spec-contract.
 */
final class SpecNotesRule {

    static final String KEY = 'Notes'
    static final Pattern NORMATIVE = ~/\b(SHALL|MUST)\b/

    private SpecNotesRule() {
    }

    static List<SpecContractFinding> judge(SpecFile spec) {
        List<SpecContractFinding> findings = []
        spec.document.requirements.findAll { TraceabilityLineRule.judged(it) }.each { SpecDocument.Requirement requirement ->
            requirement.body.findAll { it.metadataKey() == KEY && NORMATIVE.matcher(it.text).find() }.each {
                findings << new SpecContractFinding(path: spec.path, line: it.number, rule: SpecContractFinding.NOTES,
                        what: "a note carries a normative word: '${it.text}'".toString(),
                        fix: 'state the obligation as a scenario of this requirement, or as a requirement of its own; '
                                + 'a note only explains, without SHALL or MUST')
            }
            requirement.body.eachWithIndex { SpecDocument.Line line, int index ->
                if (index > 0 && line.kind == SpecDocument.Kind.TEXT
                        && requirement.body[index - 1].kind == SpecDocument.Kind.METADATA) {
                    findings << new SpecContractFinding(path: spec.path, line: line.number,
                            rule: SpecContractFinding.WRAPPED_NOTE,
                            what: "ordinary text directly after a metadata line: '${line.text}'".toString(),
                            fix: 'join it to the metadata line above it as one physical line, '
                                    + 'or move it into the requirement text before the metadata lines')
                }
            }
        }
        findings
    }
}

package com.github.oinsio.gnomish.build

import java.util.regex.Pattern

/**
 * The traceability rules of a Requirement/Scenario spec (FR1 of tighten-spec-contract, design
 * D1): every requirement carries exactly one {@code **Implements**: <ids> of <change>[; …]}
 * metadata line, and the retired {@code <!-- implements … -->} form appears nowhere in the file.
 *
 * <p>A requirement under a {@code REMOVED} or {@code RENAMED} section of a delta is not judged:
 * it states no behavior of its own, so there is nothing to trace. The YAML-like Entity and
 * Use-case formats hold no {@code ### Requirement:} block and so are never judged here.
 *
 * <p>Implements FR1 of tighten-spec-contract.
 */
final class TraceabilityLineRule {

    static final String KEY = 'Implements'
    static final String LINE_FORM = '**Implements**: <ids> of <change-name>[; <ids> of <change-name>…]'
    static final Pattern RETIRED = ~/(?i)<!--\s*implements/
    static final Pattern PART = ~/^\S.* of [a-z0-9]+(?:-[a-z0-9]+)*$/

    private TraceabilityLineRule() {
    }

    static List<SpecContractFinding> judge(SpecFile spec) {
        List<SpecContractFinding> findings = retiredComments(spec)
        spec.document.requirements.findAll { judged(it) }.each { SpecDocument.Requirement requirement ->
            List<SpecDocument.Line> lines = requirement.metadata().findAll { it.metadataKey() == KEY }
            if (lines.isEmpty()) {
                findings << finding(spec, requirement.line,
                        "requirement '${requirement.name}' has no **Implements**: line",
                        "add `${LINE_FORM}` after the requirement text, before its first scenario")
            }
            lines.drop(1).each { SpecDocument.Line extra ->
                findings << finding(spec, extra.number,
                        "requirement '${requirement.name}' carries a second **Implements**: line: '${extra.text}'",
                        "keep one **Implements**: line, the ids of every change joined by `;`")
            }
            lines.take(1).findAll { !wellFormed(it.metadataValue()) }.each { SpecDocument.Line line ->
                findings << finding(spec, line.number,
                        "requirement '${requirement.name}' has a traceability line not in the form: '${line.text}'",
                        "write `${LINE_FORM}`")
            }
        }
        findings
    }

    /** Whether a requirement states behavior to trace: not a REMOVED or RENAMED delta block. */
    static boolean judged(SpecDocument.Requirement requirement) {
        String section = requirement.section ?: ''
        !(section.startsWith('REMOVED') || section.startsWith('RENAMED'))
    }

    private static boolean wellFormed(String value) {
        !value.isEmpty() && value.split(';').every { PART.matcher(it.trim()).matches() }
    }

    private static List<SpecContractFinding> retiredComments(SpecFile spec) {
        spec.document.lines.findAll { RETIRED.matcher(it.text).find() }.collect { SpecDocument.Line line ->
            new SpecContractFinding(path: spec.path, line: line.number, rule: SpecContractFinding.RETIRED_COMMENT,
                    what: "retired traceability comment: '${line.text.trim()}'",
                    fix: "delete the comment and carry its ids in the requirement's `${LINE_FORM}` line")
        }
    }

    private static SpecContractFinding finding(SpecFile spec, int line, String what, String fix) {
        new SpecContractFinding(path: spec.path, line: line, rule: SpecContractFinding.TRACEABILITY_LINE,
                what: what, fix: fix)
    }
}

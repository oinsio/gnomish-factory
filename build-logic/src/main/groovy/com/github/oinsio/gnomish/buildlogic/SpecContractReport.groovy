package com.github.oinsio.gnomish.buildlogic

/**
 * The words of {@link SpecContractCheck}'s verdict, in the shape of the other gates (UX1, NFR-O1
 * of tighten-spec-contract): a headline, one block per finding ({@code file:line: [rule] what},
 * then what to write instead), the rule files to read, and one summary line last — scenarios
 * checked, linked and exempt. Never a stack trace.
 *
 * <p>Implements NFR-O1 and UX1 of tighten-spec-contract.
 */
final class SpecContractReport {

    static final String RULE_FILES = '.claude/rules/traceability.md and .claude/rules/delta-specs.md'

    private SpecContractReport() {
    }

    static String summary(ScenarioLinkRule.Verdict links) {
        String pending = links.pending > 0 ? " (${links.pending} pending-test)" : ''
        "Spec contract: ${links.checked} scenario(s) checked, ${links.linked} linked, ${links.exempt} exempt${pending}."
    }

    static String failure(List<SpecContractFinding> findings, ScenarioLinkRule.Verdict links) {
        """\
The spec contract is broken (${findings.size()} finding(s)):

${findings*.render().join('\n')}

Each finding names the rule it breaks and what to write instead; the rules are in ${RULE_FILES}.
${summary(links)}"""
    }

    static void write(File report, List<SpecContractFinding> findings, ScenarioLinkRule.Verdict links) {
        report.parentFile.mkdirs()
        report.setText((findings*.render() + [summary(links)]).join('\n') + '\n', 'UTF-8')
    }
}

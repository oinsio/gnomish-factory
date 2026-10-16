package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import spock.lang.Specification

/**
 * The 200-line hard cap of {@code .claude/rules/process-invariants.md} over production Java sources
 * (FR1, FR2 of fix-oversized-adapters) is a review rule, not a build gate: the implementation audit
 * checks the files a change touches against it, and the rule asks for a split along a responsibility
 * seam. This spec pins that the rule and the review that applies it both say so.
 */
class SourceFileSizeCapSpec extends Specification {

    static final String RULE = '.claude/rules/process-invariants.md'

    static final String AUDIT = '.claude/commands/audit-implementation.md'

    // F114: scenario text aligned to what this test asserts
    // Scenario: build-system/quality-gates / An oversized adapter is split into compliant units
    def "FR1, FR2: the implementation audit checks a change's files against the rule's 200-line cap, and the rule asks for a responsibility split"() {
        given:
        def rule = Files.readString(RepoSourceTree.repoRoot().resolve(RULE)).replaceAll(/\s+/, ' ')
        def audit = Files.readString(RepoSourceTree.repoRoot().resolve(AUDIT))
        def conformance = audit.substring(audit.indexOf('Project-rule conformance'))

        expect: 'the rule states the hard cap and the remedy'
        rule.contains('200 is a hard cap')
        rule.contains('splitting a file must split a responsibility')

        and: 'the audit\'s conformance step applies it to the files of the change'
        conformance.contains('Check the new/modified files')
        conformance.contains("`process-invariants.md`: file size (target 100–120 lines, hard cap 200)")
    }
}

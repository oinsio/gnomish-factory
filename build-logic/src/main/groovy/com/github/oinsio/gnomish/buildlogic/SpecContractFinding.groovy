package com.github.oinsio.gnomish.buildlogic

import groovy.transform.Immutable

/**
 * One violation of a spec-contract rule, in the wording the other gates use
 * ({@link TestTimeInjectionCheck}): where it is ({@code file:line}), which rule, what was found,
 * and what to write instead (UX1, NFR-O1 of tighten-spec-contract). Text taken from a scanned
 * file is quoted verbatim inside {@code what}: it is data, never evaluated (NFR-S1).
 *
 * <p>Implements NFR-O1, NFR-S1 and UX1 of tighten-spec-contract.
 */
@Immutable
class SpecContractFinding implements Comparable<SpecContractFinding> {

    /** The rules a finding names; each spelling is what an author greps the rule file for. */
    static final String TRACEABILITY_LINE = 'traceability line'
    static final String RETIRED_COMMENT = 'retired comment form'
    static final String NOTES = 'notes'
    static final String WRAPPED_NOTE = 'wrapped note'
    static final String UNLINKED_SCENARIO = 'scenario link: unlinked scenario'
    static final String DANGLING_MARKER = 'scenario link: dangling marker'
    static final String MALFORMED_MARKER = 'scenario link: malformed marker'
    static final String EXEMPTION = 'scenario link: exemption'

    /** The file, relative to the project directory: a spec, a test source or an exemption file. */
    String path

    /** The 1-based line, or 0 when the finding is about a whole file (an exemption file's name). */
    int line

    String rule
    String what
    String fix

    String render() {
        String where = line > 0 ? "${path}:${line}" : path
        "${where}: [${rule}] ${what}\n    fix: ${fix}"
    }

    @Override
    int compareTo(SpecContractFinding other) {
        path <=> other.path ?: line <=> other.line ?: rule <=> other.rule ?: what <=> other.what
    }
}

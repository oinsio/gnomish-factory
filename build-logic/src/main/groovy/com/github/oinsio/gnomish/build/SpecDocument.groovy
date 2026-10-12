package com.github.oinsio.gnomish.build

import groovy.transform.Immutable

/**
 * One Requirement/Scenario spec file as {@link SpecContractReader} reads it: its lines, each
 * classified by the structure OpenSpec documents, and its requirement blocks. Line numbers are
 * 1-based and kept on every value, so a finding can name {@code file:line}.
 *
 * <p>Structure only (design D6 of tighten-spec-contract): nothing here counts characters or
 * scenarios, or judges a rule; {@code SpecContractCheck} judges the project's rules on this model.
 *
 * <p>Implements FR1, FR2, FR5 and NFR-R3 of tighten-spec-contract.
 */
@Immutable
class SpecDocument {

    /** Every line of the file, in order. */
    List<Line> lines

    /** Every {@code ### Requirement:} block, in order. */
    List<Requirement> requirements

    /** What a line is, judged by structure alone; a line inside a fenced block is {@code FENCED}. */
    enum Kind {
        BLANK, TEXT, METADATA, HEADING, FENCED
    }

    /** One physical line. Implements FR1, FR2 of tighten-spec-contract. */
    @Immutable
    static class Line {
        int number
        String text
        Kind kind

        /** The key of a metadata line ({@code Implements} for {@code **Implements**: …}), else null. */
        String metadataKey() {
            kind == Kind.METADATA ? SpecContractReader.metadataKey(text) : null
        }

        /** The text after a metadata line's {@code **Key**:}, trimmed; null for any other line. */
        String metadataValue() {
            kind == Kind.METADATA ? text.substring(text.indexOf('**:') + 3).trim() : null
        }
    }

    /** A {@code #### Scenario:} heading; {@code title} is the text after it, trimmed. Implements FR5. */
    @Immutable
    static class Scenario {
        int line
        String title
    }

    /**
     * One {@code ### Requirement:} block. {@code section} is the enclosing {@code ##} heading's
     * text ({@code Requirements}, {@code ADDED Requirements}, {@code REMOVED Requirements}), null
     * when none precedes it. {@code body} holds the lines after the heading up to the first
     * {@code ####} heading or the end of the block — the text OpenSpec measures, with its
     * metadata, blank and fenced lines kept and classified. The block ends at the next heading of
     * level one to three. Implements FR1, FR2, FR5 of tighten-spec-contract.
     */
    @Immutable
    static class Requirement {
        String section
        int line
        String name
        List<Line> body
        List<Scenario> scenarios

        /** The body's metadata lines, in order. */
        List<Line> metadata() {
            body.findAll { it.kind == Kind.METADATA }
        }
    }
}

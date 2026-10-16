package com.github.oinsio.gnomish.buildlogic

import groovy.transform.Immutable
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Reads one test source into its scenario markers: {@code // Scenario: <spec-id> / <title>}, the
 * one spelling of the link from a test to the scenario it exercises (design D4 of
 * tighten-spec-contract; proposal Q1, fixed here).
 *
 * <p><b>What is a marker line.</b> A line whose text, after leading whitespace and an optional
 * block-comment lead ({@code /*} or {@code *}), starts with {@code // Scenario:}. So a marker
 * inside a {@code /* … *}{@code /} block counts like one on its own line — the marker is a comment
 * in every language a test tree holds, and the comment around it changes nothing. A
 * {@code // Scenario:} trailing code on the same line is not a marker: the marker sits above the
 * feature it names. A block comment closed on the marker's own line leaves its closing
 * {@code *}{@code /} out of the title. The spelling is matched case-sensitively.
 *
 * <p><b>Well-formed or malformed.</b> The rest of a marker line must be a spec id — the
 * capability path OpenSpec lists, lowercase segments of letters, digits and dashes joined by
 * {@code /} — then {@code " / "}, then a non-blank title, trimmed. Anything else on a marker line
 * (the informal {@code // Scenario: Empty queue — …} comments, a missing spec id) is reported as
 * {@link Malformed}, so no second spelling of the link survives.
 *
 * <p>The text is data (NFR-S1): matched against fixed patterns, never evaluated.
 *
 * <p>Implements FR5, NFR-R3 and NFR-S1 of tighten-spec-contract.
 */
final class ScenarioMarkerReader {

    /** The marker's fixed lead, for a finding that names the form to write. */
    static final String PREFIX = '// Scenario:'
    static final Pattern MARKER_LINE = ~/^\s*(?:\/\*+\s*|\*\s*)?\/\/\s*Scenario:(.*)$/
    static final String BLOCK_CLOSE = /\s*\*+\/\s*$/
    /** A spec id: kebab-case segments joined by {@code /}, the capability path under {@code openspec/specs}. */
    static final String SPEC_ID = /[a-z0-9]+(?:-[a-z0-9]+)*(?:\/[a-z0-9]+(?:-[a-z0-9]+)*)*/
    static final Pattern WELL_FORMED = ~/^\s*(${SPEC_ID}) \/ (.*\S.*)$/

    private ScenarioMarkerReader() {
    }

    static Markers read(File file) {
        read(file.getText('UTF-8'))
    }

    static Markers read(String text) {
        List<Marker> markers = []
        List<Malformed> malformed = []
        text.readLines().eachWithIndex { String line, int index ->
            Matcher marker = MARKER_LINE.matcher(line)
            if (!marker.matches()) {
                return
            }
            Matcher form = WELL_FORMED.matcher(marker.group(1).replaceFirst(BLOCK_CLOSE, ''))
            if (form.matches()) {
                markers << new Marker(line: index + 1, specId: form.group(1), title: form.group(2).trim())
            } else {
                malformed << new Malformed(line: index + 1, text: line.trim())
            }
        }
        new Markers(markers: markers, malformed: malformed)
    }

    /** The markers of one test source, in order. Implements FR5 of tighten-spec-contract. */
    @Immutable
    static class Markers {
        List<Marker> markers
        List<Malformed> malformed
    }

    /** A well-formed marker naming {@code <specId> / <title>}. Implements FR5. */
    @Immutable
    static class Marker {
        int line
        String specId
        String title
    }

    /** A {@code // Scenario:} line not in the {@code <spec-id> / <title>} form. Implements FR5. */
    @Immutable
    static class Malformed {
        int line
        String text
    }
}

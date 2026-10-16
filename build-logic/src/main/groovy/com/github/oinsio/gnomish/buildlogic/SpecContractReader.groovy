package com.github.oinsio.gnomish.buildlogic

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Reads one Requirement/Scenario spec file into a {@link SpecDocument}: its requirement blocks,
 * each with its heading, its body lines up to the first {@code ####} heading, its metadata lines
 * and its scenario headings.
 *
 * <p><b>Structure, not rules</b> (design D6 of tighten-spec-contract). The reader recognises the
 * shapes OpenSpec documents and nothing more:
 * <ul>
 *   <li>a heading is {@code #} to {@code ######} followed by whitespace; one of level one to three
 *       ends a requirement block, so a {@code ###} divider ends the body before it;</li>
 *   <li>{@code ### Requirement: <name>} opens a block; the body runs to the first {@code ####}
 *       heading; {@code #### Scenario: <title>} is a scenario, its title trimmed;</li>
 *   <li>a metadata line matches {@code ^\*\*[^*]+\*\*:} — a wrapped continuation of one is
 *       ordinary text, as OpenSpec reads it;</li>
 *   <li>a fenced block ({@code ```} or {@code ~~~}, closed by a run of the same character at least
 *       as long) is masked: a heading or a metadata line inside one is {@code FENCED}, not
 *       structure.</li>
 * </ul>
 * It never counts characters, scenarios or normative words for OpenSpec's sake (proposal NG2).
 *
 * <p>The text is data (NFR-S1): it is matched against fixed patterns, never evaluated or passed
 * to a process.
 *
 * <p>Implements FR1, FR2, FR5, NFR-R3 and NFR-S1 of tighten-spec-contract.
 */
final class SpecContractReader {

    static final Pattern HEADING = ~/^(#{1,6})\s+(.*)$/
    static final Pattern METADATA = ~/^\*\*([^*]+)\*\*:/
    static final Pattern REQUIREMENT = ~/^###\s+Requirement:(.*)$/
    static final Pattern SCENARIO = ~/^####\s+Scenario:(.*)$/
    static final Pattern FENCE = ~/^(`{3,}|~{3,})/

    private SpecContractReader() {
    }

    static SpecDocument readSpec(File file) {
        readSpec(file.getText('UTF-8'))
    }

    static SpecDocument readSpec(String text) {
        List<SpecDocument.Line> lines = classify(text.readLines())
        new SpecDocument(lines: lines, requirements: blocks(lines))
    }

    /** The key of a line matching {@link #METADATA}, else null. */
    static String metadataKey(String text) {
        Matcher matcher = METADATA.matcher(text)
        matcher.find() ? matcher.group(1) : null
    }

    private static List<SpecDocument.Line> classify(List<String> raw) {
        List<SpecDocument.Line> lines = []
        String fence = null
        raw.eachWithIndex { String text, int index ->
            String trimmed = text.trim()
            SpecDocument.Kind kind
            if (fence != null) {
                kind = SpecDocument.Kind.FENCED
                if (closes(trimmed, fence)) {
                    fence = null
                }
            } else if (FENCE.matcher(trimmed).find()) {
                Matcher opening = FENCE.matcher(trimmed)
                opening.find()
                fence = opening.group(1)
                kind = SpecDocument.Kind.FENCED
            } else {
                kind = kindOf(text, trimmed)
            }
            lines << new SpecDocument.Line(number: index + 1, text: text, kind: kind)
        }
        lines
    }

    private static boolean closes(String trimmed, String fence) {
        trimmed.length() >= fence.length() && trimmed.every { it == fence[0] }
    }

    private static SpecDocument.Kind kindOf(String text, String trimmed) {
        if (trimmed.isEmpty()) {
            return SpecDocument.Kind.BLANK
        }
        if (HEADING.matcher(text).matches()) {
            return SpecDocument.Kind.HEADING
        }
        METADATA.matcher(text).find() ? SpecDocument.Kind.METADATA : SpecDocument.Kind.TEXT
    }

    private static List<SpecDocument.Requirement> blocks(List<SpecDocument.Line> lines) {
        List<SpecDocument.Requirement> requirements = []
        String section = null
        Map open = null
        lines.each { SpecDocument.Line line ->
            int level = line.kind == SpecDocument.Kind.HEADING ? headingLevel(line.text) : 0
            if (level in 1..3) {
                close(open, requirements)
                open = null
                if (level == 2) {
                    section = headingText(line.text)
                }
                Matcher requirement = REQUIREMENT.matcher(line.text)
                if (level == 3 && requirement.matches()) {
                    open = [section: section, line: line.number, name: requirement.group(1).trim(),
                            body: [], scenarios: [], inBody: true]
                }
            } else if (open != null && level == 4) {
                open.inBody = false
                Matcher scenario = SCENARIO.matcher(line.text)
                if (scenario.matches()) {
                    open.scenarios << new SpecDocument.Scenario(line: line.number, title: scenario.group(1).trim())
                }
            } else if (open != null && open.inBody) {
                open.body << line
            }
        }
        close(open, requirements)
        requirements
    }

    private static void close(Map open, List<SpecDocument.Requirement> requirements) {
        if (open != null) {
            requirements << new SpecDocument.Requirement(section: open.section, line: open.line,
                    name: open.name, body: open.body, scenarios: open.scenarios)
        }
    }

    private static int headingLevel(String text) {
        Matcher matcher = HEADING.matcher(text)
        matcher.matches() ? matcher.group(1).length() : 0
    }

    private static String headingText(String text) {
        Matcher matcher = HEADING.matcher(text)
        matcher.matches() ? matcher.group(2).trim() : null
    }
}

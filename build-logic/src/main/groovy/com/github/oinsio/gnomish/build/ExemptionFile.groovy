package com.github.oinsio.gnomish.build

import groovy.transform.Immutable
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * One capability's exemption file, {@code config/spec-contract-exemptions/<file>.txt} (FR5 of
 * tighten-spec-contract, design D5, D9): the syntax of one file and nothing more. Blank lines and
 * lines starting with {@code #} are skipped. The first other line is the header,
 * {@code spec: <spec-id>}, and it alone names the capability; the file name is that spec id with
 * every {@code /} replaced by {@code -}, plus {@code .txt}, a redundant spelling checked against the
 * header so a reader finds the file by the capability. Every later line is one entry,
 * {@code <scenario title> => <mechanism>}, where the mechanism names the verifier (a workflow file,
 * a script) or is {@code pending-test: <change-name>}.
 *
 * <p>Whether the spec id and the titles exist is not this class's question: {@link
 * ScenarioExemptions} judges that against the main specs. The text is data (NFR-S1).
 *
 * <p>Implements FR5 and NFR-S1 of tighten-spec-contract.
 */
final class ExemptionFile {

    static final String PENDING = 'pending-test:'
    static final String ARROW = ' => '
    static final Pattern HEADER = ~/^spec:\s*(${ScenarioMarkerReader.SPEC_ID})\s*$/
    static final Pattern CHANGE_NAME = ~/^[a-z0-9]+(?:-[a-z0-9]+)*$/

    /** One well-formed entry. */
    @Immutable
    static class Entry {
        String title
        String mechanism
        int line
    }

    /** The file, relative to the project directory. */
    final String path

    /** The spec id the header declares, or null when the file has no valid header. */
    String specId

    /** The header's line, or 0 when the file has none. */
    int headerLine

    final List<Entry> entries = []

    /** Findings about the file itself: its name, its header. Judged in every run. */
    final List<SpecContractFinding> fileFindings = []

    /** Findings about one entry line. Judged only when the file's capability is in scope. */
    final List<SpecContractFinding> lineFindings = []

    private ExemptionFile(String path) {
        this.path = path
    }

    static ExemptionFile read(File base, File file) {
        ExemptionFile parsed = new ExemptionFile(SpecFile.relative(base, file))
        if (!file.name.endsWith('.txt')) {
            parsed.fileFindings << parsed.finding(0, "'${file.name}' is not an exemption file",
                    'name it `<spec-id with / replaced by ->.txt`, or move it out of the directory')
            return parsed
        }
        file.getText('UTF-8').readLines().eachWithIndex { String text, int index ->
            String line = text.trim()
            if (line && !line.startsWith('#')) {
                parsed.headerLine == 0 ? parsed.header(line, index + 1, file.name)
                        : parsed.entry(line, index + 1)
            }
        }
        if (parsed.headerLine == 0) {
            parsed.fileFindings << parsed.finding(0, 'the file names no capability',
                    'start it with the line `spec: <spec-id>`')
        }
        parsed
    }

    private void header(String line, int number, String fileName) {
        headerLine = number
        Matcher matcher = HEADER.matcher(line)
        if (!matcher.matches()) {
            fileFindings << finding(number, "the first line is not a header naming a capability: '${line}'",
                    'start the file with the line `spec: <spec-id>`')
            return
        }
        specId = matcher.group(1)
        String expected = specId.replace('/', '-') + '.txt'
        if (fileName != expected) {
            fileFindings << finding(number, "the file declares spec id '${specId}' but is named '${fileName}'",
                    "rename the file to `${expected}`, or correct the header")
        }
    }

    private void entry(String line, int number) {
        int arrow = line.indexOf(ARROW)
        String title = arrow < 0 ? '' : line.substring(0, arrow).trim()
        if (title.isEmpty()) {
            lineFindings << finding(number, "not an exemption entry: '${line}'",
                    "write `<scenario title>${ARROW}<mechanism>`, the title verbatim")
            return
        }
        String mechanism = line.substring(arrow + ARROW.length()).trim()
        Entry earlier = entries.find { it.title == title }
        if (earlier != null) {
            lineFindings << finding(number, "duplicate exemption '${title}', first at line ${earlier.line}",
                    'remove one of the two lines')
        } else if (mechanism.startsWith(PENDING) && !pendingChange(mechanism)) {
            lineFindings << finding(number, "exemption '${title}' names no verifier: '${mechanism}'",
                    "name the mechanism that verifies it (a workflow, a script) or `${PENDING} <change-name>`")
        } else {
            entries << new Entry(title: title, mechanism: mechanism, line: number)
        }
    }

    private static boolean pendingChange(String mechanism) {
        CHANGE_NAME.matcher(mechanism.substring(PENDING.length()).trim()).matches()
    }

    SpecContractFinding finding(int line, String what, String fix) {
        new SpecContractFinding(path: path, line: line, rule: SpecContractFinding.EXEMPTION,
                what: what.toString(), fix: fix.toString())
    }
}

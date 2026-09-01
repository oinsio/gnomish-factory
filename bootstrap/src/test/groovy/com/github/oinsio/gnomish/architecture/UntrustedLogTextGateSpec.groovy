package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.LogCallSites
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR6 of harden-logging-observability, design D9: attacker-influenced text reaches a log line only
 * through {@code com.github.oinsio.gnomish.logtext.LogText}, which strips control/ANSI sequences,
 * flattens newlines so one event stays one line, and caps length. Unwrapped, a subprocess's stderr
 * can forge a log record of its own, drive the operator's terminal with cursor escapes, or flood
 * the file — in a record other people read as evidence.
 *
 * <p>The gate enforces the reachable half of that rule: the accessors this codebase uses to read
 * untrusted output are recognizable by name, so their appearance in a log call's argument list is
 * checked to sit inside a {@code LogText.*(...)} wrapper. It cannot type-check "every untrusted
 * string" — a new untrusted accessor joins {@link #UNTRUSTED} in the same change that introduces
 * it, which is what {@code .claude/rules/logging.md} asks of the author.
 *
 * <p>Lives in {@code :bootstrap} for the same reason as {@link ProjectIdentityDerivationGateSpec}:
 * it is a whole-tree source gate, and this is the module whose {@code test} task wires
 * {@code repoRoot}.
 */
class UntrustedLogTextGateSpec extends Specification {

    /**
     * The accessors that yield attacker-influenced text in this codebase: subprocess and
     * in-container command output ({@code stderr}, {@code stdout}), Jackson's echo of the
     * offending untrusted bytes ({@code getOriginalMessage}), and the agent-CLI session banner
     * fields the agent process itself chooses ({@code sessionId}, {@code model}).
     */
    private static final Pattern UNTRUSTED =
    Pattern.compile('\\.(?:stderr|stdout|getOriginalMessage|sessionId|model)\\s*\\(\\s*\\)')

    // FR6: every untrusted accessor reaching a log line goes through the sanitizing choke point.
    def "untrusted text enters log lines only through LogText"() {
        given: 'every production source, with its comments removed'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the log call sites'
        LogCallSites.productionCalls().size() >= LogCallSites.KNOWN_LOG_CALLS

        when:
        def violations = sources.collectMany { file ->
            def code = RepoSourceTree.code(file)
            LogCallSites.inSource(code, RepoSourceTree.relative(file))
                    .findAll { unwrappedUntrusted(it.text) }
                    .collect { "${it.path}:${it.line}" }
        }

        then: 'the gate names every offending site'
        violations == []
    }

    // D9: the detector is the gate — a seeded violation must fail it, or a green run means nothing.
    def "a seeded raw #accessor violation is detected"() {
        given:
        def code = "log.warn(\"stage command failed: {}\", result.${accessor}());"

        expect:
        LogCallSites.inSource(code, 'Seeded.java').any {
            unwrappedUntrusted(it.text)
        }

        where:
        accessor << [
            'stderr',
            'stdout',
            'getOriginalMessage',
            'sessionId',
            'model'
        ]
    }

    // D9: the wrapped form is not flagged, including through an intermediate conversion — the
    //     sanitizer anywhere up the enclosing call chain is what the rule asks for.
    def "the wrapped form is not flagged: #call"() {
        expect:
        LogCallSites.inSource(call, 'Correct.java').every {
            !unwrappedUntrusted(it.text)
        }

        where:
        call << [
            'log.warn("push failed: {}", LogText.forLog(result.stderr()));',
            'log.debug("event {}", LogText.forLog(String.valueOf(started.sessionId())));',
            'log.warn("excerpt {}", LogText.forLog(e.getOriginalMessage(), CAP));'
        ]
    }

    /** True when the call reads untrusted text outside every {@code LogText} wrapper. */
    private static boolean unwrappedUntrusted(String call) {
        def matcher = UNTRUSTED.matcher(call)
        while (matcher.find()) {
            if (!sanitizedAt(call, matcher.start())) {
                return true
            }
        }
        false
    }

    /**
     * True when some call enclosing the offset is a {@code LogText.*} wrapper. Walks outward from
     * the occurrence, closing nested calls as it goes, so a conversion between the sanitizer and
     * the accessor ({@code LogText.forLog(String.valueOf(x))}) still counts as sanitized.
     */
    private static boolean sanitizedAt(String call, int offset) {
        def before = call.take(offset)
        int depth = 0
        for (int i = before.length() - 1; i >= 0; i--) {
            char c = before.charAt(i)
            if (c == ')' as char) {
                depth++
            } else if (c == '(' as char) {
                if (depth> 0) {
                    depth--
                } else if (calleeEndingAt(before, i).startsWith('LogText.')) {
                    return true
                }
            }
        }
        false
    }

    /** The dotted name immediately preceding the open paren at {@code index}. */
    private static String calleeEndingAt(String before, int index) {
        int start = index
        while (start> 0 && (Character.isLetterOrDigit(before.charAt(start - 1) as char)
                || before.charAt(start - 1) in ['.' as char, '_' as char])) {
            start--
        }
        before.substring(start, index)
    }
}

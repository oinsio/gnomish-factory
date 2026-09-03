package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.LogCallSites
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR7, M2 of harden-logging-observability, design D9: every log call site that reports an
 * exception passes it as the trailing throwable argument. Interpolating {@code toString()} or
 * {@code getMessage()} instead amputates the stack and the cause chain — and {@code getMessage()}
 * prints {@code null} for a cause-only throwable — so the site loses exactly the diagnosis it
 * exists to record.
 *
 * <p>A gate rather than review vigilance: the wrong form compiles, renders plausibly, and is only
 * discovered in the post-mortem where the stack is missing. The rejected alternative (a custom
 * Error Prone {@code BugChecker}, needing a build-logic subproject of its own) is recorded in
 * {@code docs/adr/0004-logging-policy.md}.
 *
 * <p>Lives in {@code :bootstrap} for the same reason as {@link ProjectIdentityDerivationGateSpec}:
 * it is a whole-tree source gate, and this is the module whose {@code test} task wires
 * {@code repoRoot}.
 */
class ThrowableConventionGateSpec extends Specification {

    /** The in-place escape hatch documented in {@code .claude/rules/logging.md}. */
    private static final String EXEMPTION = 'throwable-not-subject'

    /** Catch parameters and throwable-typed declarations: the names an exception can wear. */
    private static final Pattern EXCEPTION_NAME = Pattern.compile(
    'catch\\s*\\(\\s*(?:final\\s+)?[\\w.<>|\\s]+?\\s+(\\w+)\\s*\\)' +
    '|\\b\\w*(?:Exception|Error|Throwable)\\s+(\\w+)\\s*[,)=]')

    // FR7: no production log call interpolates an exception instead of passing it.
    def "no log call interpolates an exception it should pass as the trailing argument"() {
        given: 'every production source, with its comments removed'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the source tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'and the log calls inside it — a floor on files alone would pass on an empty call set'
        LogCallSites.productionCalls().size() >= LogCallSites.KNOWN_LOG_CALLS

        and: 'and delimited every one of them — a site the parser drops is a site nobody judges'
        LogCallSites.unparsedProductionCalls() == []

        when: 'each file is scanned for amputated diagnoses, honouring in-place exemptions'
        def violations = sources.collectMany { file ->
            def code = RepoSourceTree.code(file)
            LogCallSites.inSource(code, RepoSourceTree.relative(file))
                    .findAll { interpolatedExceptions(code, it.text) }
                    .findAll { !LogCallSites.exempted(file, it, EXEMPTION) }
                    .collect { "${it.path}:${it.line}" }
        }

        then: 'the gate names every offending site'
        violations == []
    }

    // D9: the detector is the gate — a seeded violation must fail it, or a green run means nothing.
    def "a seeded #form violation is detected"() {
        given: 'a source in the shape the rule forbids'
        def code = """
            try { work(); } catch (IOException e) {
                log.warn("could not read {}: {}", path, ${form});
            }
        """.stripIndent()

        expect:
        LogCallSites.inSource(code, 'Seeded.java')
                .any { interpolatedExceptions(code, it.text) }

        where:
        form << [
            'e.getMessage()',
            'e.getLocalizedMessage()',
            'e.toString()',
            'String.valueOf(e)',
            '"failed: " + e',
            'e + " while reading"'
        ]
    }

    // D9: the fluent builder form must be scanned too — its arguments sit past the level call.
    def "a seeded fluent violation is detected"() {
        given:
        def code = '''
            try { work(); } catch (IOException e) {
                log.atWarn().log("could not read {}: {}", path, e.getMessage());
            }
        '''.stripIndent()

        expect:
        LogCallSites.inSource(code, 'SeededFluent.java')
                .any { interpolatedExceptions(code, it.text) }
    }

    // D9: the correct form is not flagged — a gate that fails the fix is worse than none.
    def "the trailing-throwable form is not flagged"() {
        given:
        def code = '''
            try { work(); } catch (IOException e) {
                log.warn("could not read {}", path, e);
            }
        '''.stripIndent()

        expect:
        LogCallSites.inSource(code, 'Correct.java')
                .every { !interpolatedExceptions(code, it.text) }
    }

    /**
     * True when the call renders an exception the file has bound, rather than passing it. Four
     * shapes, all of which amputate the same diagnosis: the two message accessors
     * ({@code getMessage}, its localized twin), {@code toString}, the {@code String.valueOf}
     * spelling of it, and string concatenation — {@code "failed: " + e} calls {@code toString}
     * without naming it, which is the form the first three checks alone would wave through.
     */
    private static boolean interpolatedExceptions(String code, String call) {
        exceptionNames(code).any { name ->
            def quoted = Pattern.quote(name)
            call =~ /\b${quoted}\.(?:getMessage|getLocalizedMessage|toString)\(\)/ ||
                    call =~ /String\.valueOf\(\s*${quoted}\s*\)/ ||
                    call =~ /\+\s*${quoted}\b(?!\s*[.(])/ ||
                    call =~ /\b${quoted}\s*\+/
        }
    }

    /** The exception identifiers bound anywhere in one source. */
    private static Set<String> exceptionNames(String code) {
        def names = new HashSet<String>()
        def matcher = EXCEPTION_NAME.matcher(code)
        while (matcher.find()) {
            names << (matcher.group(1) ?: matcher.group(2))
        }
        names
    }
}

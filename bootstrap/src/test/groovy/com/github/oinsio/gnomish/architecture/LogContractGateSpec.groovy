package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testsupport.LogCallSites
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR16, M7 of harden-logging-observability, design D15; FR10, design D4 of split-logtext-leaves:
 * the static half of the log contract. Four questions about the whole tree at once, none of which a
 * reviewer can answer by reading one diff — every production WARN/ERROR site carries a catalog
 * code; every catalog code belongs to exactly one site; every code in use is named by at least one
 * test source; and no site spells its code as a string literal.
 *
 * <p>Why a gate: an operator's alert keys on {@code [GFnnn]}, so an uncoded degrade line is
 * invisible to the alerting it was written for, a code used twice makes the alert ambiguous about
 * <em>where</em> the factory degraded, and a code no spec names is a line nothing proves still
 * fires. All three compile, render plausibly, and surface only in the post-mortem.
 *
 * <p>The third check is deliberately weak on its own — a code in a test comment satisfies it. Its
 * behavioral backstop is the runtime gate (FR17), which fails a spec that provokes an operator
 * event no capture asserted; the pair ships together for that reason.
 *
 * <p>The fourth question has no escape hatch, unlike the other three. It is not asking whether a
 * site has a code — it is asking where the code came from, and the catalog constant is reachable
 * from every module in the build, {@code :domain} included since it took its {@code :operatorevent}
 * edge. A literal head was accepted while the domain could not reach the catalog (D15 of
 * harden-logging-observability pinned it as a valid form); once it can, that acceptance is the
 * escape hatch {@code .claude/rules/implementation.md} item 3 says to close, and closing it is what
 * keeps the catalog single-owner now that the round-trip pin ({@code DomainOperatorEventHeadSpec})
 * is gone.
 *
 * <p>In-place escape hatch {@code log-contract-exempt: <reason>} for the first three questions, the
 * same idiom as
 * {@link ThrowableConventionGateSpec}'s {@code throwable-not-subject}. Lives in {@code :bootstrap}
 * for the same reason as its siblings: it is a whole-tree source gate, and this is the module
 * whose {@code test} task wires {@code repoRoot}.
 */
class LogContractGateSpec extends Specification {

    /** The in-place escape hatch documented in {@code .claude/rules/logging.md}. */
    private static final String EXEMPTION = 'log-contract-exempt'

    /**
     * The level methods that reach the operator plane. {@code atLevel} is included although its
     * level is computed: the gate cannot prove such a site never emits WARN, and a scanner that
     * skips what it cannot classify fails open. So a dynamic-level site is judged like any other —
     * it carries a code, or it carries a reason.
     */
    private static final Set<String> OPERATOR_LEVELS = [
        'warn',
        'error',
        'atWarn',
        'atError',
        'atLevel'
    ] as Set

    /** Both ways a site can name its code: the catalog constant, or the literal head. */
    private static final Pattern CODE_REFERENCE = Pattern.compile(
    'OperatorEvent\\.([A-Z][A-Z0-9_]*)|\\[(GF\\d{3})]')

    /**
     * A code head opening a string literal — {@code "[GFnnn]}. The rejected form (FR10): the
     * quote is part of the pattern, so {@code OperatorEvent.X.head() + "message"} cannot match
     * while {@code "[GF110] message"} must.
     */
    private static final Pattern LITERAL_HEAD = Pattern.compile('"\\[(GF\\d{3})]')

    /** Every code the catalog defines — the inventory the three checks are asked against. */
    private static final Set<String> CATALOG = OperatorEvent.values().collect {
        it.code()
    } as Set

    @Shared
    List<Site> sites = operatorSites()

    @Shared
    Set<String> namedByTests = codesNamedByTests()

    // FR16: an uncoded operator line is invisible to the alerting the catalog exists for.
    def "every production WARN or ERROR call site carries a catalog code"() {
        expect: 'the scan really reached the source tree, its log calls, and its operator plane'
        RepoSourceTree.productionSources().size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        LogCallSites.productionCalls().size() >= LogCallSites.KNOWN_LOG_CALLS
        sites.size() >= LogCallSites.KNOWN_OPERATOR_CALLS

        and: 'and delimited every call — a site the parser drops is a site nobody judges'
        LogCallSites.unparsedProductionCalls() == []

        and: 'and no site is left without a code or a reason'
        sites.findAll {
            !it.exempt && it.codes.isEmpty()
        }.collect {
            it.where()
        } == []
    }

    // FR16: one code, one call site — a code used twice cannot say where the factory degraded.
    def "every catalog code is used by exactly one call site"() {
        given: 'the sites each code is emitted from'
        def emitters = [:].withDefault { [] }
        sites.each { site -> site.codes.each { emitters[it] << site.where() } }

        expect: 'no code is emitted from two places'
        emitters.findAll { it.value.size() > 1 } == [:]

        and: 'and none is defined by the catalog without an emitter behind it'
        (CATALOG - emitters.keySet()) as List == []

        and: 'and no site names a code the catalog does not define'
        (emitters.keySet() - CATALOG) as List == []
    }

    // FR16: a code no test source names is a degrade line nothing proves still fires.
    def "every code in use is named by at least one test source"() {
        expect: 'the test-tree scan really reached it'
        RepoSourceTree.testSources().size() >= RepoSourceTree.KNOWN_TEST_SOURCES
        namedByTests.size() >= LogCallSites.KNOWN_OPERATOR_CALLS

        and: 'and every non-exempt site is pinned by name somewhere in it'
        sites.findAll { !it.exempt }
        .collectMany { site ->
            (site.codes - namedByTests).collect {
                "${site.where()} ($it)"
            }
        } == []
    }

    // FR10: the catalog constant is the only accepted form, now that every module can reach it.
    def "no production log call spells its code head as a string literal"() {
        expect: 'the scan really reached the source tree and its log calls'
        RepoSourceTree.productionSources().size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        LogCallSites.productionCalls().size() >= LogCallSites.KNOWN_LOG_CALLS

        and: 'and no site carries a literal head — each would be a copy of a code that can drift'
        literalHeadSites() == []
    }

    // D15: the detector is the gate — a seeded violation must fail it, or a green run means nothing.
    def "a seeded uncoded operator site is detected: #call"() {
        expect: 'the site is seen at all — a snippet the parser drops would pass this vacuously'
        operatorSitesIn(call, 'Seeded.java').size() == 1

        and: 'and it names no code, which the whole-tree check reports as a violation'
        codesOf(call, 'Seeded.java') == [] as Set

        where:
        call << [
            'log.warn("could not read {}", path, e);',
            'log.error("could not read {}", path, e);',
            'log.atWarn().log("could not read {}", path, e);',
            'log.atLevel(levelOf(x)).log("could not read {}", path, e);'
        ]
    }

    // D15: a duplicated code is the ambiguity the one-code-one-site rule exists to forbid.
    def "a seeded duplicate code is detected"() {
        given: 'two sites naming the same catalog constant'
        def code = '''
            log.warn(OperatorEvent.PUSH_FAILED.head() + "push failed for {}", branch, e);
            log.error(OperatorEvent.PUSH_FAILED.head() + "push failed again for {}", branch, e);
        '''.stripIndent()

        when:
        def emitted = operatorSitesIn(code, 'Seeded.java').collectMany {
            codesIn(it.text)
        }

        then: 'the same code is counted twice, which the whole-tree check reports as a violation'
        emitted == [
            OperatorEvent.PUSH_FAILED.code(),
            OperatorEvent.PUSH_FAILED.code()
        ]
    }

    // D15: a code no test source names must not pass as pinned.
    def "a seeded unreferenced code is detected"() {
        expect: 'a code absent from every test source is not in the named set'
        !namedByTests.contains('GF999')
    }

    // D15: the correct form is not flagged — a gate that fails the fix is worse than none.
    def "a coded site rendering the catalog constant is not flagged: #form"() {
        expect: 'its code is read from the site'
        codesOf(form, 'Correct.java') == [expected] as Set

        and: 'and the literal-head rule leaves it alone — the head came from the constant'
        literalHeadsIn(form, 'Correct.java') == []

        where:
        form || expected
        'log.warn(OperatorEvent.PUSH_FAILED.head() + "push failed for {}", branch, e);' || 'GF015'
        'log.atError().log(OperatorEvent.PUSH_FAILED.head() + "push failed", e);' || 'GF015'
    }

    // FR10, D4 of split-logtext-leaves: the inverse of the feature above. The literal form was a
    //     pinned-valid shape while `:domain` could not reach the catalog; it is now the violation,
    //     and the same two examples that used to pass are what the detector must catch.
    def "a seeded literal head is detected: #form"() {
        expect: 'the site is seen at all — a snippet the parser drops would pass this vacuously'
        LogCallSites.inSource(form, 'Seeded.java').size() == 1

        and: 'and its head is reported as a literal, which the whole-tree check reports as a violation'
        literalHeadsIn(form, 'Seeded.java') == [expected]

        where:
        form || expected
        'log.error("[GF110] persist failed for {}", key, ex);' || 'GF110'
        'log.warn("[GF110] persist failed for {}", key, ex);' || 'GF110'
        'log.info("[GF110] not even an operator level", x);' || 'GF110'
    }

    /** One judged call site: where it is, which codes it names, and whether it carries a reason. */
    private static class Site {
        String path
        int line
        Set<String> codes
        boolean exempt

        String where() {
            "${path}:${line}"
        }
    }

    /** Every operator-plane call site of the build, with its codes and its exemption resolved. */
    private static List<Site> operatorSites() {
        RepoSourceTree.productionSources().collectMany { file ->
            operatorSitesIn(RepoSourceTree.code(file), RepoSourceTree.relative(file))
            .collect { call ->
                new Site(path: call.path, line: call.line, codes: codesIn(call.text) as Set,
                exempt: LogCallSites.exempted(file, call, EXEMPTION))
            }
        }
    }

    /** Every production log call whose head is spelled as a literal, as "path:line (code)". */
    private static List<String> literalHeadSites() {
        RepoSourceTree.productionSources().collectMany { file ->
            def path = RepoSourceTree.relative(file)
            LogCallSites.inSource(RepoSourceTree.code(file), path).collectMany { call ->
                literalHeadsIn(call.text, path).collect {
                    "${path}:${call.line} (${it})"
                }
            }
        }
    }

    /**
     * The code heads one snippet spells as string literals, in source order. Every level is judged,
     * not just the operator ones: an INFO line carries no code at all (FR14), so a literal head
     * there is the same copy under a different level.
     */
    private static List<String> literalHeadsIn(String code, String path) {
        LogCallSites.inSource(code, path).collectMany { call ->
            def found = []
            def matcher = LITERAL_HEAD.matcher(call.text)
            while (matcher.find()) {
                found << matcher.group(1)
            }
            found
        }
    }

    /** Every code any test source names, by catalog constant or by literal — the pinning evidence. */
    private static Set<String> codesNamedByTests() {
        RepoSourceTree.testSources().collectMany { codesIn(it.text) } as Set
    }

    /** The codes one operator-plane site names, in source order, duplicates kept. */
    private static List<String> codesIn(String text) {
        def codes = []
        def matcher = CODE_REFERENCE.matcher(text)
        while (matcher.find()) {
            def name = matcher.group(1)
            def code = name == null ? matcher.group(2) : CATALOG_BY_NAME[name]
            if (code != null) {
                codes << code
            }
        }
        codes
    }

    /** Catalog constants by name, so a stale reference resolves to nothing rather than throwing. */
    private static final Map<String, String> CATALOG_BY_NAME =
    OperatorEvent.values().collectEntries { [(it.name()): it.code()] }

    /** The operator-plane call sites of one snippet — what the whole-tree scan would judge. */
    private static List<LogCallSites.LogCall> operatorSitesIn(String code, String path) {
        LogCallSites.inSource(code, path).findAll {
            it.level in OPERATOR_LEVELS
        }
    }

    /** The codes one snippet's operator sites name — the seeded features' single entry point. */
    private static Set<String> codesOf(String code, String path) {
        operatorSitesIn(code, path).collectMany { codesIn(it.text) } as Set
    }
}

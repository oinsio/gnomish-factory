package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * The shared app-layer assembly fixture as the owner of the standard engine assembly in test
 * sources (FR1, FR2, FR3 of refactor-app-spec-fixtures): {@code AppAssemblyFixture.newAssembly} is
 * the one construction site of the standard set, the other {@code ManualRunAssembly} constructions
 * are the named deviations, and a call of the factory spells no default after its last deviation.
 * Both are whole-tree questions about the test sources, comments stripped.
 */
class AppAssemblyFixtureOwnerSpec extends Specification {

    static final String FIXTURE = 'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/AppAssemblyFixture.groovy'

    /**
     * Every file constructing {@code ManualRunAssembly}, with its number of sites: the fixture's
     * {@code newAssembly} and its full-graph runner builder, which builds the assembly the composition
     * root builds; and a spec whose subject is a collaborator the fixture does not cover (the check
     * client registry and secrets).
     */
    static final Map<String, Integer> SITES = [
        (FIXTURE): 2,
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/ManualRunAssemblyCheckClientWiringSpec.groovy': 2
    ]

    /** This spec spells the patterns it scans for as literals, so it leaves itself out. */
    static final String SELF = '/AppAssemblyFixtureOwnerSpec.groovy'

    /** The literals the fixture's {@code newAssembly} defaults stand for, spelled at a call site. */
    static final Set<String> DEFAULT_LITERALS = [
        'null',
        'System.out',
        'testProperties()',
        'rootTimeEquipment()'
    ] as Set

    // F116: scenario text aligned to what this test asserts
    // Scenario: build/quality-gates / One construction site for the standard assembly
    def "FR1: test sources construct the assembly only in the shared fixture and at the named deviations"() {
        given:
        def sources = RepoSourceTree.testSources { !it.endsWith(SELF) }
        def sites = sources.collectEntries { file ->
            [(RepoSourceTree.relative(file)): RepoSourceTree.code(file).readLines().count {
                    it.contains('new ManualRunAssembly(')
                } as Integer]
        }.findAll { it.value> 0 }
        def fixture = RepoSourceTree.code(sources.find {
            RepoSourceTree.relative(it) == FIXTURE
        })

        expect: 'the scan reached the whole test tree and every named site'
        sources.size() > RepoSourceTree.KNOWN_TEST_SOURCES
        sites.keySet().containsAll(SITES.keySet())

        and: 'the sites are exactly the named ones, the standard set built in newAssembly'
        sites == SITES
        fixture.indexOf('new ManualRunAssembly(') > fixture.indexOf('ManualRunAssembly newAssembly(')
    }

    // F117: scenario text aligned to what this test asserts
    // Scenario: build/quality-gates / A spec deviates from the fixture defaults
    def "FR2: a call of the fixture's factory spells no default after its last deviation"() {
        given:
        def calls = RepoSourceTree.testSources {
            it != FIXTURE && !it.endsWith(SELF)
        }.collectMany { file ->
            arguments(RepoSourceTree.code(file), 'newAssembly(').collect {
                [file: RepoSourceTree.relative(file), args: it]
            }
        }

        expect: 'the scan reached the fixture\'s callers'
        calls.size() > 20

        and: 'no call ends on a defaulted part'
        calls.findAll { call ->
            !call.args.isEmpty() && call.args.last() in DEFAULT_LITERALS
        }.collect {
            "${it.file}: ${it.args}"
        } == []
    }

    // FR2: the argument reader the feature above stands on.
    def "the argument reader splits a call's top-level arguments and nothing nested"() {
        expect:
        arguments(source, 'newAssembly(') == expected

        where:
        source || expected
        'def a = newAssembly()' || [[]]
        'newAssembly(props)' || [['props']]
        'newAssembly(null, new PrintStream(out, true, "UTF-8"), props)' || [
            [
                'null',
                'new PrintStream(out, true, "UTF-8")',
                'props'
            ]
        ]
        'x(newAssembly(\n    input,\n    System.out,\n    p), y)' || [['input', 'System.out', 'p']]
    }

    /** The top-level arguments of every call of {@code callee} in {@code code}, each trimmed. */
    static List<List<String>> arguments(String code, String callee) {
        def calls = []
        int at = code.indexOf(callee)
        while (at >= 0) {
            int depth = 0
            def args = []
            def current = new StringBuilder()
            for (int i = at + callee.length(); i <code.length(); i++) {
                String c = code.substring(i, i + 1)
                if (depth == 0 && c in [',', ')']) {
                    args << current.toString().trim()
                    current.setLength(0)
                    if (c == ')') {
                        break
                    }
                    continue
                }
                depth += c in ['(', '[', '{'] ? 1 : (c in [')', ']', '}'] ? -1 : 0)
                current.append(c)
            }
            calls << args.findAll { !it.isEmpty() }
            at = code.indexOf(callee, at + callee.length())
        }
        calls
    }
}

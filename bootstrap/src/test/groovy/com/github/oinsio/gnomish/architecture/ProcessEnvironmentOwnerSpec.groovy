package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D14 of make-checkpoint-gate-durable (D7 enforcement row "test environment"): a test that
 * composes a child process's environment starts from a cleared one. A write layered on top of the
 * inherited environment is how a fake agent spawned by a fixture saw — and wrote into — the
 * {@code GNOMISH_DECISION_FILE} of the gnome round whose build ran the test.
 *
 * <p>The rule, checked by {@link ProcessEnvironmentRule}: every write into a child environment map
 * in a test source, or in {@code :test-fixtures}' own sources, comes after a clearing step in the
 * same method — {@code environment().clear()}, or {@code TestChildEnvironment.cleared(builder)},
 * the shared helper that clears and puts back the test child baseline. Otherwise the file is an
 * exemption below, with its reason.
 *
 * <p>The scope is wider than the task's {@code *}{@code /src/test} glob: every {@code src/test}
 * tree at any depth ({@code adapters/agent}, {@code sandbox/docker}, ...), because the fake-agent
 * invocation itself lives in a nested module.
 *
 * <p>The exemptions are paths. The scan must still find an uncleared write in each, so an exemption
 * whose write moved or was fixed fails loudly rather than staying behind as a silent widening.
 *
 * <p>FR22, M10 of make-checkpoint-gate-durable.
 */
class ProcessEnvironmentOwnerSpec extends Specification {

    private static final String FIXTURES = 'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/'
    private static final String BOOT = 'bootstrap/src/test/groovy/com/github/oinsio/gnomish/'

    /** Files allowed to write into an inherited environment, each with why. */
    private static final Map<String, String> EXEMPT = [
        (FIXTURES + 'adapter/git/SeedTransferFixture.groovy'):
        'git seeding a fixture repository, not a gnome product; it needs the git test configuration as the test JVM has it',
        (BOOT + 'distribution/ReleasePreflightScriptSpec.groovy'):
        'the release preflight CI script under a stubbed gh on PATH; it is not a gnome product and reads no GNOMISH_* variable',
        ('gittransfer/src/test/groovy/com/github/oinsio/gnomish/gittransfer/GitTransferSpec.groovy'):
        'GitTransfer.environment() is a value\'s own map, asserted immutable — not a ProcessBuilder\'s',
    ]

    /** Writers the scan must see as compliant: proof that it reaches both trees, nested modules included. */
    private static final List<String> CLEARING_WRITERS = [
        FIXTURES + 'adapter/git/LocalBoxEnvironment.groovy',
        FIXTURES + 'testfixtures/TestChildEnvironment.groovy',
        'adapters/agent/src/test/groovy/com/github/oinsio/gnomish/adapter/agent/fake/FakeAgentInvocation.groovy',
        BOOT + 'e2e/E2eProcessHarness.groovy',
        BOOT + 'distribution/LauncherScriptSpec.groovy',
        BOOT + 'architecture/NightlyMutationIssueScriptSpec.groovy',
    ]

    /** This gate's own sources spell every shape it scans for, as strings. */
    private static final List<String> THIS_GATE = [
        BOOT + 'architecture/ProcessEnvironmentOwnerSpec.groovy',
        BOOT + 'architecture/ProcessEnvironmentRule.groovy',
    ]

    // FR22, M10: no test child is composed on top of the inherited environment outside the exemptions
    def "FR22, M10: every child-environment write in test sources and fixtures follows a clearing step, or is exempt"() {
        given: 'every test source at any depth and every :test-fixtures source, comments stripped'
        def sources = RepoSourceTree.testSources() + RepoSourceTree.productionSources {
            it.startsWith('test-fixtures/src/main/')
        }
        def scanned = sources.findAll {
            !THIS_GATE.contains(RepoSourceTree.relative(it))
        }
        def writes = scanned.collectEntries { file ->
            [(RepoSourceTree.relative(file)): ProcessEnvironmentRule.writes(RepoSourceTree.code(file))]
        }.findAll { it.value }

        expect: 'the scan really reached the trees, and every known compliant writer'
        scanned.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        writes.keySet().containsAll(CLEARING_WRITERS)
        CLEARING_WRITERS.every { path -> writes[path].every { it.cleared() } }

        and: 'the files with an uncleared write are exactly the exemptions'
        writes.findAll { path, found ->
            found.any {
                !it.cleared()
            }
        }.keySet().sort() == EXEMPT.keySet().sort()
    }

    // The detector is the gate — a seeded write must be found, and judged by what came before it
    def "the detector: #shape"() {
        given: 'the shape inside a method of a class'
        def code = [
            'class A {',
            '    void spawn(ProcessBuilder builder) {'
        ] + body + ['    }', '}']

        expect:
        ProcessEnvironmentRule.writes(code.join('\n')).collect {
            it.cleared()
        } == judged

        where:
        shape | body || judged
        'a direct put on the inherited map' | [
            'builder.environment().put("A", "b")'
        ] || [false]
        'a direct putAll after a clear' | [
            'builder.environment().clear()',
            'builder.environment().putAll(m)'
        ] || [true]
        'a renamed local, never cleared' | [
            'def e = builder.environment()',
            'e.put("A", "b")'
        ] || [false]
        'a renamed local, cleared first' | [
            'Map<String, String> e = builder.environment()',
            'e.clear()',
            'e.putAll(m)'
        ] || [true]
        'a Groovy property write on a local' | [
            'def env = builder.environment()',
            'env.PATH = "/bin"'
        ] || [false]
        'a subscript write on the inherited map' | [
            'builder.environment()["A"] = "b"'
        ] || [false]
        'a write through the shared helper' | [
            'def e = TestChildEnvironment.cleared(builder)',
            'e.put("A", "b")'
        ] || [true]
        'a chained write on the helper' | [
            'TestChildEnvironment.cleared(builder).putAll(m)'
        ] || [true]
        'a clear after the write' | [
            'builder.environment().put("A", "b")',
            'builder.environment().clear()'
        ] || [false]
        'a read is not a write' | [
            'def e = builder.environment()',
            'assert e.PATH == "/bin"',
            'def v = e.get("A")'
        ] || []
        'a remove is not a write' | [
            'builder.environment().remove("A")'
        ] || []
    }

    // A clear in one method does not license a write in the next
    def "the detector: a clearing step does not carry across methods"() {
        given:
        def code = [
            'class A {',
            '    void one(ProcessBuilder b) { b.environment().clear() }',
            '    void two(ProcessBuilder b) {',
            '        String s = "}{"',
            '        b.environment().put("A", "b")',
            '    }',
            '}'
        ]

        expect:
        ProcessEnvironmentRule.writes(code.join('\n')) == [
            new ProcessEnvironmentRule.Write(5, false)
        ]
    }
}

package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * M9 of extract-agent-round-runner (design D14): every implementation of {@code
 * RoundEnvironmentSource} in main sources is a subject of the one port contract spec, {@code
 * RoundEnvironmentSourceContract} in {@code :test-fixtures}. The implementations are found by a
 * scan of every production source (comments stripped), never listed by hand, so a new round source
 * fails this spec until its own module runs the contract against it.
 *
 * <p>A subject is a test source named {@code <Implementation>ContractSpec} that extends the
 * contract and lives in the implementation's own module; the contract's first feature asserts the
 * subject it builds really is the class its name says. An anonymous implementation has no name to
 * run the contract under and is refused outright.
 *
 * <p>Implements M9, FR18 of extract-agent-round-runner.
 */
class RoundEnvironmentSourceContractCoverageSpec extends Specification {

    private static final String CONTRACT =
    'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/app/port/agent/contract/RoundEnvironmentSourceContract.groovy'

    /** The implementations known when the gate was written; the scan must still reach each. */
    private static final List<String> KNOWN = [
        'HostRoundEnvironmentSource',
        'MidRoundPushRounds',
        'SandboxRoundEnvironmentSource',
    ]

    private static final String IMPLEMENTS = /\b(?:class|record)\s+(\w+)\b[^{;]*\bimplements\b[^{;]*\bRoundEnvironmentSource\b(?!\s*\.)/
    private static final String ANONYMOUS = /\bnew\s+RoundEnvironmentSource\s*\(\s*\)\s*\{/
    private static final String SUBJECT = /\bclass\s+(\w+)\s+extends\s+RoundEnvironmentSourceContract\b/

    // M9: every main-source implementation of the port runs the contract in its own module
    def "M9: every main-source implementation of RoundEnvironmentSource is a subject of the port contract"() {
        given: 'every production source, comments stripped'
        def production = RepoSourceTree.productionSources()

        expect: 'the scan really reached the tree, and the contract it pins is there'
        production.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        production.any { RepoSourceTree.relative(it) == CONTRACT }

        when:
        def implementations = implementationsIn(production)
        def subjects = subjectsIn(RepoSourceTree.testSources())

        then: 'the scan found every implementation known when the gate was written'
        implementations.keySet().containsAll(KNOWN)

        and: 'each implementation has a contract run named after it, in its own module'
        implementations.findAll { name, module ->
            subjects[name + 'ContractSpec'] != module
        } == [:]
    }

    // M9: an implementation without a name cannot be named by a contract run
    def "M9: no production source implements RoundEnvironmentSource anonymously"() {
        given:
        def production = RepoSourceTree.productionSources()

        expect:
        production.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        production.findAll {
            RepoSourceTree.code(it) =~ ANONYMOUS
        }.collect {
            RepoSourceTree.relative(it)
        } == []
    }

    /** Each implementing class's simple name, mapped to the module that declares it. */
    private static Map<String, String> implementationsIn(List<File> production) {
        production.collectEntries { file ->
            def matcher = RepoSourceTree.code(file) =~ IMPLEMENTS
            matcher.collect {
                [(it[1] as String): module(file, '/src/main/')]
            }.collectEntries()
        }
    }

    /** Each contract run's simple name, mapped to the module whose test tree holds it. */
    private static Map<String, String> subjectsIn(List<File> tests) {
        tests.collectEntries { file ->
            def matcher = RepoSourceTree.code(file) =~ SUBJECT
            matcher.collect {
                [(it[1] as String): module(file, '/src/test')]
            }.collectEntries()
        }
    }

    private static String module(File file, String sourceSetMarker) {
        def relative = '/' + RepoSourceTree.relative(file)
        relative.substring(0, relative.indexOf(sourceSetMarker))
    }
}

package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D8, D10 of define-executor-contract, the {@code ExecutorRegistry} row (task 7.3): a stage's
 * executor is chosen in one place. The registry is the only production file that builds a program
 * stage executor, and the selector the only one that builds the dispatch over it and the agent arm,
 * so no run path can wire a program stage's rounds, or pick the agent for every stage, beside the
 * dispatch. The agent arm's own construction is pinned to the selector by {@link
 * ProgressListenerWiringSpec}. A whole-tree text scan, comments stripped, that asserts it reached
 * every allowlisted file.
 *
 * <p>FR9, FR10 of define-executor-contract.
 */
class ExecutorRegistryOwnerSpec extends Specification {

    private static final String APP = 'bootstrap/src/main/java/com/github/oinsio/gnomish/app/'
    private static final String REGISTRY = APP + 'ExecutorRegistry.java'
    private static final String SELECTOR = APP + 'ExecutorAdapterSelector.java'

    def "FR9: '#form' is spelled in production only by its owner"() {
        given: 'every production source of the build, comments removed'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan reached the tree, and every owner with it'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.collect {
            RepoSourceTree.relative(it)
        }.containsAll([REGISTRY, SELECTOR])

        when:
        def spellers = sources.findAll {
            RepoSourceTree.code(it).contains(form)
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then:
        spellers == [owner]

        where:
        form || owner
        'new ProgramStageExecutor(' || REGISTRY
        'new ExecutorDispatch(' || SELECTOR
        'ExecutorRegistry.of(' || SELECTOR
    }
}

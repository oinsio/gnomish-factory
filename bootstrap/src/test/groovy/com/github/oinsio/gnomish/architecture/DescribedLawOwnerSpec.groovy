package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D5 of define-executor-contract (as revised 2026-10-31), the describe preflight's
 * single-owner row: every run's engine receives the describe versions the preflight obtained,
 * because {@code RunAssembly.assemble} takes a {@code DescribedLaw} and only the assembly's describe
 * step builds one. The constructor is package-private, which keeps every other layer out; this scan
 * keeps the package itself — every run path lives in it — from building one by hand and skipping
 * the preflight, the way {@code RunAssembler} passed {@code DescribedExecutors.none()} before task
 * 6.2. A whole-tree text scan in {@code :bootstrap}, comments stripped, for the reason {@link
 * TerminalRenderOwnerSpec} gives.
 *
 * <p>FR4, FR13 of define-executor-contract.
 */
class DescribedLawOwnerSpec extends Specification {

    private static final String OWNER = 'bootstrap/src/main/java/com/github/oinsio/gnomish/app/RunDescribe.java'

    /** The forms that hand an engine versions no preflight obtained. */
    private static final List<String> OWNED_FORMS = [
        'new DescribedLaw(',
        'new DescribeOutcome.Described(',
        'DescribedExecutors.none()'
    ]

    def "FR13: '#form' is spelled in production only by the describe owner and the value's own home"() {
        given: 'every production source of the build, comments removed'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan reached the tree, and the owner with it'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.collect { RepoSourceTree.relative(it) }.contains(OWNER)

        when:
        def spellers = sources.findAll {
            RepoSourceTree.code(it).contains(form)
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then:
        spellers == allowed

        where:
        form || allowed
        'new DescribedLaw(' || [OWNER]
        // Task 7.3: the answers a run's program dispatch is built from, which the engine's versions
        // are derived from — only the preflight obtains them, and the owner the empty set of a run
        // that runs no stage.
        'new DescribeOutcome.Described(' || [
            'application/src/main/java/com/github/oinsio/gnomish/app/describe/DescribePreflight.java',
            OWNER
        ]
        'DescribedExecutors.none()' || [
            // The domain's convenience constructor of EnginePorts, for callers that describe
            // nothing; RunAssembler, the one production EnginePorts construction, uses the
            // canonical one with the described law's versions.
            'domain/src/main/java/com/github/oinsio/gnomish/domain/engine/EnginePorts.java',
        ]
    }
}

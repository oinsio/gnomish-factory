package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Shared
import spock.lang.Specification

/**
 * Design D6 of extract-agent-round-runner as amended 2026-11-21, the {@code RoundBounds} row (task
 * 2.3a; FR7, NFR-R1): a round's three time bounds are resolved in one place. {@code RoundTimeout} keeps
 * the shapes and answers "declared or not", read at round time only by {@code RoundBounds}; its old
 * resolver and its own default — a second spelling of {@code ProgramBounds.DEFAULTS.overall()} — are
 * spelled nowhere; the agent round's wait is built only by {@code RoundBounds}, and the request the
 * dialect renders is the one {@code RoundBounds} hinted, so the bound a round is killed at and its
 * overall hint are one value ({@code RoundBoundIdentitySpec}). Every consumer of the resolution is
 * listed, so a new one fails here until it is added to the design's row.
 *
 * <p>Design D6, "The silence bound follows the dialect's declaration", and the row of the bounds on a
 * quiet stdout (task 2.3c; FR16): whether the startup-read and silence bounds apply is answered by one
 * predicate, {@code QuietStdoutBounds}, over the describe's capabilities. {@code STREAMS_PROGRESS} is
 * read in production only by the predicate, spelled otherwise only by the enum that declares it and by
 * a describe that declares it (the vendor's dialect, and the fake dialect of the runner's specs), and the predicate's answer is taken by its two
 * consumers, the {@code program} executor and {@code RoundBounds}, alone.
 *
 * <p>The program executor's wait ({@code ProgramInvocation}) is the one other construction of a
 * {@code ProgressWait}: a {@code program} run is bounded by the factory's bounds alone and reads no
 * {@code roundTimeout} (design D3 of define-executor-contract; {@code ProgramBounds}).
 *
 * <p>A whole-tree text scan, comments stripped, that asserts it reached every listed file.
 *
 * <p>FR7, FR16, NFR-R1 of extract-agent-round-runner.
 */
class RoundBoundsOwnerSpec extends Specification {

    private static final String AGENT = 'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/'
    private static final String RUNNER = AGENT + 'runner/'
    private static final String OWNER = RUNNER + 'RoundBounds.java'
    private static final String ROUND = RUNNER + 'AgentRound.java'
    private static final String COMMAND = AGENT + 'command/CommandStageExecutor.java'
    private static final String PROGRAM_WAIT = AGENT + 'program/ProgramInvocation.java'
    private static final String SHAPES = 'adapters/src/main/java/com/github/oinsio/gnomish/adapter/settings/RoundTimeout.java'
    private static final String PREDICATE = 'application/src/main/java/com/github/oinsio/gnomish/app/describe/QuietStdoutBounds.java'
    private static final String CAPABILITY = 'executor-contract/src/main/java/com/github/oinsio/gnomish/executorcontract/Capability.java'
    private static final String CLAUDE = AGENT + 'claude/ClaudeDialect.java'
    private static final String PROGRAM = AGENT + 'program/ProgramExecutor.java'
    private static final String FAKE_DIALECT =
    'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/adapter/agent/runner/FakeDialect.groovy'

    @Shared
    List<File> sources = RepoSourceTree.productionSources()

    def "the scan reached the tree, and every listed file with it"() {
        expect:
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.collect {
            RepoSourceTree.relative(it)
        }.containsAll([
            OWNER,
            ROUND,
            COMMAND,
            PROGRAM_WAIT,
            SHAPES,
            PREDICATE,
            CAPABILITY,
            CLAUDE,
            PROGRAM,
            FAKE_DIALECT
        ])
    }

    def "FR7: '#form' is spelled in production only by #owners"() {
        when:
        def spellers = sources.findAll {
            RepoSourceTree.code(it).contains(form)
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then:
        spellers == owners.sort()

        where:
        form || owners
        // the round-time read of the declared value: the owner alone
        'RoundTimeout.declared(' || [OWNER]
        // the old way: a resolver with its own fallback, and its default
        'RoundTimeout.resolve(' || []
        'RoundTimeout.DEFAULT' || []
        // every consumer of the resolution, as the design's row lists it
        'RoundBounds.of(' || [ROUND]
        // (a judge's request is an agent round since task 4.1: through RoundBounds.of in AgentRound)
        'RoundBounds.overall(' || [COMMAND]
        // the agent round's wait is built over the resolved bounds; the program executor's over the factory's
        'new ProgressWait(' || [OWNER, PROGRAM_WAIT]
        '.hinted(' || [ROUND]
    }

    def "FR16: '#form' is spelled in production only by #owners"() {
        when:
        def spellers = sources.findAll {
            RepoSourceTree.code(it).contains(form)
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then:
        spellers == owners.sort()

        where:
        form || owners
        // the enum that declares it, the one predicate that reads it, and the describes that declare it:
        // the vendor's dialect, and the runner specs' fake dialect in test-fixtures' main source set
        'STREAMS_PROGRESS' || [
            CAPABILITY,
            PREDICATE,
            CLAUDE,
            FAKE_DIALECT
        ]
        // the predicate's answer, taken by its two consumers alone
        'QuietStdoutBounds.applyTo(' || [OWNER, PROGRAM]
    }

    def "FR16: #dialect spells STREAMS_PROGRESS once, to write it into its describe, and reads no describe's capabilities"() {
        given:
        def code = RepoSourceTree.code(sources.find {
            RepoSourceTree.relative(it) == dialect
        })

        expect:
        code.count('STREAMS_PROGRESS') == 1
        code.contains('new Describe(')
        !code.contains('.capabilities()')

        where:
        dialect << [CLAUDE, FAKE_DIALECT]
    }

    def "NFR-R1: the agent round renders the request with the resolved bounds and reads the factory's bounds only to resolve them"() {
        given:
        def round = RepoSourceTree.code(sources.find {
            RepoSourceTree.relative(it) == ROUND
        })

        expect: 'the one render is of the hinted request'
        round.count('.render(') == 1
        round.contains('dialect.render(bounds.hinted(request))')

        and: "the factory's bounds are read once, as the resolution's input"
        round.count('equipment.bounds()') == 1
        round.contains('RoundBounds.of(equipment.bounds(), dialect.describe(), request)')
    }
}

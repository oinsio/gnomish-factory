package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR2, UX2, M5 of kill-expensive-mutants (design D4); FR24 of
 * supervise-daemon-loops-and-embed-dashboard (design D23): a stalling stand-in {@code git} has one
 * owner — the stand-in library's script spells the one {@code sleep}, its committed presets state
 * what stalls, and {@code StallingGit} in {@code :test-fixtures} hands them out.
 *
 * <p>The defect this gate exists for had a green build: nine hand-written stalling scripts, each
 * sleeping on every subcommand, so the clone-key resolution a production read runs first sat out
 * the whole stall and a feature cost a minute instead of milliseconds. Nothing failed — each copy
 * was correct for the spec that wrote it. The builder answers local commands at once; this
 * whole-tree scan ({@link ClaimlessGitBoundarySpec} is the precedent) keeps a tenth copy from
 * appearing beside it. Since design D23 no Groovy source spells a stall at all: the builder that
 * wrote one per test became a selector over committed presets. {@link StallingScriptRule} decides what a script that sleeps is.
 *
 * <p>The consumer list is paths, not patterns, and each is asserted reached: a renamed consumer
 * fails loudly instead of dropping out of the gate silently.
 */
class StallingGitOwnerSpec extends Specification {

    private static final String GIT_TESTS = 'adapters/git/src/test/groovy/com/github/oinsio/gnomish/adapter/git/'
    private static final String FIXTURES = 'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/adapter/git/'

    /** The owner: the stand-in library's one script, where the one shell {@code sleep} lives. */
    private static final String OWNER = 'test-fixtures/src/main/resources/stand-in/stand-in.sh'

    /** The eight consumers of design D4's single-owner table: each selects its stand-in through the owner. */
    private static final List<String> CONSUMERS = [
        GIT_TESTS + 'ContainerHarvestFetchSpec.groovy',
        GIT_TESTS + 'GitProcessRunnerBoundedNetworkSpec.groovy',
        GIT_TESTS + 'GitProcessRunnerShutdownReportSpec.groovy',
        GIT_TESTS + 'ReplicaPairReconcilerTerminationSpec.groovy',
        GIT_TESTS + 'TaskBranchLocatorSpec.groovy',
        GIT_TESTS + 'UsageHistoryWalkerTerminationSpec.groovy',
        FIXTURES + 'StallingGitFixture.groovy',
        FIXTURES + 'StallingReadGitFixture.groovy',
    ]

    // FR2, UX2: a stalling script outside the owner is the tenth copy — the shape that sat out a
    //     minute-long stall on the key resolution while every spec stayed green.
    def "FR2, UX2: no Groovy source writes a script that sleeps; the owner spells the one sleep"() {
        given: 'every Groovy source of the two governed trees'
        def sources = governedSources()
        def sleeping = sources.findAll {
            StallingScriptRule.sleeps(it)
        }.collect {
            RepoSourceTree.relative(it)
        }

        expect: 'no source writes a script that sleeps'
        // The three hand-written exemptions design D4 kept — a tracing wrapper, a leaky stall and a
        // closed-stdout stall — became committed presets with design D23.
        sleeping.sort() == []

        and: 'the owner is where the sleep is spelled'
        StallingScriptRule.SHELL_SLEEP.matcher(RepoSourceTree.repoRoot().resolve(OWNER).toFile().text).find()
    }

    // FR2, M5: a consumer renamed or moved away must fail here, not drop out of the scan silently.
    def "FR2, M5: the scan reached all eight consumers, and each selects through the owner"() {
        given: 'the consumers the scan reached that select through the owner'
        def reached = governedSources().findAll {
            CONSUMERS.contains(RepoSourceTree.relative(it)) && StallingScriptRule.buildsThroughOwner(it)
        }.collect {
            RepoSourceTree.relative(it)
        }

        expect: 'no consumer is missing'
        CONSUMERS.findAll { !reached.contains(it) } == []
    }

    // FR2: over a clean tree the scan finds nothing, so the rule is shown to bite on seeded sources.
    def "FR2: the rule counts a shell sleep in a literal and nothing else — #what"() {
        expect:
        StallingScriptRule.sleepsIn(source) == hit

        where:
        what | source | hit
        'single-quoted script' | "def s = '#!/bin/sh\\nsleep 1\\n'" | true
        'triple-quoted GString' | 'def s = """#!/bin/sh\nsleep ${STALL}\n"""' | true
        'interpolated operand in GString' | 'def s = "x ${a("q")} sleep \\${n}"' | true
        'escaped expansion' | 'def s = "sleep \\$SECONDS"' | true
        'fractional and infinity' | "def s = 'sleep 0.15'; def t = 'sleep infinity'" | true
        'Thread.sleep poll loop' | 'while (!done) { Thread.sleep(20) }' | false
        'GDK sleep in code' | 'sleep 100' | false
        'prose in a comment' | '// the stand-in sleep 1 s before exit\n/* sleep 5 */' | false
        'prose in a literal' | "def why = 'no spec may sleep here'" | false
        'a sleeper call' | 'sleeper.sleep(Duration.ofSeconds(5))' | false
    }

    /** Every Groovy source of {@code adapters/git/src/test} and {@code test-fixtures/src/main}. */
    private static List<File> governedSources() {
        def sources = RepoSourceTree.testSources {
            it.startsWith('adapters/git/src/test/') && it.endsWith('.groovy')
        } +
        RepoSourceTree.productionSources {
            it.startsWith('test-fixtures/src/main/') && it.endsWith('.groovy')
        }
        assert sources.size() > 100: "the scan reached too few sources to have covered both trees: ${sources.size()}"
        sources
    }
}

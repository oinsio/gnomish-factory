package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR2, UX2, M5 of kill-expensive-mutants (design D4): a stalling stand-in {@code git} has one
 * owner, the {@code StallingGit} builder in {@code :test-fixtures}.
 *
 * <p>The defect this gate exists for had a green build: nine hand-written stalling scripts, each
 * sleeping on every subcommand, so the clone-key resolution a production read runs first sat out
 * the whole stall and a feature cost a minute instead of milliseconds. Nothing failed — each copy
 * was correct for the spec that wrote it. The builder answers local commands at once; this
 * whole-tree scan ({@link ClaimlessGitBoundarySpec} is the precedent) keeps a tenth copy from
 * appearing beside it. {@link StallingScriptRule} decides what a script that sleeps is.
 *
 * <p>The allowlists are paths, not patterns, and each is asserted reached: a renamed consumer or
 * a stale exemption fails loudly instead of widening the gate silently.
 */
class StallingGitOwnerSpec extends Specification {

    private static final String GIT_TESTS = 'adapters/git/src/test/groovy/com/github/oinsio/gnomish/adapter/git/'
    private static final String FIXTURES = 'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/adapter/git/'

    /** The owner: the one source whose literals may spell a stall freely. */
    private static final String OWNER = FIXTURES + 'StallingGit.groovy'

    /** The eight consumers of design D4's single-owner table: each builds its stand-in through the owner. */
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

    /**
     * The scripts that sleep and stay hand-written (design D4 disposition table), each because
     * the script's own shape is the subject:
     *
     * <ul>
     *   <li>{@code GitProcessRunnerBoundedNetworkSpec}: the leaky-git script — the credential-
     *       bearing stderr line printed before the stall is what the scrub feature asserts on. The
     *       file is also a consumer; the exemption is file-level, so its builder-built stand-in
     *       and this one script share the row.
     *   <li>{@code TipStateCursorTerminationSpec}: closes stdout, then stalls — the closed pipe is
     *       the subject.
     *   <li>{@code CloneMutationConcurrencySpec}: not a stand-in at all — a tracing wrapper over
     *       the real {@code git} that sleeps briefly between its START and END lines.
     * </ul>
     */
    private static final List<String> EXEMPTIONS = [
        GIT_TESTS + 'CloneMutationConcurrencySpec.groovy',
        GIT_TESTS + 'GitProcessRunnerBoundedNetworkSpec.groovy',
        GIT_TESTS + 'TipStateCursorTerminationSpec.groovy',
    ]

    // FR2, UX2: a stalling script outside the owner is the tenth copy — the shape that sat out a
    //     minute-long stall on the key resolution while every spec stayed green.
    def "FR2, UX2: no source outside the owner and the named exemptions writes a script that sleeps"() {
        given: 'every Groovy source of the two governed trees'
        def sources = governedSources()
        def sleeping = sources.findAll {
            StallingScriptRule.sleeps(it)
        }.collect {
            RepoSourceTree.relative(it)
        }

        expect: 'the scan reached the owner and every exemption, and each still sleeps'
        ([OWNER] + EXEMPTIONS).findAll { !sleeping.contains(it) } == []

        and: 'no other source writes a script that sleeps'
        sleeping.findAll {
            it != OWNER && !EXEMPTIONS.contains(it)
        }.sort() == []
    }

    // FR2, M5: a consumer renamed or moved away must fail here, not drop out of the scan silently.
    def "FR2, M5: the scan reached all eight consumers, and each builds through the owner"() {
        given: 'the consumers the scan reached that build through the owner'
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

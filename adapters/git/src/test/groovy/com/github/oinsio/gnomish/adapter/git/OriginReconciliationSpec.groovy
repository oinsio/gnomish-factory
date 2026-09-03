package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, NFR-R1, NFR-O1, NFR-C1 of fix-lifecycle-push: the touchpoint check that converts a missed
 * edge into eventually-delivered state. Origin behind or missing the branch gets a catch-up push;
 * an origin already holding the local tip costs one refs read and nothing else; an origin that
 * cannot be reached, or that holds something the local tip does not descend from, degrades to one
 * WARN and never blocks the caller.
 */
class OriginReconciliationSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private static final String TASK_ID = 'PROJ-1'
    private static final String BRANCH = 'gnomish/PROJ-1'
    private static final String TOUCHPOINT = 'resume-start'

    private final GitProcessRunner runner = new GitProcessRunner()
    private final OriginReconciliation reconciliation = new OriginReconciliation(runner)
    private Path origin
    private Path clone

    def setup() {
        origin = initBareRepo(tempDir, 'origin.git')
        clone = tempDir.resolve('clone')
        runner.run(tempDir, 'clone', '-q', origin.toString(), clone.toString())
        Files.writeString(clone.resolve('a.txt'), 'base')
        commitAll(clone, 'init')
        gitOutput(clone, 'checkout', '-q', '-b', BRANCH)
    }

    private String commitOnBranch(String content) {
        Files.writeString(clone.resolve('a.txt'), content)
        commitAll(clone, content)
        gitOutput(clone, 'rev-parse', 'HEAD')
    }

    private Optional<String> originTip() {
        new RemoteBranchTip(runner).read(clone, BRANCH)
    }

    private void deliver() {
        assert new RefspecPush(runner).push(clone, BRANCH).exitCode() == 0
    }

    /** Migrated to the shared helper (`.claude/rules/logging.md`) when task 5.4 touched this spec. */
    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        def logs = LogCaptureSupport.attach(OriginReconciliation, Level.DEBUG)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    def "an origin missing the branch entirely is caught up"() {
        given:
        def local = commitOnBranch('one')

        when:
        def events = capture {
            reconciliation.reconcile(TASK_ID, TOUCHPOINT, clone, BRANCH, local)
        }

        then:
        originTip() == Optional.of(local)

        and: 'the catch-up is visible to a diagnosis, and silent on the operator plane'
        // FR12 of harden-logging-observability: the intention and the outcome of one push are
        // two lines about one path; the failure WARN carries the decision, this one is DEBUG.
        events.any {
            it.level == Level.DEBUG && it.formattedMessage.contains('origin does not hold the task branch tip')
        }
        events.every { it.level != Level.WARN }
    }

    def "an origin holding a strict ancestor of the local tip is caught up"() {
        given:
        commitOnBranch('one')
        deliver()
        def local = commitOnBranch('two')

        when:
        reconciliation.reconcile(TASK_ID, TOUCHPOINT, clone, BRANCH, local)

        then:
        originTip() == Optional.of(local)
    }

    def "an up-to-date origin costs one refs read and no push"() {
        given:
        def local = commitOnBranch('one')
        deliver()
        def tipBefore = originTip()

        and: 'a git stand-in recording every subcommand the check spends (NFR-C1)'
        def log = tempDir.resolve('argv.log')
        def counting = new OriginReconciliation(new GitProcessRunner(recordingGit(log).toString()))

        when:
        def events = capture {
            counting.reconcile(TASK_ID, TOUCHPOINT, clone, BRANCH, local)
        }

        then: 'exactly one remote round-trip: the presence check, then a single refs read'
        recordedSubcommands(log) == ['remote', 'ls-remote']

        and: 'nothing moved and nothing was said'
        originTip() == tipBefore
        events.isEmpty()
    }

    def "an unreachable origin degrades to one WARN without throwing"() {
        given:
        def local = commitOnBranch('one')
        gitOutput(clone, 'remote', 'set-url', 'origin', tempDir.resolve('nowhere.git').toString())

        when:
        def events = capture {
            reconciliation.reconcile(TASK_ID, TOUCHPOINT, clone, BRANCH, local)
        }

        then:
        noExceptionThrown()
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.ORIGIN_RECONCILIATION_FAILED.head() + 'origin reconciliation push failed:')
        warnings[0].formattedMessage.contains("taskId=${TASK_ID}")
        warnings[0].formattedMessage.contains("branch=${BRANCH}")
        warnings[0].formattedMessage.contains("touchpoint=${TOUCHPOINT}")
    }

    def "an origin the local tip does not descend from is left alone, with a WARN"() {
        given: 'someone else pushed a divergent branch to origin'
        def other = tempDir.resolve('other')
        runner.run(tempDir, 'clone', '-q', origin.toString(), other.toString())
        gitOutput(other, 'checkout', '-q', '-b', BRANCH)
        Files.writeString(other.resolve('theirs.txt'), 'theirs')
        commitAll(other, 'theirs')
        assert new RefspecPush(runner).push(other, BRANCH).exitCode() == 0
        def divergentTip = originTip()
        def local = commitOnBranch('mine')

        when:
        def events = capture {
            reconciliation.reconcile(TASK_ID, TOUCHPOINT, clone, BRANCH, local)
        }

        then: 'no push was attempted — history repair is not this check\'s business'
        originTip() == divergentTip

        and:
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.ORIGIN_RECONCILIATION_SKIPPED.head() + 'origin reconciliation skipped:')
    }

    def "a clone with no origin is a silent no-op"() {
        given:
        def local = initWorkingRepo(tempDir, 'local')
        Files.writeString(local.resolve('a.txt'), 'base')
        commitAll(local, 'init')
        gitOutput(local, 'checkout', '-q', '-b', BRANCH)
        def tip = gitOutput(local, 'rev-parse', 'HEAD')

        and: 'a git stand-in recording every subcommand the check spends (NFR-C1)'
        def log = tempDir.resolve('no-origin.log')
        def counting = new OriginReconciliation(new GitProcessRunner(recordingGit(log).toString()))

        when:
        def events = capture {
            counting.reconcile(TASK_ID, TOUCHPOINT, local, BRANCH, tip)
        }

        then: 'the presence check alone — no refs read reaches out, and no push is attempted'
        recordedSubcommands(log) == ['remote']

        and:
        noExceptionThrown()
        events.isEmpty()
    }
}

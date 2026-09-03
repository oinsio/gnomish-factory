package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR4, FR5, NFR-R2 of fix-lifecycle-push: the fence that stands between a park's recorded outcome
 * and the tracker write announcing it. Delivered tip → one cheap read and nothing else; undelivered
 * tip → a push, and one bounded re-attempt if that fails; still undelivered → a verdict carrying the
 * report note, never a thrown failure and never a blocked park. No origin → no remote interaction
 * at all.
 */
class ParkDeliveryFenceSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private static final String TASK_ID = 'PROJ-1'
    private static final String BRANCH = 'gnomish/PROJ-1'

    private final GitProcessRunner runner = new GitProcessRunner()
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

    private String commitPark() {
        Files.writeString(clone.resolve('park.txt'), 'parked')
        commitAll(clone, 'task parked')
        gitOutput(clone, 'rev-parse', 'HEAD')
    }

    private Optional<String> originTip() {
        new RemoteBranchTip(runner).read(clone, BRANCH)
    }

    /** Installs a pre-receive hook on the bare origin; hooks run with cwd = the bare repo. */
    private void installPreReceiveHook(String script) {
        def hook = origin.resolve('hooks').resolve('pre-receive').toFile()
        hook.parentFile.mkdirs()
        hook.text = "#!/bin/sh\n" + script + "\n"
        hook.setExecutable(true)
    }

    /** Migrated to the shared helper (`.claude/rules/logging.md`) when task 5.4 touched this spec. */
    private static List<ILoggingEvent> capture(Closure<?> emit) {
        def logs = LogCaptureSupport.attach(ParkDeliveryFence, Level.INFO)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    def "an undelivered park commit is pushed and reported delivered"() {
        given:
        def parked = commitPark()
        assert originTip() == Optional.empty()

        when:
        def verdict = new ParkDeliveryFence(runner).ensureDelivered(clone, TASK_ID)

        then:
        verdict instanceof ParkDeliveryVerdict.Delivered
        originTip() == Optional.of(parked)
    }

    def "an already delivered tip is confirmed without a push"() {
        given:
        def parked = commitPark()
        assert new RefspecPush(runner).push(clone, BRANCH).exitCode() == 0
        installPreReceiveHook('echo "no further push may happen" >&2; exit 1')

        when:
        def verdict = new ParkDeliveryFence(runner).ensureDelivered(clone, TASK_ID)

        then: 'the hook that would reject any push never fired'
        verdict instanceof ParkDeliveryVerdict.Delivered
        originTip() == Optional.of(parked)
    }

    def "a transient first failure is recovered by the bounded re-attempt"() {
        given: 'a hook that rejects only the first push it sees'
        ParkDeliveryVerdict verdict = null
        commitPark()
        installPreReceiveHook('''
            marker="$(git rev-parse --git-dir)/first-push-seen"
            if [ ! -f "$marker" ]; then touch "$marker"; echo "transient" >&2; exit 1; fi
            exit 0
        '''.stripIndent())

        when:
        def events = capture {
            verdict = new ParkDeliveryFence(runner).ensureDelivered(clone, TASK_ID)
        }

        then:
        verdict instanceof ParkDeliveryVerdict.Delivered
        originTip().isPresent()

        and: 'the first failure was recorded, the fence itself did not report exhaustion'
        // FR12 of harden-logging-observability: the first of two bounded attempts is not
        // operator business — only an exhausted fence is, and this one recovered.
        events.findAll { it.level == Level.WARN }.isEmpty()
        events[0].level == Level.INFO
        events[0].formattedMessage.startsWith('park delivery push failed, re-attempting once:')
    }

    def "an unreachable origin exhausts the fence into a note, not an exception"() {
        given:
        ParkDeliveryVerdict verdict = null
        commitPark()
        gitOutput(clone, 'remote', 'set-url', 'origin', tempDir.resolve('nowhere.git').toString())

        when:
        def events = capture {
            verdict = new ParkDeliveryFence(runner).ensureDelivered(clone, TASK_ID)
        }

        then:
        noExceptionThrown()
        verdict instanceof ParkDeliveryVerdict.Undelivered
        (verdict as ParkDeliveryVerdict.Undelivered).note().contains(BRANCH)
        (verdict as ParkDeliveryVerdict.Undelivered).note().contains('origin is behind this park')

        and: 'both the re-attempt and the exhaustion are visible, but only the exhaustion is a WARN'
        events.findAll { it.level == Level.WARN }.size() == 1
        events[0].level == Level.INFO
        events[1].level == Level.WARN
        events[1].formattedMessage.startsWith(OperatorEvent.PARK_FENCE_EXHAUSTED.head() + 'park delivery fence exhausted, parking anyway:')
    }

    def "a clone with no origin performs no remote interaction"() {
        given:
        ParkDeliveryVerdict verdict = null
        def local = initWorkingRepo(tempDir, 'local')
        Files.writeString(local.resolve('a.txt'), 'base')
        commitAll(local, 'init')
        gitOutput(local, 'checkout', '-q', '-b', BRANCH)

        when:
        def events = capture {
            verdict = new ParkDeliveryFence(runner).ensureDelivered(local, TASK_ID)
        }

        then:
        verdict instanceof ParkDeliveryVerdict.Delivered
        events.isEmpty()
    }

    // The fence is reached through the TaskBranchGit port, which is how the park exit calls it.
    def "the branch capability seam hands the fence's verdict back to the application layer"() {
        given:
        def parked = commitPark()

        when:
        def verdict = new GitTaskBranches(runner).fenceParkDelivery(clone, TASK_ID)

        then:
        verdict instanceof ParkDeliveryVerdict.Delivered
        originTip() == Optional.of(parked)
    }

    // NFR-O1: the park is never blocked by a tip the fence cannot read, but the operator is told the
    // delivery check did not run — a silent Delivered would read as "origin verified".
    def "an unreadable local tip parks anyway and says so"() {
        given:
        ParkDeliveryVerdict verdict = null

        when:
        def events = capture {
            verdict = new ParkDeliveryFence(runner).ensureDelivered(clone, 'NO-SUCH')
        }

        then:
        verdict instanceof ParkDeliveryVerdict.Delivered

        and:
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.startsWith(OperatorEvent.PARK_FENCE_TIP_UNREADABLE.head()
                + 'park delivery fence skipped, local branch tip unreadable:')
        events[0].formattedMessage.contains('taskId=NO-SUCH')
        events[0].formattedMessage.contains('branch=gnomish/NO-SUCH')
        events[0].formattedMessage.contains("cloneDir=${clone}")
    }
}

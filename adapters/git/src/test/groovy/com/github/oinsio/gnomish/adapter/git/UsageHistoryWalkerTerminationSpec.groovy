package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR16 of harden-task-branch-contract: a history read that never ran to its own exit established
 * nothing about the branch, so it must not feed the usage report — the seam rule
 * {@code GitShowTipTerminationSpec} pins for the tip reader, applied to the walker. An interrupted
 * {@code git log} hands back a prefix of the commit list, which reads as a complete report silently
 * missing its newest rounds; an interrupted {@code git show} reads as the absent-state case and
 * silently drops a commit's rounds.
 *
 * <p>Driven through the runner's git-binary seam: a stand-in {@code git} that answers every command
 * before the one under test and stalls on that one, interrupted mid-wait.
 */
class UsageHistoryWalkerTerminationSpec extends Specification {

    @TempDir
    Path tempDir

    def "FR16: an interrupted history listing is unavailability, never an empty report"() {
        when:
        def thrown = interruptDuring(stallingOn('log'))

        then:
        thrown instanceof BranchTipUnavailableException
    }

    def "FR16: an interrupted state read is unavailability, never a skipped commit"() {
        when:
        def thrown = interruptDuring(stallingOn('show'))

        then:
        thrown instanceof BranchTipUnavailableException
    }

    /** Runs a walk over {@code fakeGit} in its own thread and interrupts it mid-stall. */
    private Throwable interruptDuring(Path fakeGit) {
        Throwable thrown = null
        def walker = new Thread({
            try {
                new UsageHistoryWalker(new GitProcessRunner(fakeGit.toString()), VirtualTimeGitRetries.gitInfrastructure()).walk(tempDir, 'PROJ-1')
            } catch (Throwable t) {
                thrown = t
            }
        })
        walker.start()
        awaitStallStarted()
        walker.interrupt()
        walker.join(Duration.ofSeconds(30).toMillis())
        return thrown
    }

    /**
     * A git that lets the locator find a local branch and the walk reach {@code stalled} — every
     * command before it answers — and then stalls until interrupted, the outcome no real repository
     * can be asked to produce on demand.
     */
    private Path stallingOn(String stalled) {
        new StallingGit()
                .stallOn(stalled)
                .markOnStall(stallStarted())
                .answer('log', 'a' * 40, 0)
                .answer('rev-parse', 'a' * 40, 0)
                .write(tempDir)
    }

    private Path stallStarted() {
        tempDir.resolve('stall-started')
    }

    /** Blocks until the stalled command is in flight, so the interrupt lands on it and not before. */
    private void awaitStallStarted() {
        long deadline = System.nanoTime() + 20_000_000_000L
        while (!Files.exists(stallStarted()) && System.nanoTime() <deadline) {
            Thread.sleep(20)
        }
        assert Files.exists(stallStarted()): 'the stand-in git never reached the stalled command'
    }
}

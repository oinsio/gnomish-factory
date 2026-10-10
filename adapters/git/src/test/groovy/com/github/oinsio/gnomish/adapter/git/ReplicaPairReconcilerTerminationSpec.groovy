package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, NFR-R3 of harden-task-branch-contract: a reconciliation invocation that never ran to its own
 * exit established nothing about the replica pair, so it must not classify — the seam rule
 * {@code GitShowTipTerminationSpec} pins for the tip reader, applied to the reconciler. An
 * interrupted {@code merge-base} answers "not an ancestor" for a pair it never compared, which
 * classifies a merely BEHIND or AHEAD pair as DIVERGED and, under a live tenure, discards the local
 * line; an interrupted {@code rev-parse} can hand back a truncated tip with the same verdict; an
 * interrupted {@code update-ref} left the swap's outcome unknown — neither "won" nor "the tip
 * moved" — and counting it as a losing pass turns a shutdown into a second-writer diagnosis.
 *
 * <p>Driven through the runner's git-binary seam: a stand-in {@code git} that answers every command
 * before the one under test and stalls on that one, interrupted mid-wait.
 */
class ReplicaPairReconcilerTerminationSpec extends Specification {

    @TempDir
    Path tempDir

    def underTenure = { String taskId ->
        Optional.of(new ClaimEpoch(7L))
    } as ClaimEpochSource

    def "FR8: an interrupted tip read is unavailability, never a missing pair"() {
        when:
        def thrown = interruptDuring(stallingOn('rev-parse'))

        then:
        thrown instanceof BranchTipUnavailableException
    }

    def "FR8: an interrupted ancestry probe is unavailability, never DIVERGED — the discard needs a compared pair"() {
        when:
        def thrown = interruptDuring(stallingOn('merge-base'))

        then:
        thrown instanceof BranchTipUnavailableException
    }

    def "FR8: an interrupted ref swap is an unknown outcome, never a losing pass toward the second-writer diagnosis"() {
        when:
        def thrown = interruptDuring(stallingOn('update-ref'))

        then:
        thrown instanceof IllegalStateException
        thrown.message.contains('unknown')
        !thrown.message.contains('second writer')
    }

    /** Runs a tenured reconcile over {@code fakeGit} in its own thread and interrupts it mid-stall. */
    private Throwable interruptDuring(Path fakeGit) {
        Throwable thrown = null
        def reconciler = new Thread({
            try {
                ReplicaPairReconciler.forClone(new GitProcessRunner(fakeGit.toString()), tempDir, underTenure)
                        .reconcile('PROJ-1', 'gnomish/PROJ-1')
            } catch (Throwable t) {
                thrown = t
            }
        })
        reconciler.start()
        StallingGit.awaitStall(fakeGit)
        reconciler.interrupt()
        reconciler.join(Duration.ofSeconds(30).toMillis())
        return thrown
    }

    /**
     * A git that reaches {@code stalled} with two real-looking diverged tips — every command before
     * it answers — and then stalls until interrupted, the outcome no real repository can be asked
     * to produce on demand.
     */
    private Path stallingOn(String stalled) {
        StallingGit.marked(tempDir.resolve(stalled), 'reconcile-stall')
    }
}

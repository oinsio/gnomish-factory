package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-R2 of fix-envelope-medium: the denial-cursor carry-forward is an envelope read like any
 * other, so a read that never ran to its own exit is unavailability — not "the tip carries no
 * cursor". The degraded answer is the one that erases, durably, the position a container-mode run
 * committed: the rewrite lands a cursorless {@code state.json} and the lifecycle commit that
 * follows makes the loss permanent.
 *
 * <p>Driven through the runner's git-binary seam: a stand-in {@code git} that stalls on the read,
 * interrupted mid-wait — the outcome no real repository can be asked to produce.
 */
class StateFileWriteTerminationSpec extends Specification implements StallingReadGitFixture {

    @TempDir
    Path tempDir

    def "NFR-R2: an interrupted cursor read is unavailability, never a cursorless rewrite"() {
        given: 'a lifecycle write whose tip read stalls'
        Path worktree = Files.createDirectories(tempDir.resolve('worktree'))
        def runner = new GitProcessRunner(stallingGit(tempDir).toString())

        when: 'the read is cut off before git exits'
        Throwable thrown = null
        def writer = new Thread({
            try {
                StateFileWrite.write(runner, worktree, 'PROJ-1', TaskState.atStageStart('review'), TaskLifecycleEvent.RESUMED)
            } catch (Throwable t) {
                thrown = t
            }
        })
        writer.start()
        awaitReadStarted(tempDir)
        writer.interrupt()
        writer.join(Duration.ofSeconds(30).toMillis())

        then: 'the write refuses rather than regenerating the file without the position'
        thrown instanceof BranchTipUnavailableException
        !Files.exists(worktree.resolve(EnvelopePaths.STATE_JSON_PATH))
    }
}

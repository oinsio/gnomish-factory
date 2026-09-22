package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.gitobjects.GitObjectsInterruptedException
import com.github.oinsio.gnomish.gitobjects.ObjectId
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-R2 of fix-envelope-medium, for the container medium: the denial-cursor carry-forward reads
 * the tip through the bare-object reader, and a read cut off by a shutdown establishes nothing — so
 * it must not answer "the tip carries no cursor". That answer regenerates {@code state.json}
 * without the position the last attempt committed, and the lifecycle commit that follows makes the
 * loss permanent.
 *
 * <p>The twin of {@link StateFileWriteTerminationSpec}, which pins the same refusal on the host
 * medium's {@code git show} seam. This medium has no deadline of its own, so an interrupt is the
 * only way a read ends without an answer; the reading thread is pre-interrupted, which makes the
 * supervised wait end as {@code INTERRUPTED} deterministically rather than as a race.
 */
class TipStateCursorTerminationSpec extends Specification {

    @TempDir
    Path tempDir

    private static final ObjectId TIP = new ObjectId('0' * 40)

    def "NFR-R2: an interrupted cursor read is unavailability, never a cursorless rewrite"() {
        given: 'a lifecycle rewrite over a git that is still running when the wait begins'
        Path gitDir = Files.createDirectories(tempDir.resolve('repo.git'))
        def writer = new TaskLifecycleCommitWriter(
                GitObjects.open(gitDir, tempDir, stalledAfterOutputGit().toString()), null, null, null)

        and: 'the reading thread is interrupted, so the supervised wait ends without an exit'
        Thread.currentThread().interrupt()

        when:
        writer.tipStateCursor('PROJ-1', TIP)

        then: 'the read refuses rather than answering that there is no position to carry'
        thrown(GitObjectsInterruptedException)

        cleanup: 'never leak the interrupt flag into later specs, whichever assertion failed'
        Thread.interrupted()
    }

    /**
     * A stand-in {@code git} that closes its stdout at once — so the capped read ends — and then
     * stays alive, leaving the supervised wait as the step the interrupt lands on.
     */
    private Path stalledAfterOutputGit() {
        Path fakeGit = tempDir.resolve('stalled-git')
        fakeGit.toFile().text = '#!/bin/sh\nexec 1>&-\nsleep 600\n'
        fakeGit.toFile().executable = true
        return fakeGit
    }
}

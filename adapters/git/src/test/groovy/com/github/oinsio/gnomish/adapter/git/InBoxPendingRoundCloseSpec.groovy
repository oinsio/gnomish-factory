package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.RoundStates
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR20 of migrate-verify-to-executor-contract (design D21, "The parent of the round's close"): a
 * sandboxed round with pending commits closes on its last pending commit — the state commit's
 * parent-check is against the round's last registered commit, not the snapshot below it.
 */
class InBoxPendingRoundCloseSpec extends Specification implements InBoxPendingRound {

    @TempDir
    Path tempDir

    def setup() {
        setupPendingRound()
    }

    // Scenario: git/git-task-persistence / Daemon-inserted commit is caught by the parent-check
    def "FR20: a commit inserted in the box above the round's pending commit is caught against the last registered commit"() {
        given:
        persistence().persistPending(TASK, pendingState('run-1'))
        def pending = tip()
        new File(box.workingCopy.toFile(), 'daemon.txt').text = 'sneaky'
        runner.run(box.workingCopy, 'add', '-A')
        runner.run(box.workingCopy, 'commit', '-m', 'daemon commit')

        when:
        def key = new AttemptKey(TASK, 'implement', 1)
        persistence().persist(TASK, RoundStates.recorded(key), new ToolTrace(key, []))

        then:
        def refused = thrown(RoundBoundaryViolationException)
        refused.message.contains("is not the round's last registered commit " + pending)
        registered() == pending
    }

    // Scenario: git/git-task-persistence / The state commit sits on the round's last pending commit
    def "FR20: the round's state commit sits on its last pending commit and carries no pending entry"() {
        given:
        persistence().persistPending(TASK, pendingState('run-1'))
        persistence().persistPending(TASK, pendingState('run-1', 'run-2'))
        def lastPending = tip()

        when:
        def key = new AttemptKey(TASK, 'implement', 1)
        persistence().persist(TASK, RoundStates.recorded(key), new ToolTrace(key, []))

        then:
        def stateCommit = tip()
        registered() == stateCommit
        parentsOf(stateCommit) == lastPending
        !gitOutput(cloneDir, 'show', stateCommit + ':' + STATE).contains('run-')
    }
}

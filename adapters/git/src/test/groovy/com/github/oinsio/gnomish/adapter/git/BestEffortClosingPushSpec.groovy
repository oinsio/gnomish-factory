package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR13 of tighten-spec-contract (design D13): the best-effort closing push of a salvage or a
 * sandboxed round pushes branch and register in one atomic push, as the clone holds them — the
 * closing commit's own ref transaction already moved both — and turns a failed push into one coded
 * WARN.
 */
class BestEffortClosingPushSpec extends Specification {

    private static final Path CLONE = Path.of('/clone')

    private static ScriptedGit git(int updateRef, int push, String origin = 'https://example.invalid/repo.git\n') {
        new ScriptedGit()
                .answer(['update-ref'], updateRef)
                .answer(['remote'], origin ? 0 : 2, origin ?: '')
                .answer(['push'], push, '', push == 0 ? '' : 'error: failed to push some refs\n')
    }

    def "FR13: branch and register are pushed atomically, as the clone holds them; nothing is registered"() {
        given:
        def git = git(0, 0)

        when:
        new BestEffortClosingPush(git).pushBestEffort(CLONE, 'T-1')

        then:
        git.calls(['update-ref']).isEmpty()
        git.calls(['push'])*.command() == [
            [
                'push',
                '--atomic',
                '--no-follow-tags',
                '--no-recurse-submodules',
                '--end-of-options',
                'origin',
                'refs/heads/gnomish/T-1:refs/heads/gnomish/T-1',
                'refs/gnomish-factory/T-1:refs/gnomish-factory/T-1',
            ]
        ]
    }

    def "FR13: with no origin nothing is pushed"() {
        given:
        def git = git(0, 0, '')

        when:
        new BestEffortClosingPush(git).pushBestEffort(CLONE, 'T-1')

        then:
        git.calls(['push']).isEmpty()
    }

    def "FR13: a refused closing push is one coded WARN naming the task, never a throw"() {
        given:
        def git = git(0, 1)

        when:
        def events = LogCaptureSupport.capture(BestEffortClosingPush, Level.INFO) {
            new BestEffortClosingPush(git).pushBestEffort(CLONE, 'T-1')
        }

        then:
        noExceptionThrown()
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.startsWith(OperatorEvent.CLOSING_PUSH_FAILED.head() + 'closing push failed:')
        events[0].formattedMessage.contains('taskId=T-1')

        and: 'it says what happens next, so the operator need not guess'
        events[0].formattedMessage.contains('the commit and its register stay in the clone')
        events[0].formattedMessage.contains("the task's next touchpoint")
    }
}

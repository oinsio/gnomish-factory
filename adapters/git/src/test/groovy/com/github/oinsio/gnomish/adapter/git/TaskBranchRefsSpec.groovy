package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.InvalidTaskIdException
import spock.lang.Specification

/**
 * FR8 of fix-terminal-receipt-convergence (design D5): {@link TaskBranchRefs} is the one spelling of
 * a task branch's head and remote-tracking ref, both over the sanitized task branch name.
 */
class TaskBranchRefsSpec extends Specification {

    def "FR8: the head of #taskId is #head"() {
        expect:
        TaskBranchRefs.head(taskId) == head

        where:
        taskId || head
        'PROJ-42' || 'refs/heads/gnomish/PROJ-42'
        'feat/a b' || 'refs/heads/gnomish/feat-a-b'
    }

    def "FR8: the remote-tracking ref of #taskId is #tracking"() {
        expect:
        TaskBranchRefs.tracking(taskId) == tracking

        where:
        taskId || tracking
        'PROJ-42' || 'refs/remotes/origin/gnomish/PROJ-42'
        'feat/a b' || 'refs/remotes/origin/gnomish/feat-a-b'
    }

    def "FR8: each ref lies in its namespace, which a listing of every task branch enumerates"() {
        expect:
        TaskBranchRefs.HEAD_NAMESPACE == 'refs/heads/gnomish/'
        TaskBranchRefs.TRACKING_NAMESPACE == 'refs/remotes/origin/gnomish/'
        TaskBranchRefs.head('PROJ-42').startsWith(TaskBranchRefs.HEAD_NAMESPACE)
        TaskBranchRefs.tracking('PROJ-42').startsWith(TaskBranchRefs.TRACKING_NAMESPACE)
    }

    def "FR8: a taskId that sanitizes to nothing names no ref"() {
        when:
        TaskBranchRefs.head('///')

        then:
        thrown(InvalidTaskIdException)

        when:
        TaskBranchRefs.tracking('///')

        then:
        thrown(InvalidTaskIdException)
    }
}

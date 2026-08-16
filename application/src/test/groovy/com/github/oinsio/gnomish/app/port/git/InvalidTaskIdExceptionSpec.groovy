package com.github.oinsio.gnomish.app.port.git

import spock.lang.Specification

/**
 * FR2, FR7 (design D16) of add-git-workflow: the refusal raised when a taskId cannot be sanitized
 * into a safe branch/worktree name segment. The message quotes the offending, UN-sanitized id
 * beside the reason, and the id stays readable off the exception so the task-creation boundary can
 * report it.
 *
 * Added by task 8.7 of split-into-modules (design D13(c)).
 */
class InvalidTaskIdExceptionSpec extends Specification {

    // FR2, D16: the message quotes the raw id and names why sanitization rejected it.
    def "quotes the offending taskId beside the reason"() {
        when:
        def ex = new InvalidTaskIdException('///', 'sanitizes to an empty string')

        then:
        ex.message == 'invalid taskId "///": sanitizes to an empty string'
    }

    // FR7: the boundary reports the un-sanitized id itself, so it is carried as its own fact
    // rather than only embedded in the message.
    def "carries the un-sanitized taskId as a readable fact"() {
        when:
        def ex = new InvalidTaskIdException('PROJ-1.lock', 'ends in .lock')

        then:
        ex.taskId() == 'PROJ-1.lock'
    }
}

package com.github.oinsio.gnomish.adapter.git

import spock.lang.Specification
import spock.lang.Unroll

/**
 * FR2, FR7 of add-git-workflow (design D16): deterministic, lossy taskId sanitization
 * shared by the task branch name (FR2) and the worktree directory name (FR6).
 */
class TaskIdSanitizerSpec extends Specification {

    @Unroll
    def "FR2: sanitize(#taskId) == #expected"() {
        expect:
        TaskIdSanitizer.sanitize(taskId) == expected

        where:
        taskId                    | expected
        'PROJ-42'                 | 'PROJ-42'
        'PROJ 42: fix/it'         | 'PROJ-42-fix-it'
        'a///b'                   | 'a-b'
        '-foo-'                   | 'foo'
        '.foo.'                   | 'foo'
        '!!!foo!!!'               | 'foo'
        'foo.bar_baz-qux'         | 'foo.bar_baz-qux'
        'é日本語task'               | 'task'
    }

    @Unroll
    def "FR2: branchName(#taskId) == #expected"() {
        expect:
        TaskIdSanitizer.branchName(taskId) == expected

        where:
        taskId            | expected
        'PROJ-42'         | 'gnomish/PROJ-42'
        'PROJ 42: fix/it' | 'gnomish/PROJ-42-fix-it'
    }

    @Unroll
    def "FR2: sanitize(#taskId) is rejected as invalid"() {
        when:
        TaskIdSanitizer.sanitize(taskId)

        then:
        def e = thrown(InvalidTaskIdException)
        e.message.contains(taskId)

        where:
        taskId << [
            '!!!',
            '///',
            'foo.lock',
            '...---...',
            '   ',
        ]
    }

    def "FR2: branchName also rejects an invalid taskId"() {
        when:
        TaskIdSanitizer.branchName('!!!')

        then:
        thrown(InvalidTaskIdException)
    }

    def "FR2: InvalidTaskIdException#taskId() returns the offending, un-sanitized taskId"() {
        when:
        TaskIdSanitizer.sanitize('!!!')

        then:
        def e = thrown(InvalidTaskIdException)
        e.taskId() == '!!!'
    }
}

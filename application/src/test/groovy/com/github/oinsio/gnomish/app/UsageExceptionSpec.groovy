package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * FR10, D10 of add-claim-heartbeat: the shared "no branch found" refusal both resume bootstraps
 * raise once branch lookup for a resumed taskId comes back empty.
 */
class UsageExceptionSpec extends Specification {

    // FR10, D10: the message names the original taskId and the sanitized branch name it looked for.
    def "names the taskId and the sanitized branch name it could not find"() {
        when:
        def ex = UsageException.branchNotFound('PROJ-1')

        then:
        ex.message == 'could not resume task "PROJ-1": no branch "gnomish/PROJ-1" found locally, ' +
                'as a remote-tracking ref, or on origin (even after a fetch attempt)'
    }
}

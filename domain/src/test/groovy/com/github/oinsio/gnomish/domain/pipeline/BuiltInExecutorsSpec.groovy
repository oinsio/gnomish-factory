package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * FR1, FR13 of migrate-verify-to-executor-contract (design D18, "The six built-in names have one
 * owner"): the one set of the six built-in names, and the check-token constants that refer to it.
 */
class BuiltInExecutorsSpec extends Specification {

    def "the set is the six built-ins, in reading order, and the check tokens' constants are its own"() {
        expect:
        BuiltInExecutors.NAMES == [
            'files-exist',
            'shell',
            'github-actions',
            'scm-status',
            'http',
            'command'
        ]
        VerifyCheck.Executor.FILES_EXIST == BuiltInExecutors.FILES_EXIST
        VerifyCheck.Executor.SHELL == BuiltInExecutors.SHELL
        VerifyCheck.Executor.GITHUB_ACTIONS == BuiltInExecutors.GITHUB_ACTIONS
        VerifyCheck.Executor.SCM_STATUS == BuiltInExecutors.SCM_STATUS
    }

    def "'#name' is a built-in's name: #builtIn"() {
        expect:
        BuiltInExecutors.isBuiltIn(name) == builtIn

        where:
        name << BuiltInExecutors.NAMES + ['acme', 'github', '']
        builtIn << [true] * 6 + [false] * 3
    }
}

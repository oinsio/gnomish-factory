package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * FR1, FR13 of migrate-verify-to-executor-contract (design D18, "The six built-in names have one
 * owner"); FR14 of extract-agent-round-runner (design D12): the one set of the seven built-in names,
 * the six of them a check may name, and the check-token constants that refer to it.
 */
class BuiltInExecutorsSpec extends Specification {

    def "the set is the seven built-ins, in reading order, and the check tokens' constants are its own"() {
        expect:
        BuiltInExecutors.NAMES == [
            'files-exist',
            'shell',
            'github-actions',
            'scm-status',
            'http',
            'command',
            'claude'
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
        builtIn << [true] * 7 + [false] * 3
    }

    // FR14, D12 of extract-agent-round-runner: the agent entry is reserved, but no check names it
    def "a check may name every built-in but the reserved agent entry"() {
        expect:
        BuiltInExecutors.CLAUDE == 'claude'
        BuiltInExecutors.CHECK_NAMES == BuiltInExecutors.NAMES - ['claude']
    }
}

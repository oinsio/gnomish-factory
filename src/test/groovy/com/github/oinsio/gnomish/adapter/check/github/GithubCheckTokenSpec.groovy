package com.github.oinsio.gnomish.adapter.check.github

import spock.lang.Specification

/**
 * {@link GithubCheckToken} (task 3.5): the missing-variable branch, proven against the real
 * process environment since mutating it is not reliably possible on a module-path JVM without
 * {@code --add-opens} (same constraint documented on {@code GithubTrackerAdapterFactory}'s
 * {@code requireToken()}).
 *
 * <p>Implements FR8, NFR-S1 of add-external-check-github-actions.
 */
class GithubCheckTokenSpec extends Specification {

    def "missing GNOMISH_GITHUB_ACTIONS_TOKEN refuses clearly and names the variable"() {
        given:
        def previousToken = System.getenv(GithubCheckToken.TOKEN_ENV_VAR)

        expect: 'this test only runs meaningfully when the real environment has no token set'
        previousToken == null || previousToken.isBlank()

        when:
        GithubCheckToken.requireToken()

        then:
        def ex = thrown(GithubCheckTokenException)
        ex.message.contains(GithubCheckToken.TOKEN_ENV_VAR)
    }
}

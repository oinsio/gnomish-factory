package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import java.time.Duration
import spock.lang.Specification

/**
 * FR11, FR17 of add-plugin-architecture; task 5.2 of migrate-verify-to-executor-contract: the {@code
 * http} built-in's describe declares no credential, since each check names its own in {@code auth};
 * {@link HttpExecutor#credentials} is the one reading of that name the run's scrub and
 * never-allowlist sets take it from.
 */
class HttpExecutorCredentialsSpec extends Specification {

    private static VerifyCheck.Executor check(Map settings) {
        VerifyChecks.external('gate', 'http', settings, Duration.ofSeconds(1), Duration.ofSeconds(30),
                VerifyCheck.TimeoutClass.QUALITY, [])
    }

    def "a check's own auth credential is its one credential name, and an unauthenticated check names none"() {
        expect:
        HttpExecutor.credentials(check([url: 'https://ci.example.invalid', auth: [credential: 'GNOMISH_SONAR_TOKEN']])) == [
            'GNOMISH_SONAR_TOKEN'
        ]
        HttpExecutor.credentials(check([url: 'https://ci.example.invalid'])) == []

        and: 'the describe still declares none: the name is the check\'s, not the built-in\'s'
        HttpExecutor.describeAnswer().credentials() == []
    }
}

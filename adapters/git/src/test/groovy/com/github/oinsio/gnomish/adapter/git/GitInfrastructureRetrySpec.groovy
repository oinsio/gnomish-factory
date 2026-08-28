package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import spock.lang.Specification

/**
 * FR6, FR7 of harden-task-branch-contract: the infrastructure budget spent on a git network
 * question that was never answered — bounded attempts, doubling backoff, and no attempt at all
 * once the question settles.
 */
class GitInfrastructureRetrySpec extends Specification {

    List<Duration> slept = []
    def sleeper = { Duration d -> slept << d } as Sleeper

    def "FR6: a settled first result is returned without sleeping or re-running"() {
        given:
        def runs = 0
        def retry = new GitInfrastructureRetry(sleeper, 3, Duration.ofMillis(500))

        when:
        def result = retry.until({ runs++; 'settled' }, { it == 'settled' })

        then:
        result == 'settled'
        runs == 1
        slept.isEmpty()
    }

    def "FR6: an unsettled result is re-attempted up to the budget, with the backoff doubling"() {
        given:
        def runs = 0
        def retry = new GitInfrastructureRetry(sleeper, 3, Duration.ofMillis(500))

        when:
        def result = retry.until({ runs++; 'unsettled' }, { false })

        then: 'three attempts in total, so two waits between them'
        result == 'unsettled'
        runs == 3
        slept == [
            Duration.ofMillis(500),
            Duration.ofSeconds(1)
        ]
    }

    def "FR6: the loop stops at the first attempt that settles the question"() {
        given:
        def runs = 0
        def retry = new GitInfrastructureRetry(sleeper, 3, Duration.ofMillis(500))

        when:
        def result = retry.until({
            ++runs == 2 ? 'settled' : 'unsettled'
        }, {
            it == 'settled'
        })

        then:
        result == 'settled'
        runs == 2
        slept == [Duration.ofMillis(500)]
    }

    def "FR6: a single-attempt budget never sleeps"() {
        given:
        def runs = 0

        when:
        def result = new GitInfrastructureRetry(sleeper, 1, Duration.ofMillis(500))
                .until({ runs++; 'unsettled' }, { false })

        then:
        result == 'unsettled'
        runs == 1
        slept.isEmpty()
    }

    def "FR6: the production budget is three attempts from a half-second backoff"() {
        expect:
        GitInfrastructureRetry.DEFAULT_ATTEMPTS == 3
        GitInfrastructureRetry.DEFAULT_INITIAL_BACKOFF == Duration.ofMillis(500)
        GitInfrastructureRetry.system().attempts() == GitInfrastructureRetry.DEFAULT_ATTEMPTS
        GitInfrastructureRetry.system().initialBackoff() == GitInfrastructureRetry.DEFAULT_INITIAL_BACKOFF
    }
}

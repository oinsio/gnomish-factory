package com.github.oinsio.gnomish.app.describe

import com.github.oinsio.gnomish.executorcontract.Bounds
import java.time.Duration
import spock.lang.Specification

/**
 * {@link ProgramBounds} (NFR-R2 of define-executor-contract): the factory's three bounds on a
 * {@code program} executor, each positive, sent as the request's time hints.
 */
class ProgramBoundsSpec extends Specification {

    // NFR-R2: the defaults are 30 s to describe, 5 min of silence, 30 min per run
    def "NFR-R2: the defaults are the factory's three bounds"() {
        expect:
        ProgramBounds.DEFAULTS == new ProgramBounds(Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(30))
    }

    // NFR-R2: the bounds travel as hints in milliseconds; no budget bound is declared
    def "NFR-R2: the hints carry the three bounds in milliseconds and no turn, token or money bound"() {
        expect:
        ProgramBounds.DEFAULTS.hints() == new Bounds(null, null, null, 30_000L, 300_000L, 1_800_000L)
    }

    // NFR-R2: a bound that is zero or negative is refused, naming it
    def "NFR-R2: a #what #name bound is refused"() {
        when:
        new ProgramBounds(name == 'startup' ? value : Duration.ofSeconds(1),
                name == 'silence' ? value : Duration.ofSeconds(1), name == 'overall' ? value : Duration.ofSeconds(1))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "the $name bound must be positive, was $value"

        where:
        name | what | value
        'startup' | 'zero' | Duration.ZERO
        'silence' | 'negative' | Duration.ofSeconds(-1)
        'overall' | 'zero' | Duration.ZERO
    }
}

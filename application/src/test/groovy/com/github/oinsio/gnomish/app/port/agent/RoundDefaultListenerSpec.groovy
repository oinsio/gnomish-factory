package com.github.oinsio.gnomish.app.port.agent

import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR5 of add-sandbox-core: {@link RoundEnvironmentSource.Round#roundListener}'s own default. Only
 * the sandboxed source overrides it (with its rate-limited mid-round harvest poll); the host
 * source does not, so the executor joins this default to its own listener on every host round. It
 * therefore has to be a real, silently-discarding listener — a null would fail the join at the
 * start of every host round.
 *
 * Added by task 8.7 of split-into-modules (design D13(c)).
 */
class RoundDefaultListenerSpec extends Specification {

    /** A Round declaring only the members the interface has no default for. */
    private static class MinimalRound implements RoundEnvironmentSource.Round {

        @Override
        TaskExecutionEnvironment environment() {
            throw new UnsupportedOperationException('not used by this spec')
        }

        @Override
        Path decisionFilePath() {
            Path.of('decision.json')
        }

        @Override
        Map<String, String> decisionEnvFragment() {
            [:]
        }

        @Override
        void closeRound() {}

        @Override
        Optional<String> readDecision() {
            Optional.empty()
        }

        @Override
        void discard() {}
    }

    // FR5: a round that declares no listener still hands the executor a usable one — the executor
    // joins it unconditionally, so an absent override must yield a listener, never null.
    def "a round that overrides nothing still supplies a listener"() {
        expect:
        new MinimalRound().roundListener() != null
    }

    // FR5: the default discards silently. Progress delivery is inline on the parse loop's critical
    // path, so the do-nothing default must return promptly and must not throw.
    def "the default listener silently discards the events it is given"() {
        given:
        def listener = new MinimalRound().roundListener()

        when:
        listener.onProgress(null)

        then:
        noExceptionThrown()
    }
}

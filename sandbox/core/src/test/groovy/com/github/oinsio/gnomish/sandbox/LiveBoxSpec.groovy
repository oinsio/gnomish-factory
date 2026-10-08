package com.github.oinsio.gnomish.sandbox

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import spock.lang.Specification

/**
 * FR21 of make-checkpoint-gate-durable (D13): one live box per role, rebuilt when its key
 * changes — the single-threaded contract. The real-thread properties (a reader never waits, a
 * same-key request joins the one build, dispose waits for a build) are
 * {@code LiveBoxConcurrencySpec}'s. Every call runs through {@link #promptly}: a regression that
 * leaves a claim uncleared or loops on a settled build fails here within a bounded wait instead
 * of hanging the feature (the mutation gate counts a hang as TIMED_OUT, never as KILLED).
 */
class LiveBoxSpec extends Specification {

    def events = []
    List<TaskExecutionEnvironment> boxes = []
    RuntimeException failNext = null

    private LiveBox<String> liveBox() {
        new LiveBox<String>({ box(boxes.size() + 1) }, { env, key ->
            events << "materialize-${boxes.findIndexOf { it.is(env) } + 1}:${key}"
            if (failNext != null) {
                def failure = failNext
                failNext = null
                throw failure
            }
        })
    }

    private static final long BOUND_SECONDS = 5

    /**
     * Runs {@code action} on a daemon thread and returns its value or rethrows its failure; a call
     * that has not returned within the bound is interrupted and fails the feature, so a stuck
     * thread can neither hang the feature nor keep the JVM alive.
     */
    private static <T> T promptly(Closure<T> action) {
        def result = new CompletableFuture<T>()
        def worker = Thread.ofPlatform().daemon().start {
            try {
                result.complete(action())
            } catch (Throwable failure) {
                result.completeExceptionally(failure)
            }
        }
        try {
            return result.get(BOUND_SECONDS, TimeUnit.SECONDS)
        } catch (ExecutionException failed) {
            throw failed.cause
        } catch (TimeoutException ignored) {
            worker.interrupt()
            throw new AssertionError("the call did not return within ${BOUND_SECONDS} s" as Object)
        }
    }

    private TaskExecutionEnvironment box(int id) {
        def env = Stub(TaskExecutionEnvironment) {
            dispose() >> { events << "dispose-${id}" }
        }
        boxes << env
        env
    }

    def "FR21: the same key gets the same box with no second materialize"() {
        given:
        def live = liveBox()

        when:
        def first = promptly { live.environmentFor('a') }
        def second = promptly { live.environmentFor('a') }

        then:
        first.is(second)
        events == ['materialize-1:a']
        live.current().get().is(first)
    }

    def "FR21: a new key disposes the previous box before building the new one"() {
        given:
        def live = liveBox()
        def first = promptly { live.environmentFor('a') }

        when:
        def second = promptly { live.environmentFor('b') }

        then:
        !second.is(first)
        events == [
            'materialize-1:a',
            'dispose-1',
            'materialize-2:b'
        ]
        live.current().get().is(second)
    }

    def "FR21: a failed materialize throws its own exception, records nothing, and the next call builds again"() {
        given:
        def live = liveBox()
        def failure = new IllegalStateException('self-check failed')
        failNext = failure

        when:
        promptly { live.environmentFor('a') }

        then:
        def thrownFailure = thrown(IllegalStateException)
        thrownFailure.is(failure)
        live.current().isEmpty()

        when:
        def retried = promptly { live.environmentFor('a') }

        then: 'the claim was cleared: a second build runs and is recorded'
        events == [
            'materialize-1:a',
            'materialize-2:a'
        ]
        live.current().get().is(retried)
    }

    def "FR21: a failed box is never disposed here — it is not recorded"() {
        given:
        def live = liveBox()
        failNext = new IllegalStateException('self-check failed')

        when:
        promptly { live.environmentFor('a') }

        then:
        thrown(IllegalStateException)

        when:
        promptly { live.dispose() }

        then:
        events == ['materialize-1:a']
    }

    def "FR21: current() is empty before the first build and retiredBy names only a box under another key"() {
        given:
        def live = liveBox()

        expect:
        live.current().isEmpty()
        live.retiredBy('a').isEmpty()

        when:
        def box = promptly { live.environmentFor('a') }

        then:
        live.retiredBy('a').isEmpty()
        live.retiredBy('b').get().is(box)
        events == ['materialize-1:a']
    }

    def "FR21: dispose is idempotent and empties the record"() {
        given:
        def live = liveBox()
        promptly { live.environmentFor('a') }

        when:
        promptly { live.dispose() }
        promptly { live.dispose() }

        then:
        events == [
            'materialize-1:a',
            'dispose-1'
        ]
        live.current().isEmpty()
        live.retiredBy('b').isEmpty()
    }
}

package com.github.oinsio.gnomish.httpegress

import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import spock.lang.Specification

/**
 * FR15, NFR-R5 of migrate-verify-to-executor-contract, design D16: the exchange's own body
 * subscriber and the pending body that abandons it — the bytes counted against the consumer's bound,
 * the subscription cancelled past it or when the caller gives the request up, and the JDK's future
 * cancelled with it. Driven directly, with a subscription that records what it was asked.
 */
class BoundedBodySpec extends Specification {

    /** A subscription that records the demand and the cancel it was given. */
    static class RecordingSubscription implements Flow.Subscription {
        long requested
        boolean cancelled

        void request(long n) {
            requested += n
        }

        void cancel() {
            cancelled = true
        }
    }

    private static List<ByteBuffer> bytes(String text) {
        [ByteBuffer.wrap(text.bytes)]
    }

    private static BoundedBody body(int bound, boolean tail = false) {
        new BoundedBody(new ExchangeBounds(Duration.ofSeconds(1), bound, tail), 'http://h/x')
    }

    def "a body within the bound is requested whole and completes with every byte"() {
        given:
        def subscription = new RecordingSubscription()
        def body = body(10)

        when:
        body.onSubscribe(subscription)
        body.onNext(bytes('hello'))
        body.onNext(bytes('world'))
        body.onComplete()

        then:
        subscription.requested == Long.MAX_VALUE
        !subscription.cancelled
        new String(body.body.toCompletableFuture().get()) == 'helloworld'
    }

    def "a body past the bound cancels its subscription and fails as the response-size limit"() {
        given:
        def subscription = new RecordingSubscription()
        def body = body(4)
        body.onSubscribe(subscription)

        when:
        body.onNext(bytes('hello'))
        body.body.toCompletableFuture().get()

        then:
        def failure = thrown(ExecutionException)
        failure.cause instanceof ExchangeLimitException
        (failure.cause as ExchangeLimitException).limit() == ExchangeLimitException.Limit.RESPONSE_SIZE
        subscription.cancelled
    }

    def "a tail read keeps the last bytes and never cancels"() {
        given:
        def subscription = new RecordingSubscription()
        def body = body(3, true)
        body.onSubscribe(subscription)

        when:
        body.onNext(bytes('abcdef'))
        body.onNext(bytes('gh'))
        body.onComplete()

        then:
        new String(body.body.toCompletableFuture().get()) == 'fgh'
        !subscription.cancelled
    }

    def "a cancel reaches the subscription, and one asked before the subscription cancels it on arrival"() {
        given:
        def early = new RecordingSubscription()
        def late = new RecordingSubscription()
        def cancelledFirst = body(10)
        def subscribedFirst = body(10)

        when:
        cancelledFirst.cancel()
        cancelledFirst.onSubscribe(early)
        subscribedFirst.onSubscribe(late)
        subscribedFirst.cancel()

        then:
        early.cancelled
        early.requested == 0
        late.cancelled
    }

    def "an error of the stream completes the body with it"() {
        given:
        def body = body(10)
        def failure = new IOException('reset')

        when:
        body.onError(failure)
        body.body.toCompletableFuture().get()

        then:
        def thrownFailure = thrown(ExecutionException)
        thrownFailure.cause.is(failure)
    }

    def "abandoning a pending body cancels the JDK's future and the body's subscription"() {
        given:
        def pending = new PendingBody(new ExchangeBounds(Duration.ofSeconds(1), 10, false), 'http://h/x')
        def subscriber = pending.handler().apply(null)
        def subscription = new RecordingSubscription()
        subscriber.onSubscribe(subscription)
        def future = new CompletableFuture<Object>()

        when:
        pending.abandon(future)

        then:
        future.cancelled
        subscription.cancelled
    }

    def "abandoning before the client handed over a body cancels the future alone"() {
        given:
        def future = new CompletableFuture<Object>()

        when:
        new PendingBody(new ExchangeBounds(Duration.ofSeconds(1), 10, false), 'http://h/x').abandon(future)

        then:
        future.cancelled
    }

    def "a failed exchange is the transport failure the synchronous seam declares: #shape"() {
        when:
        def mapped = JdkHttpCheckExchange.transportFailure(new ExecutionException(cause), 'http://h/x')

        then:
        mapped == expected || mapped.cause.is(cause)
        mapped.message.contains(fragment)

        where:
        shape | cause || expected | fragment
        'an I/O cause' | new java.net.ConnectException('refused') || cause | 'refused'
        'any other' | new AssertionError('boom') || null | "exchange with 'http://h/x' failed"
    }

    def "a runtime cause leaves as itself, and an absent cause is wrapped"() {
        given:
        def limit = new ExchangeLimitException(ExchangeLimitException.Limit.RESPONSE_SIZE, 'http://h/x', 'big')

        when:
        JdkHttpCheckExchange.transportFailure(new ExecutionException(limit), 'http://h/x')

        then:
        def rethrown = thrown(ExchangeLimitException)
        rethrown.is(limit)

        and:
        JdkHttpCheckExchange.transportFailure(new ExecutionException('no cause', null), 'http://h/x').cause instanceof
                ExecutionException
    }
}

package com.github.oinsio.gnomish.httpegress

import java.nio.ByteBuffer
import java.time.Duration
import spock.lang.Specification

/**
 * FR15, NFR-R5 of migrate-verify-to-executor-contract, design D16: a tail read keeps exactly the last
 * {@code maxBodyBytes} bytes of the body, whatever the shape of the chunks the client hands over —
 * the result the chunk-dropping buffer must reproduce byte for byte. The bytes cycle through every
 * value, multi-byte UTF-8 lead and continuation bytes included, so a cut is never hidden by text.
 */
class BoundedBodyTailSpec extends Specification {

    private static byte[] payload(int length, int seed = 0) {
        (0..<length).collect { ((it + seed) % 256) as byte } as byte[]
    }

    private static byte[] tailOf(List<byte[]> chunks, int limit) {
        def all = new ByteArrayOutputStream()
        chunks.each { all.write(it) }
        def bytes = all.toByteArray()
        Arrays.copyOfRange(bytes, Math.max(0, bytes.length - limit), bytes.length)
    }

    private static BoundedBody tailBody(int limit) {
        def body = new BoundedBody(new ExchangeBounds(Duration.ofSeconds(1), limit, true), 'http://h/log')
        body.onSubscribe(new BoundedBodySpec.RecordingSubscription())
        body
    }

    def "a tail read keeps exactly the last bytes: #shape"() {
        given:
        def chunks = sizes.withIndex().collect { int size, int i ->
            payload(size, i * 37)
        }
        def body = tailBody(limit)

        when:
        chunks.each { body.onNext([ByteBuffer.wrap(it)]) }
        body.onComplete()

        then:
        body.body.toCompletableFuture().get() == tailOf(chunks, limit)

        where:
        shape | limit | sizes
        'chunks larger than the limit' | 10 | [25, 17, 31]
        'chunks smaller than the limit' | 10 | [3, 4, 2, 5, 3, 4]
        'chunks exactly the limit' | 10 | [10, 10, 10]
        'a chunk ending exactly on the limit' | 10 | [4, 6, 3]
        'a cut ending exactly on a chunk boundary' | 10 | [3, 4, 6, 4]
        'many one-byte chunks' | 7 | [1] * 300
        'a single chunk larger than the limit' | 10 | [1000]
        'a body exactly the limit' | 10 | [4, 6]
        'a body under the limit' | 10 | [3, 4]
        'empty chunks between the bytes' | 5 | [0, 3, 0, 4, 0, 6, 0]
        'a limit of one byte' | 1 | [3, 1, 2]
    }

    def "chunks handed over together in one call are kept in order"() {
        given:
        def chunks = [
            payload(6, 1),
            payload(9, 2),
            payload(4, 3)
        ]
        def body = tailBody(8)

        when:
        body.onNext(chunks.collect { ByteBuffer.wrap(it) })
        body.onNext([
            ByteBuffer.wrap(payload(2, 4))
        ])
        body.onComplete()

        then:
        body.body.toCompletableFuture().get() == tailOf(chunks + [payload(2, 4)], 8)
    }

    def "a buffer the client reuses after handing it over does not change the bytes kept"() {
        given:
        def reused = ByteBuffer.wrap('abcd'.bytes)
        def body = tailBody(6)

        when:
        body.onNext([reused])
        reused.clear()
        reused.put('WXYZ'.bytes).flip()
        body.onNext([reused])
        reused.clear()
        reused.put('????'.bytes)
        body.onComplete()

        then:
        new String(body.body.toCompletableFuture().get()) == 'cdWXYZ'
    }

    def "a whole read of a body exactly the bound, in several chunks, completes with every byte"() {
        given:
        def chunks = [
            payload(3),
            payload(4, 1),
            payload(3, 2)
        ]
        def body = new BoundedBody(ExchangeBounds.whole(Duration.ofSeconds(1), 10), 'http://h/x')
        body.onSubscribe(new BoundedBodySpec.RecordingSubscription())

        when:
        chunks.each { body.onNext([ByteBuffer.wrap(it)]) }
        body.onComplete()

        then:
        body.body.toCompletableFuture().get() == tailOf(chunks, 10)
    }
}

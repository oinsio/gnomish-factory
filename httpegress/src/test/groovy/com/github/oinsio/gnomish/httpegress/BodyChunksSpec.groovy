package com.github.oinsio.gnomish.httpegress

import java.nio.ByteBuffer
import spock.lang.Specification

/**
 * FR15, NFR-R5 of migrate-verify-to-executor-contract, design D16: a tail read holds only the chunks
 * its kept bytes span — a consumed chunk leaves the buffer — so a long log is held at the bound plus
 * one chunk, never in full. The bytes kept are pinned through the body in {@code BoundedBodyTailSpec}.
 */
class BodyChunksSpec extends Specification {

    def "keeping the last bytes holds exactly the chunks they span: #shape"() {
        given:
        def chunks = new BodyChunks()
        sizes.each { chunks.append(ByteBuffer.wrap(new byte[it])) }

        when:
        chunks.keepLast(limit)

        then:
        chunks.size() == limit
        chunks.heldChunks() == held

        where:
        shape | limit | sizes || held
        'a cut ending exactly on a chunk boundary' | 10 | [3, 4, 6, 4] || 2
        'a cut inside a chunk' | 10 | [3, 4, 6, 5] || 2
        'many one-byte chunks' | 7 | [1] * 300 || 7
        'empty chunks consumed by the cut' | 5 | [0, 3, 0, 4, 0, 6, 0] || 2
        'a single chunk larger than the limit' | 10 | [1000] || 1
    }
}

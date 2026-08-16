package com.github.oinsio.gnomish.app.lease

import java.time.Instant
import spock.lang.Specification

/**
 * HeartbeatPayload: renders the one-line progress payload a beat writes into the claim
 * marker (design D1) — stage=<x> attempt=<n> alive-at=<iso>, alive-at rendered as an
 * ISO-8601 instant.
 *
 * <p>FR1, UX1 of add-claim-heartbeat: this payload IS the operator-facing live status line —
 * the claim comment doubles as stage/attempt/last-alive without a new comment per beat.
 */
class HeartbeatPayloadSpec extends Specification {

    // FR1, UX1: the payload carries stage, attempt, and the alive-at instant in the fixed format —
    // the live status line an operator reads straight from the claim comment.
    def "renders stage, attempt and alive-at in the fixed format"() {
        given:
        def progress = new HeartbeatProgress.Progress('implement', 3)
        def aliveAt = Instant.parse('2026-07-29T10:11:12Z')

        expect:
        HeartbeatPayload.render(progress, aliveAt) == 'stage=implement attempt=3 alive-at=2026-07-29T10:11:12Z'
    }

    // FR1: the pending placeholder renders without special-casing.
    def "renders the pending placeholder verbatim"() {
        expect:
        HeartbeatPayload.render(HeartbeatProgress.PENDING, Instant.EPOCH) == 'stage=(pending) attempt=0 alive-at=1970-01-01T00:00:00Z'
    }
}

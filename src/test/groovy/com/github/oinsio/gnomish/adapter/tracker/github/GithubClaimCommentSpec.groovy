package com.github.oinsio.gnomish.adapter.tracker.github

import java.time.Instant
import spock.lang.Specification

/**
 * GithubClaimComment (add-claim-heartbeat, design D1/D5): the boundary-aware
 * resolver that picks a task's live claim comment — the earliest-id CLAIM
 * marker posted after the latest session-ending boundary marker (abort/
 * report) — and carries back its comment id, {@code updated_at}, and parsed
 * marker so {@code heartbeat} (task 3.1), {@code listOpen} (3.2), and {@code
 * removeStaleClaim} (3.3) share one resolution. Pure over parsed candidates —
 * no HTTP — so it is exercised here without WireMock.
 *
 * Implements FR1, FR5 of add-claim-heartbeat.
 */
class GithubClaimCommentSpec extends Specification {

    private static String claim(long id, String instance, String at) {
        markerComment(id, at, 'claim', instance, at, null)
    }

    private static String abort(long id, String instance, String at) {
        markerComment(id, at, 'abort', instance, at, null)
    }

    private static String report(long id, String instance, String at, String reason = null) {
        markerComment(id, at, 'report', instance, at, reason)
    }

    private static String staleClaimRemoved(long id, String instance, String at) {
        markerComment(id, at, 'stale_claim_removed', instance, at, null)
    }

    private static String markerComment(long id, String updatedAt, String kind, String instance, String at, String reason) {
        def json = reason == null
                ? /{\"kind\":\"${kind}\",\"instance\":\"${instance}\",\"at\":\"${at}\",\"version\":1}/
                : /{\"kind\":\"${kind}\",\"instance\":\"${instance}\",\"at\":\"${at}\",\"version\":1,\"reason\":\"${reason}\"}/
        """{"id":${id},"updated_at":"${updatedAt}","body":"<!-- gnomish ${json} -->\\n🤖 ${kind}"}"""
    }

    private static String array(String... comments) {
        '[' + comments.join(',') + ']'
    }

    def "FR1: resolves the single post-boundary claim comment with id, updated_at and marker"() {
        given:
        def json = array(claim(501, 'gnomish-factory-solo', '2026-07-23T10:00:00Z'))

        when:
        def resolved = GithubClaimComment.resolve(GithubClaimComment.parse(json))

        then:
        resolved.isPresent()
        resolved.get().id() == 501L
        resolved.get().updatedAt() == Instant.parse('2026-07-23T10:00:00Z')
        resolved.get().marker().instance() == 'gnomish-factory-solo'
        resolved.get().marker().kind() == GithubMarkerKind.CLAIM
    }

    def "FR5: earliest comment id wins among competing post-boundary claims"() {
        given: 'three claims race in mixed id order: a smaller id must replace the running winner, a larger must not'
        def json = array(
                claim(601, 'gnomish-factory-a', '2026-07-23T10:00:01Z'),
                claim(600, 'gnomish-factory-b', '2026-07-23T10:00:00Z'),
                claim(605, 'gnomish-factory-c', '2026-07-23T10:00:02Z'))

        when:
        def resolved = GithubClaimComment.resolve(GithubClaimComment.parse(json))

        then: 'the earliest id (600, B) is the claim comment regardless of list position'
        resolved.get().id() == 600L
        resolved.get().marker().instance() == 'gnomish-factory-b'
    }

    def "FR5: claims before the latest boundary marker are ignored"() {
        given: 'a stale claim precedes an abort boundary; a fresh claim follows it'
        def json = array(
                claim(1, 'gnomish-factory-stale', '2026-07-20T09:00:00Z'),
                abort(2, 'gnomish-factory-stale', '2026-07-20T10:00:00Z'),
                claim(900, 'gnomish-factory-fresh', '2026-07-23T10:00:00Z'))

        when:
        def resolved = GithubClaimComment.resolve(GithubClaimComment.parse(json))

        then: 'the pre-abort claim (id 1) is voided; the post-boundary claim wins even with a larger id'
        resolved.get().id() == 900L
        resolved.get().marker().instance() == 'gnomish-factory-fresh'
    }

    def "FR5: a report (park/finish) marker is a boundary that voids earlier claims"() {
        given:
        def json = array(
                claim(1, 'gnomish-factory-old', '2026-07-20T09:00:00Z'),
                report(2, 'gnomish-factory-old', '2026-07-20T11:00:00Z', 'checkpoint'),
                claim(910, 'gnomish-factory-fresh', '2026-07-24T10:00:00Z'))

        when:
        def resolved = GithubClaimComment.resolve(GithubClaimComment.parse(json))

        then:
        resolved.get().id() == 910L
    }

    def "FR5: only the LATEST boundary anchors the window"() {
        given: 'two boundaries; a claim sits between them and must be voided by the second'
        def json = array(
                abort(1, 'gnomish-factory-x', '2026-07-20T09:00:00Z'),
                claim(2, 'gnomish-factory-mid', '2026-07-20T09:30:00Z'),
                report(3, 'gnomish-factory-mid', '2026-07-20T10:00:00Z'),
                claim(920, 'gnomish-factory-fresh', '2026-07-24T10:00:00Z'))

        when:
        def resolved = GithubClaimComment.resolve(GithubClaimComment.parse(json))

        then: 'the mid claim (id 2) is before the latest boundary (the report); only the fresh claim survives'
        resolved.get().id() == 920L
    }

    def "FR4: a stale-claim-removed marker is a boundary that voids the removed claim (task 3.4 anchor)"() {
        given: 'a pre-removal claim, the reaper removal boundary, then a fresh re-claim'
        def json = array(
                claim(1, 'gnomish-factory-dead', '2026-07-20T09:00:00Z'),
                staleClaimRemoved(2, 'gnomish-factory-reaper', '2026-07-20T10:00:00Z'),
                claim(930, 'gnomish-factory-fresh', '2026-07-24T10:00:00Z'))

        when:
        def resolved = GithubClaimComment.resolve(GithubClaimComment.parse(json))

        then: 'the pre-removal claim (id 1) is voided; only the post-removal claim wins despite its larger id'
        resolved.get().id() == 930L
        resolved.get().marker().instance() == 'gnomish-factory-fresh'
    }

    def "FR4: a stale-claim-removed marker with no claim after it resolves to empty"() {
        given: 'the reaper removed the claim and no new claim has been posted yet'
        def json = array(
                claim(1, 'gnomish-factory-dead', '2026-07-20T09:00:00Z'),
                staleClaimRemoved(2, 'gnomish-factory-reaper', '2026-07-20T10:00:00Z'))

        expect:
        GithubClaimComment.resolve(GithubClaimComment.parse(json)).isEmpty()
    }

    def "FR5: no claim comment resolves to empty"() {
        given: 'the latest boundary is an abort with no claim after it'
        def json = array(
                claim(1, 'gnomish-factory-old', '2026-07-20T09:00:00Z'),
                abort(2, 'gnomish-factory-old', '2026-07-20T10:00:00Z'))

        expect:
        GithubClaimComment.resolve(GithubClaimComment.parse(json)).isEmpty()
    }

    def "FR5: an empty comment thread resolves to empty"() {
        expect:
        GithubClaimComment.resolve(GithubClaimComment.parse('[]')).isEmpty()
    }

    def "FR1: non-marker comments (operator replies) are skipped by parse"() {
        given:
        def json = array(
                '''{"id":700,"updated_at":"2026-07-23T09:00:00Z","body":"just a human comment"}''',
                claim(701, 'gnomish-factory-solo', '2026-07-23T10:00:00Z'))

        when:
        def comments = GithubClaimComment.parse(json)

        then:
        comments.size() == 1
        comments[0].id() == 701L
    }

    def "malformed comments JSON surfaces as GithubClaimException"() {
        when:
        GithubClaimComment.parse('{not json')

        then:
        thrown(GithubClaimException)
    }
}

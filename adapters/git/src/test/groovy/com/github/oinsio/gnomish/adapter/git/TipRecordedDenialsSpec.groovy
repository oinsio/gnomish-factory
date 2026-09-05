package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.sandbox.DenialCursor
import spock.lang.Specification

/**
 * FR4, FR5 of fix-denial-attribution-durability: which committed denial position a branch tip
 * offers a resuming instance. Driven over an in-memory tip so every branch shape — including the
 * quarantining ones no repository can be made to produce on demand — is a plain input.
 */
class TipRecordedDenialsSpec extends Specification {

    def cursors = new TipRecordedDenials()

    /** A tip of exactly the two envelope texts a scenario hands it. */
    private static BranchTipSource tip(String taskJson, String stateJson) {
        new BranchTipSource() {
                    @Override
                    Optional<String> readAtTip(String path) {
                        path.endsWith('task.json') ? Optional.ofNullable(taskJson) : Optional.ofNullable(stateJson)
                    }

                    @Override
                    Optional<ClaimEpoch> tipEpoch() {
                        Optional.empty()
                    }

                    @Override
                    boolean cleanupCommitInHistory() {
                        false
                    }
                }
    }

    private static String taskJson(String cursor = null, String decisions = '[]') {
        """
        {
          "version": 1, "taskId": "T-1", "title": "t", "body": "b",
          "createdAt": "2026-09-05T09:00:00Z", "baseCommit": "abc123",
          "decisions": ${decisions}, "outcome": null, "lastEscalation": null
          ${cursor == null ? '' : ', "egressCursor": ' + cursor}
        }
        """
    }

    private static String stateJson(String cursor = null, int version = 1) {
        """
        {
          "version": ${version},
          "position": { "type": "atStage", "stage": "build" },
          "attemptsUsed": 0, "attempts": [],
          "totals": { "inputTokens": 0, "outputTokens": 0, "costUsd": null, "byTool": [] }
          ${cursor == null ? '' : ', "egressCursor": ' + cursor}
        }
        """
    }

    private static String cursor(String source, String position) {
        """{ "source": "${source}", "position": "${position}" }"""
    }

    // FR4: same source means the two positions are that daemon's own timestamps, totally ordered
    def "the newest position of the same source wins, whichever envelope carries it"() {
        expect:
        cursors.restorable(tip(taskJson(cursor('guard-1', escalation)), stateJson(cursor('guard-1', attempt))))
                .position().orElseThrow().position() == winner

        where:
        attempt | escalation || winner
        '2026-09-05T10:00:00.000000001Z' | '2026-09-05T10:05:00.000000001Z' || '2026-09-05T10:05:00.000000001Z'
        '2026-09-05T10:05:00.000000001Z' | '2026-09-05T10:00:00.000000001Z' || '2026-09-05T10:05:00.000000001Z'
        '2026-09-05T10:05:00.000000001Z' | '2026-09-05T10:05:00.000000001Z' || '2026-09-05T10:05:00.000000001Z'
    }

    def "a tip carrying only one of the two positions offers that one"() {
        expect:
        cursors.restorable(tip(task, state)).position().orElseThrow().position() == offered

        where:
        task | state || offered
        taskJson() | stateJson(cursor('guard-1', '2026-09-05T10:00:00.000000001Z')) || '2026-09-05T10:00:00.000000001Z'
        taskJson(cursor('guard-1', '2026-09-05T10:05:00.000000001Z')) | stateJson() || '2026-09-05T10:05:00.000000001Z'
    }

    // FR4: two positions of two different sources cannot be ordered here — the live source's
    //     identity is the environment's to know — so the fresher envelope's is offered and the
    //     environment's stamp check drops it if that guess was wrong
    def "positions of different sources fall back to the attempt-side one"() {
        expect:
        cursors.restorable(tip(
                        taskJson(cursor('guard-old', '2026-09-05T10:05:00.000000001Z')),
                        stateJson(cursor('guard-new', '2026-09-05T10:00:00.000000001Z'))))
                .position()
                .orElseThrow() == new DenialCursor(
                'guard-new', '2026-09-05T10:00:00.000000001Z')
    }

    def "a tip carrying neither position offers none"() {
        expect:
        cursors.restorable(tip(taskJson(), stateJson())).position().isEmpty()
    }

    // FR4: a shape the contract quarantines carries nothing this factory trusts — a position
    //     parsed out of it would silence real denials on the strength of a refused document
    def "a quarantining shape offers no position at all"() {
        expect:
        cursors.restorable(tip(
                        taskJson(cursor('guard-1', '2026-09-05T10:05:00.000000001Z')),
                        stateJson(cursor('guard-1', '2026-09-05T10:00:00.000000001Z'), 2)))
                .position()
                .isEmpty()
    }

    def "a tip with no envelopes at all offers no position"() {
        expect:
        cursors.restorable(tip(null, null)).position().isEmpty()
    }
}

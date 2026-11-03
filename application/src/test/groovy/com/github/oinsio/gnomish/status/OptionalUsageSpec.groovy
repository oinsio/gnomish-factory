package com.github.oinsio.gnomish.status

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.ModelUsage
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.UsageCost
import com.github.oinsio.gnomish.domain.engine.UsageFold
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.status.json.StatusReportJsonMapper
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * NFR-C1, "Human-only run validates": a run executed entirely by a human reports
 * absent tokens and empty tool/vote aggregates while remaining a contract-valid
 * JSON document, with wall-clock fields always present (spec.md, "Optional usage").
 *
 * Verified at the JSON-tree level — parsed back from the serialized string — so
 * assertions target actual JSON null / empty array, independent of formatting.
 */
class OptionalUsageSpec extends Specification {

    def mapper = new StatusReportJsonMapper()
    def objectMapper = new ObjectMapper()

    def "FR-NFR-C1: human-only rounds fold to no model entry, every round counted, the cost unknown"() {
        given: "two human-executed rounds: measured wall time, empty tools, unreported tokens"
        def rounds = [0, 1].collect { n ->
            AttemptRecords.identified(AttemptRecords.TASK_ID, AttemptRecords.STAGE,
            RoundToken.of(String.format('%040x', n + 1)), n, AttemptRecord.Result.PASSED, Instant.EPOCH, [],
            new ExecutorUsage(Duration.ofSeconds(90 + n), [], [:]), [], Stop.none())
        }

        when: "folded the way TaskState.totals accumulates round usage"
        def totals = UsageFold.foldAll(rounds)

        then: "no round reported tokens -> no model entry (unreported, not zero), both rounds counted"
        totals.byRole().isEmpty()
        totals.rounds() == 2

        and: "the cost is unknown, never a complete zero"
        totals.cost() == new UsageCost(BigDecimal.ZERO, [:], false)
    }

    // Scenario: observability/status-report / Human-only run validates
    def "FR-NFR-C1: Human-only run validates -- a manual run with zero token usage renders contract-valid JSON with one absent entry per round, wall time present"() {
        given: "an entirely human-executed round: real measured wallTime, empty tools, unreported tokens, no judge"
        def humanRound = new ExecutorUsage(Duration.ofMillis(183_000), [], [:])
        def attempt = AttemptRecords.of(
                0,
                AttemptRecord.Result.PASSED,
                Instant.parse("2026-07-16T14:35:10Z"),
                [],
                humanRound,
                [], Stop.none())

        and: "task totals fold from human-only rounds alone, per TaskState.recordUnburnedRound"
        def state = new TaskState(
                new Position.AtStage("implement"),
                0,
                [attempt],
                UsageFold.foldAll([attempt]), ExecutorVersions.none())
        def context = new TaskContext("manual-task-1", UntrustedText.tracker("Human-only task"), UntrustedText.tracker("body"), [])

        and: "a realistic StatusReport, mirroring StatusReportJsonMapperSpec's construction pattern"
        def report = StatusReport.build(context, state, null, null)

        when: "rendered to JSON and parsed back into a generic tree, independent of formatting"
        def json = mapper.serialize(report)
        def tree = objectMapper.readTree(json)

        then: "the document parses without error and is contract-valid (version present)"
        tree.get("version").asInt() == 1

        and: "top-level totals: a snapshot with its round counted and no model entries"
        def totals = tree.get("totals")
        totals.get("byRole").isObject()
        totals.get("byRole").isEmpty()
        totals.get("rounds").asInt() == 1
        totals.get("cost").get("unpricedTokens").isEmpty()
        !totals.get("cost").get("complete").asBoolean()

        and: "per-attempt usage (FR15 of define-executor-contract): one absent entry, empty byModel, wall time kept, no judge entry"
        def attemptJson = tree.get("currentStage").get("attempts").get(0)
        def usage = attemptJson.get("usage")
        usage.isArray()
        usage.size() == 1
        def entry = usage.get(0)
        entry.get("role").asText() == "transform"
        entry.get("provenance").asText() == "absent"
        entry.get("byModel").isObject()
        entry.get("byModel").isEmpty()
        entry.get("wallMillis").asLong() == 183_000L
        entry.get("byTool").isArray()
        entry.get("byTool").isEmpty()
        !entry.has("vote")
        !attemptJson.has("judgeUsage")
    }

    // Scenario: observability/status-report / Unpriced entry validates
    def "NFR-O3: a judge vote reporting tokens without costUsd renders contract-valid, the snapshot incomplete"() {
        given: "a round whose executor priced its tokens and whose judge vote did not"
        def attempt = AttemptRecords.of(0, AttemptRecord.Result.PASSED, Instant.parse("2026-07-16T14:35:10Z"), [], [
            Participants.transform(AttemptRecords.EXECUTOR, new ExecutorUsage(null, [],
            ['m': new ModelUsage(new TokenUsage(1, 2, 3, 4), new BigDecimal('0.25'))])),
            Participants.verdict(Participants.JUDGE_EXECUTOR, 0, ['j': new ModelUsage(new TokenUsage(5, 6, 7, 8), null)])
        ], [], Stop.none())
        def state = new TaskState(new Position.AtStage("review"), 0, [attempt], UsageFold.foldAll([attempt]), ExecutorVersions.none())
        def context = new TaskContext("manual-task-2", UntrustedText.tracker("Judged task"), UntrustedText.tracker("body"), [])

        when:
        def tree = objectMapper.readTree(mapper.serialize(StatusReport.build(context, state, null, null)))

        then: "the vote's entry carries no costUsd key"
        tree.get("version").asInt() == 1
        def vote = tree.get("currentStage").get("attempts").get(0).get("usage").get(1)
        vote.get("role").asText() == "verdict"
        !vote.get("byModel").get("j").has("costUsd")

        and: "the snapshot is a lower bound naming the unpriced tokens"
        def cost = tree.get("totals").get("cost")
        cost.get("priced").decimalValue() == new BigDecimal('0.25')
        cost.get("unpricedTokens").get("verdict").get("j").get("cacheRead").asLong() == 8L
        !cost.get("complete").asBoolean()

        and: "UX4 of define-executor-contract: the status JSON carries the rounds folded beside the cost"
        tree.get("totals").get("rounds").asInt() == 1
    }
}

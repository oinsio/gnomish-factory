package com.github.oinsio.gnomish.status

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.status.json.StatusReportJsonMapper
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

    def "FR-NFR-C1: human-only round folds to null tokens and non-null wall time via ExecutorUsage.plus"() {
        given: "two human-executed rounds: measured wall time, empty tools, null tokens"
        def first = new ExecutorUsage(Duration.ofSeconds(90), [], null)
        def second = new ExecutorUsage(Duration.ofSeconds(93), [], null)

        when: "folded the way TaskState.totals accumulates round usage"
        def totals = first.plus(second)

        then: "both operands null tokens -> sumTokens(null) short-circuits to other (null) -> total tokens stay null"
        totals.tokens() == null

        and: "wall time is summed, never null, once any round reports it"
        totals.wallTime() == Duration.ofSeconds(183)

        and: "no per-tool breakdown was ever reported"
        totals.tools() == []
    }

    def "FR-NFR-C1: Human-only run validates -- a manual run with zero token usage renders contract-valid JSON with null tokens and empty byTool/perVote, wall time present"() {
        given: "an entirely human-executed round: real measured wallTime, empty tools, null tokens, no judge"
        def humanRound = new ExecutorUsage(Duration.ofMillis(183_000), [], null)
        def attempt = new AttemptRecord(
                0,
                AttemptRecord.Result.PASSED,
                Instant.parse("2026-07-16T14:35:10Z"),
                [],
                humanRound,
                JudgeUsage.none())

        and: "task totals fold from human-only rounds alone, per TaskState.recordUnburnedRound"
        def state = new TaskState(
                new Position.AtStage("implement"),
                0,
                [attempt],
                ExecutorUsage.none().plus(humanRound))
        def context = new TaskContext("manual-task-1", "Human-only task", "body", [])

        and: "a realistic StatusReport, mirroring StatusReportJsonMapperSpec's construction pattern"
        def report = StatusReport.build(context, state, 3, LiveActivity.idle())

        when: "rendered to JSON and parsed back into a generic tree, independent of formatting"
        def json = mapper.serialize(report)
        def tree = objectMapper.readTree(json)

        then: "the document parses without error and is contract-valid (version present)"
        tree.get("version").asInt() == 1

        and: "top-level totals: null tokens, non-null wallMillis, empty byTool"
        def totals = tree.get("totals")
        totals.get("tokensIn").isNull()
        totals.get("tokensOut").isNull()
        !totals.get("wallMillis").isNull()
        totals.get("wallMillis").asLong() == 183_000L
        totals.get("byTool").isArray()
        totals.get("byTool").isEmpty()

        and: "per-attempt usage: same null-tokens / non-null-wallMillis / empty-byTool shape"
        def attemptJson = tree.get("currentStage").get("attempts").get(0)
        def attemptUsage = attemptJson.get("usage")
        attemptUsage.get("tokensIn").isNull()
        attemptUsage.get("tokensOut").isNull()
        !attemptUsage.get("wallMillis").isNull()
        attemptUsage.get("wallMillis").asLong() == 183_000L
        attemptUsage.get("byTool").isArray()
        attemptUsage.get("byTool").isEmpty()

        and: "judgeUsage.perVote is an empty JSON array: no judge ran on a human-only round"
        def judgeUsage = attemptJson.get("judgeUsage")
        judgeUsage.get("perVote").isArray()
        judgeUsage.get("perVote").isEmpty()
    }
}

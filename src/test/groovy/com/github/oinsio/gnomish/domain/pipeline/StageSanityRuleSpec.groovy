package com.github.oinsio.gnomish.domain.pipeline

import java.time.Duration
import spock.lang.Specification

/**
 * StageSanityRule: the pure stage-level local-sanity rule (design D5a/D6,
 * FR11 + the FR7 attempt-limit clause). It applies catalog-free checks that
 * need no live target (NG7): the executor model must be non-blank for EVERY
 * executor type — pinned in the manifest so any instance reproduces the stage
 * identically, never left to an executor default; the resolved attempt limit
 * must be at least 1; an `external` check must have a positive interval, a
 * positive timeout, interval <= timeout, and a non-blank checkId; a `judge`
 * check must pin a non-blank model (same reproducibility rationale as the
 * executor model) and declare votes that are >= 1 and odd. Builtin and Command
 * checks carry no FR11 sanity rule and are never flagged (no validation creep).
 * Executor and judge settings are an opaque pass-through map, accepted without
 * inspecting keys, values, or ranges (Q1/D5a); whether a model is real, or a
 * CI check exists, is target-liveness and deliberately not checked (NG7).
 * Problems are located ConfigErrors naming the stage manifest (NFR-O1, UX2).
 * Error order is deterministic (NFR-R1): stages in pipeline order; within a
 * stage the model error, then the attempt-limit error, then verify-check
 * errors in check-list order and (within a check) field order.
 * Implements FR11 (and the FR7 attempt-limit clause) of load-pipeline-config.
 */
class StageSanityRuleSpec extends Specification {

    private static StageDefinition stage(String name, ExecutorType type, String model,
            Map<String, Object> settings, int attemptLimit, List<VerifyCheck> verify) {
        new StageDefinition(
                name, "Purpose of $name",
                [new ArtifactInput.Source()], [
                    new ArtifactOutput("$name-out")
                ],
                new StageDefinition.Executor(type, model, settings),
                "stages/$name/instructions.md",
                verify,
                new AutonomyLimits(attemptLimit), AdvancementMode.AUTO)
    }

    private static StageDefinition stage(String name, ExecutorType type, String model,
            Map<String, Object> settings, int attemptLimit) {
        stage(name, type, model, settings, attemptLimit, [
            new VerifyCheck.Command('./gradlew check')
        ])
    }

    private static VerifyCheck ext(String checkId, Duration interval, Duration timeout) {
        new VerifyCheck.External(checkId, interval, timeout)
    }

    private static VerifyCheck judge(String model, int votes) {
        new VerifyCheck.Judge('criteria.md', model, [:], votes)
    }

    private static ConfigError missingModel(String stage) {
        new ConfigError("stages/$stage/stage.yaml", 'executor.model',
                'missing required executor model; the model must be pinned in the manifest for every executor type')
    }

    private static ConfigError nonPositiveAttempts(String stage, int limit) {
        new ConfigError("stages/$stage/stage.yaml", 'attempts',
                "non-positive resolved attempt limit $limit; the resolved limit must be at least 1" as String)
    }

    private static ConfigError blankCheckId(String stage, int index) {
        new ConfigError("stages/$stage/stage.yaml", "verify[$index].checkId" as String,
                "missing required external check identifier" as String)
    }

    private static ConfigError badInterval(String stage, int index, Duration interval) {
        new ConfigError("stages/$stage/stage.yaml", "verify[$index].interval" as String,
                "non-positive external poll interval $interval; the interval must be positive" as String)
    }

    private static ConfigError badTimeout(String stage, int index, Duration timeout) {
        new ConfigError("stages/$stage/stage.yaml", "verify[$index].timeout" as String,
                "non-positive external poll timeout $timeout; the timeout must be positive" as String)
    }

    private static ConfigError intervalOverTimeout(String stage, int index, Duration interval, Duration timeout) {
        new ConfigError("stages/$stage/stage.yaml", "verify[$index].interval" as String,
                "external poll interval $interval exceeds timeout $timeout; the interval must not exceed the timeout" as String)
    }

    private static ConfigError missingJudgeModel(String stage, int index) {
        new ConfigError("stages/$stage/stage.yaml", "verify[$index].model" as String,
                'missing required judge model; the model must be pinned in the manifest for reproducibility')
    }

    private static ConfigError badVotes(String stage, int index, int votes) {
        new ConfigError("stages/$stage/stage.yaml", "verify[$index].votes" as String,
                "invalid judge vote count $votes; votes must be at least 1 and odd" as String)
    }

    // FR11/FR7: a pinned non-blank model and a resolved limit >= 1 are sane
    // for both executor types; limit 1 is the FR7 boundary and is valid. The
    // model is any non-blank string — reality is never checked (NG7)
    def "a pinned model with attempt limit #limit produces no errors for #type"() {
        expect: 'validating the stage yields an empty error list'
        StageSanityRule.validate([
            stage('plan', type, model, [:], limit)
        ]) == []

        where:
        type                   | model                    | limit
        ExecutorType.API       | 'claude-sonnet-4-5'      | 1
        ExecutorType.AGENT_CLI | 'no-such-model-anywhere' | 3
    }

    // FR11 delta-spec scenario: model absent (blank, per the mapper contract)
    // or whitespace-only → located error, whatever the executor type
    def "a blank model (#label) is a located error for #type"() {
        expect: 'exactly one error locating executor.model in the stage manifest'
        StageSanityRule.validate([
            stage('plan', type, model, [:], 3)
        ]) == [missingModel('plan')]

        where:
        label              | model  | type
        'empty'            | ''     | ExecutorType.API
        'whitespace-only'  | '   '  | ExecutorType.AGENT_CLI
        'tab'              | '\t'   | ExecutorType.API
    }

    // FR11 delta-spec AND-clause (Q1/D5a): settings present as a mapping are
    // accepted as an opaque pass-through — no key, value, or range inspection
    def "arbitrary executor settings are accepted without inspection"() {
        expect: 'weird keys, negative numbers, and nesting produce no errors'
        StageSanityRule.validate([
            stage('plan', ExecutorType.API, 'claude-sonnet-4-5',
            ['': 'blank key', temperature: -99.9d, nested: [flags: [true, false]], budget: -1],
            3)
        ]) == []
    }

    // FR7 delta-spec scenario: a resolved limit below 1 (default or override
    // — resolution happened before this rule) → located error naming the value
    def "a non-positive resolved attempt limit #limit is a located error"() {
        expect: 'exactly one error locating attempts in the stage manifest'
        StageSanityRule.validate([
            stage('implement', ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', [:], limit as int)
        ]) == [
            nonPositiveAttempts('implement', limit as int)
        ]

        where:
        limit << [0, -1]
    }

    // FR11 external clause: positive interval, positive timeout, interval <=
    // timeout (equal is valid — the boundary), non-blank checkId → no errors.
    // Builtin and Command checks alongside carry no FR11 rule (no creep)
    def "sane external and non-external checks produce no errors (interval #interval, timeout #timeout)"() {
        expect: 'validating the stage yields an empty error list'
        StageSanityRule.validate([
            stage('review', ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', [:], 3, [
                new VerifyCheck.Builtin('files_exist', [paths: ['x']]),
                new VerifyCheck.Command('./gradlew check'),
                ext('ci/build', interval, timeout),
            ])
        ]) == []

        where:
        interval                  | timeout
        Duration.ofSeconds(30)    | Duration.ofMinutes(10)
        Duration.ofMinutes(10)    | Duration.ofMinutes(10)
    }

    // FR11 external clause: a non-positive interval/timeout or interval >
    // timeout, or a blank checkId, is a located error naming the offending
    // check by its verify-list index and field
    def "an unsane external check (#label) is a located error"() {
        expect: 'exactly the expected located error(s) for the external check'
        StageSanityRule.validate([
            stage('review', ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', [:], 3, [
                ext(checkId, interval, timeout)
            ])
        ]) == expected

        where:
        label                    | checkId    | interval                 | timeout                  || expected
        'zero interval'          | 'ci/build' | Duration.ZERO            | Duration.ofMinutes(1)    || [
            badInterval('review', 0, Duration.ZERO)
        ]
        'negative interval'      | 'ci/build' | Duration.ofSeconds(-1)   | Duration.ofMinutes(1)    || [
            badInterval('review', 0, Duration.ofSeconds(-1))
        ]
        'zero timeout'           | 'ci/build' | Duration.ofSeconds(30)   | Duration.ZERO            || [
            badTimeout('review', 0, Duration.ZERO)
        ]
        'negative timeout'       | 'ci/build' | Duration.ofSeconds(30)   | Duration.ofSeconds(-1)   || [
            badTimeout('review', 0, Duration.ofSeconds(-1))
        ]
        'interval over timeout'  | 'ci/build' | Duration.ofMinutes(2)    | Duration.ofMinutes(1)    || [
            intervalOverTimeout('review', 0, Duration.ofMinutes(2), Duration.ofMinutes(1))
        ]
        'blank checkId'          | '   '      | Duration.ofSeconds(30)   | Duration.ofMinutes(1)    || [blankCheckId('review', 0)]
        'empty checkId'          | ''         | Duration.ofSeconds(30)   | Duration.ofMinutes(1)    || [blankCheckId('review', 0)]
    }

    // FR11 external clause: field-order determinism within one check — a blank
    // checkId, then interval faults, then timeout faults, in that field order
    def "multiple faults in one external check are ordered checkId, interval, timeout"() {
        expect: 'all three located errors in field order'
        StageSanityRule.validate([
            stage('review', ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', [:], 3, [
                ext('', Duration.ZERO, Duration.ofSeconds(-1))
            ])
        ]) == [
            blankCheckId('review', 0),
            badInterval('review', 0, Duration.ZERO),
            badTimeout('review', 0, Duration.ofSeconds(-1)),
        ]
    }

    // FR11 judge clause: votes >= 1 and odd (1 and 3 valid); a non-blank pinned
    // model is required (reproducibility, same rationale as the executor model)
    def "a sane judge check with votes #votes produces no errors"() {
        expect: 'validating the stage yields an empty error list'
        StageSanityRule.validate([
            stage('review', ExecutorType.API, 'claude-sonnet-4-5', [:], 3, [
                judge('claude-opus-4-1', votes as int)
            ])
        ]) == []

        where:
        votes << [1, 3, 5]
    }

    // FR11 judge clause: votes < 1 or even is a located error; boundaries
    // 0 (invalid), 2 (invalid even), -1 (invalid) are all covered
    def "a judge vote count of #votes is a located error"() {
        expect: 'exactly one error locating the judge votes'
        StageSanityRule.validate([
            stage('review', ExecutorType.API, 'claude-sonnet-4-5', [:], 3, [
                judge('claude-opus-4-1', votes as int)
            ])
        ]) == [
            badVotes('review', 0, votes as int)
        ]

        where:
        votes << [0, 2, -1, -2]
    }

    // FR11 judge clause: a blank judge model is a located error, mirroring the
    // executor-model rule — the judge model is pinned for reproducibility
    def "a blank judge model (#label) is a located error"() {
        expect: 'exactly one error locating the judge model'
        StageSanityRule.validate([
            stage('review', ExecutorType.API, 'claude-sonnet-4-5', [:], 3, [
                judge(model, 3)
            ])
        ]) == [
            missingJudgeModel('review', 0)
        ]

        where:
        label             | model
        'empty'           | ''
        'whitespace-only' | '   '
    }

    // FR11 judge clause: model fault before votes fault within one judge check
    def "multiple faults in one judge check are ordered model, votes"() {
        expect: 'both located errors in field order'
        StageSanityRule.validate([
            stage('review', ExecutorType.API, 'claude-sonnet-4-5', [:], 3, [
                judge('', 2)
            ])
        ]) == [
            missingJudgeModel('review', 0),
            badVotes('review', 0, 2),
        ]
    }

    // FR11: check errors are located by the check's index in the verify list,
    // so faults in later checks name the right position; sane checks (Command,
    // Builtin, valid External/Judge) contribute nothing
    def "verify-check errors are located by verify-list index"() {
        given: 'a stage whose 2nd and 4th checks are faulty'
        def stages = [
            stage('review', ExecutorType.API, 'claude-sonnet-4-5', [:], 3, [
                new VerifyCheck.Command('./gradlew check'),
                ext('', Duration.ofSeconds(30), Duration.ofMinutes(1)),
                new VerifyCheck.Builtin('files_exist', [:]),
                judge('claude-opus-4-1', 2),
            ])
        ]

        expect: 'errors name index 1 (external) then index 3 (judge)'
        StageSanityRule.validate(stages) == [
            blankCheckId('review', 1),
            badVotes('review', 3, 2),
        ]
    }

    // NFR-R1 determinism: stages in pipeline order; within a stage the model
    // error, then attempts, then verify checks in list order; a sane stage
    // contributes nothing
    def "errors are ordered by stage, then model, attempts, verify checks"() {
        given: 'a broken first stage, a sane middle stage, a broken last stage'
        def stages = [
            stage('plan', ExecutorType.API, ' ', [:], 0, [
                ext('', Duration.ZERO, Duration.ofSeconds(1))
            ]),
            stage('implement', ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', [:], 3),
            stage('review', ExecutorType.AGENT_CLI, '', [:], -2, [
                judge('', 2)
            ]),
        ]

        expect: 'model, attempts, then per-check field errors, per broken stage in stage order'
        StageSanityRule.validate(stages) == [
            missingModel('plan'),
            nonPositiveAttempts('plan', 0),
            blankCheckId('plan', 0),
            badInterval('plan', 0, Duration.ZERO),
            missingModel('review'),
            nonPositiveAttempts('review', -2),
            missingJudgeModel('review', 0),
            badVotes('review', 0, 2),
        ]
    }
}

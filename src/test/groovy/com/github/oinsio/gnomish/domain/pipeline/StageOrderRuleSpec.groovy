package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * StageOrderRule: the pure stage-order check (design D4/D6) over the stages
 * carried by PipelineDefinition in exactly the pipeline.yaml declaration
 * order. The order must be a non-empty linear sequence of unique stage names;
 * an empty pipeline or a duplicated name yields located ConfigErrors naming
 * pipeline.yaml — the file where order and names are declared (NFR-O1, UX2).
 * Duplicate reporting contract: each duplicated name is reported exactly once
 * (not per occurrence), naming the stage and its total occurrence count, in
 * first-occurrence order — deterministic per NFR-R1.
 * Implements FR3 and the unique-stage-names clause of FR6 of
 * load-pipeline-config.
 */
class StageOrderRuleSpec extends Specification {

    private static StageDefinition stage(String name) {
        new StageDefinition(
                name, "Purpose of $name",
                [new ArtifactInput.Source()], [
                    new ArtifactOutput("$name-out")
                ],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', [:]),
                "stages/$name/instructions.md",
                [
                    new VerifyCheck.Command('./gradlew check')
                ],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static ConfigError duplicate(String name, int times) {
        new ConfigError('pipeline.yaml', "stages[$name]",
                "duplicate stage name '$name' declared $times times; stage names must be unique" as String)
    }

    // FR3: a non-empty order of unique names is exactly what pipeline.yaml
    // must declare — no errors, whether one stage or many
    def "a non-empty order of unique stage names #names produces no errors"() {
        expect: 'validating the order yields an empty error list'
        StageOrderRule.validate(names.collect { stage(it) }) == []

        where:
        names << [
            ['plan', 'implement', 'review'],
            ['implement'],
        ]
    }

    // FR3 delta-spec scenario: empty stage list → located error naming
    // pipeline.yaml, where the order is declared
    def "an empty stage order is a located error naming pipeline.yaml"() {
        expect: 'exactly one error locating pipeline.yaml: stages'
        StageOrderRule.validate([]) == [
            new ConfigError('pipeline.yaml', 'stages', 'pipeline declares no stages')
        ]
    }

    // FR3/FR6 delta-spec scenario: duplicated stage names → one located error
    // per duplicated name, naming it and its occurrence count, in
    // first-occurrence order
    def "duplicated stage names in #names are reported once per name"() {
        expect: 'exactly one error per duplicated name, with its occurrence count'
        StageOrderRule.validate(names.collect { stage(it) }) == expected

        where:
        names                                          || expected
        ['plan', 'implement', 'plan']                  || [duplicate('plan', 2)]
        [
            'plan',
            'plan',
            'implement',
            'implement'
        ]     || [
            duplicate('plan', 2),
            duplicate('implement', 2)
        ]
        [
            'review',
            'plan',
            'review',
            'plan'
        ]           || [
            duplicate('review', 2),
            duplicate('plan', 2)
        ]
        [
            'implement',
            'implement',
            'implement'
        ]        || [duplicate('implement', 3)]
        [
            'plan',
            'plan',
            'plan',
            'review',
            'review'
        ]   || [
            duplicate('plan', 3),
            duplicate('review', 2)
        ]
    }
}

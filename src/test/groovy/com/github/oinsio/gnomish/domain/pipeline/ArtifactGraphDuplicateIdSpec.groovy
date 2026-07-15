package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * ArtifactGraphRule, duplicate-output-id contract: output ids must be unique
 * across the whole pipeline — internal inputs reference a bare id, so a
 * duplicate would make the reference ambiguous. Each duplicated id is
 * reported exactly once — not per occurrence — as a located ConfigError at
 * the first declaring stage's manifest (NFR-O1, UX2), naming the duplicated
 * id and every declaring stage in declaration order (both stages for a
 * cross-stage duplicate, the same stage twice for a within-stage one) plus
 * the occurrence count. Reference resolution, including its determinism under
 * duplicate ids, is covered by ArtifactGraphReferenceSpec.
 * Implements the duplicate-id clause of FR6 of load-pipeline-config.
 */
class ArtifactGraphDuplicateIdSpec extends Specification {

    private static StageDefinition stage(String name, List<String> outputIds) {
        new StageDefinition(
                name, "Purpose of $name",
                [new ArtifactInput.Source()], outputIds.collect { new ArtifactOutput(it) },
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', [:]),
                "stages/$name/instructions.md",
                [
                    new VerifyCheck.Command('./gradlew check')
                ],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static ConfigError duplicate(String id, List<String> declaringStages) {
        String stageNames = declaringStages.collect { "'$it'" }.join(', ')
        new ConfigError("stages/${declaringStages.first()}/stage.yaml", "outputs[$id]",
                "duplicate output id '$id' declared ${declaringStages.size()} times by stages " +
                "$stageNames; output ids must be unique across the pipeline" as String)
    }

    // FR6: pipeline-wide unique output ids are exactly what the rule demands —
    // no errors, including multiple distinct ids within one stage
    def "pipeline-wide unique output ids produce no errors"() {
        expect: 'validating the stages yields an empty error list'
        ArtifactGraphRule.validate([
            stage('plan', ['plan-doc', 'plan-notes']),
            stage('implement', ['code']),
        ]) == []
    }

    // FR6 delta-spec scenario: duplicate output id → one located error naming
    // the duplicated id and every declaring stage (both stages across the
    // pipeline, or the same stage twice), with the occurrence count
    def "duplicate output ids (#label) are reported once naming all declaring stages"() {
        expect: 'exactly one error per duplicated id, naming every declaring stage'
        ArtifactGraphRule.validate(stages) == expected

        where:
        label                 | stages                                || expected
        'across two stages'   | [
            stage('plan', ['api']),
            stage('implement', ['api']),
        ]                                                             || [
            duplicate('api', ['plan', 'implement'])
        ]
        'within one stage'    | [stage('plan', ['api', 'api'])]       || [
            duplicate('api', ['plan', 'plan'])
        ]
        'across three stages' | [
            stage('plan', ['x']),
            stage('design', ['x']),
            stage('implement', ['x']),
        ]                                                             || [
            duplicate('x', [
                'plan',
                'design',
                'implement'
            ])
        ]
    }
}

package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * ArtifactGraphRule, reference-resolution contract (design D4): the artifact
 * DAG is validated against the pipeline.yaml order carried by
 * PipelineDefinition, never used to derive it. An Internal input must resolve
 * to an output id of a strictly-earlier stage; Source inputs need no
 * producer. Unresolved references are located ConfigErrors naming the
 * consuming stage's manifest (NFR-O1, UX2), classified by the earliest
 * declaration of the id: none anywhere → dangling; the consumer itself →
 * self; a later stage → forward, naming the earliest late producer. Under
 * duplicate ids resolution stays deterministic: a reference resolves when ANY
 * strictly-earlier stage declares the id, while the duplicate error (see
 * ArtifactGraphDuplicateIdSpec) fires separately. Error order is
 * deterministic (NFR-R1): duplicate errors first in first-declaration order,
 * then reference errors in pipeline order, then input declaration order.
 * Implements FR4, the earlier-stage clause of FR6 and the DAG-consistency
 * clause of FR3 of load-pipeline-config.
 */
class ArtifactGraphReferenceSpec extends Specification {

    private static StageDefinition stage(String name, List<ArtifactInput> inputs, List<String> outputIds) {
        new StageDefinition(
                name, "Purpose of $name",
                inputs, outputIds.collect { new ArtifactOutput(it) },
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', [:]),
                "stages/$name/instructions.md",
                [
                    new VerifyCheck.Command('./gradlew check')
                ],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static ArtifactInput internal(String id) {
        new ArtifactInput.Internal(id)
    }

    private static ArtifactInput source() {
        new ArtifactInput.Source()
    }

    private static ConfigError duplicate(String id, List<String> declaringStages) {
        String stageNames = declaringStages.collect { "'$it'" }.join(', ')
        new ConfigError("stages/${declaringStages.first()}/stage.yaml", "outputs[$id]",
                "duplicate output id '$id' declared ${declaringStages.size()} times by stages " +
                "$stageNames; output ids must be unique across the pipeline" as String)
    }

    private static ConfigError dangling(String consumer, String id) {
        new ConfigError("stages/$consumer/stage.yaml", "inputs[$id]",
                "internal input references output id '$id', which no stage produces" as String)
    }

    private static ConfigError selfReference(String consumer, String id) {
        new ConfigError("stages/$consumer/stage.yaml", "inputs[$id]",
                "internal input references output id '$id', which is produced by stage '$consumer' itself; " +
                'the producer must be an earlier stage' as String)
    }

    private static ConfigError forward(String consumer, String id, String lateProducer) {
        new ConfigError("stages/$consumer/stage.yaml", "inputs[$id]",
                "internal input references output id '$id', which is first produced by later stage " +
                "'$lateProducer'; the producer must be an earlier stage" as String)
    }

    // FR4 delta-spec scenarios: internal inputs resolving to earlier outputs
    // and source inputs needing no producer are exactly what a consistent
    // artifact graph looks like — no errors
    def "a consistent artifact graph (#label) produces no errors"() {
        expect: 'validating the stages yields an empty error list'
        ArtifactGraphRule.validate(stages) == []

        where:
        label                                     | stages
        'multi-stage chain of internal + source'  | [
            stage('plan', [source()], ['plan-doc']),
            stage('implement', [
                internal('plan-doc'),
                source()
            ], ['code']),
            stage('review', [
                internal('code'),
                internal('plan-doc')
            ], ['review-report']),
        ]
        'a single stage with only source inputs'  | [
            stage('gather', [source(), source()], ['notes'])
        ]
    }

    // FR4/FR6 delta-spec scenario: dangling or forward reference → located
    // error identifying the input and the missing/late producer; a same-stage
    // reference is not strictly earlier either
    def "a #label reference is a located error identifying the input and producer"() {
        expect: 'exactly the classified reference error'
        ArtifactGraphRule.validate(stages) == expected

        where:
        label      | stages                                                 || expected
        'dangling' | [
            stage('implement', [internal('ghost')], ['code'])
        ] || [
            dangling('implement', 'ghost')
        ]
        'self'     | [
            stage('plan', [internal('plan-doc')], ['plan-doc'])
        ]                                                                   || [
            selfReference('plan', 'plan-doc')
        ]
        'forward'  | [
            stage('implement', [internal('plan-doc')], ['code']),
            stage('plan', [source()], ['plan-doc']),
        ]                                                                   || [
            forward('implement', 'plan-doc', 'plan')
        ]
    }

    // FR4/FR6 interplay contract: with duplicate ids the reference check stays
    // deterministic — resolves when ANY strictly-earlier stage declares the id
    // (only the duplicate error fires); a non-earlier one is classified by the
    // earliest declaration (self, or forward naming the earliest late producer)
    def "with duplicate ids (#label) reference resolution stays deterministic"() {
        expect: 'the duplicate error, plus only genuinely unresolved references'
        ArtifactGraphRule.validate(stages) == expected

        where:
        label                               | stages                        || expected
        'any earlier duplicate resolves'    | [
            stage('plan', [source()], ['api']),
            stage('design', [source()], ['api']),
            stage('implement', [internal('api')], ['code']),
        ]                                                                   || [
            duplicate('api', ['plan', 'design'])
        ]
        'forward names earliest late stage' | [
            stage('implement', [internal('api')], ['code']),
            stage('plan', [source()], ['api']),
            stage('design', [source()], ['api']),
        ]                                                                   || [
            duplicate('api', ['plan', 'design']),
            forward('implement', 'api', 'plan')
        ]
        'self wins over a later duplicate'  | [
            stage('plan', [internal('api')], ['api']),
            stage('design', [source()], ['api']),
        ]                                                                   || [
            duplicate('api', ['plan', 'design']),
            selfReference('plan', 'api')
        ]
    }

    // NFR-R1 determinism: duplicate errors first (first-declaration order),
    // then reference errors in pipeline order, then input declaration order —
    // and a source input between bad ones contributes nothing
    def "errors are ordered by rule, pipeline order, then input order"() {
        given: 'a pipeline with a duplicated id and dangling refs across stages'
        def stages = [
            stage('plan', [internal('missing-a')], ['dup']),
            stage('implement', [
                internal('z-ghost'),
                source(),
                internal('a-ghost'),
                internal('dup')
            ], ['dup', 'code']),
        ]

        expect: 'duplicates first, then references in declaration order; the dup reference resolves'
        ArtifactGraphRule.validate(stages) == [
            duplicate('dup', ['plan', 'implement']),
            dangling('plan', 'missing-a'),
            dangling('implement', 'z-ghost'),
            dangling('implement', 'a-ghost'),
        ]
    }
}

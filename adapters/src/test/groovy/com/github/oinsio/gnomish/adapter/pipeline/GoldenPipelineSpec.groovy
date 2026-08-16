package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.ArtifactOutput
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import spock.lang.Shared
import spock.lang.Specification

/**
 * The golden-fixture acceptance spec (task 7.1, success metric M1): a single,
 * genuinely valid committed {@code .gnomish/} resource tree is loaded through the
 * real {@link PipelineLoader} and the resulting {@link PipelineDefinition} is
 * asserted field-by-field. Where the loader spec makes a light "loads in order"
 * check, this spec pins every field of every stage — resolved autonomy limits,
 * executor type/model/opaque settings, instructions reference, both input kinds
 * with their producer links, output ids, and the full ordered verify list with
 * each of the four check variants' type-specific fields.
 *
 * <p>The fixture deliberately exercises the whole surface (design D8): three
 * stages in a fixed {@code pipeline.yaml} order; the default attempt limit from
 * {@code config.yaml} and a per-stage override, both asserted to resolve
 * correctly (FR7); both {@code source} and {@code internal} input kinds, the
 * internal ones linking to an earlier stage's output id (FR4); the
 * {@code agent-cli} executor (the only executor type accepted at startup since
 * add-agent-executor's FR10/D6 — {@code api} stages are rejected fail-fast);
 * all four verify-check variants — {@code builtin},
 * {@code command}, {@code external}, {@code judge} — in one stage with their order
 * preserved (FR2); and both {@code auto} and {@code manual} advancement.
 *
 * <p>The fixture is a real resource directory the loader reads via its filesystem
 * path (so {@code ReferencedFiles} sees genuine {@code instructions.md} and judge
 * acceptance-criteria files), proving the load end-to-end rather than in a
 * hand-built temp tree.
 *
 * <p>M1 / FR1, FR2, FR4 of load-pipeline-config.
 */
class GoldenPipelineSpec extends Specification {

    @Shared
    PipelineDefinition model

    def setupSpec() {
        def outcome = PipelineLoader.load(fixtureRoot())
        assert outcome instanceof LoadOutcome.Loaded
        model = (outcome as LoadOutcome.Loaded).definition()
    }

    private static Path fixtureRoot() {
        Paths.get(GoldenPipelineSpec.getResource('/.gnomish-fixtures/valid').toURI())
    }

    def "M1/FR1: the golden fixture loads into a Loaded outcome with the tree-wide fields"() {
        expect: 'the tree-wide schema version and pipeline-wide default limits'
        model.schemaVersion() == '1'
        model.defaultLimits().attemptLimit() == 3

        and: 'exactly the three stages in pipeline.yaml order (FR3)'
        model.stages()*.name() == ['plan', 'implement', 'review']
    }

    def "M1/FR2: the plan stage maps every field, including its overriding attempt limit (FR7)"() {
        given:
        def plan = model.stages()[0]

        expect: 'scalar sections'
        plan.name() == 'plan'
        plan.purpose() == 'plan the work from the task description'
        plan.instructionsRef() == 'stages/plan/instructions.md'
        plan.advancement() == AdvancementMode.AUTO

        and: 'a single source input (no producer) and one output id'
        plan.inputs() == [new ArtifactInput.Source()]
        plan.outputs() == [
            new ArtifactOutput('plan-doc')
        ]

        and: 'the agent-cli executor with its pinned model and opaque plain-JDK settings'
        plan.executor().type() == ExecutorType.AGENT_CLI
        plan.executor().model() == 'plan-model'
        plan.executor().settings() == [maxTurns: 10]
        plan.executor().settings().maxTurns instanceof Integer

        and: 'a single builtin verify check with its plain-JDK params'
        plan.verify().size() == 1
        def builtin = plan.verify()[0] as VerifyCheck.Builtin
        builtin.name() == 'files_exist'
        builtin.params() == [paths: ['plan.md']]

        and: 'the stage override wins over the config default (FR7)'
        plan.limits().attemptLimit() == 5
    }

    def "M1/FR2/FR4: the implement stage carries both input kinds and all four verify variants in order"() {
        given:
        def implement = model.stages()[1]

        expect: 'scalar sections, with manual advancement and the default (non-overridden) limit (FR7)'
        implement.name() == 'implement'
        implement.purpose() == 'implement the plan and produce a diff'
        implement.instructionsRef() == 'stages/implement/instructions.md'
        implement.advancement() == AdvancementMode.MANUAL
        implement.limits().attemptLimit() == 3

        and: 'an internal input linking to plan-doc followed by a source input, order preserved (FR4)'
        implement.inputs() == [
            new ArtifactInput.Internal('plan-doc'),
            new ArtifactInput.Source()
        ]

        and: 'one output id'
        implement.outputs() == [
            new ArtifactOutput('impl-diff')
        ]

        and: 'the agent-cli executor with opaque settings mapped to plain JDK types (D5a), all four recognized keys (FR11/D7)'
        implement.executor().type() == ExecutorType.AGENT_CLI
        implement.executor().model() == 'implement-model'
        def settings = implement.executor().settings()
        settings.maxTurns == 40 && settings.maxTurns instanceof Integer
        settings.allowedTools == ['read', 'edit'] && settings.allowedTools instanceof List
        settings.disallowedTools == ['bash'] && settings.disallowedTools instanceof List
        settings.roundTimeout == 900 && settings.roundTimeout instanceof Integer
    }

    def "M1/FR2: the implement stage's verify list holds all four variants in declaration order"() {
        given:
        def verify = model.stages()[1].verify()

        expect: 'four checks, each mapped to its own sealed variant with order preserved'
        verify.size() == 4
        verify[0] instanceof VerifyCheck.Builtin
        verify[1] instanceof VerifyCheck.Command
        verify[2] instanceof VerifyCheck.External
        verify[3] instanceof VerifyCheck.Judge

        and: 'builtin: name + plain-JDK params'
        def builtin = verify[0] as VerifyCheck.Builtin
        builtin.name() == 'files_exist'
        builtin.params() == [paths: ['src']]

        and: 'command: the executable command line'
        (verify[1] as VerifyCheck.Command).command() == './gradlew test'

        and: 'external: non-blank id and durations parsed from the short forms, interval <= timeout'
        def external = verify[2] as VerifyCheck.External
        external.checkId() == 'ci/build'
        external.interval() == Duration.ofSeconds(30)
        external.timeout() == Duration.ofMinutes(5)

        and: 'judge: criteria file, pinned model, opaque settings, odd vote count >= 1'
        def judge = verify[3] as VerifyCheck.Judge
        judge.criteriaFile() == 'stages/implement/acceptance.md'
        judge.model() == 'judge-model'
        judge.settings() == [maxTurns: 3]
        judge.votes() == 3
    }

    def "M1/FR4: the review stage links to the earlier impl-diff output and uses the default limit"() {
        given:
        def review = model.stages()[2]

        expect: 'scalar sections'
        review.name() == 'review'
        review.purpose() == 'review the implemented diff'
        review.instructionsRef() == 'stages/review/instructions.md'
        review.advancement() == AdvancementMode.AUTO
        review.limits().attemptLimit() == 3

        and: 'a lone internal input resolving to the implement stage output (FR4)'
        review.inputs() == [
            new ArtifactInput.Internal('impl-diff')
        ]
        review.outputs() == [
            new ArtifactOutput('review-report')
        ]

        and: 'the second agent-cli executor (every stage in the fixture uses agent-cli)'
        review.executor().type() == ExecutorType.AGENT_CLI
        review.executor().model() == 'review-model'
        review.executor().settings() == [:]

        and: 'a single command verify check'
        review.verify().size() == 1
        (review.verify()[0] as VerifyCheck.Command).command() == 'echo reviewed'
    }
}

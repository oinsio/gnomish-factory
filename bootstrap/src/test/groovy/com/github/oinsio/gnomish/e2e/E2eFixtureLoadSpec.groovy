package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import spock.lang.Shared
import spock.lang.Specification

/**
 * Sanity check for the {@code .gnomish-fixtures/e2e} tree task 9.1 builds for the
 * reference E2E harness (M1): before spawning any real process against it, prove
 * the fixture is a genuinely loadable pipeline with one stage whose {@code verify}
 * list covers three of {@link VerifyCheck}'s four variants (all but {@code external}).
 *
 * <p>This is a fixture sanity spec, not part of the E2E scenario itself (that is
 * tasks 9.2/9.3); it uses the same real {@link PipelineLoader} the pipeline-config
 * specs use, pointed at {@code e2e/.gnomish} rather than {@code valid/}.
 *
 * <p>M1 of add-manual-run.
 */
class E2eFixtureLoadSpec extends Specification {

    @Shared
    PipelineDefinition model

    def setupSpec() {
        def outcome = PipelineLoader.load(E2eFixture.gnomishDir(), [:],
        TrackerValidatorStub.acceptingGithubActions())
        assert outcome instanceof LoadOutcome.Loaded
        model = (outcome as LoadOutcome.Loaded).definition()
    }

    def "M1: the e2e fixture loads a single 'work' stage"() {
        expect:
        model.stages()*.name() == ['work']
        model.stages()[0].advancement() == AdvancementMode.MANUAL
    }

    // D5 of remove-interactive-console: the fixture carries no `external` check — no built-in
    // connection is configured for a packaged-jar run, and an unconnected one is refused.
    def "M1: the work stage's verify list covers three check kinds in order"() {
        given:
        def verify = model.stages()[0].verify()

        expect:
        verify.size() == 3
        verify[0] instanceof VerifyCheck.Executor
        (verify[0] as VerifyCheck.Executor).checkId() == 'files_exist'
        verify[1] instanceof VerifyCheck.Executor
        verify[2] instanceof VerifyCheck.Judge
        (verify[2] as VerifyCheck.Judge).votes() == 1
    }

    // D18 of migrate-verify-to-executor-contract ("Where a refusal at run assembly goes"): the
    //     unconnected-built-in fixture is a valid law — its one `external` check names the
    //     github-actions built-in — and loads; the missing factory.check.github-actions connection
    //     is refused at run assembly, never by a second, load-time validator.
    def "the unconnected-built-in fixture loads, its check naming github-actions"() {
        when:
        def outcome = PipelineLoader.load(
                E2eFixture.unconfiguredProviderRoot().resolve('.gnomish'), [:],
                TrackerValidatorStub.acceptingGithubActions())

        then:
        outcome instanceof LoadOutcome.Loaded
        def verify = (outcome as LoadOutcome.Loaded).definition().stages()[0].verify()
        verify.size() == 1
        verify[0] instanceof VerifyCheck.Executor
        (verify[0] as VerifyCheck.Executor).executorName() == 'github-actions'
    }

    def "M1: the fixture project root carries marker.txt for the files_exist check"() {
        expect:
        Files.exists(E2eFixture.projectRoot().resolve('marker.txt'))
    }
}

package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Shared
import spock.lang.Specification

/**
 * Sanity check for the {@code .gnomish-fixtures/e2e} tree task 9.1 builds for the
 * reference E2E harness (M1): before spawning any real process against it, prove
 * the fixture is a genuinely loadable pipeline with one stage whose {@code verify}
 * list covers all four check types, matching {@link VerifyCheck}'s four variants.
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
        def outcome = PipelineLoader.load(E2eFixture.gnomishDir())
        assert outcome instanceof LoadOutcome.Loaded
        model = (outcome as LoadOutcome.Loaded).definition()
    }

    def "M1: the e2e fixture loads a single 'work' stage"() {
        expect:
        model.stages()*.name() == ['work']
        model.stages()[0].advancement() == AdvancementMode.MANUAL
    }

    def "M1: the work stage's verify list covers all four check types in order"() {
        given:
        def verify = model.stages()[0].verify()

        expect:
        verify.size() == 4
        verify[0] instanceof VerifyCheck.Builtin
        (verify[0] as VerifyCheck.Builtin).name() == 'files_exist'
        verify[1] instanceof VerifyCheck.Command
        verify[2] instanceof VerifyCheck.External
        verify[3] instanceof VerifyCheck.Judge
        (verify[3] as VerifyCheck.Judge).votes() == 1
    }

    def "M1: the fixture project root carries marker.txt for the files_exist check"() {
        expect:
        java.nio.file.Files.exists(E2eFixture.projectRoot().resolve('marker.txt'))
    }
}

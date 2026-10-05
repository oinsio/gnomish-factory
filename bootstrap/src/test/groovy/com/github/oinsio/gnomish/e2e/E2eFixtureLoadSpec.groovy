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
        TrackerValidatorStub.discoveredGithubCheckProvider(), [] as Set)
        assert outcome instanceof LoadOutcome.Loaded
        model = (outcome as LoadOutcome.Loaded).definition()
    }

    def "M1: the e2e fixture loads a single 'work' stage"() {
        expect:
        model.stages()*.name() == ['work']
        model.stages()[0].advancement() == AdvancementMode.MANUAL
    }

    // D5 of remove-interactive-console: the fixture carries no `external` check — no provider can
    // be configured for a packaged-jar run, and an unconfigured one is a startup refusal.
    def "M1: the work stage's verify list covers three check kinds in order"() {
        given:
        def verify = model.stages()[0].verify()

        expect:
        verify.size() == 3
        verify[0] instanceof VerifyCheck.Builtin
        (verify[0] as VerifyCheck.Builtin).name() == 'files_exist'
        verify[1] instanceof VerifyCheck.Command
        verify[2] instanceof VerifyCheck.Judge
        (verify[2] as VerifyCheck.Judge).votes() == 1
    }

    // FR3, D5 of remove-interactive-console: the unconfigured-provider fixture is structurally a
    // valid pipeline — its one `external` check names a discovered provider — so the only thing
    // that refuses it is the factory-instance fact that no `factory.check.github` is configured.
    def "FR3: the unconfigured-provider fixture loads once github is configured"() {
        when:
        def outcome = loadUnconfiguredProviderFixture(TrackerValidatorStub.configuredGithubCheckProvider())

        then:
        outcome instanceof LoadOutcome.Loaded
        def verify = (outcome as LoadOutcome.Loaded).definition().stages()[0].verify()
        verify.size() == 1
        verify[0] instanceof VerifyCheck.External
        (verify[0] as VerifyCheck.External).provider() == 'github'
    }

    def "FR3: the unconfigured-provider fixture is refused, located at its check's provider"() {
        when:
        def outcome = loadUnconfiguredProviderFixture([] as Set)

        then:
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()
        errors.size() == 1
        errors[0].file() == 'stages/work/stage.yaml'
        errors[0].where() == 'verify[0].provider'
        errors[0].message().contains("check provider 'github' has no factory.check.github section")
    }

    private static LoadOutcome loadUnconfiguredProviderFixture(Set<String> configured) {
        PipelineLoader.load(
                E2eFixture.unconfiguredProviderRoot().resolve('.gnomish'), [:],
                TrackerValidatorStub.discoveredGithubCheckProvider(), configured)
    }

    def "M1: the fixture project root carries marker.txt for the files_exist check"() {
        expect:
        Files.exists(E2eFixture.projectRoot().resolve('marker.txt'))
    }
}

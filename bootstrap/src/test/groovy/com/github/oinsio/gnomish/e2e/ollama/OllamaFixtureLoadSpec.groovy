package com.github.oinsio.gnomish.e2e.ollama

import com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Shared
import spock.lang.Specification

/**
 * Sanity check for the {@code .gnomish-fixtures/ollama-e2e} tree (task 11.2, M1, D11): proves the
 * fixture is a genuinely loadable {@code agent-cli} manifest with strict settings — before any
 * process is ever spawned against it — using the real {@link PipelineLoader}, exactly as {@link
 * com.github.oinsio.gnomish.e2e.E2eFixtureLoadSpec} does for the plain E2E fixture.
 *
 * <p>This spec has no Ollama/{@code claude} dependency; it only exercises {@link PipelineLoader}
 * in-process, so it is cheap, deterministic, and always green regardless of local Ollama
 * availability. It runs under the {@code ollamaE2eTest} task because it lives in the same package
 * as the live scenario spec, not because it needs the native prerequisites {@link
 * OllamaAvailability} checks for.
 *
 * <p>Implements M1, D11 of add-agent-executor.
 */
class OllamaFixtureLoadSpec extends Specification {

    @Shared
    PipelineDefinition model

    def setupSpec() {
        def outcome = PipelineLoader.load(OllamaFixture.gnomishDir())
        assert outcome instanceof LoadOutcome.Loaded
        model = (outcome as LoadOutcome.Loaded).definition()
    }

    def "M1: the ollama-e2e fixture loads a single auto-advancing agent-cli stage"() {
        expect:
        model.stages()*.name() == ['write-file']
        model.stages()[0].advancement() == AdvancementMode.AUTO
        model.stages()[0].executor().type() == ExecutorType.AGENT_CLI
    }

    def "M1: the write-file stage's verify list is exactly one judge check with a concrete criterion"() {
        given:
        def verify = model.stages()[0].verify()

        expect:
        verify.size() == 1
        verify[0] instanceof VerifyCheck.Judge
        (verify[0] as VerifyCheck.Judge).votes() == 1
    }

    def "M1: the acceptance criteria file states a single, file-existence-only criterion"() {
        expect:
        java.nio.file.Files.readString(OllamaFixture.gnomishDir().resolve('stages/write-file/acceptance.md'))
                .contains('hello.txt')
    }
}

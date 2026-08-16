package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR12b of split-into-modules: {@code GnomishDirPipelineSource} is the {@code PipelineSource}
 * realization every command runs on — it resolves the project's {@code .gnomish/} subdirectory and
 * hands it to {@code PipelineLoader} together with the composition root's tracker-subsection
 * validator registry.
 *
 * <p>Added by task 8.1 of split-into-modules: per-module mutation scoping needs a module's classes
 * covered by that module's own specs, and this adapter was previously driven only by the
 * composition root's command suites in {@code :bootstrap}.
 */
class GnomishDirPipelineSourceSpec extends Specification {

    @TempDir
    Path tempDir

    def source = new GnomishDirPipelineSource([:])

    private Path projectWithDefinition() {
        Path project = Files.createDirectories(tempDir.resolve('project'))
        Path fixture = Paths.get(GnomishDirPipelineSourceSpec.getResource('/.gnomish-fixtures/valid').toURI())
        Path definition = project.resolve('.gnomish')
        Files.walk(fixture).forEach { Path entry ->
            Path target = definition.resolve(fixture.relativize(entry).toString())
            if (Files.isDirectory(entry)) {
                Files.createDirectories(target)
            } else {
                Files.createDirectories(target.parent)
                Files.copy(entry, target)
            }
        }
        project
    }

    def "load resolves the project's .gnomish subdirectory and returns the loader's outcome"() {
        when:
        def outcome = source.load(projectWithDefinition())

        then:
        outcome instanceof LoadOutcome.Loaded
    }

    def "a project without a .gnomish subdirectory surfaces the loader's IO failure, never a null outcome"() {
        given:
        Path bare = Files.createDirectories(tempDir.resolve('bare-project'))

        when:
        source.load(bare)

        then:
        def e = thrown(IOException)
        e.message.contains('config.yaml')
    }

    def "the validator registry is defensively copied at construction"() {
        given:
        def mutable = [:]
        def built = new GnomishDirPipelineSource(mutable)

        when:
        mutable['github'] = null

        then:
        built.trackerValidatorRegistry().isEmpty()
    }
}

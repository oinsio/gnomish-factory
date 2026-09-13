package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.app.CheckParamsValidator
import com.github.oinsio.gnomish.app.LawBinding
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator
import com.github.oinsio.gnomish.app.port.pipeline.ConfiguredDesignatorKinds
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.gitobjects.LocalGitRepoFixture
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
class GnomishDirPipelineSourceSpec extends Specification implements LocalGitRepoFixture {

    @TempDir
    Path tempDir

    def source = new GnomishDirPipelineSource([:], TrackerValidatorStub.discoveredGithubCheckProvider())

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

    /** A real, non-bare git repository whose HEAD commit carries the valid fixture's law. */
    private Path repoWithCommittedLaw() {
        Path project = projectWithDefinition()
        gitOutput(project, 'init')
        commitAll(project, 'law')
        project
    }

    // FR2, FR13, D12-D15 of add-base-ref-resolution: bindConfiguration reads both tiers from git
    //     objects at the bound revision, and pins the exact commit it read — never a blank/null one.
    def "bindConfiguration reads the law at the bound revision, pinning the peeled commit"() {
        given:
        def repo = repoWithCommittedLaw()
        def sha = gitOutput(repo, 'rev-parse', 'HEAD')
        def binding = LawBinding.atRevision(repo, 'HEAD')

        when:
        def bound = source.bindConfiguration(binding, ConfiguredDesignatorKinds.NONE)

        then:
        bound.outcome() instanceof LoadOutcome.Loaded
        bound.lawCommit().hex() == sha
    }

    // FR13, D14 of add-base-ref-resolution: bindTaskTier reads the task tier alone, pinning the
    //     same peeled commit bindConfiguration would.
    def "bindTaskTier reads the task tier at the bound revision, pinning the peeled commit"() {
        given:
        def repo = repoWithCommittedLaw()
        def sha = gitOutput(repo, 'rev-parse', 'HEAD')
        def binding = LawBinding.atRevision(repo, 'HEAD')

        when:
        def bound = source.bindTaskTier(binding)

        then:
        bound.outcome() instanceof LoadOutcome.Loaded
        bound.lawCommit().hex() == sha
    }

    // FR11, D12 of add-base-ref-resolution: a working-tree binding over a root that is no git
    //     repository at all resolves no checkout, so bindConfiguration/bindTaskTier refuse loudly
    //     instead of silently pinning a blank commit.
    def "bindConfiguration over a working tree with no repository refuses instead of pinning a blank commit"() {
        given: 'a law directory with no .git at all — the in-place workspace shape'
        def project = projectWithDefinition()
        def binding = LawBinding.workingTree(project)

        when:
        source.bindConfiguration(binding, ConfiguredDesignatorKinds.NONE)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('cannot bind the pipeline law')
        e.message.contains(project.toString())
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

    def "both registries are defensively copied at construction"() {
        given:
        Map<String, TrackerSubsectionValidator> mutableTrackers = [:]
        Map<String, CheckParamsValidator> mutableProviders = [:]
        def built = new GnomishDirPipelineSource(mutableTrackers, mutableProviders)

        when:
        mutableTrackers['github'] = null
        mutableProviders['github'] = null

        then:
        built.trackerValidatorRegistry().isEmpty()
        built.checkProviderRegistry().isEmpty()
    }

    // FR6, FR13 (add-plugin-architecture): the source closes the discovered
    // check-provider registry over the load, so an external check resolving to a
    // provider nobody discovered is a located load error rather than a mid-run failure
    def "an external check whose provider was never discovered is a located load error"() {
        given: 'a source built over an empty check-provider registry'
        def bare = new GnomishDirPipelineSource([:], [:])

        when:
        def outcome = bare.load(projectWithDefinition())

        then:
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors()*.where().contains('verify[2].provider')
    }
}

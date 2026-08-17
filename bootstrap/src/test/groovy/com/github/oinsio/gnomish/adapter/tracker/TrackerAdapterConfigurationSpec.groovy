package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerSubsectionValidator
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerAdapterFactory
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link TrackerAdapterConfiguration} (task 5.15): the {@code trackerAdapterRegistry} bean is
 * populated with the real GitHub and in-memory adapter factories, and the {@code
 * trackerSubsectionValidatorRegistry} bean with the real {@link GithubTrackerSubsectionValidator},
 * both keyed by {@code tracker.type} spelling ({@code github}, {@code inmemory}).
 *
 * <p>The validator-registry test proves the assembled-system half of the "Adapter errors aggregate
 * with core errors" scenario (FR17): when the real registry is threaded into {@link
 * PipelineLoader}, a malformed {@code tracker.github} subsection (a bad hex color) is a located
 * load error aggregated with an unrelated core error — the gap that the accept-anything placeholder
 * in {@code PipelineModelBuilder} previously left, where a bad color passed loading and surfaced
 * only later as a GitHub API error during {@code take}. This test lives here, in {@code
 * adapter.tracker}, because importing the concrete {@link GithubTrackerSubsectionValidator} is
 * forbidden everywhere else by {@code TrackerPortBoundarySpec}.
 *
 * <p>Implements FR9, FR17 of add-tracker-port; FR1, M1 of add-plugin-architecture.
 */
class TrackerAdapterConfigurationSpec extends Specification {

    @TempDir
    Path gnomishRoot

    // FR1 of add-plugin-architecture: the registry is populated by ServiceLoader, from the
    // META-INF/services entries the two adapter jars carry — not by any Map.of(...) in this class.
    def "the adapter registry is discovered from the classpath's service entries"() {
        given:
        def configuration = new TrackerAdapterConfiguration()

        when:
        def registry = configuration.trackerAdapterRegistry()

        then:
        registry.keySet() == ['github', 'inmemory'] as Set
        registry['github'] instanceof GithubTrackerAdapterFactory
        registry['inmemory'] instanceof InMemoryTrackerAdapterFactory
    }

    // FR1, design D1/D3: the validator registry is derived from the discovered factories rather than
    // discovered separately, so the two registries are keyed identically by construction; inmemory
    // grades no subsection content and therefore contributes no entry.
    def "the subsection-validator registry is derived from the discovered factories"() {
        given:
        def configuration = new TrackerAdapterConfiguration()

        when:
        def registry = configuration.trackerSubsectionValidatorRegistry(configuration.trackerAdapterRegistry())

        then:
        registry.keySet() == ['github'] as Set
        registry['github'] instanceof GithubTrackerSubsectionValidator
    }

    // FR17, "Adapter errors aggregate with core errors": with the real registry threaded into the
    // assembled loader, a bad hex color in tracker.github and an unrelated core error (unknown
    // executor) both surface in one LoadOutcome.Invalid — no longer masked by an accept-anything
    // placeholder.
    def "a bad github label color aggregates with a core error through the assembled loader"() {
        given: 'a github subsection with an invalid label color, plus an unknown-executor stage'
        write('config.yaml', '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
    labels:
      ready:
        name: gnomish:ready
        color: ZZZ
'''.stripIndent())
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', '''\
purpose: plan
executor:
  type: bogus-executor
  model: m
instructions: stages/plan/instructions.md
advancement: auto
'''.stripIndent())
        write('stages/plan/instructions.md', 'plan\n')

        when:
        def configuration = new TrackerAdapterConfiguration()
        def registry = configuration.trackerSubsectionValidatorRegistry(configuration.trackerAdapterRegistry())
        def outcome = PipelineLoader.load(gnomishRoot, registry, [:])

        then: 'one Invalid outcome carries both the bad-color adapter error and the core error'
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()
        errors.contains(new ConfigError('config.yaml', 'tracker.github.labels.ready.color',
                "'ZZZ' is not a valid 6-digit hex color (e.g. '2ea44f', no leading '#')"))
        errors.any {
            it.file() == 'stages/plan/stage.yaml' && it.where() == 'executor.type' &&
            it.message().contains("unknown executor 'bogus-executor'")
        }
    }

    private void write(String relative, String text) {
        Path target = gnomishRoot.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }
}

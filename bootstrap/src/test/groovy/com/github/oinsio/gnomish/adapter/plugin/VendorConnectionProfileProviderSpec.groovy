package com.github.oinsio.gnomish.adapter.plugin

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.CheckClientConfiguration
import com.github.oinsio.gnomish.adapter.check.CheckProviderSeam
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader
import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterConfiguration
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerAdapterFactory
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * What a connection profile does not do (FR16, design D8 of add-plugin-architecture,
 * `vendor-connection-profile` capability): it never chooses a port's provider, and core does not
 * grade a subsection that declares no reference — the provider's own validator does. Driven through
 * the discovered registries and the assembled pipeline loader, as {@link VendorConnectionProfileSpec}
 * drives the sharing itself.
 *
 * <p>Implements FR16 of add-plugin-architecture.
 */
class VendorConnectionProfileProviderSpec extends Specification {

    private static final String PROFILE = 'shared'

    @TempDir
    Path gnomishRoot

    // FR3, FR16: the subsection's own discriminator selects the provider; the profile is data only
    // Scenario: operator/vendor-connection-profile / A profile never selects the provider
    def "two ports referencing one profile each keep the provider their own subsection selects"() {
        given: 'a profile whose data even carries a type naming another tracker provider'
        def profiles = [(PROFILE): [('api-url'): 'https://api.github.com', credential: 'SHARED_TOKEN', type: InMemoryTrackerAdapterFactory.TYPE]]
        def properties = new FactoryProperties(null, null, null,
                [github: [connection: PROFILE, repo: 'acme/widgets']], profiles)
        writeTree("""\
tracker:
  type: github
  github:
    connection: ${PROFILE}
    repo: acme/widgets
""")

        when:
        def outcome = PipelineLoader.load(
                gnomishRoot, trackerValidators(), [:], [] as Set, ConnectionProfiles.of(profiles))
        def registry = new CheckClientConfiguration().checkClientRegistry(properties)

        then: 'the tracker port keeps the provider its own type selects, the profile only lending it data'
        outcome instanceof LoadOutcome.Loaded
        def tracker = (outcome as LoadOutcome.Loaded).definition().tracker()
        tracker.type() == 'github'
        tracker.subsection()['api-url'] == 'https://api.github.com'
        tracker.subsection()['type'] == InMemoryTrackerAdapterFactory.TYPE

        and: 'the check port is served by the provider its own subsection key names'
        registry['github'] instanceof GithubCheckClientFactory
        CheckProviderSeam.resolve(properties.check(), ConnectionProfiles.of(profiles)).keySet() == ['github'] as Set
    }

    // FR16: what a subsection without a reference lacks, only its provider knows
    // Scenario: operator/vendor-connection-profile / A subsection with neither form is left to its provider
    def "a subsection with neither a reference nor inline keys gets the provider's missing-key error and no core error"() {
        given: 'a github tracker subsection declaring only its labels, while a profile is defined'
        writeTree('''\
tracker:
  type: github
  github:
    labels: {}
''')

        when:
        def outcome = PipelineLoader.load(gnomishRoot, trackerValidators(), [:], [] as Set,
        ConnectionProfiles.of([(PROFILE): [('api-url'): 'https://api.github.com']]))

        then: 'the provider reports the keys it is missing, and nothing names the connection key'
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()
        errors*.where().containsAll([
            'tracker.github.api-url',
            'tracker.github.repo'
        ])
        errors.find {
            it.where() == 'tracker.github.api-url'
        }.message().contains("missing required key 'api-url'")
        errors.every {
            !it.where().endsWith(".${ConnectionProfiles.REFERENCE_KEY}")
        }
        errors.every { !it.message().contains('connection profile') }
    }

    /** The real, discovered tracker subsection validators. */
    private static Map trackerValidators() {
        def configuration = new TrackerAdapterConfiguration()
        configuration.trackerSubsectionValidatorRegistry(configuration.trackerAdapterRegistry())
    }

    /** A minimal valid tree whose config.yaml carries the given tracker section. */
    private void writeTree(String trackerSection) {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n' + trackerSection)
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', '''\
purpose: plan
executor:
  type: agent-cli
  model: m
instructions: stages/plan/instructions.md
verify:
  - type: command
    command: echo ok
advancement: auto
''')
        write('stages/plan/instructions.md', 'plan\n')
    }

    private void write(String relative, String text) {
        Path target = gnomishRoot.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }
}

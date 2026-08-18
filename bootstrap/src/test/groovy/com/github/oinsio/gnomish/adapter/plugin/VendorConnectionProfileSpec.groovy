package com.github.oinsio.gnomish.adapter.plugin

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.CheckClientConfiguration
import com.github.oinsio.gnomish.adapter.check.CheckProviderSeam
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader
import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterConfiguration
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Named per-vendor connection profiles across the two ports one vendor serves (FR16, FR17, UX3,
 * design D8/D11 of add-plugin-architecture, `vendor-connection-profile` capability).
 *
 * <p>The ergonomic claim of D8 is narrow and testable: a github tracker and github checks share ONE
 * {@code factory.connections.<name>} definition, so the endpoint and the credential name exist once
 * and cannot drift — while each port still selects its provider independently, and a config mixing
 * vendors is unaffected. The security claim of D11 rides along: a name a profile supplies is
 * configuration data no compile-time constant can see, and it must reach the child-environment
 * scrub / never-allowlist set all the same.
 *
 * <p>Both real registries are built by discovery here, and both real github factories answer, so
 * this is the assembled path an operator gets — not a hand-built stand-in of it.
 */
class VendorConnectionProfileSpec extends Specification {

    @TempDir
    Path gnomishRoot

    private static final String PROFILE = 'github-main'
    private static final String RENAMED_CREDENTIAL = 'GNOMISH_GH_MAIN_TOKEN'
    private static final String API_URL = 'https://api.github.com'

    /** One profile: the endpoint and the credential NAME (never a value, NFR-S1). */
    private static Map profileConfig() {
        [(PROFILE): [('api-url'): API_URL, credential: RENAMED_CREDENTIAL]]
    }

    /** The check port's subsection: the shared connection by name, plus its own per-port key. */
    private static Map checkSubsection() {
        [github: [connection: PROFILE, repo: 'acme/widgets']]
    }

    private static FactoryProperties propertiesWithProfile(Map check = checkSubsection()) {
        new FactoryProperties(null, null, null, null, check, profileConfig())
    }

    // FR16, "Two ports share one named profile": one definition, both ports — and the endpoint and
    //     credential each port ends up with are the profile's, with nothing duplicated to drift.
    def "a github tracker and github checks resolve one shared profile"() {
        given: 'a repo-side tracker subsection referencing the same profile the check subsection does'
        writeTree("""\
tracker:
  type: github
  github:
    connection: ${PROFILE}
    repo: acme/widgets
""")
        def properties = propertiesWithProfile()
        def profiles = ConnectionProfiles.of(properties.connections())

        when: 'the tracker loads through the assembled loader and the check subsections resolve'
        def outcome = PipelineLoader.load(gnomishRoot, trackerValidators(), [:], profiles)
        def checkResolved = CheckProviderSeam.resolve(properties.check(), profiles)['github']

        then: 'the tracker config carries the profile endpoint, not the reference'
        outcome instanceof LoadOutcome.Loaded
        def trackerConfig = (outcome as LoadOutcome.Loaded).definition().tracker()
        trackerConfig.subsection()['api-url'] == API_URL
        trackerConfig.subsection()['repo'] == 'acme/widgets'
        !trackerConfig.subsection().containsKey('connection')

        and: 'the check provider is handed the very same connection data'
        checkResolved['api-url'] == API_URL

        and: 'both ports declare the one credential the profile names — no vendor constant in sight'
        new GithubTrackerAdapterFactory().credentialEnvVars(trackerConfig) == [RENAMED_CREDENTIAL]
        new GithubCheckClientFactory().credentialEnvVars(checkResolved) == [RENAMED_CREDENTIAL]
    }

    // FR16, "Mixed-vendor configuration is unaffected": sharing is opt-in per subsection. A second
    //     provider configured inline alongside the profile-referencing one is valid and untouched.
    def "a mixed-vendor configuration still works"() {
        given: 'github by profile, the built-in http provider inline with its own allowlist'
        def check = checkSubsection() + [http: [allowlist: ['sonar.example.com']]]
        def properties = propertiesWithProfile(check)

        when:
        def registry = new CheckClientConfiguration().checkClientRegistry(properties)
        def resolved = CheckProviderSeam.resolve(properties.check(), ConnectionProfiles.of(properties.connections()))

        then: 'startup accepts both, each port resolving its own connection independently'
        registry.keySet().containsAll(['github', 'http'])
        resolved['github']['api-url'] == API_URL
        resolved['http'] == [allowlist: ['sonar.example.com']]
    }

    // FR16, "Undefined profile name is a located error": on the repo side the mistake is a load
    //     error naming the missing profile — never a mid-take failure at first API call.
    def "an undefined profile reference in the tracker subsection is a located load error"() {
        given:
        writeTree('''\
tracker:
  type: github
  github:
    connection: does-not-exist
    repo: acme/widgets
''')

        when:
        def outcome = PipelineLoader.load(
                gnomishRoot, trackerValidators(), [:], ConnectionProfiles.of(profileConfig()))

        then:
        outcome instanceof LoadOutcome.Invalid
        def error = (outcome as LoadOutcome.Invalid).errors().find {
            it.where() == 'tracker.github.connection'
        }
        error.file() == 'config.yaml'
        error.message().contains("undefined connection profile 'does-not-exist'")
        error.message().contains(PROFILE)
    }

    // FR16: on the operator side the same mistake fails startup, with the located problem in the
    //     message — the check port's twin of the load error above.
    def "an undefined profile reference in a check subsection fails startup"() {
        when:
        new CheckClientConfiguration()
                .checkClientRegistry(new FactoryProperties(null, null, null, null,
                [github: [connection: 'does-not-exist', repo: 'acme/widgets']], profileConfig()))

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('factory.check.github.connection')
        e.message.contains("undefined connection profile 'does-not-exist'")
    }

    // FR16, "Ambiguous connection declaration is a located error": a subsection declares one form or
    //     the other; inlining a key the profile already carries is the drift profiles exist to stop.
    def "declaring both the reference and an inline profile key fails startup"() {
        when:
        new CheckClientConfiguration()
                .checkClientRegistry(new FactoryProperties(null, null, null, null,
                [github: [connection: PROFILE, ('api-url'): 'https://ghe.acme.test', repo: 'acme/widgets']],
                profileConfig()))

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('factory.check.github.api-url')
        e.message.contains('declare exactly one form')
    }

    // FR16, "An inline key the profile also defines is a located error": the check port's twin,
    //     on the repo side — the same per-key verdict reaches the operator as a load error, not a
    //     startup exception, because the offending subsection lives in the target repo's config.
    def "declaring both the reference and an inline profile key in the tracker subsection is a located load error"() {
        given: 'the tracker subsection re-inlines api-url, the very key the profile carries'
        writeTree("""\
tracker:
  type: github
  github:
    connection: ${PROFILE}
    api-url: https://ghe.acme.test
    repo: acme/widgets
""")

        when:
        def outcome = PipelineLoader.load(
                gnomishRoot, trackerValidators(), [:], ConnectionProfiles.of(profileConfig()))

        then: 'the error is located at the overlapping key, naming the profile it conflicts with'
        outcome instanceof LoadOutcome.Invalid
        def error = (outcome as LoadOutcome.Invalid).errors().find {
            it.where() == 'tracker.github.api-url'
        }
        error.file() == 'config.yaml'
        error.message().contains("declares both 'connection: ${PROFILE}'")
        error.message().contains('declare exactly one form')
    }

    // FR17, "Renamed credential in a profile is still scrubbed": the profile-supplied name enters
    //     the run's declared-credential set through the SPI, so passthrough refuses it exactly as it
    //     refuses a vendor's default constant — no core source names either.
    def "a profile-renamed credential cannot be admitted into the passthrough allowlist"() {
        given:
        def properties = propertiesWithProfile()
        def registry = new CheckClientConfiguration().checkClientRegistry(properties)
        def declared = CheckProviderSeam.credentialEnvVars(
                CheckProviderSeam.resolve(properties.check(), ConnectionProfiles.of(properties.connections())),
                registry)

        expect: 'the declared set follows the profile, not the provider constant'
        declared == [RENAMED_CREDENTIAL]

        and: 'and it is scrubbed from a composed child environment even when a layer carries it'
        !ChildEnvAllowlist.of([], declared)
        .compose([RENAMED_CREDENTIAL], [(RENAMED_CREDENTIAL): 'planted-token'])
        .containsKey(RENAMED_CREDENTIAL)

        when: 'an operator tries to pass that very variable through to the gnome'
        ChildEnvAllowlist.of([RENAMED_CREDENTIAL], declared)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(RENAMED_CREDENTIAL)
    }

    /** The real, discovered tracker subsection validators — the github one grades what loads here. */
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

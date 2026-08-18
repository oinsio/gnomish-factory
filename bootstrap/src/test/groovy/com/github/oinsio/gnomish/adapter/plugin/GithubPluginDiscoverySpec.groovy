package com.github.oinsio.gnomish.adapter.plugin

import com.github.oinsio.gnomish.adapter.check.CheckClientDiscovery
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterDiscovery
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory
import com.github.oinsio.gnomish.app.CheckClientFactory
import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import spock.lang.Specification

/**
 * The bundled github jar loads through the identical path a third-party jar uses — there is no
 * privileged built-in shortcut (FR12, design D7 of add-plugin-architecture, github-plugin
 * capability).
 *
 * <p>"Identical path" is three concrete claims, and this spec makes each one falsifiable. The
 * registration is a {@code META-INF/services} file inside the github artifact, the same file kind
 * and name a third party would ship. It is read by the same {@code ServiceLoader} pass over the
 * same discovery entry point that {@code TrackerAdapterDiscoverySpec} / {@code
 * CheckClientDiscoverySpec} drive their stand-in plugin through. And the provider is reached only
 * by its discriminator: hiding the artifact's registration removes it, which no built-in shortcut
 * would allow.
 *
 * <p>Implements FR12 of add-plugin-architecture.
 */
class GithubPluginDiscoverySpec extends Specification {

    /** The discriminator both github providers declare — the only handle the core has on them. */
    private static final String GITHUB = 'github'

    // FR12: "the github tracker and check providers are discovered through ServiceLoader" — from
    //     the general registry, keyed by the discriminator each declares for itself.
    def "both github providers arrive in the discovered registry under their own discriminator"() {
        when:
        def trackers = TrackerAdapterDiscovery.discover()
        def checks = CheckClientDiscovery.discover()

        then:
        trackers[GITHUB] instanceof GithubTrackerAdapterFactory
        trackers[GITHUB].type() == GITHUB

        and:
        checks[GITHUB] instanceof GithubCheckClientFactory
        checks[GITHUB].provider() == GITHUB
    }

    // FR12: what makes them discoverable is a service file inside the github artifact itself —
    //     the registration a third-party jar carries, in the same place, under the same SPI name.
    def "the #spi registration comes from the github artifact, as a plugin jar's would"() {
        when: 'every registration of this SPI visible on the classpath'
        def registrations = getClass().classLoader.getResources('META-INF/services/' + spi.name).toList()

        then: 'one of them lives in the github artifact itself and names the github provider'
        def fromGithub = registrations.findAll {
            GithubArtifact.contributes(it)
        }
        fromGithub.size() == 1
        fromGithub.first().text.contains(provider.name)

        where:
        spi | provider
        TrackerAdapterFactory | GithubTrackerAdapterFactory
        CheckClientFactory | GithubCheckClientFactory
    }

    // FR12: no built-in shortcut — the registration is the whole mechanism. Hide the artifact's
    //     service files and github is simply not there, exactly as for any other plugin jar.
    def "hiding the artifact's registrations removes github from both registries"() {
        given:
        def withoutGithub = GithubArtifact.hiddenFrom(getClass().classLoader)

        when:
        def trackers = TrackerAdapterDiscovery.discover(withoutGithub)
        def checks = CheckClientDiscovery.discover(withoutGithub)

        then:
        !trackers.containsKey(GITHUB)
        !checks.containsKey(GITHUB)
    }
}

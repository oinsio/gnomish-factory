package com.github.oinsio.gnomish.adapter.plugin

import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterDiscovery
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory
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
 * same discovery entry point that {@code TrackerAdapterDiscoverySpec} drives its stand-in plugin
 * through. And the provider is reached only
 * by its discriminator: hiding the artifact's registration removes it, which no built-in shortcut
 * would allow.
 *
 * <p>Implements FR12 of add-plugin-architecture; FR13 of migrate-verify-to-executor-contract.
 */
class GithubPluginDiscoverySpec extends Specification {

    /**
     * The check SPI factory type, retired by task 6.1 of migrate-verify-to-executor-contract: a
     * registration under its name would be a stale provider no loader reads.
     */
    private static final String RETIRED_CHECK_SPI = 'com.github.oinsio.gnomish.app.CheckClientFactory'

    /** The discriminator the github tracker declares — the only handle the core has on it. */
    private static final String GITHUB = 'github'

    // FR12: "the github tracker provider is discovered through ServiceLoader" — from the general
    //     registry, keyed by the discriminator it declares for itself. The github-actions check is
    //     core's own built-in since task 3.3 of migrate-verify-to-executor-contract (FR13, design D4),
    //     and no check provider is discovered at all since task 5.2.
    // Scenario: plugin/github-plugin / GitHub is discovered like any third-party plugin
    def "the github tracker provider arrives in the discovered registry under its own discriminator"() {
        when:
        def trackers = TrackerAdapterDiscovery.discover()

        then:
        trackers[GITHUB] instanceof GithubTrackerAdapterFactory
        trackers[GITHUB].type() == GITHUB
    }

    // FR12: what makes the tracker discoverable is a service file inside the github artifact itself —
    //     the registration a third-party jar carries, in the same place, under the same SPI name.
    // Scenario: plugin/github-plugin / GitHub is discovered like any third-party plugin
    def "the tracker registration comes from the github artifact, as a plugin jar's would"() {
        when: 'every registration of the tracker SPI visible on the classpath'
        def registrations = getClass().classLoader.getResources('META-INF/services/' + TrackerAdapterFactory.name).toList()

        then: 'one of them lives in the github artifact itself and names the github provider'
        def fromGithub = registrations.findAll {
            GithubArtifact.contributes(it)
        }
        fromGithub.size() == 1
        fromGithub.first().text.contains(GithubTrackerAdapterFactory.name)
    }

    // FR13 of migrate-verify-to-executor-contract: no artifact registers a check provider — the
    //     bundle none, and core none since task 5.2 removed the check SPI's discovery
    // Scenario: plugin/github-plugin / GitHub is discovered like any third-party plugin
    def "no artifact carries a check-provider registration"() {
        expect:
        getClass().classLoader.getResources('META-INF/services/' + RETIRED_CHECK_SPI).toList() == []
    }

    // FR12: no built-in shortcut — the registration is the whole mechanism. Hide the artifact's
    //     service files and the github tracker is simply not there, exactly as for any other plugin
    //     jar.
    def "hiding the artifact's registrations removes the github tracker"() {
        given:
        def withoutGithub = GithubArtifact.hiddenFrom(getClass().classLoader)

        when:
        def trackers = TrackerAdapterDiscovery.discover(withoutGithub)

        then:
        !trackers.containsKey(GITHUB)
    }
}

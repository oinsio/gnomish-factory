package com.github.oinsio.gnomish.adapter.sandbox

import com.github.oinsio.gnomish.adapter.plugin.LogCaptureSupport
import com.github.oinsio.gnomish.adapter.plugin.ProviderDiscoveryReport
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.HostBindingProvider
import com.github.oinsio.gnomish.sandbox.environment.ContainerBindingProvider
import java.util.function.Function
import spock.lang.Specification

/**
 * What an operator sees at startup about the bindings the classpath contributed (NFR-O1, UX3,
 * design D6 of open-adapter-binding-registry).
 *
 * For the sandbox this report is not a convenience: the trust boundary has no runtime enforcement
 * behind it (the flat classpath is the trust domain), so visibility is the compensating control.
 * Each line therefore carries the config name an operator binds, the artifact the provider was
 * loaded from, its class, and the passport it was ratified with — enough to answer "which binding
 * weakens what, and where did it come from" before any stage runs.
 *
 * Implements NFR-O1, UX3 of open-adapter-binding-registry.
 */
class SandboxBindingReportSpec extends Specification {

    // NFR-O1/UX3: the discovered bindings, their origin and their passports, before any stage runs
    def "the startup report lists every discovered binding with its origin and passport"() {
        given: 'the bindings this build actually ships'
        def registry = SandboxBindingDiscovery.discover()

        when:
        def lines = ProviderDiscoveryReport.renderOrigins(
                'sandbox binding',
                registry.providerTypes(), {
                    SandboxBindingConfiguration.summarize(registry.require(it))
                } as Function)

        then: 'a counted header naming the port'
        lines[0] == 'discovered ' + registry.bindings().size() + ' sandbox binding provider(s):'

        and: 'one line per binding: the config name, the artifact behind it, the declaring provider'
        def host = lines.find {
            it.startsWith('  ' + BindingNames.HOST + ' <- ')
        }
        def container = lines.find {
            it.startsWith('  ' + BindingNames.CONTAINER + ' <- ')
        }
        host.startsWith('  ' + BindingNames.HOST + ' <- '
                + ProviderDiscoveryReport.artifactOf(HostBindingProvider)
                + ' (' + HostBindingProvider.name + ')')
        container.startsWith('  ' + BindingNames.CONTAINER + ' <- '
                + ProviderDiscoveryReport.artifactOf(ContainerBindingProvider)
                + ' (' + ContainerBindingProvider.name + ')')

        and: 'each line ends with the full passport that binding was ratified with'
        host.endsWith(' [isolation=NONE egress-controlled=false task-to-task-boundary=false docker-inside=true]')
        container.endsWith(
                ' [isolation=CONTAINER egress-controlled=true task-to-task-boundary=true docker-inside=false]')

        and: 'the container binding is reported from the docker module, not from core'
        ProviderDiscoveryReport.artifactOf(ContainerBindingProvider) !=
                ProviderDiscoveryReport.artifactOf(HostBindingProvider)
    }

    // NFR-O1: the report reaches the operator through the log, and the registry the bean returns is
    // the discovered one — reporting wraps the result rather than replacing it
    def "the configuration bean reports the discovered bindings and returns them"() {
        given:
        def capture = LogCaptureSupport.attach(ProviderDiscoveryReport)

        when:
        def registry = new SandboxBindingConfiguration().adapterBindingRegistry()

        then: 'the shipped bindings are both present'
        registry.names().containsAll([
            BindingNames.HOST,
            BindingNames.CONTAINER
        ])

        and: 'and both were logged with their passports before anything else could run'
        def logged = capture.list*.formattedMessage
        logged.any {
            it.startsWith('discovered ') && it.contains('sandbox binding provider(s)')
        }
        logged.any {
            it.contains(BindingNames.CONTAINER) && it.contains('isolation=CONTAINER')
            && it.contains(ContainerBindingProvider.name)
        }

        cleanup:
        capture.detach()
    }
}

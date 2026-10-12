package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.CheckClientConfiguration
import java.nio.file.Path
import org.springframework.boot.context.properties.bind.Binder
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The http check provider's egress allowlist, set in an operator file, reaches the provider as the
 * list the operator wrote: from the project file, through the production loader and Spring's
 * Binder, into {@link FactoryProperties} and the check-provider registry that grades it. Every
 * other spec of the http provider builds {@code FactoryProperties} by hand and so never crosses
 * the Binder, which binds a list inside a raw subtree as a map keyed by index (finding F130 of
 * tighten-spec-contract).
 *
 * <p>Implements FR5 of add-project-registry; FR4, UX2 of add-plugin-architecture.
 */
class OperatorConfigCheckAllowlistSpec extends Specification {

    @TempDir
    Path tmp

    // Scenario: verification/factory-egress-allowlist / Allowlist lives in operator config, not the manifest
    def "FR4: an http allowlist set in the project file binds as a list the provider accepts"() {
        given:
        def harness = new OperatorConfigLoaderHarness(tmp)
        def widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
        harness.projectConfig('widgets', '''\
            factory:
              check:
                http:
                  allowlist: [sonar.example.com, ci.example.com]
            '''.stripIndent())

        when:
        def loaded = harness.load([
            'status',
            "--dir=$widgets".toString()
        ])
        def properties = Binder.get(loaded.environment).bindOrCreate('factory', FactoryProperties)
        def registry = new CheckClientConfiguration().checkClientRegistry(properties)

        then: 'the registry grades the subsection valid, and the provider reads the list in order'
        registry.containsKey('http')
        properties.check().http.allowlist == [
            'sonar.example.com',
            'ci.example.com'
        ]
    }
}

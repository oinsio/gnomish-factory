package com.github.oinsio.gnomish

import spock.lang.Specification

/**
 * SandboxProperties: immutable typed configuration record for the container
 * adapter and egress guard knobs (design D2, D4, D7; FR3, FR7, FR9, FR10 of
 * add-sandbox-core). Validation is plain Java in the compact constructor,
 * mirroring ServePropertiesSpec — no Spring context needed here. {@code image}
 * is legitimately absent on a host-only install and stays null; every other knob
 * defaults when unset.
 *
 * FR3/FR7/FR9/FR10: the operator-owned sandbox knobs are carried, typed.
 */
class SandboxPropertiesSpec extends Specification {

    // FR3: image is optional — a host-only install sets none and it stays null
    def "image is null when unset and exposed unchanged when set"() {
        expect: 'unset image stays null (host-only install)'
        new SandboxProperties(null, null, null, null, null, null, false).image() == null

        and: 'a configured image is exposed unchanged'
        new SandboxProperties('ghcr.io/acme/box:1', null, null, null, null, null, false).image() == 'ghcr.io/acme/box:1'
    }

    // D2: runtime defaults to runc when unset
    def "runtime defaults to runc when unset"() {
        expect: 'the unset runtime resolves to the design D2 default'
        new SandboxProperties(null, null, null, null, null, null, false).runtime() == 'runc'
    }

    // D2: an explicit runtime overrides the default
    def "runtime of an explicit value is exposed unchanged"() {
        expect: 'the accessor returns exactly the configured runtime'
        new SandboxProperties(null, 'sysbox-runc', null, null, null, null, false).runtime() == 'sysbox-runc'
    }

    // D4: the guard image defaults to the pinned-major official mitmproxy image when unset
    def "guard image defaults to the pinned official image when unset"() {
        expect: 'the unset guard image resolves to the design D4 default'
        new SandboxProperties(null, null, null, null, null, null, false).guardImage() == 'mitmproxy/mitmproxy:12'
    }

    // D4: an explicit guard image (e.g. a private mirror) overrides the default
    def "guard image of an explicit value is exposed unchanged"() {
        expect: 'the accessor returns exactly the configured guard image'
        new SandboxProperties(null, null, 'mirror.local/mitmproxy:12', null, null, null, false).guardImage() ==
                'mirror.local/mitmproxy:12'
    }

    // D2/D4: a blank string knob is a configuration mistake, rejected with its property name
    def "blank #property value is rejected with the property name in the message"() {
        when: 'a properties record is built with a blank value for the knob'
        new SandboxProperties(null, runtime, guardImage, null, null, null, false)

        then: 'construction fails and the message names the property'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains(property)

        where:
        property                      | runtime | guardImage
        'factory.sandbox.runtime'     | ''      | null
        'factory.sandbox.runtime'     | '   '   | null
        'factory.sandbox.guard-image' | null    | ''
        'factory.sandbox.guard-image' | null    | '   '
    }

    // FR10: limits default to the documented set when unset
    def "limits default to the documented set when unset"() {
        expect: 'the unset limits component resolves to ResourceLimits.defaults()'
        new SandboxProperties(null, null, null, null, null, null, false).limits() == ResourceLimits.defaults()
    }

    // FR10: explicit limits are exposed unchanged
    def "explicit limits are exposed unchanged"() {
        given: 'an explicit limits set'
        def limits = new ResourceLimits('4', '8g', 1024L, '50g')

        expect: 'the accessor returns exactly that set'
        new SandboxProperties(null, null, null, limits, null, null, false).limits() == limits
    }

    // FR7: the egress allowlist defaults to empty (default-deny with nothing allowed)
    def "egress allowlist defaults to empty when unset"() {
        expect: 'the unset allowlist is empty'
        new SandboxProperties(null, null, null, null, null, null, false).egressAllowlist() == []
    }

    // FR9: the env passthrough list defaults to empty when unset
    def "env passthrough defaults to empty when unset"() {
        expect: 'the unset passthrough list is empty'
        new SandboxProperties(null, null, null, null, null, null, false).envPassthrough() == []
    }

    // FR7/FR9: the list knobs are defensively copied and exposed immutable
    def "#knob is defensively copied and exposed immutable"() {
        given: 'a mutable source list'
        def source = ['first']

        when: 'the properties are built and the source grows afterwards'
        def properties = build.call(source)
        source << 'later-noise'

        then: 'the exposed list holds only the original element'
        accessor.call(properties) == ['first']

        when: 'a caller tries to mutate the exposed list'
        accessor.call(properties) << 'intruder'

        then: 'the list rejects the mutation'
        thrown(UnsupportedOperationException)

        where:
        knob              | build                                                          | accessor
        'egressAllowlist' | { new SandboxProperties(null, null, null, null, it, null, false) }    | { it.egressAllowlist() }
        'envPassthrough'  | { new SandboxProperties(null, null, null, null, null, it, false) }    | { it.envPassthrough() }
    }

    // FR3/FR7/FR9/FR10: the properties type is an immutable record without setters
    def "the properties type is an immutable record without setter methods"() {
        expect: 'it is a Java record'
        SandboxProperties.isRecord()

        and: 'no public method follows the mutable setter convention'
        SandboxProperties.methods.every { !(it.name.startsWith('set') && it.parameterCount > 0) }
    }
}

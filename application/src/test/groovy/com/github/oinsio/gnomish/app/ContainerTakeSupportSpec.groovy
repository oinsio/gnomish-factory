package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.sandbox.BindingNames
import spock.lang.Specification

/**
 * {@link ContainerTakeSupport#hostOnly()}: the host-only bundle's {@code dockerProbe} always
 * answers unreachable, so {@link com.github.oinsio.gnomish.app.SandboxModeSelector} never even
 * asks whether a container prerequisite is met — the default binding resolves HOST regardless.
 */
class ContainerTakeSupportSpec extends Specification {

    def "hostOnly() always binds host and never claims Docker is reachable"() {
        given:
        def support = ContainerTakeSupport.hostOnly()

        expect:
        support.bindingProperties().defaultBinding() == BindingNames.HOST
        !support.dockerProbe().getAsBoolean()
    }
}

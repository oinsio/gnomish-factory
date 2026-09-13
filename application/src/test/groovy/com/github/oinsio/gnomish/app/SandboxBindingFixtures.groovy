package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxBindingProvider

/**
 * The container {@link SandboxBindingProvider} both the mode-selector and the container-dispatch
 * specs stand in for the docker backend module with (FR3, D6 of open-adapter-binding-registry):
 * this module carries neither the host nor the container backend, so a fixture provider fills in
 * for the registry the selector plans against.
 */
trait SandboxBindingFixtures {

    static SandboxBindingProvider containerProvider() {
        new ContainerSandboxBindingProvider()
    }
}

/**
 * The fixture {@link SandboxBindingProvider} for the container binding, extracted so
 * {@link SandboxBindingFixtures} does not declare an anonymous inner class inside a trait
 * (Groovy traits forbid non-static inner classes).
 */
class ContainerSandboxBindingProvider implements SandboxBindingProvider {

    @Override
    String configName() {
        BindingNames.CONTAINER
    }

    @Override
    CapabilityPassport passport() {
        CapabilityPassport.container()
    }
}

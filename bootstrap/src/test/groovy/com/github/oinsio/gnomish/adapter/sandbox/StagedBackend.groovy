package com.github.oinsio.gnomish.adapter.sandbox

import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxBindingProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * How {@link StubVmBindingProvider} is staged as a backend module would ship it: a {@code
 * META-INF/services} entry on a class loader of its own, plus the one-line trust registration a
 * reviewer would add for a real first-party backend (M4 of open-adapter-binding-registry).
 *
 * Shared by the discovery and extension-point specs, which stage the same backend the same way —
 * one staging recipe, so the two specs cannot drift into testing different extension points.
 */
class StagedBackend {

    /** A loader carrying a service entry for the stand-in backend, as its jar would. */
    static URLClassLoader loader(Path jarRoot) {
        Path services = jarRoot.resolve('META-INF/services')
        Files.createDirectories(services)
        Files.writeString(services.resolve(SandboxBindingProvider.name), StubVmBindingProvider.name + '\n')
        new URLClassLoader([
            jarRoot.toUri().toURL()
        ] as URL[], StagedBackend.classLoader)
    }

    /** The production trust table plus the stand-in's own registration — the reviewed one-liner. */
    static Map<String, CapabilityPassport> trustTable() {
        BindingTrustTable.firstParty() + [(StubVmBindingProvider.CONFIG_NAME): StubVmBindingProvider.PASSPORT]
    }
}

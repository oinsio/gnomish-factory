package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Files
import java.nio.file.Path

/**
 * A factory home of the spec's own, for a spec that boots the factory in process (design D8 of
 * add-project-registry): a temporary folder named by the {@code GNOMISH_HOME} system property —
 * the Spring {@code Environment} exposes it exactly as it exposes the OS variable — so the
 * configuration loader reads this home's {@code factory.yaml} and project files and never the
 * operator's own {@code ~/.gnomish}. A clone the spec boots against is registered through the
 * production {@link ProjectRegistry#add} (`testing.md`, "Fixtures assemble through production
 * owners"), never by writing a project file by hand.
 *
 * <p>Install in {@code setup}/{@code setupSpec}, {@link #close} in the matching cleanup: the system
 * property is JVM-global, and closing restores the value it replaced.
 *
 * <p>Implements FR1, FR3 of add-project-registry (test support).
 */
final class OperatorHomeFixture implements AutoCloseable {

    /** The home the loader reads, under the spec's temporary folder. */
    final FactoryHome home

    private final String previous

    private OperatorHomeFixture(FactoryHome home, String previous) {
        this.home = home
        this.previous = previous
    }

    /**
     * Creates {@code root} as the factory home and names it through {@code GNOMISH_HOME}.
     *
     * @param root a folder inside the spec's temporary folder; created if missing
     */
    static OperatorHomeFixture install(Path root) {
        Files.createDirectories(root)
        String previous = System.getProperty(FactoryHome.HOME_VARIABLE)
        System.setProperty(FactoryHome.HOME_VARIABLE, root.toString())
        new OperatorHomeFixture(FactoryHome.at(root), previous)
    }

    /**
     * Registers {@code clone} as a clone of {@code project} through the production registry.
     *
     * @param clone an existing git working tree
     * @return the registered clone, as the loader will resolve it
     */
    RegisteredClone register(String project, Path clone) {
        ProjectRegistry.scan(home).add(new ProjectName(project), clone.toAbsolutePath().normalize())
    }

    /** Writes the host file, {@code factory.yaml}, owner-writable only. */
    void hostConfig(String yaml) {
        Files.writeString(home.hostConfig(), yaml)
    }

    @Override
    void close() {
        if (previous == null) {
            System.clearProperty(FactoryHome.HOME_VARIABLE)
        } else {
            System.setProperty(FactoryHome.HOME_VARIABLE, previous)
        }
    }
}

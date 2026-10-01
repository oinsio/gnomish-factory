package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The project a project-scoped command works in: the registered clone the configuration loader
 * resolved from {@code --dir}, and the identity this process works under there (FR3, FR10, design
 * D2, D9 of add-project-registry). Every one of the seven project-scoped commands takes it, so the
 * directory a command works in is the one the registry matched — no command resolves {@code --dir}
 * itself — and the instance id every command mints names the project.
 *
 * <p>The clone is read when a command runs, never at construction: the {@code registeredClone}
 * bean exists only once the loader resolved a project, and a context booted for {@code project
 * add} or with no command line has none. A command that runs without it is a wiring defect — the
 * loader refuses an unregistered directory before the context exists — so {@link #registeredClone()} fails
 * loudly instead of falling back to a directory of its own.
 *
 * <p>Implements FR3, FR10 of add-project-registry.
 */
@Component
final class ProjectScope {

    private final ObjectProvider<RegisteredClone> resolvedClone;
    private final String instanceName;

    /**
     * @param resolvedClone the {@code registeredClone} bean the configuration loader registers,
     *     read lazily (design D9 of add-project-registry)
     * @param factoryProperties supplies the configured {@code factory.instance-name}
     */
    ProjectScope(ObjectProvider<RegisteredClone> resolvedClone, FactoryProperties factoryProperties) {
        this.resolvedClone = resolvedClone;
        this.instanceName = factoryProperties.instanceName();
    }

    /**
     * The registered clone {@code --dir} names.
     *
     * @throws IllegalStateException if no project was resolved for this process
     */
    RegisteredClone registeredClone() {
        RegisteredClone clone = resolvedClone.getIfAvailable();
        if (clone == null) {
            throw new IllegalStateException("no registered clone in this process: the configuration loader resolves"
                    + " --dir before a project-scoped command runs");
        }
        return clone;
    }

    /**
     * Mints this process's instance id, {@code <project>-<instance>-<suffix>}: the project name
     * first, so a tracker comment names the project (FR10), then the configured instance name and a
     * fresh per-process suffix. The one place the two names are joined.
     */
    InstanceId mintInstanceId() {
        return InstanceId.generate(registeredClone().project().value() + "-" + instanceName);
    }
}

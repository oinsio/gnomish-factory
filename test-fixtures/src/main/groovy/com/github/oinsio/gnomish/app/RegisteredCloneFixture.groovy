package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.project.CloneName
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Path
import java.util.function.Supplier
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition

/**
 * A {@link RegisteredClone} for a spec. {@link #registered} goes through the production {@link
 * ProjectRegistry#add} for a clone that is a real git working tree (`testing.md`, "Fixtures
 * assemble through production owners"), under a factory home of the spec's own and without naming
 * it through {@code GNOMISH_HOME} — a spec that boots the factory uses {@link
 * OperatorHomeFixture#register} instead. {@link #unregistered} is for a spec that needs a
 * project's layout but has no git working tree to register (a fake clone path behind stubbed git
 * ports): the value is built directly, so the name says {@code unregistered}.
 *
 * <p>{@link #provider} hands the clone the way the composition root does: as the lazily read
 * {@code registeredClone} bean the configuration loader registers (design D9 of
 * add-project-registry); {@link #scope} hands it the way a project-scoped command takes it, through
 * the production {@link ProjectScope}.
 *
 * <p>Implements FR3, FR9, FR10 of add-project-registry (test support).
 */
final class RegisteredCloneFixture {

    /** The project name a spec gets unless it names its own. */
    static final String PROJECT = 'widgets'

    private RegisteredCloneFixture() {}

    /**
     * Registers {@code clonePath} as a clone of {@code project} through the production registry,
     * under a factory home rooted at {@code home}; the clone is named by its folder, as {@code
     * project add} names it.
     *
     * @param home the factory home's root, inside the spec's temporary folder
     * @param clonePath an existing git working tree; made absolute
     */
    static RegisteredClone registered(Path home, Path clonePath, String project = PROJECT) {
        ProjectRegistry.scan(FactoryHome.at(home)).add(new ProjectName(project), clonePath.toAbsolutePath().normalize())
    }

    /**
     * A clone of {@code project} at {@code clonePath}, under a factory home rooted at {@code home}.
     *
     * @param home the factory home's root, inside the spec's temporary folder
     * @param clonePath the clone's path; made absolute
     */
    static RegisteredClone unregistered(Path home, Path clonePath, String project = PROJECT) {
        def name = new ProjectName(project)
        new RegisteredClone(name, new CloneName('main'), clonePath.toAbsolutePath().normalize(),
                FactoryHome.at(home).project(name))
    }

    /**
     * The clone {@code clonePath} is under the factory home rooted at {@code home}: the one the
     * registry already holds for it, or a new registration through the production registry — so a
     * spec that builds two runners over one clone registers it once, as an operator would.
     */
    static RegisteredClone resolvedOrRegistered(Path home, Path clonePath, String project = PROJECT) {
        def path = clonePath.toAbsolutePath().normalize()
        def registry = ProjectRegistry.scan(FactoryHome.at(home))
        def known = registry.projects().collectMany {
            it.clones()
        }.find {
            it.clonePath() == path
        }
        known ?: registry.add(new ProjectName(project), path)
    }

    /**
     * The clone as the composition root's lazily read {@code ObjectProvider<RegisteredClone>}
     * yields it, computed on the first read — the spec's clone may become a git working tree only
     * after the runner is built, as the loader resolves {@code --dir} only once a command runs.
     */
    static ObjectProvider<RegisteredClone> lazy(Supplier<RegisteredClone> clone) {
        def beans = new DefaultListableBeanFactory()
        beans.registerBeanDefinition('registeredClone', new RootBeanDefinition(RegisteredClone, clone))
        beans.getBeanProvider(RegisteredClone)
    }

    /** The clone as the composition root's {@code ObjectProvider<RegisteredClone>} yields it. */
    static ObjectProvider<RegisteredClone> provider(RegisteredClone clone) {
        def beans = new DefaultListableBeanFactory()
        beans.registerSingleton('registeredClone', clone)
        beans.getBeanProvider(RegisteredClone)
    }

    /**
     * The clone as a project-scoped command takes it: the production {@link ProjectScope} over the
     * {@link #provider}, minting instance ids under {@code instanceName} (FR3, FR10 of
     * add-project-registry).
     */
    static ProjectScope scope(RegisteredClone clone, String instanceName = 'test-instance') {
        new ProjectScope(provider(clone), new FactoryProperties(instanceName, null, null, null))
    }

    /** {@link #scope} over a clone computed on the first read, as {@link #lazy} yields it. */
    static ProjectScope lazyScope(Supplier<RegisteredClone> clone, String instanceName = 'test-instance') {
        new ProjectScope(lazy(clone), new FactoryProperties(instanceName, null, null, null))
    }
}

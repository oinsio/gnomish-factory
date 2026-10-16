package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.app.port.workspace.WorkspaceFactory
import com.github.oinsio.gnomish.domain.engine.port.BuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.port.CommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

/**
 * The seams FR12 of split-into-modules inverted: a use case in {@code :application} that needs a
 * git subprocess, the console, pipeline loading, a workspace, a check runner or the
 * container-availability probe names a port its own layer owns, and an adapter outside the use-case
 * layers realizes it. {@code ApplicationLayeringSpec} keeps the adapters out of {@code :application};
 * this spec pins the other half — the ports exist where the use cases are, and adapters fill them.
 */
class UseCasePortSpec extends Specification {

    /** One port per collaborator kind FR12 names (the git surface is three role ports). */
    private static final List<Class<?>> USE_CASE_PORTS = [
        TaskStoreGit,
        TaskBranchGit,
        TaskWorktreeGit,
        PipelineSource,
        CommandCheckRunner,
        BuiltinCheckRunner,
        ContainerRuntimeProbe
    ]

    /**
     * The collaborators whose concrete adapter use cases used to construct themselves (finding
     * F119), and the factory port the composition root now opens the workspace through (design D12
     * of tighten-spec-contract). The two adapters, {@code SystemConsoleIO} and {@code
     * DirectoryWorkspace}, are classes this spec keeps in {@code :application}: the check runners,
     * the agent and git adapters and the shared test fixtures read them by type and cannot reach
     * {@code :bootstrap}. What this list pins is that only the composition root constructs them.
     */
    private static final List<Class<?>> SELF_SUPPLIED_PORTS = [
        ConsoleIO,
        Workspace,
        WorkspaceFactory
    ]

    /** The layers that own use cases and their ports. */
    private static final List<String> USE_CASE_MODULES = ['/application/', '/domain/']

    /** Production bytecode of every module; the shared fixtures' jar is test code and stays out. */
    @Shared
    JavaClasses production = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .withImportOption({ location ->
        !location.contains('/test-fixtures/')
    } as ImportOption)
    .importPackages('com.github.oinsio.gnomish')

    // FR12 of split-into-modules: the port is owned by the use-case layer, the adapter supplied
    //     by the composition root
    // Scenario: build-system/module-layering / A use case reaches its collaborator through a port
    def "#port.simpleName is a use-case-layer port that a use case names and the composition root supplies"() {
        expect:
        suppliedThroughPort(port)

        where:
        port << USE_CASE_PORTS
    }

    // Scenario: build-system/module-layering / A use case reaches its collaborator through a port
    def "#port.simpleName is supplied by the composition root, not constructed by a use case"() {
        expect:
        suppliedThroughPort(port)

        where:
        port << SELF_SUPPLIED_PORTS
    }

    // FR12 of split-into-modules: a port sized to the use case declares nothing only its own adapter
    //     uses — a member no other class calls is a mirror of the adapter's surface
    // Scenario: build-system/module-layering / An inverted seam is the smallest capability
    def "every method #port.simpleName declares is called by a class other than its adapters"() {
        given:
        def type = production.get(port.name)

        when:
        def unused = type.methods.findAll { method ->
            !method.modifiers.contains(JavaModifier.STATIC) && !production.any { caller ->
                !caller.isAssignableTo(port.name) && caller.methodCallsFromSelf.any {
                    it.name == method.name && it.target.owner.isAssignableTo(port.name)
                }
            }
        }*.name

        then:
        unused == []

        where:
        port << (USE_CASE_PORTS + SELF_SUPPLIED_PORTS).findAll {
            !it.declaredMethods.toList().isEmpty()
        }
    }

    /** The port is a use-case-layer interface a use case names, and no use case builds its adapter. */
    private boolean suppliedThroughPort(Class<?> port) {
        def type = production.get(port.name)
        assert type.interface && inUseCaseLayer(type): 'a port interface owned by :application or :domain'
        assert production.any {
            inUseCaseLayer(it) && it != type && dependsOn(it, type)
        }: 'a use case names it'
        assert production.any {
            locationOf(it).contains('/bootstrap/') && dependsOn(it, type)
        } ||
        production.any {
            !it.interface && !inUseCaseLayer(it) && it.isAssignableTo(port.name)
        }:
        'the composition root names the port, or an adapter outside the use-case layers implements it'
        def constructing = production.findAll { caller ->
            inUseCaseLayer(caller) && caller.constructorCallsFromSelf.any {
                !it.targetOwner.interface && it.targetOwner.isAssignableTo(port.name) &&
                !decorates(it.targetOwner, port)
            }
        }*.name
        assert constructing == []: 'no use case constructs a concrete adapter of the port itself'
        true
    }

    /**
     * A decorator of the port wraps an adapter it is handed and supplies none of its own
     * (`RemoteOutageSignalingTaskBranchGit`, applied at the composition root through
     * `RemoteOutageGates.signaling`, finding F88): every constructor takes the port itself.
     */
    private static boolean decorates(JavaClass type, Class<?> port) {
        !type.constructors.isEmpty() && type.constructors.every { constructor ->
            constructor.rawParameterTypes.any { it.name == port.name }
        }
    }

    private static boolean inUseCaseLayer(JavaClass type) {
        USE_CASE_MODULES.any { locationOf(type).contains(it) }
    }

    /** Where a class was loaded from: a module's jar or class folder. */
    private static String locationOf(JavaClass type) {
        type.source.map { it.uri.toString() }.orElse('')
    }

    private static boolean dependsOn(JavaClass from, JavaClass target) {
        from.directDependenciesFromSelf.any { it.targetClass == target }
    }
}

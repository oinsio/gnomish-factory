package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.app.ContainerRunSupport
import com.github.oinsio.gnomish.app.ContainerSupports
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.environment.BoxGitLink
import com.github.oinsio.gnomish.sandbox.environment.BoxTiming
import com.github.oinsio.gnomish.sandbox.environment.ContainerEnvironmentFactory
import com.github.oinsio.gnomish.sandbox.environment.ContainerEnvironments
import com.github.oinsio.gnomish.sandbox.environment.ContainerTaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode
import com.github.oinsio.gnomish.sandbox.environment.TaskContainerSettings
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import spock.lang.Specification

/**
 * FR19, FR20 of make-checkpoint-gate-durable: the container round environment is built in two
 * halves — the installation's box equipment (the sandbox settings, the box timing, the guard
 * config root, the ownership mode) fixed once per ownership mode, and a task's inputs (the
 * environment key, the box git link, the child-env allowlist, the project identity, the denial
 * restoration read) passed per task. Read by reflection over every constructor and static factory
 * of the classes that build a round environment or a run's container support, typed by the
 * parameter types that carry each half.
 */
class ContainerEquipmentSplitSpec extends Specification {

    /** The parameter types that carry the installation's box equipment. */
    static final Set<Class<?>> INSTALLATION = [
        SandboxProperties,
        BoxTiming,
        TaskContainerSettings,
        OwnershipMode
    ] as Set

    /** The parameter types that carry a task's inputs (the restoration read by its generic type). */
    static final Set<Class<?>> TASK = [BoxGitLink, ChildEnvAllowlist] as Set

    /**
     * The chain's classes. {@code BoxEquipment} is the installation half as one value (design D12
     * of tighten-spec-contract): scanning its constructor too keeps a task input from riding into
     * it, which would move the mixed parameter list into the record instead of removing it.
     */
    static final List<Class<?>> BUILDERS = [
        Class.forName('com.github.oinsio.gnomish.sandbox.environment.BoxEquipment'),
        ContainerEnvironmentFactory,
        ContainerEnvironments,
        Class.forName('com.github.oinsio.gnomish.sandbox.environment.ContainerEnvironmentBuilder'),
        ContainerTaskExecutionEnvironment,
        ContainerRunSupport,
        ContainerSupports,
    ]

    private static boolean takesInstallation(Executable e) {
        e.parameterTypes.any { it in INSTALLATION }
    }

    private static boolean takesTaskInput(Executable e) {
        e.parameterTypes.any {
            it in TASK
        } || e.genericParameterTypes.any {
            it.typeName.contains('DenialRestoration')
        }
    }

    private static List<Executable> constructionPaths(Class<?> type) {
        (type.declaredConstructors as List<Executable>) + type.declaredMethods.findAll {
            Modifier.isStatic(it.modifiers) && !it.synthetic && it.returnType in BUILDERS
        }
    }

    // Scenario: sandbox/execution-environment / No constructor mixes installation equipment and task inputs
    def "FR20: no constructor or static factory of the round-environment chain takes both halves in one parameter list"() {
        when:
        def mixed = BUILDERS.collectMany { constructionPaths(it) }
        .findAll { takesInstallation(it) && takesTaskInput(it) }
        .collect {
            "${it.declaringClass.simpleName}(${it.parameterTypes*.simpleName.join(', ')})".toString()
        }

        then: 'the scan saw both halves somewhere, and never together'
        BUILDERS.collectMany {
            constructionPaths(it)
        }.any {
            takesInstallation(it)
        }
        BUILDERS.collectMany {
            constructionPaths(it)
        }.any {
            takesTaskInput(it)
        }
        mixed == []

        and: 'the run-support factory holds the installation settings as fixed state: its constructor takes them'
        ContainerSupports.declaredConstructors.every {
            it.parameterTypes.contains(SandboxProperties)
        }
    }
}

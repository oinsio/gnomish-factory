package com.github.oinsio.gnomish.build

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.process.ProcessForkOptions

/**
 * Keeps the factory's own variables out of every forked test JVM (design D14 of
 * make-checkpoint-gate-durable; implements FR22 of make-checkpoint-gate-durable): {@code
 * test-environment-hygiene-conventions}, applied by {@code test-conventions}, hands it every
 * {@code Test} task and the {@code pitest} task, beside {@link AdversarialGitConfig#applyTo}.
 *
 * <p>Gradle's {@code Test.environment} defaults to the environment of the build process, so a
 * build launched by a gnome — whose agent process holds {@code GNOMISH_DECISION_FILE} for its
 * round — forked test JVMs that saw the gnome's decision file, and a fixture that inherited it
 * wrote a scenario's decision into the real round. Subtractive, not an allowlist: the test JVM
 * legitimately needs the machine's Docker, Gradle and Testcontainers variables, and listing the
 * operator's tooling here would be a maintenance list. The only {@code GNOMISH_*} variables a test
 * sees are the ones its own spawner sets on the child process it starts.
 *
 * <p><b>"Not set by the build" is decided by timing.</b> The strip runs in a configuration action
 * registered by a convention plugin, which a module applies in its {@code plugins} block — before
 * any line of the module's build script can configure the task. At that moment the map holds
 * only what the task inherited, so everything removed is inherited, and a {@code GNOMISH_*} entry a
 * build script sets later survives ({@code TestEnvironmentHygieneFunctionalSpec} pins both).
 *
 * <p><b>Why at configuration time and not in a {@code doFirst}.</b> Measured on Gradle 9.7 with the
 * configuration cache this build enables: a task whose environment was modified while it was
 * configured — {@link AdversarialGitConfig} already modifies every one of these — has its whole
 * map stored in the cache entry, and a reuse forks with that stored map, not with the launching
 * environment. So the map stored after this strip carries no {@code GNOMISH_*} key, and a variable
 * present only in the environment of a later, cache-reusing build never enters it. PIT's minions
 * are forked by the {@code pitest} JVM without clearing its environment, so one strip on that task
 * reaches every minion.
 */
final class TestEnvironmentHygiene {

    /** Every variable the factory reads or hands its children is named under this prefix. */
    static final String PREFIX = 'GNOMISH_'

    private TestEnvironmentHygiene() {
    }

    /**
     * Removes every inherited {@code GNOMISH_*} variable from {@code task}'s forked JVM. {@code T}
     * is a {@code Test} or the {@code pitest} task — anything that forks a JVM.
     */
    static <T extends Task & ProcessForkOptions> void applyTo(Project project, T task) {
        Set<String> inherited = task.environment.keySet().findAll { it.startsWith(PREFIX) }
        if (!inherited.isEmpty()) {
            task.environment.keySet().removeAll(inherited)
            project.logger.info('{}: not forwarding inherited {}', task.path, inherited.sort())
        }
    }
}

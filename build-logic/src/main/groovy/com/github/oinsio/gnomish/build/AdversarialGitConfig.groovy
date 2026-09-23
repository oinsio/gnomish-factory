package com.github.oinsio.gnomish.build

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.ProcessForkOptions

/**
 * The one place the build names the committed adversarial global git configuration (design D11
 * of own-git-transfer-argv; FR12, NFR-S2): {@code test-conventions} applies it to every
 * {@code Test} task and {@code pitest-conventions} to the {@code pitest} task, so the whole test
 * build — the test JVM, every git subprocess a fixture runs, PIT's minions (forked from a
 * {@code JavaExec} they inherit from), and the packaged jar the E2E harness spawns — runs under
 * it instead of under the developer's {@code ~/.gitconfig}.
 *
 * <p>The JVM cannot set its own environment, which is why this is a build concern and not a
 * fixture's: the file is handed to git through {@code GIT_CONFIG_GLOBAL}, the variable git reads
 * in place of {@code $HOME/.gitconfig} and {@code $XDG_CONFIG_HOME/git/config}.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.adapter.git.AdversarialGitConfig} in
 * {@code :test-fixtures}: both name the same variable ({@code VARIABLE}) and the same committed
 * file ({@code RELATIVE_PATH}). The two share no classpath — the build cannot load a test
 * fixture — so the constants are spelled twice; the fixture owns the meaning of the keys and
 * offers {@code assertInEffect}, and its spec asserts that what git lists as the global
 * configuration equals the resource, so a divergence here fails that spec rather than passing
 * silently. Listed in {@code .claude/rules/manual-sync-pairs.md} under "no shared classpath".
 *
 * <p>Gradle does not treat a forked JVM's environment as a task input, so the file is declared
 * as one explicitly (by content, not by path, so the declaration costs no cache hit on another
 * checkout): a key added to the file re-runs every suite that runs git under it.
 *
 * <p>The same tasks also inherit a per-process configuration ({@code GIT_CONFIG_COUNT} with one
 * harmless key), the third medium an operator's environment can inject configuration through:
 * every git command a spec runs sees it, so the spec asserting that a transfer strips it
 * ({@code GitProcessRunnerTransferSpec}, FR6 of own-git-transfer-argv) observes a real
 * difference between the untyped and the typed entry rather than an unset that was never set.
 */
final class AdversarialGitConfig {

    /** Relative to the root project — the same file every module's tasks point at. */
    static final String RELATIVE_PATH = 'test-fixtures/src/main/resources/adversarial-gitconfig'

    static final String VARIABLE = 'GIT_CONFIG_GLOBAL'

    /** The inherited per-process configuration: one key no git command reads, present everywhere. */
    static final Map<String, String> INHERITED_CONFIG = [
        GIT_CONFIG_COUNT: '1',
        GIT_CONFIG_KEY_0: 'gnomish.inheritedProbe',
        GIT_CONFIG_VALUE_0: 'present',
    ].asImmutable()

    private AdversarialGitConfig() {
    }

    /**
     * Points {@code task}'s forked JVM at the committed file and declares the file as the task's
     * input. {@code T} is a {@code Test} or the {@code pitest} task — anything that forks a JVM.
     */
    static <T extends Task & ProcessForkOptions> void applyTo(Project project, T task) {
        File file = project.rootProject.file(RELATIVE_PATH)
        task.environment(VARIABLE, file.absolutePath)
        task.environment(INHERITED_CONFIG)
        task.inputs.file(file)
                .withPropertyName('adversarialGitConfig')
                .withPathSensitivity(PathSensitivity.NONE)
    }
}

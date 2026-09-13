package com.github.oinsio.gnomish.build

import org.gradle.testkit.runner.GradleRunner

import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared TestKit plumbing for this module's functional specs: the outer build's Gradle user
 * home, a hermetic offline runner, and the tiny file-writing helper every mini fixture needs.
 *
 * <p>Kept here rather than duplicated per spec — {@code build-logic} is a standalone included
 * build, so this cannot move outside {@code build-logic/src/functionalTest}.
 */
final class GradleRunnerSupport {

    private GradleRunnerSupport() {
    }

    /** A hermetic, offline runner for {@code projectDir} invoking {@code tasks}. */
    static GradleRunner runner(Path projectDir, String... tasks) {
        GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments([*tasks, '--offline', '--stacktrace', '-g', gradleUserHome()])
                .forwardOutput()
    }

    /** The outer build's Gradle user home — shared so `--offline` resolves from a warm cache. */
    static String gradleUserHome() {
        String home = System.getProperty('gnomish.gradleUserHome')
        assert home != null: 'functionalTest must pass -Dgnomish.gradleUserHome (see build-logic/build.gradle)'
        home
    }

    /** Writes {@code content} to {@code relativePath} under {@code projectDir}, creating parents. */
    static void writeFile(Path projectDir, String relativePath, String content) {
        Path target = projectDir.resolve(relativePath)
        Files.createDirectories(target.parent)
        target.text = content
    }
}

package com.github.oinsio.gnomish.build

import org.gradle.api.tasks.Internal
import org.gradle.process.CommandLineArgumentProvider

/**
 * Hands a Test JVM the directory its log-expectation evidence goes in (FR17 of
 * harden-logging-observability), without making that directory part of the task's inputs.
 *
 * <p>A plain {@code systemProperty} would be an input, and this one is an absolute path: it
 * changes with the checkout directory, so every Test task in the build would miss the build cache
 * on a different machine — or in a second worktree on the same one. Measured here: adding the
 * property re-executed suites that had been served from cache for weeks, which is how
 * {@code ProcessSupervisorTreeKillSpec}'s load-sensitivity surfaced. A
 * {@link CommandLineArgumentProvider} whose only field is {@link Internal} is Gradle's own answer
 * — the value reaches the JVM, and up-to-date checks never see it.
 *
 * <p>It really is not an input: the directory holds this run's output, and nothing about the
 * task's result depends on where it was written.
 */
class LogExpectationEvidence implements CommandLineArgumentProvider {

    @Internal
    File directory

    @Override
    Iterable<String> asArguments() {
        ["-Dgnomish.logExpectationGate.dir=${directory.absolutePath}".toString()]
    }
}

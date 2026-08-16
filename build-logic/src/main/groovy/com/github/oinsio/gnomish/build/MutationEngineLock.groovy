package com.github.oinsio.gnomish.build

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Mutual exclusion over the machine's CPUs for PIT runs (task 8.1 of split-into-modules).
 * Registered build-wide with {@code maxParallelUsages = 1}, so no two modules mutate at the same
 * time; every other task keeps running in parallel with whichever module holds it.
 *
 * <p>The need appeared with the module split. A single {@code pitest} task already sizes itself to
 * the whole machine ({@code threads = maxWorkerCount - 2}), which was correct while one task owned
 * the build. With ten modules each carrying their own gate, {@code org.gradle.parallel} runs
 * several of those tasks concurrently and the minion count multiplies: on a 14-core box three
 * overlapping runs ask for 36 minion JVMs. The oversubscription does not merely slow the build, it
 * corrupts the gate — observed on this tree: {@code :adapters:git:pitest} exited 1 outright, and a
 * following run reported {@code RUN_ERROR} on a {@code GithubStateWrites} mutation whose minion
 * never got to run a test. Both modules pass alone and under this lock.
 *
 * <p>A lock rather than lowering {@code threads}: the per-task thread count is the right size for
 * the machine, what is wrong is running several such tasks at once. Serializing them keeps each
 * run at full speed instead of making every run permanently slower to survive an overlap.
 */
abstract class MutationEngineLock implements BuildService<BuildServiceParameters.None> {
}

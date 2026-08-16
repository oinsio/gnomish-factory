package com.github.oinsio.gnomish.build

import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * The machine's capacity for memory-heavy forked JVMs (D1 of adapt-build-load-to-hardware).
 * Registered build-wide with {@code maxParallelUsages} set to the budget computed in
 * {@code test-conventions}; every {@code Test} task and every module's {@code pitest} task takes one
 * slot, so at most that many 3 GB JVMs coexist (FR2). Tasks that fork nothing heavy — compilation,
 * Error Prone, Spotless — never touch this service and keep Gradle's default per-core parallelism
 * (G2).
 *
 * <p>A shared service rather than {@code org.gradle.workers.max}: the worker cap throttles the whole
 * task graph, and it cannot be set from build logic at all ({@code maxWorkerCount} is fixed before
 * settings evaluation). {@code Test.maxParallelForks} is the other near-miss — it bounds forks
 * inside one task, not concurrency across the modules' tasks, which is exactly what the module split
 * turned into a memory problem.
 *
 * <p>The service logs its decision when Gradle instantiates it, i.e. at first use during execution
 * (D6, NFR-O1). Logging from the convention script body instead would go silent on every
 * configuration-cache hit — precisely the runs where a stale {@code gnomish.heavyJvmSlots} override
 * on a machine is easiest to forget about (UX2).
 */
abstract class HeavyJvmBudget implements BuildService<Params> {

    interface Params extends BuildServiceParameters {
        /** Effective slot count — what {@code maxParallelUsages} was set to. */
        Property<Integer> getSlots()

        /** Detected total RAM, whole gigabytes. */
        Property<Integer> getTotalRamGb()

        /** Detected processor count. */
        Property<Integer> getProcessors()

        /** Where the slot count came from: the formula, or the override property naming its value. */
        Property<String> getSource()
    }

    HeavyJvmBudget() {
        def p = parameters
        Logging.getLogger(HeavyJvmBudget).lifecycle(
                "Heavy-JVM budget: {} concurrent test/mutation JVM(s) — detected {} GB RAM, {} cores; source: {}",
                p.slots.get(), p.totalRamGb.get(), p.processors.get(), p.source.get())
    }
}
